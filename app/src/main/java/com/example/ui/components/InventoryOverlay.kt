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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.RpgItem
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.CharcoalShadow
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.SteelMist
import com.example.ui.theme.VitalEmerald

@Composable
fun InventoryOverlay(
    hud: HudUiState,
    atlasStore: AtlasBitmapStore,
    onItemClick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalShadow)
            .clickable(onClick = onClose)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IronPanel)
                .border(2.dp, MoltenBrass.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.action_inventory),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MoltenBrass
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ATK ${hud.atkTotal}  •  DEF ${hud.defTotal}  •  ЗОЛОТО ${hud.gold}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AgedParchment
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_inventory_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = AgedParchment
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left column: Equipped slots
                Column(
                    modifier = Modifier.weight(0.44f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_equipment),
                        style = MaterialTheme.typography.titleMedium,
                        color = SteelMist
                    )
                    EquippedSlotRow("ЗБРОЯ", hud.equippedWeapon, atlasStore)
                    EquippedSlotRow("ЩИТ", hud.equippedShield, atlasStore)
                    EquippedSlotRow("ШОЛОМ", hud.equippedHelmet, atlasStore)
                    EquippedSlotRow("ПЛАЩ", hud.equippedCloak, atlasStore)
                }

                // Right column: Backpack items
                Column(
                    modifier = Modifier.weight(0.56f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_backpack),
                        style = MaterialTheme.typography.titleMedium,
                        color = SteelMist
                    )
                    if (hud.backpack.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(IronSurfaceHigh.copy(alpha = 0.5f))
                                .border(1.dp, IronBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Рюкзак порожній. Шукайте скрині та розбивайте бочки.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelMist
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(210.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(hud.backpack, key = { it.id }) { item ->
                                BackpackItemRow(
                                    item = item,
                                    atlasStore = atlasStore,
                                    onClick = { onItemClick(item.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquippedSlotRow(
    slotTitle: String,
    item: RpgItem?,
    atlasStore: AtlasBitmapStore
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item != null) {
            Image(
                bitmap = atlasStore.getSpriteBitmap(item.spriteId),
                contentDescription = item.nameUk,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = item.nameUk,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgedParchment
                )
                Text(
                    text = item.descriptionUk,
                    style = MaterialTheme.typography.labelSmall,
                    color = MoltenBrass
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(IronPanel)
                    .border(1.dp, IronBorder, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$slotTitle — порожньо",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelMist
            )
        }
    }
}

@Composable
private fun BackpackItemRow(
    item: RpgItem,
    atlasStore: AtlasBitmapStore,
    onClick: () -> Unit
) {
    val actionLabel = if (item.type == ItemType.CONSUMABLE) "ВИПИТИ" else "ОДЯГТИ"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("item_row_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Image(
                bitmap = atlasStore.getSpriteBitmap(item.spriteId),
                contentDescription = item.nameUk,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                val qty = if (item.count > 1) " x${item.count}" else ""
                Text(
                    text = "${item.nameUk}$qty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgedParchment
                )
                Text(
                    text = item.descriptionUk,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelMist
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.type == ItemType.CONSUMABLE) VitalEmerald else MoltenBrass,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(IronPanel)
                .border(1.dp, IronBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
