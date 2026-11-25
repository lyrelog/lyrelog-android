package com.lyrelog.jvm

import com.lyrelog.Logger
import com.lyrelog.core.Engine
import com.lyrelog.core.FakeIngest
import com.lyrelog.core.JsonValue
import com.lyrelog.core.Reply
import com.lyrelog.core.SdkLog
import com.lyrelog.core.ServerRules
import com.lyrelog.core.eventually
import com.lyrelog.core.get
import com.lyrelog.core.string
import com.lyrelog.core.tempDir
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyrelogTest {
    private val ingest = FakeIngest()
    private val said = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        Lyrelog.close(1_000)
        ingest.close()
    }

    private fun config(
        apiKey: String? = "lyr_srv_test",
        environment: String? = null,
        storageDirectory: File? = null,
        endpoint: String = ingest.url,
    ) = Configuration(apiKey) {
        this.endpoint = endpoint
        this.environment = environment
        flushIntervalMillis = 3_600_000
        this.storageDirectory = storageDirectory
        shutdownHook = false
        diagnostics { said.add(it) }
    }

    @Test
    fun sendsAServersEventsWithItsKeyAndContext() {
        assertTrue(Lyrelog.start(config()))
        assertTrue(Lyrelog.isStarted)
        Lyrelog.info("checkout_done", attrs = mapOf("plan" to "pro", "latency_ms" to 412, "password" to "hunter2"))
        Lyrelog.logger("billing").warning("invoice_retry", mapOf("attempt" to 2))
        Lyrelog.captureError(IllegalStateException("card declined"), "billing", mapOf("order_id" to "o_1"))
        assertTrue(Lyrelog.flush())
        val request = ingest.received.single()
        assertEquals("Bearer lyr_srv_test", request.headers["authorization"])
        assertEquals("lyrelog-jvm/1.1.0", request.headers["user-agent"])
        request.lines.forEach(ServerRules::check)
        val (first, second, third) = request.events
        assertEquals("checkout_done", first["message"].string)
        assertEquals("[redacted]", first["attrs"]["password"].string)
        val resource = first["resource"]
        assertEquals("lyrelog-jvm/1.1.0", resource["sdk"].string)
        assertEquals("jvm", resource["platform"].string)
        assertEquals("production", resource["environment"].string)
        assertEquals(System.getProperty("os.name"), resource["os_name"].string)
        assertNull(resource["device_model"])
        assertEquals("billing", second["category"].string)
        assertEquals("warning", second["level"].string)
        assertEquals("exception", third["message"].string)
        assertEquals("java.lang.IllegalStateException", third["attrs"]["error_type"].string)
        assertEquals("card declined", third["attrs"]["error_message"].string)
        assertEquals("o_1", third["attrs"]["order_id"].string)
        assertNotNull(third["payload"]["frames"])
        // One install and one session for the process.
        assertEquals(1, request.events.map { it["install_id"].string }.toSet().size)
        assertEquals(1, request.events.map { it["session_id"].string }.toSet().size)
        assertNotNull(first["install_id"].string)
    }

    @Test
    fun withoutAKeyNothingRunsAndNothingThrows() {
        assertFalse(Lyrelog.start(config(apiKey = null)))
        assertFalse(Lyrelog.start(config(apiKey = "  ")))
        assertFalse(Lyrelog.isStarted)
        Lyrelog.info("ignored")
        Lyrelog.logger("x").error("ignored")
        Lyrelog.captureError(RuntimeException())
        Lyrelog.userScope("u").close()
        assertFalse(Lyrelog.flush(100))
        Lyrelog.close()
        assertTrue(ingest.received.isEmpty())
    }

    @Test
    fun aSecondStartIsIgnored() {
        assertTrue(Lyrelog.start(config()))
        assertFalse(Lyrelog.start(config(environment = "staging")))
        Lyrelog.info("one")
        assertTrue(Lyrelog.flush())
        assertEquals("production", ingest.allEvents.single()["resource"]["environment"].string)
    }

    @Test
    fun theEventsUserThenTheScopesThenSetUsers() {
        assertTrue(Lyrelog.start(config()))
        Lyrelog.setUser("service")
        Lyrelog.info("a")
        Lyrelog.userScope("request-1").use {
            Lyrelog.info("b")
            Lyrelog.userScope("nested").use { Lyrelog.logger("http").info("c") }
            Lyrelog.info("d")
            Lyrelog.info("e", user = "explicit")
            // Another thread doesn't see this thread's scope.
            Thread { Lyrelog.info("f") }.apply { start(); join() }
        }
        Lyrelog.captureError(RuntimeException(), user = "failing")
        Lyrelog.info("g")
        assertTrue(Lyrelog.flush())
        val users: Map<String?, String?> = ingest.allEvents.associate { it["message"].string to it["user_id"].string }
        assertEquals<Map<String?, String?>>(
            mapOf("a" to "service", "b" to "request-1", "c" to "nested", "d" to "request-1", "e" to "explicit", "f" to "service", "exception" to "failing", "g" to "service"),
            users,
        )
    }

    @Test
    fun aScopeClosedOnAnotherThreadLeavesThatThreadsUserAlone() {
        // `userScope(id).use { suspendingCall() }` in a coroutine closes on whichever thread it
        // resumed on; that thread's own scope must not become someone else's user, or nobody's.
        assertTrue(Lyrelog.start(config()))
        val opened = Lyrelog.userScope("request-a")
        Thread {
            Lyrelog.userScope("request-b").use {
                opened.close()
                Lyrelog.info("on_b")
            }
        }.apply { start(); join() }
        assertTrue(Lyrelog.flush())
        assertEquals("request-b", ingest.allEvents.single()["user_id"].string)
        opened.close()
    }

    @Test
    fun closeSendsWhatIsLeftAndRemovesTheTemporaryQueue() {
        assertTrue(Lyrelog.start(config()))
        Lyrelog.info("last_words")
        val before = File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("lyrelog-") }!!.toSet()
        Lyrelog.close()
        assertFalse(Lyrelog.isStarted)
        assertEquals(listOf("last_words"), ingest.allEvents.map { it["message"].string })
        val after = File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("lyrelog-") }!!.toSet()
        assertTrue((before - after).isNotEmpty(), "the temporary queue directory was deleted")
        Lyrelog.info("after_close")
        assertTrue(Lyrelog.start(config()), "start works again after close")
    }

    @Test
    fun aConfiguredDirectoryKeepsWhatWasntSentForTheNextStart() {
        val dir = File(tempDir(), "queue")
        // A refused key stops this process sending and keeps the events.
        ingest.replies.add(Reply(401))
        assertTrue(Lyrelog.start(config(storageDirectory = dir)))
        Lyrelog.info("kept_for_later")
        Lyrelog.flush(2_000)
        Lyrelog.close(500)
        assertTrue(dir.walkTopDown().any { it.isFile && it.name.endsWith(".ndjson") }, "the batch stayed in the directory")
        assertTrue(Lyrelog.start(config(storageDirectory = dir)))
        assertTrue(Lyrelog.flush())
        assertEquals("kept_for_later", ingest.received.last().events.single()["message"].string)
    }

    @Test
    fun aLockedDirectoryFallsBackToATemporaryOne() {
        val dir = File(tempDir(), "shared")
        val log = SdkLog { _, message -> said.add(message) }
        val first = QueueDirectory.open(dir, log)
        assertFalse(first.temporary)
        val second = QueueDirectory.open(dir, log)
        assertTrue(second.temporary)
        assertTrue(said.any { it.contains("in use by another process") })
        second.release()
        assertFalse(second.directory.exists())
        first.release()
        assertTrue(dir.exists(), "a configured directory is kept")
        val third = QueueDirectory.open(dir, log)
        assertFalse(third.temporary, "released, it can be locked again")
        third.release()
    }

    @Test
    fun environmentComesFromTheConfigurationThenTheVariable() {
        val log = SdkLog { _, message -> said.add(message) }
        assertEquals("staging-eu", ServerDefaults.environment("Staging EU", "ignored", log))
        assertEquals("staging", ServerDefaults.environment(null, "Staging", log))
        assertEquals("production", ServerDefaults.environment(null, null, log))
        assertEquals("production", ServerDefaults.environment(null, "", log))
        assertTrue(said.isEmpty())
        assertEquals("production", ServerDefaults.environment(null, "not valid!", log))
        assertTrue(said.single().startsWith("lyrelog: LYRELOG_ENVIRONMENT"))
    }

    @Test
    fun theResourceUsesWhatIsConfigured() {
        val resource = ServerDefaults.resource(
            Configuration("k") { appId = "orders-api"; release = "2.4.0"; platform = "Worker"; environment = "staging" },
            SdkLog.silent(),
        )
        assertEquals("orders-api", resource.appId)
        assertEquals("2.4.0", resource.appVersion)
        assertEquals("worker", resource.platform)
        assertEquals("staging", resource.environment)
        assertEquals("lyrelog-jvm/1.1.0", resource.sdk)
    }

    @Test
    fun theStartedJarNamesTheServiceAndItsRelease() {
        val jar = File(tempDir(), "orders-1.0.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_TITLE] = "orders"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = "1.0.3"
        }
        JarOutputStream(FileOutputStream(jar), manifest).close()
        val program = StartedProgram.of("${jar.path} --server.port=8080")
        assertEquals("orders-1.0", program.name)
        assertEquals("orders", program.title)
        assertEquals("1.0.3", program.version)
        assertEquals("com.example.Main", StartedProgram.of("com.example.Main arg").name)
        assertNull(StartedProgram.of(null).name)
        val fromJar = ServerDefaults.resource(Configuration("k"), SdkLog.silent(), null, jar.path)
        assertEquals("orders", fromJar.appId)
        assertEquals("1.0.3", fromJar.appVersion)
    }

    @Test
    fun aWrongEndpointIsSaidOnTheDiagnostics() {
        assertTrue(Lyrelog.start(config(endpoint = "http://example.com")))
        Lyrelog.info("stays")
        Lyrelog.flush(500)
        assertTrue(said.any { it.startsWith("lyrelog: the endpoint must be https") }, "$said")
        assertTrue(ingest.received.isEmpty())
    }

    @Test
    fun theKeyNeverShowsInTheConfiguration() {
        val text = Configuration("lyr_srv_secret").toString()
        assertFalse(text.contains("lyr_srv_secret"))
        assertTrue(Configuration.builder("lyr_srv_secret").platform("api").build().toString().contains("platform=api"))
    }

    @Test
    fun anUncaughtExceptionIsAnUnhandledExceptionEventAndThePreviousHandlerRuns() {
        assertTrue(Lyrelog.start(config()))
        val engine = engineOf()
        val seen = CopyOnWriteArrayList<Throwable>()
        val handler = UncaughtRecorder.handler({ _, e -> seen.add(e) }) { engine }
        val thread = Thread({}, "worker-7")
        handler.uncaughtException(thread, IllegalArgumentException("boom"))
        assertEquals(1, seen.size)
        assertTrue(Lyrelog.flush())
        val event = ingest.allEvents.single()
        ServerRules.check(event.encode())
        assertEquals("exception", event["message"].string)
        assertEquals("error", event["level"].string)
        assertEquals(JsonValue.Bool(false), event["attrs"]["handled"])
        assertEquals("uncaught_exception", event["attrs"]["mechanism"].string)
        assertEquals("worker-7", event["attrs"]["thread"].string)
    }

    /** The running engine, through the logger's supplier (tests only). */
    private fun engineOf(): Engine {
        val logger = Lyrelog.logger("x")
        val field = Logger::class.java.getDeclaredField("engine").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(logger) as () -> Engine?)()!!
    }

    @Test
    fun withoutTheHookTheApplicationClosesLyrelogLastAndKeepsItsShutdownLogs() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.lyrelog.jvm.LateClosingServer", ingest.url)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        eventually(5_000) { ingest.allEvents.size >= 2 }
        assertEquals(listOf("started", "graceful_shutdown_done"), ingest.allEvents.map { it["message"].string }, output)
    }

    /** A coroutine keeps its user across suspensions and threads, and leaves the threads it ran on clean. */
    @Test
    fun aCoroutineCarriesItsUser() {
        assertTrue(Lyrelog.start(config()))
        Lyrelog.setUser("service")
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(LyrelogUser("u_7")) {
                Lyrelog.info("before_suspending")
                kotlinx.coroutines.delay(10)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Lyrelog.info("on_another_thread") }
                Lyrelog.info("own_user", user = "u_8")
            }
            Lyrelog.info("after_the_scope")
        }
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { Lyrelog.info("an_io_thread_later") }
        assertTrue(Lyrelog.flush())
        val users: Map<String?, String?> = ingest.allEvents.associate { it["message"].string to it["user_id"].string }
        assertEquals(
            mapOf<String?, String?>(
                "before_suspending" to "u_7", "on_another_thread" to "u_7", "own_user" to "u_8",
                "after_the_scope" to "service", "an_io_thread_later" to "service",
            ),
            users,
        )
    }

    private fun java(main: String, vararg args: String, tmp: File? = null): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = mutableListOf(java)
        if (tmp != null) command += "-Djava.io.tmpdir=${tmp.path}"
        command += listOf("-cp", System.getProperty("java.class.path"), main)
        command += args
        return ProcessBuilder(command).redirectErrorStream(true).start()
    }

    /** With the hook on, what another shutdown hook logs (Spring closing its context) still goes. */
    @Test
    fun theShutdownHookWaitsForTheFrameworksShutdownLogs() {
        val process = java("com.lyrelog.jvm.FrameworkShutdownServer", ingest.url)
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        eventually(5_000) { ingest.allEvents.size >= 2 }
        assertEquals(listOf("started", "context_closed"), ingest.allEvents.map { it["message"].string }, output)
    }

    /** A thread that never stops logging can't hold the exit past the close timeout. */
    @Test
    fun theShutdownHookNeverHoldsTheExitPastTheCloseTimeout() {
        val process = java("com.lyrelog.jvm.NoisyShutdownServer", ingest.url)
        val output = process.inputStream.bufferedReader().readText()
        val ended = System.currentTimeMillis()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val exiting = Regex("exiting at (\\d+)").find(output)?.groupValues?.get(1)?.toLong()
        assertNotNull(exiting, output)
        assertTrue(ended - exiting < NoisyShutdownServer.CLOSE_TIMEOUT_MILLIS + 1_500, "the exit took ${ended - exiting} ms")
        assertTrue(ingest.allEvents.any { it["message"].string == "still_busy" }, output)
    }

    /** A process killed with -9 leaves its temporary queue; the next start removes it, never a live one's. */
    @Test
    fun aKilledProcessesTemporaryQueueIsRemovedAtTheNextStart() {
        val tmp = tempDir()
        // The refusal keeps the events on disk, as a process killed before sending would.
        ingest.replies.add(Reply(401))
        val killed = java("com.lyrelog.jvm.KilledServer", ingest.url, tmp = tmp)
        val line = killed.inputStream.bufferedReader().readLine()
        assertEquals("ready", line)
        killed.destroyForcibly()
        assertTrue(killed.waitFor(30, TimeUnit.SECONDS))
        val left = tmp.listFiles { f -> f.name.startsWith("lyrelog-") }!!.single()
        assertTrue(left.walkTopDown().any { it.isFile && it.name.endsWith(".ndjson") }, "unsent events were left behind")
        // A live process's queue, of this JVM, and one just made (its lock not taken yet) stay.
        val live = QueueDirectory.open(null, SdkLog { _, _ -> }, tmp)
        val fresh = File(tmp, "lyrelog-queue-fresh").apply { mkdirs(); File(this, QueueDirectory.LOCK_FILE).writeText("") }
        val foreign = File(tmp, "lyrelog-other").apply { mkdirs() }
        File(left, QueueDirectory.LOCK_FILE).setLastModified(System.currentTimeMillis() - 10 * 60_000)
        File(live.directory, QueueDirectory.LOCK_FILE).setLastModified(System.currentTimeMillis() - 10 * 60_000)

        val next = QueueDirectory.open(null, SdkLog { _, _ -> }, tmp)
        assertFalse(left.exists(), "the dead process's queue was removed")
        assertTrue(live.directory.exists() && fresh.exists() && foreign.exists() && next.directory.exists())
        next.release()
        live.release()
        assertFalse(live.directory.exists())
    }

    @Test
    fun theShutdownHookSendsWhatIsLeftAndAnUncaughtCrashOfMain() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.lyrelog.jvm.ExitingServer", ingest.url)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        eventually(5_000) { ingest.allEvents.size >= 2 }
        val messages = ingest.allEvents.map { it["message"].string }
        assertEquals(listOf("started", "exception"), messages, output)
        val crash = ingest.allEvents[1]
        assertEquals(JsonValue.Bool(false), crash["attrs"]["handled"])
        assertEquals("main", crash["attrs"]["thread"].string)
        assertTrue(output.contains("Exception in thread \"main\""), "the JVM's usual printout still runs: $output")
    }
}

/**
 * Started in its own JVM: without the shutdown hook, the application's own shutdown logs its
 * last steps and closes Lyrelog last (as a Spring `SmartLifecycle` would); nothing is lost.
 */
object LateClosingServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Lyrelog.start(Configuration("lyr_srv_test") { endpoint = args[0]; flushIntervalMillis = 3_600_000; shutdownHook = false })
        Runtime.getRuntime().addShutdownHook(Thread {
            Thread.sleep(300) // the framework stopping its web server, its pools...
            Lyrelog.info("graceful_shutdown_done")
            Lyrelog.close()
        })
        Lyrelog.info("started")
    }
}

/** Started in its own JVM by the test above: logs, then dies of an uncaught exception without closing. */
object ExitingServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Lyrelog.start(Configuration("lyr_srv_test") { endpoint = args[0]; flushIntervalMillis = 3_600_000; captureUncaught = true })
        Lyrelog.info("started")
        throw IllegalStateException("main died")
    }
}

/** Started in its own JVM, with the hook on: a framework's own hook logs as it closes, a little later. */
object FrameworkShutdownServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Lyrelog.start(Configuration("lyr_srv_test") { endpoint = args[0]; flushIntervalMillis = 3_600_000 })
        Runtime.getRuntime().addShutdownHook(Thread {
            Thread.sleep(300) // the context closing its beans
            Lyrelog.info("context_closed")
        })
        Lyrelog.info("started")
    }
}

/** Started in its own JVM, with the hook on: a thread keeps logging through the shutdown. */
object NoisyShutdownServer {
    const val CLOSE_TIMEOUT_MILLIS = 2_000L

    @JvmStatic
    fun main(args: Array<String>) {
        Lyrelog.start(Configuration("lyr_srv_test") { endpoint = args[0]; flushIntervalMillis = 3_600_000; closeTimeoutMillis = CLOSE_TIMEOUT_MILLIS })
        Thread {
            while (true) {
                Lyrelog.info("still_busy")
                Thread.sleep(20)
            }
        }.apply { isDaemon = true }.start()
        Thread.sleep(100)
        println("exiting at ${System.currentTimeMillis()}")
        System.out.flush()
        System.exit(0)
    }
}

/** Started in its own JVM with its own temporary folder, then killed with -9 after logging. */
object KilledServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Lyrelog.start(Configuration("lyr_srv_test") { endpoint = args[0]; flushIntervalMillis = 3_600_000; diagnostics { } })
        Lyrelog.info("never_sent")
        Lyrelog.flush(2_000)
        println("ready")
        System.out.flush()
        Thread.sleep(60_000)
    }
}
