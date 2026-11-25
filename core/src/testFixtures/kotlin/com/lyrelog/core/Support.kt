package com.lyrelog.core

import com.lyrelog.Consent
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.test.assertTrue
import kotlin.test.fail

/** A request the fake ingest received, decompressed. */
class Received(val headers: Map<String, String>, val raw: ByteArray, val body: String) {
    val lines: List<String> get() = body.split('\n').filter { it.isNotEmpty() }
    val events: List<JsonValue> get() = lines.map { Json.parse(it) ?: fail("not JSON: $it") }
    fun messages() = events.map { it["message"].string }
}

class Reply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/**
 * A local HTTP server standing in for the ingest: records each batch, answers with the next
 * scripted reply, or 200 with every line accepted.
 */
class FakeIngest : AutoCloseable {
    val received = CopyOnWriteArrayList<Received>()
    val replies = ConcurrentLinkedQueue<Reply>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val raw = exchange.requestBody.readBytes()
            val gzip = exchange.requestHeaders.getFirst("Content-Encoding") == "gzip"
            val body = String(if (gzip) GZIPInputStream(raw.inputStream()).readBytes() else raw, Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() } +
                mapOf(":path" to exchange.requestURI.path, ":method" to exchange.requestMethod)
            received.add(Received(headers, raw, body))
            val reply = replies.poll() ?: Reply(200, """{"accepted":${body.split('\n').count { it.isNotEmpty() }},"rejected":[]}""")
            for ((k, v) in reply.headers) exchange.responseHeaders.add(k, v)
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    val allEvents: List<JsonValue> get() = received.flatMap { it.events }

    override fun close() = server.stop(0)
}

fun tempDir(): File = Files.createTempDirectory("lyrelog-test").toFile().also { it.deleteOnExit() }

fun options(
    endpoint: String,
    dir: File = tempDir(),
    consent: Consent = Consent.GRANTED,
    flushIntervalMillis: Long = 3_600_000,
    resource: Resource = testResource,
    maxBatchEvents: Int = 500,
    maxBatchBytes: Int = 512 * 1024,
    intakeCapacity: Int = 10_000,
    sessionTimeoutMillis: Long = 30 * 60_000L,
    redactedKeys: List<String>? = null,
    startInForeground: Boolean = true,
) = EngineOptions(
    apiKey = "lyr_ing_test",
    storageDirectory = File(dir, "queue"),
    installIdStore = FileInstallIdStore(File(dir, "install_id")),
    resource = resource,
    endpoint = endpoint,
    consent = consent,
    flushIntervalMillis = flushIntervalMillis,
    maxBatchEvents = maxBatchEvents,
    maxBatchBytes = maxBatchBytes,
    intakeCapacity = intakeCapacity,
    sessionTimeoutMillis = sessionTimeoutMillis,
    redactedKeys = redactedKeys,
    startInForeground = startInForeground,
)

val testResource = Resource(
    appId = "com.example.tilefall", appVersion = "1.2.0", appBuild = "120", osName = "android",
    osVersion = "15", deviceModel = "Google Pixel 9", locale = "es_ES", sdk = "lyrelog-android/1.1.0",
    environment = "development", platform = "android",
)

/** Waits up to [timeoutMillis] for [condition]. */
fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        Thread.sleep(10)
    }
    assertTrue(condition(), "condition not met in $timeoutMillis ms")
}

/**
 * The server's checks for one line (lyrelog internal/events/events.go), so every test that
 * sends a line also proves the server would take its shape.
 */
object ServerRules {
    private val top = setOf("event_id", "ts", "level", "message", "attrs", "payload", "resource", "category", "user_id", "install_id", "session_id")
    private val resource = setOf("app_id", "app_version", "app_build", "os_name", "os_version", "device_model", "locale", "sdk", "environment", "platform")
    private val levels = setOf("trace", "debug", "info", "notice", "warning", "error", "critical")
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val ts = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    private val name = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    fun check(line: String) {
        assertTrue(Shaping.utf8Length(line) <= Shaping.MAX_LINE_BYTES, "line too long")
        val event = Json.parse(line) as? JsonValue.Obj ?: fail("not an object: $line")
        val unknown = event.fields.keys - top
        assertTrue(unknown.isEmpty(), "unknown fields $unknown")
        assertTrue(uuid.matches(event["event_id"].string ?: ""), "event_id")
        assertTrue(ts.matches(event["ts"].string ?: ""), "ts ${event["ts"]}")
        assertTrue(event["level"].string in levels, "level")
        val message = event["message"].string ?: fail("message")
        assertTrue(message.isNotEmpty() && Shaping.utf8Length(message) <= 2048, "message")
        event["category"]?.let { assertTrue(Shaping.utf8Length(it.string!!) <= 64, "category") }
        for (id in listOf("user_id", "install_id", "session_id")) event[id]?.let { assertTrue(Shaping.utf8Length(it.string!!) <= 128, id) }
        (event["resource"] as? JsonValue.Obj)?.let { res ->
            assertTrue((res.fields.keys - resource).isEmpty(), "resource fields ${res.fields.keys}")
            for ((k, v) in res.fields) {
                assertTrue(Shaping.utf8Length(v.string!!) <= 64, "resource.$k")
                if (k == "environment" || k == "platform") assertTrue(name.matches(v.string!!), "resource.$k")
            }
        }
        (event["attrs"] as? JsonValue.Obj)?.let { assertTrue(attrs(it, 1) <= 200, "too many attrs") }
        event["payload"]?.let { assertTrue(Shaping.utf8Length(it.encode()) <= 64 * 1024, "payload") }
    }

    private fun attrs(obj: JsonValue.Obj, depth: Int): Int {
        assertTrue(depth <= 4, "attrs too deep")
        var count = 0
        for ((k, v) in obj.fields) {
            assertTrue(k.isNotEmpty() && Shaping.utf8Length(k) <= 128 && !k.startsWith("_") && !k.contains('.'), "bad name $k")
            when (v) {
                is JsonValue.Obj -> count += attrs(v, depth + 1)
                is JsonValue.Arr -> {
                    assertTrue(v.items.size <= 64, "array $k")
                    v.items.forEach { assertTrue(it is JsonValue.Str && Shaping.utf8Length(it.value) <= 1024, "array item $k") }
                    count++
                }
                is JsonValue.Str -> {
                    assertTrue(Shaping.utf8Length(v.value) <= 1024, "string $k")
                    count++
                }
                else -> count++
            }
        }
        return count
    }
}
