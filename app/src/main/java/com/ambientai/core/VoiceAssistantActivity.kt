package com.ambientai.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity

class VoiceAssistantActivity : ComponentActivity() {
    private var voiceService: VoiceListeningService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = (service as VoiceListeningService.LocalBinder).getService()
            isBound = true
            voiceService?.triggerWakeWordManually()
            finish()
        }
        override fun onServiceDisconnected(name: ComponentName?) { voiceService = null; isBound = false }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, VoiceListeningService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(serviceConnection); isBound = false }
    }
}
