package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.ElementalAffinity
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.RpgItem
import com.example.game.model.SpriteId
import com.example.ui.components.InventoryOverlay
import com.example.ui.theme.IronCryptTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w800dp-h420dp-land")
class InventoryGridRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleSword() = RpgItem(
        id = "iron_sword",
        nameUk = "Залізний Меч",
        descriptionUk = "Фіз. клинок (+6 ATK)",
        type = ItemType.WEAPON,
        spriteId = SpriteId.ITEM_SWORD,
        atkBonus = 6,
        affinity = ElementalAffinity.PHYSICAL
    )

    private fun sampleAxe() = RpgItem(
        id = "battle_axe",
        nameUk = "Бойова Сокира",
        descriptionUk = "Вогонь Кузні (+15 ATK)",
        type = ItemType.WEAPON,
        spriteId = SpriteId.ITEM_AXE,
        atkBonus = 15,
        affinity = ElementalAffinity.FIRE
    )

    private fun sampleShield() = RpgItem(
        id = "kite_shield",
        nameUk = "Щит Лицаря",
        descriptionUk = "Кований щит (+6 DEF)",
        type = ItemType.SHIELD,
        spriteId = SpriteId.ITEM_SHIELD,
        defBonus = 6
    )

    private fun samplePotion() = RpgItem(
        id = "crimson_potion",
        nameUk = "Червоне Зілля",
        descriptionUk = "Відновлює 55 HP",
        type = ItemType.CONSUMABLE,
        spriteId = SpriteId.ITEM_POTION,
        healAmount = 55,
        count = 2
    )

    @Test
    fun `lazy grid displays items filters categories and inspector actions work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val atlasStore = AtlasBitmapStore(context)
        var hudState by mutableStateOf(
            HudUiState(
                phase = GamePhase.PLAYING,
                hp = 70,
                maxHp = 120,
                equippedWeapon = sampleSword(),
                backpack = listOf(samplePotion(), sampleAxe(), sampleShield())
            )
        )
        val actions = mutableListOf<String>()

        composeRule.setContent {
            IronCryptTheme {
                InventoryOverlay(
                    hud = hudState,
                    atlasStore = atlasStore,
                    onEquipOrUse = { id -> actions.add("use:$id") },
                    onUnequipSlot = { slot -> actions.add("unequip:${slot.name}") },
                    onMoveItem = { from, to -> actions.add("move:$from->$to") },
                    onClose = { actions.add("close") }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_backpack_grid").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_grid_item_crimson_potion").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_grid_item_battle_axe").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_grid_item_kite_shield").assertIsDisplayed()

        // Filter to GEAR -> potions hidden, gear visible
        composeRule.onNodeWithTag("inventory_filter_gear").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_grid_item_battle_axe").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_grid_item_crimson_potion").assertDoesNotExist()

        // Filter to POTIONS -> only potion visible
        composeRule.onNodeWithTag("inventory_filter_potions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_grid_item_crimson_potion").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_grid_item_battle_axe").assertDoesNotExist()

        // Back to ALL -> tap axe to inspect -> tap Equip in inspector
        composeRule.onNodeWithTag("inventory_filter_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_grid_item_battle_axe").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_inspector_panel").assertIsDisplayed()
        composeRule.onNodeWithText("Бойова Сокира").assertIsDisplayed()
        composeRule.onNodeWithTag("inventory_inspector_action_button").performClick()
        composeRule.waitForIdle()
        assertTrue(actions.contains("use:battle_axe"))

        // Tap equipped sword -> inspector offers Unequip
        composeRule.onNodeWithTag("inventory_equip_slot_WEAPON").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inventory_inspector_action_button").performClick()
        composeRule.waitForIdle()
        assertTrue(actions.contains("unequip:WEAPON"))
    }

    @Test
    fun `drag and drop supports grid reorder equip slot hero potion consume and unequip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val atlasStore = AtlasBitmapStore(context)
        val hudState = HudUiState(
            phase = GamePhase.PLAYING,
            hp = 65,
            maxHp = 120,
            equippedWeapon = sampleSword(),
            backpack = listOf(samplePotion(), sampleAxe(), sampleShield())
        )
        val actions = mutableListOf<String>()

        composeRule.setContent {
            IronCryptTheme {
                InventoryOverlay(
                    hud = hudState,
                    atlasStore = atlasStore,
                    onEquipOrUse = { id -> actions.add("use:$id") },
                    onUnequipSlot = { slot -> actions.add("unequip:${slot.name}") },
                    onMoveItem = { from, to -> actions.add("move:$from->$to") },
                    onClose = {}
                )
            }
        }

        composeRule.waitForIdle()

        // 1. Drag potion onto shield grid tile -> reorder
        val shieldCenter = composeRule.onNodeWithTag("inventory_grid_item_kite_shield")
            .fetchSemanticsNode().boundsInRoot.center
        val potionBounds = composeRule.onNodeWithTag("inventory_grid_item_crimson_potion")
            .fetchSemanticsNode().boundsInRoot
        val dragPotionToShield = shieldCenter - potionBounds.center

        composeRule.onNodeWithTag("inventory_grid_item_crimson_potion").performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveBy(dragPotionToShield)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("move:crimson_potion->kite_shield", actions.lastOrNull())

        // 2. Drag battle_axe onto WEAPON equipment slot -> equip
        val weaponCenter = composeRule.onNodeWithTag("inventory_equip_slot_WEAPON")
            .fetchSemanticsNode().boundsInRoot.center
        val axeCenter = composeRule.onNodeWithTag("inventory_grid_item_battle_axe")
            .fetchSemanticsNode().boundsInRoot.center
        val dragAxeToSlot = weaponCenter - axeCenter

        composeRule.onNodeWithTag("inventory_grid_item_battle_axe").performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveBy(dragAxeToSlot)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("use:battle_axe", actions.lastOrNull())

        // 3. Drag crimson_potion onto Hero Consume Drop Zone -> consume
        val heroCenter = composeRule.onNodeWithTag("inventory_hero_drop_zone")
            .fetchSemanticsNode().boundsInRoot.center
        val dragPotionToHero = heroCenter - potionBounds.center

        composeRule.onNodeWithTag("inventory_grid_item_crimson_potion").performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveBy(dragPotionToHero)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("use:crimson_potion", actions.lastOrNull())

        // 4. Drag equipped WEAPON back onto backpack grid -> unequip
        val gridCenter = composeRule.onNodeWithTag("inventory_backpack_grid")
            .fetchSemanticsNode().boundsInRoot.center
        val dragEquipToGrid: Offset = gridCenter - weaponCenter

        composeRule.onNodeWithTag("inventory_equip_slot_WEAPON").performTouchInput {
            down(center)
            advanceEventTime(650L)
            moveBy(dragEquipToGrid)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("unequip:WEAPON", actions.lastOrNull())
    }
}
