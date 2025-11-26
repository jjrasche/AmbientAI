package com.ambientai.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InteractionTracker @Inject constructor() {
    private val _currentState = MutableStateFlow<InteractionState?>(null)
    val currentState: StateFlow<InteractionState?> = _currentState
    private val _recentInteractions = MutableStateFlow<List<InteractionSummary>>(emptyList())
    val recentInteractions: StateFlow<List<InteractionSummary>> = _recentInteractions
    private var current: InteractionState? = null

    fun startRecording() {
        current = InteractionState(recordingStartMs = System.currentTimeMillis())
        _currentState.value = current
    }
    fun firstPartialReceived(text: String) {
        current = current?.copy(firstPartialMs = System.currentTimeMillis(), partialText = text, partialCount = 1)
        _currentState.value = current
    }
    fun partialReceived(text: String) {
        current = current?.copy(partialText = text, partialCount = (current?.partialCount ?: 0) + 1)
        _currentState.value = current
    }
    fun finalTranscriptReceived(text: String) {
        current = current?.copy(finalTranscriptMs = System.currentTimeMillis(), finalText = text)
        _currentState.value = current
    }
    fun recordingStopped() {
        current = current?.copy(recordingStopMs = System.currentTimeMillis())
        _currentState.value = current
    }
    fun routingStarted() {
        current = current?.copy(routingStartMs = System.currentTimeMillis())
        _currentState.value = current
    }
    fun workflowMatched(workflowName: String, trigger: String) {
        current = current?.copy(workflowMatchMs = System.currentTimeMillis(), matchedWorkflow = workflowName, matchedTrigger = trigger, waitingForInput = false)
        _currentState.value = current
    }
    fun workflowWaitingForInput(workflowName: String, trigger: String) {
        current = current?.copy(workflowMatchMs = System.currentTimeMillis(), matchedWorkflow = workflowName, matchedTrigger = trigger, waitingForInput = true)
        _currentState.value = current
    }
    fun noWorkflowMatched() {
        current = current?.copy(workflowMatchMs = System.currentTimeMillis(), matchedWorkflow = "(no match)")
        _currentState.value = current
    }
    fun actionStarted(actionName: String) {
        val actions = (current?.actionTimings ?: emptyList()) + ActionTiming(actionName, System.currentTimeMillis())
        current = current?.copy(actionTimings = actions)
        _currentState.value = current
    }
    fun actionCompleted(actionName: String, success: Boolean) {
        val actions = current?.actionTimings?.map { if (it.name == actionName && it.endMs == null) it.copy(endMs = System.currentTimeMillis(), success = success) else it }
        current = current?.copy(actionTimings = actions ?: emptyList())
        _currentState.value = current
    }
    fun ttsStarted() {
        current = current?.copy(ttsStartMs = System.currentTimeMillis())
        _currentState.value = current
    }
    fun ttsCompleted() {
        current = current?.copy(ttsEndMs = System.currentTimeMillis())
        _currentState.value = current
    }
    fun interactionCompleted(success: Boolean) {
        current = current?.copy(completedMs = System.currentTimeMillis(), success = success)
        _currentState.value = current
        current?.let { state ->
            val summary = InteractionSummary(
                timestamp = state.recordingStartMs,
                transcript = state.finalText ?: state.partialText ?: "",
                workflow = state.matchedWorkflow,
                totalDurationMs = state.completedMs?.let { it - state.recordingStartMs },
                sttLatencyMs = state.firstPartialMs?.let { it - state.recordingStartMs },
                workflowLatencyMs = state.workflowMatchMs?.let { start -> state.completedMs?.let { it - start } },
                success = success
            )
            _recentInteractions.value = (listOf(summary) + _recentInteractions.value).take(10)
        }
    }
    fun clear() {
        current = null
        _currentState.value = null
    }
}

data class InteractionState(
    val recordingStartMs: Long,
    val recordingStopMs: Long? = null,
    val firstPartialMs: Long? = null,
    val partialText: String? = null,
    val partialCount: Int = 0,
    val finalTranscriptMs: Long? = null,
    val finalText: String? = null,
    val routingStartMs: Long? = null,
    val workflowMatchMs: Long? = null,
    val matchedWorkflow: String? = null,
    val matchedTrigger: String? = null,
    val waitingForInput: Boolean = false,
    val actionTimings: List<ActionTiming> = emptyList(),
    val ttsStartMs: Long? = null,
    val ttsEndMs: Long? = null,
    val completedMs: Long? = null,
    val success: Boolean? = null
) {
    val sttLatencyMs: Long? get() = firstPartialMs?.let { it - recordingStartMs }
    val recordingDurationMs: Long? get() = recordingStopMs?.let { it - recordingStartMs }
    val routingLatencyMs: Long? get() = if (routingStartMs != null && workflowMatchMs != null) workflowMatchMs - routingStartMs else null
    val totalLatencyMs: Long? get() = completedMs?.let { it - recordingStartMs }
    val phase: InteractionPhase get() = when {
        completedMs != null -> InteractionPhase.COMPLETED
        ttsStartMs != null -> InteractionPhase.SPEAKING
        actionTimings.any { it.endMs == null } -> InteractionPhase.EXECUTING
        waitingForInput -> InteractionPhase.WAITING_INPUT
        workflowMatchMs != null -> InteractionPhase.MATCHED
        routingStartMs != null -> InteractionPhase.ROUTING
        finalTranscriptMs != null -> InteractionPhase.TRANSCRIBED
        recordingStopMs != null -> InteractionPhase.PROCESSING_STT
        firstPartialMs != null -> InteractionPhase.LISTENING
        else -> InteractionPhase.STARTING
    }
}

enum class InteractionPhase(val display: String) {
    STARTING("Starting..."),
    LISTENING("Listening"),
    PROCESSING_STT("Processing STT"),
    TRANSCRIBED("Transcribed"),
    ROUTING("Routing"),
    MATCHED("Matched"),
    WAITING_INPUT("Waiting for input..."),
    EXECUTING("Executing"),
    SPEAKING("Speaking"),
    COMPLETED("Done")
}

data class ActionTiming(val name: String, val startMs: Long, val endMs: Long? = null, val success: Boolean? = null) {
    val durationMs: Long? get() = endMs?.let { it - startMs }
}

data class InteractionSummary(
    val timestamp: Long,
    val transcript: String,
    val workflow: String?,
    val totalDurationMs: Long?,
    val sttLatencyMs: Long?,
    val workflowLatencyMs: Long?,
    val success: Boolean
)
