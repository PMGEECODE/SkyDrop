package com.famage.skydrop_tv_app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.famage.skydrop_tv_app.settings.TvSettingsStore

/**
 * Ensures SkyDrop TV background receiver starts automatically when Android TV boots up.
 */
class TvBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("TvBootReceiver", "BOOT_COMPLETED received - checking if TV receiver should start")
            val settings = TvSettingsStore.getInstance(context)
            if (settings.isReceiverActive.value) {
                TvReceiverService.startService(context)
            }
        }
    }
}
