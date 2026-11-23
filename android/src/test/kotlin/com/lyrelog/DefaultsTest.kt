package com.lyrelog

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android defaults that don't need a device. Everything else is tested in `core`. */
class DefaultsTest {
    @Test
    fun aWrapperNamesItselfBeforeThisSdk() {
        assertEquals("lyrelog-flutter/0.9.0 ${DeviceContext.SDK}", DeviceContext.sdk("lyrelog-flutter/0.9.0"))
        assertEquals(DeviceContext.SDK, DeviceContext.sdk(null))
        assertEquals(DeviceContext.SDK, DeviceContext.sdk(" \n"))
    }

    @Test
    fun environmentFollowsDebuggable() {
        assertEquals("development", Defaults.environment(true))
        assertEquals("production", Defaults.environment(false))
    }

    @Test
    fun deviceModelNamesTheMakerOnce() {
        assertEquals("Google Pixel 9", Defaults.deviceModel("Google", "Pixel 9"))
        assertEquals("samsung SM-S921B", Defaults.deviceModel("samsung", "SM-S921B"))
        assertEquals("OnePlus CPH2581", Defaults.deviceModel("OnePlus", "OnePlus CPH2581".removePrefix("OnePlus ")))
        assertEquals("Motorola moto g", Defaults.deviceModel("motorola", "Motorola moto g"))
        assertEquals("Google", Defaults.deviceModel("Google", ""))
    }

    @Test
    fun localeUsesAnUnderscore() {
        assertEquals("es_ES", Defaults.locale(Locale.forLanguageTag("es-ES")))
        assertEquals("en", Defaults.locale(Locale.forLanguageTag("en")))
    }

    @Test
    fun theEmulatorIsRecognised() {
        assertTrue(Defaults.isEmulator("ranchu", "sdk_gphone64_arm64", "google/sdk_gphone64_arm64/emu64a:15/..."))
        assertFalse(Defaults.isEmulator("tensor", "shiba", "google/shiba/shiba:15/..."))
    }

    @Test
    fun theConfigurationNeverPrintsTheKey() {
        assertFalse(Configuration("lyr_ing_secret").toString().contains("lyr_ing_secret"))
    }
}
