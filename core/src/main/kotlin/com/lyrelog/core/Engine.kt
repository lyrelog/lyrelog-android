package com.lyrelog.core

import com.lyrelog.Consent
import com.lyrelog.Level
import java.io.File
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Everything the engine needs, already decided by the platform layer (`android/`). */
@InternalLyrelogApi
class EngineOptions(
    val apiKey: String,
    val storageDirectory: File,
    val installIdStore: InstallIdStore,
    val resource: Resource,
    val endpoint: String = Endpoint.DEFAULT,
    /** Hosts plain `http` may reach besides loopback (the emulator's `10.0.2.2`). */
    val extraPlainHosts: Set<String> = emptySet(),
    val consent: Consent = Consent.GRANTED,
    val minimumLevel: Level = Level.DEBUG,
    /** How often a periodic round runs; at least [MIN_FLUSH_INTERVAL_MILLIS]. */
    val flushIntervalMillis: Long = 15_000,
    val maxBatchBytes: Int = 512 * 1024,
    val maxBatchEvents: Int = 500,
    val maxDiskBytes: Long = 20L * 1024 * 1024,
    val maxEventAgeMillis: Long = 7L * 24 * 3600 * 1000,
    val sessionTimeoutMillis: Long = 30L * 60 * 1000,
    val intakeCapacity: Int = 10_000,
    /** What the intake may hold in memory, estimated per event when it is logged. */
    val intakeMaxBytes: Long = DEFAULT_INTAKE_MAX_BYTES,
    val redactedKeys: List<String>? = null,
    val attachScreen: Boolean = true,
    /** Called on the caller's thread for every event kept (Android: the logcat mirror). */
    val onRecord: ((Level, String?, String) -> Unit)? = null,
    /** Key Attestation: the device's Keystore (null: off) and where its record is kept. */
    val attestation: AttestationProvider? = null,
    val attestRecord: File? = null,
    /**
     * The user of the work running on the calling thread (servers: the request's), read for each
     * event. Wins over `setUser`; an event's own user wins over it. Null: none.
     */
    val contextUser: (() -> String?)? = null,
    /**
     * Whether periodic rounds run from the start. Android passes false unless an Activity is
     * already in front: a process started for a push or a job must not upload every 15 s.
     */
    val startInForeground: Boolean = true,
) {
    /** [flushIntervalMillis], raised to [MIN_FLUSH_INTERVAL_MILLIS]. */
    val effectiveFlushIntervalMillis: Long get() = flushIntervalMillis.coerceAtLeast(MIN_FLUSH_INTERVAL_MILLIS)
    val effectiveMaxBatchEvents: Int get() = maxBatchEvents.coerceIn(1, SERVER_MAX_BATCH_EVENTS)
    val effectiveMaxBatchBytes: Int get() = maxBatchBytes.coerceIn(64 * 1024, SERVER_MAX_BATCH_BYTES)

    companion object {
        /** The server takes at most 2,000 lines and 4 MiB per request (the file is the decompressed size). */
        const val SERVER_MAX_BATCH_EVENTS = 2000
        const val SERVER_MAX_BATCH_BYTES = 4 * 1024 * 1024
        /** 16 MiB, the in-memory budget of the JavaScript, Python and Go SDKs' queues. */
        const val DEFAULT_INTAKE_MAX_BYTES = 16L * 1024 * 1024
        /** The shortest upload interval, the same in every Lyrelog SDK. */
        const val MIN_FLUSH_INTERVAL_MILLIS = 1_000L
    }
}

/**
 * Wires the pieces together, with two threads and no thread per event or per request:
 *
 * - the **writer** (`lyrelog-writer`) takes events and consent changes from the intake, in order,
 *   and applies them to disk; it never waits on the network;
 * - the **uploader** (`lyrelog-upload`, a single-thread scheduler) runs an upload round every
 *   `flushIntervalMillis` while the app is in the foreground, and the forced rounds of a flush.
 *
 * The intake holds at most `intakeCapacity` events and `intakeMaxBytes` (an estimate made
 * when each is logged); past that the oldest events are dropped and counted. Consent changes share the intake (so they apply in order with the events around
 * them) but are never dropped. Consent itself lives in a [ConsentGate] that the writer and the
 * uploader read at the moment they act, so a denial applies at once.
 *
 * No public method throws or blocks on the network, except [flush] and [close], which wait at
 * most their timeout.
 */
@InternalLyrelogApi
class Engine(
    private val options: EngineOptions,
    transport: Transport = UrlConnectionTransport(),
    private val log: SdkLog = SdkLog.stderr(),
    private val now: () -> Long = System::currentTimeMillis,
    jitter: ((Long) -> Long)? = null,
) {
    private sealed class Item {
        class Event(val event: PendingEvent, val bytes: Long = 0) : Item()
        class Control(val consent: Consent) : Item()
    }

    internal val queue = DiskQueue(
        options.storageDirectory,
        DiskQueue.Limits(
            maxFileBytes = options.effectiveMaxBatchBytes,
            maxFileEvents = options.effectiveMaxBatchEvents,
            maxTotalBytes = options.maxDiskBytes,
            maxAgeMillis = options.maxEventAgeMillis,
        ),
        now,
    )
    private val gate = ConsentGate(options.consent)
    private val identity = InstallIdentity(options.installIdStore)
    private val redaction = Redaction(options.redactedKeys)
    internal val uploader: Uploader

    /** Key Attestation's token keeper, when on. */
    internal val attestor: Attestor?

    // The intake and its counters, under `intakeLock`.
    private val intakeLock = ReentrantLock()
    private val changed = intakeLock.newCondition()
    private val intake = ArrayDeque<Item>()
    private var eventsWaiting = 0
    private var bytesWaiting = 0L
    private var submitted = 0L
    private var done = 0L
    private var memoryDrops = 0
    private var tooLarge = 0
    private var stopping = false

    /** Held by whoever is applying an item to disk (the writer, or a crash being recorded). */
    private val applyLock = ReentrantLock()

    // What every event carries, under `stateLock`.
    private val stateLock = Any()
    private var userId: String? = null
    private val session = SessionClock(options.sessionTimeoutMillis)
    private var screen: String? = null
    private var screenSince: Long? = null

    @Volatile
    private var foreground = options.startInForeground

    @Volatile
    var stopped = false
        private set

    private val writer: Thread
    private val scheduler: ScheduledThreadPoolExecutor

    init {
        val origin = Endpoint.origin(options.endpoint, options.extraPlainHosts)
        if (origin == null) {
            log.once("endpoint", "lyrelog: the endpoint must be https (plain http only to localhost); nothing will be sent, events stay on the device")
        }
        val userAgent = options.resource.sdk.ifEmpty { SDK_USER_AGENT_FALLBACK }
        attestor = if (origin != null && options.attestation != null) {
            Attestor(
                origin, options.apiKey, userAgent, transport, options.attestation,
                AttestRecordFile(options.attestRecord ?: File(options.storageDirectory, "attest.json")), log, now,
            )
        } else {
            null
        }
        uploader = Uploader(
            origin, options.apiKey, transport, queue, gate, log, userAgent,
            jitter = jitter ?: { kotlin.random.Random.nextLong(0, it + 1) },
            attestor = attestor,
        )
        // The token is ready for the first batch, when sending is allowed at all.
        if (options.consent == Consent.GRANTED) attestor?.prepare()
        // The launch consent is the first control: denied deletes what an earlier launch left,
        // granted makes what it kept pending sendable.
        submit(Item.Control(options.consent))
        writer = Thread({ writeLoop() }, "lyrelog-writer").apply {
            isDaemon = true
            start()
        }
        scheduler = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
            Thread(task, "lyrelog-upload").apply { isDaemon = true }
        }).apply {
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            val interval = options.effectiveFlushIntervalMillis
            scheduleWithFixedDelay({ guarded { if (foreground) runRound(false) } }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    // MARK: - Recording

    /**
     * Records one event. Never throws, never blocks on I/O. [user] is this event's user (else
     * the context's, else `setUser`'s); [timestampMillis] its time (else now: a bridged log
     * record keeps its own).
     */
    fun log(
        level: Level,
        message: String,
        category: String? = null,
        attrs: Map<String, Any?>? = null,
        payload: Any? = null,
        user: String? = null,
        timestampMillis: Long? = null,
    ) {
        guarded {
            record(given(level) ?: Level.INFO, given(message).orEmpty(), category, attributesOf(attrs), payload?.let { JsonValue.from(it) }, user, timestampMillis)
        }
    }

    /** The `exception` event: `error_type`, `error_message` and a JVM stack payload. Your attrs are added; theirs win. */
    fun captureError(error: Throwable, category: String? = null, attrs: Map<String, Any?>? = null, level: Level = Level.ERROR, user: String? = null) {
        guarded {
            val thrown = given(error) ?: return@guarded // a null error records nothing
            val attributes = LinkedHashMap(attributesOf(attrs))
            attributes.putAll(Exceptions.attributes(thrown))
            record(given(level) ?: Level.ERROR, "exception", category, attributes, Exceptions.payload(thrown), user, null)
        }
    }

    /**
     * Says which screen the person is on: logs `screen_viewed` (with the screen before and the
     * seconds spent on it) and, when `attachScreen` is on, tags every following event with `screen`.
     * The same screen twice in a row is one view.
     */
    fun screen(name: String, attrs: Map<String, Any?>? = null) {
        if (given(name).isNullOrEmpty()) return
        guarded {
            val at = now()
            val previous = synchronized(stateLock) {
                if (stopped || screen == name) return@guarded
                val before = screen to screenSince
                screen = name
                screenSince = at
                before
            }
            val attributes = LinkedHashMap(attributesOf(attrs))
            attributes["screen"] = JsonValue.Str(name)
            previous.first?.let { attributes["previous_screen"] = JsonValue.Str(it) }
            previous.second?.let { since ->
                attributes["seconds_on_previous"] = JsonValue.Decimal(Math.round((at - since) / 100.0) / 10.0)
            }
            record(Level.INFO, "screen_viewed", "navigation", attributes, null)
        }
    }

    fun setUser(id: String?) {
        synchronized(stateLock) { userId = id?.ifEmpty { null } }
    }

    val user: String? get() = synchronized(stateLock) { userId }

    val sessionId: String get() = synchronized(stateLock) { session.id }

    val consent: Consent get() = gate.value

    /** Applies at once: a denial deletes what is on the device, including events not yet written. */
    fun setConsent(value: Consent) {
        val consent = given(value) ?: return
        guarded { submit(Item.Control(consent)) }
    }

    /**
     * An uncaught exception, on the dying thread: the events still in memory and this one are
     * written to disk now (no network), to be sent on the next launch. It ends the app, so it's
     * filed like the Swift SDK's crash reports: `app_crashed`, critical, area `diagnostics`, with
     * `error_type`, `error_message`, `handled: false`, `mechanism: uncaught_exception` and the
     * thread's frames in the payload (docs/sdk-spec.md).
     */
    fun recordUncaught(thread: Thread, error: Throwable) {
        if (stopped || gate.value == Consent.DENIED) return
        val attributes = LinkedHashMap<String, JsonValue>()
        attributes.putAll(Exceptions.attributes(error))
        attributes["handled"] = JsonValue.Bool(false)
        attributes["mechanism"] = JsonValue.Str("uncaught_exception")
        val event = makeEvent(Level.CRITICAL, "app_crashed", "diagnostics", attributes, Exceptions.payload(error, thread)) ?: return
        val locked = try {
            applyLock.tryLock(500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        try {
            // What the writer hasn't reached yet goes first, in order.
            val waiting = intakeLock.withLock {
                val items = intake.toList()
                intake.clear()
                eventsWaiting = 0
                bytesWaiting = 0
                items
            }
            for (item in waiting) apply(item)
            apply(Item.Event(event))
            intakeLock.withLock {
                done += waiting.size
                changed.signalAll()
            }
        } finally {
            if (locked) applyLock.unlock()
        }
    }

    private fun attributesOf(attrs: Map<String, Any?>?): Map<String, JsonValue> {
        if (attrs.isNullOrEmpty()) return emptyMap()
        return (JsonValue.from(attrs) as? JsonValue.Obj)?.fields ?: emptyMap()
    }

    private fun makeEvent(
        level: Level,
        message: String,
        category: String?,
        attrs: Map<String, JsonValue>,
        payload: JsonValue?,
        eventUser: String? = null,
        timestampMillis: Long? = null,
    ): PendingEvent? {
        if (level < options.minimumLevel) return null
        val at = timestampMillis ?: now()
        val contextual = eventUser?.ifEmpty { null } ?: options.contextUser?.let { provider ->
            try {
                provider()?.ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }
        val (user, sessionId, current) = synchronized(stateLock) {
            if (stopped) return null
            Triple(contextual ?: userId, session.touch(now()), screen)
        }
        var attributes = attrs
        if (options.attachScreen && current != null && !attrs.containsKey("screen")) {
            attributes = LinkedHashMap(attrs).also { it["screen"] = JsonValue.Str(current) }
        }
        return PendingEvent(UUID.randomUUID(), at, level, category, message, attributes, payload, user, sessionId)
    }

    private fun record(
        level: Level,
        message: String,
        category: String?,
        attrs: Map<String, JsonValue>,
        payload: JsonValue?,
        user: String? = null,
        timestampMillis: Long? = null,
    ) {
        val event = makeEvent(level, message, category, attrs, payload, user, timestampMillis) ?: return
        submit(Item.Event(event, Estimate.bytes(event)))
        options.onRecord?.invoke(level, category, message)
    }

    private fun submit(item: Item) {
        intakeLock.withLock {
            if (stopping && item is Item.Event) return
            if (item is Item.Control) gate.value = item.consent
            submitted += 1
            if (item is Item.Event) {
                // The oldest waiting events make room (in count and in bytes); they will never be
                // written. The newest is always kept, even alone over the budget.
                while (eventsWaiting > 0 &&
                    (eventsWaiting >= options.intakeCapacity || bytesWaiting + item.bytes > options.intakeMaxBytes)
                ) {
                    val oldest = intake.indexOfFirst { it is Item.Event }
                    if (oldest < 0) break
                    bytesWaiting -= (intake.removeAt(oldest) as Item.Event).bytes
                    eventsWaiting -= 1
                    memoryDrops += 1
                    done += 1
                }
                eventsWaiting += 1
                bytesWaiting += item.bytes
            }
            intake.addLast(item)
            changed.signalAll()
        }
    }

    /** What the intake holds now, by the estimate (tests). */
    val waitingBytes: Long get() = intakeLock.withLock { bytesWaiting }

    // MARK: - Writer

    private fun writeLoop() {
        guarded { queue.enforceLimits() }
        while (true) {
            intakeLock.withLock {
                while (intake.isEmpty() && !stopping) changed.await()
                if (intake.isEmpty()) return
            }
            // The item is taken only while holding applyLock, so a crash being recorded
            // (recordUncaught) can never write ahead of an event the writer already took.
            applyLock.lock()
            try {
                val item = intakeLock.withLock {
                    intake.removeFirstOrNull()?.also {
                        if (it is Item.Event) {
                            eventsWaiting -= 1
                            bytesWaiting -= it.bytes
                        }
                    }
                } ?: continue
                guarded { apply(item) }
            } finally {
                applyLock.unlock()
            }
            intakeLock.withLock {
                done += 1
                changed.signalAll()
            }
        }
    }

    private fun apply(item: Item) {
        when (item) {
            is Item.Control -> when (item.consent) {
                Consent.GRANTED -> queue.promotePending()
                Consent.DENIED -> {
                    queue.removeAll()
                    identity.forget()
                    attestor?.forget()
                }
                Consent.PENDING -> {}
            }
            is Item.Event -> {
                val installId = identity.current(gate) ?: return
                val line = WireEvent.encode(item.event, installId, options.resource, redaction).toByteArray(Charsets.UTF_8)
                if (line.size > Shaping.MAX_LINE_BYTES) {
                    intakeLock.withLock { tooLarge += 1 }
                    return
                }
                appendLine(line)
            }
        }
    }

    /** Where the consent of this moment says: ready when granted, pending when pending, nowhere when denied. */
    private fun appendLine(line: ByteArray) {
        when (gate.value) {
            Consent.GRANTED -> queue.append(line, DiskQueue.Area.READY)
            Consent.PENDING -> queue.append(line, DiskQueue.Area.PENDING)
            Consent.DENIED -> {}
        }
    }

    /** Waits until everything submitted before this call is on disk. False on timeout. */
    private fun awaitWritten(deadline: Long): Boolean {
        intakeLock.withLock {
            val target = submitted
            while (done < target) {
                val left = deadline - System.nanoTime() / 1_000_000
                if (left <= 0 || !writer.isAlive) return done >= target
                changed.await(left, TimeUnit.MILLISECONDS)
            }
            return true
        }
    }

    // MARK: - Uploading

    /** One round, as in the Swift SDK: report drops, seal the open file only when it will be sent, upload. */
    private fun runRound(force: Boolean) {
        if (!uploader.willAttempt(force)) return
        reportDrops()
        // A backlog goes first; the open file is sealed when nothing older waits...
        if (queue.readyBatches().isEmpty()) queue.seal()
        val report = uploader.round(force)
        // ...or once the backlog went through.
        if (!report.stoppedForRetry && report.delivered + report.dropped > 0 && queue.hasOpenEvents && uploader.willAttempt(force)) {
            queue.seal()
            uploader.round(force)
        }
    }

    /** Events lost to the caps since the last report become one `lyrelog_events_dropped` warning. */
    private fun reportDrops() {
        val (memory, large) = intakeLock.withLock {
            val counts = memoryDrops to tooLarge
            memoryDrops = 0
            tooLarge = 0
            counts
        }
        val disk = queue.takeUnreportedDrops()
        val total = memory + large + disk
        if (total == 0) return
        val note = PendingEvent(
            UUID.randomUUID(), now(), Level.WARNING, "lyrelog", "lyrelog_events_dropped",
            linkedMapOf(
                "count" to JsonValue.Integer(total.toLong()),
                "in_memory" to JsonValue.Integer(memory.toLong()),
                "too_large" to JsonValue.Integer(large.toLong()),
                "on_disk" to JsonValue.Integer(disk.toLong()),
                "max_queue" to JsonValue.Integer(options.intakeCapacity.toLong()),
            ),
            null, null, null,
        )
        val installId = identity.current(gate) ?: return
        appendLine(WireEvent.encode(note, installId, options.resource, redaction).toByteArray(Charsets.UTF_8))
    }

    /**
     * Writes everything logged so far to disk and runs one forced upload round (when consent is
     * granted, the key works and the server hasn't asked to wait). Blocks the caller up to
     * [timeoutMillis]: don't call it on Android's main thread ([flushAsync] there). True when
     * it finished in time.
     */
    fun flush(timeoutMillis: Long = 5_000): Boolean {
        if (stopped) return false
        val deadline = System.nanoTime() / 1_000_000 + timeoutMillis
        return try {
            if (!awaitWritten(deadline)) return false
            val future: Future<*> = scheduler.submit { guarded { runRound(true) } }
            future.get(maxOf(1, deadline - System.nanoTime() / 1_000_000), TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Waits until everything logged before this call is on disk, at most [timeoutMillis]; no
     * network. True when it is: from then on it survives the process ending (a wrapper recording
     * its own fatal error, which must not wait on the upload).
     */
    fun flushToDisk(timeoutMillis: Long = 1_000): Boolean {
        if (stopped) return false
        return try {
            awaitWritten(System.nanoTime() / 1_000_000 + timeoutMillis)
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            false
        }
    }

    /** [flush] without waiting: the upload thread writes and sends when it can. */
    fun flushAsync() {
        if (stopped) return
        try {
            scheduler.execute {
                guarded {
                    awaitWritten(System.nanoTime() / 1_000_000 + 2_000)
                    runRound(true)
                }
            }
        } catch (e: Exception) {
            // shut down
        }
    }

    /** Periodic rounds run only in the foreground (Android tells); a flush always runs. */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    /**
     * Sends what was logged (waiting at most [timeoutMillis]), then stops. What couldn't be
     * sent stays on disk for the next launch. Idempotent.
     */
    fun close(timeoutMillis: Long = 2_000) {
        if (stopped) return
        flush(timeoutMillis)
        stopNow()
    }

    /** Stops without sending: events in memory are written to disk (no network), then the threads end. */
    fun stopNow() {
        synchronized(stateLock) {
            if (stopped) return
            stopped = true
        }
        CrashRecorder.detach(this)
        intakeLock.withLock {
            stopping = true
            changed.signalAll()
        }
        try {
            writer.join(1_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        scheduler.shutdownNow()
        attestor?.close()
        guarded { queue.close() }
    }

    /**
     * What Java handed over, as nullable: `core` is compiled without Kotlin's parameter checks
     * (`-Xno-param-assertions`), so a null from Java reaches here instead of throwing a
     * NullPointerException into the code that logged.
     */
    private fun <T : Any> given(value: T?): T? = value

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.once("internal-${e.javaClass.name}", "lyrelog: internal error (${e.javaClass.name}); the event may be lost")
        } catch (e: StackOverflowError) {
            log.once("internal-stack", "lyrelog: internal error (stack overflow); the event may be lost")
        } catch (e: OutOfMemoryError) {
            // nothing sensible to do; never take the app down for a log line
        } catch (e: Throwable) {
            // Any other Error (a class missing on an old Android, an app's TODO()): on Android a
            // Throwable leaving one of our threads ends the app, and one leaving a periodic
            // round would cancel every later round.
            log.once("internal-${e.javaClass.name}", "lyrelog: internal error (${e.javaClass.name}); the event may be lost")
        }
    }

    companion object {
        private const val SDK_USER_AGENT_FALLBACK = "lyrelog-android"
    }
}
