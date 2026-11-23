package com.example.tilefall

import android.app.Application
import com.lyrelog.Configuration
import com.lyrelog.Lyrelog

class TilefallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lyrelog.start(
            this,
            Configuration(BuildConfig.LYRELOG_KEY) {
                endpoint = BuildConfig.LYRELOG_ENDPOINT
                mirrorToLogcat = BuildConfig.DEBUG
            },
        )
        Lyrelog.setUser("player-42")
        Lyrelog.info("app_started", category = "app", attrs = mapOf("cold" to true))
    }
}
