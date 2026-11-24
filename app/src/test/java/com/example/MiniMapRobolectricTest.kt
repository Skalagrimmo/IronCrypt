package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.game.engine.AdaptiveQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.model.GamePhase
import com.example.game.model.MiniMapDisplayMode
import com.example.ui.components.MiniMapOverlay
import com.example.ui.theme.IronCryptTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniMapRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping minimap cycles compact expanded minimized and back`() {
        val sim = GameSimulation().apply { resetRun(GamePhase.PLAYING) }
        val gov = AdaptiveQualityGovernor()
        val miniMapState = sim.buildHudSnapshot(gov, 12).miniMap

        var mode by mutableStateOf(MiniMapDisplayMode.COMPACT)

        composeRule.setContent {
            IronCryptTheme {
                MiniMapOverlay(
                    miniMap = miniMapState,
                    mode = mode,
                    onToggleExpand = {
                        mode = if (mode == MiniMapDisplayMode.EXPANDED) {
                            MiniMapDisplayMode.COMPACT
                        } else {
                            MiniMapDisplayMode.EXPANDED
                        }
                    },
                    onMinimize = { mode = MiniMapDisplayMode.MINIMIZED },
                    onRestore = { mode = MiniMapDisplayMode.COMPACT }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("minimap_compact_card").assertIsDisplayed()

        // Tap map canvas to expand
        composeRule.onNodeWithTag("minimap_canvas").performClick()
        composeRule.waitForIdle()
        assertEquals(MiniMapDisplayMode.EXPANDED, mode)
        composeRule.onNodeWithTag("minimap_expanded_card").assertIsDisplayed()
        composeRule.onNodeWithTag("minimap_legend").assertIsDisplayed()

        // Tap minimize button to collapse to pill
        composeRule.onNodeWithTag("minimap_minimize_button").performClick()
        composeRule.waitForIdle()
        assertEquals(MiniMapDisplayMode.MINIMIZED, mode)
        composeRule.onNodeWithTag("minimap_minimized_pill").assertIsDisplayed()

        // Tap minimized pill to restore compact view
        composeRule.onNodeWithTag("minimap_minimized_pill").performClick()
        composeRule.waitForIdle()
        assertEquals(MiniMapDisplayMode.COMPACT, mode)
        composeRule.onNodeWithTag("minimap_compact_card").assertIsDisplayed()
    }
}
