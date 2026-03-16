package io.github.martinschneider.taipowergrid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TaipowerBlue = Color(0xFF0061A4)
private val TaipowerBlueDark = Color(0xFF001D36)
private val TaipowerBlueContainer = Color(0xFFD1E4FF)

private val ColorScheme = lightColorScheme(
    primary = TaipowerBlue,
    onPrimary = Color.White,
    primaryContainer = TaipowerBlueContainer,
    onPrimaryContainer = TaipowerBlueDark,
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1C1E),
)

@Composable
fun TaipowerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
