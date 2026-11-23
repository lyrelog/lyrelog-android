package com.lyrelog.core

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttestorTest {
    private class FakeKeystore : AttestationProvider {
        var key: String? = null
        var created = 0
        var unsupported = false
        var lastChallenge: ByteArray? = null
        override fun createKey(challenge: ByteArray): List<ByteArray> {
            if (unsupported) throw AttestationUnsupported("no")
            created += 1
            key = "key$created"
            lastChallenge = challenge
            return listOf("leaf-$key".toByteArray(), "root".toByteArray())
        }
        override fun hasKey() = key != null
        override fun sign(data: ByteArray): ByteArray = "${key ?: throw AttestationKeyInvalid("gone")}:${String(data)}".toByteArray()
        override fun deleteKey() {
            key = null
        }
    }

    /** The server, scripted: each route answers what the test sets. */
    private inner class FakeServer : Transport {
        val calls = mutableListOf<Pair<String, JsonValue?>>()
        var challengeStatus = 200
        var keyStatus = 200
        var tokenStatus = 200
        var retryAfter: String? = null
        private var n = 0
        override fun post(url: URL, headers: Map<String, String>, body: ByteArray): HttpAnswer {
            val path = url.path
            calls += path to Json.parse(String(body))
            check(headers["Authorization"] == "Bearer lyr_ing_test")
            val h = retryAfter?.let { mapOf("retry-after" to it) } ?: emptyMap()
            return when (path) {
                "/v1/attest/challenge" -> HttpAnswer(challengeStatus, h, if (challengeStatus == 200) """{"challenge":"c${++n}"}""" else "{}")
                "/v1/attest/android/key" -> HttpAnswer(keyStatus, h, if (keyStatus == 200) """{"token":"t-key","expires_at":${(clock + 86_400_000) / 1000},"key_id":"kid"}""" else "{}")
                "/v1/attest/android/token" -> HttpAnswer(tokenStatus, h, if (tokenStatus == 200) """{"token":"t-renewed","expires_at":${(clock + 86_400_000) / 1000}}""" else "{}")
                else -> HttpAnswer(404, h, "{}")
            }
        }
        fun paths() = calls.map { it.first }
    }

    private var clock = NOW
    private val dir: File = Files.createTempDirectory("attest").toFile()

    private fun attestor(server: FakeServer, keystore: AttestationProvider, logs: MutableList<String> = mutableListOf()) = Attestor(
        java.net.URI.create("https://ingest.example").toURL(), "lyr_ing_test", "lyrelog-android/test", server, keystore,
        AttestRecordFile(File(dir, "attest.json")), SdkLog { _, m -> logs += m }, { clock }, Executor { it.run() },
    )

    @Test
    fun base64IsTheStandardOneWithPadding() {
        val random = java.util.Random(7)
        for (size in (0..70) + listOf(1023, 1024, 1025, 4096)) {
            val bytes = ByteArray(size).also(random::nextBytes)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), Base64Text.encode(bytes), "size $size")
        }
    }

    @Test
    fun attestsOnceThenRenewsBySigning() {
        val server = FakeServer()
        val keystore = FakeKeystore()
        val a = attestor(server, keystore)
        assertEquals("t-key", a.header()) // the refresh ran inline: the token is there
        assertEquals(listOf("/v1/attest/challenge", "/v1/attest/android/key"), server.paths())
        val sent = server.calls[1].second
        assertEquals("c1", sent["challenge"].string)
        assertEquals("c1", String(keystore.lastChallenge!!)) // the attestation carries our challenge
        assertEquals(2, (sent["chain"] as JsonValue.Arr).items.size)

        // A new launch: the key id was kept, the key signs a fresh challenge.
        clock += 1000
        val next = attestor(server, keystore)
        assertEquals("t-renewed", next.header())
        val renewal = server.calls.last().second
        assertEquals("kid", renewal["key_id"].string)
        assertEquals("c2", renewal["challenge"].string)
        assertEquals(1, keystore.created)
    }

    @Test
    fun aRenewAnswerFetchesAnotherToken() {
        val server = FakeServer()
        val a = attestor(server, FakeKeystore())
        assertEquals("t-key", a.header())
        a.renew()
        assertEquals("t-renewed", a.header())
    }

    @Test
    fun renewsWhenLittleTimeIsLeft() {
        val server = FakeServer()
        val a = attestor(server, FakeKeystore())
        a.header()
        val before = a.refreshes
        clock += 86_400_000 - 1_800_000 // half an hour left
        assertEquals("t-renewed", a.header())
        assertEquals(before + 1, a.refreshes)
    }

    @Test
    fun notSetUpTurnsItOffForTheLaunch() {
        val server = FakeServer().apply { challengeStatus = 404 }
        val logs = mutableListOf<String>()
        val a = attestor(server, FakeKeystore(), logs)
        assertNull(a.header())
        assertNull(a.header())
        assertEquals(1, server.calls.size)
        assertTrue(logs.single().contains("off for this launch"))
    }

    @Test
    fun noAndroidAppsInTheProjectTurnsItOff() {
        val server = FakeServer().apply { keyStatus = 404 }
        val keystore = FakeKeystore()
        val a = attestor(server, keystore)
        assertNull(a.header())
        assertNull(a.header())
        assertEquals(2, server.calls.size)
        assertNull(keystore.key) // the unused key is deleted
    }

    @Test
    fun aRefusedDeviceWaitsADay() {
        val server = FakeServer().apply { keyStatus = 400 }
        val keystore = FakeKeystore()
        val logs = mutableListOf<String>()
        assertNull(attestor(server, keystore, logs).header())
        assertTrue(logs.any { it.contains("refused") })
        // The next launches within a day don't ask again, not even for a challenge.
        clock += 3_600_000
        assertNull(attestor(server, keystore).header())
        assertEquals(2, server.calls.size)
        clock += 86_400_000
        server.keyStatus = 200
        assertEquals("t-key", attestor(server, keystore).header())
    }

    @Test
    fun anUnknownKeyIsAttestedAgain() {
        val server = FakeServer()
        val keystore = FakeKeystore()
        attestor(server, keystore).header()
        server.tokenStatus = 404
        clock += 1000
        val a = attestor(server, keystore)
        server.calls.clear()
        assertEquals("t-key", a.header())
        assertEquals(listOf("/v1/attest/challenge", "/v1/attest/android/token", "/v1/attest/challenge", "/v1/attest/android/key"), server.paths())
        assertEquals(2, keystore.created)
    }

    @Test
    fun aBusyServerIsAskedAgainLaterNotAtOnce() {
        val server = FakeServer().apply { keyStatus = 503; retryAfter = "120" }
        val a = attestor(server, FakeKeystore())
        assertNull(a.header())
        val calls = server.calls.size
        clock += 60_000
        assertNull(a.header()) // still waiting: Retry-After was two minutes
        assertEquals(calls, server.calls.size)
        clock += 61_000
        server.keyStatus = 200
        assertEquals("t-key", a.header())
    }

    /** An Error from the Keystore (a missing class on an old Android) is a failure like any other, never thrown. */
    @Test
    fun anErrorFromTheKeystoreIsRetriedLaterAndNeverThrown() {
        val server = FakeServer()
        var fail = true
        val keystore = object : AttestationProvider {
            val inner = FakeKeystore()
            override fun createKey(challenge: ByteArray): List<ByteArray> {
                if (fail) throw NoClassDefFoundError("android/security/keystore/Something")
                return inner.createKey(challenge)
            }
            override fun hasKey() = inner.hasKey()
            override fun sign(data: ByteArray) = inner.sign(data)
            override fun deleteKey() = inner.deleteKey()
        }
        val a = attestor(server, keystore)
        assertNull(a.header())
        assertNull(a.header()) // waiting out the backoff, not asking again
        assertEquals(1, a.refreshes)
        fail = false
        clock += Attestor.BACKOFF_BASE_MILLIS
        assertEquals("t-key", a.header())
    }

    @Test
    fun aDeviceThatCantAttestTurnsItOff() {
        val server = FakeServer()
        val a = attestor(server, FakeKeystore().apply { unsupported = true })
        assertNull(a.header())
        assertNull(a.header())
        assertEquals(1, server.calls.size)
    }

    @Test
    fun forgetDeletesTheKeyAndTheRecord() {
        val server = FakeServer()
        val keystore = FakeKeystore()
        val a = attestor(server, keystore)
        assertNotNull(a.header())
        a.forget()
        assertNull(keystore.key)
        assertTrue(!File(dir, "attest.json").exists())
    }

    @Test
    fun theUploaderCarriesTheTokenAndHonoursRenew() {
        val server = FakeServer()
        val a = attestor(server, FakeKeystore())
        val seen = mutableListOf<String?>()
        val queue = DiskQueue(File(dir, "queue"), DiskQueue.Limits(512 * 1024, 500, 20L * 1024 * 1024, 7L * 86_400_000), { clock })
        val ingest = Transport { url, headers, body ->
            if (url.path.startsWith("/v1/attest")) return@Transport server.post(url, headers, body)
            seen += headers[Attestor.HEADER]
            HttpAnswer(200, if (seen.size == 1) mapOf("x-lyrelog-attestation" to "renew") else emptyMap(), """{"accepted":1,"rejected":[]}""")
        }
        val uploader = Uploader(java.net.URI.create("https://ingest.example").toURL(), "lyr_ing_test", ingest, queue, ConsentGate(com.lyrelog.Consent.GRANTED), SdkLog.silent(), "ua", { clock }, attestor = a)
        repeat(2) {
            queue.append("""{"message":"m$it"}""".toByteArray(), DiskQueue.Area.READY)
            queue.seal()
            uploader.round(force = true)
        }
        assertEquals(listOf<String?>("t-key", "t-renewed"), seen)
    }

    companion object {
        const val NOW = 1_800_000_000_000L
    }
}
