package com.lyrelog

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.nio.file.Files
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Lyrelog.start` runs in `Application.onCreate`, on the main thread. */
class StartTest {
    /** A context that notes every call that would touch the disk on the calling thread. */
    private class AppContext(private val data: File) : ContextWrapper(null) {
        val diskCalls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = "com.example.tilefall"
        override fun getApplicationInfo() = ApplicationInfo().apply { dataDir = data.path }

        // Each of these checks or makes its directory (StrictMode: a disk read, or a write).
        override fun getNoBackupFilesDir(): File = File(data, "no_backup").also { diskCalls += "noBackupFilesDir" }
        override fun getFilesDir(): File = File(data, "files").also { diskCalls += "filesDir" }
        override fun getCacheDir(): File = File(data, "cache").also { diskCalls += "cacheDir" }
        override fun getDataDir(): File = data.also { diskCalls += "dataDir" }
    }

    @After
    fun tearDown() = Lyrelog.stopNow()

    @Test
    fun startTouchesNoDiskOnTheCallingThread() {
        val data = Files.createTempDirectory("lyrelog-app").toFile()
        val context = AppContext(data)
        Lyrelog.start(context, Configuration("lyr_ing_test") { endpoint = "http://127.0.0.1:9"; captureCrashes = false; attest = false })
        assertNotNull("started", Lyrelog.sessionId)
        assertEquals(emptyList<String>(), context.diskCalls.toList())

        // The queue is where it always was, made by the writer.
        Lyrelog.info("first")
        Lyrelog.stopNow()
        val queue = File(data, "no_backup/lyrelog/queue/ready")
        assertTrue(queue.list().orEmpty().isNotEmpty())
    }

    /** In `Application.onCreate`, anything thrown is a crash at every launch: not even an Error leaves `start`. */
    @Test
    fun startNeverThrowsEvenAnError() {
        val broken = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = throw NoSuchMethodError("a method missing on this Android version")
        }
        assertEquals(false, Lyrelog.start(broken, Configuration("lyr_ing_test")))
        assertEquals(false, Lyrelog.isStarted)
        assertEquals(null, Lyrelog.sessionId)
    }

    /** `start` says whether it started, as the JVM SDK's does; `isStarted` and `consent` say what runs. */
    @Test
    fun startSaysWhetherItStartedAndConsentIsReadable() {
        val context = AppContext(Files.createTempDirectory("lyrelog-app").toFile())
        val configuration = Configuration("lyr_ing_test") {
            endpoint = "http://127.0.0.1:9"
            captureCrashes = false
            attest = false
            consent = Consent.PENDING
        }
        assertEquals(false, Lyrelog.isStarted)
        assertEquals(null, Lyrelog.consent)
        Lyrelog.setConsent(Consent.DENIED)
        assertEquals("a consent before start is the one that will apply", Consent.DENIED, Lyrelog.consent)
        Lyrelog.setConsent(Consent.GRANTED)

        assertEquals(true, Lyrelog.start(context, configuration))
        assertEquals(true, Lyrelog.isStarted)
        assertEquals("setConsent before start wins over the configuration", Consent.GRANTED, Lyrelog.consent)
        assertEquals("a second start is ignored", false, Lyrelog.start(context, configuration))

        Lyrelog.setConsent(Consent.PENDING)
        assertEquals(Consent.PENDING, Lyrelog.consent)

        Lyrelog.stopNow()
        assertEquals(false, Lyrelog.isStarted)
        assertEquals(null, Lyrelog.consent)
        assertEquals("start works again after a stop", true, Lyrelog.start(context, configuration))
        assertEquals(Consent.PENDING, Lyrelog.consent)
    }
}
