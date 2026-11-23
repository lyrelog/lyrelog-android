package com.lyrelog.core

/**
 * The SDK's own diagnostics (a dropped batch, a wrong endpoint, the monthly quota), never the
 * app's events and never the API key. On Android they go to logcat under the tag `Lyrelog`
 * (debug level; the quota warning at warning level). [once] says a thing once per launch.
 */
@InternalLyrelogApi
class SdkLog(private val sink: (warning: Boolean, message: String) -> Unit) {
    private val said = HashSet<String>()
    private val recentLines = ArrayDeque<String>()

    fun once(key: String, message: String) {
        val first = synchronized(said) { said.add(key) }
        if (first) say(message)
    }

    fun say(message: String) = emit(false, message)

    fun warn(message: String) = emit(true, message)

    private fun emit(warning: Boolean, message: String) {
        synchronized(recentLines) {
            recentLines.addLast(message)
            if (recentLines.size > 50) recentLines.removeFirst()
        }
        try {
            sink(warning, message)
        } catch (e: Exception) {
            // a log sink must never break the SDK
        }
    }

    /** What was said lately (tests). */
    val recent: List<String> get() = synchronized(recentLines) { recentLines.toList() }

    companion object {
        /** Plain JVM: standard error. */
        fun stderr() = SdkLog { _, message -> System.err.println(message) }
        fun silent() = SdkLog { _, _ -> }
    }
}
