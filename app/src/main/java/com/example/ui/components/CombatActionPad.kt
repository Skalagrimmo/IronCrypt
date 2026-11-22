package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.SpriteId
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.IronBorderBright
import com.example.ui.theme.IronPanel
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.VitalEmerald

@Composable
fun CombatActionPad(
    atlasStore: AtlasBitmapStore,
    cleaveCooldownRatio: Float,
    dashCooldownRatio: Float,
    potionCount: Int,
    interactPromptUk: String?,
    onAttack: () -> Unit,
    onCleave: () -> Unit,
    onDash: () -> Unit,
    onPotion: () -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Context-sensitive Interact chip when standing near Chest, Lever, or NPC
        AnimatedVisibility(
            visible = interactPromptUk != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MoltenBrass.copy(alpha = 0.92f))
                    .border(2.dp, AgedParchment, RoundedCornerShape(8.dp))
                    .clickable(onClick = onInteract)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("interact_button"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = stringResource(R.string.action_interact),
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = interactPromptUk ?: stringResource(R.string.action_interact),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionCircleButton(
                size = 54.dp,
                ringColor = if (potionCount > 0) VitalEmerald else IronBorderBright,
                cooldownRatio = 0f,
                label = "$potionCount",
                spriteBitmap = atlasStore.getSpriteBitmap(SpriteId.ITEM_POTION),
                contentDescription = stringResource(R.string.action_potion),
                testTag = "potion_button",
                onClick = onPotion
            )
            ActionCircleButton(
                size = 58.dp,
                ringColor = CrimsonForge,
                cooldownRatio = cleaveCooldownRatio,
                label = stringResource(R.string.action_cleave),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.action_cleave),
                        tint = AgedParchment,
                        modifier = Modifier.size(24.dp)
                    )
                },
                contentDescription = stringResource(R.string.action_cleave),
                testTag = "cleave_button",
                onClick = onCleave
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ActionCircleButton(
                size = 58.dp,
                ringColor = ArcaneCobalt,
                cooldownRatio = dashCooldownRatio,
                label = stringResource(R.string.action_dash),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = stringResource(R.string.action_dash),
                        tint = AgedParchment,
                        modifier = Modifier.size(24.dp)
                    )
                },
                contentDescription = stringResource(R.string.action_dash),
                testTag = "dash_button",
                onClick = onDash
            )

            ActionCircleButton(
                size = 78.dp,
                ringColor = MoltenBrass,
                cooldownRatio = 0f,
                label = stringResource(R.string.action_attack),
                spriteBitmap = atlasStore.getSpriteBitmap(SpriteId.ITEM_SWORD),
                contentDescription = stringResource(R.string.action_attack),
                testTag = "attack_button",
                onClick = onAttack
            )
        }
    }
}

@Composable
private fun ActionCircleButton(
    size: Dp,
    ringColor: Color,
    cooldownRatio: Float,
    label: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    spriteBitmap: ImageBitmap? = null,
    icon: (@Composable () -> Unit)? = null
) {
    // Keep the original full-size hit target while shrinking only the visible control.
    val visualSize = size * 0.82f
    Box(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .background(IronPanel.copy(alpha = 0.80f))
                .border(2.5.dp, ringColor.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (spriteBitmap != null) {
                    Image(
                        bitmap = spriteBitmap,
                        contentDescription = contentDescription,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(visualSize * 0.44f)
                    )
                } else if (icon != null) {
                    icon()
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AgedParchment
                )
            }

            if (cooldownRatio > 0.01f) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawArc(
                        color = Color.Black.copy(alpha = 0.65f),
                        startAngle = -90f,
                        sweepAngle = 360f * cooldownRatio,
                        useCenter = true
                    )
                }
            }
        }
    }
}
