package com.lyrelog.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskQueueTest {
    private var clock = 1_000_000_000_000L
    private fun queue(dir: File, total: Long = 1L shl 30, age: Long = 7L * 86_400_000, fileEvents: Int = 500, fileBytes: Int = 512 * 1024) =
        DiskQueue(dir, DiskQueue.Limits(fileBytes, fileEvents, total, age)) { clock }

    private fun line(n: Int, size: Int = 10) = ("{\"n\":$n,\"pad\":\"" + "x".repeat(size) + "\"}").toByteArray()

    @Test
    fun filesSealAtTheEventAndByteLimits() {
        val q = queue(tempDir(), fileEvents = 3)
        repeat(7) { q.append(line(it), DiskQueue.Area.READY) }
        assertEquals(2, q.readyBatches().size)
        q.seal()
        val batches = q.readyBatches()
        assertEquals(listOf(3, 3, 1), batches.map { String(it.readBytes()).count { c -> c == '\n' } })

        val bytes = queue(tempDir(), fileBytes = 1000)
        repeat(10) { bytes.append(line(it, 200), DiskQueue.Area.READY) }
        bytes.seal()
        assertTrue(bytes.readyBatches().all { it.length() <= 1000 })
        assertEquals(10, bytes.readyBatches().sumOf { String(it.readBytes()).count { c -> c == '\n' } })
    }

    @Test
    fun theOldestFilesGoFirstOverTheSizeCapAndCountAsDrops() {
        val q = queue(tempDir(), total = 2_000, fileEvents = 1)
        repeat(20) {
            clock += 1
            q.append(line(it, 200), DiskQueue.Area.READY)
        }
        val left = q.readyBatches()
        assertTrue(left.sumOf { it.length() } <= 2_000)
        assertTrue(String(left.last().readBytes()).contains("\"n\":19"))
        assertEquals(20 - left.size, q.droppedEvents)
        assertEquals(20 - left.size, q.takeUnreportedDrops())
        assertEquals(0, q.takeUnreportedDrops())
    }

    @Test
    fun filesOlderThanTheMaximumAgeAreDropped() {
        val dir = tempDir()
        val q = queue(dir, age = 7L * 86_400_000)
        q.append(line(1), DiskQueue.Area.READY)
        q.seal()
        clock += 8L * 86_400_000
        q.append(line(2), DiskQueue.Area.READY)
        q.seal()
        val left = q.readyBatches()
        assertEquals(1, left.size)
        assertTrue(String(left[0].readBytes()).contains("\"n\":2"))
        assertEquals(1, q.droppedEvents)
    }

    @Test
    fun eventsSurviveARestartAndATornLastLineIsCut() {
        val dir = tempDir()
        val first = queue(dir)
        first.append(line(1), DiskQueue.Area.READY)
        first.append(line(2), DiskQueue.Area.READY)
        // The app dies mid-write: half a line at the end of the open file.
        val open = File(dir, "ready").listFiles()!!.single()
        open.appendText("{\"n\":3,\"pa")
        val second = queue(dir)
        val batches = second.readyBatches()
        assertEquals(1, batches.size)
        assertEquals("${String(line(1))}\n${String(line(2))}\n", String(batches[0].readBytes()))
    }

    @Test
    fun pendingIsKeptApartPromotedOrDeleted() {
        val dir = tempDir()
        val q = queue(dir)
        q.append(line(1), DiskQueue.Area.PENDING)
        q.append(line(2), DiskQueue.Area.READY) // a new area seals the pending file
        q.seal()
        assertEquals(1, q.readyBatches().size)
        q.promotePending()
        assertEquals(2, q.readyBatches().size)
        q.append(line(3), DiskQueue.Area.PENDING)
        q.removeAll()
        assertTrue(q.isEmpty())
    }

    /**
     * Making the queue touches no file: `Lyrelog.start` runs on the main thread (in
     * `Application.onCreate`), where StrictMode flags disk reads and writes. The writer thread
     * prepares it on first use (directories, torn files repaired).
     */
    @Test
    fun makingTheQueueTouchesNoFileUntilItIsUsed() {
        val root = File(tempDir(), "queue")
        val q = queue(root)
        assertFalse(root.exists())

        // A file an earlier launch was writing when it died, half a line at its end.
        val ready = File(root, "ready").apply { mkdirs() }
        val torn = File(ready, "000000000000001-000000000001-a.ndjson").apply { writeText("{\"n\":1}\n{\"n\":2") }
        val again = queue(root)
        assertEquals("{\"n\":1}\n{\"n\":2", torn.readText())
        assertEquals(listOf(torn), again.readyBatches())
        assertEquals("{\"n\":1}\n", torn.readText())
        q.append(line(1), DiskQueue.Area.PENDING)
        assertTrue(File(root, "pending").list()!!.isNotEmpty())
    }
}
