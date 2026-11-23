package com.lyrelog

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

/** When periodic uploads run, from what the Activities do. */
class LifecycleTest {
    private val calls = ArrayList<String>()
    private val tracker = ActivityTracker({ calls += if (it) "foreground" else "background" }, { calls += "flush" }, null)

    private class Rotating : Activity() {
        override fun isChangingConfigurations() = true
    }

    @Test
    fun theLastStopSendsTheAppToTheBackground() {
        val board = Activity()
        val settings = Activity()
        tracker.onActivityStarted(board)
        tracker.onActivityStarted(settings)
        tracker.onActivityStopped(board)
        assertEquals(listOf("foreground"), calls)
        tracker.onActivityStopped(settings)
        assertEquals(listOf("foreground", "background", "flush"), calls)
    }

    @Test
    fun aRotationIsNotTheBackground() {
        val before = Rotating()
        tracker.onActivityStarted(before)
        tracker.onActivityStopped(before)
        tracker.onActivityStarted(Activity())
        assertEquals(listOf("foreground", "foreground"), calls)
    }

    /**
     * `start` ran with the board already open (a late start): opening settings, then the board
     * stopping under it, is not the background. Before, the count went 1 then 0 here.
     */
    @Test
    fun aLateStartWithAnActivityOpenCountsRight() {
        val board = Activity() // started before Lyrelog: never seen
        val settings = Activity()
        tracker.onActivityStarted(settings)
        tracker.onActivityStopped(board)
        assertEquals(listOf("foreground"), calls)

        // Back to the board, then home.
        tracker.onActivityStarted(board)
        tracker.onActivityStopped(settings)
        assertEquals(listOf("foreground"), calls)
        tracker.onActivityStopped(board)
        assertEquals(listOf("foreground", "background", "flush"), calls)
    }

    @Test
    fun aLateStartAndStraightHomeIsTheBackground() {
        tracker.onActivityStopped(Activity()) // the only Activity, started before Lyrelog
        assertEquals(listOf("background", "flush"), calls)
    }

    @Test
    fun aDestroyedActivityDoesNotHoldTheForeground() {
        val lost = Activity()
        val board = Activity()
        tracker.onActivityStarted(lost)
        tracker.onActivityStarted(board)
        tracker.onActivityDestroyed(lost)
        tracker.onActivityStopped(board)
        assertEquals(listOf("foreground", "background", "flush"), calls)
    }

    /** In a unit test no Activity of this process is in front: like a process started for a push. */
    @Test
    fun noActivityInFrontMeansTheBackground() {
        assertEquals(false, ActivityTracker.inFront())
    }
}
