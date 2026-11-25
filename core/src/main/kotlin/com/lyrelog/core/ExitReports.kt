package com.lyrelog.core

/**
 * What Android says ended the app's previous processes (ApplicationExitInfo, Android 11+), read
 * into Lyrelog events on the next launch (2026-11-25): an ANR, with the main thread's frames from
 * its trace, and a native (C/C++) crash, with the crashed thread's frames from its tombstone
 * (Android 12+). JVM crashes aren't here: [CrashRecorder] has them already, when they happen.
 *
 * Plain Kotlin, no Android API, so it's tested on the JVM; `com.lyrelog.ExitReasons` (the
 * Android module) asks ActivityManager and hands the traces here.
 */
object ExitReports {
    /** The most frames an event carries, as the Swift SDK's crash reports. */
    const val MAX_FRAMES = 60

    /**
     * The main thread's frames in an ANR's trace (the "main" thread's `at ...` lines, as
     * `StackTraceElement.toString()` writes them), top first.
     */
    fun anrFrames(trace: String): List<String> {
        val frames = ArrayList<String>()
        var inMain = false
        for (raw in trace.lineSequence()) {
            val line = raw.trim()
            if (!inMain) {
                if (raw.startsWith("\"main\"")) inMain = true
                continue
            }
            if (line.isEmpty()) break // the next thread
            if (line.startsWith("at ")) {
                frames += line.removePrefix("at ")
                if (frames.size == MAX_FRAMES) break
            }
        }
        return frames
    }

    /** The attributes and payload of an ANR's `app_hung` event. */
    fun anrEvent(description: String?, trace: String?): Pair<Map<String, Any?>, Map<String, Any?>> {
        val attrs = LinkedHashMap<String, Any?>()
        attrs["mechanism"] = "anr"
        description?.takeIf { it.isNotBlank() }?.let { attrs["description"] = it.take(300) }
        val payload = LinkedHashMap<String, Any?>()
        val frames = trace?.let { anrFrames(it) }.orEmpty()
        if (frames.isNotEmpty()) payload["frames"] = frames
        return attrs to payload
    }

    /** One frame of a native crash's backtrace. */
    data class NativeFrame(val relPc: Long, val function: String?, val functionOffset: Long, val file: String?, val buildId: String?) {
        val binary: String get() = file?.substringAfterLast('/')?.ifEmpty { null } ?: "?"

        /** "libgame.so +0x1a2b (Game::step+24)": like the Swift SDK's frames, and the function when known. */
        val text: String
            get() = "$binary +0x${java.lang.Long.toHexString(relPc)}" + (function?.let { " ($it+$functionOffset)" } ?: "")

        /** The app's own code: its libraries and compiled Java, under /data/app. */
        val inApp: Boolean get() = file?.startsWith("/data/app/") == true
    }

    /** What a tombstone says about a native crash. */
    data class Tombstone(
        val signal: Int,
        val signalName: String?,
        val codeName: String?,
        val faultAddress: Long?,
        val abortMessage: String?,
        val frames: List<NativeFrame>,
    )

    /**
     * A tombstone as Android 12+ gives it (protobuf, AOSP's system/core/debuggerd/proto/
     * tombstone.proto): the signal and the crashed thread's backtrace. null when it isn't one.
     */
    fun tombstone(bytes: ByteArray): Tombstone? {
        return try {
            readTombstone(Proto(bytes))
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun readTombstone(p: Proto): Tombstone? {
        var tid = -1L
        var signal = 0
        var signalName: String? = null
        var codeName: String? = null
        var fault: Long? = null
        var abort: String? = null
        val threads = HashMap<Long, List<NativeFrame>>()
        while (p.more()) {
            val (field, type) = p.tag()
            when {
                field == 6 && type == 0 -> tid = p.varint()
                field == 10 && type == 2 -> {
                    val s = Proto(p.bytes())
                    var hasFault = false
                    var address = 0L
                    while (s.more()) {
                        val (f, t) = s.tag()
                        when {
                            f == 1 && t == 0 -> signal = s.varint().toInt()
                            f == 2 && t == 2 -> signalName = s.string()
                            f == 4 && t == 2 -> codeName = s.string()
                            f == 8 && t == 0 -> hasFault = s.varint() != 0L
                            f == 9 && t == 0 -> address = s.varint()
                            else -> s.skip(t)
                        }
                    }
                    if (hasFault) fault = address
                }
                field == 14 && type == 2 -> abort = p.string()
                field == 16 && type == 2 -> { // map<uint32, Thread>: an entry {1: key, 2: Thread}
                    val entry = Proto(p.bytes())
                    var key = -1L
                    var frames: List<NativeFrame> = emptyList()
                    while (entry.more()) {
                        val (f, t) = entry.tag()
                        when {
                            f == 1 && t == 0 -> key = entry.varint()
                            f == 2 && t == 2 -> frames = readThreadFrames(Proto(entry.bytes()))
                            else -> entry.skip(t)
                        }
                    }
                    threads[key] = frames
                }
                else -> p.skip(type)
            }
        }
        if (signal == 0 && abort == null) return null
        val frames = threads[tid] ?: threads.values.firstOrNull().orEmpty()
        return Tombstone(signal, signalName, codeName, fault, abort, frames.take(MAX_FRAMES))
    }

    private fun readThreadFrames(p: Proto): List<NativeFrame> {
        val frames = ArrayList<NativeFrame>()
        while (p.more()) {
            val (f, t) = p.tag()
            if (f == 4 && t == 2) frames += readFrame(Proto(p.bytes())) else p.skip(t)
        }
        return frames
    }

    private fun readFrame(p: Proto): NativeFrame {
        var relPc = 0L
        var function: String? = null
        var offset = 0L
        var file: String? = null
        var buildId: String? = null
        while (p.more()) {
            val (f, t) = p.tag()
            when {
                f == 1 && t == 0 -> relPc = p.varint()
                f == 4 && t == 2 -> function = p.string().ifEmpty { null }
                f == 5 && t == 0 -> offset = p.varint()
                f == 6 && t == 2 -> file = p.string().ifEmpty { null }
                f == 8 && t == 2 -> buildId = p.string().ifEmpty { null }
                else -> p.skip(t)
            }
        }
        return NativeFrame(relPc, function, offset, file, buildId)
    }

    /**
     * The attributes and payload of a native crash's `app_crashed` event. `native_function` is
     * the app's own frame nearest the top (a function in its libraries or compiled Java), which
     * the server groups by, as it groups other crashes by their function.
     */
    fun nativeCrashEvent(description: String?, tombstone: Tombstone?): Pair<Map<String, Any?>, Map<String, Any?>> {
        val attrs = LinkedHashMap<String, Any?>()
        attrs["mechanism"] = "native"
        attrs["handled"] = false
        tombstone?.signalName?.let { attrs["signal"] = it }
        tombstone?.codeName?.let { attrs["signal_code"] = it }
        tombstone?.abortMessage?.takeIf { it.isNotBlank() }?.let { attrs["error_message"] = it.take(500) }
        description?.takeIf { it.isNotBlank() }?.let { attrs["description"] = it.take(300) }
        val frames = tombstone?.frames.orEmpty()
        (frames.firstOrNull { it.inApp && it.function != null } ?: frames.firstOrNull { it.function != null })?.let {
            attrs["native_function"] = it.function
            attrs["top_frame"] = it.text
        }
        val payload = LinkedHashMap<String, Any?>()
        if (frames.isNotEmpty()) payload["frames"] = frames.map { it.text }
        val builds = LinkedHashMap<String, Any?>()
        for (frame in frames) {
            val id = frame.buildId ?: continue
            builds.putIfAbsent(frame.binary, id)
        }
        if (builds.isNotEmpty()) payload["build_ids"] = builds
        tombstone?.faultAddress?.let { payload["fault_address"] = "0x" + java.lang.Long.toHexString(it) }
        return attrs to payload
    }

    /** A minimal protobuf reader: what a tombstone needs (varints, length-delimited, fixed). */
    private class Proto(private val data: ByteArray) {
        private var at = 0

        fun more() = at < data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = data[at++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("varint too long")
        }

        fun tag(): Pair<Int, Int> {
            val key = varint()
            return (key ushr 3).toInt() to (key and 7).toInt()
        }

        fun bytes(): ByteArray {
            val length = varint()
            if (length < 0 || length > data.size - at) throw IllegalStateException("length past the end")
            val out = data.copyOfRange(at, at + length.toInt())
            at += length.toInt()
            return out
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        fun skip(type: Int) {
            when (type) {
                0 -> varint()
                1 -> at += 8
                2 -> bytes()
                5 -> at += 4
                else -> throw IllegalStateException("wire type $type")
            }
            if (at > data.size) throw IllegalStateException("past the end")
        }
    }
}
