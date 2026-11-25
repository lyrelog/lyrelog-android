package com.example.tilefall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: an ANR in the background, which Android ends without a dialog and records
 * (ApplicationExitInfo REASON_ANR), for trying the SDK's ANR reports:
 * `adb shell am broadcast -n com.example.tilefall/.FreezeReceiver --receiver-foreground`.
 */
class FreezeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Thread.sleep(30_000) // longer than a foreground broadcast's 10 s
    }
}
