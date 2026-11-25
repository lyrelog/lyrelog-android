package com.lyrelog

import android.app.Application
import android.content.Context
import android.util.Log
import com.lyrelog.core.CrashRecorder
import com.lyrelog.core.Engine
import com.lyrelog.core.EngineOptions
import com.lyrelog.core.FileInstallIdStore
import com.lyrelog.core.SdkLog
import java.io.File

/**
 * Lyrelog: structured logs from your Android app to Lyrelog, a hosted log service.
 *
 * ```kotlin
 * class TilefallApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Lyrelog.start(this, Configuration("lyr_ing_..."))
 *     }
 * }
 * Lyrelog.setUser("player-123")
 * Lyrelog.info("level_cleared", attrs = mapOf("level" to 12, "moves" to 31))
 * val store = Lyrelog.logger("store")
 * store.warning("purchase_retry", mapOf("attempt" to 2))
 * ```
 *
 * Logging never blocks and never throws: the event is copied, handed to a background writer that
 * puts it on disk, and sent in batches. Events logged before [start] are ignored; [setUser] and
 * [setConsent] before [start] are kept and applied when it runs.
 */
object Lyrelog {
    /** This SDK's version, sent as `resource.sdk` (`lyrelog-android/1.1.1`) and the User-Agent. */
    const val VERSION = "1.1.1"
    private const val TAG = "Lyrelog"

    private val lock = Any()

    @Volatile
    private var engine: Engine? = null
    private var tracker: ActivityTracker? = null
    private var application: Application? = null

    // `setUser` and `setConsent` before `start`, applied when it runs.
    private var earlyUserSet = false
    private var earlyUser: String? = null
    private var earlyConsent: Consent? = null

    /**
     * Start once, as early as possible (`Application.onCreate`). Reads the app's defaults
     * (package name, version, debuggable), opens the queue in `noBackupFilesDir/lyrelog` and
     * begins sending what an earlier launch left (a crash included). True when this call started
     * Lyrelog; false when it was already running (the call is ignored) or couldn't start (said
     * once in logcat). Never throws, and touches no disk on the calling thread.
     */
    @JvmStatic
    fun start(context: Context, configuration: Configuration): Boolean {
        try {
            synchronized(lock) {
                if (engine != null) return false
                if (given(context) == null || given(configuration) == null) return false
                val app = context.applicationContext
                val consent = earlyConsent ?: configuration.consent
                val base = File(noBackupDirectory(app), "lyrelog")
                val mirror: ((Level, String?, String) -> Unit)? = if (configuration.mirrorToLogcat) {
                    { level, category, message -> Log.println(priority(level), TAG, if (category != null) "[$category] $message" else message) }
                } else {
                    null
                }
                val options = EngineOptions(
                    apiKey = configuration.apiKey,
                    storageDirectory = configuration.storageDirectory ?: File(base, "queue"),
                    installIdStore = FileInstallIdStore(File(base, "install_id")),
                    resource = DeviceContext.resource(app, configuration),
                    endpoint = configuration.endpoint,
                    extraPlainHosts = if (DeviceContext.isEmulator()) setOf("10.0.2.2") else emptySet(),
                    consent = consent,
                    minimumLevel = configuration.minimumLevel,
                    flushIntervalMillis = configuration.flushIntervalMillis,
                    maxBatchBytes = configuration.maxBatchBytes,
                    maxBatchEvents = configuration.maxBatchEvents,
                    maxDiskBytes = configuration.maxDiskBytes,
                    maxEventAgeMillis = configuration.maxEventAgeMillis,
                    sessionTimeoutMillis = configuration.sessionTimeoutMillis,
                    redactedKeys = configuration.redactedKeys,
                    attachScreen = configuration.attachScreen,
                    onRecord = mirror,
                    // An emulator has no secure hardware Google attests: it would only be refused.
                    attestation = if (configuration.attest && !DeviceContext.isEmulator()) KeystoreAttestation(app) else null,
                    attestRecord = File(base, "attest.json"),
                    // A process started for a push or a job has no Activity: no periodic uploads
                    // until one starts (unless `start` runs late, with one already in front).
                    startInForeground = ActivityTracker.inFront(),
                )
                val log = SdkLog { warning, message -> if (warning) Log.w(TAG, message) else Log.d(TAG, message) }
                val started = Engine(options, log = log)
                if (earlyUserSet) started.setUser(earlyUser)
                earlyUserSet = false
                earlyUser = null
                earlyConsent = null
                if (configuration.captureCrashes) {
                    CrashRecorder.install(started)
                    // ANRs and native crashes of earlier processes, from Android's own records.
                    ExitReasons.report(app, started, File(base, "last_exit"))
                }
                (app as? Application)?.let {
                    val screens: ((String) -> Unit)? = if (configuration.automaticScreens) { name -> started.screen(name) } else null
                    val callbacks = ActivityTracker(started::setForeground, started::flushAsync, screens)
                    it.registerActivityLifecycleCallbacks(callbacks)
                    tracker = callbacks
                    application = it
                }
                engine = started
                return true
            }
        } catch (e: Throwable) {
            // An Error too: thrown from Application.onCreate, it would crash every launch.
            Log.d(TAG, "lyrelog: could not start (${e.javaClass.name})")
            return false
        }
    }

    /** Whether Lyrelog is running: started, and not stopped since. */
    @JvmStatic
    val isStarted: Boolean get() = engine != null

    /**
     * The consent in force: the running SDK's (a [setConsent] applies at once), else the one a
     * [setConsent] before [start] will apply, else `null` (not started, nothing chosen yet).
     */
    @JvmStatic
    val consent: Consent? get() = synchronized(lock) { engine?.consent ?: earlyConsent }

    /**
     * Sends what was logged (waiting at most [timeoutMillis]), then stops; `start` can be called
     * again afterwards. What couldn't be sent stays on disk for the next launch. Blocks: call it
     * off the main thread, or use [stopNow].
     */
    @JvmStatic
    @JvmOverloads
    fun stop(timeoutMillis: Long = 2_000) {
        detach()?.close(timeoutMillis)
    }

    /** Stops at once without sending: what was logged is written to disk and goes with the next launch. */
    @JvmStatic
    fun stopNow() {
        detach()?.stopNow()
    }

    /**
     * `noBackupFilesDir` without asking for it: `Context.getNoBackupFilesDir` checks (and makes)
     * the directory, a disk access StrictMode flags on the main thread, where `start` runs. It is
     * `<dataDir>/no_backup`; the writer thread makes it on first use.
     */
    private fun noBackupDirectory(app: Context): File =
        app.applicationInfo?.dataDir?.let { File(it, "no_backup") } ?: app.noBackupFilesDir

    private fun detach(): Engine? = synchronized(lock) {
        val current = engine ?: return null
        engine = null
        tracker?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        tracker = null
        application = null
        current
    }

    /**
     * Writes everything logged so far to disk and tries to send it now, waiting at most
     * [timeoutMillis]. Blocks: never on the main thread (use [flushInBackground] there).
     * Rarely needed: going to the background already sends. True when it finished in time.
     */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMillis: Long = 5_000): Boolean = engine?.flush(timeoutMillis) ?: false

    /**
     * Waits until everything logged so far is written to the queue on disk, at most
     * [timeoutMillis], and sends nothing. True when it is: from then on it survives the process
     * ending and goes with this launch's next upload or the next launch's. For a fatal error
     * your own handler records just before the process ends (Lyrelog's React Native and Flutter
     * SDKs do this for JavaScript and Dart crashes): unlike [flush], a slow network can't make
     * it wait, so the error isn't left half-sent and recorded twice. Blocks briefly.
     */
    @JvmStatic
    @JvmOverloads
    fun flushToDisk(timeoutMillis: Long = 1_000): Boolean = engine?.flushToDisk(timeoutMillis) ?: false

    /** [flush] without waiting. */
    @JvmStatic
    fun flushInBackground() {
        engine?.flushAsync()
    }

    /** Your own player or account id on every following event; `null` clears it. Before `start`, it applies from the start. */
    @JvmStatic
    fun setUser(id: String?) {
        synchronized(lock) {
            val current = engine
            if (current != null) {
                current.setUser(id)
            } else {
                earlyUserSet = true
                earlyUser = id
            }
        }
    }

    /**
     * The person's choice, applied at once: a denial deletes what is on the device (and the
     * install id) and records nothing more. Before `start`, it wins over `Configuration.consent`.
     */
    @JvmStatic
    fun setConsent(consent: Consent) {
        if (given(consent) == null) return
        val current = synchronized(lock) {
            if (engine == null) earlyConsent = consent
            engine
        }
        current?.setConsent(consent)
    }

    /**
     * Says which screen the person is on: logs `screen_viewed` (with the screen before and the
     * seconds spent on it) and, unless `attachScreen` is off, tags every following event with `screen`.
     */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String, attrs: Map<String, Any?>? = null) {
        engine?.screen(name, attrs)
    }

    /** The current session id, e.g. to show in a support screen. */
    @JvmStatic
    val sessionId: String? get() = engine?.sessionId

    /** A logger for one area of the app; every event it records carries `category`. */
    @JvmStatic
    fun logger(category: String): Logger = Logger(category) { engine }

    @JvmStatic
    @JvmOverloads
    fun log(level: Level, message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) {
        engine?.log(level, message, category, attrs, payload)
    }

    @JvmStatic @JvmOverloads
    fun trace(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.TRACE, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun debug(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.DEBUG, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun info(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.INFO, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun notice(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.NOTICE, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun warning(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.WARNING, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun error(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.ERROR, message, category, attrs, payload)

    @JvmStatic @JvmOverloads
    fun critical(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.CRITICAL, message, category, attrs, payload)

    /**
     * The `exception` event every Lyrelog SDK sends: level `error`, attributes `error_type` (the
     * class name) and `error_message`, and a payload with the frames, causes and suppressed
     * exceptions. Your `attrs` are added; theirs win.
     */
    @JvmStatic
    @JvmOverloads
    fun captureError(error: Throwable, category: String? = null, attrs: Map<String, Any?>? = null, level: Level = Level.ERROR) {
        engine?.captureError(error, category, attrs, level)
    }

    /** A null from Java, as nullable (`-Xno-param-assertions`: no NullPointerException into the app). */
    private fun <T : Any> given(value: T?): T? = value

    private fun priority(level: Level) = when (level) {
        Level.TRACE -> Log.VERBOSE
        Level.DEBUG -> Log.DEBUG
        Level.INFO, Level.NOTICE -> Log.INFO
        Level.WARNING -> Log.WARN
        Level.ERROR -> Log.ERROR
        Level.CRITICAL -> Log.ASSERT
    }
}
