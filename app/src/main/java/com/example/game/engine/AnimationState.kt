package com.example.game.engine

import com.example.game.model.AnimationClip
import com.example.game.model.Direction
import com.example.game.model.SpriteRect
import kotlin.math.floor

/**
 * Mutable, zero-allocation runtime playback state for any 8-directional [AnimationClip].
 *
 * Manages frame indices, composed playback speed (`basePlaybackSpeed * speedMultiplier`),
 * continuous cycle progression (`cyclePhase`), and one-shot duration / facing locks so it
 * can be owned by [HeroAnimationController] or reused directly by other 8-way actors.
 */
class AnimationState {

    companion object {
        // Common exact cycle period (in seconds) across all cyclic DarkHeroAtlasData clips:
        // 240 * 11.0 (8f walk) = 2640 % 8 == 0; 240 * 7.5 (4f walk) = 1800 % 4 == 0;
        // 240 * 4.5 (4f idle) = 1080 % 4 == 0; 240 * 5.5 (4f dialogue) = 1320 % 4 == 0.
        const val CYCLE_WRAP_PERIOD_SEC: Float = 240f
        private const val MIN_ONESHOT_DURATION_SEC: Float = 0.05f
    }

    var elapsedTime: Float = 0f
        private set

    var duration: Float = 0f
        private set

    /**
     * Monotonic scaled clock (in seconds at base 1.0x clip FPS) driving cyclic frame lookup.
     */
    var cyclePhase: Float = 0f
        private set

    var basePlaybackSpeed: Float = 1f
        private set

    var speedMultiplier: Float = 1f
        private set

    var loop: Boolean = true
        private set

    var direction: Direction = Direction.SOUTH
        private set

    var lockedDirection: Direction = Direction.SOUTH
        private set

    var locksFacing: Boolean = false
        private set

    var holdFinalFrame: Boolean = false
        private set

    val effectivePlaybackSpeed: Float
        get() = basePlaybackSpeed * speedMultiplier

    val isActiveOneShot: Boolean
        get() = !loop && (holdFinalFrame || elapsedTime < duration)

    val isCompleted: Boolean
        get() = !loop && elapsedTime >= duration

    val isFacingLocked: Boolean
        get() = locksFacing && isActiveOneShot

    val cycleProgress: Float
        get() = if (!loop) {
            val dur = duration.coerceAtLeast(MIN_ONESHOT_DURATION_SEC)
            (elapsedTime / dur).coerceIn(0f, 1f)
        } else {
            val frac = cyclePhase - floor(cyclePhase)
            frac.coerceIn(0f, 0.9999f)
        }

    fun reset(
        initialDirection: Direction = Direction.SOUTH,
        baseSpeed: Float = 1f
    ) {
        elapsedTime = 0f
        duration = 0f
        cyclePhase = 0f
        basePlaybackSpeed = baseSpeed.coerceAtLeast(0f)
        speedMultiplier = 1f
        loop = true
        direction = initialDirection
        lockedDirection = initialDirection
        locksFacing = false
        holdFinalFrame = false
    }

    fun setBasePlaybackSpeed(speed: Float) {
        basePlaybackSpeed = speed.coerceAtLeast(0f)
    }

    fun setSpeedMultiplier(multiplier: Float) {
        speedMultiplier = multiplier.coerceAtLeast(0f)
    }

    fun setDirection(dir: Direction) {
        direction = dir
        if (!isFacingLocked) {
            lockedDirection = dir
        }
    }

    fun startCyclic(
        direction: Direction = this.direction,
        baseSpeed: Float = this.basePlaybackSpeed,
        speedMult: Float = 1f
    ) {
        elapsedTime = 0f
        duration = 0f
        cyclePhase = 0f
        basePlaybackSpeed = baseSpeed.coerceAtLeast(0f)
        speedMultiplier = speedMult.coerceAtLeast(0f)
        loop = true
        this.direction = direction
        lockedDirection = direction
        locksFacing = false
        holdFinalFrame = false
    }

    fun startOneShot(
        durationSec: Float,
        direction: Direction = this.direction,
        lockFacing: Boolean = true,
        holdFinalFrame: Boolean = false,
        baseSpeed: Float = this.basePlaybackSpeed
    ) {
        elapsedTime = 0f
        duration = durationSec.coerceAtLeast(MIN_ONESHOT_DURATION_SEC)
        cyclePhase = 0f
        basePlaybackSpeed = baseSpeed.coerceAtLeast(0f)
        speedMultiplier = 1f
        loop = false
        this.direction = direction
        lockedDirection = direction
        locksFacing = lockFacing
        this.holdFinalFrame = holdFinalFrame
    }

    fun releaseFacingLock() {
        locksFacing = false
    }

    /**
     * Advances timers and cycle phase by [dt] seconds without allocating objects.
     *
     * @return `true` when advancing a non-looping clip that has reached completion.
     */
    fun advance(
        dt: Float,
        speedMultiplier: Float = this.speedMultiplier,
        clampOneShotToDuration: Boolean = this.holdFinalFrame
    ): Boolean {
        val safeDt = dt.coerceAtLeast(0f)
        val clampedMult = speedMultiplier.coerceAtLeast(0f)
        this.speedMultiplier = clampedMult

        if (!loop) {
            val nextElapsed = elapsedTime + safeDt * basePlaybackSpeed
            elapsedTime = if (clampOneShotToDuration) {
                nextElapsed.coerceAtMost(duration)
            } else {
                nextElapsed
            }
            if (elapsedTime >= duration) {
                if (!holdFinalFrame) {
                    locksFacing = false
                }
                return true
            }
            return false
        }

        elapsedTime += safeDt * basePlaybackSpeed
        val deltaPhase = safeDt * basePlaybackSpeed * clampedMult
        if (deltaPhase > 0f) {
            val nextPhase = cyclePhase + deltaPhase
            cyclePhase = if (nextPhase >= CYCLE_WRAP_PERIOD_SEC) {
                nextPhase % CYCLE_WRAP_PERIOD_SEC
            } else {
                nextPhase
            }
        }
        return false
    }

    fun resolveDirection(liveFacing: Direction = this.direction): Direction =
        if (isFacingLocked) lockedDirection else liveFacing

    fun resolveFrameIndex(clip: AnimationClip): Int {
        val count = clip.frameCount
        if (count <= 1) return 0
        return if (!loop) {
            val dur = duration.coerceAtLeast(MIN_ONESHOT_DURATION_SEC)
            val progress = (elapsedTime / dur).coerceIn(0f, 0.9999f)
            (progress * count).toInt().coerceIn(0, count - 1)
        } else {
            val rawFrame = floor(cyclePhase * clip.fps).toInt()
            ((rawFrame % count) + count) % count
        }
    }

    fun resolveSpriteRect(
        clip: AnimationClip,
        liveFacing: Direction = this.direction
    ): SpriteRect {
        val activeFacing = resolveDirection(liveFacing)
        val dirRow = clip.framesByDirection[activeFacing.rowIndex]
        return dirRow[resolveFrameIndex(clip)]
    }
}
