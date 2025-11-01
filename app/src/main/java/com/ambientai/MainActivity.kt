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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService
import com.ambientai.core.llm.GeminiNanoTester
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.LlmInteractionRepository
import com.ambientai.data.repositories.TranscriptRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed class TimelineItem {
    abstract val timestamp: Long

    data class TranscriptItem(val transcript: Transcript) : TimelineItem() {
        override val timestamp = transcript.timestamp
    }

    data class LlmItem(val interaction: LlmInteraction) : TimelineItem() {
        override val timestamp = interaction.timestamp
    }
}

class MainActivity : ComponentActivity() {

    private var voiceService: VoiceListeningService? = null
    private var isBound = false
    private var transcriptRepository: TranscriptRepository? = null
    private var llmInteractionRepository: LlmInteractionRepository? = null

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
            loadTimelineItems()
        }
    }

    private var currentTranscript by mutableStateOf("")
    private var timelineItems by mutableStateOf<List<TimelineItem>>(emptyList())
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
        llmInteractionRepository = LlmInteractionRepository(applicationContext)

        checkPermissionsAndStart()
        loadTimelineItems()

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
                            timelineItems = timelineItems,
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
        // Don't close repositories - they share the application's BoxStore
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

    private fun loadTimelineItems() {
        val transcripts = transcriptRepository?.getRecent(20) ?: emptyList()
        val llmInteractions = llmInteractionRepository?.getRecent(20) ?: emptyList()

        val items = mutableListOf<TimelineItem>()
        items.addAll(transcripts.map { TimelineItem.TranscriptItem(it) })
        items.addAll(llmInteractions.map { TimelineItem.LlmItem(it) })

        timelineItems = items.sortedByDescending { it.timestamp }
    }

    private fun toggleExcludeFromContext(transcript: Transcript) {
        val oldValue = transcript.excludeFromContext
        transcript.excludeFromContext = !transcript.excludeFromContext
        transcriptRepository?.update(transcript)
        Log.d(TAG, "Toggled transcript ${transcript.id}: excludeFromContext ${oldValue} -> ${transcript.excludeFromContext}")
        loadTimelineItems()
    }

    @Composable
    fun DebugScreen(
        currentTranscript: String,
        timelineItems: List<TimelineItem>,
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
                text = "Timeline (${timelineItems.size}) - Long press transcript to toggle context",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(timelineItems) { item ->
                    when (item) {
                        is TimelineItem.TranscriptItem -> {
                            TranscriptCard(
                                transcript = item.transcript,
                                onToggleExcludeFromContext = onToggleExcludeFromContext
                            )
                        }
                        is TimelineItem.LlmItem -> {
                            LlmInteractionCard(interaction = item.interaction)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun TranscriptCard(
        transcript: Transcript,
        onToggleExcludeFromContext: (Transcript) -> Unit
    ) {
        val borderColor = if (transcript.excludeFromContext) {
            Color(0xFFE57373) // Red for excluded
        } else {
            Color(0xFF81C784) // Green for included
        }

        val backgroundColor = if (transcript.excludeFromContext) {
            Color(0xFFFFF3F3) // Light red background
        } else {
            Color(0xFFF1F8F4) // Light green background
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { },
                    onLongClick = { onToggleExcludeFromContext(transcript) }
                ),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(borderColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = dateFormat.format(Date(transcript.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (transcript.excludeFromContext) "Excluded" else "Included",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = borderColor
                    )
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
    fun LlmInteractionCard(interaction: LlmInteraction) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF3E5F5) // Light purple for LLM
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🤖",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = dateFormat.format(Date(interaction.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${interaction.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (interaction.grade != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "⭐${interaction.grade}/5",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFA726)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Show user prompt
                Text(
                    text = "Context:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = interaction.userPrompt.take(200) + if (interaction.userPrompt.length > 200) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show response
                Text(
                    text = "Response:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = interaction.response,
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