package com.example

import com.example.data.GraphicsSettings
import com.example.game.engine.EnemyKind
import com.example.game.engine.ExynosQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.engine.PropKind
import com.example.game.model.AffinityEffectiveness
import com.example.game.model.ElementalAffinity
import com.example.game.model.GamePhase
import com.example.game.model.ItemType
import com.example.game.model.MiniMapMarkerKind
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.RpgItem
import com.example.game.model.SoundCue
import com.example.game.model.SpriteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IronCryptUnitTest {

    @Test
    fun qualityGovernorStepsDownAndBackUpInAutoMode() {
        val gov = ExynosQualityGovernor()
        gov.mode = QualityMode.AUTO
        assertEquals(QualityTier.HIGH, gov.activeTier)

        var now = 1_000_000L
        // Simulate sustained slow frames (> 26ms) -> should step down HIGH -> MEDIUM -> LOW
        repeat(200) {
            now += 30_000_000L
            gov.recordFrame(now, 28f)
        }
        assertEquals(QualityTier.LOW, gov.activeTier)

        // Simulate fast frames (< 10ms) -> should recover LOW -> MEDIUM -> HIGH
        repeat(420) {
            now += 10_000_000L
            gov.recordFrame(now, 8f)
        }
        assertEquals(QualityTier.HIGH, gov.activeTier)
    }

    @Test
    fun presetAndCustomOverridesResolveRenderConfigAccurately() {
        val gov = ExynosQualityGovernor()

        // Low preset disables shadows, torch halos, and lighting shader strength
        gov.customSettings = GraphicsSettings.forPreset(QualityMode.LOW)
        val lowCfg = gov.resolveRenderConfig()
        assertFalse(lowCfg.isCustomOverride)
        assertEquals(0.70f, lowCfg.resolutionScale, 0.001f)
        assertEquals(0.0f, lowCfg.shaderLightingStrength, 0.001f)
        assertFalse(lowCfg.enableGroundShadows)
        assertFalse(lowCfg.enableTorchGlow)

        // Custom override on Low enables shader lighting & 100% scale while keeping custom flag
        gov.customSettings = gov.customSettings.copy(
            isCustomOverride = true,
            shaderLightingEnabled = true,
            torchFlickerEnabled = true,
            resolutionScale = 1.0f,
            poolBudgetTier = QualityTier.MEDIUM
        )
        val customCfg = gov.resolveRenderConfig()
        assertTrue(customCfg.isCustomOverride)
        assertEquals(1.0f, customCfg.resolutionScale, 0.001f)
        assertTrue(customCfg.shaderLightingStrength > 0.5f)
        assertTrue(customCfg.torchFlickerEnabled)
        assertEquals(16, customCfg.maxSimultaneousEntities)
        assertEquals(32, customCfg.maxParticles)
    }

    @Test
    fun simulationCombatGateAndEquipmentWork() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)

        val initialAtk = sim.playerAtkTotal()
        assertFalse(sim.map.gateUnlocked)

        // Unlock gate via map and verify collision opens at gate tiles (x=23, y=11)
        assertFalse(sim.map.isWalkable(23.5f, 11.5f, 0.3f))
        sim.map.unlockGate()
        assertTrue(sim.map.gateUnlocked)
        assertTrue(sim.map.isWalkable(23.5f, 11.5f, 0.3f))

        val axe = sim.equippedWeapon
        assertTrue(initialAtk > 0 && axe != null)

        val startX = sim.playerX
        sim.joyX = 1f
        sim.joyY = 0f
        repeat(10) {
            sim.update(0.016f, QualityTier.HIGH)
        }
        assertTrue(sim.playerX > startX)
    }

    @Test
    fun miniMapFogOfWarAndQuestMarkersUpdateDynamically() {
        val sim = GameSimulation()
        val gov = ExynosQualityGovernor()
        sim.resetRun(GamePhase.PLAYING)

        // Starting tile area is discovered; far east boss sanctum is initially shrouded
        assertTrue(sim.isTileDiscovered(4, 12))
        assertFalse(sim.isTileDiscovered(31, 10))

        val initialSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertTrue(initialSnap.discoveredPercent > 0)
        assertTrue(initialSnap.markers.any { it.kind == MiniMapMarkerKind.LEVER && it.isPrimaryQuest })
        assertTrue(initialSnap.markers.any { it.kind == MiniMapMarkerKind.GATE_LOCKED && it.isPrimaryQuest })
        assertEquals(2, initialSnap.markers.count { it.kind == MiniMapMarkerKind.BOSS && !it.isPrimaryQuest })
        // Chronicler at (5.8, 9.2) is within initial 5.5-tile vision radius
        assertTrue(initialSnap.markers.any { it.kind == MiniMapMarkerKind.NPC })
        // Center corridor chest at (18.0, 4.2) is undiscovered initially
        assertFalse(initialSnap.markers.any { it.kind == MiniMapMarkerKind.CHEST })

        // Move hero near north-center chest & lever and reveal area
        sim.playerX = 18.5f
        sim.playerY = 4.5f
        sim.revealAroundPlayer(force = false)
        assertTrue(sim.isTileDiscovered(18, 4))

        val corridorSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertTrue(corridorSnap.markers.any { it.kind == MiniMapMarkerKind.CHEST })
        assertTrue(corridorSnap.discoveredPercent > initialSnap.discoveredPercent)

        // Pull lever and unlock gate -> lever marker clears, gate switches to OPEN, bosses become primary
        val leverIdx = sim.props.indexOfFirst { it.active && it.kind == PropKind.LEVER }
        sim.props[leverIdx].used = true
        sim.map.unlockGate()

        val unlockedSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertFalse(unlockedSnap.markers.any { it.kind == MiniMapMarkerKind.LEVER })
        assertTrue(unlockedSnap.markers.any { it.kind == MiniMapMarkerKind.GATE_OPEN })
        assertEquals(2, unlockedSnap.markers.count { it.kind == MiniMapMarkerKind.BOSS && it.isPrimaryQuest })
    }

    @Test
    fun floatingCombatTextSpawnsForHitsCleaveAndPotions() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)

        // Affinity resolution & enemy weakness multipliers
        assertEquals(ElementalAffinity.PHYSICAL, sim.resolveAttackAffinity(isCleave = false))
        assertEquals(ElementalAffinity.ARCANE, sim.resolveAttackAffinity(isCleave = true))

        val skelFire = sim.enemyAffinityEffectiveness(EnemyKind.SKELETON, ElementalAffinity.FIRE)
        assertEquals(AffinityEffectiveness.WEAKNESS, skelFire.first)
        assertEquals(1.35f, skelFire.second, 0.01f)

        val mechArcane = sim.enemyAffinityEffectiveness(EnemyKind.BOSS_MECH, ElementalAffinity.ARCANE)
        assertEquals(AffinityEffectiveness.WEAKNESS, mechArcane.first)
        assertEquals(1.35f, mechArcane.second, 0.01f)

        // Move hero adjacent to enemy 0 and trigger Arcane Cleave
        val target = sim.enemies[0]
        sim.playerX = target.x - 1.0f
        sim.playerY = target.y
        sim.cleaveQueued = true
        sim.update(0.016f, QualityTier.HIGH)

        assertTrue(sim.hasActiveFloatingTexts)
        val hitSnap = sim.exportFloatingTextSnapshot()
        assertTrue(hitSnap.items.any { it.affinity == ElementalAffinity.ARCANE && !it.isPlayerHit })

        // Lower hero HP and use potion -> spawns HEAL floating text
        sim.playerHp = 50
        sim.potionQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        val healSnap = sim.exportFloatingTextSnapshot()
        assertTrue(healSnap.items.any { it.affinity == ElementalAffinity.HEAL && it.amountText == "+55" })

        // Advance past floating text lifetime -> pool returns cleanly to idle
        repeat(40) {
            sim.update(0.05f, QualityTier.HIGH)
        }
        assertFalse(sim.hasActiveFloatingTexts)
        assertTrue(sim.exportFloatingTextSnapshot().items.isEmpty())
    }

    @Test
    fun soundCueRingBufferEmitsCombatPotionDashAndClearsOnReset() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        assertFalse(sim.hasPendingSoundCues)

        val cues = mutableListOf<SoundCue>()

        // Primary swing
        sim.attackQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertTrue(sim.hasPendingSoundCues)
        sim.drainSoundCues(cues)
        assertFalse(sim.hasPendingSoundCues)
        assertTrue(cues.contains(SoundCue.SWING))

        // Dash
        cues.clear()
        sim.dashQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        sim.drainSoundCues(cues)
        assertTrue(cues.contains(SoundCue.DASH))

        // Cleave hitting enemy 0
        cues.clear()
        val target = sim.enemies[0]
        sim.playerX = target.x - 1.0f
        sim.playerY = target.y
        sim.cleaveQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        sim.drainSoundCues(cues)
        assertTrue(cues.contains(SoundCue.CLEAVE))
        assertTrue(cues.any { it == SoundCue.HIT || it == SoundCue.CRIT })

        // Potion heal
        cues.clear()
        sim.playerHp = 60
        sim.potionQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        sim.drainSoundCues(cues)
        assertTrue(cues.contains(SoundCue.POTION))

        // Queued cues clear cleanly on resetRun
        sim.attackCooldown = 0f
        sim.attackQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertTrue(sim.hasPendingSoundCues)
        sim.resetRun(GamePhase.PLAYING)
        assertFalse(sim.hasPendingSoundCues)
        cues.clear()
        sim.drainSoundCues(cues)
        assertTrue(cues.isEmpty())
    }

    @Test
    fun inventoryReorderUnequipAndSwapPreserveItemsAndStats() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)

        val battleAxe = RpgItem(
            id = "battle_axe",
            nameUk = "Бойова Сокира",
            descriptionUk = "+15 ATK",
            type = ItemType.WEAPON,
            spriteId = SpriteId.ITEM_AXE,
            atkBonus = 15,
            affinity = ElementalAffinity.FIRE
        )
        val kiteShield = RpgItem(
            id = "kite_shield",
            nameUk = "Щит Лицаря",
            descriptionUk = "+6 DEF",
            type = ItemType.SHIELD,
            spriteId = SpriteId.ITEM_SHIELD,
            defBonus = 6
        )
        sim.backpack.add(battleAxe)
        sim.backpack.add(kiteShield)

        // Initial order: [crimson_potion, battle_axe, kite_shield]
        assertEquals(listOf("crimson_potion", "battle_axe", "kite_shield"), sim.backpack.map { it.id })

        // Reorder kite_shield to front
        sim.reorderBackpackItem("kite_shield", "crimson_potion")
        assertEquals(listOf("kite_shield", "crimson_potion", "battle_axe"), sim.backpack.map { it.id })

        // Equip battle_axe swaps iron_sword in-place at index 2
        val baseAtk = sim.playerAtkTotal()
        sim.equipOrUseItem("battle_axe")
        assertEquals("battle_axe", sim.equippedWeapon?.id)
        assertTrue(sim.playerAtkTotal() > baseAtk)
        assertEquals(listOf("kite_shield", "crimson_potion", "iron_sword"), sim.backpack.map { it.id })

        // Unequip weapon moves battle_axe back to backpack and lowers ATK
        sim.unequipSlot(ItemType.WEAPON)
        assertEquals(null, sim.equippedWeapon)
        assertEquals(12, sim.playerAtkTotal())
        assertTrue(sim.backpack.any { it.id == "battle_axe" })
    }
}
