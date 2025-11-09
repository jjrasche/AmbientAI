package com.ambientai.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambientai.data.entities.Transcript
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptCard(transcript: Transcript, onToggleExcludeFromContext: (Transcript) -> Unit) {
    val borderColor = if (transcript.excludeFromContext) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val backgroundColor = if (transcript.excludeFromContext) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    Card(modifier = Modifier.fillMaxWidth().border(2.dp, borderColor, RoundedCornerShape(8.dp)).combinedClickable(onClick = {}, onLongClick = { onToggleExcludeFromContext(transcript) }), colors = CardDefaults.cardColors(containerColor = backgroundColor)) { Column(modifier = Modifier.padding(12.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(8.dp).background(borderColor, RoundedCornerShape(4.dp))); Spacer(modifier = Modifier.width(8.dp)); Text(text = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(transcript.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(text = if (transcript.excludeFromContext) "Excluded" else "Included", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = borderColor) }; Spacer(modifier = Modifier.height(4.dp)); Text(text = transcript.text, style = MaterialTheme.typography.bodyMedium) } }
}
