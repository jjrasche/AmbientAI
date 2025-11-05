package com.ambientai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    // Subtle purple for transcripts with high contrast text
    primary = Color(0xFF5E35B1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE7F6),
    onPrimaryContainer = Color(0xFF311B92),

    // Teal for secondary actions
    secondary = Color(0xFF00796B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2F1),
    onSecondaryContainer = Color(0xFF004D40),

    // Muted purple for LLM interactions
    tertiary = Color(0xFF7E57C2),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E5F5),
    onTertiaryContainer = Color(0xFF4A148C),

    // Clear error red with good contrast
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C),

    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF424242),

    outline = Color(0xFF9E9E9E)
)

private val DarkColorScheme = darkColorScheme(
    // Subtle purple for transcripts with high contrast
    primary = Color(0xFFB39DDB),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A1A4A),
    onPrimaryContainer = Color(0xFFE8E0F5),

    // Teal for secondary actions
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A3D3A),
    onSecondaryContainer = Color(0xFFB2DFDB),

    // Muted purple for LLM interactions
    tertiary = Color(0xFFCE93D8),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3A2448),
    onTertiaryContainer = Color(0xFFF3E5F5),

    // Clear error red
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A1C1C),
    onErrorContainer = Color(0xFFFFCDD2),

    background = Color(0xFF121212),
    onBackground = Color(0xFFE8E8E8),

    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC0C0C0),

    outline = Color(0xFF757575)
)

@Composable
fun AmbientAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}