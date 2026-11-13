package com.kuma.motointercom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal data class SonarTarget(
    val angleDegrees: Float,
    val distanceFraction: Float,
    val radiusFraction: Float
)

internal data class SonarMotion(val waveFraction: Float = 0f, val pulse: Float = 0f)

@Composable
internal fun rememberSonarMotion(active: Boolean, targetIds: List<String>): SonarMotion {
    if (!active) return SonarMotion()
    return key(targetIds) {
        val transition = rememberInfiniteTransition(label = "active_sonar")
        val cycle = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "sonar_ping"
        ).value
        SonarMotion(
            waveFraction = 0.14f + cycle * 1.6f,
            pulse = (1f - cycle / 0.18f).coerceAtLeast(0f).pow(1.6f)
        )
    }
}

internal fun sonarEchoEnergy(waveFraction: Float, target: SonarTarget): Float {
    val entryFraction = (target.distanceFraction - target.radiusFraction).coerceAtLeast(0.01f)
    val travelFraction = waveFraction - entryFraction
    if (waveFraction <= 0f || travelFraction < 0f || travelFraction >= entryFraction) return 0f
    return (1f - travelFraction / entryFraction).pow(1.45f)
}

internal fun sonarTransmission(
    angleRadians: Float,
    waveFraction: Float,
    targets: List<SonarTarget>
): Float {
    var transmission = 1f
    for (target in targets) {
        if (waveFraction <= target.distanceFraction) continue
        val halfAngle = asin((target.radiusFraction / target.distanceFraction).coerceIn(0f, 0.95f))
        if (halfAngle <= 0f) continue
        val targetAngle = target.angleDegrees * PI.toFloat() / 180f
        val difference = abs(atanAngleDifference(angleRadians, targetAngle))
        val penumbra = ((difference / halfAngle - 0.78f) / 0.44f).coerceIn(0f, 1f)
        transmission *= 0.06f + penumbra * 0.94f
    }
    return transmission
}

private fun atanAngleDifference(first: Float, second: Float): Float {
    val fullTurn = 2f * PI.toFloat()
    var difference = (first - second + PI.toFloat()) % fullTurn
    if (difference < 0f) difference += fullTurn
    return difference - PI.toFloat()
}

internal fun DrawScope.drawActiveSonar(
    origin: Offset,
    scopeRadius: Float,
    sourceRadius: Float,
    waves: List<Float>,
    pulse: Float,
    targets: List<SonarTarget>,
    accent: Color
) {
    if (scopeRadius <= 0f) return
    for (target in targets) {
        val reach = waves.filter { it > target.distanceFraction }.maxOrNull() ?: continue
        val startRadius = target.distanceFraction * scopeRadius
        val endRadius = reach.coerceAtMost(1.04f) * scopeRadius
        if (endRadius <= startRadius) continue
        val halfAngle = asin((target.radiusFraction / target.distanceFraction).coerceIn(0f, 0.95f))
        val spreadDegrees = halfAngle * 180f / PI.toFloat()
        val shadowPath = Path().apply {
            arcTo(
                Rect(origin.x - startRadius, origin.y - startRadius, origin.x + startRadius, origin.y + startRadius),
                target.angleDegrees - spreadDegrees,
                spreadDegrees * 2f,
                true
            )
            arcTo(
                Rect(origin.x - endRadius, origin.y - endRadius, origin.x + endRadius, origin.y + endRadius),
                target.angleDegrees + spreadDegrees,
                -spreadDegrees * 2f,
                false
            )
            close()
        }
        val angle = target.angleDegrees * PI.toFloat() / 180f
        val fade = (1f - (reach - target.distanceFraction) / 0.8f).coerceIn(0f, 1f)
        drawPath(
            shadowPath,
            Brush.linearGradient(
                listOf(Color(0xFF020604).copy(alpha = 0.85f * fade), Color.Transparent),
                start = origin + Offset(cos(angle), sin(angle)) * startRadius,
                end = origin + Offset(cos(angle), sin(angle)) * endRadius
            )
        )
    }

    if (pulse > 0.01f) {
        drawCircle(
            color = accent.copy(alpha = pulse * 0.48f),
            radius = sourceRadius + (1f - pulse) * 16.dp.toPx(),
            center = origin,
            style = Stroke((1.2f + pulse).dp.toPx())
        )
    }

    for (wave in waves) {
        for (harmonic in 0..2) {
            val waveRadius = wave - harmonic * 0.055f
            if (waveRadius <= 0.14f || waveRadius > 1.08f) continue
            val attenuation = (1f - (waveRadius / 1.12f).pow(1.8f)).coerceIn(0f, 1f)
            val intensity = attenuation * when (harmonic) {
                0 -> 0.84f
                1 -> 0.42f
                else -> 0.22f
            }
            val radius = waveRadius * scopeRadius
            val stroke = (if (harmonic == 0) 1.8.dp else 1.dp).toPx()
            if (targets.isEmpty()) {
                drawCircle(accent.copy(alpha = intensity), radius, origin, style = Stroke(stroke))
            } else {
                repeat(96) { segment ->
                    val angle = (segment + 0.5f) * 2f * PI.toFloat() / 96f
                    val transmission = sonarTransmission(angle, waveRadius, targets)
                    drawArc(
                        color = accent.copy(alpha = intensity * transmission),
                        startAngle = segment * 3.75f,
                        sweepAngle = 4.1f,
                        useCenter = false,
                        topLeft = origin - Offset(radius, radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke)
                    )
                }
            }
        }

        for (target in targets) {
            val energy = sonarEchoEnergy(wave, target)
            if (energy <= 0f) continue
            val entry = (target.distanceFraction - target.radiusFraction).coerceAtLeast(0.01f)
            val travel = wave - entry
            val angle = target.angleDegrees * PI.toFloat() / 180f
            val targetCenter = origin + Offset(cos(angle), sin(angle)) * (target.distanceFraction * scopeRadius)
            val targetRadius = target.radiusFraction * scopeRadius
            val flash = (1f - travel / 0.2f).coerceAtLeast(0f).pow(1.3f)
            if (flash > 0f) {
                val radius = targetRadius + 2.dp.toPx()
                drawArc(
                    color = Color(0xFFE8FFC3).copy(alpha = flash * 0.95f),
                    startAngle = target.angleDegrees + 128f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = targetCenter - Offset(radius, radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            for (reflection in 0..1) {
                val reflectedTravel = travel - reflection * 0.055f
                if (reflectedTravel < 0f) continue
                val radius = targetRadius + reflectedTravel * scopeRadius
                drawArc(
                    color = Color(0xFFCDFA91).copy(alpha = energy * if (reflection == 0) 0.76f else 0.38f),
                    startAngle = target.angleDegrees + 142f,
                    sweepAngle = 76f,
                    useCenter = false,
                    topLeft = targetCenter - Offset(radius, radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke((if (reflection == 0) 1.6.dp else 1.dp).toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
