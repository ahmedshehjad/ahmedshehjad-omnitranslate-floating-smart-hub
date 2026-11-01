package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

class OmniTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Room DB
        AppDatabase.getInstance(this)

        // Configure test device IDs (Emulator + Logcat Device ID)
        try {
            val testDeviceIds = listOf(
                AdRequest.DEVICE_ID_EMULATOR,
                "46043d5e-bd7b-421e-9b16-9e6be6f28d7a"
            )
            val configuration = RequestConfiguration.Builder()
                .setTestDeviceIds(testDeviceIds)
                .build()
            MobileAds.setRequestConfiguration(configuration)

            // Initialize MobileAds SDK
            MobileAds.initialize(this) {}
        } catch (_: Exception) {}
    }
}
