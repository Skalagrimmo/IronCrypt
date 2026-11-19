package com.example.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.game.model.GamePhase
import com.example.game.model.PauseMenuTab
import com.example.game.model.QualityMode
import com.example.game.model.SaveSlotSummary
import com.example.game.model.SpriteId
import com.example.ui.components.CombatActionPad
import com.example.ui.components.DialogueOverlay
import com.example.ui.components.FloatingCombatTextOverlay
import com.example.ui.components.HudTopBar
import com.example.ui.components.InventoryOverlay
import com.example.ui.components.MiniMapOverlay
import com.example.ui.components.UnifiedPauseMenu
import com.example.ui.components.VirtualJoystick
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.CharcoalShadow
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.FoundryVoid
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.VitalEmerald

@Composable
fun IronCryptScreen(viewModel: GameViewModel) {
    val hud by viewModel.hudState.collectAsStateWithLifecycle()
    val settings by viewModel.graphicsSettings.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val saveSummary by viewModel.saveSummary.collectAsStateWithLifecycle()
    val isInventoryOpen by viewModel.isInventoryOpen.collectAsStateWithLifecycle()
    val isPauseMenuOpen by viewModel.isPauseMenuOpen.collectAsStateWithLifecycle()
    val activePauseTab by viewModel.activePauseTab.collectAsStateWithLifecycle()
    val renderScale by viewModel.activeRenderScale.collectAsStateWithLifecycle()
    val miniMapMode by viewModel.miniMapMode.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var surfacePxW by remember { mutableIntStateOf(0) }
    var surfacePxH by remember { mutableIntStateOf(0) }

    val glView = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(viewModel.renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    LaunchedEffect(renderScale, surfacePxW, surfacePxH) {
        if (surfacePxW > 0 && surfacePxH > 0) {
            val scaledW = (surfacePxW * renderScale).toInt().coerceAtLeast(320)
            val scaledH = (surfacePxH * renderScale).toInt().coerceAtLeast(180)
            glView.holder.setFixedSize(scaledW, scaledH)
        }
    }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onAppResume()
                    glView.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onAppPause()
                    glView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = isInventoryOpen || isPauseMenuOpen || hud.activeDialogue != null || hud.phase == GamePhase.PLAYING) {
        when {
            isInventoryOpen -> viewModel.closeInventory()
            isPauseMenuOpen -> viewModel.closePauseMenu()
            hud.activeDialogue != null -> viewModel.selectDialogueChoice(null, null)
            hud.phase == GamePhase.PLAYING -> viewModel.openPauseMenu(PauseMenuTab.GAME)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FoundryVoid)
            .onSizeChanged { size ->
                surfacePxW = size.width
                surfacePxH = size.height
            }
    ) {
        AndroidView(
            factory = { glView },
            modifier = Modifier.fillMaxSize()
        )

        FloatingCombatTextOverlay(
            isPlaying = hud.phase == GamePhase.PLAYING && !isInventoryOpen && !isPauseMenuOpen,
            hasActiveProvider = viewModel::hasActiveFloatingTexts,
            snapshotProvider = viewModel::exportFloatingTextSnapshot,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (hud.phase == GamePhase.PLAYING) {
                HudTopBar(
                    hud = hud,
                    atlasStore = viewModel.atlasStore,
                    showTelemetry = settings.showTelemetry,
                    onOpenInventory = viewModel::openInventory,
                    onOpenPauseMenu = { viewModel.openPauseMenu(PauseMenuTab.GAME) },
                    onOpenGraphicsTab = { viewModel.openPauseMenu(PauseMenuTab.GRAPHICS) },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                AnimatedVisibility(
                    visible = hud.toastMessageUk != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 66.dp)
                ) {
                    Text(
                        text = hud.toastMessageUk ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MoltenBrass,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(IronPanel.copy(alpha = 0.9f))
                            .border(1.5.dp, MoltenBrass, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                VirtualJoystick(
                    onVectorChange = viewModel::setJoystick,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 4.dp)
                )

                MiniMapOverlay(
                    miniMap = hud.miniMap,
                    mode = miniMapMode,
                    onToggleExpand = viewModel::toggleMiniMapExpanded,
                    onMinimize = viewModel::minimizeMiniMap,
                    onRestore = viewModel::restoreMiniMap,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp)
                )

                CombatActionPad(
                    atlasStore = viewModel.atlasStore,
                    cleaveCooldownRatio = hud.cleaveCooldownRatio,
                    dashCooldownRatio = hud.dashCooldownRatio,
                    potionCount = hud.potionCount,
                    interactPromptUk = hud.interactPromptUk,
                    onAttack = viewModel::triggerAttack,
                    onCleave = viewModel::triggerCleave,
                    onDash = viewModel::triggerDash,
                    onPotion = viewModel::triggerPotion,
                    onInteract = viewModel::triggerInteract,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 4.dp)
                )
            }
        }

        if (hud.phase == GamePhase.TITLE && !isPauseMenuOpen) {
            TitleOverlay(
                viewModel = viewModel,
                selectedMode = settings.qualityMode,
                isCustomOverride = settings.isCustomOverride,
                saveSummary = saveSummary,
                onContinue = viewModel::continueSavedExpedition,
                onStart = viewModel::startExpedition,
                onOpenGraphicsSettings = { viewModel.openPauseMenu(PauseMenuTab.GRAPHICS) }
            )
        }

        hud.activeDialogue?.let { activeDlg ->
            DialogueOverlay(
                dialogue = activeDlg,
                atlasStore = viewModel.atlasStore,
                onSelectChoice = viewModel::selectDialogueChoice
            )
        }

        if (isInventoryOpen) {
            InventoryOverlay(
                hud = hud,
                atlasStore = viewModel.atlasStore,
                onEquipOrUse = viewModel::equipOrUseItem,
                onUnequipSlot = viewModel::unequipItem,
                onMoveItem = viewModel::moveBackpackItem,
                onClose = viewModel::closeInventory
            )
        }

        if (isPauseMenuOpen) {
            UnifiedPauseMenu(
                hud = hud,
                settings = settings,
                audioSettings = audioSettings,
                saveSummary = saveSummary,
                activeTab = activePauseTab,
                onSelectTab = viewModel::selectPauseTab,
                onResume = viewModel::closePauseMenu,
                onSaveGame = viewModel::saveGameNow,
                onLoadGame = viewModel::loadSavedGame,
                onRestart = viewModel::restartRun,
                onReturnToTitle = viewModel::returnToTitle,
                onSelectMode = viewModel::setQualityMode,
                onToggleShaderLighting = viewModel::toggleShaderLighting,
                onToggleTorchFlicker = viewModel::toggleTorchFlicker,
                onToggleGroundShadows = viewModel::toggleGroundShadows,
                onToggleTorchHalos = viewModel::toggleTorchHalos,
                onToggleScreenShake = viewModel::toggleScreenShake,
                onSelectResolutionScale = viewModel::setResolutionScale,
                onSelectPoolBudget = viewModel::setPoolBudgetTier,
                onToggleTelemetry = viewModel::setShowTelemetry,
                onToggleAmbient = viewModel::setAmbientEnabled,
                onAmbientVolumeChange = viewModel::setAmbientVolume,
                onToggleSfx = viewModel::setSfxEnabled,
                onSfxVolumeChange = viewModel::setSfxVolume,
                onTestSound = viewModel::previewCombatSfx
            )
        }

        if (hud.phase == GamePhase.VICTORY || hud.phase == GamePhase.DEFEAT) {
            EndRunOverlay(
                victory = hud.phase == GamePhase.VICTORY,
                kills = hud.kills,
                gold = hud.gold,
                onRestart = viewModel::restartRun
            )
        }
    }
}

@Composable
private fun TitleOverlay(
    viewModel: GameViewModel,
    selectedMode: QualityMode,
    isCustomOverride: Boolean,
    saveSummary: SaveSlotSummary,
    onContinue: () -> Unit,
    onStart: () -> Unit,
    onOpenGraphicsSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CharcoalShadow)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(IronPanel.copy(alpha = 0.94f))
                .border(2.dp, MoltenBrass.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.dark_hero_title_portrait),
                    contentDescription = "Dark Hero",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(86.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, MoltenBrass.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(6.dp))
                Image(
                    bitmap = viewModel.atlasStore.getSpriteBitmap(SpriteId.BOSS_MECH_1),
                    contentDescription = "Iron Automaton",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(72.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayLarge,
                    color = MoltenBrass
                )
                Text(
                    text = stringResource(R.string.title_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = AgedParchment
                )
                Text(
                    text = stringResource(R.string.title_exynos_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = VitalEmerald
                )

                if (saveSummary.hasSave) {
                    Text(
                        text = "Збережено: HP ${saveSummary.hp}/${saveSummary.maxHp} • ${saveSummary.gold} золота • Боси ${saveSummary.bossesDefeated}/${saveSummary.totalBosses}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MoltenBrass,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(IronSurfaceHigh)
                            .border(1.dp, MoltenBrass.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityMode.entries.forEach { mode ->
                        val active = !isCustomOverride && selectedMode == mode
                        Text(
                            text = mode.labelUk,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) Color.Black else AgedParchment,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) MoltenBrass else IronSurfaceHigh)
                                .border(1.dp, if (active) AgedParchment else IronBorder, RoundedCornerShape(6.dp))
                                .clickable { viewModel.setQualityMode(mode) }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (saveSummary.hasSave) {
                        Button(
                            onClick = onContinue,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MoltenBrass,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("continue_expedition_button")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_continue_expedition),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saveSummary.hasSave) IronSurfaceHigh else MoltenBrass,
                            contentColor = if (saveSummary.hasSave) AgedParchment else Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("start_expedition_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_start_expedition),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenGraphicsSettings,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("title_graphics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.action_graphics_settings),
                            tint = AgedParchment,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.label_pause_tab_graphics),
                            style = MaterialTheme.typography.labelMedium,
                            color = AgedParchment
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndRunOverlay(
    victory: Boolean,
    kills: Int,
    gold: Int,
    onRestart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CharcoalShadow)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(IronPanel)
                .border(2.dp, if (victory) MoltenBrass else CrimsonForge, RoundedCornerShape(14.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(if (victory) R.string.label_victory else R.string.label_defeat),
                style = MaterialTheme.typography.headlineMedium,
                color = if (victory) MoltenBrass else CrimsonForge
            )
            Text(
                text = "Знищено ворогів: $kills   •   Зібрано золота: $gold",
                style = MaterialTheme.typography.bodyLarge,
                color = AgedParchment
            )
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (victory) MoltenBrass else CrimsonForge,
                    contentColor = if (victory) Color.Black else AgedParchment
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("restart_run_button")
            ) {
                Text(
                    text = stringResource(R.string.action_restart),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
