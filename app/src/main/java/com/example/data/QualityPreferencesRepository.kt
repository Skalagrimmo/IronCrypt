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

    private val keyAmbientEnabled = booleanPreferencesKey("audio_ambient_enabled")
    private val keyAmbientVolume = floatPreferencesKey("audio_ambient_volume")
    private val keySfxEnabled = booleanPreferencesKey("audio_sfx_enabled")
    private val keySfxVolume = floatPreferencesKey("audio_sfx_volume")

    val audioSettingsFlow: Flow<AudioSettings> = context.ironCryptDataStore.data.map { prefs ->
        AudioSettings(
            ambientEnabled = prefs[keyAmbientEnabled] ?: true,
            ambientVolume = (prefs[keyAmbientVolume] ?: 0.55f).coerceIn(0f, 1f),
            sfxEnabled = prefs[keySfxEnabled] ?: true,
            sfxVolume = (prefs[keySfxVolume] ?: 0.85f).coerceIn(0f, 1f)
        )
    }

    suspend fun setAmbientEnabled(enabled: Boolean) {
        context.ironCryptDataStore.edit { prefs ->
            prefs[keyAmbientEnabled] = enabled
        }
    }

    suspend fun setAmbientVolume(volume: Float) {
        context.ironCryptDataStore.edit { prefs ->
            prefs[keyAmbientVolume] = volume.coerceIn(0f, 1f)
        }
    }

    suspend fun setSfxEnabled(enabled: Boolean) {
        context.ironCryptDataStore.edit { prefs ->
            prefs[keySfxEnabled] = enabled
        }
    }

    suspend fun setSfxVolume(volume: Float) {
        context.ironCryptDataStore.edit { prefs ->
            prefs[keySfxVolume] = volume.coerceIn(0f, 1f)
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

data class AudioSettings(
    val ambientEnabled: Boolean = true,
    val ambientVolume: Float = 0.55f,
    val sfxEnabled: Boolean = true,
    val sfxVolume: Float = 0.85f
)
