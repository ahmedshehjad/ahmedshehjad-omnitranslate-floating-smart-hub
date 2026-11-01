package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

@Composable
fun AdMobBannerView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        factory = { context: Context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                // Use official Google AdMob test banner ID in debug/emulator to avoid invalid traffic or filter errors
                adUnitId = if (BuildConfig.DEBUG) {
                    "ca-app-pub-3940256099942544/6300978111"
                } else {
                    "ca-app-pub-3307359221672630/6203128375"
                }
                adListener = object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        // Handled gracefully without crashing or throwing
                    }
                }
                try {
                    loadAd(AdRequest.Builder().build())
                } catch (_: Exception) {}
            }
        }
    )
}
