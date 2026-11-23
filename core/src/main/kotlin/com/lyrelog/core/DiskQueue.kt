package com.lyrelog.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Events wait on disk until the server confirms them, so a crash, a kill or a week offline
 * loses nothing that reached the disk (up to the caps). The Swift SDK's layout:
 *
 *     ready/<millis>-<seq>-<uuid>.ndjson     batches to send, oldest first
 *     pending/<millis>-<seq>-<uuid>.ndjson   kept while consent is pending, never sent
 *
 * Every event is appended to the open file at once (one `write`, so it is in the kernel before
 * the call returns: a crash of the app loses nothing written). A file is sealed (becomes one
 * batch, never written again) when it reaches the size or count limit, or when the uploader asks.
 * One sealed file = one request; a retry resends exactly the same bytes.
 *
 * Thread-safe: every method holds the queue's lock, and none does network I/O.
 */
internal class DiskQueue(
    val root: File,
    val limits: Limits,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Limits(
        val maxFileBytes: Int,
        val maxFileEvents: Int,
        val maxTotalBytes: Long,
        val maxAgeMillis: Long,
    )

    enum class Area(val directory: String) {
        READY("ready"),
        PENDING("pending"),
    }

    private class OpenFile(val file: File, val area: Area, val stream: FileOutputStream) {
        var bytes = 0
        var events = 0
    }

    private val lock = Any()
    private var open: OpenFile? = null
    private var sequence = 0L
    private var unreportedDrops = 0

    /** Every event lost since this queue was made (caps, write failures, refused batches). */
    var droppedEvents = 0
        private set

    /**
     * Whether the directories exist and torn files were repaired. Done on first use, not when
     * the queue is made: that is `Lyrelog.start`, on the main thread, where StrictMode flags any
     * disk access; the first use is the writer's.
     */
    private var prepared = false

    fun append(line: ByteArray, area: Area): Unit = synchronized(lock) {
        val size = line.size + 1
        val current = open
        if (current != null && (current.area != area || current.bytes + size > limits.maxFileBytes)) seal()
        val target = open ?: try {
            createFile(area).also { open = it }
        } catch (e: IOException) {
            noteDropped(1)
            return
        }
        val record = ByteArray(size)
        System.arraycopy(line, 0, record, 0, line.size)
        record[line.size] = '\n'.code.toByte()
        try {
            target.stream.write(record)
            target.bytes += size
            target.events += 1
        } catch (e: IOException) {
            // Usually a full disk. Cut the file back to its last whole line and close it, so
            // what it already holds still goes; if even that fails, the file goes too.
            noteDropped(1)
            open = null
            try {
                target.stream.channel.truncate(target.bytes.toLong())
                target.stream.close()
                if (target.events == 0) target.file.delete()
            } catch (e: IOException) {
                closeQuietly(target.stream)
                target.file.delete()
                noteDropped(target.events)
            }
            return
        }
        if (target.events >= limits.maxFileEvents || target.bytes >= limits.maxFileBytes) seal()
    }

    /** Whether the open file holds anything. */
    val hasOpenEvents: Boolean get() = synchronized(lock) { (open?.events ?: 0) > 0 }

    /** Drops since the last call, to report them. */
    fun takeUnreportedDrops(): Int = synchronized(lock) {
        val count = unreportedDrops
        unreportedDrops = 0
        count
    }

    fun noteDropped(count: Int) = synchronized(lock) {
        droppedEvents += count
        unreportedDrops += count
    }

    /** Close the open file so it can be sent, then enforce the caps. */
    fun seal(): Unit = synchronized(lock) {
        val current = open ?: return
        closeQuietly(current.stream)
        open = null
        if (current.events == 0) current.file.delete()
        enforceLimits()
    }

    /** Sealed batches waiting to be sent, oldest first. */
    fun readyBatches(): List<File> = synchronized(lock) {
        files(Area.READY).filter { it != open?.file }
    }

    fun contents(batch: File): ByteArray? = synchronized(lock) {
        try {
            batch.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    fun remove(batch: File) = synchronized(lock) {
        batch.delete()
        Unit
    }

    /** Remove a batch that will never be delivered, counting its events. */
    fun drop(batch: File) = synchronized(lock) {
        noteDropped(lineCount(batch))
        batch.delete()
        Unit
    }

    /** Consent granted: what was kept while pending becomes sendable. */
    fun promotePending() = synchronized(lock) {
        if (open?.area == Area.PENDING) seal()
        for (file in files(Area.PENDING)) {
            file.renameTo(File(File(root, Area.READY.directory), file.name))
        }
    }

    /** Consent denied: nothing recorded may stay on the device. */
    fun removeAll() = synchronized(lock) {
        open?.let { closeQuietly(it.stream) }
        open = null
        for (area in Area.values()) files(area).forEach { it.delete() }
        unreportedDrops = 0
    }

    /** Whether anything at all waits (any area, open file included). */
    fun isEmpty(): Boolean = synchronized(lock) { Area.values().all { files(it).isEmpty() } }

    /** Drop files older than maxAge, then the oldest ones until under maxTotalBytes. */
    fun enforceLimits() = synchronized(lock) {
        val cutoff = now() - limits.maxAgeMillis
        val all = Area.values().flatMap { files(it) }.filter { it != open?.file }
            .sortedWith(compareBy<File>({ creation(it) }, { it.name }))
        var total = all.sumOf { it.length() } + (open?.bytes ?: 0)
        for (file in all) {
            if (creation(file) >= cutoff && total <= limits.maxTotalBytes) continue
            val size = file.length()
            noteDropped(lineCount(file))
            file.delete()
            total -= size
        }
    }

    /** Closes the open file (the engine stopped); what it holds goes as a batch next time. */
    fun close() = synchronized(lock) { seal() }

    private fun createFile(area: Area): OpenFile {
        prepare()
        sequence += 1
        val name = String.format(java.util.Locale.ROOT, "%015d-%012d-%s.ndjson", now(), sequence, UUID.randomUUID())
        val file = File(File(root, area.directory), name)
        file.parentFile?.mkdirs()
        return OpenFile(file, area, FileOutputStream(file, true))
    }

    /**
     * A file the app was writing when it died may end in half a line: cut it back to its last
     * newline, so every batch is whole lines.
     */
    private fun repairTornFiles() {
        for (area in Area.values()) {
            for (file in files(area)) {
                try {
                    RandomAccessFile(file, "rw").use { raf ->
                        var end = raf.length()
                        while (end > 0) {
                            raf.seek(end - 1)
                            if (raf.read() == '\n'.code) break
                            end--
                        }
                        if (end != raf.length()) raf.setLength(end)
                    }
                    if (file.length() == 0L) file.delete()
                } catch (e: IOException) {
                    file.delete()
                }
            }
        }
    }

    /** The caller holds [lock]. */
    private fun prepare() {
        if (prepared) return
        prepared = true
        for (area in Area.values()) File(root, area.directory).mkdirs()
        repairTornFiles()
    }

    private fun files(area: Area): List<File> {
        prepare()
        val names = File(root, area.directory).list() ?: return emptyList()
        return names.filter { it.endsWith(".ndjson") }.sorted().map { File(File(root, area.directory), it) }
    }

    private fun lineCount(file: File): Int = try {
        file.readBytes().count { it == '\n'.code.toByte() }
    } catch (e: IOException) {
        0
    }

    private fun closeQuietly(stream: FileOutputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
            // nothing to do
        }
    }

    companion object {
        /** When a file was made, from the milliseconds at the start of its name. */
        fun creation(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: 0L
    }
}
