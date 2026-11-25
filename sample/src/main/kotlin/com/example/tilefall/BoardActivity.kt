package com.example.tilefall

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lyrelog.Lyrelog

/** The board: drop tiles, fail gracefully, or crash on purpose. */
class BoardActivity : Activity() {
    private val game = Lyrelog.logger("game")
    private var tiles = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val score = TextView(this).apply {
            textSize = 24f
            text = getString(R.string.app_name)
            contentDescription = "score"
        }
        setContentView(column(
            score,
            button("Drop tile") {
                tiles += 1
                score.text = "Tiles: $tiles"
                game.info("tile_dropped", mapOf("tiles" to tiles, "column" to tiles % 7))
            },
            button("Undo (handled error)") {
                try {
                    check(tiles > 0) { "nothing to undo" }
                    tiles -= 1
                } catch (e: IllegalStateException) {
                    game.captureError(e, mapOf("tiles" to tiles))
                }
            },
            button("Crash") {
                game.warning("crash_requested")
                throw IllegalStateException("tile fell off the board")
            },
            // Android reports these itself, on the next launch (ApplicationExitInfo, Android 11+).
            button("Freeze (ANR)") {
                game.warning("freeze_requested")
                Thread.sleep(20_000) // the main thread stops answering: touch the screen and Android calls it an ANR
            },
            button("Native crash") {
                game.warning("native_crash_requested")
                writeToAddressZero()
            },
            button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ))
    }

    override fun onStart() {
        super.onStart()
        // A debug build talking to a local Lyrelog (http://10.0.2.2:8090) needs Android 17's
        // local network permission; the permission is declared only in the debug manifest.
        val local = BuildConfig.LYRELOG_ENDPOINT.startsWith("http://")
        if (BuildConfig.DEBUG && local && Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(LOCAL_NETWORK), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Lyrelog.screen("Board")
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun column(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Edge to edge (targetSdk 35+): keep the buttons clear of the status and navigation bars.
        fitsSystemWindows = true
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(48, 96, 48, 48)
        views.forEach { addView(it) }
    }

    /** A real SIGSEGV outside the JVM, without native code of our own: a write to address 0. */
    private fun writeToAddressZero() {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(field.get(null), 0L, 0L)
    }

    private companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
