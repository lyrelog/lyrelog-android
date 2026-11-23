package com.lyrelog.core

/**
 * Fitting an event inside the ingest's limits (lyrelog: internal/events/events.go,
 * docs/ingest-api.md) before it is queued, so a line is never rejected later for something the
 * SDK could have fixed. The same policy as the Swift SDK's EventPreparation: never drop an event
 * for its shape. Long strings are cut on a character boundary, extra attributes (and names that
 * collide once cleaned) are dropped and counted in `lyrelog_dropped_attrs`, an oversized
 * payload is replaced by a short summary.
 */
internal object Shaping {
    const val MAX_MESSAGE_BYTES = 2048
    const val MAX_ID_BYTES = 128
    const val MAX_RESOURCE_BYTES = 64
    const val MAX_ATTRIBUTES = 200
    const val MAX_ATTRIBUTE_STRING_BYTES = 1024
    const val MAX_ARRAY_ITEMS = 64
    const val MAX_DEPTH = 4
    const val MAX_NAME_BYTES = 128
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val PREVIEW_BYTES = 1024
    const val DROPPED_MARKER = "lyrelog_dropped_attrs"
    /** The server rejects a longer line on its own; such an event is dropped and counted. */
    const val MAX_LINE_BYTES = 1 shl 20

    fun message(text: String): String = truncated(text, MAX_MESSAGE_BYTES).ifEmpty { "(empty)" }

    fun identifier(text: String?): String? =
        if (text.isNullOrEmpty()) null else truncated(text, MAX_ID_BYTES)

    fun resource(text: String): String = truncated(text, MAX_RESOURCE_BYTES)

    fun category(text: String?): String? =
        if (text.isNullOrEmpty()) null else truncated(text, MAX_RESOURCE_BYTES)

    fun attributes(input: Map<String, JsonValue>, redaction: Redaction = Redaction.DEFAULT): Map<String, JsonValue> {
        val redacted = redaction.applyFields(input)
        val state = Budget(MAX_ATTRIBUTES - 1) // room for the dropped marker
        val result = obj(redacted, 1, state)
        if (state.dropped == 0) return result
        return LinkedHashMap(result).also { it[DROPPED_MARKER] = JsonValue.Integer(state.dropped.toLong()) }
    }

    fun payload(value: JsonValue?, redaction: Redaction = Redaction.DEFAULT): JsonValue? {
        if (value == null) return null
        val clean = redaction.apply(value)
        val encoded = clean.encode()
        val bytes = utf8Length(encoded)
        if (bytes <= MAX_PAYLOAD_BYTES) return clean
        return JsonValue.Obj(
            linkedMapOf(
                "lyrelog_truncated" to JsonValue.Bool(true),
                "bytes" to JsonValue.Integer(bytes.toLong()),
                "preview" to JsonValue.Str(truncated(encoded, PREVIEW_BYTES)),
            ),
        )
    }

    private class Budget(var left: Int) {
        var dropped = 0
    }

    private fun obj(input: Map<String, JsonValue>, depth: Int, budget: Budget): Map<String, JsonValue> {
        val out = LinkedHashMap<String, JsonValue>()
        for (key in input.keys.sorted()) {
            val name = attributeName(key)
            val value = input.getValue(key)
            // `a.b` and `a_b`, or `_x` and `x`, become the same name: keep the first (in
            // sorted order, so always the same one) and count the rest.
            if (out.containsKey(name)) {
                budget.dropped += leafCount(value)
                continue
            }
            if (value is JsonValue.Obj && depth < MAX_DEPTH) {
                val child = obj(value.fields, depth + 1, budget)
                if (child.isNotEmpty()) out[name] = JsonValue.Obj(child)
            } else {
                if (budget.left <= 0) {
                    budget.dropped += leafCount(value)
                    continue
                }
                budget.left -= 1
                out[name] = leaf(value)
            }
        }
        return out
    }

    private fun leaf(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Str -> JsonValue.Str(truncated(value.value, MAX_ATTRIBUTE_STRING_BYTES))
        is JsonValue.Decimal -> if (value.value.isFinite()) value else JsonValue.Null
        is JsonValue.Integer, is JsonValue.Bool, JsonValue.Null, is JsonValue.Raw -> value
        // Attribute arrays hold strings only.
        is JsonValue.Arr -> JsonValue.Arr(
            value.items.take(MAX_ARRAY_ITEMS).map { JsonValue.Str(truncated(stringForm(it), MAX_ATTRIBUTE_STRING_BYTES)) },
        )
        // Deeper than MAX_DEPTH: kept as a JSON string instead of lost.
        is JsonValue.Obj -> JsonValue.Str(truncated(stringForm(value), MAX_ATTRIBUTE_STRING_BYTES))
    }

    private fun leafCount(value: JsonValue): Int =
        if (value is JsonValue.Obj) value.fields.values.sumOf { leafCount(it) } else 1

    /** Dots would mean nesting and a leading underscore is reserved: both are rewritten. */
    fun attributeName(key: String): String {
        val name = key.replace('.', '_').trimStart('_').ifEmpty { "key" }
        return truncated(name, MAX_NAME_BYTES)
    }

    fun stringForm(value: JsonValue): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Integer -> value.value.toString()
        is JsonValue.Decimal -> value.value.toString()
        is JsonValue.Raw -> value.text
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> "null"
        is JsonValue.Arr, is JsonValue.Obj -> value.encode()
    }

    fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** Cut to at most [maxBytes] of UTF-8 without splitting a character (or a surrogate pair). */
    fun truncated(text: String, maxBytes: Int): String {
        if (text.length <= maxBytes / 3 || utf8Length(text) <= maxBytes) return text
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            val size = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + size > maxBytes) break
            bytes += size
            i += if (pair) 2 else 1
        }
        return text.substring(0, i)
    }
}

/**
 * Secrets never leave the device (docs/sdk-spec.md rule 9, the same rule in every Lyrelog SDK):
 * a field whose name, lowercased and without `-`, `_`, `.` or spaces, is one of the keys or ends
 * with one is sent as "[redacted]", whatever its value (a whole object too). "X-Api-Key",
 * "csrf_token" and "Set-Cookie" match; "token_count" and "max_tokens" don't. Applied at every
 * depth of the attributes and the payload, inside arrays too, before anything is cut.
 *
 * `null` keys are [DEFAULT_KEYS]; an empty list turns redaction off.
 */
@InternalLyrelogApi
class Redaction(keys: List<String>?) {
    val keys: Set<String> = (keys ?: DEFAULT_KEYS).map(::normal).filter { it.isNotEmpty() }.toSet()

    fun isSecret(name: String): Boolean {
        if (keys.isEmpty()) return false
        val normal = normal(name)
        if (normal.isEmpty()) return false
        return keys.any { normal.endsWith(it) }
    }

    fun applyFields(fields: Map<String, JsonValue>): Map<String, JsonValue> {
        if (keys.isEmpty()) return fields
        val out = LinkedHashMap<String, JsonValue>()
        for ((k, v) in fields) out[k] = if (isSecret(k)) MARKER else apply(v)
        return out
    }

    fun apply(value: JsonValue): JsonValue {
        if (keys.isEmpty()) return value
        return when (value) {
            is JsonValue.Obj -> JsonValue.Obj(applyFields(value.fields))
            is JsonValue.Arr -> JsonValue.Arr(value.items.map(::apply))
            else -> value
        }
    }

    companion object {
        /** The same list in every Lyrelog SDK. */
        val DEFAULT_KEYS: List<String> = listOf(
            "authorization", "cookie", "password", "passwd", "secret", "token", "apikey", "privatekey",
            "credential", "credentials", "accesskey", "signature",
        )
        val MARKER = JsonValue.Str("[redacted]")
        val DEFAULT = Redaction(null)

        fun normal(name: String): String =
            name.lowercase(java.util.Locale.ROOT).filterNot { it == '-' || it == '_' || it == '.' || it == ' ' }
    }
}
