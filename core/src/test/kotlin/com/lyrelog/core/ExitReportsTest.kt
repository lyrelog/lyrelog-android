package com.lyrelog.core

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ExitReportsTest {
    /** An ANR trace as ApplicationExitInfo gives it (trimmed): the main thread's Java frames. */
    private val anrTrace = """
        ----- pid 4321 at 2026-11-25 14:00:00.000 -----
        Cmd line: com.example.tilefall

        "main" prio=5 tid=1 Sleeping
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a1e3d8 self=0xb4000071c7a3a7b0
          at java.lang.Thread.sleep(Native method)
          - sleeping on <0x0a1b2c3d> (a java.lang.Object)
          at java.lang.Thread.sleep(Thread.java:450)
          at com.example.tilefall.Board.solve(Board.kt:88)
          at com.example.tilefall.MainActivity.onClick(MainActivity.kt:20)
          native: #00 pc 000000000004f0b8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24)

        "Signal Catcher" daemon prio=10 tid=2 Runnable
          at java.lang.Object.wait(Native method)
    """.trimIndent()

    @Test
    fun anAnrsMainThreadFramesAreRead() {
        assertEquals(
            listOf(
                "java.lang.Thread.sleep(Native method)",
                "java.lang.Thread.sleep(Thread.java:450)",
                "com.example.tilefall.Board.solve(Board.kt:88)",
                "com.example.tilefall.MainActivity.onClick(MainActivity.kt:20)",
            ),
            ExitReports.anrFrames(anrTrace),
        )
        val (attrs, payload) = ExitReports.anrEvent("Input dispatching timed out", anrTrace)
        assertEquals("anr", attrs["mechanism"])
        assertEquals("Input dispatching timed out", attrs["description"])
        assertEquals(4, (payload["frames"] as List<*>).size)
    }

    // A protobuf writer for the test: the fields of AOSP's tombstone.proto it reads.
    private class W {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun field(n: Int, v: Long) { varint((n.toLong() shl 3) or 0); varint(v) }
        fun field(n: Int, s: String) = field(n, s.toByteArray())
        fun field(n: Int, b: ByteArray) { varint((n.toLong() shl 3) or 2); varint(b.size.toLong()); out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, function: String?, offset: Long, file: String, buildId: String) = W().apply {
        field(1, relPc); field(2, relPc + 0x7000000000); if (function != null) field(4, function); field(5, offset); field(6, file); field(8, buildId)
    }.bytes()

    private fun tombstone(): ByteArray {
        val crashed = W().apply {
            field(1, 4321); field(2, "main")
            field(4, frame(0x1a2b, "Game::step(int)", 24, "/data/app/~~x==/com.example.tilefall-y==/lib/arm64/libgame.so", "abc123"))
            field(4, frame(0x99, null, 0, "/apex/com.android.runtime/lib64/bionic/libc.so", "libc1"))
            field(4, frame(0x500, "art_quick_invoke_stub", 548, "/apex/com.android.art/lib64/libart.so", "art1"))
        }.bytes()
        val other = W().apply { field(1, 4322); field(4, frame(0x10, "other", 0, "/system/lib64/libfoo.so", "f")) }.bytes()
        val signal = W().apply { field(1, 11); field(2, "SIGSEGV"); field(3, 1); field(4, "SEGV_MAPERR"); field(8, 1); field(9, 0x10) }.bytes()
        return W().apply {
            field(1, 3) // arch
            field(6, 4321) // tid
            field(10, signal)
            field(14, "")
            field(16, W().apply { field(1, 4322); field(2, other) }.bytes())
            field(16, W().apply { field(1, 4321); field(2, crashed) }.bytes())
        }.bytes()
    }

    @Test
    fun aTombstoneGivesTheSignalAndTheCrashedThreadsFrames() {
        val t = ExitReports.tombstone(tombstone())!!
        assertEquals(11, t.signal)
        assertEquals("SIGSEGV", t.signalName)
        assertEquals("SEGV_MAPERR", t.codeName)
        assertEquals(0x10L, t.faultAddress)
        assertEquals(listOf("libgame.so +0x1a2b (Game::step(int)+24)", "libc.so +0x99", "libart.so +0x500 (art_quick_invoke_stub+548)"), t.frames.map { it.text })
        val (attrs, payload) = ExitReports.nativeCrashEvent("crash", t)
        assertEquals("native", attrs["mechanism"])
        assertEquals("SIGSEGV", attrs["signal"])
        // The app's own frame, not libart's.
        assertEquals("Game::step(int)", attrs["native_function"])
        assertEquals(mapOf("libgame.so" to "abc123", "libc.so" to "libc1", "libart.so" to "art1"), payload["build_ids"])
        assertEquals("0x10", payload["fault_address"])
        assertEquals(listOf("libgame.so"), payload["app_binaries"])
    }

    @Test
    fun aLibraryLoadedFromTheApkIsNamedByItself() {
        val f = ExitReports.NativeFrame(0x10, null, 0, "/data/app/~~x==/com.example.tilefall-y==/base.apk!libgame.so", "abc")
        assertEquals("libgame.so", f.binary)
        assertEquals(true, f.inApp)
    }

    @Test
    fun somethingElseIsNoTombstone() {
        assertNull(ExitReports.tombstone("not a tombstone at all".toByteArray()))
        assertNull(ExitReports.tombstone(ByteArray(0)))
        assertNull(ExitReports.tombstone(byteArrayOf(0x52, 0x7f)))
    }
}
