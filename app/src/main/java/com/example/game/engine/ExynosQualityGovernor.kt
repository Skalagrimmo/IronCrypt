package com.example.game.engine

import com.example.data.GraphicsSettings
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.RenderConfig

/**
 * Adaptive performance governor tuned for Exynos 7870 (8x Cortex-A53 + Mali-T830 MP1).
 * Tracks rolling frame render/simulation time without heap allocations and resolves
 * the active [RenderConfig] combining preset auto-scaling and user shader/render overrides.
 */
class ExynosQualityGovernor {

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

    private var overBudgetFrames: Int = 0
    private var underBudgetFrames: Int = 0

    fun reset() {
        emaFrameMs = 16.6f
        currentFrameMs = 16.6f
        overBudgetFrames = 0
        underBudgetFrames = 0
        frameCounter = 0
        lastFpsTimestampNs = 0L
        if (mode == QualityMode.AUTO) {
            activeTier = QualityTier.HIGH
        }
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
     * Called once per rendered frame on the GL thread. Zero allocations.
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
