package com.ambientai.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.CustomTab(
    index: Int,
    selectedTab: Int,
    text: String,
    onClick: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f).height(48.dp)
            .combinedClickable( onClick = onClick,  onDoubleClick = onDoubleClick,  onLongClick = onLongClick)
            .background(if (selectedTab == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = if (selectedTab == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}