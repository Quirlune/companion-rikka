package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Three dots circle locally while their orbital plane rotates in three dimensions. */
@Composable
fun CompanionOrbitLoading(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "CompanionOrbit")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "CompanionOrbitPhase",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val orbitRadius = min(size.width, size.height) * 0.33f
        val dotRadius = 2.4.dp.toPx()
        val tilt = phase + 0.6f
        val yaw = 0.55f * sin(phase)
        val roll = 0.4f * sin(phase * 2f)
        val dots = (0..2).map { index ->
            val angle = phase * 2f + index * (2 * PI / 3).toFloat()
            val u = cos(angle)
            val v = sin(angle)
            val y1 = v * cos(tilt)
            val z1 = v * sin(tilt)
            val x2 = u * cos(yaw) + z1 * sin(yaw)
            val z = -u * sin(yaw) + z1 * cos(yaw)
            val x = x2 * cos(roll) - y1 * sin(roll)
            val y = x2 * sin(roll) + y1 * cos(roll)
            val perspective = 1f / (1f - 0.1f * z)
            OrbitDot(x * perspective, y * perspective, z, perspective)
        }.sortedBy { it.z }
        dots.forEach { dot ->
            drawCircle(
                color = color,
                radius = dotRadius * dot.perspective * (1f + dot.z * 0.22f),
                center = Offset(
                    size.width / 2f + orbitRadius * dot.x,
                    size.height / 2f + orbitRadius * dot.y,
                ),
                alpha = (0.8f + dot.z * 0.165f).coerceIn(0f, 1f),
            )
        }
    }
}

private data class OrbitDot(val x: Float, val y: Float, val z: Float, val perspective: Float)
