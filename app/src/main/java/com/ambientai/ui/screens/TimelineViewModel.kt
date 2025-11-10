package com.ambientai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.IActionExecutionRepository
import com.ambientai.data.repositories.TranscriptRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TimelineViewModel(
    private val transcriptRepository: TranscriptRepository,
    private val actionExecutionRepository: ActionExecutionRepository
) : ViewModel() {
    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript: StateFlow<String> = _currentTranscript.asStateFlow()
    val transcripts: StateFlow<List<Transcript>> = transcriptRepository
        .getRecentTranscripts(20)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val llmInteractions: StateFlow<List<IActionExecutionRepository.LlmInteractionView>> =
        actionExecutionRepository
            .getLlmInteractions()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
    val timelineItems: StateFlow<List<TimelineItem>> = combine(
        transcripts,
        llmInteractions
    ) { transcriptList, llmList ->
        buildList {
            addAll(transcriptList.map { TimelineItem.TranscriptItem(it) })
            addAll(llmList.map { TimelineItem.LlmItem(it) })
        }.sortedByDescending { it.timestamp }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    fun updateCurrentTranscript(text: String) {
        _currentTranscript.value = text
    }

    fun clearCurrentTranscript() {
        _currentTranscript.value = ""
    }
    fun toggleExcludeFromContext(transcript: Transcript) {
        viewModelScope.launch {
            transcriptRepository.toggleExcludeFromContext(transcript.id)
        }
    }
}
