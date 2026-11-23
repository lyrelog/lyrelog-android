package com.lyrelog.jvm

import com.lyrelog.core.Engine
import com.lyrelog.core.InstallIdStore
import com.lyrelog.core.Names
import com.lyrelog.core.Resource
import com.lyrelog.core.SdkLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.jar.JarFile

/** What a server says about itself, read once at start. */
internal object ServerDefaults {
    const val SDK = "lyrelog-jvm/${Lyrelog.VERSION}"
    const val PLATFORM = "jvm"

    fun resource(
        configuration: Configuration,
        log: SdkLog,
        environmentVariable: String? = System.getenv("LYRELOG_ENVIRONMENT"),
        command: String? = System.getProperty("sun.java.command"),
    ): Resource {
        val started = StartedProgram.of(command)
        return Resource(
            appId = configuration.appId ?: started.title ?: started.name ?: "",
            appVersion = configuration.release ?: started.version ?: "",
            osName = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            sdk = SDK,
            environment = environment(configuration.environment, environmentVariable, log),
            platform = configuration.platform?.let(Names::clean) ?: PLATFORM,
        )
    }

    /** Configured (cleaned), else `$LYRELOG_ENVIRONMENT` if valid, else production. As in the Go and Python SDKs. */
    fun environment(configured: String?, variable: String?, log: SdkLog): String {
        configured?.let(Names::clean)?.let { return it }
        val fromEnv = variable?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (fromEnv.isEmpty()) return "production"
        if (Names.isValid(fromEnv)) return fromEnv
        log.once("environment", "lyrelog: LYRELOG_ENVIRONMENT must be lowercase letters, digits, - and _, up to 32; using production")
        return "production"
    }
}

/** The jar or class the JVM was started with (`sun.java.command`), for the default app id and release. */
internal class StartedProgram(val name: String?, val title: String?, val version: String?) {
    companion object {
        fun of(command: String?): StartedProgram = try {
            val first = command?.trim()?.split(' ')?.firstOrNull().orEmpty()
            when {
                first.isEmpty() -> StartedProgram(null, null, null)
                first.endsWith(".jar", ignoreCase = true) -> {
                    val file = File(first)
                    val attributes = if (file.isFile) JarFile(file).use { it.manifest?.mainAttributes } else null
                    StartedProgram(
                        file.name.substringBeforeLast('.'),
                        attributes?.getValue("Implementation-Title")?.takeIf { it.isNotBlank() },
                        attributes?.getValue("Implementation-Version")?.takeIf { it.isNotBlank() },
                    )
                }
                else -> {
                    val version = try {
                        Class.forName(first, false, Thread.currentThread().contextClassLoader)?.`package`?.implementationVersion
                    } catch (e: Throwable) {
                        null
                    }
                    StartedProgram(first, null, version)
                }
            }
        } catch (e: Exception) {
            StartedProgram(null, null, null)
        }
    }
}

/** A server's install is its process: a new id each start, never written anywhere (as in Go and Python). */
internal class ProcessInstallId : InstallIdStore {
    @Volatile
    private var id: String? = null
    override fun load(): String? = id
    override fun save(id: String) {
        this.id = id
    }
    override fun clear() {
        id = null
    }
}

/**
 * The queue's directory, held by this process alone: a configured one under a file lock (kept
 * across starts), or a temporary one deleted at close.
 */
internal class QueueDirectory private constructor(
    val directory: File,
    val temporary: Boolean,
    private val channel: FileChannel?,
    private val lock: FileLock?,
) {
    fun release() {
        try {
            lock?.release()
        } catch (e: IOException) {
            // closing the channel releases it too
        }
        try {
            channel?.close()
        } catch (e: IOException) {
            // nothing more to do
        }
        if (temporary) directory.deleteRecursively()
    }

    companion object {
        const val LOCK_FILE = ".lock"

        fun open(configured: File?, log: SdkLog): QueueDirectory {
            if (configured != null) {
                locked(configured)?.let { return it }
                log.once(
                    "storage-locked",
                    "lyrelog: the storage directory ${configured.path} is in use by another process (or can't be locked); this one queues in a temporary directory",
                )
            }
            val dir = Files.createTempDirectory("lyrelog-").toFile()
            return QueueDirectory(dir, true, null, null)
        }

        private fun locked(dir: File): QueueDirectory? {
            var channel: FileChannel? = null
            return try {
                dir.mkdirs()
                channel = RandomAccessFile(File(dir, LOCK_FILE), "rw").channel
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null // held by this JVM already
                }
                if (lock == null) {
                    channel.close()
                    null
                } else {
                    QueueDirectory(dir, false, channel, lock)
                }
            } catch (e: Exception) {
                try {
                    channel?.close()
                } catch (ignored: IOException) {
                    // nothing more to do
                }
                null
            }
        }
    }
}

/**
 * `captureUncaught`: an exception no code caught becomes an `exception` event with
 * `handled: false` and `mechanism: uncaught_exception` (docs/sdk-spec.md), then the previous
 * handler runs (or the JVM's usual printout). Installed once per process; the shutdown hook
 * sends it when that thread was the last one.
 */
internal object UncaughtRecorder {
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

    fun handler(previous: Thread.UncaughtExceptionHandler?, engine: () -> Engine? = { target }) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                engine()?.captureError(error, null, mapOf("handled" to false, "mechanism" to "uncaught_exception", "thread" to thread.name))
            } catch (e: Throwable) {
                // recording must never hide the exception
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                System.err.print("Exception in thread \"${thread.name}\" ")
                error.printStackTrace()
            }
        }
}
