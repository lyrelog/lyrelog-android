package com.lyrelog

import java.io.File

/**
 * Everything Lyrelog can be told at start. Only [apiKey] is required; the rest has the same
 * defaults as the Swift SDK (a mobile SDK with an on-disk queue).
 *
 * ```kotlin
 * Configuration("lyr_ing_...")                               // the defaults
 * Configuration("lyr_ing_...") { consent = Consent.PENDING } // Kotlin: set what you need
 * ```
 * ```java
 * Configuration.builder("lyr_ing_...").consent(Consent.PENDING).build();   // Java
 * ```
 *
 * A plain class with a [Builder] (not a data class), so options can be added in a later version
 * without breaking apps built against this one. [toBuilder] copies one to change it.
 */
class Configuration private constructor(builder: Builder) {
    /** The configuration with every default: `Configuration("lyr_ing_...")`, `new Configuration(key)`. */
    constructor(apiKey: String) : this(Builder(apiKey))

    /** The project's ingest key (`lyr_ing_...`). It can only append events, so it is fine inside the app. */
    val apiKey: String = builder.apiKey

    /**
     * The ingest origin. A pasted `.../v1/events:batch` or `.../v1` is cut back. `https` only;
     * plain `http` only to `localhost`, `127.0.0.1`, `::1`, and on the emulator to `10.0.2.2`
     * (your computer; the app also needs cleartext allowed for it, in a debug network security
     * config). Any other endpoint is refused: nothing is sent, events stay on the device.
     */
    val endpoint: String = builder.endpoint

    /** The person's choice at start; `Lyrelog.setConsent` before `start` wins over it. */
    val consent: Consent = builder.consent

    /** Events below this level are dropped before they reach the disk. */
    val minimumLevel: Level = builder.minimumLevel

    /** How often the uploader runs while an Activity is started; at least 1 second (smaller values are raised to it). */
    val flushIntervalMillis: Long = builder.flushIntervalMillis

    /** A batch (one file, one request) closes at whichever comes first; kept within 2,000 events and 4 MiB. */
    val maxBatchBytes: Int = builder.maxBatchBytes
    val maxBatchEvents: Int = builder.maxBatchEvents

    /** Hard caps on what waits on the device; the oldest files go first. */
    val maxDiskBytes: Long = builder.maxDiskBytes
    val maxEventAgeMillis: Long = builder.maxEventAgeMillis

    /** A new session starts after this long without any event. */
    val sessionTimeoutMillis: Long = builder.sessionTimeoutMillis

    /**
     * Which environment these events belong to. `null` detects it: `development` when the app
     * is debuggable, `production` otherwise. Any name works: lowercase letters, digits, `-`
     * and `_`, up to 32 ("staging", "internal"); other names are cleaned ("Staging EU" -> "staging-eu").
     */
    val environment: String? = builder.environment

    /** Which part of the product sends these events. `null` is `android`. */
    val platform: String? = builder.platform

    /** Overrides `resource.app_version` (default: the app's versionName). */
    val appVersion: String? = builder.appVersion

    /**
     * Field names whose values are sent as "[redacted]", in attrs and payload at any depth.
     * `null` is [DEFAULT_REDACTED_KEYS]; your list replaces it (add with
     * `DEFAULT_REDACTED_KEYS + "pin"`); an empty list turns redaction off.
     */
    val redactedKeys: List<String>? = builder.redactedKeys

    /**
     * Record uncaught exceptions to disk as they happen, and read the ANRs and native crashes
     * Android recorded (Android 11+; a native crash's frames from 12); all are sent on the next
     * launch, at the time they happened.
     */
    val captureCrashes: Boolean = builder.captureCrashes

    /** Tag every event with the screen set by `Lyrelog.screen` (`screen` attribute). */
    val attachScreen: Boolean = builder.attachScreen

    /** Log a `screen_viewed` for each Activity that comes to the front, named by its class. Off: name your screens. */
    val automaticScreens: Boolean = builder.automaticScreens

    /** Also print every event to logcat (tag `Lyrelog`). */
    val mirrorToLogcat: Boolean = builder.mirrorToLogcat

    /**
     * Key Attestation: once per install a key in the device's secure hardware is attested by
     * Google's roots, and batches then carry a token, so their events are stored verified
     * (`verified_by = key_attestation`). It needs the app's package and signing certificate in
     * the project's settings; until then (a 404) it turns itself off for the launch. Emulators,
     * rooted devices and unlocked bootloaders can't pass: their events arrive unverified, as
     * without it. Never delays an event.
     */
    val attest: Boolean = builder.attest

    /**
     * For Lyrelog's own Flutter and React Native SDKs: their name and version
     * (`lyrelog-flutter/0.9.0`), sent before this SDK's in `resource.sdk` so the dashboard can
     * tell where events came from. Leave it `null` in an app.
     */
    val wrapper: String? = builder.wrapper

    /** Where the queue lives. Default: `noBackupFilesDir/lyrelog` (never in a backup). */
    val storageDirectory: File? = builder.storageDirectory

    /** A builder holding this configuration, to change some of it: `config.toBuilder().consent(...).build()`. */
    fun toBuilder(): Builder = Builder(apiKey).also { b ->
        b.endpoint = endpoint
        b.consent = consent
        b.minimumLevel = minimumLevel
        b.flushIntervalMillis = flushIntervalMillis
        b.maxBatchBytes = maxBatchBytes
        b.maxBatchEvents = maxBatchEvents
        b.maxDiskBytes = maxDiskBytes
        b.maxEventAgeMillis = maxEventAgeMillis
        b.sessionTimeoutMillis = sessionTimeoutMillis
        b.environment = environment
        b.platform = platform
        b.appVersion = appVersion
        b.redactedKeys = redactedKeys
        b.captureCrashes = captureCrashes
        b.attachScreen = attachScreen
        b.automaticScreens = automaticScreens
        b.mirrorToLogcat = mirrorToLogcat
        b.attest = attest
        b.wrapper = wrapper
        b.storageDirectory = storageDirectory
    }

    /** [toBuilder], changed in Kotlin: `config.copy { consent = Consent.DENIED }`. */
    @JvmSynthetic
    fun copy(configure: Builder.() -> Unit): Configuration = toBuilder().apply(configure).build()

    /** The key never shows in a log or a crash report that prints the configuration. */
    override fun toString(): String =
        "Configuration(endpoint=$endpoint, consent=$consent, environment=$environment, platform=$platform, apiKey=<redacted>)"

    /**
     * Every option, with its default. Kotlin sets the properties (`consent = Consent.PENDING`,
     * inside `Configuration(key) { ... }`); Java chains the methods of the same names
     * (`.consent(Consent.PENDING)`), then [build]. A `null` from Java for an option that has a
     * default is that default.
     */
    class Builder(apiKey: String) {
        @set:JvmSynthetic var apiKey: String = apiKey
        @set:JvmSynthetic var endpoint: String = DEFAULT_ENDPOINT
        @set:JvmSynthetic var consent: Consent = Consent.GRANTED
        @set:JvmSynthetic var minimumLevel: Level = Level.DEBUG
        @set:JvmSynthetic var flushIntervalMillis: Long = 15_000
        @set:JvmSynthetic var maxBatchBytes: Int = 512 * 1024
        @set:JvmSynthetic var maxBatchEvents: Int = 500
        @set:JvmSynthetic var maxDiskBytes: Long = 20L * 1024 * 1024
        @set:JvmSynthetic var maxEventAgeMillis: Long = 7L * 24 * 3600 * 1000
        @set:JvmSynthetic var sessionTimeoutMillis: Long = 30L * 60 * 1000
        @set:JvmSynthetic var environment: String? = null
        @set:JvmSynthetic var platform: String? = null
        @set:JvmSynthetic var appVersion: String? = null
        @set:JvmSynthetic var redactedKeys: List<String>? = null
        @set:JvmSynthetic var captureCrashes: Boolean = true
        @set:JvmSynthetic var attachScreen: Boolean = true
        @set:JvmSynthetic var automaticScreens: Boolean = false
        @set:JvmSynthetic var mirrorToLogcat: Boolean = false
        @set:JvmSynthetic var attest: Boolean = true
        @set:JvmSynthetic var wrapper: String? = null
        @set:JvmSynthetic var storageDirectory: File? = null

        fun apiKey(value: String) = apply { apiKey = value }
        /** `null` is [DEFAULT_ENDPOINT]. */
        fun endpoint(value: String?) = apply { endpoint = value ?: DEFAULT_ENDPOINT }
        /** `null` is `Consent.GRANTED`. */
        fun consent(value: Consent?) = apply { consent = value ?: Consent.GRANTED }
        /** `null` is `Level.DEBUG`. */
        fun minimumLevel(value: Level?) = apply { minimumLevel = value ?: Level.DEBUG }
        fun flushIntervalMillis(value: Long) = apply { flushIntervalMillis = value }
        fun maxBatchBytes(value: Int) = apply { maxBatchBytes = value }
        fun maxBatchEvents(value: Int) = apply { maxBatchEvents = value }
        fun maxDiskBytes(value: Long) = apply { maxDiskBytes = value }
        fun maxEventAgeMillis(value: Long) = apply { maxEventAgeMillis = value }
        fun sessionTimeoutMillis(value: Long) = apply { sessionTimeoutMillis = value }
        fun environment(value: String?) = apply { environment = value }
        fun platform(value: String?) = apply { platform = value }
        fun appVersion(value: String?) = apply { appVersion = value }
        fun redactedKeys(value: List<String>?) = apply { redactedKeys = value }
        fun captureCrashes(value: Boolean) = apply { captureCrashes = value }
        fun attachScreen(value: Boolean) = apply { attachScreen = value }
        fun automaticScreens(value: Boolean) = apply { automaticScreens = value }
        fun mirrorToLogcat(value: Boolean) = apply { mirrorToLogcat = value }
        fun attest(value: Boolean) = apply { attest = value }
        fun wrapper(value: String?) = apply { wrapper = value }
        fun storageDirectory(value: File?) = apply { storageDirectory = value }

        fun build(): Configuration = Configuration(this)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://ingest.lyrelog.com"

        /** What `redactedKeys = null` redacts, the same list in every Lyrelog SDK. */
        @JvmField
        val DEFAULT_REDACTED_KEYS: List<String> = com.lyrelog.core.Redaction.DEFAULT_KEYS

        /** For Java: `Configuration.builder("lyr_ing_...").consent(Consent.PENDING).build()`. */
        @JvmStatic
        fun builder(apiKey: String): Builder = Builder(apiKey)

        /** Kotlin: `Configuration("lyr_ing_...") { consent = Consent.PENDING; attest = false }`. */
        @JvmSynthetic
        operator fun invoke(apiKey: String, configure: Builder.() -> Unit): Configuration =
            Builder(apiKey).apply(configure).build()
    }
}
