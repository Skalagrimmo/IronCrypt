package com.example.game.engine

import com.example.game.model.AnimationClip
import com.example.game.model.DarkHeroAtlasData
import com.example.game.model.Direction
import com.example.game.model.HeroVisualAppearance
import com.example.game.model.HeroVisualLoadout
import com.example.game.model.SpriteRect

/**
 * Semantic grouping for Dark Hero animations.
 */
enum class AnimationCategory {
    MOVEMENT,
    GESTURE,
    COMBAT,
    STATUS
}

/**
 * Canonical hero animation states for priority arbitration, looping, facing locks, and timing.
 */
enum class HeroAnimationState(
    val category: AnimationCategory,
    val priority: Int,
    val loop: Boolean,
    val locksFacing: Boolean,
    val defaultDurationSec: Float
) {
    IDLE(
        category = AnimationCategory.MOVEMENT,
        priority = 10,
        loop = true,
        locksFacing = false,
        defaultDurationSec = 0f
    ),
    WALK(
        category = AnimationCategory.MOVEMENT,
        priority = 20,
        loop = true,
        locksFacing = false,
        defaultDurationSec = 0f
    ),
    DIALOGUE(
        category = AnimationCategory.GESTURE,
        priority = 30,
        loop = true,
        locksFacing = false,
        defaultDurationSec = 0f
    ),
    INTERACT(
        category = AnimationCategory.GESTURE,
        priority = 40,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.40f
    ),
    ATTACK(
        category = AnimationCategory.COMBAT,
        priority = 50,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.22f
    ),
    CLEAVE(
        category = AnimationCategory.COMBAT,
        priority = 60,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.28f
    ),
    DASH(
        category = AnimationCategory.COMBAT,
        priority = 70,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.22f
    ),
    HURT(
        category = AnimationCategory.STATUS,
        priority = 80,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.28f
    ),
    DEAD(
        category = AnimationCategory.STATUS,
        priority = 100,
        loop = false,
        locksFacing = true,
        defaultDurationSec = 0.50f
    )
}

/**
 * Zero-allocation animation state machine for the 8-direction Dark Hero.
 *
 * Delegates frame index, composed playback speed (`basePlaybackSpeed * speedMultiplier`),
 * continuous cycle progression, and directional facing lock tracking to a reusable
 * [AnimationState] instance while enforcing [HeroAnimationState] event priority and
 * [HeroVisualLoadout] clip selection.
 */
class HeroAnimationController {

    companion object {
        private const val JOY_MOVE_THRESHOLD = 0.08f
        private const val WALK_SPEED_BASE = 0.65f
        private const val WALK_SPEED_RANGE = 0.65f
    }

    /**
     * Reusable zero-allocation playback holder tracking frame timing, speed, cycle phase, and facing locks.
     */
    val playback: AnimationState = AnimationState()

    var currentState: HeroAnimationState = HeroAnimationState.IDLE
        private set

    val currentCategory: AnimationCategory
        get() = currentState.category

    private var lastTriggerSerial: Long = -1L

    val stateTimer: Float
        get() = playback.elapsedTime

    val clipDuration: Float
        get() = playback.duration

    val cyclePhase: Float
        get() = playback.cyclePhase

    val lockedDirection: Direction
        get() = playback.lockedDirection

    val isOneShotActive: Boolean
        get() = currentState == HeroAnimationState.DEAD || playback.isActiveOneShot

    val isFacingLocked: Boolean
        get() = currentState == HeroAnimationState.DEAD || playback.isFacingLocked

    val cycleProgress: Float
        get() = playback.cycleProgress

    var basePlaybackSpeed: Float
        get() = playback.basePlaybackSpeed
        set(value) {
            playback.setBasePlaybackSpeed(value)
        }

    fun currentFrameIndex(loadout: HeroVisualLoadout): Int =
        playback.resolveFrameIndex(resolveCurrentClip(loadout))

    fun reset(initialFacing: Direction = Direction.SOUTH) {
        currentState = HeroAnimationState.IDLE
        lastTriggerSerial = -1L
        playback.reset(initialDirection = initialFacing, baseSpeed = 1f)
    }

    /**
     * Checks whether [state] is allowed to interrupt the currently active state.
     */
    fun canAcceptTrigger(state: HeroAnimationState, triggerSerial: Long): Boolean {
        if (state.loop) return false
        if (triggerSerial <= lastTriggerSerial) return false
        if (currentState == HeroAnimationState.DEAD) return false
        if (!currentState.loop && playback.elapsedTime < playback.duration && state.priority < currentState.priority) {
            return false
        }
        return true
    }

    /**
     * Triggers a one-shot animation strictly from a confirmed simulation event and locks
     * the hero's 8-directional facing via [playback] until the clip finishes or is overridden.
     */
    fun triggerConfirmed(
        state: HeroAnimationState,
        triggerSerial: Long,
        durationSec: Float = state.defaultDurationSec,
        direction: Direction = playback.lockedDirection
    ): Boolean {
        if (!canAcceptTrigger(state, triggerSerial)) return false
        lastTriggerSerial = triggerSerial
        currentState = state
        playback.startOneShot(
            durationSec = durationSec,
            direction = direction,
            lockFacing = state.locksFacing,
            holdFinalFrame = (state == HeroAnimationState.DEAD)
        )
        return true
    }

    /**
     * Returns the facing direction that gameplay and rendering should display right now.
     */
    fun resolveActiveDirection(liveFacing: Direction): Direction =
        playback.resolveDirection(liveFacing)

    /**
     * Advances animation during normal active gameplay. Zero allocations.
     */
    fun updateGameplay(
        dt: Float,
        joyMag: Float,
        hasDialogue: Boolean,
        isDefeat: Boolean,
        liveFacing: Direction = playback.lockedDirection
    ) {
        if (isDefeat) {
            enterDeadIfNeeded(liveFacing)
            playback.advance(dt = dt, clampOneShotToDuration = true)
            return
        }

        val clampedMag = joyMag.coerceIn(0f, 1f)
        val walkMult = if (clampedMag > JOY_MOVE_THRESHOLD) {
            WALK_SPEED_BASE + clampedMag * WALK_SPEED_RANGE
        } else {
            1f
        }

        if (!currentState.loop) {
            val finished = playback.advance(
                dt = dt,
                clampOneShotToDuration = (currentState == HeroAnimationState.DEAD)
            )
            if (!finished) {
                playback.setDirection(liveFacing)
                return
            }
            val nextBase = resolveBaseState(clampedMag, hasDialogue)
            enterBaseState(
                target = nextBase,
                liveFacing = liveFacing,
                speedMult = if (nextBase == HeroAnimationState.WALK) walkMult else 1f
            )
            return
        }

        playback.setDirection(liveFacing)
        val targetBase = resolveBaseState(clampedMag, hasDialogue)
        val stepMultiplier = if (targetBase == HeroAnimationState.WALK) walkMult else 1f
        if (targetBase != currentState) {
            enterBaseState(
                target = targetBase,
                liveFacing = liveFacing,
                speedMult = stepMultiplier
            )
        } else {
            playback.advance(dt = dt, speedMultiplier = stepMultiplier)
        }
    }

    /**
     * Updates only visual animations (`DIALOGUE` / `IDLE` / `DEAD`) while gameplay physics are paused
     * (e.g. during active NPC dialogue or post-defeat screen before `GameSimulation.update` early-returns).
     */
    fun updateVisualOnly(
        dt: Float,
        hasDialogue: Boolean,
        isDefeat: Boolean,
        liveFacing: Direction = playback.lockedDirection
    ) {
        if (isDefeat) {
            enterDeadIfNeeded(liveFacing)
            playback.advance(dt = dt, clampOneShotToDuration = true)
            return
        }

        if (currentState == HeroAnimationState.DEAD) {
            playback.advance(dt = dt, clampOneShotToDuration = true)
            return
        }

        if (!currentState.loop) {
            val finished = playback.advance(dt = dt, clampOneShotToDuration = false)
            if (!finished) return
        }

        playback.setDirection(liveFacing)
        val target = if (hasDialogue) HeroAnimationState.DIALOGUE else HeroAnimationState.IDLE
        if (currentState != target) {
            enterBaseState(target = target, liveFacing = liveFacing, speedMult = 1f)
        } else {
            playback.advance(dt = dt, speedMultiplier = 1f)
        }
    }

    private fun enterBaseState(
        target: HeroAnimationState,
        liveFacing: Direction,
        speedMult: Float
    ) {
        currentState = target
        playback.startCyclic(
            direction = liveFacing,
            speedMult = speedMult
        )
    }

    private fun enterDeadIfNeeded(liveFacing: Direction) {
        if (currentState != HeroAnimationState.DEAD) {
            currentState = HeroAnimationState.DEAD
            val targetDir = playback.resolveDirection(liveFacing)
            playback.startOneShot(
                durationSec = HeroAnimationState.DEAD.defaultDurationSec,
                direction = targetDir,
                lockFacing = true,
                holdFinalFrame = true
            )
        }
    }

    private fun resolveBaseState(joyMag: Float, hasDialogue: Boolean): HeroAnimationState = when {
        hasDialogue -> HeroAnimationState.DIALOGUE
        joyMag > JOY_MOVE_THRESHOLD -> HeroAnimationState.WALK
        else -> HeroAnimationState.IDLE
    }

    fun resolveCurrentClip(loadout: HeroVisualLoadout): AnimationClip = when (currentState) {
        HeroAnimationState.DEAD -> DarkHeroAtlasData.deadClip
        HeroAnimationState.HURT -> DarkHeroAtlasData.hurtClip
        HeroAnimationState.DASH -> DarkHeroAtlasData.dashRollClip
        HeroAnimationState.CLEAVE -> DarkHeroAtlasData.cleaveCrimsonClip
        HeroAnimationState.ATTACK -> if (loadout.hasShield) {
            DarkHeroAtlasData.attackShieldedClip
        } else {
            DarkHeroAtlasData.cleaveCrimsonClip
        }
        HeroAnimationState.INTERACT -> DarkHeroAtlasData.interactClip
        HeroAnimationState.DIALOGUE -> DarkHeroAtlasData.dialogueClip
        HeroAnimationState.WALK -> if (loadout.hasShield) {
            DarkHeroAtlasData.walkShieldedClip
        } else {
            DarkHeroAtlasData.walkHoodedClip
        }
        HeroAnimationState.IDLE -> when {
            loadout.hasShield -> DarkHeroAtlasData.idleShieldedClip
            loadout.hasShadowHoodLook -> DarkHeroAtlasData.idleHoodedClip
            else -> DarkHeroAtlasData.idleHornedUnshieldedClip
        }
    }

    fun resolveFrameIndex(clip: AnimationClip): Int = playback.resolveFrameIndex(clip)

    fun resolveFrame(loadout: HeroVisualLoadout, facing: Direction): SpriteRect {
        val clip = resolveCurrentClip(loadout)
        return playback.resolveSpriteRect(clip, facing)
    }

    fun matchesClipLoadoutExactly(loadout: HeroVisualLoadout): Boolean {
        val appearance = resolveCurrentClip(loadout).visualAppearance
        return when (appearance) {
            HeroVisualAppearance.HORNED_DARK_SHIELDED ->
                loadout.hasShield && loadout.weaponId == "iron_sword" &&
                    loadout.cloakId == null && (loadout.helmetId == null || loadout.helmetId == "knight_helmet")
            HeroVisualAppearance.HORNED_DARK_UNSHIELDED ->
                !loadout.hasShield && loadout.weaponId == "iron_sword" &&
                    loadout.cloakId == null && (loadout.helmetId == null || loadout.helmetId == "knight_helmet")
            HeroVisualAppearance.SMOOTH_HOOD_DARK_UNSHIELDED ->
                !loadout.hasShield && loadout.weaponId == "iron_sword" && loadout.hasShadowHoodLook
            HeroVisualAppearance.SMOOTH_HOOD_CRIMSON_UNSHIELDED,
            HeroVisualAppearance.HORNED_CRIMSON_UNARMED,
            HeroVisualAppearance.HORNED_CRIMSON_UNSHIELDED -> false
        }
    }
}
