package com.ambientai.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientai.data.WorkflowSeeder
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.LogEntry
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.LogEntryRepository
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import com.ambientai.data.repositories.WorkflowExecutionRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DatabaseScreen(
    transcriptRepository: TranscriptRepository,
    actionExecutionRepository: ActionExecutionRepository,
    workflowDefinitionRepository: WorkflowDefinitionRepository,
    taskRepository: TaskRepository,
    workflowExecutionRepository: WorkflowExecutionRepository,
    logEntryRepository: LogEntryRepository,
    onBack: () -> Unit
) {
    val transcripts by transcriptRepository.getAllTranscripts().collectAsStateWithLifecycle(initialValue = emptyList())
    val llmInteractions by actionExecutionRepository.getLlmInteractions().collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by taskRepository.getAllTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    val workflows by workflowDefinitionRepository.getAllTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    val workflowExecutions by workflowExecutionRepository.getRecentExecutions().collectAsStateWithLifecycle(initialValue = emptyList())
    val logEntries by logEntryRepository.getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())
    val workflowSeeder = WorkflowSeeder();
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text( text = "Database",  style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) {  Text("Back") }
        }

        ScrollableTabRow(selectedTabIndex = selectedTab) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 0 })
            ) { Text(text = "Transcripts (${transcripts.size})", modifier = Modifier.padding(16.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 1 })
            ) { Text(text = "LLM (${llmInteractions.size})",  modifier = Modifier.padding(16.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 2 },
                    onDoubleClick = { workflowSeeder.seed() },
                    onLongClick = { workflowDefinitionRepository.deleteAll() }),
            ) { Text(text = "Workflows (${workflows.size})", modifier = Modifier.padding(16.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 3 },
                    onLongClick = { taskRepository.deleteAll() }),
            ) { Text(text = "Tasks (${tasks.size})", modifier = Modifier.padding(16.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 4 }),
//                    onLongClick = { workflowExecutionRepository.deleteAll(); actionExecutionRepository.deleteAll() }
            ) { Text(text = "Executions (${workflowExecutions.size})", modifier = Modifier.padding(16.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = { selectedTab = 5 },
                    onLongClick = { logEntryRepository.deleteAll() }),
            ) { Text(text = "Log Entries (${logEntries.size})", modifier = Modifier.padding(16.dp)) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        when (selectedTab) {
            0 -> TranscriptsTab(transcripts)
            1 -> LlmInteractionsTab(llmInteractions)
            2 -> WorkflowsTab(workflows)
            3 -> TasksTab(tasks)
            4 -> WorkflowExecutionsTab(workflowExecutions, workflowExecutionRepository)
            5 -> LogsTab(logEntries)
        }
    }
}
@Composable
fun TranscriptsTab(transcripts: List<Transcript>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(transcripts) { transcript ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row( modifier = Modifier.fillMaxWidth(),  horizontalArrangement = Arrangement.SpaceBetween ) {
                        Text(text = "ID: ${transcript.id}",  style = MaterialTheme.typography.labelSmall)
                        Text(text = dateFormat.format(Date(transcript.timestamp)),  style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = transcript.text,  style = MaterialTheme.typography.bodyMedium)
                    if (transcript.excludeFromContext) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "EXCLUDED FROM CONTEXT",  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
@Composable
fun LlmInteractionsTab(interactions: List<ActionExecutionRepository.LlmInteractionView>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(interactions) { interaction ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row( modifier = Modifier.fillMaxWidth(),  horizontalArrangement = Arrangement.SpaceBetween ) {
                        Text(text = "ID: ${interaction.id}",  style = MaterialTheme.typography.labelSmall)
                        Text(text = "${interaction.latencyMs}ms",  style = MaterialTheme.typography.labelSmall)
                    }
                    Text(text = dateFormat.format(Date(interaction.timestamp)),  style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "System Prompt:",  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(text = interaction.systemPrompt.take(100) +  if (interaction.systemPrompt.length > 100) "..." else "", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "User Prompt:",  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(text = interaction.userPrompt.take(100) +  if (interaction.userPrompt.length > 100) "..." else "", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Response:",  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(text = interaction.response,  style = MaterialTheme.typography.bodyMedium)
                    if (interaction.grade != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Grade: ${interaction.grade}/5",  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
@Composable
fun WorkflowsTab(workflows: List<WorkflowDefinition>) {
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(workflows) { workflow ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row( modifier = Modifier.fillMaxWidth(),  horizontalArrangement = Arrangement.SpaceBetween ) {
                        Text(text = "ID: ${workflow.id}",  style = MaterialTheme.typography.labelSmall)
                        Text(text = if (workflow.enabled) "ENABLED" else "DISABLED",  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = if (workflow.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = workflow.name,  style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = workflow.definition.take(200) +  if (workflow.definition.length > 200) "..." else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable
fun TasksTab(tasks: List<Task>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tasks) { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row( modifier = Modifier.fillMaxWidth(),  horizontalArrangement = Arrangement.SpaceBetween ) {
                        Text(text = "ID: ${task.id}",  style = MaterialTheme.typography.labelSmall)
                        Text(text = task.status.name,  style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = when (task.status.name) {
                                "ACTIVE" -> MaterialTheme.colorScheme.primary
                                "PAUSED" -> MaterialTheme.colorScheme.tertiary
                                "COMPLETED" -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = task.name,  style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Created: ${dateFormat.format(Date(task.createdAt))}",  style = MaterialTheme.typography.bodySmall)
                    if (task.completedAt != null) {
                        Text(text = "Completed: ${dateFormat.format(Date(task.completedAt!!))}",  style = MaterialTheme.typography.bodySmall)
                    }
                    Text(text = "Total time: ${task.totalElapsedMs().toHumanDuration()}",  style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun WorkflowExecutionsTab(
    executions: List<WorkflowExecution>,
    workflowExecutionRepository: WorkflowExecutionRepository
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    var expandedExecutionId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(executions) { execution ->
            val isExpanded = expandedExecutionId == execution.id
            val actions = remember(execution.id) {
                workflowExecutionRepository.getActionsForExecution(execution.id)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expandedExecutionId = if (isExpanded) null else execution.id
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (execution.success) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Workflow execution header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ID: ${execution.id}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${execution.executionTimeMs}ms",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = if (execution.success) "✓" else "✗",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (execution.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = execution.workflowName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = dateFormat.format(Date(execution.timestamp)),
                        style = MaterialTheme.typography.labelSmall
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Trigger: ${execution.matchedTrigger}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = execution.transcript.take(100) +
                                if (execution.transcript.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (execution.errorMessage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error: ${execution.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Expandable actions section
                    if (isExpanded && actions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Actions (${actions.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        actions.forEach { action ->
                            ActionExecutionRow(action)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    if (!isExpanded && actions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "▼ ${actions.size} actions (tap to expand)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActionExecutionRow(action: ActionExecution) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (action.success) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.error
        },
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = action.actionName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${action.latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (action.grade != null) {
                        Text(
                            text = "⭐${action.grade}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Text(
                text = "Step: ${action.stepPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!action.success && action.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${action.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Show input/output preview
            if (action.inputJson.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Input: ${action.inputJson.take(500)}${if (action.inputJson.length > 500) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (action.success && action.outputJson.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Output: ${action.outputJson.take(500)}${if (action.outputJson.length > 500) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
fun LogsTab(logs: List<LogEntry>) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(logs) { log ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "ID: ${log.id}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = log.type.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(text = dateFormat.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))

                    val data = JSONObject(log.data)
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = data.optString(key, "")
                        if (value.isNotEmpty()) {
                            Text(
                                text = "$key: $value",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (key == "name") FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Transcript ID: ${log.transcript.targetId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}