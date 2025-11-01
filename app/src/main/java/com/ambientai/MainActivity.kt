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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService
import com.ambientai.core.llm.GeminiNanoTester
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.TranscriptRepository
import kotlinx.coroutines.launch
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
    private var showNanoTest by mutableStateOf(false)

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
                    if (showNanoTest) {
                        NanoTestScreen(onBack = { showNanoTest = false })
                    } else {
                        DebugScreen(
                            currentTranscript = currentTranscript,
                            transcripts = transcripts,
                            onTestNano = { showNanoTest = true },
                            onToggleExcludeFromContext = { transcript ->
                                toggleExcludeFromContext(transcript)
                            }
                        )
                    }
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

    private fun toggleExcludeFromContext(transcript: Transcript) {
        transcript.excludeFromContext = !transcript.excludeFromContext
        transcriptRepository?.update(transcript)
        loadTranscripts()
    }

    @Composable
    fun DebugScreen(
        currentTranscript: String,
        transcripts: List<Transcript>,
        onTestNano: () -> Unit,
        onToggleExcludeFromContext: (Transcript) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ambient AI Debug",
                    style = MaterialTheme.typography.headlineMedium
                )

                Button(onClick = onTestNano) {
                    Text("Test Nano")
                }
            }

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
                text = "Recent Transcripts (${transcripts.size}) - Long press to exclude from context",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcripts) { transcript ->
                    TranscriptItem(
                        transcript = transcript,
                        onToggleExcludeFromContext = onToggleExcludeFromContext
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun TranscriptItem(
        transcript: Transcript,
        onToggleExcludeFromContext: (Transcript) -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = { onToggleExcludeFromContext(transcript) }
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (transcript.excludeFromContext) {
                    Color(0xFFFFEBEE) // Light red tint for excluded
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = dateFormat.format(Date(transcript.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (transcript.excludeFromContext) {
                        Text(
                            text = "Excluded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transcript.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    @Composable
    fun NanoTestScreen(onBack: () -> Unit) {
        var isRunning by remember { mutableStateOf(false) }
        var currentProgress by remember { mutableStateOf("") }
        var testResults by remember { mutableStateOf<List<GeminiNanoTester.TestResult>>(emptyList()) }

        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Nano Tests",
                    style = MaterialTheme.typography.headlineMedium
                )

                Button(onClick = onBack) {
                    Text("Back")
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        isRunning = true
                        testResults = emptyList()
                        currentProgress = "Starting tests..."

                        val tester = GeminiNanoTester(applicationContext)
                        val results = tester.runAllTests { progress ->
                            currentProgress = progress
                        }

                        testResults = results
                        isRunning = false
                        currentProgress = "Tests complete"
                    }
                },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(if (isRunning) "Running..." else "Run All Tests")
            }

            if (currentProgress.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = currentProgress,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(testResults) { result ->
                    TestResultCard(result = result)
                }
            }
        }
    }

    @Composable
    fun TestResultCard(result: GeminiNanoTester.TestResult) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (result.success) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = result.testName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (result.success) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    Text(
                        text = if (result.success) "✓" else "✗",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (result.success) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.success) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }
    }
}