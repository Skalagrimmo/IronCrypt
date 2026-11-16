package com.example

import com.example.data.GraphicsSettings
import com.example.game.engine.ExynosQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.model.GamePhase
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
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
}
