package com.ambientai.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts VoiceListeningService on device boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed - starting VoiceListeningService")

            val serviceIntent = Intent(context, VoiceListeningService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}