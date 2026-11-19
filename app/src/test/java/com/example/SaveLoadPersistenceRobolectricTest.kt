package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.GameSaveRepository
import com.example.data.QualityPreferencesRepository
import com.example.game.engine.GameSimulation
import com.example.game.engine.DungeonMap
import com.example.game.engine.PropKind
import com.example.game.model.Direction
import com.example.game.model.GamePhase
import com.example.game.model.GameSaveSnapshot
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SaveLoadPersistenceRobolectricTest {

    @Test
    fun `snapshot json round trip and simulation restore preserve full dungeon state`() {
        val source = GameSimulation()
        source.resetRun(GamePhase.PLAYING)

        source.playerX = 19.25f
        source.playerY = 11.75f
        source.playerFacing = Direction.NORTH
        source.playerHp = 78
        source.playerStamina = 42.5f
        source.playerGold = 185
        source.kills = 7
        source.bossesDefeated = 1
        source.map.unlockGate()
        source.revealAroundPlayer(force = true)
        source.selectDialogueChoice(null, "GIFT_POTION")
        source.selectDialogueChoice(null, "GIFT_HOOD")
        source.equipOrUseItem("shadow_hood")

        // Mark chest opened, lever pulled, and barrel broken
        val chestIdx = source.props.indexOfFirst { it.active && it.kind == PropKind.CHEST }
        assertTrue(chestIdx >= 0)
        source.props[chestIdx].used = true

        val leverIdx = source.props.indexOfFirst { it.active && it.kind == PropKind.LEVER }
        assertTrue(leverIdx >= 0)
        source.props[leverIdx].used = true

        val barrelIdx = source.props.indexOfFirst { it.active && it.kind == PropKind.BARREL }
        assertTrue(barrelIdx >= 0)
        source.props[barrelIdx].active = false

        // Wound enemy 0 and defeat enemy 1
        assertTrue(source.enemies[0].active)
        source.enemies[0].hp = 17
        source.enemies[0].x = 14.5f
        source.enemies[0].y = 10.25f
        source.enemies[0].facing = Direction.WEST
        source.enemies[1].active = false
        source.enemies[1].hp = 0

        // Place an active ground pickup
        source.pickups[0].active = true
        source.pickups[0].x = 12.5f
        source.pickups[0].y = 11.5f
        source.pickups[0].itemId = "gold_coins"

        val exported = source.exportSnapshot(savedAtMs = 1_700_000_123_000L)
        val json = exported.toJson()
        val parsed = GameSaveSnapshot.fromJson(json)
        assertNotNull(parsed)
        assertEquals(exported, parsed)

        val target = GameSimulation()
        target.resetRun(GamePhase.TITLE)
        target.restoreSnapshot(parsed!!)

        assertEquals(GamePhase.PLAYING, target.phase)
        assertEquals(19.25f, target.playerX, 0.001f)
        assertEquals(11.75f, target.playerY, 0.001f)
        assertEquals(Direction.NORTH, target.playerFacing)
        assertEquals(78, target.playerHp)
        assertEquals(42.5f, target.playerStamina, 0.01f)
        assertEquals(185, target.playerGold)
        assertEquals(7, target.kills)
        assertEquals(1, target.bossesDefeated)
        assertTrue(target.map.gateUnlocked)
        assertTrue(target.map.isWalkable(DungeonMap.GATE_CENTER_X, DungeonMap.GATE_CENTER_Y, 0.3f))
        assertTrue(target.isTileDiscovered(18, 11))
        assertTrue(target.isTileDiscovered(19, 11))
        assertFalse(target.isTileDiscovered(65, 60))
        assertEquals("shadow_hood", target.equippedCloak?.id)
        val reExported = target.exportSnapshot(savedAtMs = 1_700_000_123_000L)
        assertTrue(reExported.giftedPotion)
        assertTrue(reExported.giftedHood)
        assertEquals(exported.backpack, reExported.backpack)

        assertTrue(target.props[chestIdx].used)
        assertTrue(target.props[leverIdx].used)
        assertFalse(target.props[barrelIdx].active)

        assertTrue(target.enemies[0].active)
        assertEquals(17, target.enemies[0].hp)
        assertEquals(14.5f, target.enemies[0].x, 0.001f)
        assertEquals(10.25f, target.enemies[0].y, 0.001f)
        assertEquals(Direction.WEST, target.enemies[0].facing)
        assertFalse(target.enemies[1].active)
        assertEquals(0, target.enemies[1].hp)

        assertTrue(target.pickups[0].active)
        assertEquals("gold_coins", target.pickups[0].itemId)
        assertEquals(12.5f, target.pickups[0].x, 0.001f)
    }

    @Test
    fun legacyVersionOneSaveMigratesToSafeEntranceWithoutApplyingOldActorIndices() {
        val source = GameSimulation()
        source.resetRun(GamePhase.PLAYING)
        source.playerGold = 333
        source.playerHp = 77
        source.map.unlockGate()

        // Old compact-map coordinates and prop/enemy indices must not be replayed
        // into the expanded layout.
        val legacy = source.exportSnapshot(savedAtMs = 1_700_000_555_000L).copy(
            version = 1,
            playerX = 23.5f,
            playerY = 11.5f,
            bossesDefeated = 1,
            discoveredMask = "0".repeat(36 * 24)
        )

        val target = GameSimulation()
        target.restoreSnapshot(legacy)

        assertEquals(GamePhase.PLAYING, target.phase)
        assertEquals(DungeonMap.START_X, target.playerX, 0.001f)
        assertEquals(DungeonMap.START_Y, target.playerY, 0.001f)
        assertEquals(333, target.playerGold)
        assertEquals(77, target.playerHp)
        assertTrue(target.map.gateUnlocked)
        assertTrue(target.map.isWalkable(DungeonMap.GATE_CENTER_X, DungeonMap.GATE_CENTER_Y, 0.3f))
        assertEquals(1, target.bossesDefeated)
        assertEquals(1, target.enemies.count { it.active && it.kind.isBoss })
        assertEquals(DungeonMap.WIDTH * DungeonMap.HEIGHT, target.buildHudSnapshot(
            com.example.game.engine.ExynosQualityGovernor(), 0
        ).miniMap.discoveredMask.length)
    }

    @Test
    fun `datastore persists graphics preferences and game save across repository instances`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Graphics preferences persistence
        val prefs1 = QualityPreferencesRepository(context)
        prefs1.applyPreset(QualityMode.MEDIUM)
        prefs1.updateCustom { it.copy(shaderLightingEnabled = false, resolutionScale = 0.70f, poolBudgetTier = QualityTier.LOW) }
        prefs1.setShowTelemetry(false)

        val prefs2 = QualityPreferencesRepository(context)
        val restoredGraphics = prefs2.settingsFlow.first()
        assertEquals(QualityMode.MEDIUM, restoredGraphics.qualityMode)
        assertTrue(restoredGraphics.isCustomOverride)
        assertFalse(restoredGraphics.shaderLightingEnabled)
        assertEquals(0.70f, restoredGraphics.resolutionScale, 0.001f)
        assertEquals(QualityTier.LOW, restoredGraphics.poolBudgetTier)
        assertFalse(restoredGraphics.showTelemetry)

        // Game save persistence
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        sim.playerHp = 64
        sim.playerGold = 99
        sim.bossesDefeated = 1
        sim.map.unlockGate()
        val snapshot = sim.exportSnapshot(savedAtMs = 1_700_000_999_000L)

        val saveRepo1 = GameSaveRepository(context)
        saveRepo1.saveGame(snapshot)

        val saveRepo2 = GameSaveRepository(context)
        val summary = saveRepo2.saveSummaryFlow.first()
        assertTrue(summary.hasSave)
        assertEquals(64, summary.hp)
        assertEquals(99, summary.gold)
        assertEquals(1, summary.bossesDefeated)

        val loaded = saveRepo2.loadGame()
        assertNotNull(loaded)
        assertEquals(64, loaded!!.playerHp)
        assertEquals(99, loaded.playerGold)
        assertTrue(loaded.gateUnlocked)

        saveRepo2.clearSave()
        assertFalse(saveRepo1.saveSummaryFlow.first().hasSave)
        assertNull(saveRepo1.loadGame())
    }
}
