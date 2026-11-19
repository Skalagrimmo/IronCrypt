package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.HudUiState
import com.example.game.model.SpriteId
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronPanel
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.VitalEmerald

@Composable
fun HudTopBar(
    hud: HudUiState,
    atlasStore: AtlasBitmapStore,
    onOpenInventory: () -> Unit,
    onOpenPauseMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Left: Hero portrait, HP/Stamina bars, Gold & Quest Hint
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(IronPanel.copy(alpha = 0.84f))
                    .border(1.5.dp, IronBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = atlasStore.getHeroPortraitBitmap(hud.heroPortraitId),
                    contentDescription = "Dark Hero",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BarMeter(
                        label = "HP ${hud.hp}/${hud.maxHp}",
                        fraction = (hud.hp.toFloat() / hud.maxHp.toFloat()).coerceIn(0f, 1f),
                        fillColor = if (hud.hp < hud.maxHp * 0.35f) CrimsonForge else VitalEmerald,
                        widthDp = 148
                    )
                    BarMeter(
                        label = "SP ${hud.stamina.toInt()}",
                        fraction = (hud.stamina / hud.maxStamina).coerceIn(0f, 1f),
                        fillColor = ArcaneCobalt,
                        widthDp = 148
                    )
                }
                Spacer(Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = atlasStore.getSpriteBitmap(SpriteId.ITEM_COINS),
                        contentDescription = "Gold",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${hud.gold}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MoltenBrass
                    )
                }
            }

            Text(
                text = hud.questHintUk,
                style = MaterialTheme.typography.labelMedium,
                color = AgedParchment,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(IronPanel.copy(alpha = 0.72f))
                    .border(1.dp, IronBorder.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Right: Inventory & Unified Pause Menu Button.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudIconActionButton(
                icon = Icons.Filled.Backpack,
                label = stringResource(R.string.action_inventory),
                tint = MoltenBrass,
                testTag = "inventory_button",
                onClick = onOpenInventory
            )

            HudIconActionButton(
                icon = Icons.Filled.Pause,
                label = stringResource(R.string.action_pause),
                tint = AgedParchment,
                testTag = "pause_button",
                onClick = onOpenPauseMenu
            )
        }
    }
}

@Composable
private fun BarMeter(
    label: String,
    fraction: Float,
    fillColor: Color,
    widthDp: Int
) {
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .border(1.dp, IronBorder, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(14.dp)
                .background(fillColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 5.dp)
        )
    }
}

@Composable
private fun HudIconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(IronPanel.copy(alpha = 0.88f))
            .border(1.5.dp, IronBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AgedParchment
        )
    }
}

/**
 * Discreet diagnostics readout. Kept away from the top-right mini-map controls
 * and bottom-corner movement/combat controls.
 */
@Composable
fun HudTelemetryOverlay(
    hud: HudUiState,
    onOpenGraphicsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tierLabel = if (hud.telemetry.isCustomOverride) {
        "CUSTOM"
    } else {
        hud.telemetry.activeTier.label
    }

    Column(
        modifier = modifier
            .clickable(onClick = onOpenGraphicsTab)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("telemetry_overlay"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = String.format(
                java.util.Locale.US,
                "%d FPS · %.1f ms · %s %d%%",
                hud.telemetry.fps,
                hud.telemetry.frameTimeMs,
                tierLabel,
                (hud.telemetry.resolutionScale * 100).toInt()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = AgedParchment.copy(alpha = 0.42f)
        )
        Text(
            text = "OBJ ${hud.telemetry.activeEntities} · PRT ${hud.telemetry.activeParticles} · DC ${hud.telemetry.drawCalls}",
            style = MaterialTheme.typography.labelSmall,
            color = AgedParchment.copy(alpha = 0.30f)
        )
    }
}
