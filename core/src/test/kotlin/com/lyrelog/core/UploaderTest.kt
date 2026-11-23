package com.lyrelog.core

import com.lyrelog.Consent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** The uploader on its own, with a scripted transport and a hand-driven clock. */
class UploaderTest {
    private val said = CopyOnWriteArrayList<String>()
    private val ceilings = ArrayList<Long>()
    private var clock = 1_000_000L
    private val answers = ArrayDeque<() -> HttpAnswer>()

    private val queue = DiskQueue(tempDir(), DiskQueue.Limits(512 * 1024, 500, 20L * 1024 * 1024, 7L * 86_400_000))
    private val uploader = Uploader(
        java.net.URI("http://127.0.0.1:9").toURL(), "lyr_ing_test",
        Transport { _, _, _ -> answers.removeFirst()() },
        queue, ConsentGate(Consent.GRANTED), SdkLog { _, message -> said.add(message) }, "lyrelog-test",
        clock = { clock },
        jitter = { ceiling -> ceilings.add(ceiling); 0L },
    )

    private fun batch() {
        queue.append("""{"message":"x"}""".toByteArray(), DiskQueue.Area.READY)
        queue.seal()
    }

    private fun answer(status: Int, body: String = "", retryAfter: String? = null) =
        answers.addLast { HttpAnswer(status, if (retryAfter != null) mapOf("retry-after" to retryAfter) else emptyMap(), body) }

    private fun noAnswer() = answers.addLast { throw IOException("unreachable") }

    private val quota = """{"error":"this organization used its 1000000 events for the month.","plan":"free","limit":1000000,"used":1000412}"""
    private val accepted = """{"accepted":1,"rejected":[]}"""

    @Test
    fun theMonthlyQuotaIsNotAFailedAttempt() {
        batch()
        repeat(4) {
            answer(429, quota, retryAfter = "60")
            uploader.round(force = true)
            clock += 61_000 // past its Retry-After
        }
        // Four quota answers drew no backoff: the next failure is the first, ceiling 1 s.
        assertEquals(emptyList(), ceilings)
        answer(503)
        uploader.round(force = true)
        assertEquals(listOf(Backoff.BASE_MILLIS), ceilings)
        // Nor do they count toward "uploads keep failing".
        assertEquals(0, said.count { "in a row" in it })
    }

    @Test
    fun theQuotasRetryAfterIsStillHonoured() {
        batch()
        answer(429, quota, retryAfter = "60")
        uploader.round(force = true)
        clock += 30_000
        assertEquals(false, uploader.willAttempt(force = true))
        clock += 31_000
        assertEquals(true, uploader.willAttempt(force = true))
    }

    @Test
    fun saysOnceWhenUploadsKeepFailing() {
        batch()
        repeat(Uploader.DOWN_AFTER - 1) {
            noAnswer()
            uploader.round(force = true)
        }
        assertEquals(0, said.count { "in a row" in it })
        repeat(5) {
            noAnswer()
            uploader.round(force = true)
        }
        assertEquals(1, said.count { "in a row" in it }, said.toString())

        // An upload that goes through re-arms it.
        answer(200, accepted)
        uploader.round(force = true)
        batch()
        repeat(Uploader.DOWN_AFTER) {
            answer(503)
            uploader.round(force = true)
            clock += Backoff.CAP_MILLIS
        }
        assertEquals(2, said.count { "in a row" in it }, said.toString())
    }

    @Test
    fun aHugeRetryAfterWaitsAnHourAtMost() {
        batch()
        answer(503, retryAfter = "99999999999999999999")
        uploader.round(force = true)
        clock += ResponseClassifier.MAX_RETRY_AFTER_MILLIS - 1
        assertEquals(false, uploader.willAttempt(force = true))
        clock += 1
        assertEquals(true, uploader.willAttempt(force = true))
    }
}
