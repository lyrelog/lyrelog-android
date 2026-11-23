package com.lyrelog.core

import java.io.File
import java.io.IOException
import java.util.UUID

/** Where the install id lives: a file on the JVM, SharedPreferences on Android. */
@InternalLyrelogApi
interface InstallIdStore {
    fun load(): String?
    fun save(id: String)
    fun clear()
}

/** A file holding the id (plain JVM, tests). */
@InternalLyrelogApi
class FileInstallIdStore(private val file: File) : InstallIdStore {
    override fun load(): String? = try {
        if (file.isFile) file.readText().trim() else null
    } catch (e: IOException) {
        null
    }

    override fun save(id: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(id)
        } catch (e: IOException) {
            // the id is then new each launch
        }
    }

    override fun clear() {
        file.delete()
    }
}

/** The install id, read or made on first use, and forgotten on a denial of consent. */
internal class InstallIdentity(private val store: InstallIdStore) {
    private var id: String? = null

    /** The id, or null while consent is denied (then nothing may be written to the device). */
    @Synchronized
    fun current(consent: ConsentGate): String? {
        if (consent.value == com.lyrelog.Consent.DENIED) return null
        id?.let { return it }
        val saved = try {
            store.load()?.takeIf { isUuid(it) }
        } catch (e: Exception) {
            null
        }
        val made = saved ?: UUID.randomUUID().toString().also {
            try {
                store.save(it)
            } catch (e: Exception) {
                // kept in memory for this launch
            }
        }
        id = made
        return made
    }

    @Synchronized
    fun forget() {
        id = null
        try {
            store.clear()
        } catch (e: Exception) {
            // nothing more to do
        }
    }

    private fun isUuid(text: String) = try {
        UUID.fromString(text).toString() == text.lowercase(java.util.Locale.ROOT)
    } catch (e: IllegalArgumentException) {
        false
    }
}

/** The current session: a new one starts after [timeoutMillis] without any event. */
internal class SessionClock(private val timeoutMillis: Long) {
    var id: String = UUID.randomUUID().toString()
        private set
    private var lastActivity: Long? = null

    @Synchronized
    fun touch(nowMillis: Long): String {
        val last = lastActivity
        if (last != null && nowMillis - last > timeoutMillis) id = UUID.randomUUID().toString()
        lastActivity = nowMillis
        return id
    }
}
