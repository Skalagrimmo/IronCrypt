package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.game.model.AffinityEffectiveness
import com.example.game.model.ElementalAffinity
import com.example.game.model.FloatingTextFrameSnapshot
import com.example.game.model.FloatingTextItem
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.FoundryVoid
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.VitalEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun projectWorldToScreen(
    worldX: Float,
    worldY: Float,
    camX: Float,
    camY: Float,
    visibleWorldHeight: Float,
    viewWidthPx: Float,
    viewHeightPx: Float
): Offset {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f || visibleWorldHeight <= 0f) return Offset.Zero
    val halfH = visibleWorldHeight * 0.5f
    val aspect = viewWidthPx / viewHeightPx
    val halfW = halfH * aspect
    val u = (worldX - (camX - halfW)) / (2f * halfW)
    val v = (worldY - (camY - halfH)) / (2f * halfH)
    return Offset(u * viewWidthPx, v * viewHeightPx)
}

internal fun resolveFloatingTextColor(item: FloatingTextItem): Color {
    return when {
        item.isPlayerHit -> if (item.affinity == ElementalAffinity.FIRE) MoltenBrass else CrimsonForge
        item.affinity == ElementalAffinity.HEAL -> VitalEmerald
        item.affinity == ElementalAffinity.FIRE -> MoltenBrass
        item.affinity == ElementalAffinity.ARCANE -> ArcaneCobalt
        item.isCrit -> MoltenBrass
        else -> AgedParchment
    }
}

@Composable
fun FloatingCombatTextOverlay(
    isPlaying: Boolean,
    hasActiveProvider: () -> Boolean,
    snapshotProvider: () -> FloatingTextFrameSnapshot,
    modifier: Modifier = Modifier
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    val textMeasurer = rememberTextMeasurer()

    val normalNumStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    val critNumStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 23.sp,
        fontWeight = FontWeight.ExtraBold
    )
    val badgeStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var wasActive = false
        while (isActive) {
            val activeNow = hasActiveProvider()
            if (activeNow || wasActive) {
                withFrameNanos {
                    frameTick++
                }
                wasActive = activeNow
            } else {
                delay(32L)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("floating_combat_text_canvas")
    ) {
        if (!isPlaying) return@Canvas
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        val snapshot = snapshotProvider()
        if (snapshot.items.isEmpty()) return@Canvas

        for (item in snapshot.items) {
            drawFloatingItem(
                item = item,
                snapshot = snapshot,
                textMeasurer = textMeasurer,
                normalNumStyle = normalNumStyle,
                critNumStyle = critNumStyle,
                badgeStyle = badgeStyle
            )
        }
    }
}

private fun DrawScope.drawFloatingItem(
    item: FloatingTextItem,
    snapshot: FloatingTextFrameSnapshot,
    textMeasurer: TextMeasurer,
    normalNumStyle: TextStyle,
    critNumStyle: TextStyle,
    badgeStyle: TextStyle
) {
    val t = item.progress.coerceIn(0f, 1f)
    val easeOut = 1f - (1f - t) * (1f - t)
    val riseWorld = if (item.isCrit) 1.15f else 0.90f
    val animX = item.worldX + item.driftX * easeOut
    val animY = item.worldY - riseWorld * easeOut

    val screenPos = projectWorldToScreen(
        worldX = animX,
        worldY = animY,
        camX = snapshot.camX,
        camY = snapshot.camY,
        visibleWorldHeight = snapshot.visibleWorldHeight,
        viewWidthPx = size.width,
        viewHeightPx = size.height
    )

    if (screenPos.x < -120f || screenPos.x > size.width + 120f ||
        screenPos.y < -120f || screenPos.y > size.height + 120f
    ) {
        return
    }

    val peakScale = if (item.isCrit) 1.44f else 1.20f
    val settleScale = if (item.isCrit) 1.12f else 1.0f
    val scaleFactor = when {
        t < 0.16f -> {
            val k = t / 0.16f
            0.65f + (peakScale - 0.65f) * k
        }
        t < 0.34f -> {
            val k = (t - 0.16f) / 0.18f
            peakScale + (settleScale - peakScale) * k
        }
        else -> settleScale
    }

    val alpha = if (t < 0.65f) 1.0f else ((1f - t) / 0.35f).coerceIn(0f, 1f)
    val primaryColor = resolveFloatingTextColor(item)
    val outlineColor = FoundryVoid.copy(alpha = alpha * 0.95f)

    val numStyle = (if (item.isCrit) critNumStyle else normalNumStyle)
    val numLayout = textMeasurer.measure(text = item.amountText, style = numStyle)
    val numW = numLayout.size.width.toFloat()
    val numH = numLayout.size.height.toFloat()

    scale(scaleX = scaleFactor, scaleY = scaleFactor, pivot = screenPos) {
        if (item.isCrit && t < 0.36f) {
            val burstAlpha = ((0.36f - t) / 0.36f) * 0.55f
            val r = (numW * 0.52f).coerceAtLeast(18f)
            val diamond = Path().apply {
                moveTo(screenPos.x, screenPos.y - r * 0.7f)
                lineTo(screenPos.x + r, screenPos.y)
                lineTo(screenPos.x, screenPos.y + r * 0.7f)
                lineTo(screenPos.x - r, screenPos.y)
                close()
            }
            drawPath(path = diamond, color = primaryColor.copy(alpha = burstAlpha))
        }

        val topLeft = Offset(screenPos.x - numW * 0.5f, screenPos.y - numH * 0.5f)
        drawOutlinedText(
            layout = numLayout,
            topLeft = topLeft,
            fillColor = primaryColor.copy(alpha = alpha),
            outlineColor = outlineColor,
            offsetPx = 2.0f
        )

        if (item.tagText.isNotEmpty()) {
            val tagColor = when {
                item.effectiveness == AffinityEffectiveness.WEAKNESS -> MoltenBrass
                item.effectiveness == AffinityEffectiveness.RESISTANT -> AgedParchment.copy(alpha = 0.78f)
                item.isCrit -> AgedParchment
                else -> primaryColor
            }
            val tagLayout = textMeasurer.measure(text = item.tagText, style = badgeStyle)
            val tagTopLeft = Offset(
                screenPos.x - tagLayout.size.width * 0.5f,
                topLeft.y - tagLayout.size.height - 2f
            )
            drawOutlinedText(
                layout = tagLayout,
                topLeft = tagTopLeft,
                fillColor = tagColor.copy(alpha = alpha),
                outlineColor = outlineColor,
                offsetPx = 1.5f
            )
        }
    }
}

private fun DrawScope.drawOutlinedText(
    layout: androidx.compose.ui.text.TextLayoutResult,
    topLeft: Offset,
    fillColor: Color,
    outlineColor: Color,
    offsetPx: Float
) {
    drawText(layout, color = outlineColor, topLeft = Offset(topLeft.x - offsetPx, topLeft.y))
    drawText(layout, color = outlineColor, topLeft = Offset(topLeft.x + offsetPx, topLeft.y))
    drawText(layout, color = outlineColor, topLeft = Offset(topLeft.x, topLeft.y - offsetPx))
    drawText(layout, color = outlineColor, topLeft = Offset(topLeft.x, topLeft.y + offsetPx))
    drawText(layout, color = fillColor, topLeft = topLeft)
}
