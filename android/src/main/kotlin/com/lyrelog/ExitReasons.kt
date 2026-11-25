package com.lyrelog

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.lyrelog.core.Engine
import com.lyrelog.core.ExitReports
import java.io.File

/**
 * ANRs and native (C/C++) crashes of the app's earlier processes, from what Android recorded
 * (ApplicationExitInfo, Android 11+; a native crash's frames from Android 12+), sent on the next
 * launch at the time they happened (2026-11-25). The app's own process can't catch either: an ANR
 * is the system ending a frozen app, a native crash happens outside the JVM. JVM crashes come from
 * [com.lyrelog.core.CrashRecorder] already, so they're skipped here.
 *
 * Each exit is sent once: [marker] keeps the time of the newest one read. Exits older than 7 days
 * are left out, as the Swift SDK leaves out MetricKit's.
 */
internal object ExitReasons {
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun report(app: Context, engine: Engine, marker: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val thread = Thread({ readAndSend(app, engine, marker) }, "lyrelog-exits")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun readAndSend(app: Context, engine: Engine, marker: File) {
        try {
            val manager = app.getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            val seen = try {
                marker.readText().trim().toLong()
            } catch (e: Exception) {
                0L
            }
            val now = System.currentTimeMillis()
            var newest = seen
            for (exit in exits.sortedBy { it.timestamp }) {
                if (exit.timestamp <= seen) continue
                newest = maxOf(newest, exit.timestamp)
                if (now - exit.timestamp > MAX_AGE_MILLIS) continue
                when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> {
                        val (attrs, payload) = ExitReports.anrEvent(exit.description, trace(exit)?.toString(Charsets.UTF_8))
                        engine.log(Level.ERROR, "app_hung", "diagnostics", attrs, payload, timestampMillis = exit.timestamp)
                    }
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                        // From Android 12 the trace is the tombstone, as protobuf; before, nothing.
                        val tombstone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) trace(exit)?.let { ExitReports.tombstone(it) } else null
                        val (attrs, payload) = ExitReports.nativeCrashEvent(exit.description, tombstone)
                        engine.log(Level.CRITICAL, "app_crashed", "diagnostics", attrs, payload, timestampMillis = exit.timestamp)
                    }
                }
            }
            if (newest > seen) {
                marker.parentFile?.mkdirs()
                marker.writeText(newest.toString())
            }
        } catch (e: Exception) {
            // Never the app's problem: these reports are a bonus.
        }
    }

    /** The exit's trace, at most 4 MiB. */
    @TargetApi(Build.VERSION_CODES.R)
    private fun trace(exit: ApplicationExitInfo): ByteArray? = try {
        exit.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < 4 * 1024 * 1024) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }
}
