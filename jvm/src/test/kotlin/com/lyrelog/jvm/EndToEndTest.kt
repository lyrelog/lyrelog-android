package com.lyrelog.jvm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Against a running Lyrelog (in the lyrelog repo: `make up`, then a server key:
 * `docker compose exec -T ingest /lyrelog key create -scope server <project>`):
 *
 *     LYRELOG_E2E_URL=http://127.0.0.1:8090 LYRELOG_E2E_KEY=lyr_srv_... ./gradlew :jvm:test --tests '*EndToEnd*'
 *
 * Skipped when either variable is missing.
 */
class EndToEndTest {
    @Test
    fun sendsToARealServer() {
        val url = System.getenv("LYRELOG_E2E_URL").orEmpty()
        val key = System.getenv("LYRELOG_E2E_KEY").orEmpty()
        assumeTrue(url.isNotEmpty() && key.isNotEmpty(), "set LYRELOG_E2E_URL and LYRELOG_E2E_KEY to run against a Lyrelog server")
        val said = ArrayList<String>()
        assertTrue(Lyrelog.start(Configuration(key) { endpoint = url; platform = "api"; release = "0.9.0-e2e"; shutdownHook = false; diagnostics { said.add(it) } }))
        try {
            val marker = UUID.randomUUID().toString().take(8)
            Lyrelog.userScope("e2e-user").use {
                Lyrelog.info("e2e_jvm_$marker", "test", mapOf("latency_ms" to 412, "tags" to listOf("a", "b"), "password" to "hunter2"),
                    mapOf("note" to "from the jvm test", "headers" to mapOf("Authorization" to "Bearer e2e")))
                Lyrelog.captureError(IllegalStateException("e2e"), "test")
            }
            assertTrue(Lyrelog.flush(15_000))
            assertTrue(said.isEmpty(), "no diagnostics: $said")
            println("sent e2e_jvm_$marker")
        } finally {
            Lyrelog.close()
        }
    }
}
