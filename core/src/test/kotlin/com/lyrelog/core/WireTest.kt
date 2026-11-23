package com.lyrelog.core

import com.lyrelog.Level
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WireTest {
    @Test
    fun timestampsMatchAReferenceFormatter() {
        val reference = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (millis in listOf(0L, 951_782_400_000L, 1_763_546_400_123L, 4_102_444_799_999L, 1_709_164_800_001L)) {
            assertEquals(reference.format(java.util.Date(millis)), Timestamps.format(millis))
        }
        val random = java.util.Random(7)
        repeat(2000) {
            val millis = (random.nextDouble() * 5_000_000_000_000L).toLong()
            assertEquals(reference.format(java.util.Date(millis)), Timestamps.format(millis))
        }
    }

    @Test
    fun aLineCarriesExactlyTheWireFields() {
        val id = UUID.randomUUID()
        val event = PendingEvent(id, 1_763_546_400_123, Level.WARNING, "checkout", "checkout_done",
            mapOf("items" to JsonValue.Integer(3)), JsonValue.from(mapOf("r" to "x")), "user-1", "sess")
        val parsed = Json.parse(WireEvent.encode(event, "inst", testResource, Redaction.DEFAULT))!!
        assertEquals(id.toString(), parsed["event_id"].string)
        assertEquals("2025-11-19T10:00:00.123Z", parsed["ts"].string)
        assertEquals("warning", parsed["level"].string)
        assertEquals("checkout", parsed["category"].string)
        assertEquals("user-1", parsed["user_id"].string)
        assertEquals("inst", parsed["install_id"].string)
        assertEquals("sess", parsed["session_id"].string)
        assertEquals(JsonValue.Integer(3), parsed["attrs"]["items"])
        assertEquals("android", parsed["resource"]["platform"].string)
        assertEquals("lyrelog-android/1.0.0", parsed["resource"]["sdk"].string)
        val bare = Json.parse(WireEvent.encode(PendingEvent(id, 0, Level.INFO, null, "m", emptyMap(), null, null, null), null, Resource(), Redaction.DEFAULT))!!
        assertNull(bare["attrs"])
        assertNull(bare["category"])
        assertEquals("production", bare["resource"]["environment"].string)
    }

    @Test
    fun environmentAndPlatformNames() {
        assertEquals("staging-eu", Names.clean("Staging EU"))
        assertEquals("internal", Names.clean("internal"))
        assertNull(Names.clean("---"))
        assertEquals(32, Names.clean("x".repeat(50))!!.length)
    }

    @Test
    fun jsonEscapesAndParses() {
        val text = JsonValue.from(mapOf("s" to "a\"b\\c\n\u0001 é😀")).encode()
        assertEquals("{\"s\":\"a\\\"b\\\\c\\n\\u0001\\u2028é😀\"}", text)
        assertEquals("a\"b\\c\n\u0001 é😀", Json.parse(text)["s"].string)
        assertNull(Json.parse("{\"a\":1} x"))
        assertNull(Json.parse("{\"a\":}"))
        assertEquals(JsonValue.Integer(5), Json.parse("""{"accepted":5}""")["accepted"])
    }
}
