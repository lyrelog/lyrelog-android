package com.lyrelog.jvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    private val root = File(System.getProperty("lyrelog.repoRoot"))

    /** `Lyrelog.VERSION` (both of them), the artifacts' version and the README install lines agree. */
    @Test
    fun oneVersionEverywhere() {
        val built = System.getProperty("lyrelog.version")
        assertEquals("1.0.0", Lyrelog.VERSION)
        assertEquals(built, Lyrelog.VERSION)
        assertEquals("lyrelog-jvm/$built", ServerDefaults.SDK)
        val android = File(root, "android/src/main/kotlin/com/lyrelog/Lyrelog.kt").readText()
        assertTrue(android.contains("const val VERSION = \"$built\""), "the Android SDK's VERSION")
        val readme = File(root, "README.md").readText()
        val installs = Regex("""com\.lyrelog:lyrelog-[a-z-]+:([0-9][^"]*)""").findAll(readme).map { it.groupValues[1] }.toList()
        assertTrue(installs.isNotEmpty())
        assertEquals(setOf(built), installs.toSet(), "README install lines")
    }
}
