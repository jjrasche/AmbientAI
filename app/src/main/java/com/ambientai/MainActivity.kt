package com.ambientai

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ambientai.core.InteractionTracker
import com.ambientai.core.VoiceListeningService
import com.ambientai.core.ui.UiService
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.*
import com.ambientai.ui.components.InfoModal
import com.ambientai.ui.components.QuickStartWorkflowDialog
import com.ambientai.ui.screens.DatabaseScreen
import com.ambientai.ui.screens.TimelineScreen
import com.ambientai.ui.screens.WorkflowReviewScreen
import com.ambientai.ui.theme.AmbientAITheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var transcriptRepository: ITranscriptRepository
    @Inject lateinit var actionExecutionRepository: IActionExecutionRepository
    @Inject lateinit var workflowDefinitionRepository: IWorkflowDefinitionRepository
    @Inject lateinit var taskRepository: ITaskRepository
    @Inject lateinit var workflowExecutionRepository: IWorkflowExecutionRepository
    @Inject lateinit var logEntryRepository: ILogEntryRepository
    @Inject lateinit var workflowReviewService: com.ambientai.core.workflow.WorkflowReviewService
    @Inject lateinit var uiService: UiService
    @Inject lateinit var interactionTracker: InteractionTracker
    private var voiceService: VoiceListeningService? = null
    private var isBound = false
    private var currentScreen by mutableStateOf<Screen>(Screen.Timeline)
    private var currentTranscript by mutableStateOf("")
    private var quickStartWorkflow by mutableStateOf<WorkflowDefinition?>(null)
    private var isQuickStartListening by mutableStateOf(false)

    sealed class Screen {
        object Timeline : Screen()
        object Database : Screen()
        object WorkflowReview : Screen()
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = (service as VoiceListeningService.LocalBinder).getService().also { isBound = true; it.registerListener(transcriptListener) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { voiceService = null; isBound = false }
    }
    private val transcriptListener = object : VoiceListeningService.TranscriptUpdateListener {
        override fun onPartialTranscript(text: String) { currentTranscript = text }
        override fun onWorkflowStarted(workflowName: String, transcript: String) { }
        override fun onWorkflowCompleted(workflowName: String) { }
        override fun onTranscriptSaved(transcript: Transcript) { currentTranscript = ""; if (isQuickStartListening) { isQuickStartListening = false; quickStartWorkflow?.let { workflow -> voiceService?.executeWorkflowDirectly(workflow.id, transcript.id) }; quickStartWorkflow = null } }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true && permissions[Manifest.permission.POST_NOTIFICATIONS] == true) startVoiceService()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissionsAndStart()
        checkAlarmPermission()
        setContent {
            val modalData by uiService.modalState.collectAsState()
            val currentInteraction by interactionTracker.currentState.collectAsState()
            val recentInteractions by interactionTracker.recentInteractions.collectAsState()
            AmbientAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.Timeline -> TimelineScreen(currentInteraction, recentInteractions,
                            onNavigateToDb = { currentScreen = Screen.Database })
                        Screen.Database -> DatabaseScreen(transcriptRepository, actionExecutionRepository, workflowDefinitionRepository,
                            taskRepository, workflowExecutionRepository, logEntryRepository, onBack = { currentScreen = Screen.Timeline }, onNavigateToReview = { currentScreen = Screen.WorkflowReview }, onWorkflowQuickStart = ::handleWorkflowQuickStart, onWorkflowsRefresh = ::refreshWorkflows)
                        Screen.WorkflowReview -> WorkflowReviewScreen(workflowDefinitionRepository, workflowExecutionRepository, actionExecutionRepository, workflowReviewService, onBack = { currentScreen = Screen.Database })
                    }
                    quickStartWorkflow?.let { workflow -> QuickStartWorkflowDialog(workflow = workflow, isListening = isQuickStartListening, partialTranscript = currentTranscript, onStartListening = ::startQuickStartListening, onDismiss = ::cancelQuickStart) }
                    modalData?.let { InfoModal(modalData = it, onDismiss = { uiService.execute("ui.dismissModal", org.json.JSONObject()) }) }
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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions)
        } else {
            startVoiceService()
        }
    }
    private fun startVoiceService() = ContextCompat.startForegroundService(this, Intent(this, VoiceListeningService::class.java))
    private fun toggleExcludeFromContext(transcript: Transcript) = transcriptRepository.toggleExcludeFromContext(transcript.id)
    private fun handleWorkflowQuickStart(workflow: WorkflowDefinition) {
        val requiresInput = org.json.JSONObject(workflow.definition).optBoolean("requiresInput", false)
        if (requiresInput) { quickStartWorkflow = workflow }
        else { voiceService?.executeWorkflowDirectly(workflow.id, 0) }
    }
    private fun startQuickStartListening() { isQuickStartListening = true; quickStartWorkflow?.let { voiceService?.startQuickStartRecording(it.id) } }
    private fun cancelQuickStart() { quickStartWorkflow = null; isQuickStartListening = false; voiceService?.cancelQuickStart() }
    private fun refreshWorkflows() { voiceService?.reloadWorkflows() }
    private fun checkAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")).also { startActivity(it) }
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && voiceService?.isRecording() == true) {
            voiceService?.stopRecordingManually()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
