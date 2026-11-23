package com.lyrelog.core

import com.lyrelog.Level
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapingTest {
    private fun attrs(vararg pairs: Pair<String, Any?>) =
        Shaping.attributes((JsonValue.from(mapOf(*pairs)) as JsonValue.Obj).fields)

    @Test
    fun messageIsCutOnACharacterBoundary() {
        val text = "é".repeat(2000) // 4,000 bytes
        val cut = Shaping.message(text)
        assertEquals(1024, cut.length)
        assertEquals(2048, Shaping.utf8Length(cut))
        // An emoji (a surrogate pair, 4 bytes) is never split.
        val emoji = "a" + "😀".repeat(600)
        val cutEmoji = Shaping.message(emoji)
        assertTrue(Shaping.utf8Length(cutEmoji) <= 2048)
        assertTrue(!Character.isHighSurrogate(cutEmoji.last()))
        assertEquals("(empty)", Shaping.message(""))
    }

    @Test
    fun idsAndCategoryAreBounded() {
        assertEquals(128, Shaping.identifier("x".repeat(300))!!.length)
        assertNull(Shaping.identifier(""))
        assertEquals(64, Shaping.category("c".repeat(100))!!.length)
        assertNull(Shaping.category(""))
    }

    @Test
    fun attributeStringsAndArraysAreBounded() {
        val out = attrs("long" to "x".repeat(5000), "tags" to (1..100).map { "t$it" }, "mixed" to listOf(1, true, null))
        assertEquals(1024, (out["long"] as JsonValue.Str).value.length)
        assertEquals(64, (out["tags"] as JsonValue.Arr).items.size)
        assertEquals(listOf("1", "true", "null"), (out["mixed"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value })
    }

    @Test
    fun namesWithDotsOrLeadingUnderscoresAreRewrittenAndCollisionsCounted() {
        val out = attrs("a.b" to 1, "a_b" to 2, "_x" to 3, "" to 4)
        assertEquals(JsonValue.Integer(1), out["a_b"]) // "a.b" sorts first and wins
        assertEquals(JsonValue.Integer(3), out["x"])
        assertEquals(JsonValue.Integer(4), out["key"])
        assertEquals(JsonValue.Integer(1), out[Shaping.DROPPED_MARKER])
    }

    @Test
    fun atMost200LeavesWithTheMarker() {
        val many = (1..300).associate { "k%03d".format(it) to it }
        val out = Shaping.attributes((JsonValue.from(many) as JsonValue.Obj).fields)
        assertEquals(200, out.size)
        assertEquals(JsonValue.Integer(101), out[Shaping.DROPPED_MARKER])
    }

    @Test
    fun objectsDeeperThanFourLevelsBecomeText() {
        val deep = mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1)))))
        val out = Shaping.attributes((JsonValue.from(deep) as JsonValue.Obj).fields)
        val d = out["a"]["b"]["c"]["d"]
        assertEquals(JsonValue.Str("""{"e":1}"""), d)
        assertEquals(emptyMap(), attrs("empty" to emptyMap<String, Any>()))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        val out = attrs("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "f" to 0.1f)
        assertEquals(JsonValue.Null, out["nan"])
        assertEquals(JsonValue.Null, out["inf"])
        assertEquals(JsonValue.Decimal(0.1), out["f"])
    }

    @Test
    fun bigPayloadIsSummarised() {
        val big = mapOf("data" to "y".repeat(70_000))
        val out = Shaping.payload(JsonValue.from(big)) as JsonValue.Obj
        assertEquals(JsonValue.Bool(true), out.fields["lyrelog_truncated"])
        assertTrue((out.fields["bytes"] as JsonValue.Integer).value > 65_536)
        assertEquals(1024, Shaping.utf8Length(out.fields["preview"].string!!))
        val small = JsonValue.from(mapOf("ok" to true))
        assertEquals(small, Shaping.payload(small))
    }

    @Test
    fun cyclicAndOddValuesNeverThrow() {
        val cyclic = HashMap<String, Any?>()
        cyclic["self"] = cyclic
        val bad = object {
            override fun toString(): String = throw IllegalStateException("no")
        }
        val value = JsonValue.from(mapOf("c" to cyclic, "bad" to bad, "chars" to charArrayOf('h', 'i'), "level" to Level.INFO))
        val text = value.encode()
        assertTrue(text.contains("\"chars\":\"hi\""))
        assertTrue(text.contains("\"level\":\"INFO\""))
    }

    @Test
    fun everyShapedLineMeetsTheServerRules() {
        val event = PendingEvent(
            UUID.randomUUID(), 1_763_546_400_123, Level.INFO, "c".repeat(80), "m".repeat(3000),
            (JsonValue.from((1..400).associate { "k.$it" to "v".repeat(2000) }) as JsonValue.Obj).fields,
            JsonValue.from(mapOf("big" to "z".repeat(100_000))), "u".repeat(200), "s",
        )
        val line = WireEvent.encode(event, "i", testResource.copy(appId = "a".repeat(100), environment = "Staging EU"), Redaction.DEFAULT)
        ServerRules.check(line)
        assertTrue(line.contains("\"environment\":\"staging-eu\""))
    }
}
