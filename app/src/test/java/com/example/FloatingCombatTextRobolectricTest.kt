package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.game.model.AffinityEffectiveness
import com.example.game.model.ElementalAffinity
import com.example.game.model.FloatingTextFrameSnapshot
import com.example.game.model.FloatingTextItem
import com.example.ui.components.FloatingCombatTextOverlay
import com.example.ui.components.projectWorldToScreen
import com.example.ui.components.resolveFloatingTextColor
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.IronCryptTheme
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.VitalEmerald
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingCombatTextRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `world projection and affinity color coding map accurately`() {
        val center = projectWorldToScreen(
            worldX = 10f,
            worldY = 12f,
            camX = 10f,
            camY = 12f,
            visibleWorldHeight = 9.2f,
            viewWidthPx = 1280f,
            viewHeightPx = 720f
        )
        assertEquals(640f, center.x, 0.01f)
        assertEquals(360f, center.y, 0.01f)

        // One full visibleWorldHeight/2 upward from camera center maps to top edge (y = 0)
        val topEdge = projectWorldToScreen(
            worldX = 10f,
            worldY = 12f - 4.6f,
            camX = 10f,
            camY = 12f,
            visibleWorldHeight = 9.2f,
            viewWidthPx = 1280f,
            viewHeightPx = 720f
        )
        assertEquals(Offset(640f, 0f).y, topEdge.y, 0.01f)

        val phys = FloatingTextItem(1L, 10f, 12f, "18", "", ElementalAffinity.PHYSICAL, AffinityEffectiveness.NEUTRAL, false, false, 0.2f, 0f)
        val physCrit = phys.copy(id = 2L, amountText = "30!", tagText = "КРИТ", isCrit = true)
        val fire = phys.copy(id = 3L, amountText = "36", tagText = "ВОГОНЬ ВРАЗЛ.", affinity = ElementalAffinity.FIRE)
        val arcane = phys.copy(id = 4L, amountText = "44", tagText = "МАГІЯ ВРАЗЛ.", affinity = ElementalAffinity.ARCANE)
        val playerHit = phys.copy(id = 5L, amountText = "-12", isPlayerHit = true)
        val heal = phys.copy(id = 6L, amountText = "+55", tagText = "HP", affinity = ElementalAffinity.HEAL)

        assertEquals(AgedParchment, resolveFloatingTextColor(phys))
        assertEquals(MoltenBrass, resolveFloatingTextColor(physCrit))
        assertEquals(MoltenBrass, resolveFloatingTextColor(fire))
        assertEquals(ArcaneCobalt, resolveFloatingTextColor(arcane))
        assertEquals(CrimsonForge, resolveFloatingTextColor(playerHit))
        assertEquals(VitalEmerald, resolveFloatingTextColor(heal))
    }

    @Test
    fun `floating combat text overlay mounts and displays active combat numbers`() {
        val snapshot = FloatingTextFrameSnapshot(
            camX = 10f,
            camY = 12f,
            items = listOf(
                FloatingTextItem(1L, 10.5f, 11.2f, "32!", "КРИТ • ВОГОНЬ", ElementalAffinity.FIRE, AffinityEffectiveness.WEAKNESS, true, false, 0.25f, 0.12f),
                FloatingTextItem(2L, 9.8f, 11.5f, "+55", "HP", ElementalAffinity.HEAL, AffinityEffectiveness.NEUTRAL, false, false, 0.45f, -0.08f)
            )
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            IronCryptTheme {
                FloatingCombatTextOverlay(
                    isPlaying = true,
                    hasActiveProvider = { true },
                    snapshotProvider = { snapshot }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(48L)
        composeRule.onNodeWithTag("floating_combat_text_canvas").assertIsDisplayed()
    }
}
