package com.lyrelog

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Going to the background is the last reliable moment to send: Android may freeze or kill the
 * process any time after. When the last started Activity stops (not for a rotation), periodic
 * uploads pause and one upload runs on Lyrelog's own thread; they resume when an Activity starts.
 * With `automaticScreens`, each Activity that resumes logs `screen_viewed` with its class name.
 *
 * Periodic uploads start paused unless an Activity is already in front ([inFront]): a process
 * started for a push, a job or a broadcast has none, and must not upload every 15 s. The tracker
 * keeps the Activities it saw start; when `start` ran late, with Activities already open, the
 * stop of one it never saw start counts only when nothing it saw start is still started, so
 * opening a second screen doesn't send the app to the background.
 *
 * Plain `Application.ActivityLifecycleCallbacks`, so no AndroidX dependency.
 */
internal class ActivityTracker(
    private val setForeground: (Boolean) -> Unit,
    private val flush: () -> Unit,
    private val screen: ((String) -> Unit)?,
) : Application.ActivityLifecycleCallbacks {
    // Only touched on the main thread, where the callbacks run.
    private val started: MutableSet<Activity> = Collections.newSetFromMap(IdentityHashMap())

    override fun onActivityStarted(activity: Activity) {
        val first = started.isEmpty()
        started.add(activity)
        if (first) setForeground(true)
    }

    override fun onActivityStopped(activity: Activity) {
        val known = started.remove(activity)
        // One started before Lyrelog: the background only if nothing seen starting is still in front.
        if (!known && started.isNotEmpty()) return
        if (started.isEmpty() && !activity.isChangingConfigurations) {
            setForeground(false)
            flush()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        screen?.invoke(activity.javaClass.simpleName)
    }

    override fun onActivityDestroyed(activity: Activity) {
        // Destroyed without its stop reported (rare): it mustn't hold the foreground.
        started.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    companion object {
        /**
         * Whether one of this process's Activities is in front now (`start` called late, or a
         * launch the system already put on top). A binder call, no disk.
         */
        fun inFront(): Boolean = try {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (e: Throwable) {
            false
        }
    }
}
