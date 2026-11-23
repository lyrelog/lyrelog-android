package com.lyrelog.core

/**
 * A small JSON model, so the SDK needs nothing beyond the Kotlin standard library. Values
 * from the app are copied into it on the caller's thread ([JsonValue.from]), so a map the app
 * changes afterwards can't change an event already logged.
 */
@InternalLyrelogApi
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    /** An integer kept exact. */
    data class Integer(val value: Long) : JsonValue()
    /** A finite double (non-finite values become [Null]). */
    data class Decimal(val value: Double) : JsonValue()
    /** A number written as given (BigInteger, BigDecimal), already checked to be valid JSON. */
    data class Raw(val text: String) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue() {
        override fun toString() = "Null"
    }
    data class Arr(val items: List<JsonValue>) : JsonValue()
    /** Keys in insertion order. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    fun encode(): String = StringBuilder().also { Json.write(this, it) }.toString()

    companion object {
        /** Deeper than this, a value is kept as its text: it also stops a map that contains itself. */
        const val MAX_COPY_DEPTH = 32

        /**
         * Copies any value into JSON: strings, numbers, booleans, null, maps (keys as text),
         * lists, sets, arrays, enums (their name); anything else as its `toString()`. Never
         * throws: a value whose `toString()` throws (even an Error) becomes its class name.
         */
        fun from(value: Any?, depth: Int = 0): JsonValue {
            return try {
                convert(value, depth)
            } catch (e: Throwable) {
                // An Exception, a StackOverflowError, an app's TODO() (NotImplementedError)...
                Str(value?.javaClass?.name ?: "null")
            }
        }

        private fun convert(value: Any?, depth: Int): JsonValue {
            if (depth > MAX_COPY_DEPTH) return Str(safeText(value))
            return when (value) {
                null -> Null
                is JsonValue -> value
                is String -> Str(value)
                is CharSequence -> Str(value.toString())
                is Char -> Str(value.toString())
                is Boolean -> Bool(value)
                is Int -> Integer(value.toLong())
                is Long -> Integer(value)
                is Short -> Integer(value.toLong())
                is Byte -> Integer(value.toLong())
                is Double -> if (value.isFinite()) Decimal(value) else Null
                // 0.1f is 0.10000000149011612 as a double; its own text is what the app meant.
                is Float -> if (value.isFinite()) Decimal(value.toString().toDouble()) else Null
                is java.math.BigInteger -> Raw(value.toString())
                is java.math.BigDecimal -> Raw(value.toString().let { if (Json.isNumber(it)) it else value.toDouble().toString() })
                is Number -> value.toDouble().let { if (it.isFinite()) Decimal(it) else Null }
                is Map<*, *> -> {
                    val out = LinkedHashMap<String, JsonValue>()
                    for ((k, v) in value.entries.toList()) out[k.toString()] = convert(v, depth + 1)
                    Obj(out)
                }
                is Iterable<*> -> Arr(value.toList().map { convert(it, depth + 1) })
                is Array<*> -> Arr(value.map { convert(it, depth + 1) })
                is IntArray -> Arr(value.map { Integer(it.toLong()) })
                is LongArray -> Arr(value.map { Integer(it) })
                is DoubleArray -> Arr(value.map { convert(it, depth + 1) })
                is FloatArray -> Arr(value.map { convert(it, depth + 1) })
                is BooleanArray -> Arr(value.map { Bool(it) })
                is ShortArray -> Arr(value.map { Integer(it.toLong()) })
                is ByteArray -> Arr(value.map { Integer(it.toLong()) })
                is CharArray -> Str(String(value))
                is Enum<*> -> Str(value.name)
                else -> Str(safeText(value))
            }
        }

        private fun safeText(value: Any?): String = try {
            value.toString()
        } catch (e: Throwable) {
            value?.javaClass?.name ?: "null"
        }
    }
}

/** JSON writing and a small strict parser (for the server's answers and the tests). */
internal object Json {
    fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Str -> string(value.value, out)
            is JsonValue.Integer -> out.append(value.value)
            is JsonValue.Decimal -> out.append(number(value.value))
            is JsonValue.Raw -> out.append(value.text)
            is JsonValue.Bool -> out.append(value.value)
            JsonValue.Null -> out.append("null")
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    string(k, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
        }
    }

    private fun number(value: Double): String {
        if (!value.isFinite()) return "null"
        // Whole doubles stay doubles ("3.0"), as the other SDKs send them; Kotlin's "1.0E10" is valid JSON.
        return value.toString()
    }

    fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (c < ' ') {
                    out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun isNumber(text: String) = numberPattern.matches(text)

    /** Parses one JSON value; null when the text isn't exactly one valid value. */
    fun parse(text: String): JsonValue? = try {
        Parser(text).run {
            val value = value(0)
            skipSpace()
            if (pos != text.length) null else value
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Parser(val text: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun value(depth: Int): JsonValue {
            require(depth < 128)
            skipSpace()
            return when (text[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(str())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> num()
            }
        }

        fun literal(word: String, value: JsonValue): JsonValue {
            require(text.startsWith(word, pos))
            pos += word.length
            return value
        }

        fun obj(depth: Int): JsonValue {
            pos++
            val out = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (text[pos] == '}') {
                pos++
                return JsonValue.Obj(out)
            }
            while (true) {
                skipSpace()
                require(text[pos] == '"')
                val key = str()
                skipSpace()
                require(text[pos] == ':')
                pos++
                out[key] = value(depth + 1)
                skipSpace()
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(out)
                    else -> throw IllegalArgumentException()
                }
            }
        }

        fun arr(depth: Int): JsonValue {
            pos++
            val out = ArrayList<JsonValue>()
            skipSpace()
            if (text[pos] == ']') {
                pos++
                return JsonValue.Arr(out)
            }
            while (true) {
                out.add(value(depth + 1))
                skipSpace()
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(out)
                    else -> throw IllegalArgumentException()
                }
            }
        }

        fun str(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException()
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException()
                    else -> out.append(c)
                }
            }
        }

        fun num(): JsonValue {
            val start = pos
            while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
            val raw = text.substring(start, pos)
            require(isNumber(raw))
            if (raw.none { it in ".eE" }) raw.toLongOrNull()?.let { return JsonValue.Integer(it) }
            return JsonValue.Decimal(raw.toDouble())
        }
    }
}

/** Field access for parsed JSON. */
operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(key)

@InternalLyrelogApi
val JsonValue?.string: String? get() = (this as? JsonValue.Str)?.value
