package com.famage.skydrop_tv_app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.famage.skydrop_tv_app.receiver.TvReceiverService
import com.famage.skydrop_tv_app.settings.TvSettingsStore
import com.famage.skydrop_tv_app.ui.components.TvSplashScreen
import com.famage.skydrop_tv_app.ui.screens.TvMainScreen
import com.famage.skydrop_tv_app.ui.theme.SkydroptvappTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from the starting splash theme to standard app theme
        setTheme(R.style.Theme_Skydroptvapp)
        super.onCreate(savedInstanceState)
        
        // Prevent Android TV from dimming, going into screensaver or sleep while SkyDrop is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Automatically start the background receiver service so TV is always ready to receive drops 24/7
        val settings = TvSettingsStore.getInstance(this)
        if (settings.isReceiverActive.value) {
            TvReceiverService.startService(this)
        }

        setContent {
            val themePreference by settings.themePreference.collectAsState()
            var showSplash by remember { mutableStateOf(true) }

            SkydroptvappTheme(themePreference = themePreference) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    Crossfade(
                        targetState = showSplash,
                        label = "SplashCrossfade"
                    ) { isSplash ->
                        if (isSplash) {
                            TvSplashScreen(
                                onSplashFinished = { showSplash = false }
                            )
                        } else {
                            TvMainScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvReceiverService.isAppInForeground = true
    }

    override fun onStop() {
        super.onStop()
        TvReceiverService.isAppInForeground = false
    }
}
