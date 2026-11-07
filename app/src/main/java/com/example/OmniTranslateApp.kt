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
            // Only the official emulator test ID is configured here.
            // Add a physical device only after obtaining its AdMob test-device ID from Logcat.
            val testDeviceIds = listOf(AdRequest.DEVICE_ID_EMULATOR)
            val configuration = RequestConfiguration.Builder()
                .setTestDeviceIds(testDeviceIds)
                .build()
            MobileAds.setRequestConfiguration(configuration)

            // Initialize MobileAds SDK
            MobileAds.initialize(this) {}
        } catch (_: Exception) {}
    }
}
