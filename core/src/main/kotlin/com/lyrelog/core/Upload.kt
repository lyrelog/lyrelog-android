package com.lyrelog.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** What to do with a batch after a request. Decided by a pure function so it can be tested. */
internal sealed class Outcome {
    /** Stored. `rejected` lines were invalid and are gone for good (never resent). */
    data class Delivered(val accepted: Int, val rejected: Int) : Outcome()
    /** Temporary (408, 425, 429, 5xx, network): keep the same bytes, try again later. */
    data class Retry(val afterMillis: Long?) : Outcome()
    /** The batch itself is wrong (400, 413, 415, any other 4xx, a 3xx): resending would fail. */
    data class Drop(val status: Int) : Outcome()
    /** 401/403: the key is unknown or revoked. Stop sending until restart, keep the data. */
    object Unauthorized : Outcome() {
        override fun toString() = "Unauthorized"
    }
}

internal object ResponseClassifier {
    fun classify(status: Int, body: String, retryAfter: String?, nowMillis: Long = System.currentTimeMillis()): Outcome =
        when {
            status in 200..299 -> {
                // A 2xx we can't read may not mean "stored": retry; the server drops duplicates.
                val answer = Json.parse(body)
                val accepted = answer["accepted"] as? JsonValue.Integer
                if (accepted == null) {
                    Outcome.Retry(null)
                } else {
                    Outcome.Delivered(accepted.value.toInt(), (answer["rejected"] as? JsonValue.Arr)?.items?.size ?: 0)
                }
            }
            status == 401 || status == 403 -> Outcome.Unauthorized
            status == 408 || status == 425 || status == 429 || status >= 500 ->
                Outcome.Retry(parseRetryAfter(retryAfter, nowMillis))
            else -> Outcome.Drop(status)
        }

    /**
     * The server's words when a 429 is the organization's monthly quota, not the rate limit:
     * the quota answer is a JSON object with a non-null `plan` (and `limit`, `used`, the `error`
     * text); the rate limit's has only `error`, the daily share's `source_limit`. Anything else
     * is a plain 429 (null). Kept to one printable line of at most 300 characters.
     */
    fun quotaMessage(status: Int, body: String): String? {
        if (status != 429) return null
        val answer = Json.parse(body) as? JsonValue.Obj ?: return null
        val plan = answer.fields["plan"]
        if (plan == null || plan == JsonValue.Null) return null
        val text = (answer.fields["error"].string ?: "")
            .map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")
            .trim().trimEnd('.').take(300)
        return text.ifEmpty { "this organization's monthly events are used up" }
    }

    /** The longest wait a `Retry-After` can ask for: an hour, the most Lyrelog's server sends. */
    const val MAX_RETRY_AFTER_MILLIS = 3_600_000L

    /**
     * `Retry-After` is either seconds or an HTTP date; null when absent or unreadable. Capped at
     * [MAX_RETRY_AFTER_MILLIS]: a proxy or captive portal answering a year must not stop sending
     * until the app restarts (nor overflow the clock arithmetic).
     */
    fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { seconds ->
            if (!seconds.isFinite() || seconds < 0) return null
            return if (seconds >= MAX_RETRY_AFTER_MILLIS / 1000.0) MAX_RETRY_AFTER_MILLIS else (seconds * 1000).toLong()
        }
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val date = try {
            format.parse(text)
        } catch (e: java.text.ParseException) {
            null
        } ?: return null
        return (date.time - nowMillis).coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }
}

internal object Backoff {
    const val BASE_MILLIS = 1_000L
    const val CAP_MILLIS = 300_000L

    /** Full jitter: uniform(0, min(cap, base * 2^attempt)), never sooner than the server asked. */
    fun delayMillis(attempt: Int, retryAfterMillis: Long?, random: (Long) -> Long = { Random.nextLong(0, it + 1) }): Long {
        val ceiling = minOf(CAP_MILLIS, BASE_MILLIS shl minOf(attempt, 16))
        return maxOf(random(ceiling), retryAfterMillis ?: 0L)
    }
}

/** One HTTP answer, headers by lowercase name. */
@InternalLyrelogApi
class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/** How a batch reaches the server. A test passes its own; the SDK uses [UrlConnectionTransport]. */
@InternalLyrelogApi
fun interface Transport {
    /** Throws IOException for a network failure (no answer). */
    @Throws(IOException::class)
    fun post(url: URL, headers: Map<String, String>, body: ByteArray): HttpAnswer
}

/**
 * `java.net.HttpURLConnection`, nothing else: no redirects followed (a 301/302 would turn the
 * POST into a GET and send the key elsewhere), no caches, no cookies, bounded times and body.
 */
internal class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : Transport {
    override fun post(url: URL, headers: Map<String, String>, body: ByteArray): HttpAnswer {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setFixedLengthStreamingMode(body.size)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { readCapped(it, 1 shl 20) } ?: ""
            val answerHeaders = HashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values.isNotEmpty()) answerHeaders[name.lowercase(Locale.ROOT)] = values.last()
            }
            return HttpAnswer(status, answerHeaders, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val n = stream.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

internal object Gzip {
    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}

/** Whether the person agreed to send, read by the writer and the uploader at the moment they act. */
internal class ConsentGate(initial: com.lyrelog.Consent) {
    @Volatile
    var value: com.lyrelog.Consent = initial
}

/**
 * Sends sealed batches one at a time, oldest first. Only ever called from the engine's single
 * upload thread, so one round runs at a time.
 *
 * A retryable failure stops the round and sets a backoff (1 s doubling to 5 min, full jitter).
 * A forced round (flush, going to the background) ignores that backoff, never the server's
 * `Retry-After`. A batch that keeps failing goes to the back after [MOVE_BACK_AFTER] tries, so it
 * can't hold the others, and is dropped (counted) after [DROP_AFTER] failures in rounds where
 * other batches went through. A 429 never counts against the batch (it's not the batch's fault).
 * The monthly quota's 429 isn't a failed attempt at all (docs/sdk-spec.md, rule 4): it waits for
 * its `Retry-After` but leaves the backoff as it was. After [DOWN_AFTER] failed attempts in a row
 * the developer is told once, and again only after an upload went through.
 */
internal class Uploader(
    origin: URL?,
    private val apiKey: String,
    private val transport: Transport,
    private val queue: DiskQueue,
    private val consent: ConsentGate,
    private val log: SdkLog,
    private val userAgent: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitter: (Long) -> Long = { Random.nextLong(0, it + 1) },
    /** Key Attestation (null: off): its token goes on each batch, a `renew` answer asks for another. */
    private val attestor: Attestor? = null,
) {
    data class Report(var delivered: Int = 0, var dropped: Int = 0, var stoppedForRetry: Boolean = false)

    private val endpoint: URL? = origin?.let { URL(it.toString().trimEnd('/') + "/v1/events:batch") }
    private var attempt = 0
    private var backoffUntil: Long? = null
    private var serverWaitUntil: Long? = null
    private val failures = HashMap<String, Int>()
    private val strikes = HashMap<String, Int>()

    @Volatile
    var disabled = endpoint == null
        private set

    /** Whether the developer was told the monthly quota is used up; a 2xx clears it. */
    private var quotaWarned = false

    /** Failed attempts in a row (no answer, 408, 425, a rate-limit 429, 5xx), reset by a 2xx. */
    private var consecutiveFailures = 0
    private var downWarned = false

    /** How many quota warnings were logged (tests). */
    @Volatile
    var quotaWarnings = 0
        private set

    /** Whether a round started now would send anything (so the open file is worth sealing). */
    fun willAttempt(force: Boolean): Boolean {
        if (disabled || consent.value != com.lyrelog.Consent.GRANTED) return false
        val now = clock()
        serverWaitUntil?.let { if (now < it) return false }
        if (!force) backoffUntil?.let { if (now < it) return false }
        return true
    }

    fun round(force: Boolean): Report {
        val report = Report()
        if (!willAttempt(force)) return report
        val batches = queue.readyBatches().withIndex().sortedWith(
            compareBy({ (failures[it.value.name] ?: 0) >= MOVE_BACK_AFTER }, { it.index }),
        ).map { it.value }
        for (batch in batches) {
            if (disabled || consent.value != com.lyrelog.Consent.GRANTED) break
            val name = batch.name
            val body = queue.contents(batch)
            if (body == null || body.isEmpty()) {
                queue.remove(batch)
                continue
            }
            val (outcome, status, quota) = send(body)
            when (outcome) {
                is Outcome.Delivered -> {
                    queue.remove(batch)
                    forget(name)
                    report.delivered += 1
                    attempt = 0
                    backoffUntil = null
                    quotaWarned = false
                    consecutiveFailures = 0
                    downWarned = false
                    if (outcome.rejected > 0) {
                        log.once("rejected", "lyrelog: the server rejected ${outcome.rejected} line(s) of a batch; they are not resent (first time this launch)")
                    }
                }
                is Outcome.Drop -> {
                    queue.drop(batch)
                    forget(name)
                    report.dropped += 1
                    warnAboutDrop(outcome.status)
                }
                Outcome.Unauthorized -> {
                    disabled = true
                    log.once("unauthorized", "lyrelog: the ingest key was refused (${status ?: 0}); nothing more is sent until the app restarts, the events stay on the device")
                    return report
                }
                is Outcome.Retry -> {
                    if (quota != null) {
                        // The month is used up: nothing is wrong with the network or the batch.
                        warnAboutQuota(quota)
                        outcome.afterMillis?.let { serverWaitUntil = clock() + it }
                        report.stoppedForRetry = true
                        return report
                    }
                    // A failure the server answered, other than rate limiting, may be this batch's fault.
                    if (status != null && status != 429) {
                        failures[name] = (failures[name] ?: 0) + 1
                        if (report.delivered > 0) {
                            val count = (strikes[name] ?: 0) + 1
                            strikes[name] = count
                            if (count >= DROP_AFTER) {
                                queue.drop(batch)
                                forget(name)
                                report.dropped += 1
                                log.once("poison", "lyrelog: dropped a batch the server kept failing ($status) while others went through")
                            }
                        }
                    }
                    val now = clock()
                    outcome.afterMillis?.let { serverWaitUntil = now + it }
                    backoffUntil = now + Backoff.delayMillis(attempt, outcome.afterMillis, jitter)
                    attempt += 1
                    consecutiveFailures += 1
                    if (consecutiveFailures >= DOWN_AFTER && !downWarned) {
                        downWarned = true
                        log.warn("lyrelog: $consecutiveFailures uploads in a row failed (${status?.let { "status $it" } ?: "no answer"}); events are kept${keptWhere()} and retried with backoff. Said again only after an upload goes through.")
                    }
                    report.stoppedForRetry = true
                    return report
                }
            }
        }
        return report
    }

    private fun keptWhere() = " on disk (up to ${describeKeeping(queue.limits)})"

    private fun forget(name: String) {
        failures.remove(name)
        strikes.remove(name)
    }

    /** Once per quota stop (until a batch is accepted again). The retry itself is unchanged. */
    private fun warnAboutQuota(error: String) {
        if (quotaWarned) return
        quotaWarned = true
        quotaWarnings += 1
        log.warn("Lyrelog: $error. Events are kept and sent when sending resumes; how many wait depends on the platform (here: on disk, up to ${describeKeeping(queue.limits)}).")
    }

    private fun warnAboutDrop(status: Int) {
        when (status) {
            in 300..399 -> log.once("redirect", "lyrelog: the endpoint answered $status (a redirect, not followed); the batch was dropped. Check Configuration.endpoint")
            404 -> log.once("404", "lyrelog: the endpoint answered 404; the batch was dropped. Configuration.endpoint should be the ingest origin, e.g. https://ingest.lyrelog.com")
            else -> log.once("drop-$status", "lyrelog: the server refused a batch ($status); it was dropped")
        }
    }

    /** The outcome, the HTTP status when the server answered, and the quota text when it was the quota. */
    private fun send(body: ByteArray): Triple<Outcome, Int?, String?> {
        val url = endpoint ?: return Triple(Outcome.Unauthorized, null, null)
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer $apiKey"
        headers["Content-Type"] = "application/x-ndjson"
        headers["User-Agent"] = userAgent
        attestor?.header()?.let { headers[Attestor.HEADER] = it }
        var payload = body
        try {
            payload = Gzip.compress(body)
            headers["Content-Encoding"] = "gzip"
        } catch (e: IOException) {
            // send it plain
        }
        return try {
            val answer = transport.post(url, headers, payload)
            if (answer.header(Attestor.HEADER) == "renew") attestor?.renew()
            val outcome = ResponseClassifier.classify(answer.status, answer.body, answer.header("Retry-After"))
            Triple(outcome, answer.status, ResponseClassifier.quotaMessage(answer.status, answer.body))
        } catch (e: IOException) {
            // No answer: retried quietly, but said once per launch, so a blocked network
            // (Android's cleartext policy, a wrong host) shows in the log.
            log.once("network", "lyrelog: no answer from the endpoint (${e.javaClass.name}: ${e.message?.take(200)}); retrying with backoff")
            Triple(Outcome.Retry(null), null, null)
        } catch (e: RuntimeException) {
            // A misbehaving stack (SecurityException without the INTERNET permission, say).
            log.once("transport", "lyrelog: sending failed (${e.javaClass.name}); retrying later")
            Triple(Outcome.Retry(null), null, null)
        }
    }

    companion object {
        const val MOVE_BACK_AFTER = 3
        const val DROP_AFTER = 5

        /** Failed attempts in a row before the one "uploads keep failing" warning. */
        const val DOWN_AFTER = 3

        /** "20 MB and 7 days" for the queue's caps. */
        fun describeKeeping(limits: DiskQueue.Limits): String {
            val megabytes = limits.maxTotalBytes / 1_048_576.0
            val size = if (megabytes >= 1) "${Math.round(megabytes)} MB" else "${maxOf(1, limits.maxTotalBytes / 1024)} KB"
            val days = limits.maxAgeMillis / 86_400_000.0
            val age = if (days >= 1) {
                val whole = Math.round(days)
                if (whole == 1L) "1 day" else "$whole days"
            } else {
                val hours = maxOf(1L, Math.round(limits.maxAgeMillis / 3_600_000.0))
                if (hours == 1L) "1 hour" else "$hours hours"
            }
            return "$size and $age"
        }
    }
}
