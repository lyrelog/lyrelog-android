package com.lyrelog

import com.lyrelog.core.Engine
import com.lyrelog.core.InternalLyrelogApi

/**
 * A logger for one area of the app: every event it records carries [category], like Android's
 * log tags or OSLog's categories. Get one with `Lyrelog.logger("checkout")`. Cheap to make and
 * keep; it logs into whatever SDK is running when it is called (nothing before `start`).
 */
class Logger @InternalLyrelogApi constructor(val category: String, private val engine: () -> Engine?) {
    @JvmOverloads
    fun log(level: Level, message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) {
        engine()?.log(level, message, category, attrs, payload)
    }

    @JvmOverloads
    fun trace(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.TRACE, message, attrs, payload)
    @JvmOverloads
    fun debug(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.DEBUG, message, attrs, payload)
    @JvmOverloads
    fun info(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.INFO, message, attrs, payload)
    @JvmOverloads
    fun notice(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.NOTICE, message, attrs, payload)
    @JvmOverloads
    fun warning(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.WARNING, message, attrs, payload)
    @JvmOverloads
    fun error(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.ERROR, message, attrs, payload)
    @JvmOverloads
    fun critical(message: String, attrs: Map<String, Any?>? = null, payload: Any? = null) = log(Level.CRITICAL, message, attrs, payload)

    /** The `exception` event in this logger's category. */
    @JvmOverloads
    fun captureError(error: Throwable, attrs: Map<String, Any?>? = null, level: Level = Level.ERROR) {
        engine()?.captureError(error, category, attrs, level)
    }
}
