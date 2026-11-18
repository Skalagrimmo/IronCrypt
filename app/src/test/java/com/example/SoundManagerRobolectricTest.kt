package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.audio.IronCryptSoundManager
import com.example.data.AudioSettings
import com.example.data.GraphicsSettings
import com.example.data.QualityPreferencesRepository
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.PauseMenuTab
import com.example.game.model.SaveSlotSummary
import com.example.game.model.SoundCue
import com.example.ui.components.UnifiedPauseMenu
import com.example.ui.theme.IronCryptTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w800dp-h420dp-land")
class SoundManagerRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `datastore persists audio settings across repository instances`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo1 = QualityPreferencesRepository(context)
        repo1.setAmbientEnabled(false)
        repo1.setAmbientVolume(0.25f)
        repo1.setSfxEnabled(false)
        repo1.setSfxVolume(0.65f)

        val repo2 = QualityPreferencesRepository(context)
        val restored = repo2.audioSettingsFlow.first()
        assertFalse(restored.ambientEnabled)
        assertEquals(0.25f, restored.ambientVolume, 0.001f)
        assertFalse(restored.sfxEnabled)
        assertEquals(0.65f, restored.sfxVolume, 0.001f)
    }

    @Test
    fun `sound manager initializes with raw wav assets and handles lifecycle safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawIds = listOf(
            R.raw.amb_crypt_drone,
            R.raw.sfx_swing,
            R.raw.sfx_cleave,
            R.raw.sfx_hit,
            R.raw.sfx_crit,
            R.raw.sfx_player_hit,
            R.raw.sfx_dash,
            R.raw.sfx_potion,
            R.raw.sfx_chest,
            R.raw.sfx_lever
        )
        rawIds.forEach { resId ->
            context.resources.openRawResource(resId).use { stream ->
                val header = ByteArray(12)
                assertEquals(12, stream.read(header))
                assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
                assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            }
        }

        val manager = IronCryptSoundManager(context)
        manager.applySettings(AudioSettings(ambientEnabled = true, ambientVolume = 0.5f, sfxEnabled = true, sfxVolume = 0.8f))
        manager.setMenuDucked(true)
        manager.playCue(SoundCue.SWING)
        manager.playCues(listOf(SoundCue.CRIT, SoundCue.CHEST, SoundCue.LEVER))
        manager.onAppPause()
        manager.onAppResume()
        manager.release()
        // Post-release calls must remain no-op safe
        manager.playCue(SoundCue.POTION)
        manager.onAppResume()
    }

    @Test
    fun `pause menu audio tab renders switches sliders and invokes callbacks`() {
        var activeTab by mutableStateOf(PauseMenuTab.GAME)
        var audioState by mutableStateOf(AudioSettings())
        var testSoundClicks = 0

        composeRule.setContent {
            IronCryptTheme {
                UnifiedPauseMenu(
                    hud = HudUiState(phase = GamePhase.PLAYING),
                    settings = GraphicsSettings(),
                    audioSettings = audioState,
                    saveSummary = SaveSlotSummary(),
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it },
                    onResume = {},
                    onSaveGame = {},
                    onLoadGame = {},
                    onRestart = {},
                    onReturnToTitle = {},
                    onSelectMode = {},
                    onToggleShaderLighting = {},
                    onToggleTorchFlicker = {},
                    onToggleGroundShadows = {},
                    onToggleTorchHalos = {},
                    onToggleScreenShake = {},
                    onSelectResolutionScale = {},
                    onSelectPoolBudget = {},
                    onToggleTelemetry = {},
                    onToggleAmbient = { audioState = audioState.copy(ambientEnabled = it) },
                    onAmbientVolumeChange = { audioState = audioState.copy(ambientVolume = it) },
                    onToggleSfx = { audioState = audioState.copy(sfxEnabled = it) },
                    onSfxVolumeChange = { audioState = audioState.copy(sfxVolume = it) },
                    onTestSound = { testSoundClicks++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pause_tab_audio").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("audio_ambient_switch").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertFalse(audioState.ambientEnabled)

        composeRule.onNodeWithTag("audio_ambient_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("audio_sfx_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("audio_sfx_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("audio_test_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, testSoundClicks)

        // Toggle SFX off -> test sound button becomes disabled
        composeRule.onNodeWithTag("audio_sfx_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(audioState.sfxEnabled)
        composeRule.onNodeWithTag("audio_test_button").performScrollTo().assertIsNotEnabled()
        assertTrue(activeTab == PauseMenuTab.AUDIO)
    }
}
