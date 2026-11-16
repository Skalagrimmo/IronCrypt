package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IronBorderBright
import com.example.ui.theme.IronPanel
import com.example.ui.theme.MoltenBrass
import kotlin.math.sqrt

/**
 * Multi-touch friendly virtual analog joystick for landscape mobile combat.
 */
@Composable
fun VirtualJoystick(
    onVectorChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(144.dp)
            .testTag("virtual_joystick")
            .pointerInput(Unit) {
                val maxRadius = size.width * 0.34f
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true

                    fun updateFromPos(pos: Offset) {
                        val raw = pos - center
                        val dist = sqrt(raw.x * raw.x + raw.y * raw.y)
                        val clamped = if (dist > maxRadius && dist > 0.001f) {
                            Offset(raw.x / dist * maxRadius, raw.y / dist * maxRadius)
                        } else raw
                        knobOffset = clamped
                        onVectorChange(
                            (clamped.x / maxRadius).coerceIn(-1f, 1f),
                            (clamped.y / maxRadius).coerceIn(-1f, 1f)
                        )
                    }

                    updateFromPos(down.position)
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChange() != Offset.Zero) {
                            updateFromPos(change.position)
                        }
                        change.consume()
                    }
                    isPressed = false
                    knobOffset = Offset.Zero
                    onVectorChange(0f, 0f)
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val outerR = size.minDimension * 0.44f
            val innerR = size.minDimension * 0.19f

            // Outer iron base ring
            drawCircle(
                color = IronPanel.copy(alpha = if (isPressed) 0.65f else 0.42f),
                radius = outerR,
                center = c
            )
            drawCircle(
                color = if (isPressed) MoltenBrass.copy(alpha = 0.85f) else IronBorderBright.copy(alpha = 0.6f),
                radius = outerR,
                center = c,
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Cardinal cross ticks
            val tickLen = 7.dp.toPx()
            val tickCol = IronBorderBright.copy(alpha = 0.45f)
            drawLine(tickCol, Offset(c.x, c.y - outerR + 4f), Offset(c.x, c.y - outerR + tickLen), 2f)
            drawLine(tickCol, Offset(c.x, c.y + outerR - 4f), Offset(c.x, c.y + outerR - tickLen), 2f)
            drawLine(tickCol, Offset(c.x - outerR + 4f, c.y), Offset(c.x - outerR + tickLen, c.y), 2f)
            drawLine(tickCol, Offset(c.x + outerR - 4f, c.y), Offset(c.x + outerR - tickLen, c.y), 2f)

            // Thumb knob
            val knobCenter = c + knobOffset
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                radius = innerR + 2.dp.toPx(),
                center = knobCenter + Offset(0f, 2.dp.toPx())
            )
            drawCircle(
                color = if (isPressed) MoltenBrass else IronBorderBright,
                radius = innerR,
                center = knobCenter
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = innerR * 0.72f,
                center = knobCenter,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
