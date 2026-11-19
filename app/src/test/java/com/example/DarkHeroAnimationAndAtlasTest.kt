package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.game.engine.AnimationCategory
import com.example.game.engine.AnimationState
import com.example.game.engine.ExynosQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.engine.HeroAnimationController
import com.example.game.engine.HeroAnimationState
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.AtlasId
import com.example.game.model.DarkHeroAtlasData
import com.example.game.model.Direction
import com.example.game.model.GamePhase
import com.example.game.model.HeroPortraitId
import com.example.game.model.HeroVisualAppearance
import com.example.game.model.HeroVisualLoadout
import com.example.game.model.HeroVisualProfile
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.SpriteId
import com.example.game.model.SpriteRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DarkHeroAnimationAndAtlasTest {

    // -------------------------------------------------------------------------
    // 1. DarkHeroAtlasTest
    // -------------------------------------------------------------------------

    @Test
    fun atlasMetadataContainsExactly432UniqueNonOverlappingPaddedFramesAndValidPivots() {
        val uniqueByKey = LinkedHashMap<String, SpriteRect>()
        for (clip in DarkHeroAtlasData.allClips) {
            assertEquals(8, clip.framesByDirection.size)
            for (dirRow in clip.framesByDirection) {
                assertEquals(clip.frameCount, dirRow.size)
                for (rect in dirRow) {
                    assertTrue(rect.atlasId == AtlasId.HERO_CORE || rect.atlasId == AtlasId.HERO_VARIANTS)
                    assertEquals(2048, rect.atlasWidth)
                    assertEquals(2048, rect.atlasHeight)
                    assertTrue("w must be > 0", rect.w > 0)
                    assertTrue("h must be > 0", rect.h > 0)
                    assertTrue("x + padding out of bounds", rect.x >= DarkHeroAtlasData.ATLAS_PADDING_PX)
                    assertTrue("y + padding out of bounds", rect.y >= DarkHeroAtlasData.ATLAS_PADDING_PX)
                    assertTrue("right edge exceeds 2048", rect.x + rect.w <= 2048 - DarkHeroAtlasData.ATLAS_PADDING_PX)
                    assertTrue("bottom edge exceeds 2048", rect.y + rect.h <= 2048 - DarkHeroAtlasData.ATLAS_PADDING_PX)
                    assertTrue("worldUnitsPerTexel must be calibrated > 0", rect.worldUnitsPerTexel > 0f)
                    assertTrue("trimOffsetX >= 0", rect.trimOffsetX >= 0)
                    assertTrue("trimOffsetY >= 0", rect.trimOffsetY >= 0)
                    assertTrue("trimOffsetX + w <= sourceW", rect.trimOffsetX + rect.w <= rect.sourceW)
                    assertTrue("trimOffsetY + h <= sourceH", rect.trimOffsetY + rect.h <= rect.sourceH)
                    uniqueByKey["${rect.atlasId.name}:${rect.x},${rect.y},${rect.w},${rect.h}"] = rect
                }
            }
        }
        assertEquals(DarkHeroAtlasData.TOTAL_PACKED_FRAMES, uniqueByKey.size)
        assertEquals(432, uniqueByKey.size)

        // Verify disjoint rects with >= 3 px gap inside each atlas
        for (atlas in listOf(AtlasId.HERO_CORE, AtlasId.HERO_VARIANTS)) {
            val rects = uniqueByKey.values.filter { it.atlasId == atlas }
            assertTrue(rects.isNotEmpty())
            for (i in rects.indices) {
                val a = rects[i]
                for (j in i + 1 until rects.size) {
                    val b = rects[j]
                    val sepX = (a.x + a.w + DarkHeroAtlasData.ATLAS_PADDING_PX <= b.x) ||
                        (b.x + b.w + DarkHeroAtlasData.ATLAS_PADDING_PX <= a.x)
                    val sepY = (a.y + a.h + DarkHeroAtlasData.ATLAS_PADDING_PX <= b.y) ||
                        (b.y + b.h + DarkHeroAtlasData.ATLAS_PADDING_PX <= a.y)
                    assertTrue(
                        "Rects overlap or violate 3px padding in $atlas: $a vs $b",
                        sepX || sepY
                    )
                }
            }
        }

        // Verify calibrated foot-anchor overrides for action sheets
        assertEquals(121.5f, DarkHeroAtlasData.deadClip.framesByDirection[0][3].footAnchorY, 0.01f)
        assertEquals(132.0f, DarkHeroAtlasData.dashRollClip.framesByDirection[0][2].footAnchorY, 0.01f)
        assertEquals(166.5f, DarkHeroAtlasData.interactClip.framesByDirection[0][1].footAnchorY, 0.01f)

        // Verify WORLD_MAIN backward compatibility
        val tileRect = SpriteId.TILE_STONE_BRICK.rect
        assertEquals(AtlasId.WORLD_MAIN, tileRect.atlasId)
        assertEquals(1024, tileRect.atlasWidth)
        assertEquals(0f, tileRect.worldUnitsPerTexel, 0.0001f)
    }

    @Test
    fun packedAtlasPngsPreserve2048ResolutionAndTrueAlphaAndDecodePortraitsViaRegionDecoder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        for (atlas in listOf(AtlasId.HERO_CORE, AtlasId.HERO_VARIANTS)) {
            val bmp = BitmapFactory.decodeResource(context.resources, atlas.drawableRes, opts)
            try {
                assertEquals(2048, bmp.width)
                assertEquals(2048, bmp.height)
                var hasZeroAlpha = false
                var hasSemiAlpha = false
                var hasFullAlpha = false
                val rowBuf = IntArray(2048)
                for (y in 0 until 600 step 4) {
                    bmp.getPixels(rowBuf, 0, 2048, 0, y, 2048, 1)
                    for (px in rowBuf) {
                        val a = (px ushr 24) and 0xFF
                        when {
                            a == 0 -> {
                                hasZeroAlpha = true
                                assertEquals("Transparent pixel must be RGB zeroed", 0, px)
                            }
                            a == 255 -> hasFullAlpha = true
                            else -> hasSemiAlpha = true
                        }
                    }
                    if (hasZeroAlpha && hasSemiAlpha && hasFullAlpha) break
                }
                assertTrue("$atlas must have transparent alpha==0 pixels", hasZeroAlpha)
                assertTrue("$atlas must preserve semi-transparent anti-aliased alpha pixels", hasSemiAlpha)
                assertTrue("$atlas must have opaque alpha==255 pixels", hasFullAlpha)
            } finally {
                bmp.recycle()
            }
        }

        // Verify BitmapRegionDecoder loads all 3 Hero portraits cleanly without loading full atlases
        val store = AtlasBitmapStore(context)
        for (pid in HeroPortraitId.entries) {
            val img = store.getHeroPortraitBitmap(pid)
            assertTrue("Portrait $pid width > 80", img.width > 80)
            assertTrue("Portrait $pid height > 120", img.height > 120)
        }
    }

    // -------------------------------------------------------------------------
    // 2. HeroAnimationStateTest
    // -------------------------------------------------------------------------

    @Test
    fun eightDirectionQuantizerAndRowMappingWorkAcrossAllOctants() {
        assertEquals(Direction.NORTH, Direction.fromVector(0f, -1f))
        assertEquals(Direction.NORTH_EAST, Direction.fromVector(0.707f, -0.707f))
        assertEquals(Direction.EAST, Direction.fromVector(1f, 0f))
        assertEquals(Direction.SOUTH_EAST, Direction.fromVector(0.707f, 0.707f))
        assertEquals(Direction.SOUTH, Direction.fromVector(0f, 1f))
        assertEquals(Direction.SOUTH_WEST, Direction.fromVector(-0.707f, 0.707f))
        assertEquals(Direction.WEST, Direction.fromVector(-1f, 0f))
        assertEquals(Direction.NORTH_WEST, Direction.fromVector(-0.707f, -0.707f))
        assertEquals(Direction.NORTH_EAST, Direction.fromVector(0.001f, 0.001f, Direction.NORTH_EAST))

        assertEquals(0, Direction.SOUTH.rowIndex)
        assertEquals(1, Direction.SOUTH_EAST.rowIndex)
        assertEquals(2, Direction.EAST.rowIndex)
        assertEquals(3, Direction.NORTH_EAST.rowIndex)
        assertEquals(4, Direction.NORTH.rowIndex)
        assertEquals(5, Direction.NORTH_WEST.rowIndex)
        assertEquals(6, Direction.WEST.rowIndex)
        assertEquals(7, Direction.SOUTH_WEST.rowIndex)
    }

    @Test
    fun confirmedEventTriggersRespectCooldownStaminaPriorityAndAntiRetrigger() {
        val anim = HeroAnimationController()
        assertEquals(HeroAnimationState.IDLE, anim.currentState)

        // Duplicate serial rejection & priority arbitration
        assertTrue(anim.triggerConfirmed(HeroAnimationState.ATTACK, triggerSerial = 1L))
        assertFalse(anim.triggerConfirmed(HeroAnimationState.CLEAVE, triggerSerial = 1L)) // same serial rejected
        assertFalse(anim.triggerConfirmed(HeroAnimationState.INTERACT, triggerSerial = 2L)) // lower priority rejected while ATTACK active
        assertTrue(anim.triggerConfirmed(HeroAnimationState.DASH, triggerSerial = 3L)) // higher priority DASH accepted
        assertEquals(HeroAnimationState.DASH, anim.currentState)

        // Advancing past DASH duration returns to WALK if moving
        anim.updateGameplay(dt = 0.25f, joyMag = 0.8f, hasDialogue = false, isDefeat = false)
        assertEquals(HeroAnimationState.WALK, anim.currentState)

        // Simulation rejects triggers when blocked by cooldown or insufficient stamina
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        sim.attackCooldown = 0.25f
        sim.attackQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(HeroAnimationState.IDLE, sim.heroAnimation.currentState)

        sim.playerStamina = 10f
        sim.cleaveQueued = true
        sim.dashQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(HeroAnimationState.IDLE, sim.heroAnimation.currentState)

        // Valid stamina & cooldown -> triggers confirmed cleave
        sim.playerStamina = 100f
        sim.cleaveQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(HeroAnimationState.CLEAVE, sim.heroAnimation.currentState)
    }

    @Test
    fun dialogueAndDeadStatesAdvanceDuringSimulationEarlyReturnAndDeadRemainsUninterruptible() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)

        // Default horned unshielded IDLE strictly stays on col 0 (never col 1 speaking gesture)
        val loadoutHorned = sim.currentVisualLoadout()
        assertEquals(HeroVisualProfile.UNSHIELDED_DARK_HORNED, loadoutHorned.visualProfile)
        assertEquals(1, sim.heroAnimation.resolveCurrentClip(loadoutHorned).frameCount)
        assertEquals(0, sim.heroAnimation.resolveFrameIndex(sim.heroAnimation.resolveCurrentClip(loadoutHorned)))

        // Move near Chronicler at (5.8, 9.2) and open dialogue
        sim.playerX = 5.4f
        sim.playerY = 9.8f
        sim.update(0.016f, QualityTier.HIGH)
        sim.interactQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertTrue(sim.activeDialogue != null)
        assertEquals(HeroAnimationState.DIALOGUE, sim.heroAnimation.currentState)

        val xDuringDialogue = sim.playerX
        val seenDialogueFrames = mutableSetOf<Int>()
        sim.joyX = 1f
        repeat(18) {
            sim.update(0.045f, QualityTier.HIGH)
            val clip = sim.heroAnimation.resolveCurrentClip(loadoutHorned)
            seenDialogueFrames += sim.heroAnimation.resolveFrameIndex(clip)
        }
        assertEquals(xDuringDialogue, sim.playerX, 0.0001f) // movement frozen during dialogue
        assertTrue("DIALOGUE must cycle through gesture frames during early-exit", seenDialogueFrames.size >= 3)

        // Close dialogue -> returns to WALK immediately on next tick
        sim.selectDialogueChoice(null, null)
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(HeroAnimationState.WALK, sim.heroAnimation.currentState)

        // Trigger lethal defeat -> DEAD advances to col 7 (frame index 3) even though phase == DEFEAT early-returns
        sim.joyX = 0f
        sim.enemies[0].active = true
        sim.enemies[0].x = sim.playerX + 0.5f
        sim.enemies[0].y = sim.playerY
        sim.enemies[0].attackWindup = 0.01f
        sim.playerHp = 2
        sim.invulnTimer = 0f
        sim.update(0.02f, QualityTier.HIGH)
        assertEquals(GamePhase.DEFEAT, sim.phase)
        assertEquals(HeroAnimationState.DEAD, sim.heroAnimation.currentState)

        // Attempt lower-priority trigger while DEAD -> rejected
        assertFalse(sim.heroAnimation.triggerConfirmed(HeroAnimationState.HURT, 999L))

        repeat(20) {
            sim.update(0.04f, QualityTier.HIGH)
        }
        assertEquals(HeroAnimationState.DEAD, sim.heroAnimation.currentState)
        assertEquals(3, sim.heroAnimation.resolveFrameIndex(DarkHeroAtlasData.deadClip))
    }

    @Test
    fun heroLoadoutAndPortraitAccuracyReflectActualEquippedIdsHonestly() {
        val defaultLoadout = HeroVisualLoadout(weaponId = "iron_sword")
        assertEquals(HeroVisualProfile.UNSHIELDED_DARK_HORNED, defaultLoadout.visualProfile)
        assertEquals(HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED, defaultLoadout.portraitId)
        assertTrue(defaultLoadout.portraitMatchesLoadoutExactly)

        val shieldedLoadout = HeroVisualLoadout(weaponId = "iron_sword", shieldId = "kite_shield")
        assertEquals(HeroVisualProfile.SHIELDED_DARK, shieldedLoadout.visualProfile)
        assertEquals(HeroPortraitId.PORTRAIT_SHIELDED_DARK, shieldedLoadout.portraitId)
        assertTrue(shieldedLoadout.portraitMatchesLoadoutExactly)

        val hoodedLoadout = HeroVisualLoadout(weaponId = "iron_sword", cloakId = "shadow_hood")
        assertEquals(HeroVisualProfile.UNSHIELDED_DARK_HOODED, hoodedLoadout.visualProfile)
        assertEquals(HeroPortraitId.PORTRAIT_UNSHIELDED_HOODED, hoodedLoadout.portraitId)
        assertTrue(hoodedLoadout.portraitMatchesLoadoutExactly)

        // Wearing knight_helmet over shadow_hood falls back to horned helmet visual and marks mismatch
        val hoodWithHelmet = HeroVisualLoadout(
            weaponId = "iron_sword",
            helmetId = "knight_helmet",
            cloakId = "shadow_hood"
        )
        assertEquals(HeroVisualProfile.UNSHIELDED_DARK_HORNED, hoodWithHelmet.visualProfile)
        assertEquals(HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED, hoodWithHelmet.portraitId)
        assertFalse(hoodWithHelmet.portraitMatchesLoadoutExactly)

        // Equipping battle_axe still shows Dark Hero sword sprites/portraits but honestly reports false
        val axeLoadout = HeroVisualLoadout(weaponId = "battle_axe", shieldId = "kite_shield")
        assertEquals(HeroPortraitId.PORTRAIT_SHIELDED_DARK, axeLoadout.portraitId)
        assertFalse(axeLoadout.portraitMatchesLoadoutExactly)

        // Shared action sheets honestly report false in HeroAnimationController
        val anim = HeroAnimationController()
        anim.triggerConfirmed(HeroAnimationState.DASH, 1L)
        assertEquals(HeroVisualAppearance.HORNED_CRIMSON_UNARMED, anim.resolveCurrentClip(defaultLoadout).visualAppearance)
        assertFalse(anim.matchesClipLoadoutExactly(defaultLoadout))
    }

    @Test
    fun eightWayMovementAndDiagonalCombatHitDiagonalTarget() {
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        val enemy = sim.enemies[0]
        sim.playerX = enemy.x - 0.85f
        sim.playerY = enemy.y - 0.85f
        val startHp = enemy.hp

        // Move south-east toward enemy, then strike without snapping to 4 cardinal directions
        sim.joyX = 0.707f
        sim.joyY = 0.707f
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(Direction.SOUTH_EAST, sim.playerFacing)

        sim.joyX = 0f
        sim.joyY = 0f
        sim.attackQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertTrue("Diagonal SOUTH_EAST melee strike must hit SE enemy", enemy.hp < startHp)

        // Diagonal dash fallback when joystick released maintains SOUTH_EAST vector
        sim.dashQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(Direction.SOUTH_EAST.dirX, sim.dashDirX, 0.01f)
        assertEquals(Direction.SOUTH_EAST.dirY, sim.dashDirY, 0.01f)
    }

    @Test
    fun animationStateCategoriesAndFacingLockHoldDuringOneShotsAndReleaseOnCompletion() {
        // Canonical HeroAnimationState taxonomy & category metadata
        assertEquals(AnimationCategory.MOVEMENT, HeroAnimationState.IDLE.category)
        assertEquals(AnimationCategory.MOVEMENT, HeroAnimationState.WALK.category)
        assertEquals(AnimationCategory.GESTURE, HeroAnimationState.DIALOGUE.category)
        assertEquals(AnimationCategory.GESTURE, HeroAnimationState.INTERACT.category)
        assertEquals(AnimationCategory.COMBAT, HeroAnimationState.ATTACK.category)
        assertEquals(AnimationCategory.COMBAT, HeroAnimationState.CLEAVE.category)
        assertEquals(AnimationCategory.COMBAT, HeroAnimationState.DASH.category)
        assertEquals(AnimationCategory.STATUS, HeroAnimationState.HURT.category)
        assertEquals(AnimationCategory.STATUS, HeroAnimationState.DEAD.category)

        assertFalse(HeroAnimationState.IDLE.locksFacing)
        assertFalse(HeroAnimationState.WALK.locksFacing)
        assertFalse(HeroAnimationState.DIALOGUE.locksFacing)
        assertTrue(HeroAnimationState.ATTACK.locksFacing)
        assertTrue(HeroAnimationState.CLEAVE.locksFacing)
        assertTrue(HeroAnimationState.DASH.locksFacing)

        // Controller-level facing lock backed by AnimationState playback holder
        val anim = HeroAnimationController()
        anim.reset(Direction.NORTH)
        assertFalse(anim.isFacingLocked)
        assertTrue(
            anim.triggerConfirmed(
                state = HeroAnimationState.ATTACK,
                triggerSerial = 1L,
                direction = Direction.SOUTH_EAST
            )
        )
        assertTrue(anim.isFacingLocked)
        assertTrue(anim.playback.isFacingLocked)
        assertEquals(Direction.SOUTH_EAST, anim.resolveActiveDirection(Direction.WEST))

        // Higher-priority DASH overrides ATTACK and re-locks facing onto NORTH_WEST
        assertTrue(
            anim.triggerConfirmed(
                state = HeroAnimationState.DASH,
                triggerSerial = 2L,
                direction = Direction.NORTH_WEST
            )
        )
        assertTrue(anim.isFacingLocked)
        assertEquals(Direction.NORTH_WEST, anim.resolveActiveDirection(Direction.SOUTH))

        // Completing DASH unlocks facing and falls back to live direction
        anim.updateGameplay(dt = 0.25f, joyMag = 0.6f, hasDialogue = false, isDefeat = false, liveFacing = Direction.WEST)
        assertFalse(anim.isFacingLocked)
        assertEquals(Direction.WEST, anim.resolveActiveDirection(Direction.WEST))

        // Simulation-level facing lock: walking opposite direction mid-swing cannot spin hero early
        val sim = GameSimulation()
        sim.resetRun(GamePhase.PLAYING)
        sim.joyX = 1f
        sim.joyY = 0f
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(Direction.EAST, sim.playerFacing)

        sim.joyX = 0f
        sim.attackQueued = true
        sim.update(0.016f, QualityTier.HIGH)
        assertEquals(HeroAnimationState.ATTACK, sim.heroAnimation.currentState)
        assertTrue(sim.heroAnimation.isFacingLocked)
        assertEquals(Direction.EAST, sim.playerFacing)

        // Hold WEST while ATTACK clip is still mid-flight (2 * 0.04s = 0.08s < 0.22s) -> facing remains locked EAST
        sim.joyX = -1f
        repeat(2) { sim.update(0.04f, QualityTier.HIGH) }
        assertTrue(sim.heroAnimation.isFacingLocked)
        assertEquals(Direction.EAST, sim.playerFacing)

        // Advance past remaining attack duration (total 0.28s > 0.22s) -> lock releases and turns WEST
        repeat(5) { sim.update(0.04f, QualityTier.HIGH) }
        assertFalse(sim.heroAnimation.isFacingLocked)
        assertEquals(Direction.WEST, sim.playerFacing)

        // Equal-priority consecutive combo trigger with new serial restarts & re-locks facing onto interrupting direction
        anim.reset(Direction.SOUTH)
        assertTrue(anim.triggerConfirmed(HeroAnimationState.ATTACK, triggerSerial = 10L, direction = Direction.EAST))
        anim.updateGameplay(dt = 0.08f, joyMag = 0f, hasDialogue = false, isDefeat = false, liveFacing = Direction.WEST)
        assertTrue(anim.triggerConfirmed(HeroAnimationState.ATTACK, triggerSerial = 11L, direction = Direction.NORTH_WEST))
        assertEquals(0f, anim.stateTimer, 0.0001f)
        assertTrue(anim.isFacingLocked)
        assertEquals(Direction.NORTH_WEST, anim.lockedDirection)
        assertEquals(Direction.NORTH_WEST, anim.resolveActiveDirection(Direction.SOUTH))

        // Recovering from completed ATTACK into WALK resets loop phase cleanly to frame 0
        val shielded = HeroVisualLoadout(weaponId = "iron_sword", shieldId = "kite_shield")
        anim.updateGameplay(dt = 0.25f, joyMag = 0.9f, hasDialogue = false, isDefeat = false, liveFacing = Direction.NORTH_WEST)
        assertEquals(HeroAnimationState.WALK, anim.currentState)
        assertEquals(AnimationCategory.MOVEMENT, anim.currentCategory)
        assertEquals(0f, anim.cyclePhase, 0.0001f)
        assertEquals(0, anim.currentFrameIndex(shielded))

        // Continuous 8-way loop advancing then pausing to IDLE and resuming WALK resets to frame 0
        for (dir in Direction.entries) {
            anim.updateGameplay(dt = 0.05f, joyMag = 0.9f, hasDialogue = false, isDefeat = false, liveFacing = dir)
            val rect = anim.resolveFrame(shielded, dir)
            val expectedRow = DarkHeroAtlasData.walkShieldedClip.framesByDirection[dir.rowIndex]
            assertTrue("Frame must come from octant row $dir", expectedRow.contains(rect))
        }
        assertTrue(anim.cyclePhase > 0f)
        anim.updateGameplay(dt = 0.016f, joyMag = 0f, hasDialogue = false, isDefeat = false, liveFacing = Direction.SOUTH)
        assertEquals(HeroAnimationState.IDLE, anim.currentState)
        assertEquals(0f, anim.cyclePhase, 0.0001f)
        assertEquals(0, anim.currentFrameIndex(shielded))
    }

    @Test
    fun standaloneAnimationStateManagesFramesSpeedAndCycleLogicForAny8WayClip() {
        val walkClip = DarkHeroAtlasData.walkShieldedClip // 8 frames @ 11 fps
        val atkClip = DarkHeroAtlasData.attackShieldedClip // 8 frames @ 18 fps

        // Clip-agnostic cyclic playback across all 8 directions + composed basePlaybackSpeed * speedMultiplier
        val state = AnimationState()
        state.startCyclic(direction = Direction.SOUTH_WEST, baseSpeed = 1.5f, speedMult = 0.8f)
        assertEquals(1.2f, state.effectivePlaybackSpeed, 0.001f)
        assertTrue(state.loop)
        assertFalse(state.isFacingLocked)

        val dt = 0.05f
        state.advance(dt) // phase advances by 0.05 * 1.5 * 0.8 = 0.06s
        assertEquals(0.06f, state.cyclePhase, 0.0001f)
        val expectedFrame = (0.06f * walkClip.fps).toInt() % walkClip.frameCount
        assertEquals(expectedFrame, state.resolveFrameIndex(walkClip))
        assertEquals(
            walkClip.framesByDirection[Direction.SOUTH_WEST.rowIndex][expectedFrame],
            state.resolveSpriteRect(walkClip, Direction.SOUTH_WEST)
        )

        // Wrap boundary safety across exact 240s common period
        state.startCyclic(direction = Direction.NORTH_EAST, baseSpeed = 1f, speedMult = 1f)
        state.advance(AnimationState.CYCLE_WRAP_PERIOD_SEC + 0.10f)
        assertTrue(state.cyclePhase < 1.0f)
        assertEquals(0.10f, state.cyclePhase, 0.001f)

        // One-shot facing lock & holdFinalFrame vs auto-release on completion
        state.startOneShot(durationSec = 0.20f, direction = Direction.EAST, lockFacing = true, holdFinalFrame = false)
        assertTrue(state.isFacingLocked)
        assertEquals(Direction.EAST, state.resolveDirection(Direction.NORTH))
        assertFalse(state.advance(0.10f))
        assertTrue(state.isFacingLocked)
        assertEquals(atkClip.framesByDirection[Direction.EAST.rowIndex][4], state.resolveSpriteRect(atkClip, Direction.NORTH))
        assertTrue(state.advance(0.12f))
        assertTrue(state.isCompleted)
        assertFalse(state.isFacingLocked)
        assertEquals(Direction.NORTH, state.resolveDirection(Direction.NORTH))

        // holdFinalFrame keeps final frame & facing locked until reset/new state
        state.startOneShot(durationSec = 0.50f, direction = Direction.NORTH_WEST, lockFacing = true, holdFinalFrame = true)
        assertTrue(state.advance(1.0f))
        assertEquals(0.50f, state.elapsedTime, 0.0001f)
        assertTrue(state.isFacingLocked)
        assertEquals(DarkHeroAtlasData.deadClip.frameCount - 1, state.resolveFrameIndex(DarkHeroAtlasData.deadClip))
    }

    @Test
    fun monotonicCyclePhaseAdvancesWalkAndIdleContinuouslyWithoutPoppingOnInputChange() {
        val shieldedLoadout = HeroVisualLoadout(weaponId = "iron_sword", shieldId = "kite_shield")
        val clip = DarkHeroAtlasData.walkShieldedClip // 8 frames @ 11 fps
        val anim = HeroAnimationController()

        // Ramp joystick from high to low -> monotonic phase & frame progression never steps backward
        var prevPhase = 0f
        var prevUnwrappedFrame = 0f
        val magnitudes = floatArrayOf(1.0f, 0.95f, 0.8f, 0.55f, 0.35f, 0.20f, 0.90f, 0.15f)
        for (mag in magnitudes) {
            repeat(4) {
                anim.updateGameplay(dt = 0.016f, joyMag = mag, hasDialogue = false, isDefeat = false)
                assertEquals(HeroAnimationState.WALK, anim.currentState)
                assertTrue("cyclePhase must be monotonic", anim.cyclePhase >= prevPhase)
                val unwrappedFrame = anim.cyclePhase * clip.fps
                assertTrue(
                    "Continuous phase * fps must never jump backward on input change ($prevUnwrappedFrame -> $unwrappedFrame)",
                    unwrappedFrame >= prevUnwrappedFrame
                )
                prevPhase = anim.cyclePhase
                prevUnwrappedFrame = unwrappedFrame
                val idx = anim.resolveFrameIndex(clip)
                assertTrue(idx in 0 until clip.frameCount)
            }
        }

        // Faster input advances further in the same elapsed duration than slow walk
        val slow = HeroAnimationController()
        val fast = HeroAnimationController()
        repeat(10) {
            slow.updateGameplay(dt = 0.016f, joyMag = 0.2f, hasDialogue = false, isDefeat = false)
            fast.updateGameplay(dt = 0.016f, joyMag = 1.0f, hasDialogue = false, isDefeat = false)
        }
        assertTrue("Fast joystick input must advance cyclePhase further", fast.cyclePhase > slow.cyclePhase)

        // Base playback speed scaling accelerates walk progression
        val hasted = HeroAnimationController().apply { basePlaybackSpeed = 1.5f }
        repeat(10) {
            hasted.updateGameplay(dt = 0.016f, joyMag = 1.0f, hasDialogue = false, isDefeat = false)
        }
        assertTrue("basePlaybackSpeed=1.5f must outpace 1.0f walk cycle", hasted.cyclePhase > fast.cyclePhase)

        // Shielded IDLE loops continuously across 4 calm-stance frames
        val idleAnim = HeroAnimationController()
        val seenIdleFrames = mutableSetOf<Int>()
        val idleClip = idleAnim.resolveCurrentClip(shieldedLoadout)
        assertEquals(4, idleClip.frameCount)
        repeat(24) {
            idleAnim.updateGameplay(dt = 0.04f, joyMag = 0f, hasDialogue = false, isDefeat = false)
            assertEquals(HeroAnimationState.IDLE, idleAnim.currentState)
            seenIdleFrames += idleAnim.resolveFrameIndex(idleClip)
        }
        assertEquals(setOf(0, 1, 2, 3), seenIdleFrames)
    }

    // -------------------------------------------------------------------------
    // 3. PerformanceTelemetryTest
    // -------------------------------------------------------------------------

    @Test
    fun governorRingBufferComputesPartialAndWrapAroundP95AndJankCountCleanly() {
        val gov = ExynosQualityGovernor()
        gov.mode = QualityMode.AUTO

        var now = 1_000_000L
        // Partial buffer (< 120 samples): 18 frames @ 10ms, 2 frames @ 20ms (> 16.67ms)
        repeat(18) {
            now += 16_000_000L
            gov.recordFrame(now, 10f)
        }
        repeat(2) {
            now += 20_000_000L
            gov.recordFrame(now, 20f)
        }
        assertEquals(20, gov.sampleCount)
        assertEquals(2, gov.cpuSubmitJankCount)
        assertEquals(20f, gov.computeCpuSubmitP95Ms(), 0.05f)

        // Wrap around > 120 samples with fast 8ms frames -> old 20ms outliers evicted from P95 window
        repeat(130) {
            now += 10_000_000L
            gov.recordFrame(now, 8f)
        }
        assertEquals(ExynosQualityGovernor.FRAME_RING_CAPACITY, gov.sampleCount)
        assertEquals(2, gov.cpuSubmitJankCount) // cumulative jank count retained across run
        assertEquals(8f, gov.computeCpuSubmitP95Ms(), 0.05f)

        // Atlas telemetry propagation into HUD snapshot
        gov.updateAtlasTelemetry(
            decodeMs = 42.5f,
            uploadCallMs = 18.2f,
            totalCpuMs = 60.7f,
            textureMiB = 36f,
            glMaxTextureSize = 4096,
            legacyFallback = false
        )
        val sim = GameSimulation().apply { resetRun(GamePhase.PLAYING) }
        val snap = sim.buildHudSnapshot(gov, 4).telemetry
        assertEquals(4096, snap.maxTextureSize)
        assertEquals(36f, snap.atlasTextureMiB, 0.01f)
        assertFalse(snap.useLegacyHeroFallback)
        assertEquals(8f, snap.cpuSubmitP95Ms, 0.05f)

        gov.reset()
        assertEquals(0, gov.sampleCount)
        assertEquals(0, gov.cpuSubmitJankCount)
    }
}
