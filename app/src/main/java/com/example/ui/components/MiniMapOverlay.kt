package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.model.MiniMapDisplayMode
import com.example.game.model.MiniMapMarker
import com.example.game.model.MiniMapMarkerKind
import com.example.game.model.MiniMapState
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.FoundryVoid
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronBorderBright
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.SteelMist
import com.example.ui.theme.VitalEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiniMapOverlay(
    miniMap: MiniMapState,
    mode: MiniMapDisplayMode,
    onToggleExpand: () -> Unit,
    onMinimize: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulseTransition = rememberInfiniteTransition(label = "minimap_pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "minimap_pulse_alpha"
    )

    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= 780.dp
        val compactMapWidth = if (isWide) 174.dp else 144.dp
        val maxSafeExpandedWidth = (maxWidth * 0.44f).coerceAtLeast(220.dp)
        val maxSafeExpandedByHeight = ((maxHeight - 88.dp) * 1.48f).coerceAtLeast(180.dp)
        val expandedMapWidth = minOf(
            if (isWide) 348.dp else 282.dp,
            maxSafeExpandedWidth,
            maxSafeExpandedByHeight
        )

        if (mode == MiniMapDisplayMode.MINIMIZED) {
            MinimizedMiniMapPill(
                discoveredPercent = miniMap.discoveredPercent,
                hasPrimaryObjective = miniMap.markers.any { it.isPrimaryQuest },
                pulse = pulse,
                onClick = onRestore
            )
        } else {
            val targetWidth: Dp = if (mode == MiniMapDisplayMode.EXPANDED) expandedMapWidth else compactMapWidth
            val animatedWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "minimap_width"
            )
            val isExpanded = mode == MiniMapDisplayMode.EXPANDED

            Column(
                modifier = Modifier
                    .width(animatedWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IronPanel.copy(alpha = 0.74f))
                    .border(
                        width = 1.5.dp,
                        color = if (isExpanded) MoltenBrass.copy(alpha = 0.82f) else IronBorder.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .testTag(if (isExpanded) "minimap_expanded_card" else "minimap_compact_card")
            ) {
                // Header bar with discovery % and 48dp-safe touch buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FoundryVoid.copy(alpha = 0.55f))
                        .padding(start = 8.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isExpanded) {
                            "${stringResource(R.string.label_minimap_title)} • ${miniMap.discoveredPercent}%"
                        } else {
                            "${stringResource(R.string.label_minimap_short)} ${miniMap.discoveredPercent}%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExpanded) MoltenBrass else AgedParchment,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleExpand)
                            .padding(4.dp)
                            .testTag("minimap_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                            contentDescription = stringResource(
                                if (isExpanded) R.string.action_minimap_collapse else R.string.action_minimap_expand
                            ),
                            tint = MoltenBrass,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onMinimize)
                            .padding(4.dp)
                            .testTag("minimap_minimize_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ExpandLess,
                            contentDescription = stringResource(R.string.action_minimap_minimize),
                            tint = SteelMist,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Vector mini-map canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FoundryVoid.copy(alpha = 0.62f))
                        .border(1.dp, IronBorder.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleExpand)
                        .testTag("minimap_canvas")
                ) {
                    val terrainPaths = remember(
                        miniMap.mapWidth,
                        miniMap.mapHeight,
                        miniMap.terrainLayout,
                        miniMap.discoveredMask
                    ) {
                        buildMiniMapTerrainPaths(miniMap)
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(miniMap.mapWidth.toFloat() / miniMap.mapHeight.toFloat())
                    ) {
                        drawMiniMap(
                            miniMap = miniMap,
                            pulse = pulse,
                            isExpanded = isExpanded,
                            terrainPaths = terrainPaths
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FoundryVoid.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("minimap_legend"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LegendItem(color = MoltenBrass, label = "Важіль")
                        LegendItem(
                            color = if (miniMap.gateUnlocked) VitalEmerald else CrimsonForge,
                            label = if (miniMap.gateUnlocked) "Брама (відкр.)" else "Брама"
                        )
                        LegendItem(color = CrimsonForge, label = "Бос")
                        LegendItem(color = ArcaneCobalt, label = "NPC")
                        LegendItem(color = AgedParchment, label = "Скриня")
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimizedMiniMapPill(
    discoveredPercent: Int,
    hasPrimaryObjective: Boolean,
    pulse: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(IronPanel.copy(alpha = 0.78f))
            .border(1.5.dp, IronBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("minimap_minimized_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Map,
            contentDescription = stringResource(R.string.action_minimap_expand),
            tint = MoltenBrass,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "${stringResource(R.string.label_minimap_short)} $discoveredPercent%",
            style = MaterialTheme.typography.labelMedium,
            color = AgedParchment
        )
        if (hasPrimaryObjective) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MoltenBrass.copy(alpha = pulse))
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SteelMist
        )
    }
}

private data class MiniMapTerrainPaths(
    val floors: Path,
    val grates: Path,
    val walls: Path,
    val lockedGates: Path,
    val openGates: Path,
    val fog: Path
)

private fun buildMiniMapTerrainPaths(miniMap: MiniMapState): MiniMapTerrainPaths {
    val cols = miniMap.mapWidth.coerceAtLeast(1)
    val rows = miniMap.mapHeight.coerceAtLeast(1)
    val total = cols * rows
    val terrain = miniMap.terrainLayout
    val mask = miniMap.discoveredMask
    val hasTerrain = terrain.length == total
    val hasMask = mask.length == total

    val paths = MiniMapTerrainPaths(
        floors = Path(),
        grates = Path(),
        walls = Path(),
        lockedGates = Path(),
        openGates = Path(),
        fog = Path()
    )

    for (index in 0 until total) {
        val x = (index % cols).toFloat()
        val y = (index / cols).toFloat()
        val discovered = !hasMask || mask[index] == '1'
        val target = if (!discovered) {
            paths.fog
        } else {
            when (if (hasTerrain) terrain[index] else 'F') {
                'W' -> paths.walls
                'L' -> paths.lockedGates
                'O' -> paths.openGates
                'S' -> paths.grates
                else -> paths.floors
            }
        }

        // Unit-space rectangles are cached between player-marker pulse frames.
        target.moveTo(x, y)
        target.lineTo(x + 1.01f, y)
        target.lineTo(x + 1.01f, y + 1.01f)
        target.lineTo(x, y + 1.01f)
        target.close()
    }

    return paths
}

private fun DrawScope.drawMiniMap(
    miniMap: MiniMapState,
    pulse: Float,
    isExpanded: Boolean,
    terrainPaths: MiniMapTerrainPaths
) {
    val cols = miniMap.mapWidth.coerceAtLeast(1)
    val rows = miniMap.mapHeight.coerceAtLeast(1)
    val tileW = size.width / cols.toFloat()
    val tileH = size.height / rows.toFloat()

    val floorColor = IronSurfaceHigh.copy(alpha = 0.84f)
    val grateColor = SteelMist.copy(alpha = 0.42f)
    val wallColor = IronBorderBright.copy(alpha = 0.90f)
    val lockedGateColor = CrimsonForge.copy(alpha = 0.92f)
    val openGateColor = VitalEmerald.copy(alpha = 0.85f)
    val fogColor = FoundryVoid.copy(alpha = 0.86f)

    // Draw the full terrain as a handful of cached paths instead of thousands of
    // individual rectangles on every animated mini-map frame.
    scale(scaleX = tileW, scaleY = tileH, pivot = Offset.Zero) {
        drawPath(terrainPaths.floors, color = floorColor)
        drawPath(terrainPaths.grates, color = grateColor)
        drawPath(terrainPaths.walls, color = wallColor)
        drawPath(terrainPaths.lockedGates, color = lockedGateColor)
        drawPath(terrainPaths.openGates, color = openGateColor)
        drawPath(terrainPaths.fog, color = fogColor)
    }

    // Active quest markers & discovered points of interest.
    val baseRadius = (minOf(tileW, tileH) * (if (isExpanded) 0.58f else 0.72f)).coerceAtLeast(2.2f)
    for (marker in miniMap.markers) {
        drawMarker(marker, tileW, tileH, baseRadius, pulse)
    }

    // Player indicator (pulsing halo + directional chevron).
    val px = miniMap.playerX * tileW
    val py = miniMap.playerY * tileH
    val playerSize = (minOf(tileW, tileH) * (if (isExpanded) 0.82f else 1.0f)).coerceAtLeast(3.2f)

    drawCircle(
        color = VitalEmerald.copy(alpha = 0.30f * pulse),
        radius = playerSize * 1.9f,
        center = Offset(px, py)
    )
    drawCircle(
        color = VitalEmerald,
        radius = playerSize * 1.15f,
        center = Offset(px, py)
    )

    val angleDeg = miniMap.playerFacing.miniMapAngleDeg
    rotate(degrees = angleDeg, pivot = Offset(px, py)) {
        val path = Path().apply {
            moveTo(px + playerSize * 1.15f, py)
            lineTo(px - playerSize * 0.75f, py - playerSize * 0.72f)
            lineTo(px - playerSize * 0.35f, py)
            lineTo(px - playerSize * 0.75f, py + playerSize * 0.72f)
            close()
        }
        drawPath(path = path, color = AgedParchment)
    }
}

private fun DrawScope.drawMarker(
    marker: MiniMapMarker,
    tileW: Float,
    tileH: Float,
    radius: Float,
    pulse: Float
) {
    val cx = marker.x * tileW
    val cy = marker.y * tileH

    if (marker.isPrimaryQuest) {
        val ringColor = if (marker.kind == MiniMapMarkerKind.LEVER || marker.kind == MiniMapMarkerKind.GATE_LOCKED) {
            MoltenBrass
        } else {
            CrimsonForge
        }
        drawCircle(
            color = ringColor.copy(alpha = 0.45f * pulse),
            radius = radius * 1.85f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.6f)
        )
    }

    when (marker.kind) {
        MiniMapMarkerKind.LEVER -> {
            drawCircle(color = MoltenBrass, radius = radius * 1.05f, center = Offset(cx, cy))
            drawCircle(color = AgedParchment, radius = radius * 0.45f, center = Offset(cx, cy))
        }
        MiniMapMarkerKind.GATE_LOCKED -> {
            drawRect(
                color = CrimsonForge,
                topLeft = Offset(cx - radius * 0.65f, cy - radius * 1.35f),
                size = Size(radius * 1.3f, radius * 2.7f)
            )
            drawRect(
                color = MoltenBrass,
                topLeft = Offset(cx - radius * 0.65f, cy - radius * 1.35f),
                size = Size(radius * 1.3f, radius * 2.7f),
                style = Stroke(width = 1.2f)
            )
        }
        MiniMapMarkerKind.GATE_OPEN -> {
            drawRect(
                color = VitalEmerald,
                topLeft = Offset(cx - radius * 0.55f, cy - radius * 1.15f),
                size = Size(radius * 1.1f, radius * 2.3f),
                style = Stroke(width = 1.6f)
            )
        }
        MiniMapMarkerKind.BOSS -> {
            val d = radius * 1.25f
            val path = Path().apply {
                moveTo(cx, cy - d)
                lineTo(cx + d, cy)
                lineTo(cx, cy + d)
                lineTo(cx - d, cy)
                close()
            }
            drawPath(path = path, color = CrimsonForge)
            drawPath(path = path, color = MoltenBrass, style = Stroke(width = 1.2f))
        }
        MiniMapMarkerKind.NPC -> {
            drawCircle(color = ArcaneCobalt, radius = radius * 0.95f, center = Offset(cx, cy))
            drawCircle(color = AgedParchment, radius = radius * 0.4f, center = Offset(cx, cy))
        }
        MiniMapMarkerKind.CHEST -> {
            val half = radius * 0.85f
            drawRect(
                color = AgedParchment,
                topLeft = Offset(cx - half, cy - half),
                size = Size(half * 2f, half * 2f)
            )
            drawRect(
                color = MoltenBrass,
                topLeft = Offset(cx - half, cy - half),
                size = Size(half * 2f, half * 2f),
                style = Stroke(width = 1.2f)
            )
        }
    }
}
