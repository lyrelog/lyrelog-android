package com.lyrelog.core

import com.lyrelog.Consent
import com.lyrelog.Level
import com.lyrelog.Logger
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val ingest = FakeIngest()
    private val engines = ArrayList<Engine>()
    private val said = CopyOnWriteArrayList<String>()
    private val log = SdkLog { _, message -> said.add(message) }

    @AfterTest
    fun tearDown() {
        engines.forEach { it.stopNow() }
        ingest.close()
    }

    private fun engine(opts: EngineOptions = options(ingest.url), now: () -> Long = System::currentTimeMillis) =
        Engine(opts, log = log, now = now).also { engines.add(it) }

    private fun quota() = Reply(429, """{"error":"this organization used its 1000000 events for the month.","plan":"free","limit":1000000,"used":1000412}""")

    @Test
    fun sendsOneGzippedBatchWithTheContract() {
        val e = engine()
        e.log(Level.INFO, "checkout_done", "checkout", mapOf("items" to 3, "payment" to mapOf("method" to "card")), mapOf("response" to "ok"))
        e.log(Level.DEBUG, "two")
        e.log(Level.TRACE, "below the minimum level")
        assertTrue(e.flush())
        val request = ingest.received.single()
        assertEquals("/v1/events:batch", request.headers[":path"])
        assertEquals("POST", request.headers[":method"])
        assertEquals("Bearer lyr_ing_test", request.headers["authorization"])
        assertEquals("application/x-ndjson", request.headers["content-type"])
        assertEquals("gzip", request.headers["content-encoding"])
        assertEquals("lyrelog-android/1.0.0", request.headers["user-agent"])
        request.lines.forEach(ServerRules::check)
        assertEquals(listOf("checkout_done", "two"), request.messages())
        val first = request.events[0]
        assertEquals(JsonValue.Integer(3), first["attrs"]["items"])
        assertEquals("card", first["attrs"]["payment"]["method"].string)
        assertEquals("ok", first["payload"]["response"].string)
        assertEquals(1, request.events.map { it["install_id"].string }.toSet().size)
        assertEquals(e.sessionId, first["session_id"].string)
    }

    @Test
    fun anEventsOwnUserAndTimeWinOverTheContextsAndSetUsers() {
        val context = ThreadLocal<String?>()
        val base = options(ingest.url)
        val e = engine(EngineOptions(base.apiKey, base.storageDirectory, base.installIdStore, base.resource, endpoint = ingest.url,
            flushIntervalMillis = base.flushIntervalMillis, contextUser = { context.get() }))
        e.setUser("everyone")
        e.log(Level.INFO, "from_set_user")
        context.set("request-user")
        e.log(Level.INFO, "from_context")
        e.log(Level.INFO, "own_user", user = "own", timestampMillis = 1_700_000_000_123)
        e.captureError(IllegalStateException("x"), user = "failing")
        context.remove()
        e.log(Level.INFO, "back_to_set_user")
        assertTrue(e.flush())
        val events = ingest.allEvents
        events.forEach { ServerRules.check(it.encode()) }
        assertEquals(listOf("everyone", "request-user", "own", "failing", "everyone"), events.map { it["user_id"].string })
        assertEquals("2023-11-14T22:13:20.123Z", events[2]["ts"].string)
    }

    @Test
    fun batchesHoldAtMost500Events() {
        val e = engine()
        repeat(1200) { e.log(Level.INFO, "e$it") }
        assertTrue(e.flush(10_000))
        assertEquals(listOf(500, 500, 200), ingest.received.map { it.lines.size })
        assertEquals((0 until 1200).map { "e$it" }, ingest.allEvents.map { it["message"].string })
    }

    @Test
    fun aRetryResendsExactlyTheSameEvents() {
        ingest.replies.add(Reply(503, """{"error":"busy"}"""))
        val e = engine()
        e.log(Level.INFO, "once")
        e.flush()
        e.flush() // a flush skips our own backoff (no Retry-After here)
        assertEquals(2, ingest.received.size)
        assertEquals(ingest.received[0].body, ingest.received[1].body)
        assertContentEquals(ingest.received[0].raw, ingest.received[1].raw)
        e.flush()
        assertEquals(2, ingest.received.size)
    }

    @Test
    fun networkErrorsKeepTheEvents() {
        val dir = tempDir()
        val offline = engine(options("http://127.0.0.1:1", dir))
        offline.log(Level.INFO, "offline")
        offline.flush()
        offline.stopNow()
        val online = engine(options(ingest.url, dir))
        assertTrue(online.flush())
        assertEquals(listOf("offline"), ingest.allEvents.map { it["message"].string })
    }

    @Test
    fun unauthorizedStopsSendingUntilRestartAndKeepsTheData() {
        ingest.replies.add(Reply(401, """{"error":"unknown key"}"""))
        val dir = tempDir()
        val e = engine(options(ingest.url, dir))
        e.log(Level.INFO, "a")
        e.flush()
        e.log(Level.INFO, "b")
        e.flush()
        assertEquals(1, ingest.received.size)
        assertTrue(e.uploader.disabled)
        assertTrue(said.any { it.contains("refused (401)") })
        e.close()
        // The next launch sends everything that was kept.
        val next = engine(options(ingest.url, dir))
        assertTrue(next.flush())
        assertEquals(listOf("a", "b"), ingest.received.drop(1).flatMap { it.messages() })
    }

    @Test
    fun aRefusedBatchIsDroppedAndTheDropReported() {
        ingest.replies.add(Reply(400, """{"error":"empty batch"}"""))
        val e = engine()
        e.log(Level.INFO, "bad")
        e.flush()
        e.log(Level.INFO, "next")
        e.flush()
        assertEquals(2, ingest.received.size)
        val second = ingest.received[1].events
        val note = second.single { it["message"].string == "lyrelog_events_dropped" }
        assertEquals("lyrelog", note["category"].string)
        assertEquals("warning", note["level"].string)
        assertEquals(JsonValue.Integer(1), note["attrs"]["count"])
        assertEquals(JsonValue.Integer(1), note["attrs"]["on_disk"])
        assertTrue(second.any { it["message"].string == "next" })
        assertFalse(second.any { it["message"].string == "bad" })
    }

    @Test
    fun aRedirectIsNotFollowed() {
        ingest.replies.add(Reply(302, "", mapOf("Location" to "${ingest.url}/elsewhere")))
        val e = engine()
        e.log(Level.INFO, "x")
        e.flush()
        assertEquals(1, ingest.received.size)
        assertTrue(said.any { it.contains("redirect") })
        e.flush()
        // Only the drop note follows; nothing ever went to the redirect's target.
        assertEquals(listOf("lyrelog_events_dropped"), ingest.received.drop(1).flatMap { it.messages() })
        assertTrue(ingest.received.all { it.headers[":path"] == "/v1/events:batch" })
    }

    @Test
    fun rejectedLinesAreNotResent() {
        ingest.replies.add(Reply(200, """{"accepted":1,"rejected":[{"line":2,"error":"bad"}]}"""))
        val e = engine()
        e.log(Level.INFO, "a")
        e.log(Level.INFO, "b")
        e.flush()
        e.flush()
        assertEquals(1, ingest.received.size)
        assertTrue(said.any { it.contains("rejected 1 line") })
    }

    @Test
    fun retryAfterIsHonouredEvenByAFlush() {
        ingest.replies.add(Reply(429, """{"error":"rate limit for this project"}""", mapOf("Retry-After" to "60")))
        val e = engine()
        e.log(Level.INFO, "x")
        e.flush()
        e.flush()
        assertEquals(1, ingest.received.size)
        assertFalse(e.uploader.willAttempt(force = true))
    }

    @Test
    fun verificationRequired425IsRetried() {
        ingest.replies.add(Reply(425, """{"error":"verified events only","verification_required":"android"}""", mapOf("Retry-After" to "0")))
        val e = engine()
        e.log(Level.INFO, "waits")
        e.flush()
        e.flush()
        assertEquals(2, ingest.received.size)
        assertEquals(ingest.received[0].body, ingest.received[1].body)
    }

    @Test
    fun theQuotaWarningIsSaidOnceUntilABatchGoesThrough() {
        ingest.replies.addAll(listOf(quota(), quota(), Reply(200, """{"accepted":1,"rejected":[]}"""), quota()))
        val e = engine()
        e.log(Level.INFO, "a")
        e.flush()
        e.flush()
        assertEquals(1, e.uploader.quotaWarnings)
        e.flush() // delivered: re-arms the warning
        e.log(Level.INFO, "b")
        e.flush()
        assertEquals(2, e.uploader.quotaWarnings)
        val warnings = said.filter { it.startsWith("Lyrelog: this organization used") }
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("on disk, up to 20 MB and 7 days"))
        assertFalse(said.any { it.contains("lyr_ing_test") })
    }

    @Test
    fun pendingConsentKeepsEventsUntilGranted() {
        val dir = tempDir()
        val e = engine(options(ingest.url, dir, consent = Consent.PENDING))
        e.log(Level.INFO, "kept")
        e.flush()
        assertTrue(ingest.received.isEmpty())
        e.close()
        // Kept across a restart while still pending, sent once granted.
        val next = engine(options(ingest.url, dir, consent = Consent.PENDING))
        next.flush()
        assertTrue(ingest.received.isEmpty())
        next.setConsent(Consent.GRANTED)
        next.log(Level.INFO, "after")
        assertTrue(next.flush())
        assertEquals(listOf("kept", "after"), ingest.allEvents.map { it["message"].string })
    }

    @Test
    fun aDenialDeletesEverythingAndTheInstallId() {
        val dir = tempDir()
        val e = engine(options(ingest.url, dir, consent = Consent.PENDING))
        e.log(Level.INFO, "secret")
        e.flush()
        val firstId = File(dir, "install_id").readText()
        e.setConsent(Consent.DENIED)
        e.log(Level.INFO, "while denied")
        e.flush()
        assertTrue(e.queue.isEmpty())
        assertFalse(File(dir, "install_id").exists())
        e.setConsent(Consent.GRANTED)
        e.log(Level.INFO, "fresh")
        assertTrue(e.flush())
        assertEquals(listOf("fresh"), ingest.allEvents.map { it["message"].string })
        assertNotEquals(firstId, ingest.allEvents.single()["install_id"].string)
    }

    @Test
    fun closeSendsWhatWasLogged() {
        val e = engine()
        repeat(10) { e.log(Level.INFO, "bye$it") }
        e.close()
        assertEquals(10, ingest.allEvents.size)
        e.log(Level.INFO, "after close")
        assertFalse(e.flush())
    }

    @Test
    fun theSessionRotatesAfterThirtyIdleMinutes() {
        var clock = 1_800_000_000_000L
        val e = engine(now = { clock })
        e.log(Level.INFO, "a")
        clock += 29 * 60_000L
        e.log(Level.INFO, "b")
        clock += 31 * 60_000L
        e.log(Level.INFO, "c")
        e.flush()
        val sessions = ingest.allEvents.map { it["session_id"].string }
        assertEquals(sessions[0], sessions[1])
        assertNotEquals(sessions[1], sessions[2])
    }

    @Test
    fun theUserIsAttachedUntilCleared() {
        val e = engine()
        e.setUser("user-1")
        e.log(Level.INFO, "a")
        e.setUser(null)
        e.log(Level.INFO, "b")
        e.flush()
        assertEquals("user-1", ingest.allEvents[0]["user_id"].string)
        assertNull(ingest.allEvents[1]["user_id"])
    }

    @Test
    fun screensAreLoggedAndAttached() {
        var clock = 1_800_000_000_000L
        val e = engine(now = { clock })
        e.screen("Board")
        e.screen("Board")
        clock += 12_340
        e.log(Level.INFO, "tile_dropped")
        e.screen("Settings", mapOf("from" to "menu"))
        e.flush()
        val events = ingest.allEvents
        assertEquals(listOf("screen_viewed", "tile_dropped", "screen_viewed"), events.map { it["message"].string })
        assertEquals("navigation", events[0]["category"].string)
        assertEquals("Board", events[1]["attrs"]["screen"].string)
        val second = events[2]["attrs"]
        assertEquals("Settings", second["screen"].string)
        assertEquals("Board", second["previous_screen"].string)
        assertEquals(JsonValue.Decimal(12.3), second["seconds_on_previous"])
        assertEquals("menu", second["from"].string)
    }

    @Test
    fun captureErrorSendsTheExceptionEventWithAStack() {
        val e = engine()
        val error = IllegalStateException("board is full", java.io.IOException("disk"))
        e.captureError(error, "game", mapOf("level_id" to 7, "error_type" to "overridden?"))
        Logger("store") { e }.captureError(RuntimeException("x"))
        e.flush()
        val (event, logged) = ingest.allEvents
        ServerRules.check(event.encode())
        assertEquals("exception", event["message"].string)
        assertEquals("error", event["level"].string)
        assertEquals("game", event["category"].string)
        assertEquals("java.lang.IllegalStateException", event["attrs"]["error_type"].string)
        assertEquals("board is full", event["attrs"]["error_message"].string)
        assertEquals(JsonValue.Integer(7), event["attrs"]["level_id"])
        val frames = event["payload"]["frames"] as JsonValue.Arr
        assertTrue(frames.items.first().string!!.contains("EngineTest.captureErrorSendsTheExceptionEventWithAStack"))
        assertEquals("java.io.IOException", (event["payload"]["causes"] as JsonValue.Arr).items[0]["type"].string)
        assertEquals("store", logged["category"].string)
    }

    @Test
    fun areaLoggersCarryTheirCategory() {
        val e = engine()
        val checkout = Logger("checkout") { e }
        checkout.warning("payment_retry", mapOf("attempt" to 2))
        checkout.trace("dropped below the minimum")
        Logger("none") { null }.info("before start: ignored")
        e.flush()
        val event = ingest.allEvents.single()
        assertEquals("checkout", event["category"].string)
        assertEquals("warning", event["level"].string)
    }

    @Test
    fun anUncaughtExceptionIsWrittenNowAndSentOnTheNextLaunch() {
        val dir = tempDir()
        val dead = Engine(options("http://127.0.0.1:1", dir), log = log)
        repeat(50) { dead.log(Level.INFO, "before$it") }
        val handled = CopyOnWriteArrayList<Throwable>()
        val handler = CrashRecorder.handler({ _, error -> handled.add(error) }) { dead }
        val crash = IllegalArgumentException("tile out of bounds")
        handler.uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf<Throwable>(crash), handled) // the previous handler still runs
        // The process dies here: nothing more runs, not even stop.
        val next = engine(options(ingest.url, dir))
        assertTrue(next.flush())
        val events = ingest.allEvents
        assertEquals(51, events.size)
        assertEquals((0 until 50).map { "before$it" }, events.take(50).map { it["message"].string })
        val crashed = events.last()
        assertEquals("app_crashed", crashed["message"].string) // filed like the Swift SDK's crashes
        assertEquals("critical", crashed["level"].string)
        assertEquals("diagnostics", crashed["category"].string)
        assertEquals(JsonValue.Bool(false), crashed["attrs"]["handled"])
        assertEquals("uncaught_exception", crashed["attrs"]["mechanism"].string)
        assertEquals("java.lang.IllegalArgumentException", crashed["attrs"]["error_type"].string)
        assertEquals(Thread.currentThread().name, crashed["payload"]["thread"].string)
        dead.stopNow()
    }

    @Test
    fun anEventTooLargeForALineIsDroppedAndCounted() {
        val e = engine()
        val huge = (1..30).associate { "list$it" to List(64) { "x".repeat(1000) } }
        e.log(Level.INFO, "huge", attrs = huge)
        e.flush()
        e.log(Level.INFO, "next")
        e.flush()
        val note = ingest.allEvents.single { it["message"].string == "lyrelog_events_dropped" }
        assertEquals(JsonValue.Integer(1), note["attrs"]["too_large"])
        assertFalse(ingest.allEvents.any { it["message"].string == "huge" })
    }

    @Test
    fun theOldestEventsGoWhenTheIntakeIsFull() {
        val e = engine(options(ingest.url, intakeCapacity = 10))
        repeat(20_000) { e.log(Level.INFO, "e$it") }
        assertTrue(e.flush(20_000))
        e.flush(20_000)
        val messages = ingest.allEvents.map { it["message"].string }
        assertTrue(messages.contains("e19999"))
        val note = ingest.allEvents.filter { it["message"].string == "lyrelog_events_dropped" }
        val dropped = note.sumOf { (it["attrs"]["in_memory"] as JsonValue.Integer).value }
        assertEquals(20_000L, messages.count { it!!.startsWith("e") } + dropped)
    }

    @Test
    fun aPlainHttpEndpointElsewhereIsRefusedAndTheEventsStay() {
        val e = engine(options("http://ingest.example.com"))
        e.log(Level.INFO, "kept")
        e.flush()
        assertTrue(ingest.received.isEmpty())
        assertFalse(e.queue.isEmpty())
        assertTrue(said.any { it.contains("must be https") })
    }

    @Test
    fun periodicRoundsSendWithoutAFlushOnlyInTheForeground() {
        val e = engine(options(ingest.url, flushIntervalMillis = 100))
        e.setForeground(false)
        e.log(Level.INFO, "background")
        Thread.sleep(400)
        assertTrue(ingest.received.isEmpty())
        e.setForeground(true)
        eventually { ingest.allEvents.size == 1 }
        e.flushAsync()
    }

    @Test
    fun loggingNeverThrows() {
        val e = engine()
        val cyclic = ArrayList<Any?>()
        cyclic.add(cyclic)
        e.log(Level.INFO, "odd", attrs = mapOf("cycle" to cyclic, "bad" to object { override fun toString(): String = error("boom") }), payload = cyclic)
        assertTrue(e.flush())
        ingest.received.single().lines.forEach(ServerRules::check)
    }

    @Test
    fun aValueWhoseToStringThrowsAnErrorNeverReachesTheCaller() {
        val e = engine()
        // Kotlin's TODO() throws NotImplementedError, an Error, not an Exception.
        e.log(Level.INFO, "todo", attrs = mapOf("half_done" to object { override fun toString(): String = TODO() }))
        assertTrue(e.flush())
        val event = ingest.received.single().events.single()
        assertEquals("todo", event["message"].string)
        assertTrue(event["attrs"]["half_done"].string!!.isNotEmpty())
    }

    /**
     * On Android any Throwable that leaves one of the SDK's threads ends the app (the system's
     * default handler kills the process), so the writer must survive an Error, not only an Exception.
     */
    @Test
    fun anErrorOnTheWriterThreadNeitherKillsItNorTheApp() {
        val dir = tempDir()
        var failOnce = true
        val store = object : InstallIdStore {
            val file = FileInstallIdStore(File(dir, "install_id"))
            override fun load(): String? {
                if (failOnce) {
                    failOnce = false
                    throw LinkageError("a class missing on this Android version")
                }
                return file.load()
            }
            override fun save(id: String) = file.save(id)
            override fun clear() = file.clear()
        }
        val base = options(ingest.url, dir)
        val e = engine(EngineOptions(base.apiKey, base.storageDirectory, store, base.resource, endpoint = ingest.url,
            flushIntervalMillis = base.flushIntervalMillis))
        e.log(Level.INFO, "lost_to_the_error")
        e.log(Level.INFO, "after")
        assertTrue(e.flush())
        assertEquals(listOf("after"), ingest.received.single().messages())
    }

    /** An Error in a periodic round must not cancel the periodic task (a ScheduledExecutor would, silently). */
    @Test
    fun anErrorInAPeriodicRoundDoesNotStopTheNextOnes() {
        val real = UrlConnectionTransport()
        var failOnce = true
        val transport = Transport { url, headers, body ->
            if (failOnce) {
                failOnce = false
                throw NoClassDefFoundError("a class missing on this Android version")
            }
            real.post(url, headers, body)
        }
        val e = Engine(options(ingest.url, flushIntervalMillis = 100), transport = transport, log = log).also { engines.add(it) }
        e.log(Level.INFO, "periodic")
        eventually { ingest.allEvents.size == 1 }
        assertFalse(failOnce)
    }

    /** A wrapper reloading (React Native's dev reload) stops and starts again: no thread may stay behind. */
    @Test
    fun stoppingEndsEveryThreadItStarted() {
        fun ours() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("lyrelog-") }.toSet()
        val before = ours()
        val keystore = object : AttestationProvider {
            override fun createKey(challenge: ByteArray): List<ByteArray> = throw AttestationUnsupported("test")
            override fun hasKey() = false
            override fun sign(data: ByteArray): ByteArray = throw AttestationUnsupported("test")
            override fun deleteKey() {}
        }
        repeat(3) {
            val base = options(ingest.url)
            val e = Engine(EngineOptions(base.apiKey, base.storageDirectory, base.installIdStore, base.resource, endpoint = ingest.url,
                flushIntervalMillis = base.flushIntervalMillis, attestation = keystore), log = log)
            e.log(Level.INFO, "x")
            e.close()
        }
        eventually { (ours() - before).isEmpty() }
    }

    /**
     * A wrapper recording its own fatal error (React Native's `logNow`) needs to know the event is
     * on disk, not that the upload finished: on a slow network `flush` times out although the
     * crash is safe, and the wrapper would let the native recorder report it a second time.
     */
    @Test
    fun flushToDiskWaitsForTheDiskAloneNotTheNetwork() {
        val transport = Transport { _, _, _ ->
            Thread.sleep(3_000)
            throw java.io.IOException("slow network")
        }
        val e = Engine(options(ingest.url), transport = transport, log = log).also { engines.add(it) }
        e.log(Level.CRITICAL, "app_crashed")
        assertFalse(e.flush(300)) // the upload is still waiting on the network
        e.log(Level.CRITICAL, "app_crashed_again")
        val started = System.nanoTime()
        assertTrue(e.flushToDisk(1_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 900)
        val onDisk = File(e.queue.root, "ready").listFiles().orEmpty().joinToString("") { it.readText() }
        assertTrue(onDisk.contains("app_crashed_again"))
    }

    /** A stop and a new start (a wrapper's reload) must not stack handlers: a crash is recorded once. */
    @Test
    fun startingAgainDoesNotStackTheCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val first = engine()
            CrashRecorder.install(first)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            first.stopNow()
            val second = engine()
            CrashRecorder.install(second)
            assertTrue(installed === Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /**
     * The intake is bounded in bytes too (16 MiB, like the JavaScript, Python and Go SDKs' memory
     * queues): 10,000 one-megabyte bodies waiting for a slow writer would take 20 GB of the app's
     * heap. The oldest go first and are counted.
     */
    @Test
    fun theIntakeHoldsAtMost16MiBOfEvents() {
        val dir = tempDir()
        val writerStuck = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : InstallIdStore {
            val file = FileInstallIdStore(File(dir, "install_id"))
            override fun load(): String? {
                writerStuck.countDown()
                release.await()
                return file.load()
            }
            override fun save(id: String) = file.save(id)
            override fun clear() = file.clear()
        }
        val base = options(ingest.url, dir)
        val e = engine(EngineOptions(base.apiKey, base.storageDirectory, store, base.resource, endpoint = ingest.url,
            flushIntervalMillis = base.flushIntervalMillis))
        val body = "x".repeat(1_000_000) // 2 MB as a Java string
        e.log(Level.INFO, "first")
        assertTrue(writerStuck.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repeat(100) { e.log(Level.INFO, "body$it", payload = mapOf("body" to body)) }
        assertTrue(e.waitingBytes <= EngineOptions.DEFAULT_INTAKE_MAX_BYTES)
        release.countDown()
        assertTrue(e.flush(20_000))
        e.flush(20_000)
        val messages = ingest.allEvents.mapNotNull { it["message"].string }
        assertTrue(messages.contains("body99"))
        val kept = messages.count { it.startsWith("body") }
        assertTrue(kept in 1..8, "kept $kept")
        val dropped = ingest.allEvents.filter { it["message"].string == "lyrelog_events_dropped" }
            .sumOf { (it["attrs"]["in_memory"] as JsonValue.Integer).value }
        assertEquals(100L, kept + dropped)
    }

    /** Every SDK uploads at most once a second on its timer; a smaller interval is raised to it. */
    @Test
    fun anIntervalUnderASecondIsRaisedToOne() {
        assertEquals(1_000L, options(ingest.url, flushIntervalMillis = 100).effectiveFlushIntervalMillis)
        assertEquals(5_000L, options(ingest.url, flushIntervalMillis = 5_000).effectiveFlushIntervalMillis)
        val e = engine(options(ingest.url, flushIntervalMillis = 100))
        e.log(Level.INFO, "one")
        Thread.sleep(600)
        assertEquals(0, ingest.received.size, "a 100 ms timer would have sent by now")
        eventually { ingest.received.size == 1 }
    }

    /** Android starts a process for a push or a job without any Activity: no periodic uploads then. */
    @Test
    fun startedInTheBackgroundNoTimerRunsUntilTheForeground() {
        val e = engine(options(ingest.url, flushIntervalMillis = 1_000, startInForeground = false))
        e.log(Level.INFO, "from_a_push")
        Thread.sleep(1_600)
        assertEquals(0, ingest.received.size)
        e.setForeground(true)
        eventually { ingest.received.size == 1 }
    }
}
