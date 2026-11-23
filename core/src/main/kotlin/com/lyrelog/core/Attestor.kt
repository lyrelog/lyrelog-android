package com.lyrelog.core

import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What can go wrong with the device's attestation key, as far as the logic cares. */
@InternalLyrelogApi
class AttestationUnsupported(message: String) : Exception(message)

/** The key is gone or unusable (deleted, a Keystore that lost it): make another. */
@InternalLyrelogApi
class AttestationKeyInvalid(message: String) : Exception(message)

/**
 * The Android Keystore, behind an interface so the logic is tested on the JVM. One key per
 * install, under a fixed alias. Implementations throw [AttestationUnsupported] when the device
 * can't attest at all, [AttestationKeyInvalid] when the key is unusable, anything else for a
 * passing failure.
 */
@InternalLyrelogApi
interface AttestationProvider {
    /** Makes a new P-256 signing key whose attestation carries [challenge]; its chain, leaf first (DER). */
    fun createKey(challenge: ByteArray): List<ByteArray>

    /** Whether the key still exists. */
    fun hasKey(): Boolean

    /** SHA256withECDSA (DER) over [data] with the key. */
    fun sign(data: ByteArray): ByteArray

    fun deleteKey()
}

/**
 * What survives launches: the key id the server gave (the key itself stays in the Keystore),
 * and when the server last refused an attestation. A small file in the SDK's no-backup folder.
 */
internal class AttestRecordFile(private val file: File) {
    data class Record(val keyId: String?, val refusedAtMillis: Long?)

    fun load(): Record = try {
        val fields = Json.parse(file.readText())
        Record(fields["key_id"].string, (fields["refused_at"] as? JsonValue.Integer)?.value)
    } catch (e: IOException) {
        Record(null, null)
    } catch (e: RuntimeException) {
        Record(null, null)
    }

    fun save(record: Record) {
        try {
            file.parentFile?.mkdirs()
            val fields = LinkedHashMap<String, JsonValue>()
            record.keyId?.let { fields["key_id"] = JsonValue.Str(it) }
            record.refusedAtMillis?.let { fields["refused_at"] = JsonValue.Integer(it) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonValue.Obj(fields).encode())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: IOException) {
            // next launch attests again: nothing lost but a request
        }
    }

    fun delete() {
        file.delete()
    }
}

/**
 * Gets and keeps the Key Attestation token that batches carry in `X-Lyrelog-Attestation`, so the
 * server stores their events as verified (`verified_by = key_attestation`). The Swift SDK's
 * App Attest client, for Android (docs/ingest-api.md):
 *
 * - once per install: a challenge, a new Keystore key attested over it, its chain to
 *   `/v1/attest/android/key`, which answers a token and the key id;
 * - then, to renew: a fresh challenge signed with the key to `/v1/attest/android/token`.
 *
 * It never holds a batch: [header] answers at once with the token it has (or null) and, when one
 * is missing, ending or refused, starts one background refresh. One refresh at a time; failures
 * wait (30 s doubling to an hour, or the server's `Retry-After`). A 404 (Key Attestation isn't
 * set up for the project), a refused ingest key or a device that can't attest turn it off until
 * the next launch. After the server refuses an attestation (an unlocked bootloader, an app not
 * signed with the project's certificate), no new key for a day. Never logs the token, the key id
 * or the chain.
 */
internal class Attestor(
    origin: URL,
    private val apiKey: String,
    private val userAgent: String,
    private val transport: Transport,
    private val provider: AttestationProvider,
    private val store: AttestRecordFile,
    private val log: SdkLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lyrelog-attest").apply { isDaemon = true }
    },
) {
    private class Token(val value: String, val expiresAtMillis: Long, val issuedAtMillis: Long)

    private val base = origin.toString().trimEnd('/')
    private val challengeURL = java.net.URI.create("$base/v1/attest/challenge").toURL()
    private val keyURL = java.net.URI.create("$base/v1/attest/android/key").toURL()
    private val tokenURL = java.net.URI.create("$base/v1/attest/android/token").toURL()

    private val lock = Any()
    private var token: Token? = null
    private var running = false
    private var renewRequested = false
    private var disabled = false
    private var failures = 0
    private var waitUntil: Long? = null
    private var forgetWhenDone = false

    /** Refreshes started (tests). */
    @Volatile
    var refreshes = 0
        private set

    /** The token for the next batch, or null (the batch then goes without it). Never waits. */
    fun header(): String? {
        startIfNeeded()
        synchronized(lock) {
            val current = token ?: return null
            return if (current.expiresAtMillis > now()) current.value else null
        }
    }

    /** Starts a refresh if one is needed, without waiting (at launch). */
    fun prepare() = startIfNeeded()

    /** A batch answer said `X-Lyrelog-Attestation: renew`: the token wasn't accepted. */
    fun renew() {
        synchronized(lock) {
            if (token == null && !renewRequested) return
            if (renewRequested) {
                noteFailure(null)
                return
            }
            token = null
            renewRequested = true
        }
        startIfNeeded()
    }

    /**
     * The engine stopped: the refresh thread ends (after a refresh under way, bounded by the
     * transport's timeouts), so a stop and a new start leave no thread behind.
     */
    fun close() {
        (executor as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    /** Consent was denied: forget the key and the token (a new key is made after a grant). */
    fun forget() {
        synchronized(lock) {
            token = null
            renewRequested = false
            if (running) {
                forgetWhenDone = true
                return
            }
        }
        dropKey()
    }

    private val needsRefresh: Boolean
        get() {
            if (disabled || running) return false
            waitUntil?.let { if (now() < it) return false }
            if (renewRequested) return true
            val current = token ?: return true
            val left = current.expiresAtMillis - now()
            val life = current.expiresAtMillis - current.issuedAtMillis
            return left < minOf(RENEW_BEFORE_MILLIS, life / 2)
        }

    private fun startIfNeeded() {
        synchronized(lock) {
            if (!needsRefresh) return
            running = true
            refreshes += 1
        }
        try {
            executor.execute {
                val result = try {
                    obtain()
                } catch (e: Throwable) {
                    // An Error too (a Keystore class missing on an old Android): leaving this
                    // thread, it would end the app.
                    Result.Retry(null)
                }
                finish(result)
            }
        } catch (e: RuntimeException) {
            synchronized(lock) { running = false }
        }
    }

    private sealed class Result {
        class Got(val value: String, val expiresAtMillis: Long) : Result()
        class Disable(val reason: String) : Result()
        class Retry(val afterMillis: Long?) : Result()
    }

    private fun finish(result: Result) {
        val forgetNow: Boolean
        synchronized(lock) {
            running = false
            forgetNow = forgetWhenDone
            forgetWhenDone = false
            if (!forgetNow) {
                when (result) {
                    is Result.Got -> {
                        token = Token(result.value, result.expiresAtMillis, now())
                        renewRequested = false
                        failures = 0
                        waitUntil = null
                    }
                    is Result.Disable -> {
                        disabled = true
                        token = null
                    }
                    is Result.Retry -> noteFailure(result.afterMillis)
                }
            }
        }
        if (forgetNow) {
            dropKey()
            return
        }
        if (result is Result.Disable) {
            log.once("key-attestation-off", "lyrelog: Key Attestation is off for this launch (${result.reason}); events are stored unverified")
        }
    }

    /** The caller holds [lock]. */
    private fun noteFailure(retryAfterMillis: Long?) {
        val doubling = minOf(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS shl minOf(failures, 16))
        failures += 1
        waitUntil = now() + maxOf(doubling, retryAfterMillis ?: 0L)
    }

    private fun dropKey() {
        try {
            provider.deleteKey()
        } catch (e: Exception) {
            // a key nobody can use is harmless
        }
        store.delete()
    }

    // The exchange

    private fun obtain(): Result {
        var record = store.load()
        // A key the server doesn't know is attested again, at most once per refresh.
        repeat(2) {
            if (record.keyId == null) {
                record.refusedAtMillis?.let { refusedAt ->
                    val since = now() - refusedAt
                    if (since in 0 until REFUSAL_PAUSE_MILLIS) return Result.Retry(REFUSAL_PAUSE_MILLIS - since)
                }
            }
            val challenge = when (val answer = post(challengeURL, emptyMap())) {
                is Answer.Ok -> answer.body["challenge"].string?.takeIf { it.isNotEmpty() } ?: return Result.Retry(null)
                is Answer.Unknown -> return Result.Disable("not set up for this project")
                is Answer.Unauthorized -> return Result.Disable("the server answered ${answer.status}")
                is Answer.Refused -> return Result.Disable("the server answered 400")
                is Answer.Again -> return Result.Retry(answer.afterMillis)
            }
            val challengeBytes = challenge.toByteArray(Charsets.UTF_8)
            val keyId = record.keyId
            if (keyId != null && provider.hasKey()) {
                val signature = try {
                    provider.sign(challengeBytes)
                } catch (e: AttestationKeyInvalid) {
                    record = forgetKey(record, refused = false)
                    return@repeat
                } catch (e: AttestationUnsupported) {
                    return Result.Disable("not supported on this device")
                }
                when (val answer = post(tokenURL, mapOf("key_id" to keyId, "challenge" to challenge, "signature" to b64(signature)))) {
                    is Answer.Ok -> return tokenFrom(answer.body) ?: Result.Retry(null)
                    is Answer.Unknown -> {
                        record = forgetKey(record, refused = false) // attest a new key
                        return@repeat
                    }
                    is Answer.Refused -> {
                        record = forgetKey(record, refused = false)
                        return@repeat
                    }
                    is Answer.Unauthorized -> return Result.Disable("the server answered ${answer.status}")
                    is Answer.Again -> return Result.Retry(answer.afterMillis)
                }
            }
            // A new key, attested over this challenge.
            try {
                provider.deleteKey()
            } catch (e: Exception) {
                // there was none
            }
            val chain = try {
                provider.createKey(challengeBytes)
            } catch (e: AttestationUnsupported) {
                return Result.Disable("not supported on this device")
            }
            when (val answer = post(keyURL, mapOf("challenge" to challenge, "chain" to chain.map(::b64)))) {
                is Answer.Ok -> {
                    val got = tokenFrom(answer.body)
                    val newKeyId = answer.body["key_id"].string
                    if (got == null || newKeyId.isNullOrEmpty()) {
                        forgetKey(record, refused = false)
                        return Result.Retry(null)
                    }
                    store.save(AttestRecordFile.Record(newKeyId, null))
                    return got
                }
                is Answer.Refused -> {
                    forgetKey(record, refused = true)
                    log.once("key-attestation-refused", "lyrelog: the server refused this device's key attestation (an unlocked bootloader, a build not signed with a certificate set in the project's settings, or an emulator); events are stored unverified, a new try in a day")
                    return Result.Retry(null)
                }
                is Answer.Unknown -> {
                    forgetKey(record, refused = false)
                    return Result.Disable("not set up for this project: add the app's package and signing certificate in its settings")
                }
                is Answer.Unauthorized -> {
                    forgetKey(record, refused = false)
                    return Result.Disable("the server answered ${answer.status}")
                }
                is Answer.Again -> {
                    forgetKey(record, refused = false) // a new key, over a new challenge, next time
                    return Result.Retry(answer.afterMillis)
                }
            }
        }
        return Result.Retry(null)
    }

    private fun forgetKey(record: AttestRecordFile.Record, refused: Boolean): AttestRecordFile.Record {
        try {
            provider.deleteKey()
        } catch (e: Exception) {
            // gone already
        }
        val fresh = AttestRecordFile.Record(null, if (refused) now() else record.refusedAtMillis)
        if (fresh.refusedAtMillis == null) store.delete() else store.save(fresh)
        return fresh
    }

    private fun tokenFrom(body: JsonValue?): Result.Got? {
        val value = body["token"].string?.takeIf { it.isNotEmpty() } ?: return null
        val expires = (body["expires_at"] as? JsonValue.Integer)?.value ?: return null
        return Result.Got(value, expires * 1000)
    }

    private sealed class Answer {
        class Ok(val body: JsonValue?) : Answer()
        object Unknown : Answer()
        object Refused : Answer()
        class Unauthorized(val status: Int) : Answer()
        class Again(val afterMillis: Long?) : Answer()
    }

    private fun post(url: URL, fields: Map<String, Any>): Answer {
        val body = JsonValue.from(fields).encode().toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            "User-Agent" to userAgent,
        )
        val answer = try {
            transport.post(url, headers, body)
        } catch (e: IOException) {
            return Answer.Again(null)
        } catch (e: RuntimeException) {
            return Answer.Again(null)
        }
        return when (answer.status) {
            in 200..299 -> Answer.Ok(Json.parse(answer.body))
            404 -> Answer.Unknown
            401, 403 -> Answer.Unauthorized(answer.status)
            408, 425, 429, in 500..599 -> Answer.Again(ResponseClassifier.parseRetryAfter(answer.header("Retry-After")))
            in 400..499 -> Answer.Refused
            else -> Answer.Again(null)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64Text.encode(bytes)

    companion object {
        const val HEADER = "X-Lyrelog-Attestation"
        /** Renew when less than this is left (or half the token's life, if shorter). */
        const val RENEW_BEFORE_MILLIS = 3_600_000L
        const val REFUSAL_PAUSE_MILLIS = 86_400_000L
        const val BACKOFF_BASE_MILLIS = 30_000L
        const val BACKOFF_CAP_MILLIS = 3_600_000L
    }
}

/**
 * Standard base64 with padding (RFC 4648 section 4). Not `java.util.Base64`: Android has it only
 * from API 26, and on 24 and 25 the attestation thread would end the app with a
 * `NoClassDefFoundError`. `android.util.Base64` isn't reachable from `core`.
 */
internal object Base64Text {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[(b0 and 0x03 shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < bytes.size) ALPHABET[(b1 and 0x0f shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }
}
