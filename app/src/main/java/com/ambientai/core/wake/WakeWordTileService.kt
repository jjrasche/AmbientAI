package com.ambientai.core.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService

/**
 * Quick Settings tile to toggle wake word detection on/off.
 * Service stays alive - tile only pauses/resumes detection.
 */
class WakeWordTileService : TileService() {

    private var boundService: VoiceListeningService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceListeningService.LocalBinder
            boundService = binder.getService()
            isBound = true
            Log.d(TAG, "Service bound")
            updateTile()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        private const val TAG = "WakeWordTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")

        // Try to bind to service if it's running
        if (VoiceListeningService.isServiceRunning()) {
            tryBindService()
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "Tile stopped listening")
        tryUnbindService()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")

        val isRunning = VoiceListeningService.isServiceRunning()
        val isDetecting = VoiceListeningService.isDetectionActive()

        if (!isRunning) {
            // Service not running - start it (this will work from tile)
            startService()
            return
        }

        // Service is running - toggle detection state
        if (isDetecting) {
            pauseDetection()
        } else {
            resumeDetection()
        }

        // Update tile immediately
        updateTile()
    }

    private fun startService() {
        try {
            val intent = Intent(this, VoiceListeningService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Started VoiceListeningService")

            // Bind to newly started service
            tryBindService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun pauseDetection() {
        try {
            if (isBound && boundService != null) {
                boundService?.pauseDetection()
                Log.d(TAG, "Paused detection via bound service")
            } else {
                // Fallback: send intent
                val intent = Intent(this, VoiceListeningService::class.java).apply {
                    action = VoiceListeningService.ACTION_PAUSE_DETECTION
                }
                startService(intent)
                Log.d(TAG, "Paused detection via intent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause detection", e)
        }
    }

    private fun resumeDetection() {
        try {
            if (isBound && boundService != null) {
                boundService?.resumeDetection()
                Log.d(TAG, "Resumed detection via bound service")
            } else {
                // Fallback: send intent
                val intent = Intent(this, VoiceListeningService::class.java).apply {
                    action = VoiceListeningService.ACTION_RESUME_DETECTION
                }
                startService(intent)
                Log.d(TAG, "Resumed detection via intent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume detection", e)
        }
    }

    private fun tryBindService() {
        if (!isBound) {
            try {
                val intent = Intent(this, VoiceListeningService::class.java)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "Binding to service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service", e)
            }
        }
    }

    private fun tryUnbindService() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
                isBound = false
                boundService = null
                Log.d(TAG, "Unbound from service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service", e)
            }
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            val isRunning = VoiceListeningService.isServiceRunning()
            val isDetecting = VoiceListeningService.isDetectionActive()

            state = if (isRunning && isDetecting) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }

            label = "Wake Word"

            contentDescription = when {
                !isRunning -> "Wake word service inactive"
                isDetecting -> "Wake word detection active - tap to pause"
                else -> "Wake word detection paused - tap to resume"
            }

            updateTile()
            Log.d(TAG, "Tile updated: running=$isRunning, detecting=$isDetecting, state=${if (state == Tile.STATE_ACTIVE) "ACTIVE" else "INACTIVE"}")
        }
    }

    override fun onDestroy() {
        tryUnbindService()
        super.onDestroy()
    }
}