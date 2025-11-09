package com.ambientai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.TranscriptRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * POC: ViewModel version of TimelineScreen logic
 *
 * BEFORE (in Composable):
 * - Repository instances passed from MainActivity
 * - currentTranscript state managed in MainActivity
 * - Flow collection happens in composable
 * - Business logic (toggleExcludeFromContext) in MainActivity
 *
 * AFTER (with ViewModel):
 * - ViewModel injected (will use Hilt later)
 * - All state centralized here
 * - Survives configuration changes (rotation)
 * - Testable without starting Activity
 */
class TimelineViewModel(
    private val transcriptRepository: TranscriptRepository,
    private val actionExecutionRepository: ActionExecutionRepository
) : ViewModel() {

    // State: Current transcript being spoken (updated by service listener)
    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript: StateFlow<String> = _currentTranscript.asStateFlow()

    // State: Recent transcripts (reactive from DB)
    val transcripts: StateFlow<List<Transcript>> = transcriptRepository
        .getRecentTranscripts(20)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // State: LLM interactions (reactive from DB)
    val llmInteractions: StateFlow<List<ActionExecutionRepository.LlmInteractionView>> =
        actionExecutionRepository
            .getLlmInteractions()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // State: Merged timeline (computed from transcripts + llmInteractions)
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

    // Actions: Update current transcript (called by service listener)
    fun updateCurrentTranscript(text: String) {
        _currentTranscript.value = text
    }

    fun clearCurrentTranscript() {
        _currentTranscript.value = ""
    }

    // Actions: Business logic (was in MainActivity before)
    fun toggleExcludeFromContext(transcript: Transcript) {
        viewModelScope.launch {
            transcriptRepository.toggleExcludeFromContext(transcript.id)
        }
    }
}

/**
 * HOW TO USE (POC):
 *
 * // In MainActivity onCreate (manual DI for now, Hilt later):
 * val timelineViewModel = TimelineViewModel(
 *     transcriptRepository = TranscriptRepository(),
 *     actionExecutionRepository = ActionExecutionRepository()
 * )
 *
 * // Update from service listener:
 * private val transcriptListener = object : VoiceListeningService.TranscriptUpdateListener {
 *     override fun onPartialTranscript(text: String) {
 *         timelineViewModel.updateCurrentTranscript(text)
 *     }
 *     override fun onTranscriptSaved(transcript: Transcript) {
 *         timelineViewModel.clearCurrentTranscript()
 *     }
 * }
 *
 * // In Composable:
 * @Composable
 * fun TimelineScreenWithViewModel(viewModel: TimelineViewModel, onNavigateToDb: () -> Unit) {
 *     val currentTranscript by viewModel.currentTranscript.collectAsStateWithLifecycle()
 *     val timelineItems by viewModel.timelineItems.collectAsStateWithLifecycle()
 *
 *     Column {
 *         // UI for currentTranscript
 *         Card { Text(currentTranscript.ifEmpty { "Waiting..." }) }
 *
 *         // Timeline list
 *         LazyColumn {
 *             items(timelineItems) { item ->
 *                 when (item) {
 *                     is TimelineItem.TranscriptItem -> TranscriptCard(
 *                         transcript = item.transcript,
 *                         onToggleExcludeFromContext = viewModel::toggleExcludeFromContext
 *                     )
 *                     is TimelineItem.LlmItem -> LlmInteractionCard(item.interaction)
 *                 }
 *             }
 *         }
 *     }
 * }
 *
 * BENEFITS YOU GET:
 *
 * 1. SURVIVES ROTATION:
 *    - Rotate phone → currentTranscript preserved ✅
 *    - Repository flows don't restart ✅
 *    - No jarring UI resets ✅
 *
 * 2. TESTABLE:
 *    @Test
 *    fun `toggle exclude from context updates repository`() {
 *        val fakeRepo = FakeTranscriptRepository()
 *        val viewModel = TimelineViewModel(fakeRepo, fakeActionRepo)
 *
 *        viewModel.toggleExcludeFromContext(transcript)
 *
 *        assertTrue(fakeRepo.wasToggleCalled)
 *    }
 *
 * 3. CLEAR SEPARATION:
 *    - MainActivity: Only permissions, service binding, navigation
 *    - ViewModel: State management, business logic
 *    - Composable: Just UI rendering (dumb component)
 *
 * 4. SCALES BETTER:
 *    - Add search feature? Add searchQuery: StateFlow<String> to ViewModel
 *    - Add filter? Add filterType: StateFlow<FilterType>
 *    - Add pagination? Add loadMore() function
 *    - All without touching MainActivity or Composable
 *
 * COMPARISON:
 *
 * Lines of code in MainActivity:
 * - BEFORE: 103 lines (8 fields, service binding, permissions, state, business logic, UI)
 * - AFTER: ~40 lines (just permissions, service binding, navigation)
 *
 * Testability:
 * - BEFORE: Need to start ComponentActivity to test toggleExcludeFromContext
 * - AFTER: Pure Kotlin unit test (no Android framework)
 *
 * Configuration changes:
 * - BEFORE: currentTranscript lost on rotation (recreates Activity)
 * - AFTER: currentTranscript preserved (ViewModel survives)
 */
