package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.IronCryptSoundManager
import com.example.data.AudioSettings
import com.example.data.GameSaveRepository
import com.example.data.GraphicsSettings
import com.example.data.QualityPreferencesRepository
import com.example.game.engine.ExynosQualityGovernor
import com.example.game.engine.GameSimulation
import com.example.game.engine.IronCryptRenderer
import com.example.game.model.AtlasBitmapStore
import com.example.game.model.FloatingTextFrameSnapshot
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.MiniMapDisplayMode
import com.example.game.model.PauseMenuTab
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.SaveSlotSummary
import com.example.game.model.SoundCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = QualityPreferencesRepository(application)
    private val saveRepo = GameSaveRepository(application)
    val soundManager = IronCryptSoundManager(application)
    val atlasStore = AtlasBitmapStore(application)
    val simulation = GameSimulation()
    val governor = ExynosQualityGovernor()

    private val soundCueDrainScratch = ArrayList<SoundCue>(16)

    private val _hudState = MutableStateFlow(simulation.buildHudSnapshot(governor, 0))
    val hudState: StateFlow<HudUiState> = _hudState.asStateFlow()

    private val _isInventoryOpen = MutableStateFlow(false)
    val isInventoryOpen: StateFlow<Boolean> = _isInventoryOpen.asStateFlow()

    private val _isPauseMenuOpen = MutableStateFlow(false)
    val isPauseMenuOpen: StateFlow<Boolean> = _isPauseMenuOpen.asStateFlow()

    private val _activePauseTab = MutableStateFlow(PauseMenuTab.GAME)
    val activePauseTab: StateFlow<PauseMenuTab> = _activePauseTab.asStateFlow()

    private val _activeRenderScale = MutableStateFlow(QualityTier.HIGH.resolutionScale)
    val activeRenderScale: StateFlow<Float> = _activeRenderScale.asStateFlow()

    private val _miniMapMode = MutableStateFlow(MiniMapDisplayMode.COMPACT)
    val miniMapMode: StateFlow<MiniMapDisplayMode> = _miniMapMode.asStateFlow()

    val graphicsSettings: StateFlow<GraphicsSettings> = prefsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = GraphicsSettings()
    )

    val audioSettings: StateFlow<AudioSettings> = prefsRepo.audioSettingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AudioSettings()
    )

    val saveSummary: StateFlow<SaveSlotSummary> = saveRepo.saveSummaryFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SaveSlotSummary()
    )

    private var clearedEndRunSave = false

    val renderer: IronCryptRenderer = IronCryptRenderer(
        context = application,
        simulation = simulation,
        governor = governor,
        onScaleRequested = { scale ->
            _activeRenderScale.value = scale
        },
        onHudUpdate = {
            syncHud()
        },
        onSoundCuesReady = {
            flushSoundCues()
        }
    )

    init {
        viewModelScope.launch {
            prefsRepo.settingsFlow.collect { settings ->
                governor.customSettings = settings
                _activeRenderScale.value = governor.resolveRenderConfig().resolutionScale
                syncHud()
            }
        }
        viewModelScope.launch {
            prefsRepo.audioSettingsFlow.collect { audio ->
                soundManager.applySettings(audio)
            }
        }
    }

    fun startExpedition() {
        clearedEndRunSave = false
        governor.reset()
        simulation.resetRun(GamePhase.PLAYING)
        _isInventoryOpen.value = false
        _isPauseMenuOpen.value = false
        syncPauseFlag()
        syncHud()
    }

    fun continueSavedExpedition() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { saveRepo.loadGame() }
            if (snapshot != null) {
                clearedEndRunSave = false
                governor.reset()
                simulation.restoreSnapshot(snapshot)
                _isInventoryOpen.value = false
                _isPauseMenuOpen.value = false
                syncPauseFlag()
                syncHud()
            } else {
                startExpedition()
            }
        }
    }

    fun restartRun() {
        startExpedition()
    }

    fun returnToTitle() {
        autoSaveIfPlaying()
        _isInventoryOpen.value = false
        _isPauseMenuOpen.value = false
        simulation.resetRun(GamePhase.TITLE)
        syncPauseFlag()
        syncHud()
    }

    fun saveGameNow() {
        if (simulation.phase != GamePhase.PLAYING) return
        val snapshot = simulation.exportSnapshot()
        viewModelScope.launch(Dispatchers.IO) {
            saveRepo.saveGame(snapshot)
            simulation.showExternalToast("Гру збережено")
            syncHud()
        }
    }

    fun loadSavedGame() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { saveRepo.loadGame() }
            if (snapshot != null) {
                clearedEndRunSave = false
                simulation.restoreSnapshot(snapshot)
                _isInventoryOpen.value = false
                _isPauseMenuOpen.value = false
                syncPauseFlag()
                syncHud()
            } else {
                simulation.showExternalToast("Збереження не знайдено")
                syncHud()
            }
        }
    }

    fun autoSaveIfPlaying() {
        if (simulation.phase == GamePhase.PLAYING) {
            val snapshot = simulation.exportSnapshot()
            viewModelScope.launch(Dispatchers.IO) {
                saveRepo.saveGame(snapshot)
            }
        }
    }

    fun setJoystick(dx: Float, dy: Float) {
        simulation.joyX = dx
        simulation.joyY = dy
    }

    fun triggerAttack() {
        simulation.attackQueued = true
    }

    fun triggerCleave() {
        simulation.cleaveQueued = true
    }

    fun triggerDash() {
        simulation.dashQueued = true
    }

    fun triggerInteract() {
        simulation.interactQueued = true
    }

    fun triggerPotion() {
        simulation.potionQueued = true
    }

    fun openInventory() {
        _isInventoryOpen.value = true
        _isPauseMenuOpen.value = false
        syncPauseFlag()
        syncHud()
    }

    fun closeInventory() {
        _isInventoryOpen.value = false
        syncPauseFlag()
    }

    fun equipOrUseItem(itemId: String) {
        simulation.equipOrUseItem(itemId)
        syncHud()
    }

    fun unequipItem(slotType: ItemType) {
        simulation.unequipSlot(slotType)
        syncHud()
    }

    fun moveBackpackItem(fromItemId: String, toItemId: String) {
        simulation.reorderBackpackItem(fromItemId, toItemId)
        syncHud()
    }

    fun selectDialogueChoice(nextNodeId: String?, actionTag: String?) {
        simulation.selectDialogueChoice(nextNodeId, actionTag)
        syncHud()
    }

    fun openPauseMenu(tab: PauseMenuTab = PauseMenuTab.GAME) {
        autoSaveIfPlaying()
        _activePauseTab.value = tab
        _isPauseMenuOpen.value = true
        _isInventoryOpen.value = false
        syncPauseFlag()
        syncHud()
    }

    fun selectPauseTab(tab: PauseMenuTab) {
        _activePauseTab.value = tab
    }

    fun closePauseMenu() {
        _isPauseMenuOpen.value = false
        syncPauseFlag()
    }

    fun toggleMiniMapExpanded() {
        _miniMapMode.value = when (_miniMapMode.value) {
            MiniMapDisplayMode.MINIMIZED -> MiniMapDisplayMode.COMPACT
            MiniMapDisplayMode.COMPACT -> MiniMapDisplayMode.EXPANDED
            MiniMapDisplayMode.EXPANDED -> MiniMapDisplayMode.COMPACT
        }
    }

    fun minimizeMiniMap() {
        _miniMapMode.value = MiniMapDisplayMode.MINIMIZED
    }

    fun restoreMiniMap() {
        _miniMapMode.value = MiniMapDisplayMode.COMPACT
    }

    fun setQualityMode(mode: QualityMode) {
        val next = GraphicsSettings.forPreset(mode, graphicsSettings.value.showTelemetry)
        governor.customSettings = next
        _activeRenderScale.value = governor.resolveRenderConfig().resolutionScale
        viewModelScope.launch {
            prefsRepo.applyPreset(mode)
        }
        syncHud()
    }

    fun toggleShaderLighting(enabled: Boolean) = updateCustom { it.copy(shaderLightingEnabled = enabled) }
    fun toggleTorchFlicker(enabled: Boolean) = updateCustom { it.copy(torchFlickerEnabled = enabled) }
    fun toggleGroundShadows(enabled: Boolean) = updateCustom { it.copy(groundShadowsEnabled = enabled) }
    fun toggleTorchHalos(enabled: Boolean) = updateCustom { it.copy(torchHalosEnabled = enabled) }
    fun toggleScreenShake(enabled: Boolean) = updateCustom { it.copy(screenShakeEnabled = enabled) }
    fun setResolutionScale(scale: Float) = updateCustom { it.copy(resolutionScale = scale) }
    fun setPoolBudgetTier(tier: QualityTier) = updateCustom { it.copy(poolBudgetTier = tier) }

    fun setShowTelemetry(show: Boolean) {
        governor.customSettings = governor.customSettings.copy(showTelemetry = show)
        viewModelScope.launch {
            prefsRepo.setShowTelemetry(show)
        }
    }

    private fun updateCustom(transform: (GraphicsSettings) -> GraphicsSettings) {
        val updated = transform(governor.customSettings).copy(isCustomOverride = true)
        governor.customSettings = updated
        _activeRenderScale.value = governor.resolveRenderConfig().resolutionScale
        viewModelScope.launch {
            prefsRepo.updateCustom(transform)
        }
        syncHud()
    }

    fun setAmbientEnabled(enabled: Boolean) {
        soundManager.applySettings(audioSettings.value.copy(ambientEnabled = enabled))
        viewModelScope.launch { prefsRepo.setAmbientEnabled(enabled) }
    }

    fun setAmbientVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        soundManager.applySettings(audioSettings.value.copy(ambientVolume = clamped))
        viewModelScope.launch { prefsRepo.setAmbientVolume(clamped) }
    }

    fun setSfxEnabled(enabled: Boolean) {
        soundManager.applySettings(audioSettings.value.copy(sfxEnabled = enabled))
        viewModelScope.launch { prefsRepo.setSfxEnabled(enabled) }
    }

    fun setSfxVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        soundManager.applySettings(audioSettings.value.copy(sfxVolume = clamped))
        viewModelScope.launch { prefsRepo.setSfxVolume(clamped) }
    }

    fun previewCombatSfx() {
        soundManager.playCue(SoundCue.CRIT)
    }

    fun onAppResume() {
        soundManager.onAppResume()
    }

    fun onAppPause() {
        autoSaveIfPlaying()
        soundManager.onAppPause()
    }

    fun hasActiveFloatingTexts(): Boolean = simulation.hasActiveFloatingTexts

    fun exportFloatingTextSnapshot(): FloatingTextFrameSnapshot =
        simulation.exportFloatingTextSnapshot(renderer.renderCamX, renderer.renderCamY)

    private fun flushSoundCues() {
        soundCueDrainScratch.clear()
        simulation.drainSoundCues(soundCueDrainScratch)
        soundManager.playCues(soundCueDrainScratch)
    }

    private fun syncPauseFlag() {
        val overlayOpen = _isInventoryOpen.value || _isPauseMenuOpen.value
        simulation.isPausedForOverlay = overlayOpen
        soundManager.setMenuDucked(overlayOpen)
    }

    private fun syncHud() {
        val snapshot = simulation.buildHudSnapshot(governor, renderer.lastDrawCalls)
        _hudState.value = snapshot
        if ((snapshot.phase == GamePhase.VICTORY || snapshot.phase == GamePhase.DEFEAT) && !clearedEndRunSave) {
            clearedEndRunSave = true
            viewModelScope.launch(Dispatchers.IO) {
                saveRepo.clearSave()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        soundManager.release()
    }
}
