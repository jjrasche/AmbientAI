package com.ambientai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ambientai.data.entities.WorkflowDefinition
import org.json.JSONObject

@Composable
fun QuickStartWorkflowDialog(workflow: WorkflowDefinition, isListening: Boolean, partialTranscript: String, onStartListening: () -> Unit, onDismiss: () -> Unit) {
    val triggerExamples = remember(workflow.definition) { parseTriggerPhrases(workflow.definition) }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = workflow.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (triggerExamples.isNotEmpty()) {
                    Text(text = "Example phrases:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { triggerExamples.take(3).forEach { trigger -> Text(text = "• \"$trigger\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }
                }
                if (isListening) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Listening...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        if (partialTranscript.isNotEmpty()) { Spacer(modifier = Modifier.height(4.dp)); Text(text = partialTranscript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    Button(onClick = onStartListening, modifier = Modifier.fillMaxWidth()) { Text("Start Speaking") }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }
}
private fun parseTriggerPhrases(workflowJson: String) = runCatching { JSONObject(workflowJson).let { json -> json.optJSONObject("triggers")?.optJSONArray("keywords")?.let { keywordsArray -> List(keywordsArray.length()) { i -> keywordsArray.getString(i) } } ?: json.optJSONArray("triggers")?.let { triggersArray -> List(triggersArray.length()) { i -> triggersArray.getString(i) } } ?: emptyList() } }.getOrElse { emptyList() }
