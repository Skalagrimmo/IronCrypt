package com.example.game.engine

import com.example.data.GraphicsSettings
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.RenderConfig
import java.util.Locale
import kotlin.math.ceil

/**
 * Adaptive quality governor for mobile OpenGL ES 2.0 devices.
 * Tracks rolling GL-thread CPU-submit durations in a zero-allocation 120-sample circular buffer,
 * computes P95 and >16.67ms jank counts off the hot path, and exposes GPU/atlas load telemetry.
 * It uses measured frame workload rather than SoC or GPU-vendor detection.
 */
class AdaptiveQualityGovernor {

    companion object {
        const val FRAME_RING_CAPACITY = 120
        const val JANK_THRESHOLD_MS = 16.67f
    }

    @Volatile
    var customSettings: GraphicsSettings = GraphicsSettings()
        set(value) {
            field = value
            mode = value.qualityMode
        }

    @Volatile
    var mode: QualityMode = QualityMode.AUTO
        set(value) {
            field = value
            if (value != QualityMode.AUTO) {
                activeTier = when (value) {
                    QualityMode.HIGH -> QualityTier.HIGH
                    QualityMode.MEDIUM -> QualityTier.MEDIUM
                    QualityMode.LOW -> QualityTier.LOW
                    QualityMode.AUTO -> activeTier
                }
                overBudgetFrames = 0
                underBudgetFrames = 0
            }
        }

    @Volatile
    var activeTier: QualityTier = QualityTier.HIGH
        private set

    private var emaFrameMs: Float = 16.6f
    private var lastFpsTimestampNs: Long = 0L
    private var frameCounter: Int = 0

    @Volatile
    var currentFps: Int = 60
        private set

    @Volatile
    var currentFrameMs: Float = 16.6f
        private set

    // Zero-allocation circular ring buffer for CPU submit times
    private val ringLock = Any()
    private val frameRing = FloatArray(FRAME_RING_CAPACITY)
    private val scratchSorted = FloatArray(FRAME_RING_CAPACITY)
    private var ringCursor: Int = 0

    @Volatile
    var sampleCount: Int = 0
        private set

    @Volatile
    var cpuSubmitJankCount: Int = 0
        private set

    // Atlas & GPU initialization telemetry (set from GL thread on surface creation)
    @Volatile var atlasDecodeMs: Float = 0f
    @Volatile var atlasUploadCallMs: Float = 0f
    @Volatile var atlasLoadTotalCpuMs: Float = 0f
    @Volatile var atlasTextureMiB: Float = 4f
    @Volatile var maxTextureSize: Int = 0
    @Volatile var useLegacyHeroFallback: Boolean = false

    private var overBudgetFrames: Int = 0
    private var underBudgetFrames: Int = 0

    fun reset() {
        emaFrameMs = 16.6f
        currentFrameMs = 16.6f
        overBudgetFrames = 0
        underBudgetFrames = 0
        frameCounter = 0
        lastFpsTimestampNs = 0L
        synchronized(ringLock) {
            ringCursor = 0
            sampleCount = 0
            cpuSubmitJankCount = 0
            frameRing.fill(0f)
        }
        if (mode == QualityMode.AUTO) {
            activeTier = QualityTier.HIGH
        }
    }

    fun updateAtlasTelemetry(
        decodeMs: Float,
        uploadCallMs: Float,
        totalCpuMs: Float,
        textureMiB: Float,
        glMaxTextureSize: Int,
        legacyFallback: Boolean
    ) {
        atlasDecodeMs = decodeMs
        atlasUploadCallMs = uploadCallMs
        atlasLoadTotalCpuMs = totalCpuMs
        atlasTextureMiB = textureMiB
        maxTextureSize = glMaxTextureSize
        useLegacyHeroFallback = legacyFallback
    }

    /**
     * Computes CPU frame-submit P95 from the rolling ring buffer using a pre-allocated scratch array.
     * Safe to call from HUD snapshot builder at ~15 Hz without per-frame allocations.
     */
    fun computeCpuSubmitP95Ms(): Float {
        synchronized(ringLock) {
            val count = sampleCount
            if (count <= 0) return currentFrameMs
            System.arraycopy(frameRing, 0, scratchSorted, 0, count)
            for (i in 1 until count) {
                val key = scratchSorted[i]
                var j = i - 1
                while (j >= 0 && scratchSorted[j] > key) {
                    scratchSorted[j + 1] = scratchSorted[j]
                    j--
                }
                scratchSorted[j + 1] = key
            }
            val rank = ceil(count * 0.95).toInt().coerceIn(1, count) - 1
            return scratchSorted[rank]
        }
    }

    fun formatPerfReportLine(drawCalls: Int, entities: Int, particles: Int): String {
        val p95 = computeCpuSubmitP95Ms()
        return String.format(
            Locale.US,
            "fps=%d emaMs=%.2f p95Ms=%.2f jank=%d samples=%d tier=%s scale=%.2f dc=%d obj=%d prt=%d glMax=%d texMiB=%.1f fallback=%s decMs=%.1f upMs=%.1f",
            currentFps,
            currentFrameMs,
            p95,
            cpuSubmitJankCount,
            sampleCount,
            activeTier.label,
            resolveRenderConfig().resolutionScale,
            drawCalls,
            entities,
            particles,
            maxTextureSize,
            atlasTextureMiB,
            useLegacyHeroFallback,
            atlasDecodeMs,
            atlasUploadCallMs
        )
    }

    fun resolveRenderConfig(): RenderConfig {
        val settings = customSettings
        if (!settings.isCustomOverride) {
            return RenderConfig.fromTier(activeTier, isCustom = false)
        }
        val budget = settings.poolBudgetTier
        val lighting = if (settings.shaderLightingEnabled) {
            when (activeTier) {
                QualityTier.LOW -> 0.75f
                QualityTier.MEDIUM -> 0.85f
                QualityTier.HIGH -> 1.0f
            }
        } else 0.0f
        val warmth = if (settings.torchFlickerEnabled && settings.shaderLightingEnabled) 1.0f else 0.25f

        return RenderConfig(
            activeTier = activeTier,
            isCustomOverride = true,
            resolutionScale = settings.resolutionScale,
            maxSimultaneousEntities = budget.maxSimultaneousEntities,
            maxParticles = budget.maxParticles,
            shaderLightingStrength = lighting,
            warmthTintStrength = warmth,
            torchFlickerEnabled = settings.torchFlickerEnabled,
            enableTorchGlow = settings.torchHalosEnabled,
            enableGroundShadows = settings.groundShadowsEnabled,
            enableScreenShake = settings.screenShakeEnabled
        )
    }

    /**
     * Called once per rendered frame on the GL thread. Zero heap allocations.
     * @return true if [activeTier] changed on this frame.
     */
    fun recordFrame(nowNs: Long, workDurationMs: Float): Boolean {
        if (lastFpsTimestampNs == 0L) {
            lastFpsTimestampNs = nowNs
        }
        frameCounter++
        val elapsedNs = nowNs - lastFpsTimestampNs
        if (elapsedNs >= 500_000_000L) {
            currentFps = ((frameCounter * 1_000_000_000L) / elapsedNs).toInt().coerceIn(1, 120)
            frameCounter = 0
            lastFpsTimestampNs = nowNs
        }

        val clampedWork = workDurationMs.coerceIn(0.1f, 200f)
        synchronized(ringLock) {
            frameRing[ringCursor] = clampedWork
            ringCursor = (ringCursor + 1) % FRAME_RING_CAPACITY
            if (sampleCount < FRAME_RING_CAPACITY) sampleCount++
            if (workDurationMs > JANK_THRESHOLD_MS) cpuSubmitJankCount++
        }

        emaFrameMs = emaFrameMs * 0.92f + workDurationMs.coerceIn(1f, 80f) * 0.08f
        currentFrameMs = emaFrameMs

        if (mode != QualityMode.AUTO) {
            val target = when (mode) {
                QualityMode.HIGH -> QualityTier.HIGH
                QualityMode.MEDIUM -> QualityTier.MEDIUM
                QualityMode.LOW -> QualityTier.LOW
                QualityMode.AUTO -> activeTier
            }
            if (activeTier != target) {
                activeTier = target
                return true
            }
            return false
        }

        val downgradeThresholdMs = when (activeTier) {
            QualityTier.HIGH -> 18.5f
            QualityTier.MEDIUM -> 24.0f
            QualityTier.LOW -> 999f
        }
        val upgradeThresholdMs = when (activeTier) {
            QualityTier.HIGH -> -1f
            QualityTier.MEDIUM -> 12.5f
            QualityTier.LOW -> 15.0f
        }

        if (emaFrameMs > downgradeThresholdMs) {
            overBudgetFrames++
            underBudgetFrames = 0
            if (overBudgetFrames >= 75) {
                overBudgetFrames = 0
                val next = when (activeTier) {
                    QualityTier.HIGH -> QualityTier.MEDIUM
                    QualityTier.MEDIUM -> QualityTier.LOW
                    QualityTier.LOW -> QualityTier.LOW
                }
                if (next != activeTier) {
                    activeTier = next
                    return true
                }
            }
        } else if (emaFrameMs < upgradeThresholdMs) {
            underBudgetFrames++
            overBudgetFrames = 0
            if (underBudgetFrames >= 180) {
                underBudgetFrames = 0
                val next = when (activeTier) {
                    QualityTier.LOW -> QualityTier.MEDIUM
                    QualityTier.MEDIUM -> QualityTier.HIGH
                    QualityTier.HIGH -> QualityTier.HIGH
                }
                if (next != activeTier) {
                    activeTier = next
                    return true
                }
            }
        } else {
            if (overBudgetFrames > 0) overBudgetFrames--
            if (underBudgetFrames > 0) underBudgetFrames--
        }
        return false
    }
}
