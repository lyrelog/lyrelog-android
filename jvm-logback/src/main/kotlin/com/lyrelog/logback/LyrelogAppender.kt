package com.lyrelog.logback

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.lyrelog.jvm.Configuration
import com.lyrelog.Level
import com.lyrelog.jvm.Lyrelog
import com.lyrelog.core.Exceptions

/**
 * Sends SLF4J and Logback records (Spring Boot's default logging) to Lyrelog.
 *
 * ```xml
 * <appender name="LYRELOG" class="com.lyrelog.logback.LyrelogAppender" />
 * <root level="INFO"><appender-ref ref="LYRELOG" /></root>
 * ```
 *
 * - the logger's name is the category (`com.example.billing.InvoiceService`), unless
 *   [category] is set or the record carries a `category` key;
 * - the formatted message is the message; with arguments, the pattern goes to
 *   `attrs.msg_template` so you can group by it;
 * - MDC entries and SLF4J 2 key-value pairs (`atInfo().addKeyValue(...)`) become attributes,
 *   except `user_id` (the event's user) and `category`;
 * - an exception becomes `error_type`, `error_message` and the payload's `exception`;
 * - TRACE, DEBUG, INFO, WARN and ERROR map to the Lyrelog level of the same name (`warning` for WARN);
 * - the record's time is the event's time.
 *
 * It forwards to the running [Lyrelog]: start it in code, or give the appender an [apiKey]
 * (`<apiKey>${LYRELOG_KEY}</apiKey>`) and it starts Lyrelog itself, with [endpoint],
 * [environment] and [platform], and closes it when Logback stops. Records from the SDK's own
 * threads and from `com.lyrelog` loggers are ignored, so it can never loop.
 */
class LyrelogAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {
    /** Starts Lyrelog with this key when it isn't running yet. */
    var apiKey: String? = null
    var endpoint: String? = null
    var environment: String? = null
    var platform: String? = null

    /** A fixed category for every record, instead of the logger's name. */
    var category: String? = null

    /** Send MDC entries as attributes (default true). */
    var isIncludeMdc: Boolean = true

    private var startedHere = false
    private val inside = ThreadLocal<Boolean>()

    override fun start() {
        // Logback writes an unset `${LYRELOG_KEY}` as `LYRELOG_KEY_IS_UNDEFINED`: no key, as in code.
        val key = apiKey?.trim().orEmpty().takeUnless { it.endsWith("_IS_UNDEFINED") }.orEmpty()
        if (key.isNotEmpty() && !Lyrelog.isStarted) {
            startedHere = Lyrelog.start(
                Configuration.builder(key)
                    .endpoint(endpoint?.trim()?.ifEmpty { null } ?: Configuration.DEFAULT_ENDPOINT)
                    .environment(environment?.trim()?.ifEmpty { null })
                    .platform(platform?.trim()?.ifEmpty { null })
                    .build(),
            )
        }
        super.start()
    }

    override fun stop() {
        if (startedHere) {
            startedHere = false
            Lyrelog.close()
        }
        super.stop()
    }

    override fun append(event: ILoggingEvent) {
        if (inside.get() == true) return
        val loggerName = event.loggerName.orEmpty()
        if (loggerName == "com.lyrelog" || loggerName.startsWith("com.lyrelog.")) return
        // The record's thread, not the current one: behind an AsyncAppender they differ.
        if (event.threadName?.startsWith("lyrelog-") == true) return
        if (!Lyrelog.isStarted) return
        inside.set(true)
        try {
            forward(event, loggerName)
        } catch (e: Exception) {
            // never throw into the code that logged
        } finally {
            inside.remove()
        }
    }

    private fun forward(event: ILoggingEvent, loggerName: String) {
        val attrs = LinkedHashMap<String, Any?>()
        var user: String? = null
        var recordCategory: String? = null
        fun take(key: String?, value: Any?) {
            when (key) {
                null, "" -> {}
                "user_id" -> user = value?.toString()
                "category" -> recordCategory = value?.toString()
                else -> attrs[key] = value
            }
        }
        if (isIncludeMdc) event.mdcPropertyMap?.forEach { (k, v) -> take(k, v) }
        event.keyValuePairs?.forEach { take(it.key, it.value) }
        val message = event.formattedMessage ?: event.message ?: ""
        if (!event.argumentArray.isNullOrEmpty() && event.message != null && event.message != message) {
            attrs.putIfAbsent("msg_template", event.message)
        }
        var payload: Map<String, Any?>? = null
        val error = (event.throwableProxy as? ThrowableProxy)?.throwable
        if (error != null) {
            attrs.putAll(Exceptions.attributes(error))
            payload = mapOf("exception" to Exceptions.payload(error))
        } else if (event.throwableProxy != null) {
            val proxy = event.throwableProxy
            attrs["error_type"] = proxy.className
            attrs["error_message"] = proxy.message.orEmpty()
        }
        Lyrelog.log(
            level(event.level),
            message.ifEmpty { "log" },
            category ?: recordCategory ?: loggerName.ifEmpty { null },
            attrs,
            payload,
            user,
            event.timeStamp,
        )
    }

    companion object {
        @JvmStatic
        fun level(level: LogbackLevel?): Level = when {
            level == null -> Level.INFO
            level.toInt() >= LogbackLevel.ERROR_INT -> Level.ERROR
            level.toInt() >= LogbackLevel.WARN_INT -> Level.WARNING
            level.toInt() >= LogbackLevel.INFO_INT -> Level.INFO
            level.toInt() >= LogbackLevel.DEBUG_INT -> Level.DEBUG
            else -> Level.TRACE
        }
    }
}
