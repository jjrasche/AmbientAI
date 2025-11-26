package com.ambientai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambientai.core.InteractionSummary
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecentInteractionsCard(interactions: List<InteractionSummary>, modifier: Modifier = Modifier) {
    if (interactions.isEmpty()) return
    val avgLatency = interactions.mapNotNull { it.totalDurationMs }.average().takeIf { !it.isNaN() }?.toLong()
    val avgStt = interactions.mapNotNull { it.sttLatencyMs }.average().takeIf { !it.isNaN() }?.toLong()
    val successRate = interactions.count { it.success }.toFloat() / interactions.size * 100
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Recent (${interactions.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    avgStt?.let { StatChip(label = "STT", value = "${it}ms", color = if (it < 400) Color(0xFF4CAF50) else if (it < 800) Color(0xFFFF9800) else Color(0xFFF44336)) }
                    avgLatency?.let { StatChip(label = "Avg", value = "${it}ms", color = if (it < 2000) Color(0xFF4CAF50) else if (it < 4000) Color(0xFFFF9800) else Color(0xFFF44336)) }
                    StatChip(label = "OK", value = "${successRate.toInt()}%", color = if (successRate > 80) Color(0xFF4CAF50) else if (successRate > 50) Color(0xFFFF9800) else Color(0xFFF44336))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            interactions.take(5).forEach { interaction ->
                InteractionRow(interaction)
                if (interaction != interactions.take(5).last()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun InteractionRow(interaction: InteractionSummary) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (interaction.success) Color(0xFF4CAF50) else Color(0xFFF44336)))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = interaction.transcript.take(50) + if (interaction.transcript.length > 50) "..." else "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                interaction.workflow?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(interaction.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            interaction.totalDurationMs?.let { Text(text = "${it}ms", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (it < 2000) Color(0xFF4CAF50) else if (it < 4000) Color(0xFFFF9800) else Color(0xFFF44336)) }
        }
    }
}
