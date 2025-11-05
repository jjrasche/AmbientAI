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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ambientai.core.VoiceListeningService
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.LogRepository
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import com.ambientai.ui.screens.DatabaseScreen
import com.ambientai.ui.screens.TimelineScreen
import com.ambientai.ui.theme.AmbientAITheme

class MainActivity : ComponentActivity() {

    // Service binding
    private var voiceService: VoiceListeningService? = null
    private var isBound = false

    // Repositories
    private lateinit var transcriptRepository: TranscriptRepository
    private lateinit var actionExecutionRepository: ActionExecutionRepository
    private lateinit var workflowDefinitionRepository: WorkflowDefinitionRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var logRepository: LogRepository

    // Navigation state
    private var currentScreen by mutableStateOf<Screen>(Screen.Timeline)

    // Current transcript state (from service)
    private var currentTranscript by mutableStateOf("")

    sealed class Screen {
        object Timeline : Screen()
        object Database : Screen()
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    // Service connection
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

    // Transcript update listener
    private val transcriptListener = object : VoiceListeningService.TranscriptUpdateListener {
        override fun onPartialTranscript(text: String) {
            currentTranscript = text
        }

        override fun onTranscriptSaved(transcript: Transcript) {
            currentTranscript = ""
        }
    }

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true &&
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true) {
            startVoiceService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize repositories
        transcriptRepository = TranscriptRepository()
        actionExecutionRepository = ActionExecutionRepository()
        workflowDefinitionRepository = WorkflowDefinitionRepository()
        taskRepository = TaskRepository()
        logRepository = LogRepository()

        checkPermissionsAndStart()

        setContent {
            AmbientAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.Timeline -> TimelineScreen(
                            currentTranscript = currentTranscript,
                            transcriptRepository = transcriptRepository,
                            actionExecutionRepository = actionExecutionRepository,
                            onNavigateToDb = { currentScreen = Screen.Database },
                            onToggleExcludeFromContext = ::toggleExcludeFromContext
                        )
                        Screen.Database -> DatabaseScreen(
                            transcriptRepository = transcriptRepository,
                            actionExecutionRepository = actionExecutionRepository,
                            workflowDefinitionRepository = workflowDefinitionRepository,
                            taskRepository = taskRepository,
                            logRepository = logRepository,
                            onBack = { currentScreen = Screen.Timeline }
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

    private fun toggleExcludeFromContext(transcript: Transcript) {
        transcriptRepository.toggleExcludeFromContext(transcript.id)
        Log.d(TAG, "Toggled excludeFromContext for transcript ${transcript.id}")
    }
}