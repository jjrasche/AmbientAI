package com.ambientai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambientai.data.repositories.IActionExecutionRepository
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LlmInteractionCard(interaction: IActionExecutionRepository.LlmInteractionView) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Column(modifier = Modifier.padding(12.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Text(text = "🤖", style = MaterialTheme.typography.titleMedium); Spacer(modifier = Modifier.width(8.dp)); Text(text = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(interaction.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Row(verticalAlignment = Alignment.CenterVertically) { Text(text = "${interaction.latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); interaction.grade?.let { Spacer(modifier = Modifier.width(8.dp)); Text(text = "⭐$it/5", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary) } } }; Spacer(modifier = Modifier.height(8.dp)); Text(text = "Context:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(text = interaction.userPrompt.take(200) + if (interaction.userPrompt.length > 200) "..." else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(modifier = Modifier.height(8.dp)); Text(text = "Response:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold); Text(text = interaction.response, style = MaterialTheme.typography.bodyMedium) } }
}
