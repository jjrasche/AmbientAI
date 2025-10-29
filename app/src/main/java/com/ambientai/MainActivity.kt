package com.ambientai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.TranscriptRepository
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var voiceService: VoiceListeningService? = null
    private var isBound = false
    private var transcriptRepository: TranscriptRepository? = null

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceListeningService.LocalBinder
            voiceService = binder.getService()
            isBound = true
            voiceService?.registerListener(transcriptListener)
            Log.d(TAG, "Service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    private val transcriptListener = object : VoiceListeningService.TranscriptUpdateListener {
        override fun onPartialTranscript(text: String) {
            currentTranscript = text
        }

        override fun onTranscriptSaved(transcript: Transcript) {
            currentTranscript = ""
            loadTranscripts()
        }
    }

    private var currentTranscript by mutableStateOf("")
    private var transcripts by mutableStateOf<List<Transcript>>(emptyList())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true &&
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true) {
            startVoiceService()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transcriptRepository = TranscriptRepository(applicationContext)

        checkPermissionsAndStart()
        loadTranscripts()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DebugScreen(
                        currentTranscript = currentTranscript,
                        transcripts = transcripts
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, VoiceListeningService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            voiceService?.unregisterListener(transcriptListener)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transcriptRepository?.close()
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            requestPermissionLauncher.launch(permissions)
        } else {
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceListeningService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun loadTranscripts() {
        transcripts = transcriptRepository?.getRecent(20) ?: emptyList()
    }

    @Composable
    fun DebugScreen(
        currentTranscript: String,
        transcripts: List<Transcript>
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Ambient AI Debug",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Transcript",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentTranscript.ifEmpty { "Waiting for wake word..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "Recent Transcripts (${transcripts.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcripts) { transcript ->
                    TranscriptItem(transcript = transcript)
                }
            }
        }
    }

    @Composable
    fun TranscriptItem(transcript: Transcript) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = dateFormat.format(Date(transcript.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transcript.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}