package com.lyrelog.core

import com.lyrelog.Level
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Against a running Lyrelog (in the lyrelog repo: `make up`, then an ingest key):
 *
 *     LYRELOG_E2E_URL=http://127.0.0.1:8090 LYRELOG_E2E_KEY=lyr_ing_... ./gradlew :core:test --tests '*EndToEnd*'
 *
 * Skipped when either variable is missing.
 */
class EndToEndTest {
    @Test
    fun sendsToARealServer() {
        val url = System.getenv("LYRELOG_E2E_URL").orEmpty()
        val key = System.getenv("LYRELOG_E2E_KEY").orEmpty()
        assumeTrue(url.isNotEmpty() && key.isNotEmpty(), "set LYRELOG_E2E_URL and LYRELOG_E2E_KEY to run against a Lyrelog server")
        val dir = tempDir()
        val delivered = ArrayList<String>()
        val transport = Transport { target, headers, body ->
            UrlConnectionTransport().post(target, headers, body).also { delivered.add(it.body) }
        }
        val base = options(url, dir)
        val engine = Engine(
            EngineOptions(key, base.storageDirectory, base.installIdStore, testResource.copy(appVersion = "0.9.0-e2e"), endpoint = url, flushIntervalMillis = 3_600_000),
            transport = transport,
        )
        val marker = UUID.randomUUID().toString().take(8)
        engine.setUser("e2e-user")
        engine.log(Level.INFO, "e2e_android_$marker", "test", mapOf("latency_ms" to 412, "gc" to mapOf("ok" to true), "tags" to listOf("a", "b"), "password" to "hunter2"),
            mapOf("note" to "from the android core test", "headers" to mapOf("Authorization" to "Bearer e2e")))
        engine.captureError(IllegalStateException("e2e"), "test")
        engine.screen("E2E")
        assertTrue(engine.flush(15_000))
        engine.close()
        assertTrue(engine.queue.isEmpty(), "everything was delivered")
        val answer = Json.parse(delivered.single())
        assertEquals(JsonValue.Integer(3), answer["accepted"])
        assertEquals(0, (answer["rejected"] as JsonValue.Arr).items.size)
        println("sent e2e_android_$marker")
    }
}
