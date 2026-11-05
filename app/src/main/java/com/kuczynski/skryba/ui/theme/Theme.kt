package com.kuczynski.skryba.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF0F7B6C)
val Accent = Color(0xFFFFC857)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E8DF),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    secondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFDDA8),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    surfaceVariant = Color(0xFFDDE5E2),
    surfaceContainer = Color(0xFFECF1EF),
    surfaceContainerHigh = Color(0xFFE6ECEA),
    surfaceContainerLow = Color(0xFFF1F5F3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DD8C6),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFB8E8DF),
    secondary = Color(0xFFB1CCC5),
    secondaryContainer = Color(0xFF334B46),
    tertiary = Color(0xFFFFB94C),
    tertiaryContainer = Color(0xFF684300),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF3F4946),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B2A),
    surfaceContainerLow = Color(0xFF181D1C),
)

@Composable
fun SkrybaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
