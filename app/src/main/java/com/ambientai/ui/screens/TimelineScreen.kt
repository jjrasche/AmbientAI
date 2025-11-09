package com.ambientai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.ui.components.LlmInteractionCard
import com.ambientai.ui.components.TranscriptCard

sealed class TimelineItem {
    abstract val timestamp: Long
    data class TranscriptItem(val transcript: Transcript) : TimelineItem() { override val timestamp = transcript.timestamp }
    data class LlmItem(val interaction: ActionExecutionRepository.LlmInteractionView) : TimelineItem() { override val timestamp = interaction.timestamp }
}
@Composable
fun TimelineScreen(currentTranscript: String, transcriptRepository: TranscriptRepository, actionExecutionRepository: ActionExecutionRepository, onNavigateToDb: () -> Unit, onToggleExcludeFromContext: (Transcript) -> Unit) {
    val transcripts by transcriptRepository.getRecentTranscripts(20).collectAsStateWithLifecycle(initialValue = emptyList())
    val llmInteractions by actionExecutionRepository.getLlmInteractions().collectAsStateWithLifecycle(initialValue = emptyList())
    val timelineItems = remember(transcripts, llmInteractions) { buildList { addAll(transcripts.map { TimelineItem.TranscriptItem(it) }); addAll(llmInteractions.map { TimelineItem.LlmItem(it) }) }.sortedByDescending { it.timestamp } }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(text = "Ambient AI", style = MaterialTheme.typography.headlineMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onNavigateToDb) { Text("Database") } } }; Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(modifier = Modifier.padding(16.dp)) { Text(text = "Live Transcript", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(modifier = Modifier.height(8.dp)); Text(text = currentTranscript.ifEmpty { "Waiting for wake word..." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) } }; Text(text = "Timeline (${timelineItems.size}) - Long press transcript to toggle context", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)); LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(timelineItems, key = { when (it) { is TimelineItem.TranscriptItem -> "t-${it.transcript.id}"; is TimelineItem.LlmItem -> "l-${it.interaction.id}" } }) { item -> when (item) { is TimelineItem.TranscriptItem -> TranscriptCard(transcript = item.transcript, onToggleExcludeFromContext = onToggleExcludeFromContext); is TimelineItem.LlmItem -> LlmInteractionCard(interaction = item.interaction) } } } }
}
