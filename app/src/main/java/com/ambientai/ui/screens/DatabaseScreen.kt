package com.ambientai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.LlmInteractionRepository
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DatabaseScreen(
    transcriptRepository: TranscriptRepository,
    llmInteractionRepository: LlmInteractionRepository,
    workflowDefinitionRepository: WorkflowDefinitionRepository,
    taskRepository: TaskRepository,
    onBack: () -> Unit
) {
    val transcripts by transcriptRepository.getAllTranscripts()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val llmInteractions by llmInteractionRepository.getAllInteractions()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Workflows and tasks don't have Flow support yet, so use regular lists
    val workflows = remember { workflowDefinitionRepository.getAll() }
    val tasks = remember { taskRepository.getAll() }

    var selectedTab by remember { mutableStateOf(0) }

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
                text = "Database",
                style = MaterialTheme.typography.headlineMedium
            )
            Button(onClick = onBack) {
                Text("Back")
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 }
            ) {
                Text(
                    text = "Transcripts (${transcripts.size})",
                    modifier = Modifier.padding(16.dp)
                )
            }
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 }
            ) {
                Text(
                    text = "LLM (${llmInteractions.size})",
                    modifier = Modifier.padding(16.dp)
                )
            }
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 }
            ) {
                Text(
                    text = "Workflows (${workflows.size})",
                    modifier = Modifier.padding(16.dp)
                )
            }
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 }
            ) {
                Text(
                    text = "Tasks (${tasks.size})",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> TranscriptsTab(transcripts)
            1 -> LlmInteractionsTab(llmInteractions)
            2 -> WorkflowsTab(workflows)
            3 -> TasksTab(tasks)
        }
    }
}

@Composable
fun TranscriptsTab(transcripts: List<Transcript>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transcripts) { transcript ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID: ${transcript.id}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = dateFormat.format(Date(transcript.timestamp)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = transcript.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (transcript.excludeFromContext) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "EXCLUDED FROM CONTEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LlmInteractionsTab(interactions: List<LlmInteraction>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(interactions) { interaction ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID: ${interaction.id}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "${interaction.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = dateFormat.format(Date(interaction.timestamp)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "System Prompt:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = interaction.systemPrompt.take(100) +
                                if (interaction.systemPrompt.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "User Prompt:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = interaction.userPrompt.take(100) +
                                if (interaction.userPrompt.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Response:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = interaction.response,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (interaction.grade != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grade: ${interaction.grade}/5",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowsTab(workflows: List<WorkflowDefinition>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(workflows) { workflow ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID: ${workflow.id}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = if (workflow.enabled) "ENABLED" else "DISABLED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (workflow.enabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = workflow.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = workflow.definition.take(200) +
                                if (workflow.definition.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TasksTab(tasks: List<Task>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tasks) { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID: ${task.id}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = task.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (task.status.name) {
                                "ACTIVE" -> MaterialTheme.colorScheme.primary
                                "PAUSED" -> MaterialTheme.colorScheme.tertiary
                                "COMPLETED" -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Created: ${dateFormat.format(Date(task.createdAt))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (task.completedAt != null) {
                        Text(
                            text = "Completed: ${dateFormat.format(Date(task.completedAt!!))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "Total time: ${formatDuration(task.totalElapsedMs())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    return when {
        hours > 0 -> {
            val remainingMinutes = minutes % 60
            if (remainingMinutes > 0) {
                "$hours hours $remainingMinutes minutes"
            } else {
                "$hours hours"
            }
        }
        minutes > 0 -> "$minutes minutes"
        else -> "$seconds seconds"
    }
}