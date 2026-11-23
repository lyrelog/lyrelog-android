package com.lyrelog.jvm

import com.lyrelog.Level
import java.io.File
import java.util.function.Consumer

/**
 * Everything Lyrelog can be told at start, for a server. Only [apiKey] is needed; `null` or
 * blank makes [Lyrelog.start] do nothing, so the same code runs in tests and on a laptop
 * without a key.
 *
 * ```kotlin
 * Configuration(System.getenv("LYRELOG_KEY")) { platform = "api"; environment = "staging" }
 * ```
 * ```java
 * Configuration.builder(System.getenv("LYRELOG_KEY")).platform("api").environment("staging").build();
 * ```
 *
 * A plain class with a [Builder], so options can be added in a later version without breaking
 * code built against this one. [toBuilder] (Kotlin: [copy]) changes a copy.
 */
class Configuration private constructor(builder: Builder) {
    /** The configuration with every default: `Configuration(key)`, `new Configuration(key)`. */
    constructor(apiKey: String?) : this(Builder(apiKey))

    /**
     * The project's server key (`lyr_srv_...`): its events are stored verified (`verified_by =
     * server`). A secret: read it from the server's environment. An ingest key (`lyr_ing_...`)
     * works too, unverified.
     */
    val apiKey: String? = builder.apiKey
    /**
     * The ingest origin. A pasted `.../v1/events:batch` or `.../v1` is cut back. `https` only;
     * plain `http` only to `localhost`, `127.0.0.1` and `::1`. Any other endpoint is refused:
     * nothing is sent.
     */
    val endpoint: String = builder.endpoint
    /**
     * Which environment these events belong to. `null`: the `LYRELOG_ENVIRONMENT` environment
     * variable, else `production`. Any name works: lowercase letters, digits, `-` and `_`, up to
     * 32; other names are cleaned ("Staging EU" -> "staging-eu"). An invalid
     * `LYRELOG_ENVIRONMENT` is reported once and replaced by `production`.
     */
    val environment: String? = builder.environment
    /** Which part of the product sends these events (`api`, `worker`). `null` is `jvm`. */
    val platform: String? = builder.platform
    /** `resource.app_version`. `null`: the `Implementation-Version` of the jar that was started, if any. */
    val release: String? = builder.release
    /** `resource.app_id`, the service's name. `null`: the started jar's `Implementation-Title` or file name, else the main class. */
    val appId: String? = builder.appId
    /** Events below this level are dropped before they are queued. */
    val minimumLevel: Level = builder.minimumLevel
    /** How often the background thread sends; at least 1 second (smaller values are raised to it). */
    val flushIntervalMillis: Long = builder.flushIntervalMillis
    /** A batch (one request) closes at whichever comes first; kept within 2,000 events and 4 MiB. */
    val maxBatchBytes: Int = builder.maxBatchBytes
    val maxBatchEvents: Int = builder.maxBatchEvents
    /** Events waiting to be written; past it the oldest is dropped and counted. */
    val maxQueue: Int = builder.maxQueue
    /** Hard caps on what waits in the queue directory; the oldest batches go first. */
    val maxDiskBytes: Long = builder.maxDiskBytes
    val maxEventAgeMillis: Long = builder.maxEventAgeMillis
    /**
     * Field names whose values are sent as "[redacted]", in attrs and payload at any depth.
     * `null` is [DEFAULT_REDACTED_KEYS]; your list replaces it (add with
     * `DEFAULT_REDACTED_KEYS + "pin"`); an empty list turns redaction off.
     */
    val redactedKeys: List<String>? = builder.redactedKeys
    /**
     * Where the queue lives. `null`: a new directory under `java.io.tmpdir` for this process,
     * deleted when Lyrelog closes (what wasn't sent by then is lost, as with an in-memory
     * queue). A directory of your own keeps what wasn't sent for the next start; one process
     * at a time (a second one finds it locked and uses a temporary directory).
     */
    val storageDirectory: File? = builder.storageDirectory
    /**
     * Also record exceptions no code caught (`Thread.setDefaultUncaughtExceptionHandler`, the
     * previous handler still runs) as `exception` events with `handled: false`. Off by default.
     */
    val captureUncaught: Boolean = builder.captureUncaught
    /**
     * Close (send what's left, up to [closeTimeoutMillis]) when the JVM shuts down. The hook runs
     * as shutdown begins, alongside your framework's own shutdown, so what that logs while it
     * stops gracefully may come too late. To keep it, turn this off and call [Lyrelog.close]
     * yourself, as the last step of your shutdown (Spring: a `SmartLifecycle` with the lowest
     * phase, whose `stop` runs after the web server and the other beans have stopped).
     */
    val shutdownHook: Boolean = builder.shutdownHook
    /** How long closing waits for the last send. */
    val closeTimeoutMillis: Long = builder.closeTimeoutMillis
    /**
     * Receives the SDK's own diagnostics (a refused key, a dropped batch, the monthly quota),
     * each starting with `lyrelog: ` or `Lyrelog: `, each once per process except the quota
     * warning. `null`: standard error. Never point it at a logger that feeds Lyrelog.
     */
    val diagnostics: Consumer<String>? = builder.diagnostics

    /** The key never shows in a log that prints the configuration. */
    override fun toString(): String =
        "Configuration(endpoint=$endpoint, environment=$environment, platform=$platform, appId=$appId, release=$release, apiKey=<redacted>)"

    /** A builder holding this configuration, to change some of it. */
    fun toBuilder(): Builder = Builder(apiKey).also { b ->
        b.endpoint = endpoint
        b.environment = environment
        b.platform = platform
        b.release = release
        b.appId = appId
        b.minimumLevel = minimumLevel
        b.flushIntervalMillis = flushIntervalMillis
        b.maxBatchBytes = maxBatchBytes
        b.maxBatchEvents = maxBatchEvents
        b.maxQueue = maxQueue
        b.maxDiskBytes = maxDiskBytes
        b.maxEventAgeMillis = maxEventAgeMillis
        b.redactedKeys = redactedKeys
        b.storageDirectory = storageDirectory
        b.captureUncaught = captureUncaught
        b.shutdownHook = shutdownHook
        b.closeTimeoutMillis = closeTimeoutMillis
        b.diagnostics = diagnostics
    }

    /** [toBuilder], changed in Kotlin: `config.copy { environment = "staging" }`. */
    @JvmSynthetic
    fun copy(configure: Builder.() -> Unit): Configuration = toBuilder().apply(configure).build()

    /**
     * Every option, with its default. Kotlin sets the properties (inside `Configuration(key) { ... }`);
     * Java chains the methods of the same names, then [build]. A `null` from Java for an option
     * that has a default is that default.
     */
    class Builder(apiKey: String?) {
        @set:JvmSynthetic var apiKey: String? = apiKey
        @set:JvmSynthetic var endpoint: String = DEFAULT_ENDPOINT
        @set:JvmSynthetic var environment: String? = null
        @set:JvmSynthetic var platform: String? = null
        @set:JvmSynthetic var release: String? = null
        @set:JvmSynthetic var appId: String? = null
        @set:JvmSynthetic var minimumLevel: Level = Level.DEBUG
        @set:JvmSynthetic var flushIntervalMillis: Long = 5_000
        @set:JvmSynthetic var maxBatchBytes: Int = 512 * 1024
        @set:JvmSynthetic var maxBatchEvents: Int = 500
        @set:JvmSynthetic var maxQueue: Int = 10_000
        @set:JvmSynthetic var maxDiskBytes: Long = 20L * 1024 * 1024
        @set:JvmSynthetic var maxEventAgeMillis: Long = 7L * 24 * 3600 * 1000
        @set:JvmSynthetic var redactedKeys: List<String>? = null
        @set:JvmSynthetic var storageDirectory: File? = null
        @set:JvmSynthetic var captureUncaught: Boolean = false
        @set:JvmSynthetic var shutdownHook: Boolean = true
        @set:JvmSynthetic var closeTimeoutMillis: Long = 5_000
        @set:JvmSynthetic var diagnostics: Consumer<String>? = null

        fun apiKey(value: String?) = apply { apiKey = value }
        /** `null` (an unset environment variable) is [DEFAULT_ENDPOINT]. */
        fun endpoint(value: String?) = apply { endpoint = value ?: DEFAULT_ENDPOINT }
        fun environment(value: String?) = apply { environment = value }
        fun platform(value: String?) = apply { platform = value }
        fun release(value: String?) = apply { release = value }
        fun appId(value: String?) = apply { appId = value }
        /** `null` is `Level.DEBUG`. */
        fun minimumLevel(value: Level?) = apply { minimumLevel = value ?: Level.DEBUG }
        fun flushIntervalMillis(value: Long) = apply { flushIntervalMillis = value }
        fun maxBatchBytes(value: Int) = apply { maxBatchBytes = value }
        fun maxBatchEvents(value: Int) = apply { maxBatchEvents = value }
        fun maxQueue(value: Int) = apply { maxQueue = value }
        fun maxDiskBytes(value: Long) = apply { maxDiskBytes = value }
        fun maxEventAgeMillis(value: Long) = apply { maxEventAgeMillis = value }
        fun redactedKeys(value: List<String>?) = apply { redactedKeys = value }
        fun storageDirectory(value: File?) = apply { storageDirectory = value }
        fun captureUncaught(value: Boolean) = apply { captureUncaught = value }
        fun shutdownHook(value: Boolean) = apply { shutdownHook = value }
        fun closeTimeoutMillis(value: Long) = apply { closeTimeoutMillis = value }
        fun diagnostics(value: Consumer<String>?) = apply { diagnostics = value }

        fun build(): Configuration = Configuration(this)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://ingest.lyrelog.com"

        /** What `redactedKeys = null` redacts, the same list in every Lyrelog SDK. */
        @JvmField
        val DEFAULT_REDACTED_KEYS: List<String> = com.lyrelog.core.Redaction.DEFAULT_KEYS

        /** For Java: `Configuration.builder(System.getenv("LYRELOG_KEY")).platform("api").build()`. */
        @JvmStatic
        fun builder(apiKey: String?): Builder = Builder(apiKey)

        /** Kotlin: `Configuration(System.getenv("LYRELOG_KEY")) { platform = "api" }`. */
        @JvmSynthetic
        operator fun invoke(apiKey: String?, configure: Builder.() -> Unit): Configuration =
            Builder(apiKey).apply(configure).build()
    }
}
