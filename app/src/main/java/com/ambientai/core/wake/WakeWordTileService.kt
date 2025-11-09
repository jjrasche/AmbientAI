package com.ambientai.core.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService

class WakeWordTileService : TileService() {
    private var boundService: VoiceListeningService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = (service as VoiceListeningService.LocalBinder).getService()
            isBound = true
            updateTile()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (VoiceListeningService.isServiceRunning()) tryBindService()
        updateTile()
    }
    override fun onStopListening() {
        super.onStopListening()
        tryUnbindService()
    }
    override fun onClick() {
        super.onClick()
        if (!VoiceListeningService.isServiceRunning()) {
            startService()
            return
        }
        if (VoiceListeningService.isDetectionActive()) pauseDetection() else resumeDetection()
        updateTile()
    }

    private fun startService() = try {
        ContextCompat.startForegroundService(this, Intent(this, VoiceListeningService::class.java))
        tryBindService()
    } catch (e: Exception) {}
    private fun pauseDetection() = try {
        if (isBound && boundService != null) boundService?.pauseDetection()
        else startService(Intent(this, VoiceListeningService::class.java).apply { action = VoiceListeningService.ACTION_PAUSE_DETECTION })
    } catch (e: Exception) {}
    private fun resumeDetection() = try {
        if (isBound && boundService != null) boundService?.resumeDetection()
        else startService(Intent(this, VoiceListeningService::class.java).apply { action = VoiceListeningService.ACTION_RESUME_DETECTION })
    } catch (e: Exception) {}
    private fun tryBindService() {
        if (!isBound) try { bindService(Intent(this, VoiceListeningService::class.java), serviceConnection, Context.BIND_AUTO_CREATE) } catch (e: Exception) {}
    }
    private fun tryUnbindService() {
        if (isBound) try { unbindService(serviceConnection); isBound = false; boundService = null } catch (e: Exception) {}
    }

    private fun updateTile() = qsTile?.apply {
        state = if (VoiceListeningService.isServiceRunning() && VoiceListeningService.isDetectionActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        label = "Wake Word"
        contentDescription = when {
            !VoiceListeningService.isServiceRunning() -> "Wake word service inactive"
            VoiceListeningService.isDetectionActive() -> "Wake word detection active - tap to pause"
            else -> "Wake word detection paused - tap to resume"
        }
        updateTile()
    } ?: Unit
    override fun onDestroy() {
        tryUnbindService()
        super.onDestroy()
    }
}