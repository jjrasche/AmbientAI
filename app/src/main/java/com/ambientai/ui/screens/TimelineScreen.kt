package com.ambientai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ambientai.core.InteractionState
import com.ambientai.core.InteractionSummary
import com.ambientai.ui.components.CurrentInteractionCard
import com.ambientai.ui.components.RecentInteractionsCard
@Composable
fun TimelineScreen(currentInteraction: InteractionState?, recentInteractions: List<InteractionSummary>, onNavigateToDb: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(text = "Ambient AI", style = MaterialTheme.typography.headlineMedium); Button(onClick = onNavigateToDb) { Text("Database") } }
        CurrentInteractionCard(state = currentInteraction, modifier = Modifier.padding(bottom = 12.dp))
        RecentInteractionsCard(interactions = recentInteractions)
    }
}
