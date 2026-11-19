package com.example.game.model

enum class QualityMode(val labelUk: String) {
    AUTO("АВТО (Exynos)"),
    LOW("НИЗЬКА"),
    MEDIUM("СЕРЕДНЯ"),
    HIGH("ВИСОКА")
}

enum class QualityTier(
    val label: String,
    val resolutionScale: Float,
    val maxSimultaneousEntities: Int,
    val maxParticles: Int,
    val shaderLightingStrength: Float,
    val warmthTintStrength: Float,
    val enableTorchFlicker: Boolean,
    val enableTorchGlow: Boolean,
    val enableGroundShadows: Boolean,
    val enableScreenShake: Boolean
) {
    LOW(
        label = "LOW",
        resolutionScale = 0.70f,
        maxSimultaneousEntities = 10,
        maxParticles = 12,
        shaderLightingStrength = 0.0f,
        warmthTintStrength = 0.0f,
        enableTorchFlicker = false,
        enableTorchGlow = false,
        enableGroundShadows = false,
        enableScreenShake = false
    ),
    MEDIUM(
        label = "MED",
        resolutionScale = 0.85f,
        maxSimultaneousEntities = 16,
        maxParticles = 32,
        shaderLightingStrength = 0.65f,
        warmthTintStrength = 0.55f,
        enableTorchFlicker = true,
        enableTorchGlow = true,
        enableGroundShadows = true,
        enableScreenShake = true
    ),
    HIGH(
        label = "HIGH",
        resolutionScale = 1.0f,
        maxSimultaneousEntities = 24,
        maxParticles = 64,
        shaderLightingStrength = 1.0f,
        warmthTintStrength = 1.0f,
        enableTorchFlicker = true,
        enableTorchGlow = true,
        enableGroundShadows = true,
        enableScreenShake = true
    )
}

/**
 * Resolved per-frame rendering and shader parameters consumed lock-free by the OpenGL ES pipeline.
 */
data class RenderConfig(
    val activeTier: QualityTier = QualityTier.HIGH,
    val isCustomOverride: Boolean = false,
    val resolutionScale: Float = 1.0f,
    val maxSimultaneousEntities: Int = 24,
    val maxParticles: Int = 64,
    val shaderLightingStrength: Float = 1.0f,
    val warmthTintStrength: Float = 1.0f,
    val torchFlickerEnabled: Boolean = true,
    val enableTorchGlow: Boolean = true,
    val enableGroundShadows: Boolean = true,
    val enableScreenShake: Boolean = true
) {
    companion object {
        fun fromTier(tier: QualityTier, isCustom: Boolean = false): RenderConfig = RenderConfig(
            activeTier = tier,
            isCustomOverride = isCustom,
            resolutionScale = tier.resolutionScale,
            maxSimultaneousEntities = tier.maxSimultaneousEntities,
            maxParticles = tier.maxParticles,
            shaderLightingStrength = tier.shaderLightingStrength,
            warmthTintStrength = tier.warmthTintStrength,
            torchFlickerEnabled = tier.enableTorchFlicker,
            enableTorchGlow = tier.enableTorchGlow,
            enableGroundShadows = tier.enableGroundShadows,
            enableScreenShake = tier.enableScreenShake
        )
    }
}

enum class PauseMenuTab {
    GAME,
    GRAPHICS,
    AUDIO
}

enum class SoundCue {
    SWING,
    CLEAVE,
    HIT,
    CRIT,
    PLAYER_HIT,
    DASH,
    POTION,
    CHEST,
    LEVER
}

enum class Direction(
    val rowIndex: Int,
    val dirX: Float,
    val dirY: Float,
    val miniMapAngleDeg: Float
) {
    SOUTH(0, 0f, 1f, 90f),
    SOUTH_EAST(1, 0.70710677f, 0.70710677f, 45f),
    EAST(2, 1f, 0f, 0f),
    NORTH_EAST(3, 0.70710677f, -0.70710677f, -45f),
    NORTH(4, 0f, -1f, -90f),
    NORTH_WEST(5, -0.70710677f, -0.70710677f, -135f),
    WEST(6, -1f, 0f, 180f),
    SOUTH_WEST(7, -0.70710677f, 0.70710677f, 135f);

    companion object {
        fun fromVector(dx: Float, dy: Float, fallback: Direction = SOUTH): Direction {
            val magSq = dx * dx + dy * dy
            if (magSq < 1e-4f) return fallback
            // Clockwise angle from North (0, -1) in world coords where +X is East and +Y is South
            val rad = kotlin.math.atan2(dx.toDouble(), (-dy).toDouble())
            var deg = Math.toDegrees(rad)
            if (deg < 0.0) deg += 360.0
            val sector = ((kotlin.math.round(deg / 45.0).toInt() % 8) + 8) % 8
            return when (sector) {
                0 -> NORTH
                1 -> NORTH_EAST
                2 -> EAST
                3 -> SOUTH_EAST
                4 -> SOUTH
                5 -> SOUTH_WEST
                6 -> WEST
                else -> NORTH_WEST
            }
        }
    }
}

enum class HeroVisualProfile {
    SHIELDED_DARK,
    UNSHIELDED_DARK_HOODED,
    UNSHIELDED_DARK_HORNED
}

data class HeroVisualLoadout(
    val weaponId: String? = null,
    val shieldId: String? = null,
    val helmetId: String? = null,
    val cloakId: String? = null
) {
    val hasShield: Boolean get() = shieldId == "kite_shield"
    val hasShadowHoodLook: Boolean get() = cloakId == "shadow_hood" && helmetId == null

    val visualProfile: HeroVisualProfile get() = when {
        hasShield -> HeroVisualProfile.SHIELDED_DARK
        hasShadowHoodLook -> HeroVisualProfile.UNSHIELDED_DARK_HOODED
        else -> HeroVisualProfile.UNSHIELDED_DARK_HORNED
    }

    val portraitId: HeroPortraitId get() = when {
        hasShield -> HeroPortraitId.PORTRAIT_SHIELDED_DARK
        hasShadowHoodLook -> HeroPortraitId.PORTRAIT_UNSHIELDED_HOODED
        else -> HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED
    }

    val portraitMatchesLoadoutExactly: Boolean get() = when (portraitId) {
        HeroPortraitId.PORTRAIT_SHIELDED_DARK ->
            weaponId == "iron_sword" && shieldId == "kite_shield" &&
                cloakId == null && (helmetId == null || helmetId == "knight_helmet")
        HeroPortraitId.PORTRAIT_UNSHIELDED_HOODED ->
            weaponId == "iron_sword" && shieldId == null &&
                helmetId == null && cloakId == "shadow_hood"
        HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED ->
            weaponId == "iron_sword" && shieldId == null &&
                cloakId == null && (helmetId == null || helmetId == "knight_helmet")
    }
}

enum class ItemType {
    WEAPON,
    SHIELD,
    HELMET,
    CLOAK,
    CONSUMABLE,
    GOLD
}

enum class ElementalAffinity(val labelUk: String) {
    PHYSICAL("ФІЗ."),
    FIRE("ВОГОНЬ"),
    ARCANE("МАГІЯ"),
    HEAL("ЛІК.")
}

enum class AffinityEffectiveness(val badgeUk: String) {
    WEAKNESS("ВРАЗЛ."),
    NEUTRAL(""),
    RESISTANT("ОПІР")
}

data class RpgItem(
    val id: String,
    val nameUk: String,
    val descriptionUk: String,
    val type: ItemType,
    val spriteId: SpriteId,
    val atkBonus: Int = 0,
    val defBonus: Int = 0,
    val spdBonus: Float = 0f,
    val healAmount: Int = 0,
    val affinity: ElementalAffinity = ElementalAffinity.PHYSICAL,
    val critBonus: Float = 0f,
    val count: Int = 1
)

data class DialogueChoice(
    val labelUk: String,
    val nextNodeId: String? = null,
    val actionTag: String? = null
)

data class DialogueNode(
    val id: String,
    val speakerUk: String,
    val portrait: SpriteId,
    val textUk: String,
    val choices: List<DialogueChoice>
)

data class ActiveDialogue(
    val npcId: String,
    val node: DialogueNode
)

data class TelemetrySnapshot(
    val fps: Int = 60,
    val frameTimeMs: Float = 16.6f,
    val qualityMode: QualityMode = QualityMode.AUTO,
    val activeTier: QualityTier = QualityTier.HIGH,
    val isCustomOverride: Boolean = false,
    val resolutionScale: Float = 1.0f,
    val shaderLightingStrength: Float = 1.0f,
    val activeEntities: Int = 0,
    val activeParticles: Int = 0,
    val drawCalls: Int = 0,
    val atlasDecodeMs: Float = 0f,
    val atlasUploadCallMs: Float = 0f,
    val atlasLoadTotalCpuMs: Float = 0f,
    val atlasTextureMiB: Float = 4f,
    val maxTextureSize: Int = 0,
    val useLegacyHeroFallback: Boolean = false,
    val cpuSubmitP95Ms: Float = 16.6f,
    val cpuSubmitJankCount: Int = 0,
    val sampleCount: Int = 0
)

enum class GamePhase {
    TITLE,
    PLAYING,
    VICTORY,
    DEFEAT
}

enum class MiniMapDisplayMode {
    MINIMIZED,
    COMPACT,
    EXPANDED
}

enum class MiniMapMarkerKind {
    LEVER,
    GATE_LOCKED,
    GATE_OPEN,
    BOSS,
    NPC,
    CHEST
}

data class MiniMapMarker(
    val x: Float,
    val y: Float,
    val kind: MiniMapMarkerKind,
    val isPrimaryQuest: Boolean = false
)

data class FloatingTextItem(
    val id: Long,
    val worldX: Float,
    val worldY: Float,
    val amountText: String,
    val tagText: String,
    val affinity: ElementalAffinity,
    val effectiveness: AffinityEffectiveness,
    val isCrit: Boolean,
    val isPlayerHit: Boolean,
    val progress: Float,
    val driftX: Float
)

data class FloatingTextFrameSnapshot(
    val camX: Float = 4.5f,
    val camY: Float = 12.0f,
    val visibleWorldHeight: Float = 9.2f,
    val items: List<FloatingTextItem> = emptyList()
)

data class MiniMapState(
    val mapWidth: Int = 36,
    val mapHeight: Int = 24,
    val playerX: Float = 4.5f,
    val playerY: Float = 12.0f,
    val playerFacing: Direction = Direction.EAST,
    val terrainLayout: String = "",
    val discoveredMask: String = "",
    val discoveredPercent: Int = 0,
    val gateUnlocked: Boolean = false,
    val markers: List<MiniMapMarker> = emptyList()
)

data class HudUiState(
    val phase: GamePhase = GamePhase.TITLE,
    val hp: Int = 120,
    val maxHp: Int = 120,
    val stamina: Float = 100f,
    val maxStamina: Float = 100f,
    val gold: Int = 25,
    val atkTotal: Int = 18,
    val defTotal: Int = 4,
    val kills: Int = 0,
    val totalBosses: Int = 2,
    val bossesDefeated: Int = 0,
    val gateUnlocked: Boolean = false,
    val questHintUk: String = "Знайдіть важіль у коридорі та поговоріть з Хроністом",
    val interactPromptUk: String? = null,
    val cleaveCooldownRatio: Float = 0f,
    val dashCooldownRatio: Float = 0f,
    val potionCount: Int = 2,
    val equippedWeapon: RpgItem? = null,
    val equippedShield: RpgItem? = null,
    val equippedHelmet: RpgItem? = null,
    val equippedCloak: RpgItem? = null,
    val heroPortraitId: HeroPortraitId = HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED,
    val portraitMatchesLoadoutExactly: Boolean = true,
    val backpack: List<RpgItem> = emptyList(),
    val activeDialogue: ActiveDialogue? = null,
    val toastMessageUk: String? = null,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val miniMap: MiniMapState = MiniMapState()
)
