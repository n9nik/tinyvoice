package com.n9nik.voicerecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.MobileAds
import com.n9nik.voicerecorder.ads.ConsentManager
import com.n9nik.voicerecorder.ui.VoiceApp
import com.n9nik.voicerecorder.ui.theme.UtilityTheme
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var consentManager: ConsentManager
    private val adsInitStarted = AtomicBoolean(false)
    private var adsReady by mutableStateOf(false)
    private var privacyOptionsAvailable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentManager = ConsentManager(this)

        setContent {
            UtilityTheme {
                VoiceApp(
                    adsReady = adsReady,
                    privacyOptionsAvailable = privacyOptionsAvailable,
                    onPrivacyOptions = {
                        consentManager.showPrivacyOptions(this) {
                            startAdsIfAllowed()
                        }
                    }
                )
            }
        }

        consentManager.gatherConsent(this) {
            privacyOptionsAvailable = consentManager.isPrivacyOptionsRequired
            startAdsIfAllowed()
        }
    }

    private fun startAdsIfAllowed() {
        if (!consentManager.canRequestAds || !adsInitStarted.compareAndSet(false, true)) return
        Thread {
            MobileAds.initialize(this) {
                runOnUiThread { adsReady = true }
            }
        }.start()
    }
}
