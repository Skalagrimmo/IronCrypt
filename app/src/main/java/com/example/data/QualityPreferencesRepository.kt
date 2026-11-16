package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ironCryptDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ironcrypt_settings"
)

data class GraphicsSettings(
    val qualityMode: QualityMode = QualityMode.AUTO,
    val isCustomOverride: Boolean = false,
    val shaderLightingEnabled: Boolean = true,
    val torchFlickerEnabled: Boolean = true,
    val groundShadowsEnabled: Boolean = true,
    val torchHalosEnabled: Boolean = true,
    val screenShakeEnabled: Boolean = true,
    val resolutionScale: Float = 1.0f,
    val poolBudgetTier: QualityTier = QualityTier.HIGH,
    val showTelemetry: Boolean = true
) {
    companion object {
        fun forPreset(mode: QualityMode, showTelemetry: Boolean = true): GraphicsSettings {
            val tier = when (mode) {
                QualityMode.LOW -> QualityTier.LOW
                QualityMode.MEDIUM -> QualityTier.MEDIUM
                QualityMode.HIGH, QualityMode.AUTO -> QualityTier.HIGH
            }
            return GraphicsSettings(
                qualityMode = mode,
                isCustomOverride = false,
                shaderLightingEnabled = tier.shaderLightingStrength > 0.05f,
                torchFlickerEnabled = tier.enableTorchFlicker,
                groundShadowsEnabled = tier.enableGroundShadows,
                torchHalosEnabled = tier.enableTorchGlow,
                screenShakeEnabled = tier.enableScreenShake,
                resolutionScale = tier.resolutionScale,
                poolBudgetTier = tier,
                showTelemetry = showTelemetry
            )
        }
    }
}

class QualityPreferencesRepository(private val context: Context) {

    private val keyQualityMode = stringPreferencesKey("quality_mode")
    private val keyIsCustom = booleanPreferencesKey("is_custom_override")
    private val keyShaderLighting = booleanPreferencesKey("shader_lighting")
    private val keyTorchFlicker = booleanPreferencesKey("torch_flicker")
    private val keyGroundShadows = booleanPreferencesKey("ground_shadows")
    private val keyTorchHalos = booleanPreferencesKey("torch_halos")
    private val keyScreenShake = booleanPreferencesKey("screen_shake")
    private val keyResolutionScale = floatPreferencesKey("resolution_scale")
    private val keyPoolBudget = stringPreferencesKey("pool_budget_tier")
    private val keyShowTelemetry = booleanPreferencesKey("show_telemetry")

    val settingsFlow: Flow<GraphicsSettings> = context.ironCryptDataStore.data.map { prefs ->
        val modeName = prefs[keyQualityMode] ?: QualityMode.AUTO.name
        val mode = runCatching { QualityMode.valueOf(modeName) }.getOrDefault(QualityMode.AUTO)
        val defaults = GraphicsSettings.forPreset(mode)
        val poolName = prefs[keyPoolBudget] ?: defaults.poolBudgetTier.name
        val poolTier = runCatching { QualityTier.valueOf(poolName) }.getOrDefault(defaults.poolBudgetTier)

        GraphicsSettings(
            qualityMode = mode,
            isCustomOverride = prefs[keyIsCustom] ?: false,
            shaderLightingEnabled = prefs[keyShaderLighting] ?: defaults.shaderLightingEnabled,
            torchFlickerEnabled = prefs[keyTorchFlicker] ?: defaults.torchFlickerEnabled,
            groundShadowsEnabled = prefs[keyGroundShadows] ?: defaults.groundShadowsEnabled,
            torchHalosEnabled = prefs[keyTorchHalos] ?: defaults.torchHalosEnabled,
            screenShakeEnabled = prefs[keyScreenShake] ?: defaults.screenShakeEnabled,
            resolutionScale = prefs[keyResolutionScale] ?: defaults.resolutionScale,
            poolBudgetTier = poolTier,
            showTelemetry = prefs[keyShowTelemetry] ?: true
        )
    }

    suspend fun applyPreset(mode: QualityMode) {
        context.ironCryptDataStore.edit { prefs ->
            val currentTelemetry = prefs[keyShowTelemetry] ?: true
            val preset = GraphicsSettings.forPreset(mode, currentTelemetry)
            writeGraphicsFields(prefs, preset)
        }
    }

    suspend fun updateCustom(transform: (GraphicsSettings) -> GraphicsSettings) {
        context.ironCryptDataStore.edit { prefs ->
            val modeName = prefs[keyQualityMode] ?: QualityMode.AUTO.name
            val mode = runCatching { QualityMode.valueOf(modeName) }.getOrDefault(QualityMode.AUTO)
            val defaults = GraphicsSettings.forPreset(mode)
            val poolName = prefs[keyPoolBudget] ?: defaults.poolBudgetTier.name
            val poolTier = runCatching { QualityTier.valueOf(poolName) }.getOrDefault(defaults.poolBudgetTier)

            val current = GraphicsSettings(
                qualityMode = mode,
                isCustomOverride = prefs[keyIsCustom] ?: false,
                shaderLightingEnabled = prefs[keyShaderLighting] ?: defaults.shaderLightingEnabled,
                torchFlickerEnabled = prefs[keyTorchFlicker] ?: defaults.torchFlickerEnabled,
                groundShadowsEnabled = prefs[keyGroundShadows] ?: defaults.groundShadowsEnabled,
                torchHalosEnabled = prefs[keyTorchHalos] ?: defaults.torchHalosEnabled,
                screenShakeEnabled = prefs[keyScreenShake] ?: defaults.screenShakeEnabled,
                resolutionScale = prefs[keyResolutionScale] ?: defaults.resolutionScale,
                poolBudgetTier = poolTier,
                showTelemetry = prefs[keyShowTelemetry] ?: true
            )
            val updated = transform(current).copy(isCustomOverride = true)
            writeGraphicsFields(prefs, updated)
        }
    }

    suspend fun setShowTelemetry(show: Boolean) {
        context.ironCryptDataStore.edit { prefs ->
            prefs[keyShowTelemetry] = show
        }
    }

    private fun writeGraphicsFields(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        settings: GraphicsSettings
    ) {
        prefs[keyQualityMode] = settings.qualityMode.name
        prefs[keyIsCustom] = settings.isCustomOverride
        prefs[keyShaderLighting] = settings.shaderLightingEnabled
        prefs[keyTorchFlicker] = settings.torchFlickerEnabled
        prefs[keyGroundShadows] = settings.groundShadowsEnabled
        prefs[keyTorchHalos] = settings.torchHalosEnabled
        prefs[keyScreenShake] = settings.screenShakeEnabled
        prefs[keyResolutionScale] = settings.resolutionScale
        prefs[keyPoolBudget] = settings.poolBudgetTier.name
        prefs[keyShowTelemetry] = settings.showTelemetry
    }
}
