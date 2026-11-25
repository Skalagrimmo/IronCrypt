package com.example

import com.example.data.GraphicsSettings
import com.example.game.engine.EnemyAttackPattern
import com.example.game.engine.EnemyKind
import com.example.game.engine.AdaptiveQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.engine.DungeonMap
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
        val gov = AdaptiveQualityGovernor()
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
        val gov = AdaptiveQualityGovernor()

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

        // The final Sanctum gate must block its six-tile passage until unlocked.
        assertFalse(sim.map.isWalkable(DungeonMap.GATE_CENTER_X, DungeonMap.GATE_CENTER_Y, 0.3f))
        sim.map.unlockGate()
        assertTrue(sim.map.gateUnlocked)
        assertTrue(sim.map.isWalkable(DungeonMap.GATE_CENTER_X, DungeonMap.GATE_CENTER_Y, 0.3f))

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
        val gov = AdaptiveQualityGovernor()
        sim.resetRun(GamePhase.PLAYING)

        // Starting tile area is discovered; the distant Sanctum remains shrouded.
        assertTrue(sim.isTileDiscovered(DungeonMap.START_X.toInt(), DungeonMap.START_Y.toInt()))
        assertFalse(sim.isTileDiscovered(85, 11))

        val initialSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertTrue(initialSnap.discoveredPercent > 0)
        assertTrue(initialSnap.markers.any { it.kind == MiniMapMarkerKind.LEVER && it.isPrimaryQuest })
        assertTrue(initialSnap.markers.any { it.kind == MiniMapMarkerKind.GATE_LOCKED && it.isPrimaryQuest })
        assertEquals(0, initialSnap.markers.count { it.kind == MiniMapMarkerKind.BOSS })
        // The Chronicler is in a separate sanctuary and must remain hidden until explored.
        assertFalse(initialSnap.markers.any { it.kind == MiniMapMarkerKind.NPC })
        // The first optional treasure branch is outside the initial discovery radius.
        assertFalse(initialSnap.markers.any { it.kind == MiniMapMarkerKind.CHEST })

        // Explore the north-west sanctuary and reveal its NPC.
        sim.playerX = DungeonMap.CHRONICLER_X
        sim.playerY = DungeonMap.CHRONICLER_Y
        sim.revealAroundPlayer(force = false)
        assertTrue(sim.isTileDiscovered(10, 12))
        assertTrue(sim.buildHudSnapshot(gov, 0).miniMap.markers.any { it.kind == MiniMapMarkerKind.NPC })

        // Move hero to the Ashen Archive and reveal its treasure chamber.
        sim.playerX = 65.5f
        sim.playerY = 11.5f
        sim.revealAroundPlayer(force = false)
        assertTrue(sim.isTileDiscovered(65, 11))

        val corridorSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertTrue(corridorSnap.markers.any { it.kind == MiniMapMarkerKind.CHEST })
        assertTrue(corridorSnap.discoveredPercent > initialSnap.discoveredPercent)

        // Pull lever and unlock gate. The boss room itself remains hidden until entered.
        val leverIdx = sim.props.indexOfFirst { it.active && it.kind == PropKind.LEVER }
        sim.props[leverIdx].used = true
        sim.map.unlockGate()
        assertEquals(0, sim.buildHudSnapshot(gov, 0).miniMap.markers.count {
            it.kind == MiniMapMarkerKind.BOSS
        })

        // Enter the Sanctum to reveal both boss markers.
        sim.playerX = 85.5f
        sim.playerY = 12.5f
        sim.revealAroundPlayer(force = false)
        val unlockedSnap = sim.buildHudSnapshot(gov, 0).miniMap
        assertFalse(unlockedSnap.markers.any { it.kind == MiniMapMarkerKind.LEVER })
        assertTrue(unlockedSnap.markers.any { it.kind == MiniMapMarkerKind.GATE_OPEN })
        assertEquals(2, unlockedSnap.markers.count { it.kind == MiniMapMarkerKind.BOSS && it.isPrimaryQuest })
    }

    @Test
    fun expandedDungeonRoomsAreConnectedAndSanctumRemainsGateLocked() {
        val map = DungeonMap()
        assertEquals(96, DungeonMap.WIDTH)
        assertEquals(72, DungeonMap.HEIGHT)

        fun canReach(targetX: Int, targetY: Int): Boolean {
            val visited = BooleanArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
            val queue = IntArray(visited.size)
            var head = 0
            var tail = 0
            val startX = DungeonMap.START_X.toInt()
            val startY = DungeonMap.START_Y.toInt()
            val startIndex = startY * DungeonMap.WIDTH + startX
            queue[tail++] = startIndex
            visited[startIndex] = true

            while (head < tail) {
                val current = queue[head++]
                val x = current % DungeonMap.WIDTH
                val y = current / DungeonMap.WIDTH
                if (x == targetX && y == targetY) return true

                for (direction in 0..3) {
                    val nx = when (direction) {
                        0 -> x + 1
                        1 -> x - 1
                        else -> x
                    }
                    val ny = when (direction) {
                        2 -> y + 1
                        3 -> y - 1
                        else -> y
                    }
                    if (nx !in 0 until DungeonMap.WIDTH || ny !in 0 until DungeonMap.HEIGHT) continue
                    val next = ny * DungeonMap.WIDTH + nx
                    if (visited[next]) continue
                    if (!map.isWalkable(nx + 0.5f, ny + 0.5f, 0.58f)) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            return false
        }

        // Every major wing and the optional service loop must be reachable from the entrance.
        assertTrue(canReach(10, 12)) // Chronicler's Sanctuary
        assertTrue(canReach(35, 12)) // Gearworks
        assertTrue(canReach(60, 13)) // Ashen Archive
        assertTrue(canReach(40, 35)) // Furnace Nave
        assertTrue(canReach(35, 60)) // Cinder Drainage
        assertTrue(canReach(60, 36)) // Service Shaft
        assertTrue(canReach(61, 60)) // Ossuary Crypt
        assertTrue(canReach(84, 36)) // Crimson Antechamber
        assertFalse(canReach(84, 12)) // Crimson Sanctum is locked

        map.unlockGate()
        assertTrue(canReach(84, 12))
    }

    @Test
    fun enemiesUseSharedFlowFieldToRouteAroundRoomWalls() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)

        // The Archive and Gearworks are separated by a wall. Their only connection
        // is the doorway farther south, so a direct chase vector points into masonry.
        val enemy = sim.enemies[4]
        enemy.x = 52.5f
        enemy.y = 8.5f
        sim.playerX = 45.7f
        sim.playerY = 8.5f

        val startingY = enemy.y
        repeat(20) {
            sim.update(0.016f, QualityTier.HIGH)
        }

        // The shared route must first send this enemy south toward the real doorway.
        assertTrue(enemy.y > startingY + 0.2f)
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
    fun enemyKindsUseDistinctTelegraphedAttackPatterns() {
        val cases = listOf(
            Triple(EnemyKind.SKELETON, EnemyAttackPattern.SLASH, 0.72f),
            Triple(EnemyKind.GARGOYLE, EnemyAttackPattern.POUNCE, 2.40f),
            Triple(EnemyKind.AUTOMATON, EnemyAttackPattern.SLAM, 1.05f),
            Triple(EnemyKind.BOSS_MECH, EnemyAttackPattern.CHARGE, 3.00f),
            Triple(EnemyKind.BOSS_DEMON, EnemyAttackPattern.FIRE_FAN, 4.00f)
        )
        val resolvedPatterns = mutableSetOf<EnemyAttackPattern>()

        for ((kind, expectedPattern, distance) in cases) {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, kind, distance)
            val startingHp = sim.playerHp

            sim.update(0.016f, QualityTier.HIGH)

            assertEquals("$kind should select its own pattern", expectedPattern, enemy.attackPattern)
            assertTrue("$kind should expose a readable windup", enemy.attackWindup > 0f)
            assertTrue("$kind should publish a positive windup duration", enemy.attackWindupDuration > 0f)
            assertEquals("$kind must not deal damage on telegraph start", startingHp, sim.playerHp)
            resolvedPatterns.add(enemy.attackPattern)
        }

        assertEquals(5, resolvedPatterns.size)
    }

    @Test
    fun enemiesSelectSecondaryMovesAtCloseRange() {
        val cases = listOf(
            EnemyKind.GARGOYLE to EnemyAttackPattern.SLASH,
            EnemyKind.AUTOMATON to EnemyAttackPattern.BASH,
            EnemyKind.BOSS_MECH to EnemyAttackPattern.SLAM,
            EnemyKind.BOSS_DEMON to EnemyAttackPattern.FIRE_ORB
        )

        for ((kind, expectedPattern) in cases) {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, kind, 0.72f)
            val startingHp = sim.playerHp

            sim.update(0.016f, QualityTier.HIGH)

            assertEquals("$kind should switch to its close-range move", expectedPattern, enemy.attackPattern)
            assertTrue(enemy.attackWindup > 0f)
            assertEquals(startingHp, sim.playerHp)
        }

        // The close-range demon orb is one slower, heavier projectile rather than another fan.
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        val demon = prepareSingleEnemy(sim, EnemyKind.BOSS_DEMON, 0.72f)
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(EnemyAttackPattern.FIRE_ORB, demon.attackPattern)
        sim.playerY += 2.0f
        repeat(14) {
            if (demon.attackPattern != EnemyAttackPattern.NONE) {
                sim.update(0.05f, QualityTier.HIGH)
            }
        }
        val orb = sim.projectiles.firstOrNull { it.active && !it.fromPlayer }
        assertTrue("Close-range demon should launch a focused orb", orb != null)
        assertEquals(demon.kind.atk + 8, orb!!.damage)
        assertEquals(1, sim.projectiles.count { it.active && !it.fromPlayer })
    }

    @Test
    fun committedMeleeAndAreaAttacksCanBeEvaded() {
        // Step behind the skeleton after it commits to a frontal slash.
        run {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, EnemyKind.SKELETON)
            val startingHp = sim.playerHp
            sim.update(0.016f, QualityTier.HIGH)
            assertEquals(EnemyAttackPattern.SLASH, enemy.attackPattern)

            sim.playerX = enemy.x + 0.76f
            repeat(14) { sim.update(0.05f, QualityTier.HIGH) }
            assertEquals(startingHp, sim.playerHp)
        }

        // The gargoyle lands at the location it marked, not the player's new position.
        run {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, EnemyKind.GARGOYLE, 2.40f)
            val targetX = enemy.attackTargetX
            val targetY = enemy.attackTargetY
            val startingHp = sim.playerHp
            sim.update(0.016f, QualityTier.HIGH)
            assertEquals(EnemyAttackPattern.POUNCE, enemy.attackPattern)

            sim.playerX = targetX + 2.0f
            sim.playerY = targetY
            repeat(22) { sim.update(0.05f, QualityTier.HIGH) }
            assertEquals(startingHp, sim.playerHp)
        }

        // The automaton's close punch is directional and can be avoided by stepping behind it.
        run {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, EnemyKind.AUTOMATON, 0.72f)
            val startingHp = sim.playerHp
            sim.update(0.016f, QualityTier.HIGH)
            assertEquals(EnemyAttackPattern.BASH, enemy.attackPattern)

            sim.playerX = enemy.x + 0.78f
            repeat(14) { sim.update(0.05f, QualityTier.HIGH) }
            assertEquals(startingHp, sim.playerHp)
        }

        // The automaton's slow slam has a broad but finite radius.
        run {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, EnemyKind.AUTOMATON, 1.05f)
            val startingHp = sim.playerHp
            sim.update(0.016f, QualityTier.HIGH)
            assertEquals(EnemyAttackPattern.SLAM, enemy.attackPattern)

            sim.playerX = enemy.x + 2.6f
            repeat(25) { sim.update(0.05f, QualityTier.HIGH) }
            assertEquals(startingHp, sim.playerHp)
        }

        // The boss charge follows its announced lane; a perpendicular sidestep avoids it.
        run {
            val sim = GameSimulation()
            sim.resetRun(GamePhase.PLAYING)
            val enemy = prepareSingleEnemy(sim, EnemyKind.BOSS_MECH, 3.00f)
            val startingHp = sim.playerHp
            sim.update(0.016f, QualityTier.HIGH)
            assertEquals(EnemyAttackPattern.CHARGE, enemy.attackPattern)

            sim.playerY += 1.25f
            repeat(28) { sim.update(0.05f, QualityTier.HIGH) }
            assertEquals(startingHp, sim.playerHp)
        }
    }

    @Test
    fun demonFanLaunchesFiveProjectilesAlongItsTelegraphedLanes() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        val enemy = prepareSingleEnemy(sim, EnemyKind.BOSS_DEMON, 4.00f)
        val startingHp = sim.playerHp

        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(EnemyAttackPattern.FIRE_FAN, enemy.attackPattern)
        assertEquals(0, sim.projectiles.count { it.active })
        assertEquals(startingHp, sim.playerHp)

        // Evade the point the demon aimed at, then let the committed fan resolve.
        sim.playerY += 2.0f
        repeat(20) { sim.update(0.05f, QualityTier.HIGH) }

        assertEquals(EnemyAttackPattern.NONE, enemy.attackPattern)
        assertEquals(5, sim.projectiles.count { it.active && !it.fromPlayer })
        assertEquals(startingHp, sim.playerHp)
    }

    private fun prepareSingleEnemy(sim: GameSimulation, kind: EnemyKind, distance: Float = 0.72f): com.example.game.engine.EnemyActor {
        sim.enemies.forEach { it.active = false }
        sim.projectiles.forEach { it.active = false }
        val enemy = sim.enemies[0]
        enemy.active = true
        enemy.kind = kind
        enemy.x = sim.playerX + distance
        enemy.y = sim.playerY
        enemy.hp = kind.maxHp
        enemy.maxHp = kind.maxHp
        enemy.facing = com.example.game.model.Direction.WEST
        enemy.attackWindup = 0f
        enemy.attackWindupDuration = 0f
        enemy.attackCooldown = 0f
        enemy.attackPattern = EnemyAttackPattern.NONE
        enemy.attackDirX = -1f
        enemy.attackDirY = 0f
        enemy.attackTargetX = sim.playerX
        enemy.attackTargetY = sim.playerY
        enemy.hitFlash = 0f
        enemy.animTime = 0f
        return enemy
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
