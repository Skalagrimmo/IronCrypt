package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.ElementalAffinity
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.RpgItem
import com.example.game.model.SpriteId
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CharcoalShadow
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronBorderBright
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.SteelMist
import com.example.ui.theme.VitalEmerald
import com.example.ui.theme.VitalEmeraldDark
import kotlin.math.roundToInt

enum class InventoryFilterTab {
    ALL,
    GEAR,
    POTIONS
}

sealed interface InventorySelection {
    data class BackpackItem(val itemId: String) : InventorySelection
    data class EquippedSlot(val slotType: ItemType) : InventorySelection
}

private sealed interface DragOrigin {
    data class Backpack(val itemId: String) : DragOrigin
    data class Equipped(val slotType: ItemType) : DragOrigin
}

private sealed interface DropTarget {
    data object HeroConsume : DropTarget
    data class EquipSlot(val slotType: ItemType) : DropTarget
    data class ReorderBackpack(val targetItemId: String) : DropTarget
    data object UnequipToPack : DropTarget
}

@Stable
private class InventoryDragController {
    var activeItem by mutableStateOf<RpgItem?>(null)
    var origin by mutableStateOf<DragOrigin?>(null)
    var pointerWindowPos by mutableStateOf(Offset.Zero)
    var rootWindowOrigin by mutableStateOf(Offset.Zero)

    var heroDropBounds by mutableStateOf<Rect?>(null)
    var backpackGridBounds by mutableStateOf<Rect?>(null)
    val equipSlotBounds = mutableStateMapOf<ItemType, Rect>()
    val backpackItemBounds = mutableStateMapOf<String, Rect>()

    fun startDrag(item: RpgItem, dragOrigin: DragOrigin, startWindowPos: Offset) {
        activeItem = item
        origin = dragOrigin
        pointerWindowPos = startWindowPos
    }

    fun updateDrag(delta: Offset) {
        pointerWindowPos += delta
    }

    fun clear() {
        activeItem = null
        origin = null
    }

    fun currentDropTarget(): DropTarget? {
        val item = activeItem ?: return null
        val src = origin ?: return null
        val pos = pointerWindowPos

        return when (src) {
            is DragOrigin.Backpack -> {
                if (item.type == ItemType.CONSUMABLE && heroDropBounds?.contains(pos) == true) {
                    DropTarget.HeroConsume
                } else if (isGearType(item.type) && equipSlotBounds[item.type]?.contains(pos) == true) {
                    DropTarget.EquipSlot(item.type)
                } else {
                    val targetEntry = backpackItemBounds.entries.firstOrNull { (id, rect) ->
                        id != src.itemId && rect.contains(pos)
                    }
                    if (targetEntry != null) {
                        DropTarget.ReorderBackpack(targetEntry.key)
                    } else {
                        null
                    }
                }
            }
            is DragOrigin.Equipped -> {
                if (backpackGridBounds?.contains(pos) == true) {
                    DropTarget.UnequipToPack
                } else {
                    null
                }
            }
        }
    }
}

private fun isGearType(type: ItemType): Boolean =
    type == ItemType.WEAPON || type == ItemType.SHIELD || type == ItemType.HELMET || type == ItemType.CLOAK

@Composable
fun InventoryOverlay(
    hud: HudUiState,
    atlasStore: AtlasBitmapStore,
    onEquipOrUse: (String) -> Unit,
    onUnequipSlot: (ItemType) -> Unit,
    onMoveItem: (fromItemId: String, toItemId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeFilter by remember { mutableStateOf(InventoryFilterTab.ALL) }
    var selection by remember {
        mutableStateOf<InventorySelection?>(
            hud.backpack.firstOrNull()?.let { InventorySelection.BackpackItem(it.id) }
                ?: hud.equippedWeapon?.let { InventorySelection.EquippedSlot(ItemType.WEAPON) }
        )
    }

    val dragController = remember { InventoryDragController() }
    val latestEquipOrUse by rememberUpdatedState(onEquipOrUse)
    val latestUnequipSlot by rememberUpdatedState(onUnequipSlot)
    val latestMoveItem by rememberUpdatedState(onMoveItem)

    val filteredItems = remember(hud.backpack, activeFilter) {
        when (activeFilter) {
            InventoryFilterTab.ALL -> hud.backpack
            InventoryFilterTab.GEAR -> hud.backpack.filter { isGearType(it.type) }
            InventoryFilterTab.POTIONS -> hud.backpack.filter { it.type == ItemType.CONSUMABLE }
        }
    }

    val gearCount = remember(hud.backpack) { hud.backpack.count { isGearType(it.type) } }
    val potionCount = remember(hud.backpack) { hud.backpack.count { it.type == ItemType.CONSUMABLE } }

    // Resolve inspector item cleanly even after equipping/consuming/unequipping
    val inspectedPair: Pair<RpgItem, Boolean>? = remember(selection, hud) {
        when (val sel = selection) {
            is InventorySelection.BackpackItem -> {
                hud.backpack.firstOrNull { it.id == sel.itemId }?.let { it to false }
            }
            is InventorySelection.EquippedSlot -> {
                equippedForSlot(hud, sel.slotType)?.let { it to true }
            }
            null -> null
        } ?: hud.backpack.firstOrNull()?.let { it to false }
            ?: hud.equippedWeapon?.let { it to true }
    }

    val hoveredTarget = dragController.currentDropTarget()
    val draggedItem = dragController.activeItem
    val draggedOrigin = dragController.origin

    fun handleDropCommit() {
        val item = dragController.activeItem
        val target = dragController.currentDropTarget()
        dragController.clear()
        if (item == null || target == null) return

        when (target) {
            DropTarget.HeroConsume -> latestEquipOrUse(item.id)
            is DropTarget.EquipSlot -> {
                latestEquipOrUse(item.id)
                selection = InventorySelection.EquippedSlot(target.slotType)
            }
            is DropTarget.ReorderBackpack -> {
                latestMoveItem(item.id, target.targetItemId)
                selection = InventorySelection.BackpackItem(item.id)
            }
            DropTarget.UnequipToPack -> {
                latestUnequipSlot(item.type)
                selection = InventorySelection.BackpackItem(item.id)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                dragController.rootWindowOrigin = coords.boundsInWindow().topLeft
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CharcoalShadow)
                .clickable(onClick = onClose)
        )

        Column(
            modifier = Modifier
                .padding(14.dp)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(12.dp))
                .background(IronPanel)
                .border(2.dp, MoltenBrass.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Header
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HP ${hud.hp}/${hud.maxHp}  •  ATK ${hud.atkTotal}  •  DEF ${hud.defTotal}  •  ЗОЛОТО ${hud.gold}",
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

            Spacer(Modifier.height(8.dp))

            // Split body: Left Pane (Hero drop zone + 2x2 Equipment) | Right Pane (Tabs + LazyVerticalGrid + Inspector)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Left Pane
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroConsumeDropZone(
                        hud = hud,
                        atlasStore = atlasStore,
                        isPotionDragged = draggedItem?.type == ItemType.CONSUMABLE,
                        isHovered = hoveredTarget == DropTarget.HeroConsume,
                        onBoundsChanged = { dragController.heroDropBounds = it }
                    )

                    Text(
                        text = stringResource(R.string.label_equipment),
                        style = MaterialTheme.typography.labelLarge,
                        color = SteelMist
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EquipmentGridSlotCard(
                                slotTitle = "ЗБРОЯ",
                                slotType = ItemType.WEAPON,
                                item = hud.equippedWeapon,
                                atlasStore = atlasStore,
                                isSelected = (selection as? InventorySelection.EquippedSlot)?.slotType == ItemType.WEAPON,
                                isValidDropTarget = draggedOrigin is DragOrigin.Backpack && draggedItem?.type == ItemType.WEAPON,
                                isHovered = hoveredTarget == DropTarget.EquipSlot(ItemType.WEAPON),
                                isBeingDragged = (draggedOrigin as? DragOrigin.Equipped)?.slotType == ItemType.WEAPON,
                                onSelect = {
                                    if (hud.equippedWeapon != null) {
                                        selection = InventorySelection.EquippedSlot(ItemType.WEAPON)
                                    }
                                },
                                onBoundsChanged = { dragController.equipSlotBounds[ItemType.WEAPON] = it },
                                onDragStart = { item, pos ->
                                    dragController.startDrag(item, DragOrigin.Equipped(ItemType.WEAPON), pos)
                                },
                                onDragDelta = { dragController.updateDrag(it) },
                                onDragFinish = { handleDropCommit() },
                                onDragCancel = { dragController.clear() },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )

                            EquipmentGridSlotCard(
                                slotTitle = "ЩИТ",
                                slotType = ItemType.SHIELD,
                                item = hud.equippedShield,
                                atlasStore = atlasStore,
                                isSelected = (selection as? InventorySelection.EquippedSlot)?.slotType == ItemType.SHIELD,
                                isValidDropTarget = draggedOrigin is DragOrigin.Backpack && draggedItem?.type == ItemType.SHIELD,
                                isHovered = hoveredTarget == DropTarget.EquipSlot(ItemType.SHIELD),
                                isBeingDragged = (draggedOrigin as? DragOrigin.Equipped)?.slotType == ItemType.SHIELD,
                                onSelect = {
                                    if (hud.equippedShield != null) {
                                        selection = InventorySelection.EquippedSlot(ItemType.SHIELD)
                                    }
                                },
                                onBoundsChanged = { dragController.equipSlotBounds[ItemType.SHIELD] = it },
                                onDragStart = { item, pos ->
                                    dragController.startDrag(item, DragOrigin.Equipped(ItemType.SHIELD), pos)
                                },
                                onDragDelta = { dragController.updateDrag(it) },
                                onDragFinish = { handleDropCommit() },
                                onDragCancel = { dragController.clear() },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EquipmentGridSlotCard(
                                slotTitle = "ШОЛОМ",
                                slotType = ItemType.HELMET,
                                item = hud.equippedHelmet,
                                atlasStore = atlasStore,
                                isSelected = (selection as? InventorySelection.EquippedSlot)?.slotType == ItemType.HELMET,
                                isValidDropTarget = draggedOrigin is DragOrigin.Backpack && draggedItem?.type == ItemType.HELMET,
                                isHovered = hoveredTarget == DropTarget.EquipSlot(ItemType.HELMET),
                                isBeingDragged = (draggedOrigin as? DragOrigin.Equipped)?.slotType == ItemType.HELMET,
                                onSelect = {
                                    if (hud.equippedHelmet != null) {
                                        selection = InventorySelection.EquippedSlot(ItemType.HELMET)
                                    }
                                },
                                onBoundsChanged = { dragController.equipSlotBounds[ItemType.HELMET] = it },
                                onDragStart = { item, pos ->
                                    dragController.startDrag(item, DragOrigin.Equipped(ItemType.HELMET), pos)
                                },
                                onDragDelta = { dragController.updateDrag(it) },
                                onDragFinish = { handleDropCommit() },
                                onDragCancel = { dragController.clear() },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )

                            EquipmentGridSlotCard(
                                slotTitle = "ПЛАЩ",
                                slotType = ItemType.CLOAK,
                                item = hud.equippedCloak,
                                atlasStore = atlasStore,
                                isSelected = (selection as? InventorySelection.EquippedSlot)?.slotType == ItemType.CLOAK,
                                isValidDropTarget = draggedOrigin is DragOrigin.Backpack && draggedItem?.type == ItemType.CLOAK,
                                isHovered = hoveredTarget == DropTarget.EquipSlot(ItemType.CLOAK),
                                isBeingDragged = (draggedOrigin as? DragOrigin.Equipped)?.slotType == ItemType.CLOAK,
                                onSelect = {
                                    if (hud.equippedCloak != null) {
                                        selection = InventorySelection.EquippedSlot(ItemType.CLOAK)
                                    }
                                },
                                onBoundsChanged = { dragController.equipSlotBounds[ItemType.CLOAK] = it },
                                onDragStart = { item, pos ->
                                    dragController.startDrag(item, DragOrigin.Equipped(ItemType.CLOAK), pos)
                                },
                                onDragDelta = { dragController.updateDrag(it) },
                                onDragFinish = { handleDropCommit() },
                                onDragCancel = { dragController.clear() },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }

                // Right Pane: Filter Tabs + LazyVerticalGrid + Item Detail Inspector
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InventoryFilterChip(
                            label = "${stringResource(R.string.label_inv_filter_all)} (${hud.backpack.size})",
                            selected = activeFilter == InventoryFilterTab.ALL,
                            testTag = "inventory_filter_all",
                            onClick = { activeFilter = InventoryFilterTab.ALL },
                            modifier = Modifier.weight(1f)
                        )
                        InventoryFilterChip(
                            label = "${stringResource(R.string.label_inv_filter_gear)} ($gearCount)",
                            selected = activeFilter == InventoryFilterTab.GEAR,
                            testTag = "inventory_filter_gear",
                            onClick = { activeFilter = InventoryFilterTab.GEAR },
                            modifier = Modifier.weight(1f)
                        )
                        InventoryFilterChip(
                            label = "${stringResource(R.string.label_inv_filter_potions)} ($potionCount)",
                            selected = activeFilter == InventoryFilterTab.POTIONS,
                            testTag = "inventory_filter_potions",
                            onClick = { activeFilter = InventoryFilterTab.POTIONS },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val unequipHighlight = draggedOrigin is DragOrigin.Equipped
                    val gridBorderColor by animateColorAsState(
                        targetValue = when {
                            hoveredTarget == DropTarget.UnequipToPack -> MoltenBrass
                            unequipHighlight -> MoltenBrass.copy(alpha = 0.55f)
                            else -> IronBorder
                        },
                        animationSpec = tween(150),
                        label = "grid_border"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IronSurfaceHigh.copy(alpha = 0.45f))
                            .border(if (unequipHighlight) 2.dp else 1.dp, gridBorderColor, RoundedCornerShape(10.dp))
                            .onGloballyPositioned { coords ->
                                dragController.backpackGridBounds = coords.boundsInWindow()
                            }
                            .testTag("inventory_backpack_grid")
                    ) {
                        if (filteredItems.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Inventory2,
                                    contentDescription = null,
                                    tint = SteelMist,
                                    modifier = Modifier.size(30.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = if (unequipHighlight) {
                                        "Відпустіть сюди, щоб зняти предмет"
                                    } else {
                                        "У цій категорії немає предметів. Шукайте скрині у крипті."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (unequipHighlight) MoltenBrass else SteelMist
                                )
                            }
                        } else {
                            val emptyTrailingSlots = (8 - filteredItems.size).coerceAtLeast(0)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredItems, key = { it.id }) { item ->
                                    val isSelected = (selection as? InventorySelection.BackpackItem)?.itemId == item.id
                                    val isBeingDragged = (draggedOrigin as? DragOrigin.Backpack)?.itemId == item.id
                                    val isReorderHovered = (hoveredTarget as? DropTarget.ReorderBackpack)?.targetItemId == item.id
                                    BackpackGridTile(
                                        item = item,
                                        atlasStore = atlasStore,
                                        isSelected = isSelected,
                                        isBeingDragged = isBeingDragged,
                                        isDropTargetHovered = isReorderHovered,
                                        onClick = { selection = InventorySelection.BackpackItem(item.id) },
                                        onBoundsChanged = { rect ->
                                            dragController.backpackItemBounds[item.id] = rect
                                        },
                                        onDragStart = { startPos ->
                                            dragController.startDrag(item, DragOrigin.Backpack(item.id), startPos)
                                        },
                                        onDragDelta = { dragController.updateDrag(it) },
                                        onDragFinish = { handleDropCommit() },
                                        onDragCancel = { dragController.clear() }
                                    )
                                }

                                items(emptyTrailingSlots, key = { idx -> "empty_slot_$idx" }) {
                                    EmptyGridSlot()
                                }
                            }
                        }
                    }

                    // Anchored Detail Inspector Bar
                    ItemDetailInspectorBar(
                        inspected = inspectedPair,
                        atlasStore = atlasStore,
                        onActionClick = { item, isEquipped ->
                            if (isEquipped) {
                                onUnequipSlot(item.type)
                                selection = InventorySelection.BackpackItem(item.id)
                            } else {
                                onEquipOrUse(item.id)
                                if (isGearType(item.type)) {
                                    selection = InventorySelection.EquippedSlot(item.type)
                                }
                            }
                        }
                    )
                }
            }
        }

        // Floating Drag Ghost rendered above all panes
        if (draggedItem != null) {
            val density = LocalDensity.current
            val ghostSizePx = with(density) { 64.dp.toPx() }
            val localOffset = dragController.pointerWindowPos - dragController.rootWindowOrigin
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (localOffset.x - ghostSizePx * 0.5f).roundToInt(),
                                y = (localOffset.y - ghostSizePx * 0.5f).roundToInt()
                            )
                        }
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IronPanel.copy(alpha = 0.94f))
                        .border(
                            2.dp,
                            if (draggedItem.type == ItemType.CONSUMABLE) VitalEmerald else MoltenBrass,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(6.dp)
                        .testTag("inventory_drag_ghost"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = atlasStore.getSpriteBitmap(draggedItem.spriteId),
                        contentDescription = draggedItem.nameUk,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroConsumeDropZone(
    hud: HudUiState,
    atlasStore: AtlasBitmapStore,
    isPotionDragged: Boolean,
    isHovered: Boolean,
    onBoundsChanged: (Rect) -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> VitalEmerald
            isPotionDragged -> VitalEmerald.copy(alpha = 0.72f)
            else -> IronBorder
        },
        animationSpec = tween(150),
        label = "hero_drop_border"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isHovered -> VitalEmeraldDark.copy(alpha = 0.55f)
            else -> IronSurfaceHigh
        },
        animationSpec = tween(150),
        label = "hero_drop_bg"
    )
    val hpFraction = if (hud.maxHp > 0) (hud.hp.toFloat() / hud.maxHp.toFloat()).coerceIn(0f, 1f) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(if (isPotionDragged) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInWindow()) }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("inventory_hero_drop_zone"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            bitmap = atlasStore.getSpriteBitmap(SpriteId.HERO_SOUTH),
            contentDescription = "Hero",
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(34.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isHovered) {
                        stringResource(R.string.label_inv_hero_drop_ready)
                    } else {
                        stringResource(R.string.label_inv_hero_drop)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPotionDragged) VitalEmerald else SteelMist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            LinearProgressIndicator(
                progress = { hpFraction },
                color = CrimsonForge,
                trackColor = IronPanel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Text(
                text = "HP: ${hud.hp} / ${hud.maxHp}",
                style = MaterialTheme.typography.labelSmall,
                color = AgedParchment
            )
        }
    }
}

@Composable
private fun EquipmentGridSlotCard(
    slotTitle: String,
    slotType: ItemType,
    item: RpgItem?,
    atlasStore: AtlasBitmapStore,
    isSelected: Boolean,
    isValidDropTarget: Boolean,
    isHovered: Boolean,
    isBeingDragged: Boolean,
    onSelect: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onDragStart: (RpgItem, Offset) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestItem by rememberUpdatedState(item)
    var slotWindowOrigin by remember { mutableStateOf(Offset.Zero) }

    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> MoltenBrass
            isValidDropTarget -> MoltenBrass.copy(alpha = 0.72f)
            isSelected -> AgedParchment
            else -> IronBorder
        },
        animationSpec = tween(150),
        label = "equip_slot_border"
    )

    Column(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (isBeingDragged) 0.35f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) MoltenBrass.copy(alpha = 0.16f) else IronSurfaceHigh)
            .border(if (isHovered || isSelected || isValidDropTarget) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInWindow()
                slotWindowOrigin = rect.topLeft
                onBoundsChanged(rect)
            }
            .clickable(enabled = item != null, onClick = onSelect)
            .pointerInput(slotType, item?.id) {
                if (item != null) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { localOffset ->
                            latestItem?.let { current ->
                                onDragStart(current, slotWindowOrigin + localOffset)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount)
                        },
                        onDragEnd = onDragFinish,
                        onDragCancel = onDragCancel
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("inventory_equip_slot_${slotType.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = slotTitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (isValidDropTarget) MoltenBrass else SteelMist,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        if (item != null) {
            Image(
                bitmap = atlasStore.getSpriteBitmap(item.spriteId),
                contentDescription = item.nameUk,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.nameUk,
                style = MaterialTheme.typography.labelSmall,
                color = AgedParchment,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = compactStatSummary(item),
                style = MaterialTheme.typography.labelSmall,
                color = MoltenBrass,
                maxLines = 1
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(IronPanel)
                    .border(1.dp, IronBorder.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "порожньо",
                style = MaterialTheme.typography.labelSmall,
                color = SteelMist.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InventoryFilterChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MoltenBrass else IronSurfaceHigh)
            .border(1.dp, if (selected) AgedParchment else IronBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.Black else AgedParchment,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BackpackGridTile(
    item: RpgItem,
    atlasStore: AtlasBitmapStore,
    isSelected: Boolean,
    isBeingDragged: Boolean,
    isDropTargetHovered: Boolean,
    onClick: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragFinish: () -> Unit,
    onDragCancel: () -> Unit
) {
    var tileWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragFinish by rememberUpdatedState(onDragFinish)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    val borderColor by animateColorAsState(
        targetValue = when {
            isDropTargetHovered -> VitalEmerald
            isSelected -> MoltenBrass
            else -> IronBorder
        },
        animationSpec = tween(120),
        label = "tile_border"
    )

    val badgeColor = affinityBadgeColor(item)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .minimumInteractiveComponentSize()
            .alpha(if (isBeingDragged) 0.32f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) IronSurfaceHigh else IronPanel)
            .border(if (isSelected || isDropTargetHovered) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInWindow()
                tileWindowOrigin = rect.topLeft
                onBoundsChanged(rect)
            }
            .clickable(onClick = onClick)
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { localOffset ->
                        latestOnDragStart(tileWindowOrigin + localOffset)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        latestOnDragDelta(dragAmount)
                    },
                    onDragEnd = { latestOnDragFinish() },
                    onDragCancel = { latestOnDragCancel() }
                )
            }
            .padding(5.dp)
            .testTag("inventory_grid_item_${item.id}")
    ) {
        // Affinity / category dot at top-start
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(7.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )

        // Quantity badge at top-end
        if (item.count > 1) {
            Text(
                text = "x${item.count}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = AgedParchment,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(4.dp))
                    .background(IronSurfaceHigh)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // Crisp pixel-art item icon
        Image(
            bitmap = atlasStore.getSpriteBitmap(item.spriteId),
            contentDescription = item.nameUk,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
        )

        // Compact stat tag at bottom
        Text(
            text = compactStatSummary(item),
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun EmptyGridSlot() {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(IronPanel.copy(alpha = 0.55f))
            .border(1.dp, IronBorder.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ItemDetailInspectorBar(
    inspected: Pair<RpgItem, Boolean>?,
    atlasStore: AtlasBitmapStore,
    onActionClick: (RpgItem, isEquipped: Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorderBright.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("inventory_inspector_panel"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (inspected == null) {
            Text(
                text = "Оберіть предмет для перегляду характеристик",
                style = MaterialTheme.typography.labelMedium,
                color = SteelMist
            )
        } else {
            val (item, isEquipped) = inspected
            val badgeColor = affinityBadgeColor(item)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    bitmap = atlasStore.getSpriteBitmap(item.spriteId),
                    contentDescription = item.nameUk,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(34.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val qty = if (!isEquipped && item.count > 1) " x${item.count}" else ""
                        Text(
                            text = "${item.nameUk}$qty",
                            style = MaterialTheme.typography.titleSmall,
                            color = AgedParchment,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.type == ItemType.CONSUMABLE) "ЗІЛЛЯ" else item.affinity.labelUk,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(IronPanel)
                                .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = item.descriptionUk,
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelMist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            val actionText = when {
                isEquipped -> stringResource(R.string.action_unequip)
                item.type == ItemType.CONSUMABLE -> stringResource(R.string.action_consume)
                else -> stringResource(R.string.action_equip)
            }
            val buttonBg = when {
                isEquipped -> IronPanel
                item.type == ItemType.CONSUMABLE -> VitalEmerald
                else -> MoltenBrass
            }
            val buttonFg = when {
                isEquipped -> AgedParchment
                else -> Color.Black
            }

            Button(
                onClick = { onActionClick(item, isEquipped) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBg,
                    contentColor = buttonFg
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("inventory_inspector_action_button")
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun equippedForSlot(hud: HudUiState, slotType: ItemType): RpgItem? = when (slotType) {
    ItemType.WEAPON -> hud.equippedWeapon
    ItemType.SHIELD -> hud.equippedShield
    ItemType.HELMET -> hud.equippedHelmet
    ItemType.CLOAK -> hud.equippedCloak
    ItemType.CONSUMABLE, ItemType.GOLD -> null
}

private fun compactStatSummary(item: RpgItem): String = when {
    item.healAmount > 0 -> "+${item.healAmount} HP"
    item.atkBonus > 0 -> "+${item.atkBonus} ATK"
    item.defBonus > 0 -> "+${item.defBonus} DEF"
    item.spdBonus > 0f -> "+${item.spdBonus} SPD"
    else -> item.affinity.labelUk
}

private fun affinityBadgeColor(item: RpgItem): Color = when {
    item.type == ItemType.CONSUMABLE -> VitalEmerald
    item.affinity == ElementalAffinity.FIRE -> CrimsonForge
    item.affinity == ElementalAffinity.ARCANE -> ArcaneCobalt
    else -> MoltenBrass
}
