package com.omnidroid.app.mobile.shared.compose.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

val YunoDarkBackground = Color(0xFF1D1B1E)
val YunoPurplePrimary = Color(0xFFE6B4FF)
val YunoPurpleContainer = Color(0xFF6E119F)
val YunoOnPrimaryContainer = Color(0xFFF5D9FF)
val YunoSurfaceVariant = Color(0xFF4B444D)
val YunoFavoriteRed = Color(0xFFE53935)
val YunoStarYellow = Color(0xFFFFB300)

/**
 * Animated floating stroke bubbles matching YuNO's signature aesthetic.
 */
@Composable
fun YunoBubbleBackground(
    modifier: Modifier = Modifier,
    bubbleColor: Color = Color(0xFF8834B9).copy(alpha = 0.22f),
) {
    val infiniteTransition = rememberInfiniteTransition(label = "yunoBubbles")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bubblesMotion",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val bubbles = listOf(
            Triple(0.08f, 0.25f, 130f),
            Triple(0.32f, 0.65f, 210f),
            Triple(0.55f, 0.18f, 170f),
            Triple(0.78f, 0.72f, 250f),
            Triple(0.92f, 0.32f, 120f),
            Triple(0.45f, 0.42f, 290f),
            Triple(0.20f, 0.88f, 150f),
            Triple(0.85f, 0.12f, 95f),
            Triple(0.68f, 0.90f, 190f),
        )

        bubbles.forEachIndexed { i, (relX, relY, radiusDp) ->
            val phase = (animOffset + i * 0.11f) % 1f
            val rad = phase * 2f * Math.PI.toFloat()
            val offsetY = sin(rad) * 35f
            val offsetX = cos(rad) * 25f
            val cx = relX * w + offsetX
            val cy = relY * h + offsetY
            val r = radiusDp * density

            drawCircle(
                color = bubbleColor,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 7f * density),
            )
        }
    }
}
