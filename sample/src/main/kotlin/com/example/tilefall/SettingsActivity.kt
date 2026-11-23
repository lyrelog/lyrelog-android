package com.example.tilefall

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lyrelog.Consent
import com.lyrelog.Lyrelog

/** Settings: the diagnostics choice, applied at once. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Send diagnostics" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(48, 96, 48, 48)
            addView(status)
            for ((label, consent) in listOf("Allow" to Consent.GRANTED, "Ask later" to Consent.PENDING, "Don't send" to Consent.DENIED)) {
                addView(Button(this@SettingsActivity).apply {
                    text = label
                    setOnClickListener {
                        Lyrelog.setConsent(consent)
                        status.text = "Send diagnostics: $label"
                    }
                })
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Lyrelog.screen("Settings")
    }
}
