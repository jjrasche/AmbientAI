package com.ambientai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
import com.ambientai.data.repositories.LogEntryRepository
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import com.ambientai.data.repositories.WorkflowExecutionRepository
import com.ambientai.ui.screens.DatabaseScreen
import com.ambientai.ui.screens.TimelineScreen
import com.ambientai.ui.theme.AmbientAITheme

class MainActivity : ComponentActivity() {
    private var voiceService: VoiceListeningService? = null
    private var isBound = false
    private lateinit var transcriptRepository: TranscriptRepository
    private lateinit var actionExecutionRepository: ActionExecutionRepository
    private lateinit var workflowDefinitionRepository: WorkflowDefinitionRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var workflowExecutionRepository: WorkflowExecutionRepository
    private lateinit var logEntryRepository: LogEntryRepository
    private var currentScreen by mutableStateOf<Screen>(Screen.Timeline)
    private var currentTranscript by mutableStateOf("")
    sealed class Screen {
        object Timeline : Screen()
        object Database : Screen()
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = (service as VoiceListeningService.LocalBinder).getService().also {
                isBound = true
                it.registerListener(transcriptListener)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBound = false
        }
    }
    private val transcriptListener = object : VoiceListeningService.TranscriptUpdateListener {
        override fun onPartialTranscript(text: String) { currentTranscript = text }
        override fun onTranscriptSaved(transcript: Transcript) { currentTranscript = "" }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true && permissions[Manifest.permission.POST_NOTIFICATIONS] == true) startVoiceService()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transcriptRepository = TranscriptRepository()
        actionExecutionRepository = ActionExecutionRepository()
        workflowDefinitionRepository = WorkflowDefinitionRepository()
        taskRepository = TaskRepository()
        workflowExecutionRepository = WorkflowExecutionRepository()
        logEntryRepository = LogEntryRepository()
        checkPermissionsAndStart()
        setContent {
            AmbientAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.Timeline -> TimelineScreen(currentTranscript, transcriptRepository, actionExecutionRepository,
                            onNavigateToDb = { currentScreen = Screen.Database }, onToggleExcludeFromContext = ::toggleExcludeFromContext)
                        Screen.Database -> DatabaseScreen(transcriptRepository, actionExecutionRepository, workflowDefinitionRepository,
                            taskRepository, workflowExecutionRepository, logEntryRepository, onBack = { currentScreen = Screen.Timeline })
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, VoiceListeningService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
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
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions)
        } else {
            startVoiceService()
        }
    }
    private fun startVoiceService() = ContextCompat.startForegroundService(this, Intent(this, VoiceListeningService::class.java))
    private fun toggleExcludeFromContext(transcript: Transcript) = transcriptRepository.toggleExcludeFromContext(transcript.id)
}
