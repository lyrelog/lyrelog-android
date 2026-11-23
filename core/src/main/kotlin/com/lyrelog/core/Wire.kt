package com.lyrelog.core

import com.lyrelog.Level
import java.util.UUID

/** The app and device, the same for every event of this launch (docs/ingest-api.md `resource`). */
@InternalLyrelogApi
data class Resource(
    val appId: String = "",
    val appVersion: String = "",
    val appBuild: String = "",
    val osName: String = "",
    val osVersion: String = "",
    val deviceModel: String = "",
    val locale: String = "",
    val sdk: String = "",
    val environment: String = "production",
    val platform: String = "",
) {
    /** Only the ten fields the server knows, each at most 64 bytes; empty ones left out. */
    fun toJson(): JsonValue.Obj {
        val out = LinkedHashMap<String, JsonValue>()
        fun put(name: String, value: String) {
            if (value.isNotEmpty()) out[name] = JsonValue.Str(Shaping.resource(value))
        }
        put("app_id", appId)
        put("app_version", appVersion)
        put("app_build", appBuild)
        put("os_name", osName)
        put("os_version", osVersion)
        put("device_model", deviceModel)
        put("locale", locale)
        put("sdk", sdk)
        put("environment", Names.clean(environment) ?: "production")
        Names.clean(platform)?.let { out["platform"] = JsonValue.Str(it) }
        return JsonValue.Obj(out)
    }
}

@InternalLyrelogApi
object Names {
    private val valid = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    fun isValid(name: String) = valid.matches(name)

    /** Makes any name a valid environment or platform ("Staging EU" -> "staging-eu"); empty is null. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val lowered = raw.lowercase(java.util.Locale.ROOT).map { c ->
            if ((c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-') c else '-'
        }.joinToString("")
        val name = lowered.trim('-', '_').take(32).trimEnd('-', '_')
        return name.ifEmpty { null }
    }
}

/** An event as captured on the caller's thread: values already copied, nothing encoded yet. */
internal class PendingEvent(
    val id: UUID,
    val timestampMillis: Long,
    val level: Level,
    val category: String?,
    val message: String,
    val attributes: Map<String, JsonValue>,
    val payload: JsonValue?,
    val userId: String?,
    val sessionId: String?,
)

/** One NDJSON line, exactly what the ingest expects. */
internal object WireEvent {
    fun encode(event: PendingEvent, installId: String?, resource: Resource, redaction: Redaction): String {
        val out = LinkedHashMap<String, JsonValue>()
        out["event_id"] = JsonValue.Str(event.id.toString().lowercase(java.util.Locale.ROOT))
        out["ts"] = JsonValue.Str(Timestamps.format(event.timestampMillis))
        out["level"] = JsonValue.Str(event.level.wireName)
        Shaping.category(event.category)?.let { out["category"] = JsonValue.Str(it) }
        out["message"] = JsonValue.Str(Shaping.message(event.message))
        Shaping.identifier(event.userId)?.let { out["user_id"] = JsonValue.Str(it) }
        Shaping.identifier(installId)?.let { out["install_id"] = JsonValue.Str(it) }
        Shaping.identifier(event.sessionId)?.let { out["session_id"] = JsonValue.Str(it) }
        val attrs = Shaping.attributes(event.attributes, redaction)
        if (attrs.isNotEmpty()) out["attrs"] = JsonValue.Obj(attrs)
        Shaping.payload(event.payload, redaction)?.let { out["payload"] = it }
        out["resource"] = resource.toJson()
        return JsonValue.Obj(out).encode()
    }
}

/**
 * RFC 3339 in UTC with milliseconds ("2026-11-19T10:00:00.123Z"), without java.time (Android
 * 24 doesn't have it without desugaring) and without a shared, thread-unsafe formatter.
 */
internal object Timestamps {
    fun format(millis: Long): String {
        val days = Math.floorDiv(millis, 86_400_000L)
        val inDay = Math.floorMod(millis, 86_400_000L)
        // Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val h = inDay / 3_600_000
        val m = inDay / 60_000 % 60
        val s = inDay / 1000 % 60
        val ms = inDay % 1000
        return StringBuilder(24)
            .append(pad(year, 4)).append('-').append(pad(month, 2)).append('-').append(pad(day, 2))
            .append('T').append(pad(h, 2)).append(':').append(pad(m, 2)).append(':').append(pad(s, 2))
            .append('.').append(pad(ms, 3)).append('Z').toString()
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
}

/**
 * Roughly what an event waiting in memory holds: two bytes a character (a Java string's worst
 * case) plus a little per value. Strings aren't read, only measured, so it costs nothing for a
 * long one.
 */
internal object Estimate {
    private const val PER_VALUE = 16L

    fun bytes(event: PendingEvent): Long {
        var total = 128L + 2L * event.message.length + 2L * (event.category?.length ?: 0)
        for ((k, v) in event.attributes) total += 2L * k.length + of(v)
        event.payload?.let { total += of(it) }
        return total
    }

    fun of(value: JsonValue): Long = when (value) {
        is JsonValue.Str -> PER_VALUE + 2L * value.value.length
        is JsonValue.Raw -> PER_VALUE + 2L * value.text.length
        is JsonValue.Arr -> value.items.fold(PER_VALUE) { sum, item -> sum + of(item) }
        is JsonValue.Obj -> value.fields.entries.fold(PER_VALUE) { sum, (k, v) -> sum + 2L * k.length + of(v) }
        else -> PER_VALUE
    }
}
