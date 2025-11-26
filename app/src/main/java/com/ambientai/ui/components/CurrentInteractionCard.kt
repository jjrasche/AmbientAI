package com.ambientai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambientai.core.InteractionPhase
import com.ambientai.core.InteractionState

@Composable
fun CurrentInteractionCard(state: InteractionState?, modifier: Modifier = Modifier) {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state != null && state.completedMs == null) { now.value = System.currentTimeMillis(); kotlinx.coroutines.delay(50) } }
    val elapsedMs = state?.let { now.value - it.recordingStartMs } ?: 0L
    val phaseColor by animateColorAsState(targetValue = when (state?.phase) {
        InteractionPhase.STARTING -> Color(0xFF9E9E9E)
        InteractionPhase.LISTENING -> Color(0xFF4CAF50)
        InteractionPhase.PROCESSING_STT -> Color(0xFFFF9800)
        InteractionPhase.TRANSCRIBED -> Color(0xFF2196F3)
        InteractionPhase.ROUTING -> Color(0xFFFF9800)
        InteractionPhase.MATCHED -> Color(0xFF9C27B0)
        InteractionPhase.WAITING_INPUT -> Color(0xFFFFEB3B)
        InteractionPhase.EXECUTING -> Color(0xFFE91E63)
        InteractionPhase.SPEAKING -> Color(0xFF00BCD4)
        InteractionPhase.COMPLETED -> if (state.success == true) Color(0xFF4CAF50) else Color(0xFFF44336)
        null -> Color(0xFF9E9E9E)
    }, label = "phaseColor")
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PulsingDot(color = phaseColor, isPulsing = state != null && state.completedMs == null)
                    Text(text = state?.phase?.display ?: "Idle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = phaseColor)
                }
                Text(text = formatMs(elapsedMs), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = state.partialText ?: state.finalText ?: "...", style = MaterialTheme.typography.bodyLarge, maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(12.dp))
                TimingBreakdown(state)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Press power button to start", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TimingBreakdown(state: InteractionState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimingChip(label = "STT", value = state.sttLatencyMs, suffix = "ms")
            TimingChip(label = "Recording", value = state.recordingDurationMs, suffix = "ms")
            TimingChip(label = "Routing", value = state.routingLatencyMs, suffix = "ms")
        }
        if (state.matchedWorkflow != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Workflow:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = state.matchedWorkflow, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                state.matchedTrigger?.let { Text(text = "($it)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (state.actionTimings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            state.actionTimings.forEach { action ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = action.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.durationMs?.let { Text(text = "${it}ms", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        action.success?.let { Text(text = if (it) "OK" else "FAIL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (it) Color(0xFF4CAF50) else Color(0xFFF44336)) }
                    }
                }
            }
        }
        state.totalLatencyMs?.let {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Total", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = formatMs(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (it < 2000) Color(0xFF4CAF50) else if (it < 4000) Color(0xFFFF9800) else Color(0xFFF44336))
            }
        }
    }
}

@Composable
private fun TimingChip(label: String, value: Long?, suffix: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value?.let { "$it$suffix" } ?: "--", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PulsingDot(color: Color, isPulsing: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 0.3f, animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse), label = "pulseAlpha")
    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color.copy(alpha = if (isPulsing) alpha else 1f)))
}

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 10000 -> String.format("%.1fs", ms / 1000.0)
    else -> String.format("%.0fs", ms / 1000.0)
}
