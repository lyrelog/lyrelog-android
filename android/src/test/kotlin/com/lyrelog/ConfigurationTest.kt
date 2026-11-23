package com.lyrelog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigurationTest {
    @Test
    fun theDefaults() {
        val c = Configuration("lyr_ing_test")
        assertEquals("lyr_ing_test", c.apiKey)
        assertEquals(Configuration.DEFAULT_ENDPOINT, c.endpoint)
        assertEquals(Consent.GRANTED, c.consent)
        assertEquals(Level.DEBUG, c.minimumLevel)
        assertEquals(15_000L, c.flushIntervalMillis)
        assertEquals(512 * 1024, c.maxBatchBytes)
        assertEquals(500, c.maxBatchEvents)
        assertEquals(20L * 1024 * 1024, c.maxDiskBytes)
        assertEquals(7L * 86_400_000, c.maxEventAgeMillis)
        assertEquals(30L * 60_000, c.sessionTimeoutMillis)
        assertNull(c.environment)
        assertNull(c.redactedKeys)
        assertEquals(true, c.captureCrashes)
        assertEquals(true, c.attachScreen)
        assertEquals(false, c.automaticScreens)
        assertEquals(false, c.mirrorToLogcat)
        assertEquals(true, c.attest)
        assertNull(c.wrapper)
        assertNull(c.storageDirectory)
    }

    @Test
    fun kotlinSetsWhatItNeedsAndCopies() {
        val c = Configuration("lyr_ing_test") {
            consent = Consent.PENDING
            environment = "beta"
            redactedKeys = Configuration.DEFAULT_REDACTED_KEYS + "pin"
            storageDirectory = File("/tmp/q")
        }
        assertEquals(Consent.PENDING, c.consent)
        assertEquals("beta", c.environment)
        assertEquals("pin", c.redactedKeys!!.last())

        val changed = c.copy { consent = Consent.DENIED; wrapper = "lyrelog-flutter/0.9.0" }
        assertNotSame(c, changed)
        assertEquals(Consent.PENDING, c.consent)
        assertEquals(Consent.DENIED, changed.consent)
        assertEquals("beta", changed.environment)
        assertEquals(File("/tmp/q"), changed.storageDirectory)
        assertEquals("lyrelog-flutter/0.9.0", changed.wrapper)
        assertEquals(c.toBuilder().build().toString(), c.toString())
    }
}
