package com.lyrelog.core

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The `exception` event every Lyrelog SDK sends (docs/sdk-spec.md, "Event names"): attributes
 * `error_type` (the class name) and `error_message`, and a JVM payload: the description, the
 * frames, the causes and the suppressed exceptions, each bounded so the payload stays far
 * below the 64 KiB limit (beyond it the server would only get a 1 KiB preview).
 */
@InternalLyrelogApi
object Exceptions {
    const val MAX_FRAMES = 60
    const val MAX_CAUSE_FRAMES = 20
    const val MAX_CAUSES = 8
    const val MAX_SUPPRESSED = 4
    /** Each frame or message is cut to this many bytes. */
    const val MAX_TEXT_BYTES = 1024

    fun attributes(error: Throwable): Map<String, JsonValue> = linkedMapOf(
        "error_type" to JsonValue.Str(error.javaClass.name),
        "error_message" to JsonValue.Str(safeMessage(error)),
    )

    fun payload(error: Throwable, thread: Thread? = null): JsonValue {
        val out = LinkedHashMap<String, JsonValue>()
        out["description"] = JsonValue.Str(Shaping.truncated(safeDescription(error), MAX_TEXT_BYTES))
        thread?.let { out["thread"] = JsonValue.Str(Shaping.truncated(it.name, 256)) }
        putFrames(out, error, MAX_FRAMES)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        seen.add(error)
        val causes = ArrayList<JsonValue>()
        var cause = error.cause
        while (cause != null && cause !in seen && causes.size < MAX_CAUSES) {
            seen.add(cause)
            causes.add(described(cause, MAX_CAUSE_FRAMES))
            cause = cause.cause
        }
        if (causes.isNotEmpty()) out["causes"] = JsonValue.Arr(causes)
        val suppressed = try {
            error.suppressed.toList()
        } catch (e: Exception) {
            emptyList()
        }
        if (suppressed.isNotEmpty()) {
            out["suppressed"] = JsonValue.Arr(suppressed.take(MAX_SUPPRESSED).map { described(it, MAX_CAUSE_FRAMES) })
        }
        return JsonValue.Obj(out)
    }

    private fun described(error: Throwable, maxFrames: Int): JsonValue {
        val out = LinkedHashMap<String, JsonValue>()
        out["type"] = JsonValue.Str(error.javaClass.name)
        out["message"] = JsonValue.Str(Shaping.truncated(safeMessage(error), MAX_TEXT_BYTES))
        putFrames(out, error, maxFrames)
        return JsonValue.Obj(out)
    }

    private fun putFrames(out: MutableMap<String, JsonValue>, error: Throwable, max: Int) {
        val frames = try {
            error.stackTrace
        } catch (e: Exception) {
            emptyArray()
        }
        out["frames"] = JsonValue.Arr(frames.take(max).map { JsonValue.Str(Shaping.truncated(it.toString(), MAX_TEXT_BYTES)) })
        if (frames.size > max) out["frames_omitted"] = JsonValue.Integer((frames.size - max).toLong())
    }

    /** `getMessage()` can be overridden and throw; the event is kept anyway. */
    private fun safeMessage(error: Throwable): String = try {
        error.message ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun safeDescription(error: Throwable): String = try {
        error.toString()
    } catch (e: Exception) {
        error.javaClass.name
    }
}

/**
 * Records an uncaught exception to disk synchronously on the dying thread (no network: the
 * process is about to end), then hands it to the handler that was there before (on Android the
 * system's, which shows the crash dialog and kills the process). The event goes on the next
 * launch. Installed once per process; a later `start` only changes the engine it records into.
 */
@InternalLyrelogApi
object CrashRecorder {
    @Volatile
    private var target: Engine? = null
    private var installed = false

    @Synchronized
    fun install(engine: Engine) {
        target = engine
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler(handler(Thread.getDefaultUncaughtExceptionHandler()))
    }

    fun detach(engine: Engine) {
        if (target === engine) target = null
    }

    /** The handler, chaining [previous] (tests call it directly). */
    fun handler(previous: Thread.UncaughtExceptionHandler?, engine: () -> Engine? = { target }) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                engine()?.recordUncaught(thread, error)
            } catch (e: Throwable) {
                // recording must never hide the crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                System.err.print("Exception in thread \"${thread.name}\" ")
                error.printStackTrace()
            }
        }
}
