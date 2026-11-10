package com.sdm.plus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Colours taken from the app icon: cyan → violet → magenta on deep navy.
object Neon {
    val Cyan = Color(0xFF22D3FF)
    val Blue = Color(0xFF4F7BFF)
    val Violet = Color(0xFF9B5CFF)
    val Magenta = Color(0xFFE040FB)
    val Pink = Color(0xFFFF7AE6)
    val Night = Color(0xFF070417)
    val Deep = Color(0xFF0E0A26)
    val Panel = Color(0xFF16113A)
    val Panel2 = Color(0xFF1E1750)

    val brush = Brush.horizontalGradient(listOf(Cyan, Violet, Magenta))
    val softBrush = Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.55f), Violet.copy(alpha = 0.55f), Magenta.copy(alpha = 0.55f)))
}

private val NeonScheme = darkColorScheme(
    primary = Neon.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1F7A),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Neon.Cyan,
    onSecondary = Color(0xFF00222C),
    secondaryContainer = Color(0xFF0F3A52),
    onSecondaryContainer = Color(0xFFC9F2FF),
    tertiary = Neon.Magenta,
    onTertiary = Color.White,
    background = Neon.Night,
    onBackground = Color(0xFFECE6FF),
    surface = Neon.Night,
    onSurface = Color(0xFFECE6FF),
    surfaceVariant = Neon.Panel,
    onSurfaceVariant = Color(0xFFB8AEDD),
    surfaceContainerLowest = Neon.Night,
    surfaceContainerLow = Neon.Deep,
    surfaceContainer = Neon.Deep,
    surfaceContainerHigh = Neon.Panel,
    surfaceContainerHighest = Neon.Panel2,
    outline = Color(0xFF4B3F86),
    outlineVariant = Color(0xFF2C2560),
    error = Color(0xFFFF5C8A),
    onError = Color.White,
)

/** Always dark neon, matching the icon. */
@Composable
fun SDMTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeonScheme, content = content)
}

/** Glowing gradient button used for the main actions. */
@Composable
fun NeonButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .shadow(18.dp, shape, ambientColor = Neon.Magenta, spotColor = Neon.Violet)
            .clip(shape)
            .background(Neon.brush)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White)
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** Progress bar with the icon's gradient. */
@Composable
fun NeonProgress(progress: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(Neon.Panel2),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(shape)
                .background(Neon.brush),
        )
    }
}
