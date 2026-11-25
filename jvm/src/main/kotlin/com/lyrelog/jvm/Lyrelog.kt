package com.lyrelog.jvm

import com.lyrelog.Level
import com.lyrelog.Logger
import com.lyrelog.core.Engine
import com.lyrelog.core.EngineOptions
import com.lyrelog.core.SdkLog

/**
 * Lyrelog: structured logs from your Java or Kotlin server to Lyrelog, a hosted log service.
 *
 * ```kotlin
 * Lyrelog.start(Configuration(System.getenv("LYRELOG_KEY")) { platform = "api" })
 * Lyrelog.info("checkout_done", attrs = mapOf("plan" to "pro", "latency_ms" to 412), user = customerId)
 * val billing = Lyrelog.logger("billing")
 * billing.warning("invoice_retry", mapOf("attempt" to 2))
 * ```
 *
 * Logging never blocks and never throws: the event is copied, handed to a background writer and
 * sent in batches by one background thread. Before [start] (or without a key) every call does
 * nothing. A JVM shutdown hook sends what's left ([Configuration.shutdownHook]).
 */
object Lyrelog {
    /** This SDK's version, sent as `resource.sdk` (`lyrelog-jvm/1.1.0`) and the User-Agent. */
    const val VERSION = "1.1.0"

    private class Running(
        val engine: Engine,
        val directory: QueueDirectory,
        val closeTimeoutMillis: Long,
        var hook: Thread?,
    )

    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** The [userScope]'s user on each thread; [LyrelogUser] sets it for a coroutine. */
    internal val requestUser = ThreadLocal<String?>()

    /**
     * Starts once, as early as possible (`main`, a Spring `@PostConstruct` or `ApplicationRunner`,
     * Ktor's module). True when this call started Lyrelog; false when it was already running,
     * when [Configuration.apiKey] is null or blank (then nothing is recorded), or when it couldn't
     * start (said once on [Configuration.diagnostics]). Never throws.
     */
    @JvmStatic
    fun start(configuration: Configuration): Boolean {
        val configuration = given(configuration) ?: return false
        val log = sdkLog(configuration)
        try {
            synchronized(lock) {
                if (running != null) return false
                val key = configuration.apiKey?.trim().orEmpty()
                if (key.isEmpty()) return false
                val directory = QueueDirectory.open(configuration.storageDirectory, log)
                val engine = try {
                    Engine(options(configuration, key, directory, log), log = log)
                } catch (e: Exception) {
                    directory.release()
                    throw e
                }
                if (configuration.captureUncaught) UncaughtRecorder.install(engine)
                val current = Running(engine, directory, configuration.closeTimeoutMillis, null)
                if (configuration.shutdownHook) {
                    val hook = Thread({ closeFromHook(current) }, "lyrelog-shutdown")
                    try {
                        Runtime.getRuntime().addShutdownHook(hook)
                        current.hook = hook
                    } catch (e: Exception) {
                        // already shutting down
                    }
                }
                running = current
                return true
            }
        } catch (e: Exception) {
            log.once("start", "lyrelog: could not start (${e.javaClass.name})")
            return false
        }
    }

    private fun sdkLog(configuration: Configuration): SdkLog {
        val sink = configuration.diagnostics
        return SdkLog { _, message -> if (sink != null) sink.accept(message) else System.err.println(message) }
    }

    private fun options(configuration: Configuration, key: String, directory: QueueDirectory, log: SdkLog) = EngineOptions(
        apiKey = key,
        storageDirectory = directory.directory,
        installIdStore = ProcessInstallId(),
        resource = ServerDefaults.resource(configuration, log),
        endpoint = given(configuration.endpoint) ?: Configuration.DEFAULT_ENDPOINT,
        minimumLevel = given(configuration.minimumLevel) ?: Level.DEBUG,
        flushIntervalMillis = configuration.flushIntervalMillis,
        maxBatchBytes = configuration.maxBatchBytes,
        maxBatchEvents = configuration.maxBatchEvents,
        maxDiskBytes = configuration.maxDiskBytes,
        maxEventAgeMillis = configuration.maxEventAgeMillis,
        // One session per process, never rotated (as in the Go SDK and Node).
        sessionTimeoutMillis = Long.MAX_VALUE,
        intakeCapacity = configuration.maxQueue.coerceAtLeast(1),
        redactedKeys = configuration.redactedKeys,
        attachScreen = false,
        contextUser = { requestUser.get() },
    )

    /** Whether Lyrelog is running: started with a key, and not closed since. */
    @JvmStatic
    val isStarted: Boolean get() = running != null

    /**
     * Sends what was logged (waiting at most [Configuration.closeTimeoutMillis]), then stops;
     * [start] can be called again afterwards. What couldn't be sent is lost, unless the queue
     * lives in your own [Configuration.storageDirectory] (then the next start sends it).
     */
    @JvmStatic
    fun close() {
        running?.let { closeRunning(it, it.closeTimeoutMillis, fromHook = false) }
    }

    /** [close], waiting at most [timeoutMillis]. */
    @JvmStatic
    fun close(timeoutMillis: Long) {
        running?.let { closeRunning(it, timeoutMillis, fromHook = false) }
    }

    /** Nothing logged for this long: the other shutdown hooks are done logging. */
    internal const val SHUTDOWN_QUIET_MILLIS = 500L

    /**
     * The JVM runs every shutdown hook at once, in no set order, so a framework closing itself
     * (Spring's context, a web server draining) still logs while this one runs. Lyrelog keeps
     * recording until nothing was logged for [SHUTDOWN_QUIET_MILLIS], for at most half of
     * [Configuration.closeTimeoutMillis], then closes with what's left of it: the whole hook
     * never takes longer than the close timeout.
     */
    private fun closeFromHook(current: Running) {
        val budget = current.closeTimeoutMillis.coerceAtLeast(0)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        try {
            var seen = current.engine.submittedCount
            var quietSince = elapsed()
            while (elapsed() < budget / 2 && elapsed() - quietSince < SHUTDOWN_QUIET_MILLIS) {
                Thread.sleep(25)
                val count = current.engine.submittedCount
                if (count != seen) {
                    seen = count
                    quietSince = elapsed()
                }
            }
        } catch (e: InterruptedException) {
            // close now
        } catch (e: Exception) {
            // close now
        }
        closeRunning(current, (budget - elapsed()).coerceAtLeast(0), fromHook = true)
    }

    private fun closeRunning(current: Running, timeoutMillis: Long, fromHook: Boolean) {
        synchronized(lock) {
            if (running !== current) return
            running = null
        }
        try {
            UncaughtRecorder.detach(current.engine)
            current.engine.close(timeoutMillis)
        } catch (e: Exception) {
            // never throw into the caller
        } finally {
            current.directory.release()
            val hook = current.hook
            if (!fromHook && hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (e: Exception) {
                    // the JVM is shutting down
                }
            }
        }
    }

    /**
     * Sends everything logged so far, waiting at most [timeoutMillis]. True when it finished in
     * time. Before a serverless handler returns (AWS Lambda), so a frozen process loses nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMillis: Long = 5_000): Boolean = running?.engine?.flush(timeoutMillis) ?: false

    /** [flush] without waiting. */
    @JvmStatic
    fun flushInBackground() {
        running?.engine?.flushAsync()
    }

    /**
     * Your user or account id on every following event, from every thread; `null` clears it.
     * On a server handling many people, use [userScope] or the `user` argument instead.
     */
    @JvmStatic
    fun setUser(id: String?) {
        running?.engine?.setUser(id)
    }

    /**
     * The user of the work on this thread (a request), until the scope is closed: wins over
     * [setUser]; an event's own `user` wins over it. Thread-local: coroutines hop threads, so
     * give them `withContext(LyrelogUser(id))` instead (kotlinx-coroutines); in reactive code
     * (Reactor, RxJava) pass `user` on each event.
     *
     * ```java
     * try (var scope = Lyrelog.userScope(request.getUserId())) { handle(request); }
     * ```
     */
    @JvmStatic
    fun userScope(id: String?): UserScope {
        val previous = requestUser.get()
        requestUser.set(id?.ifEmpty { null })
        return UserScope(previous)
    }

    /**
     * Ends a [userScope], putting back the user that was set before it. Closed on another
     * thread (a coroutine that resumed elsewhere), it changes nothing there.
     */
    class UserScope internal constructor(private val previous: String?) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var closed = false

        override fun close() {
            if (closed || Thread.currentThread() !== owner) return
            closed = true
            if (previous == null) requestUser.remove() else requestUser.set(previous)
        }
    }

    /** A logger for one area of the service; every event it records carries `category`. */
    @JvmStatic
    fun logger(category: String): Logger = Logger(given(category).orEmpty()) { running?.engine }

    /**
     * Records one event. [user] is this event's user (else the [userScope]'s, else [setUser]'s).
     * [timestampMillis] is its time (else now), for bridges that forward a record logged earlier.
     */
    @JvmStatic
    @JvmOverloads
    fun log(
        level: Level,
        message: String,
        category: String? = null,
        attrs: Map<String, Any?>? = null,
        payload: Any? = null,
        user: String? = null,
        timestampMillis: Long? = null,
    ) {
        running?.engine?.log(given(level) ?: Level.INFO, given(message).orEmpty(), category, attrs, payload, user, timestampMillis)
    }

    @JvmStatic @JvmOverloads
    fun trace(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.TRACE, message, category, attrs, payload, user)

    /** [trace] without a category, for Java: `Lyrelog.trace("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun trace(message: String, attrs: Map<String, Any?>?) = log(Level.TRACE, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun debug(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.DEBUG, message, category, attrs, payload, user)

    /** [debug] without a category, for Java: `Lyrelog.debug("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun debug(message: String, attrs: Map<String, Any?>?) = log(Level.DEBUG, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun info(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.INFO, message, category, attrs, payload, user)

    /** [info] without a category, for Java: `Lyrelog.info("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun info(message: String, attrs: Map<String, Any?>?) = log(Level.INFO, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun notice(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.NOTICE, message, category, attrs, payload, user)

    /** [notice] without a category, for Java: `Lyrelog.notice("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun notice(message: String, attrs: Map<String, Any?>?) = log(Level.NOTICE, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun warning(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.WARNING, message, category, attrs, payload, user)

    /** [warning] without a category, for Java: `Lyrelog.warning("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun warning(message: String, attrs: Map<String, Any?>?) = log(Level.WARNING, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun error(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.ERROR, message, category, attrs, payload, user)

    /** [error] without a category, for Java: `Lyrelog.error("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun error(message: String, attrs: Map<String, Any?>?) = log(Level.ERROR, message, null, attrs)

    @JvmStatic @JvmOverloads
    fun critical(message: String, category: String? = null, attrs: Map<String, Any?>? = null, payload: Any? = null, user: String? = null) =
        log(Level.CRITICAL, message, category, attrs, payload, user)

    /** [critical] without a category, for Java: `Lyrelog.critical("checkout_done", Map.of("plan", "pro"))`. */
    @JvmStatic
    fun critical(message: String, attrs: Map<String, Any?>?) = log(Level.CRITICAL, message, null, attrs)

    /** [log] without a category, for Java: `Lyrelog.log(Level.INFO, "checkout_done", attrs)`. */
    @JvmStatic
    fun log(level: Level, message: String, attrs: Map<String, Any?>?) = log(level, message, null, attrs)

    /**
     * The `exception` event every Lyrelog SDK sends: level `error` (or [level]), attributes
     * `error_type` (the class name) and `error_message`, and a payload with the frames, causes
     * and suppressed exceptions. Your `attrs` are added; theirs win.
     */
    @JvmStatic
    @JvmOverloads
    fun captureError(error: Throwable, category: String? = null, attrs: Map<String, Any?>? = null, level: Level = Level.ERROR, user: String? = null) {
        val engine = running?.engine ?: return
        engine.captureError(given(error) ?: return, category, attrs, given(level) ?: Level.ERROR, user)
    }

    /** [captureError] without a category, for Java: `Lyrelog.captureError(e, Map.of("order_id", id))`. */
    @JvmStatic
    fun captureError(error: Throwable, attrs: Map<String, Any?>?) = captureError(error, null, attrs)

    /**
     * What Java handed over, as nullable: this module is compiled without Kotlin's parameter
     * checks (`-Xno-param-assertions`), so a null from Java reaches here instead of throwing a
     * NullPointerException into the code that logged.
     */
    private fun <T : Any> given(value: T?): T? = value
}
