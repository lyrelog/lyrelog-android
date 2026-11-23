package com.lyrelog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassifierTest {
    private fun classify(status: Int, body: String = "", retryAfter: String? = null) =
        ResponseClassifier.classify(status, body, retryAfter, nowMillis = 1_000_000)

    @Test
    fun twoHundredWithRejectedLinesIsDelivered() {
        assertEquals(Outcome.Delivered(3, 2), classify(200, """{"accepted":3,"rejected":[{"line":1,"error":"x"},{"line":4,"error":"y"}]}"""))
        assertEquals(Outcome.Delivered(5, 0), classify(202, """{"accepted":5,"rejected":[]}"""))
    }

    @Test
    fun aTwoHundredThatCantBeReadIsRetried() {
        assertEquals(Outcome.Retry(null), classify(200, "<html>"))
        assertEquals(Outcome.Retry(null), classify(200, "{}"))
    }

    @Test
    fun refusedBatchesAreDropped() {
        for (status in listOf(400, 404, 413, 415, 422, 301, 302, 307)) assertEquals(Outcome.Drop(status), classify(status))
    }

    @Test
    fun revokedKeysStopSending() {
        assertEquals(Outcome.Unauthorized, classify(401))
        assertEquals(Outcome.Unauthorized, classify(403))
    }

    @Test
    fun temporaryProblemsAreRetriedWithRetryAfter() {
        assertEquals(Outcome.Retry(30_000), classify(425, """{"error":"x","verification_required":"android"}""", "30"))
        assertEquals(Outcome.Retry(5_000), classify(503, "", "5"))
        assertEquals(Outcome.Retry(null), classify(500))
        assertEquals(Outcome.Retry(null), classify(408))
        assertEquals(Outcome.Retry(1_500), classify(429, """{"error":"rate limit for this project"}""", "1.5"))
        assertEquals(Outcome.Retry(null), classify(599))
    }

    @Test
    fun retryAfterAsAnHttpDate() {
        // 1,000,000 ms after the epoch is 00:16:40 GMT on 1 Jan 1970.
        assertEquals(20_000L, ResponseClassifier.parseRetryAfter("Thu, 01 Jan 1970 00:17:00 GMT", 1_000_000))
        assertEquals(0L, ResponseClassifier.parseRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT", 1_000_000))
        assertNull(ResponseClassifier.parseRetryAfter("soon", 0))
        assertNull(ResponseClassifier.parseRetryAfter("-3", 0))
        assertNull(ResponseClassifier.parseRetryAfter(null, 0))
    }

    @Test
    fun theMonthlyQuotaIsToldApartFromTheOther429s() {
        val quota = """{"error":"this organization used its 1000000 events for the month.","plan":"free","limit":1000000,"used":1000412}"""
        assertEquals("this organization used its 1000000 events for the month", ResponseClassifier.quotaMessage(429, quota))
        assertNull(ResponseClassifier.quotaMessage(429, """{"error":"rate limit for this project"}"""))
        assertNull(ResponseClassifier.quotaMessage(429, """{"error":"this address sent 20000 events today","source_limit":20000}"""))
        assertNull(ResponseClassifier.quotaMessage(429, """{"error":"x","plan":null}"""))
        assertNull(ResponseClassifier.quotaMessage(429, "<html>"))
        assertNull(ResponseClassifier.quotaMessage(503, quota))
        assertEquals("this organization's monthly events are used up", ResponseClassifier.quotaMessage(429, """{"plan":"free","limit":1}"""))
        assertEquals("a b", ResponseClassifier.quotaMessage(429, """{"plan":"free","error":"a\nb"}"""))
    }

    @Test
    fun backoffIsFullJitterFromOneSecondToFiveMinutes() {
        val ceilings = (0..12).map { attempt -> var seen = 0L; Backoff.delayMillis(attempt, null) { seen = it; it }; seen }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000, 300_000, 300_000), ceilings)
        assertEquals(0L, Backoff.delayMillis(3, null) { 0 })
        // Never sooner than the server asked.
        assertEquals(60_000L, Backoff.delayMillis(0, 60_000) { it })
        repeat(1000) { assertTrue(Backoff.delayMillis(4, null) in 0..16_000) }
        assertEquals(300_000L, Backoff.delayMillis(200, null) { it })
    }

    @Test
    fun keepingIsDescribed() {
        assertEquals("20 MB and 7 days", Uploader.describeKeeping(DiskQueue.Limits(1, 1, 20L * 1024 * 1024, 7L * 86_400_000)))
        assertEquals("512 KB and 1 hour", Uploader.describeKeeping(DiskQueue.Limits(1, 1, 512L * 1024, 3_600_000)))
    }
}

class EndpointTest {
    @Test
    fun pastedPathsAreStrippedAndOnlyHttpsOrLoopbackHttpIsUsed() {
        assertEquals("https://ingest.lyrelog.com", Endpoint.origin("https://ingest.lyrelog.com/v1/events:batch").toString())
        assertEquals("https://ingest.lyrelog.com", Endpoint.origin("https://ingest.lyrelog.com/v1/").toString())
        assertEquals("https://example.com/lyrelog", Endpoint.origin("https://example.com/lyrelog/v1?x=1#f").toString())
        assertEquals("http://127.0.0.1:8090", Endpoint.origin("http://127.0.0.1:8090").toString())
        assertEquals("http://localhost:8090", Endpoint.origin("http://localhost:8090/v1").toString())
        assertEquals("http://[::1]:8090", Endpoint.origin("http://[::1]:8090").toString())
        assertNull(Endpoint.origin("http://ingest.lyrelog.com"))
        assertNull(Endpoint.origin("http://10.0.2.2:8090"))
        assertEquals("http://10.0.2.2:8090", Endpoint.origin("http://10.0.2.2:8090", setOf("10.0.2.2")).toString())
        assertNull(Endpoint.origin("ftp://ingest.lyrelog.com"))
        assertNull(Endpoint.origin("not a url"))
    }
}

class RetryAfterCapTest {
    private val now = 1_000_000L

    @Test
    fun retryAfterIsCappedAtAnHour() {
        assertEquals(3_600_000L, ResponseClassifier.MAX_RETRY_AFTER_MILLIS)
        assertEquals(3_600_000L, ResponseClassifier.parseRetryAfter("3601", now))
        assertEquals(3_600_000L, ResponseClassifier.parseRetryAfter("99999999999999999999", now))
        assertEquals(3_600_000L, ResponseClassifier.parseRetryAfter("1e300", now))
        assertEquals(3_600_000L, ResponseClassifier.parseRetryAfter("Fri, 31 Dec 9999 23:59:59 GMT", now))
        assertEquals(120_000L, ResponseClassifier.parseRetryAfter("120", now))
        assertEquals(null, ResponseClassifier.parseRetryAfter("Infinity", now))
    }
}
