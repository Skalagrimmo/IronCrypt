package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AudioSettings
import com.example.data.GraphicsSettings
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.PauseMenuTab
import com.example.game.model.QualityMode
import com.example.game.model.QualityTier
import com.example.game.model.SaveSlotSummary
import com.example.ui.theme.AgedParchment
import com.example.ui.theme.ArcaneCobalt
import com.example.ui.theme.CharcoalShadow
import com.example.ui.theme.CrimsonForge
import com.example.ui.theme.IronBorder
import com.example.ui.theme.IronPanel
import com.example.ui.theme.IronSurfaceHigh
import com.example.ui.theme.MoltenBrass
import com.example.ui.theme.SteelMist
import com.example.ui.theme.VitalEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UnifiedPauseMenu(
    hud: HudUiState,
    settings: GraphicsSettings,
    audioSettings: AudioSettings,
    saveSummary: SaveSlotSummary,
    activeTab: PauseMenuTab,
    onSelectTab: (PauseMenuTab) -> Unit,
    onResume: () -> Unit,
    onSaveGame: () -> Unit,
    onLoadGame: () -> Unit,
    onRestart: () -> Unit,
    onReturnToTitle: () -> Unit,
    onSelectMode: (QualityMode) -> Unit,
    onToggleShaderLighting: (Boolean) -> Unit,
    onToggleTorchFlicker: (Boolean) -> Unit,
    onToggleGroundShadows: (Boolean) -> Unit,
    onToggleTorchHalos: (Boolean) -> Unit,
    onToggleScreenShake: (Boolean) -> Unit,
    onSelectResolutionScale: (Float) -> Unit,
    onSelectPoolBudget: (QualityTier) -> Unit,
    onToggleTelemetry: (Boolean) -> Unit,
    onToggleAmbient: (Boolean) -> Unit,
    onAmbientVolumeChange: (Float) -> Unit,
    onToggleSfx: (Boolean) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onTestSound: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalShadow)
            .clickable(onClick = onResume)
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(IronPanel)
                .border(2.dp, MoltenBrass.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PauseTabChip(
                        label = stringResource(R.string.label_pause_tab_game),
                        selected = activeTab == PauseMenuTab.GAME,
                        testTag = "pause_tab_game",
                        onClick = { onSelectTab(PauseMenuTab.GAME) }
                    )
                    PauseTabChip(
                        label = stringResource(R.string.label_pause_tab_graphics),
                        selected = activeTab == PauseMenuTab.GRAPHICS,
                        testTag = "pause_tab_graphics",
                        onClick = { onSelectTab(PauseMenuTab.GRAPHICS) }
                    )
                    PauseTabChip(
                        label = stringResource(R.string.label_pause_tab_audio),
                        selected = activeTab == PauseMenuTab.AUDIO,
                        testTag = "pause_tab_audio",
                        onClick = { onSelectTab(PauseMenuTab.AUDIO) }
                    )
                }

                IconButton(
                    onClick = onResume,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("close_pause_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = AgedParchment
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (activeTab) {
                PauseMenuTab.GAME -> PauseGameTabContent(
                    hud = hud,
                    saveSummary = saveSummary,
                    onResume = onResume,
                    onSaveGame = onSaveGame,
                    onLoadGame = onLoadGame,
                    onOpenGraphics = { onSelectTab(PauseMenuTab.GRAPHICS) },
                    onRestart = onRestart,
                    onReturnToTitle = onReturnToTitle
                )

                PauseMenuTab.GRAPHICS -> PauseGraphicsTabContent(
                    hud = hud,
                    settings = settings,
                    onSelectMode = onSelectMode,
                    onToggleShaderLighting = onToggleShaderLighting,
                    onToggleTorchFlicker = onToggleTorchFlicker,
                    onToggleGroundShadows = onToggleGroundShadows,
                    onToggleTorchHalos = onToggleTorchHalos,
                    onToggleScreenShake = onToggleScreenShake,
                    onSelectResolutionScale = onSelectResolutionScale,
                    onSelectPoolBudget = onSelectPoolBudget,
                    onToggleTelemetry = onToggleTelemetry
                )

                PauseMenuTab.AUDIO -> PauseAudioTabContent(
                    audioSettings = audioSettings,
                    onToggleAmbient = onToggleAmbient,
                    onAmbientVolumeChange = onAmbientVolumeChange,
                    onToggleSfx = onToggleSfx,
                    onSfxVolumeChange = onSfxVolumeChange,
                    onTestSound = onTestSound
                )
            }
        }
    }
}

@Composable
private fun PauseTabChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MoltenBrass else IronSurfaceHigh)
            .border(
                1.5.dp,
                if (selected) AgedParchment else IronBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) Color.Black else AgedParchment
        )
    }
}

@Composable
private fun PauseGameTabContent(
    hud: HudUiState,
    saveSummary: SaveSlotSummary,
    onResume: () -> Unit,
    onSaveGame: () -> Unit,
    onLoadGame: () -> Unit,
    onOpenGraphics: () -> Unit,
    onRestart: () -> Unit,
    onReturnToTitle: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(IronSurfaceHigh)
                .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (hud.phase == GamePhase.TITLE) "ПІДГОТОВКА ДО ЕКСПЕДИЦІЇ" else "ПАУЗА — ЕКСПЕДИЦІЯ У КРИПТІ",
                style = MaterialTheme.typography.titleMedium,
                color = MoltenBrass
            )
            Text(
                text = "HP: ${hud.hp}/${hud.maxHp}   •   ATK: ${hud.atkTotal}   •   DEF: ${hud.defTotal}   •   Золото: ${hud.gold}",
                style = MaterialTheme.typography.bodyMedium,
                color = AgedParchment
            )
            Text(
                text = "Знищено ворогів: ${hud.kills}   •   Босів подолано: ${hud.bossesDefeated}/${hud.totalBosses}",
                style = MaterialTheme.typography.labelMedium,
                color = SteelMist
            )
            val saveLine = if (saveSummary.hasSave && saveSummary.savedAtEpochMs > 0L) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(saveSummary.savedAtEpochMs))
                "DataStore слот: $timeStr  (HP ${saveSummary.hp}/${saveSummary.maxHp} • ${saveSummary.gold} золота)"
            } else {
                "DataStore слот: немає активного збереження"
            }
            Text(
                text = saveLine,
                style = MaterialTheme.typography.labelSmall,
                color = if (saveSummary.hasSave) VitalEmerald else SteelMist
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MoltenBrass,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pause_resume_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_resume),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Button(
                    onClick = onOpenGraphics,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IronSurfaceHigh,
                        contentColor = AgedParchment
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pause_open_graphics_button")
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.label_pause_tab_graphics),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Manual Save & Load controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveGame,
                    enabled = hud.phase == GamePhase.PLAYING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VitalEmerald.copy(alpha = 0.85f),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pause_save_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_save_game),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Button(
                    onClick = onLoadGame,
                    enabled = saveSummary.hasSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArcaneCobalt.copy(alpha = 0.85f),
                        contentColor = AgedParchment
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pause_load_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_load_game),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (hud.phase != GamePhase.TITLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onRestart,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonForge.copy(alpha = 0.85f),
                            contentColor = AgedParchment
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pause_restart_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_restart),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Button(
                        onClick = onReturnToTitle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IronSurfaceHigh,
                            contentColor = SteelMist
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pause_title_button")
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_title_menu),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseGraphicsTabContent(
    hud: HudUiState,
    settings: GraphicsSettings,
    onSelectMode: (QualityMode) -> Unit,
    onToggleShaderLighting: (Boolean) -> Unit,
    onToggleTorchFlicker: (Boolean) -> Unit,
    onToggleGroundShadows: (Boolean) -> Unit,
    onToggleTorchHalos: (Boolean) -> Unit,
    onToggleScreenShake: (Boolean) -> Unit,
    onSelectResolutionScale: (Float) -> Unit,
    onSelectPoolBudget: (QualityTier) -> Unit,
    onToggleTelemetry: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_exynos_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelMist
                )
                val badgeText = if (settings.isCustomOverride) {
                    "CUSTOM • ${(hud.telemetry.resolutionScale * 100).toInt()}%"
                } else {
                    "АКТИВНО: ${hud.telemetry.activeTier.label} • ${(hud.telemetry.resolutionScale * 100).toInt()}%"
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (settings.isCustomOverride) MoltenBrass else VitalEmerald,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(IronSurfaceHigh)
                        .border(1.dp, IronBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QualityMode.entries.forEach { mode ->
                    val selected = !settings.isCustomOverride && settings.qualityMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MoltenBrass else IronSurfaceHigh)
                            .border(
                                1.5.dp,
                                if (selected) AgedParchment else IronBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 10.dp, horizontal = 6.dp)
                            .testTag("quality_mode_${mode.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.labelUk,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color.Black else AgedParchment
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = String.format(
                    Locale.US,
                    "FPS: %d  |  %.1f ms  |  Draw Calls: %d  |  Шейдер світла: %.0f%%  |  OBJ: %d  PRT: %d",
                    hud.telemetry.fps,
                    hud.telemetry.frameTimeMs,
                    hud.telemetry.drawCalls,
                    hud.telemetry.shaderLightingStrength * 100f,
                    hud.telemetry.activeEntities,
                    hud.telemetry.activeParticles
                ),
                style = MaterialTheme.typography.labelSmall,
                color = VitalEmerald,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(IronSurfaceHigh)
                    .border(1.dp, IronBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Шейдер освітлення (uLightingStrength)",
                subtitle = "Динамічне освітлення тайлів та віньєтка навколо факелів",
                checked = settings.shaderLightingEnabled,
                testTag = "toggle_shader_lighting",
                onCheckedChange = onToggleShaderLighting
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Мерехтіння факелів і теплота (uFlicker / uWarmth)",
                subtitle = "Пульсація світла та тепла колірна градація у фрагментному шейдері",
                checked = settings.torchFlickerEnabled,
                testTag = "toggle_torch_flicker",
                onCheckedChange = onToggleTorchFlicker
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Тіні під персонажами (Ground Shadows)",
                subtitle = "М'які тіні під героєм та ворогами",
                checked = settings.groundShadowsEnabled,
                testTag = "toggle_ground_shadows",
                onCheckedChange = onToggleGroundShadows
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Адитивні ореоли світла (Additive Halos)",
                subtitle = "Світіння навколо факелів (відключайте для економії fill-rate)",
                checked = settings.torchHalosEnabled,
                testTag = "toggle_torch_halos",
                onCheckedChange = onToggleTorchHalos
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Тряска камери при ударах (Screen Shake)",
                subtitle = "Мікро-імпульс камери під час сильних ударів та босів",
                checked = settings.screenShakeEnabled,
                testTag = "toggle_screen_shake",
                onCheckedChange = onToggleScreenShake
            )
        }

        item {
            GraphicsChipSelectorRow(
                title = "Масштаб рендерингу OpenGL",
                options = listOf(
                    "70%" to (settings.resolutionScale <= 0.75f),
                    "85%" to (settings.resolutionScale in 0.76f..0.92f),
                    "100%" to (settings.resolutionScale > 0.92f)
                ),
                onSelectIndex = { idx ->
                    val scale = when (idx) {
                        0 -> 0.70f
                        1 -> 0.85f
                        else -> 1.0f
                    }
                    onSelectResolutionScale(scale)
                }
            )
        }

        item {
            GraphicsChipSelectorRow(
                title = "Ліміт об'єктів / частинок (CPU+GPU бюджет)",
                options = listOf(
                    "10 / 12" to (settings.poolBudgetTier == QualityTier.LOW),
                    "16 / 32" to (settings.poolBudgetTier == QualityTier.MEDIUM),
                    "24 / 64" to (settings.poolBudgetTier == QualityTier.HIGH)
                ),
                onSelectIndex = { idx ->
                    val tier = when (idx) {
                        0 -> QualityTier.LOW
                        1 -> QualityTier.MEDIUM
                        else -> QualityTier.HIGH
                    }
                    onSelectPoolBudget(tier)
                }
            )
        }

        item {
            GraphicsSwitchRow(
                title = "Показувати телеметрію FPS на екрані",
                subtitle = "Відображає FPS, час кадру та кількість активних об'єктів у HUD",
                checked = settings.showTelemetry,
                testTag = "telemetry_switch",
                onCheckedChange = onToggleTelemetry
            )
        }
    }
}

@Composable
private fun GraphicsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AgedParchment
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = SteelMist
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun GraphicsChipSelectorRow(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelectIndex: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = AgedParchment,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { idx, (label, active) ->
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) MoltenBrass else IronPanel)
                        .border(
                            1.dp,
                            if (active) AgedParchment else IronBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectIndex(idx) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) Color.Black else AgedParchment
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseAudioTabContent(
    audioSettings: AudioSettings,
    onToggleAmbient: (Boolean) -> Unit,
    onAmbientVolumeChange: (Float) -> Unit,
    onToggleSfx: (Boolean) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onTestSound: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AudioChannelCard(
            title = "Атмосфера підземелля (Ambient)",
            subtitle = "Похмурий ембієнт крипти (автопауза у фоні)",
            enabled = audioSettings.ambientEnabled,
            volume = audioSettings.ambientVolume,
            switchTag = "audio_ambient_switch",
            sliderTag = "audio_ambient_slider",
            onToggle = onToggleAmbient,
            onVolumeChange = onAmbientVolumeChange
        )

        AudioChannelCard(
            title = "Бойові та світові ефекти (SFX)",
            subtitle = "Удари зброєю, крит, ривок, зілля, скрині та важіль",
            enabled = audioSettings.sfxEnabled,
            volume = audioSettings.sfxVolume,
            switchTag = "audio_sfx_switch",
            sliderTag = "audio_sfx_slider",
            onToggle = onToggleSfx,
            onVolumeChange = onSfxVolumeChange
        )

        Button(
            onClick = onTestSound,
            enabled = audioSettings.sfxEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MoltenBrass,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("audio_test_button")
        ) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_test_sound),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun AudioChannelCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    volume: Float,
    switchTag: String,
    sliderTag: String,
    onToggle: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(IronSurfaceHigh)
            .border(1.dp, IronBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .clickable { onToggle(!enabled) }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgedParchment
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelMist
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(switchTag)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Гучність: ${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MoltenBrass else SteelMist,
                modifier = Modifier.width(106.dp)
            )
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = MoltenBrass,
                    activeTrackColor = MoltenBrass,
                    inactiveTrackColor = IronPanel
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .testTag(sliderTag)
            )
        }
    }
}
