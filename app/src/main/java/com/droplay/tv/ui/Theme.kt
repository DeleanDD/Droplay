package com.droplay.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val Navy = Color(0xFF05081B)
val Surface = Color(0xFF10152E)
val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val Coral = Color(0xFFFF6670)
val Muted = Color(0xFF9AA4C0)

@Composable
fun DroplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet, secondary = Cyan, tertiary = Coral,
            background = Navy, surface = Surface, onBackground = Color.White, onSurface = Color.White,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
