package com.lyrelog.jvm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * [Lyrelog.userScope] for coroutines: the user of everything the coroutine logs, on whichever
 * thread it runs, until it ends. Wins over [Lyrelog.setUser]; an event's own `user` wins over it.
 *
 * ```kotlin
 * withContext(LyrelogUser(call.principal<User>()?.id)) {
 *     handle(call)   // every event logged here, after any suspension, carries the user
 * }
 * ```
 *
 * Needs kotlinx-coroutines (1.6 or later), which Lyrelog doesn't bring: an application with
 * coroutines has it already. Nothing else in Lyrelog loads it.
 */
class LyrelogUser(val id: String?) : ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LyrelogUser>

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = Lyrelog.requestUser.get()
        set(id?.ifEmpty { null })
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = set(oldState)

    private fun set(value: String?) {
        if (value == null) Lyrelog.requestUser.remove() else Lyrelog.requestUser.set(value)
    }

    override fun toString() = "LyrelogUser"
}
