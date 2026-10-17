package dev.ytkara.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Designed for a 1080p TV: the stick reports 960×540 dp. */
object Kara {
    val Background = Color(0xFF0D0B14)
    val Surface = Color(0xFF1A1626)
    val SurfaceHigh = Color(0xFF262036)
    val Text = Color(0xFFF4F1FA)
    val TextDim = Color(0xFFA59DB8)
    val Pink = Color(0xFFFF4FA3)
    val Orange = Color(0xFFFF9A3D)
    val Accent = Brush.horizontalGradient(listOf(Pink, Orange))
}
