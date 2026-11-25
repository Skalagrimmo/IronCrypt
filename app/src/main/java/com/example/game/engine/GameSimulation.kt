package com.example.game.engine

import com.example.game.model.ActiveDialogue
import com.example.game.model.AffinityEffectiveness
import com.example.game.model.Direction
import com.example.game.model.ElementalAffinity
import com.example.game.model.FloatingTextFrameSnapshot
import com.example.game.model.FloatingTextItem
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.QualityTier
import com.example.game.model.RenderConfig
import com.example.game.model.RpgItem
import com.example.game.model.GameSaveSnapshot
import com.example.game.model.HeroVisualLoadout
import com.example.game.model.MiniMapMarker
import com.example.game.model.MiniMapMarkerKind
import com.example.game.model.MiniMapState
import com.example.game.model.SavedEnemyState
import com.example.game.model.SavedItemStack
import com.example.game.model.SavedPickupState
import com.example.game.model.SavedPropState
import com.example.game.model.SoundCue
import com.example.game.model.SpriteId
import com.example.game.model.SpriteRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

enum class EnemyKind(
    val maxHp: Int,
    val atk: Int,
    val speed: Float,
    val radius: Float,
    val scaleW: Float,
    val scaleH: Float,
    val isBoss: Boolean,
    val isRanged: Boolean
) {
    SKELETON(48, 12, 2.55f, 0.34f, 0.72f, 1.15f, false, false),
    GARGOYLE(68, 16, 3.05f, 0.40f, 1.05f, 1.05f, false, false),
    AUTOMATON(115, 22, 1.85f, 0.46f, 1.05f, 1.28f, false, false),
    BOSS_MECH(340, 28, 1.65f, 0.56f, 1.45f, 1.52f, true, false),
    BOSS_DEMON(280, 24, 2.25f, 0.52f, 1.38f, 1.45f, true, true)
}

/** A telegraphed enemy move with its own shape, timing and resolution rule. */
enum class EnemyAttackPattern {
    NONE,
    SLASH,
    POUNCE,
    SLAM,
    CHARGE,
    FIRE_FAN
}

class EnemyActor {
    var active = false
    var kind = EnemyKind.SKELETON
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var hp = 0
    var maxHp = 0
    var facing = Direction.SOUTH
    var aiTimer = 0f
    var attackWindup = 0f
    var attackWindupDuration = 0f
    var attackCooldown = 0f
    var attackPattern = EnemyAttackPattern.NONE
    var attackDirX = 1f
    var attackDirY = 0f
    var attackTargetX = 0f
    var attackTargetY = 0f
    var hitFlash = 0f
    var animTime = 0f
}

enum class PropKind {
    TORCH,
    BARREL,
    CHEST,
    LEVER,
    GEARS,
    BARRICADE,
    SKULLS,
    CHANDELIER,
    NPC_CHRONICLER,
    NPC_CULTIST
}

class WorldProp {
    var active = false
    var kind = PropKind.TORCH
    var x = 0f
    var y = 0f
    var solid = false
    var used = false
    var lootId = ""
    var hitFlash = 0f
}

class Projectile {
    var active = false
    var fromPlayer = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var damage = 14
    var life = 0f
    var r = 1f
    var g = 0.35f
    var b = 0.2f
}

class Pickup {
    var active = false
    var x = 0f
    var y = 0f
    var itemId = ""
    var bobPhase = 0f
}

class Particle {
    var active = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var maxLife = 0.4f
    var size = 0.14f
    var r = 1f
    var g = 0.7f
    var b = 0.25f
}

class FloatingTextSlot {
    var active = false
    var id = 0L
    var worldX = 0f
    var worldY = 0f
    var amountText = ""
    var tagText = ""
    var affinity = ElementalAffinity.PHYSICAL
    var effectiveness = AffinityEffectiveness.NEUTRAL
    var isCrit = false
    var isPlayerHit = false
    var age = 0f
    var duration = 0.95f
    var driftX = 0f
}

/**
 * Fixed-pool real-time RPG simulation. Zero allocations during update ticks keep garbage
 * collection off the frame loop on constrained mobile CPUs.
 */
class GameSimulation(val map: DungeonMap = DungeonMap()) {

    companion object {
        const val MAX_ENEMIES = 24
        const val MAX_PROPS = 40
        const val MAX_PROJECTILES = 24
        const val MAX_PICKUPS = 24
        const val MAX_PARTICLES = 64
        const val MAX_FLOATING_TEXTS = 24
        const val VISION_RADIUS_TILES = 5.5f
        private const val NAVIGATION_AGENT_RADIUS = 0.58f
        private const val PLAYER_COLLISION_RADIUS = 0.28f
    }

    val enemies = Array(MAX_ENEMIES) { EnemyActor() }
    val props = Array(MAX_PROPS) { WorldProp() }
    val projectiles = Array(MAX_PROJECTILES) { Projectile() }
    val pickups = Array(MAX_PICKUPS) { Pickup() }
    val particles = Array(MAX_PARTICLES) { Particle() }
    val floatingTexts = Array(MAX_FLOATING_TEXTS) { FloatingTextSlot() }
    @Volatile var hasActiveFloatingTexts = false
    private var nextFloatingTextId = 1L

    private val soundCueBuffer = Array(16) { SoundCue.SWING }
    private var soundCueHead = 0
    private var soundCueTail = 0
    private var soundCueCount = 0
    @Volatile var hasPendingSoundCues = false

    // Lock-free touch inputs written by Compose UI thread, read on GL thread
    @Volatile var joyX = 0f
    @Volatile var joyY = 0f
    @Volatile var attackQueued = false
    @Volatile var cleaveQueued = false
    @Volatile var dashQueued = false
    @Volatile var interactQueued = false
    @Volatile var potionQueued = false
    @Volatile var isPausedForOverlay = false

    // Player state
    var phase = GamePhase.TITLE
    var playerX = DungeonMap.START_X
    var playerY = DungeonMap.START_Y
    var playerFacing = Direction.EAST
    var playerHp = 120
    var playerMaxHp = 120
    var playerStamina = 100f
    val playerMaxStamina = 100f
    var playerGold = 25
    var kills = 0
    var bossesDefeated = 0
    var totalBosses = 2

    var attackAnimTimer = 0f
    var attackCooldown = 0f
    var cleaveCooldown = 0f
    var dashCooldown = 0f
    var dashTimer = 0f
    var dashDirX = 1f
    var dashDirY = 0f
    var invulnTimer = 0f
    var playerHitFlash = 0f
    var walkAnimTime = 0f
    var screenShakeTimer = 0f
    var screenShakeStrength = 0f

    // Camera state
    var camX = DungeonMap.START_X
    var camY = DungeonMap.START_Y

    // RPG Equipment & Inventory (mutated under simulationLock when opened)
    val stateLock = Any()
    private val itemCatalog = buildItemCatalog()
    var equippedWeapon: RpgItem? = itemCatalog["iron_sword"]
    var equippedShield: RpgItem? = null
    var equippedHelmet: RpgItem? = null
    var equippedCloak: RpgItem? = null
    private var cachedVisualLoadout = HeroVisualLoadout(weaponId = "iron_sword")
    val heroAnimation = HeroAnimationController()
    private var nextAnimationTriggerSerial = 1L

    val backpack = mutableListOf<RpgItem>()
    var activeDialogue: ActiveDialogue? = null
    var pendingToastUk: String? = null
    private var toastTimer = 0f
    private var giftedPotion = false
    private var giftedHood = false

    var nearestInteractPrompt: String? = null
    private var nearestPropIdx = -1

    // Frame budget telemetry counters
    var activeEntitiesRendered = 0
    var activeParticlesCount = 0
    var globalTime = 0f

    // Fog-of-war discovery state for the adaptive mini-map
    private val discoveredTiles = BooleanArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
    private var discoveredTileCount = 0
    private var lastRevealTileX = -999
    private var lastRevealTileY = -999
    private var cachedMiniMapTerrain = map.encodeMiniMapTerrain()
    private var cachedDiscoveredMask = ""

    private var rngState = 0x5A17C931

    // Shared, preallocated four-way flow field. Rebuilt only when the player changes tile
    // or map passability changes (for example, when the Sanctum gate opens).
    private val navigationDistance = IntArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
    private val navigationStepX = ByteArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
    private val navigationStepY = ByteArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
    private val navigationQueue = IntArray(DungeonMap.WIDTH * DungeonMap.HEIGHT)
    private var navigationPlayerTileX = Int.MIN_VALUE
    private var navigationPlayerTileY = Int.MIN_VALUE
    private var navigationMapRevision = Int.MIN_VALUE

    init {
        resetRun(GamePhase.TITLE)
    }

    fun resetRun(startPhase: GamePhase = GamePhase.PLAYING) {
        synchronized(stateLock) {
            map.reset()
            navigationPlayerTileX = Int.MIN_VALUE
            navigationPlayerTileY = Int.MIN_VALUE
            navigationMapRevision = Int.MIN_VALUE
            phase = startPhase
            playerX = DungeonMap.START_X
            playerY = DungeonMap.START_Y
            camX = playerX
            camY = playerY
            playerFacing = Direction.EAST
            playerMaxHp = 120
            playerHp = 120
            playerStamina = 100f
            playerGold = 25
            kills = 0
            bossesDefeated = 0
            attackAnimTimer = 0f
            attackCooldown = 0f
            cleaveCooldown = 0f
            dashCooldown = 0f
            dashTimer = 0f
            invulnTimer = 0f
            playerHitFlash = 0f
            screenShakeTimer = 0f
            activeDialogue = null
            giftedPotion = false
            giftedHood = false
            pendingToastUk = null

            equippedWeapon = itemCatalog["iron_sword"]
            equippedShield = null
            equippedHelmet = null
            equippedCloak = null
            refreshVisualLoadout()
            heroAnimation.reset(playerFacing)
            backpack.clear()
            backpack.add(itemCatalog["crimson_potion"]!!.copy(count = 2))

            enemies.forEach { it.active = false }
            props.forEach { it.active = false }
            projectiles.forEach { it.active = false }
            pickups.forEach { it.active = false }
            particles.forEach { it.active = false }
            floatingTexts.forEach { it.active = false }
            hasActiveFloatingTexts = false
            clearSoundCues()

            populateDungeon()
            discoveredTiles.fill(false)
            discoveredTileCount = 0
            lastRevealTileX = -999
            lastRevealTileY = -999
            cachedMiniMapTerrain = map.encodeMiniMapTerrain()
            revealAroundPlayer(force = true)
        }
    }

    private fun refreshVisualLoadout() {
        cachedVisualLoadout = HeroVisualLoadout(
            weaponId = equippedWeapon?.id,
            shieldId = equippedShield?.id,
            helmetId = equippedHelmet?.id,
            cloakId = equippedCloak?.id
        )
    }

    fun currentVisualLoadout(): HeroVisualLoadout = cachedVisualLoadout

    fun resolveHeroFrameRect(): SpriteRect = heroAnimation.resolveFrame(cachedVisualLoadout, playerFacing)

    private fun populateDungeon() {
        var pi = 0
        fun addProp(kind: PropKind, x: Float, y: Float, solid: Boolean = false, loot: String = "") {
            if (pi >= MAX_PROPS) return
            props[pi].apply {
                active = true
                this.kind = kind
                this.x = x
                this.y = y
                this.solid = solid
                this.used = false
                this.lootId = loot
                this.hitFlash = 0f
            }
            pi++
        }

        // Foundry Entrance and Chronicler's Sanctuary.
        addProp(PropKind.TORCH, 4.5f, 29.5f)
        addProp(PropKind.TORCH, 16.5f, 29.5f)
        addProp(PropKind.TORCH, 4.5f, 43.5f)
        addProp(PropKind.TORCH, 16.5f, 43.5f)
        addProp(PropKind.TORCH, 4.5f, 6.5f)
        addProp(PropKind.TORCH, 16.5f, 6.5f)

        // Gearworks and Ashen Archive.
        addProp(PropKind.TORCH, 29.5f, 5.5f)
        addProp(PropKind.TORCH, 42.5f, 5.5f)
        addProp(PropKind.TORCH, 55.5f, 5.5f)
        addProp(PropKind.TORCH, 68.5f, 5.5f)
        // Furnace Nave and the lower wings.
        addProp(PropKind.TORCH, 27.5f, 24.5f)
        addProp(PropKind.TORCH, 48.5f, 24.5f)
        addProp(PropKind.TORCH, 29.5f, 66.5f)
        addProp(PropKind.TORCH, 55.5f, 66.5f)

        // Eastern progression route.
        addProp(PropKind.TORCH, 76.5f, 29.5f)
        addProp(PropKind.TORCH, 91.5f, 29.5f)
        addProp(PropKind.TORCH, 76.5f, 46.5f)
        addProp(PropKind.TORCH, 91.5f, 46.5f)
        addProp(PropKind.TORCH, 76.5f, 5.5f)
        addProp(PropKind.TORCH, 91.5f, 5.5f)

        // NPCs: the Chronicler is close to the entrance; the Cultist is deeper in the Crypt.
        addProp(PropKind.NPC_CHRONICLER, DungeonMap.CHRONICLER_X, DungeonMap.CHRONICLER_Y, solid = true)
        addProp(PropKind.NPC_CULTIST, 62.5f, 60.5f, solid = true)

        // The Gearworks lever opens the only gate into Crimson Sanctum.
        addProp(PropKind.LEVER, DungeonMap.LEVER_X, DungeonMap.LEVER_Y, solid = true)
        addProp(PropKind.GEARS, 41.5f, 10.5f, solid = true)

        // Optional treasure branches reward exploration without blocking the main route.
        addProp(PropKind.CHEST, 15.5f, 42.0f, solid = true, loot = "kite_shield")
        addProp(PropKind.CHEST, 65.5f, 11.5f, solid = true, loot = "battle_axe")
        addProp(PropKind.CHEST, 43.5f, 63.5f, solid = true, loot = "crimson_potion")
        addProp(PropKind.CHEST, 68.5f, 63.5f, solid = true, loot = "knight_helmet")
        addProp(PropKind.CHEST, 88.5f, 40.5f, solid = true, loot = "shadow_hood")

        // Barrels, barricades and environmental dressing.
        addProp(PropKind.BARREL, 5.5f, 40.5f, solid = true)
        addProp(PropKind.BARREL, 7.0f, 41.5f, solid = true)
        addProp(PropKind.BARREL, 32.5f, 43.5f, solid = true)
        addProp(PropKind.BARREL, 45.5f, 33.0f, solid = true)
        addProp(PropKind.BARRICADE, 31.5f, 36.5f, solid = true)
        addProp(PropKind.BARRICADE, 57.5f, 42.5f, solid = true)
        addProp(PropKind.SKULLS, 56.5f, 55.5f)
        addProp(PropKind.SKULLS, 68.5f, 58.5f)
        addProp(PropKind.CHANDELIER, 39.5f, 35.5f)
        addProp(PropKind.CHANDELIER, 84.5f, 37.5f)

        // Enemies are distributed by wing. Bosses remain together in the final Sanctum.
        spawnEnemy(EnemyKind.SKELETON, 15.5f, 39.5f)
        spawnEnemy(EnemyKind.SKELETON, 15.5f, 14.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 42.5f, 17.5f)
        spawnEnemy(EnemyKind.GARGOYLE, 56.5f, 9.5f)
        spawnEnemy(EnemyKind.SKELETON, 65.5f, 17.5f)
        spawnEnemy(EnemyKind.SKELETON, 34.5f, 30.5f)
        spawnEnemy(EnemyKind.GARGOYLE, 43.5f, 44.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 44.5f, 27.5f)
        spawnEnemy(EnemyKind.SKELETON, 31.5f, 63.5f)
        spawnEnemy(EnemyKind.GARGOYLE, 43.5f, 63.5f)
        spawnEnemy(EnemyKind.SKELETON, 57.5f, 36.5f)
        spawnEnemy(EnemyKind.SKELETON, 66.5f, 58.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 56.5f, 63.5f)
        spawnEnemy(EnemyKind.GARGOYLE, 86.5f, 32.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 86.5f, 43.5f)
        spawnEnemy(EnemyKind.BOSS_MECH, 85.5f, 11.5f)
        spawnEnemy(EnemyKind.BOSS_DEMON, 86.5f, 17.5f)
    }

    private fun spawnEnemy(kind: EnemyKind, x: Float, y: Float) {
        for (e in enemies) {
            if (!e.active) {
                e.active = true
                e.kind = kind
                e.x = x
                e.y = y
                e.vx = 0f
                e.vy = 0f
                e.maxHp = kind.maxHp
                e.hp = kind.maxHp
                e.facing = Direction.WEST
                e.aiTimer = nextRand01() * 2f
                e.attackWindup = 0f
                e.attackWindupDuration = 0f
                e.attackPattern = EnemyAttackPattern.NONE
                e.attackDirX = 1f
                e.attackDirY = 0f
                e.attackTargetX = x
                e.attackTargetY = y
                e.attackCooldown = 0.6f
                e.hitFlash = 0f
                e.animTime = nextRand01() * 3f
                return
            }
        }
    }

    fun playerAtkTotal(): Int = 12 + (equippedWeapon?.atkBonus ?: 0)
    fun playerDefTotal(): Int =
        (equippedShield?.defBonus ?: 0) + (equippedHelmet?.defBonus ?: 0) + (equippedCloak?.defBonus ?: 0)
    fun playerSpeedTotal(): Float = 4.35f + (equippedCloak?.spdBonus ?: 0f)
    fun playerCritChance(): Float =
        (0.14f + (equippedWeapon?.critBonus ?: 0f) + (equippedCloak?.critBonus ?: 0f)).coerceIn(0f, 0.75f)

    fun resolveAttackAffinity(isCleave: Boolean): ElementalAffinity {
        val weaponAffinity = equippedWeapon?.affinity ?: ElementalAffinity.PHYSICAL
        return if (isCleave) {
            if (weaponAffinity == ElementalAffinity.FIRE) ElementalAffinity.FIRE else ElementalAffinity.ARCANE
        } else {
            weaponAffinity
        }
    }

    fun enemyAffinityEffectiveness(kind: EnemyKind, affinity: ElementalAffinity): Pair<AffinityEffectiveness, Float> {
        return when (kind) {
            EnemyKind.SKELETON, EnemyKind.GARGOYLE -> when (affinity) {
                ElementalAffinity.FIRE -> AffinityEffectiveness.WEAKNESS to 1.35f
                else -> AffinityEffectiveness.NEUTRAL to 1.0f
            }
            EnemyKind.AUTOMATON, EnemyKind.BOSS_MECH -> when (affinity) {
                ElementalAffinity.ARCANE -> AffinityEffectiveness.WEAKNESS to 1.35f
                ElementalAffinity.FIRE -> AffinityEffectiveness.RESISTANT to 0.85f
                else -> AffinityEffectiveness.NEUTRAL to 1.0f
            }
            EnemyKind.BOSS_DEMON -> when (affinity) {
                ElementalAffinity.ARCANE -> AffinityEffectiveness.WEAKNESS to 1.25f
                ElementalAffinity.FIRE -> AffinityEffectiveness.RESISTANT to 0.80f
                else -> AffinityEffectiveness.NEUTRAL to 1.0f
            }
        }
    }

    fun update(dtRaw: Float, config: RenderConfig) {
        val dt = dtRaw.coerceIn(0.001f, 0.05f)
        globalTime += dt

        if (toastTimer > 0f) {
            toastTimer -= dt
            if (toastTimer <= 0f) pendingToastUk = null
        }

        // Smooth camera follow even on Title screen
        val lerp = (dt * 7.5f).coerceIn(0f, 1f)
        camX += (playerX - camX) * lerp
        camY += (playerY - camY) * lerp

        if (phase != GamePhase.PLAYING || isPausedForOverlay || activeDialogue != null) {
            // Clear stale action edges while paused/dialogue
            attackQueued = false
            cleaveQueued = false
            dashQueued = false
            interactQueued = false
            potionQueued = false
            if (!isPausedForOverlay && (activeDialogue != null || phase == GamePhase.DEFEAT)) {
                synchronized(stateLock) {
                    heroAnimation.updateVisualOnly(
                        dt = dt,
                        hasDialogue = phase == GamePhase.PLAYING && activeDialogue != null,
                        isDefeat = phase == GamePhase.DEFEAT,
                        liveFacing = playerFacing
                    )
                }
            }
            return
        }

        synchronized(stateLock) {
            updatePlayer(dt, config)
            updateEnemies(dt, config)
            updateProjectiles(dt, config)
            updatePickups(dt, config)
            updateParticles(dt)
            updateFloatingTexts(dt)
        }
    }

    fun update(dtRaw: Float, tier: QualityTier) {
        update(dtRaw, RenderConfig.fromTier(tier))
    }

    private fun updatePlayer(dt: Float, config: RenderConfig) {
        if (attackCooldown > 0f) attackCooldown -= dt
        if (attackAnimTimer > 0f) attackAnimTimer -= dt
        if (cleaveCooldown > 0f) cleaveCooldown -= dt
        if (dashCooldown > 0f) dashCooldown -= dt
        if (invulnTimer > 0f) invulnTimer -= dt
        if (playerHitFlash > 0f) playerHitFlash = (playerHitFlash - dt * 5f).coerceAtLeast(0f)
        if (screenShakeTimer > 0f) screenShakeTimer -= dt

        playerStamina = (playerStamina + dt * 16f).coerceAtMost(playerMaxStamina)

        // Handle potion quick-use
        if (potionQueued) {
            potionQueued = false
            usePotionInternal()
        }

        val rawJoyMag = sqrt(joyX * joyX + joyY * joyY)
        heroAnimation.updateGameplay(
            dt = dt,
            joyMag = if (dashTimer > 0f) 0f else rawJoyMag,
            hasDialogue = activeDialogue != null,
            isDefeat = phase == GamePhase.DEFEAT,
            liveFacing = playerFacing
        )

        // Handle Dash
        if (dashQueued) {
            dashQueued = false
            if (dashCooldown <= 0f && playerStamina >= 20f) {
                playerStamina -= 20f
                dashCooldown = 1.15f
                dashTimer = 0.22f
                invulnTimer = 0.35f
                val mag = sqrt(joyX * joyX + joyY * joyY)
                if (mag > 0.15f) {
                    dashDirX = joyX / mag
                    dashDirY = joyY / mag
                    playerFacing = Direction.fromVector(dashDirX, dashDirY, playerFacing)
                } else {
                    dashDirX = playerFacing.dirX
                    dashDirY = playerFacing.dirY
                }
                heroAnimation.triggerConfirmed(
                    state = HeroAnimationState.DASH,
                    triggerSerial = nextAnimationTriggerSerial++,
                    durationSec = 0.22f,
                    direction = playerFacing
                )
                spawnParticleBurst(playerX, playerY + 0.2f, 6, 0.7f, 0.75f, 0.85f, config)
                queueSoundCue(SoundCue.DASH)
            }
        }

        // Movement
        if (dashTimer > 0f) {
            dashTimer -= dt
            moveWithCollision(dashDirX * 10.5f * dt, dashDirY * 10.5f * dt, 0.30f)
        } else {
            val jx = joyX
            val jy = joyY
            val mag = sqrt(jx * jx + jy * jy)
            if (mag > 0.08f) {
                val spd = playerSpeedTotal() * mag.coerceAtMost(1f)
                val nx = jx / mag
                val ny = jy / mag
                moveWithCollision(nx * spd * dt, ny * spd * dt, 0.30f)
                walkAnimTime += dt * 9f
                if (!heroAnimation.isFacingLocked) {
                    playerFacing = Direction.fromVector(nx, ny, playerFacing)
                }
            }
        }

        // Primary Slash
        if (attackQueued) {
            attackQueued = false
            if (attackCooldown <= 0f) {
                attackCooldown = 0.30f
                attackAnimTimer = 0.22f
                if (dashTimer <= 0f && rawJoyMag > 0.08f &&
                    (!heroAnimation.isFacingLocked || HeroAnimationState.ATTACK.priority >= heroAnimation.currentState.priority)
                ) {
                    playerFacing = Direction.fromVector(joyX / rawJoyMag, joyY / rawJoyMag, playerFacing)
                }
                val activeAttackFacing = heroAnimation.resolveActiveDirection(playerFacing)
                heroAnimation.triggerConfirmed(
                    state = HeroAnimationState.ATTACK,
                    triggerSerial = nextAnimationTriggerSerial++,
                    durationSec = 0.22f,
                    direction = activeAttackFacing
                )
                queueSoundCue(SoundCue.SWING)
                performMeleeStrike(
                    radius = 1.65f,
                    coneCos = -0.15f,
                    damage = playerAtkTotal(),
                    knockback = 4.2f,
                    isCleave = false,
                    config = config
                )
            }
        }

        // Heavy 360 Cleave
        if (cleaveQueued) {
            cleaveQueued = false
            if (cleaveCooldown <= 0f && playerStamina >= 25f) {
                playerStamina -= 25f
                cleaveCooldown = 2.4f
                attackAnimTimer = 0.28f
                if (dashTimer <= 0f && rawJoyMag > 0.08f &&
                    (!heroAnimation.isFacingLocked || HeroAnimationState.CLEAVE.priority >= heroAnimation.currentState.priority)
                ) {
                    playerFacing = Direction.fromVector(joyX / rawJoyMag, joyY / rawJoyMag, playerFacing)
                }
                val activeCleaveFacing = heroAnimation.resolveActiveDirection(playerFacing)
                heroAnimation.triggerConfirmed(
                    state = HeroAnimationState.CLEAVE,
                    triggerSerial = nextAnimationTriggerSerial++,
                    durationSec = 0.28f,
                    direction = activeCleaveFacing
                )
                queueSoundCue(SoundCue.CLEAVE)
                performMeleeStrike(
                    radius = 2.25f,
                    coneCos = -1.1f,
                    damage = (playerAtkTotal() * 1.8f).toInt(),
                    knockback = 7.0f,
                    isCleave = true,
                    config = config
                )
                spawnParticleBurst(playerX, playerY, 14, 0.95f, 0.65f, 0.2f, config)
                if (config.enableScreenShake) {
                    screenShakeTimer = 0.16f
                    screenShakeStrength = 0.12f
                }
            }
        }

        // Reveal fog-of-war tiles when entering a new tile
        revealAroundPlayer(force = false)

        // Proximity detection for Interact
        findNearestInteractable()
        if (interactQueued) {
            interactQueued = false
            triggerNearestInteraction(config)
        }
    }

    private fun moveWithCollision(dx: Float, dy: Float, radius: Float) {
        val nextX = playerX + dx
        if (map.isWalkable(nextX, playerY, radius) && !collidesWithSolidProp(nextX, playerY, radius)) {
            playerX = nextX
        }
        val nextY = playerY + dy
        if (map.isWalkable(playerX, nextY, radius) && !collidesWithSolidProp(playerX, nextY, radius)) {
            playerY = nextY
        }
    }

    private fun collidesWithSolidProp(wx: Float, wy: Float, radius: Float): Boolean {
        for (p in props) {
            if (!p.active || !p.solid) continue
            val dx = wx - p.x
            val dy = wy - p.y
            if (dx * dx + dy * dy < (radius + 0.38f) * (radius + 0.38f)) return true
        }
        return false
    }

    private fun performMeleeStrike(
        radius: Float,
        coneCos: Float,
        damage: Int,
        knockback: Float,
        isCleave: Boolean,
        config: RenderConfig
    ) {
        val fx = playerFacing.dirX
        val fy = playerFacing.dirY

        val affinity = resolveAttackAffinity(isCleave)
        val critChance = (playerCritChance() + if (isCleave) 0.20f else 0f).coerceIn(0f, 0.85f)

        // Check enemies
        for (e in enemies) {
            if (!e.active) continue
            val dx = e.x - playerX
            val dy = e.y - playerY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= radius + e.kind.radius) {
                val dot = if (dist > 0.001f) (dx * fx + dy * fy) / dist else 1f
                if (dot >= coneCos) {
                    val isCrit = nextRand01() < critChance
                    val (effectiveness, elemMult) = enemyAffinityEffectiveness(e.kind, affinity)
                    val critMult = if (isCrit) 1.65f else 1.0f
                    val finalDamage = (damage * elemMult * critMult).toInt().coerceAtLeast(1)
                    damageEnemy(
                        e = e,
                        damage = finalDamage,
                        affinity = affinity,
                        effectiveness = effectiveness,
                        isCrit = isCrit,
                        dx = dx,
                        dy = dy,
                        dist = dist,
                        knockback = knockback,
                        config = config
                    )
                }
            }
        }

        // Check destructible barrels
        for (p in props) {
            if (!p.active || p.kind != PropKind.BARREL) continue
            val dx = p.x - playerX
            val dy = p.y - playerY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= radius + 0.45f) {
                p.active = false
                spawnParticleBurst(p.x, p.y, 10, 0.72f, 0.45f, 0.22f, config)
                if (nextRand01() < 0.55f) {
                    spawnPickup(p.x, p.y, "crimson_potion")
                } else {
                    spawnPickup(p.x, p.y, "gold_coins")
                }
            }
        }
    }

    private fun damageEnemy(
        e: EnemyActor,
        damage: Int,
        affinity: ElementalAffinity,
        effectiveness: AffinityEffectiveness,
        isCrit: Boolean,
        dx: Float,
        dy: Float,
        dist: Float,
        knockback: Float,
        config: RenderConfig
    ) {
        e.hp -= damage
        e.hitFlash = 1f
        val inv = if (dist > 0.01f) 1f / dist else 1f
        val kx = dx * inv * knockback * 0.08f
        val ky = dy * inv * knockback * 0.08f
        if (map.isWalkable(e.x + kx, e.y + ky, e.kind.radius)) {
            e.x += kx
            e.y += ky
        }
        val tagParts = buildString {
            if (isCrit) append("КРИТ")
            if (affinity == ElementalAffinity.FIRE || affinity == ElementalAffinity.ARCANE) {
                if (isNotEmpty()) append(" • ")
                append(affinity.labelUk)
            }
            if (effectiveness != AffinityEffectiveness.NEUTRAL) {
                if (isNotEmpty()) append(" ")
                append(effectiveness.badgeUk)
            }
        }
        spawnFloatingText(
            worldX = e.x,
            worldY = e.y - e.kind.scaleH * 0.56f,
            amountText = if (isCrit) "$damage!" else "$damage",
            tagText = tagParts,
            affinity = affinity,
            effectiveness = effectiveness,
            isCrit = isCrit,
            isPlayerHit = false
        )
        queueSoundCue(if (isCrit) SoundCue.CRIT else SoundCue.HIT)
        when (affinity) {
            ElementalAffinity.FIRE -> spawnParticleBurst(e.x, e.y, if (isCrit) 12 else 8, 1f, 0.58f, 0.18f, config)
            ElementalAffinity.ARCANE -> spawnParticleBurst(e.x, e.y, if (isCrit) 12 else 8, 0.28f, 0.62f, 1f, config)
            else -> spawnParticleBurst(e.x, e.y, if (isCrit) 10 else 7, 1f, 0.35f, 0.25f, config)
        }

        if (e.hp <= 0) {
            e.active = false
            kills++
            spawnParticleBurst(e.x, e.y, 14, 0.95f, 0.65f, 0.25f, config)
            if (e.kind.isBoss) {
                bossesDefeated++
                if (config.enableScreenShake) {
                    screenShakeTimer = 0.28f
                    screenShakeStrength = 0.18f
                }
                showToast("${e.kind.name} ПОВАЛЕНО!")
                if (bossesDefeated >= totalBosses) {
                    phase = GamePhase.VICTORY
                }
            } else {
                if (nextRand01() < 0.65f) spawnPickup(e.x, e.y, "gold_coins")
                else if (nextRand01() < 0.35f) spawnPickup(e.x, e.y, "crimson_potion")
            }
        }
    }

    private fun updateEnemies(dt: Float, config: RenderConfig) {
        ensureEnemyFlowField()
        var activeCount = 0
        for (e in enemies) {
            if (!e.active) continue
            activeCount++
            if (activeCount > config.maxSimultaneousEntities) {
                val dxCam = abs(e.x - camX)
                val dyCam = abs(e.y - camY)
                if (dxCam > 9f || dyCam > 6f) continue
            }

            e.animTime += dt * 4f
            if (e.hitFlash > 0f) e.hitFlash = (e.hitFlash - dt * 5f).coerceAtLeast(0f)
            if (e.attackCooldown > 0f) e.attackCooldown -= dt

            val dx = playerX - e.x
            val dy = playerY - e.y
            val dist = sqrt(dx * dx + dy * dy)
            val aggroRange = if (e.kind.isBoss) 10.5f else 7.5f

            // A committed attack keeps its initial aim. The player can evade the
            // marked lane/area instead of being tracked until the final damage frame.
            if (e.attackWindup > 0f) {
                e.attackWindup = (e.attackWindup - dt).coerceAtLeast(0f)
                e.hitFlash = 0.45f
                if (e.attackWindup <= 0f) {
                    resolveEnemyAttack(e, config)
                    e.attackCooldown = when (e.attackPattern) {
                        EnemyAttackPattern.SLASH -> 1.30f
                        EnemyAttackPattern.POUNCE -> 1.90f
                        EnemyAttackPattern.SLAM -> 2.10f
                        EnemyAttackPattern.CHARGE -> 2.55f
                        EnemyAttackPattern.FIRE_FAN -> 1.80f
                        EnemyAttackPattern.NONE -> 1.45f
                    }
                    e.attackPattern = EnemyAttackPattern.NONE
                    e.attackWindupDuration = 0f
                }
                continue
            }

            if (dist < aggroRange) {
                e.facing = Direction.fromVector(dx, dy, e.facing)
                val desiredRange = when (e.kind) {
                    EnemyKind.SKELETON -> 0.98f
                    EnemyKind.GARGOYLE -> 3.20f
                    EnemyKind.AUTOMATON -> 1.28f
                    EnemyKind.BOSS_MECH -> 3.75f
                    EnemyKind.BOSS_DEMON -> 5.20f
                }
                val canSeePlayer = hasLineOfSight(e.x, e.y, playerX, playerY)
                if (dist > desiredRange || !canSeePlayer) {
                    moveEnemyAlongFlow(e, dt)
                } else if (e.attackCooldown <= 0f) {
                    beginEnemyAttack(e, dx, dy, dist)
                }
            }
        }
    }

    private fun beginEnemyAttack(e: EnemyActor, dx: Float, dy: Float, dist: Float) {
        val invDist = if (dist > 0.001f) 1f / dist else 1f
        e.attackDirX = dx * invDist
        e.attackDirY = dy * invDist
        e.attackTargetX = playerX
        e.attackTargetY = playerY

        // Keep the AI hot path allocation-free: no temporary Pair/collection per attack.
        val windup: Float
        when (e.kind) {
            EnemyKind.SKELETON -> {
                e.attackPattern = EnemyAttackPattern.SLASH
                windup = 0.46f
            }
            EnemyKind.GARGOYLE -> {
                e.attackPattern = EnemyAttackPattern.POUNCE
                windup = 0.78f
            }
            EnemyKind.AUTOMATON -> {
                e.attackPattern = EnemyAttackPattern.SLAM
                windup = 0.92f
            }
            EnemyKind.BOSS_MECH -> {
                e.attackPattern = EnemyAttackPattern.CHARGE
                windup = 1.02f
            }
            EnemyKind.BOSS_DEMON -> {
                e.attackPattern = EnemyAttackPattern.FIRE_FAN
                windup = 0.84f
            }
        }
        e.attackWindup = windup
        e.attackWindupDuration = windup
    }

    private fun resolveEnemyAttack(e: EnemyActor, config: RenderConfig) {
        when (e.attackPattern) {
            EnemyAttackPattern.NONE -> Unit

            EnemyAttackPattern.SLASH -> {
                val dx = playerX - e.x
                val dy = playerY - e.y
                val dist = sqrt(dx * dx + dy * dy)
                val dot = if (dist > 0.001f) {
                    (dx * e.attackDirX + dy * e.attackDirY) / dist
                } else 1f
                if (dist <= 1.68f && dot >= 0.34f &&
                    hasLineOfSight(e.x, e.y, playerX, playerY)
                ) {
                    damagePlayer(e.kind.atk, config)
                    spawnParticleBurst(playerX, playerY, 5, 0.95f, 0.24f, 0.16f, config)
                }
            }

            EnemyAttackPattern.POUNCE -> {
                // The gargoyle commits to the location marked at take-off.
                val landingX = e.attackTargetX - e.attackDirX * 0.82f
                val landingY = e.attackTargetY - e.attackDirY * 0.82f
                if (map.isWalkable(landingX, landingY, e.kind.radius)) {
                    e.x = landingX
                    e.y = landingY
                }
                val dx = playerX - e.attackTargetX
                val dy = playerY - e.attackTargetY
                if (dx * dx + dy * dy <= 0.98f * 0.98f) {
                    damagePlayer(e.kind.atk + 3, config)
                }
                spawnParticleBurst(e.attackTargetX, e.attackTargetY, 12, 0.82f, 0.42f, 0.18f, config)
            }

            EnemyAttackPattern.SLAM -> {
                // The automaton's slow impact covers a circle, not just its facing.
                val dx = playerX - e.x
                val dy = playerY - e.y
                if (dx * dx + dy * dy <= 1.95f * 1.95f &&
                    hasLineOfSight(e.x, e.y, playerX, playerY)
                ) {
                    damagePlayer(e.kind.atk, config)
                }
                spawnParticleBurst(e.x, e.y, 18, 1f, 0.48f, 0.12f, config)
            }

            EnemyAttackPattern.CHARGE -> resolveBossCharge(e, config)

            EnemyAttackPattern.FIRE_FAN -> fireEnemyProjectileFan(e)
        }
    }

    private fun resolveBossCharge(e: EnemyActor, config: RenderConfig) {
        val startX = e.x
        val startY = e.y
        var previousX = startX
        var previousY = startY
        var hitPlayer = false

        // Move along the announced lane in short collision-checked steps.
        for (step in 1..22) {
            val nextX = startX + e.attackDirX * (step * 0.19f)
            val nextY = startY + e.attackDirY * (step * 0.19f)
            if (!map.isWalkable(nextX, nextY, e.kind.radius)) break

            if (!hitPlayer && distancePointToSegmentSquared(
                    playerX, playerY, previousX, previousY, nextX, nextY
                ) <= 0.72f * 0.72f
            ) {
                damagePlayer(e.kind.atk + 5, config)
                hitPlayer = true
            }
            e.x = nextX
            e.y = nextY
            previousX = nextX
            previousY = nextY
        }
        spawnParticleBurst(e.x, e.y, 16, 0.92f, 0.30f, 0.12f, config)
    }

    private fun distancePointToSegmentSquared(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val abX = bx - ax
        val abY = by - ay
        val lengthSquared = abX * abX + abY * abY
        if (lengthSquared <= 0.0001f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        val t = (((px - ax) * abX + (py - ay) * abY) / lengthSquared).coerceIn(0f, 1f)
        val closestX = ax + abX * t
        val closestY = ay + abY * t
        val dx = px - closestX
        val dy = py - closestY
        return dx * dx + dy * dy
    }

    /**
     * Tile-sampled visibility check. It deliberately ignores actors and props; only
     * solid dungeon geometry and locked gates block melee/ranged attacks.
     */
    private fun hasLineOfSight(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        val dx = toX - fromX
        val dy = toY - fromY
        val steps = (maxOf(abs(dx), abs(dy)) * 4f).toInt().coerceAtLeast(1)
        for (i in 1 until steps) {
            val progress = i.toFloat() / steps.toFloat()
            val tileX = floor((fromX + dx * progress).toDouble()).toInt()
            val tileY = floor((fromY + dy * progress).toDouble()).toInt()
            if (map.isTileSolid(map.getTile(tileX, tileY))) return false
        }
        return true
    }

    /**
     * Rebuild a shared four-way BFS flow field only when the player changes tile or
     * map passability changes. Every enemy then follows the same precomputed routes.
     */
    private fun ensureEnemyFlowField() {
        val playerTileX = floor(playerX.toDouble()).toInt()
        val playerTileY = floor(playerY.toDouble()).toInt()
        val mapRevision = map.walkabilityRevision
        if (playerTileX == navigationPlayerTileX &&
            playerTileY == navigationPlayerTileY &&
            mapRevision == navigationMapRevision
        ) return

        navigationPlayerTileX = playerTileX
        navigationPlayerTileY = playerTileY
        navigationMapRevision = mapRevision
        navigationDistance.fill(-1)
        navigationStepX.fill(0)
        navigationStepY.fill(0)

        // Use the nearest cell that has enough clearance for even the largest enemy.
        var startIndex = -1
        var bestDistance = Int.MAX_VALUE
        for (radius in 0..2) {
            for (y in (playerTileY - radius)..(playerTileY + radius)) {
                for (x in (playerTileX - radius)..(playerTileX + radius)) {
                    if (x !in 0 until DungeonMap.WIDTH || y !in 0 until DungeonMap.HEIGHT) continue
                    if (maxOf(kotlin.math.abs(x - playerTileX), kotlin.math.abs(y - playerTileY)) != radius) continue
                    if (!map.isWalkable(x + 0.5f, y + 0.5f, NAVIGATION_AGENT_RADIUS)) continue
                    val distance = (x - playerTileX) * (x - playerTileX) +
                        (y - playerTileY) * (y - playerTileY)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        startIndex = y * DungeonMap.WIDTH + x
                    }
                }
            }
            if (startIndex >= 0) break
        }
        if (startIndex < 0) return

        var head = 0
        var tail = 0
        navigationQueue[tail++] = startIndex
        navigationDistance[startIndex] = 0

        while (head < tail) {
            val current = navigationQueue[head++]
            val x = current % DungeonMap.WIDTH
            val y = current / DungeonMap.WIDTH

            for (direction in 0..3) {
                val nextX: Int
                val nextY: Int
                when (direction) {
                    0 -> { nextX = x + 1; nextY = y }
                    1 -> { nextX = x - 1; nextY = y }
                    2 -> { nextX = x; nextY = y + 1 }
                    else -> { nextX = x; nextY = y - 1 }
                }
                if (nextX !in 0 until DungeonMap.WIDTH || nextY !in 0 until DungeonMap.HEIGHT) continue
                val nextIndex = nextY * DungeonMap.WIDTH + nextX
                if (navigationDistance[nextIndex] >= 0) continue
                if (!map.isWalkable(nextX + 0.5f, nextY + 0.5f, NAVIGATION_AGENT_RADIUS)) continue

                navigationDistance[nextIndex] = navigationDistance[current] + 1
                navigationStepX[nextIndex] = (x - nextX).toByte()
                navigationStepY[nextIndex] = (y - nextY).toByte()
                navigationQueue[tail++] = nextIndex
            }
        }
    }

    private fun moveEnemyAlongFlow(enemy: EnemyActor, dt: Float) {
        val tileX = floor(enemy.x.toDouble()).toInt()
        val tileY = floor(enemy.y.toDouble()).toInt()
        if (tileX !in 0 until DungeonMap.WIDTH || tileY !in 0 until DungeonMap.HEIGHT) return

        val index = tileY * DungeonMap.WIDTH + tileX
        if (navigationDistance[index] < 0) return

        val stepX = navigationStepX[index].toInt()
        val stepY = navigationStepY[index].toInt()
        val targetX = if (stepX == 0 && stepY == 0) playerX else tileX + stepX + 0.5f
        val targetY = if (stepX == 0 && stepY == 0) playerY else tileY + stepY + 0.5f
        val dx = targetX - enemy.x
        val dy = targetY - enemy.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= 0.025f) return

        enemy.facing = Direction.fromVector(dx, dy, enemy.facing)
        val travel = (enemy.kind.speed * dt).coerceAtMost(distance)
        val moveX = dx / distance * travel
        val moveY = dy / distance * travel

        // Keep axis-separated collision so the existing wall-sliding behaviour remains.
        if (map.isWalkable(enemy.x + moveX, enemy.y, enemy.kind.radius)) {
            enemy.x += moveX
        }
        if (map.isWalkable(enemy.x, enemy.y + moveY, enemy.kind.radius)) {
            enemy.y += moveY
        }
    }

    private fun fireEnemyProjectileFan(e: EnemyActor) {
        val baseAngle = atan2(e.attackDirY.toDouble(), e.attackDirX.toDouble()).toFloat()
        // Five slower projectiles create lanes that can be read and dodged individually.
        for (lane in -2..2) {
            val angle = baseAngle + lane * 0.20f
            for (p in projectiles) {
                if (!p.active) {
                    p.active = true
                    p.fromPlayer = false
                    p.x = e.x
                    p.y = e.y
                    p.vx = cos(angle) * 4.9f
                    p.vy = sin(angle) * 4.9f
                    p.damage = e.kind.atk
                    p.life = 2.45f
                    p.r = 0.78f; p.g = 0.32f; p.b = 1f
                    break
                }
            }
        }
    }

    private fun damagePlayer(
        rawAtk: Int,
        config: RenderConfig,
        affinity: ElementalAffinity = ElementalAffinity.PHYSICAL
    ) {
        if (invulnTimer > 0f || phase != GamePhase.PLAYING) return
        val mitigated = (rawAtk - playerDefTotal()).coerceAtLeast(3)
        playerHp = (playerHp - mitigated).coerceAtLeast(0)
        invulnTimer = 0.55f
        playerHitFlash = 1f
        spawnFloatingText(
            worldX = playerX,
            worldY = playerY - 0.64f,
            amountText = "-$mitigated",
            tagText = if (affinity == ElementalAffinity.FIRE) affinity.labelUk else "",
            affinity = affinity,
            effectiveness = AffinityEffectiveness.NEUTRAL,
            isCrit = false,
            isPlayerHit = true
        )
        queueSoundCue(SoundCue.PLAYER_HIT)
        spawnParticleBurst(playerX, playerY, 8, 0.95f, 0.2f, 0.2f, config)
        if (config.enableScreenShake) {
            screenShakeTimer = 0.15f
            screenShakeStrength = 0.10f
        }
        if (playerHp <= 0) {
            phase = GamePhase.DEFEAT
            heroAnimation.triggerConfirmed(
                state = HeroAnimationState.DEAD,
                triggerSerial = nextAnimationTriggerSerial++,
                durationSec = 0.50f,
                direction = heroAnimation.resolveActiveDirection(playerFacing)
            )
        } else if (mitigated > 0) {
            val hurtDir = heroAnimation.resolveActiveDirection(playerFacing)
            heroAnimation.triggerConfirmed(
                state = HeroAnimationState.HURT,
                triggerSerial = nextAnimationTriggerSerial++,
                durationSec = 0.28f,
                direction = hurtDir
            )
        }
    }

    private fun updateProjectiles(dt: Float, config: RenderConfig) {
        for (p in projectiles) {
            if (!p.active) continue
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.life <= 0f || !map.isWalkable(p.x, p.y, 0.12f)) {
                p.active = false
                spawnParticleBurst(p.x, p.y, 4, p.r, p.g, p.b, config)
                continue
            }
            if (!p.fromPlayer) {
                val dx = p.x - playerX
                val dy = p.y - playerY
                if (dx * dx + dy * dy < 0.42f * 0.42f) {
                    p.active = false
                    damagePlayer(p.damage, config, ElementalAffinity.FIRE)
                }
            }
        }
    }

    private fun updatePickups(dt: Float, config: RenderConfig) {
        for (pk in pickups) {
            if (!pk.active) continue
            pk.bobPhase += dt * 4f
            val dx = pk.x - playerX
            val dy = pk.y - playerY
            if (dx * dx + dy * dy < 0.72f * 0.72f) {
                pk.active = false
                collectItem(pk.itemId)
                spawnParticleBurst(pk.x, pk.y, 6, 0.95f, 0.85f, 0.3f, config)
            }
        }
    }

    private fun spawnPickup(x: Float, y: Float, itemId: String) {
        for (pk in pickups) {
            if (!pk.active) {
                pk.active = true
                pk.x = x
                pk.y = y
                pk.itemId = itemId
                pk.bobPhase = nextRand01() * 3f
                return
            }
        }
    }

    private fun spawnParticleBurst(
        x: Float,
        y: Float,
        requestedCount: Int,
        r: Float,
        g: Float,
        b: Float,
        config: RenderConfig
    ) {
        var currentActive = 0
        for (pt in particles) if (pt.active) currentActive++

        var spawned = 0
        for (pt in particles) {
            if (currentActive >= config.maxParticles || spawned >= requestedCount) break
            if (!pt.active) {
                val angle = nextRand01() * (2f * PI.toFloat())
                val speed = 1.2f + nextRand01() * 2.8f
                pt.active = true
                pt.x = x
                pt.y = y
                pt.vx = cos(angle) * speed
                pt.vy = sin(angle) * speed
                pt.maxLife = 0.25f + nextRand01() * 0.25f
                pt.life = pt.maxLife
                pt.size = 0.11f + nextRand01() * 0.08f
                pt.r = r; pt.g = g; pt.b = b
                currentActive++
                spawned++
            }
        }
    }

    private fun updateParticles(dt: Float) {
        var count = 0
        for (pt in particles) {
            if (!pt.active) continue
            pt.life -= dt
            if (pt.life <= 0f) {
                pt.active = false
            } else {
                pt.x += pt.vx * dt
                pt.y += pt.vy * dt
                pt.vx *= 0.92f
                pt.vy *= 0.92f
                count++
            }
        }
        activeParticlesCount = count
    }

    private fun findNearestInteractable() {
        var bestIdx = -1
        var bestDistSq = 1.85f * 1.85f
        for (i in props.indices) {
            val p = props[i]
            if (!p.active) continue
            val interactable = when (p.kind) {
                PropKind.CHEST, PropKind.LEVER -> !p.used
                PropKind.NPC_CHRONICLER, PropKind.NPC_CULTIST -> true
                else -> false
            }
            if (!interactable) continue
            val dx = p.x - playerX
            val dy = p.y - playerY
            val d2 = dx * dx + dy * dy
            if (d2 < bestDistSq) {
                bestDistSq = d2
                bestIdx = i
            }
        }
        nearestPropIdx = bestIdx
        nearestInteractPrompt = if (bestIdx >= 0) {
            when (props[bestIdx].kind) {
                PropKind.CHEST -> "ВІДКРИТИ СКРИНЮ"
                PropKind.LEVER -> "ПОТЯГНУТИ ВАЖІЛЬ"
                PropKind.NPC_CHRONICLER -> "ХРОНІСТ ТІНЕЙ"
                PropKind.NPC_CULTIST -> "ВІДСТУПНИК"
                else -> null
            }
        } else null
    }

    private fun triggerNearestInteraction(config: RenderConfig) {
        val idx = nearestPropIdx
        if (idx !in props.indices) return
        val p = props[idx]
        if (!p.active) return

        when (p.kind) {
            PropKind.CHEST -> if (!p.used) {
                p.used = true
                if (!heroAnimation.isFacingLocked || HeroAnimationState.INTERACT.priority >= heroAnimation.currentState.priority) {
                    playerFacing = Direction.fromVector(p.x - playerX, p.y - playerY, playerFacing)
                }
                heroAnimation.triggerConfirmed(
                    state = HeroAnimationState.INTERACT,
                    triggerSerial = nextAnimationTriggerSerial++,
                    durationSec = 0.40f,
                    direction = heroAnimation.resolveActiveDirection(playerFacing)
                )
                collectItem(p.lootId)
                queueSoundCue(SoundCue.CHEST)
                spawnParticleBurst(p.x, p.y, 12, 0.95f, 0.8f, 0.25f, config)
            }
            PropKind.LEVER -> if (!p.used) {
                p.used = true
                if (!heroAnimation.isFacingLocked || HeroAnimationState.INTERACT.priority >= heroAnimation.currentState.priority) {
                    playerFacing = Direction.fromVector(p.x - playerX, p.y - playerY, playerFacing)
                }
                heroAnimation.triggerConfirmed(
                    state = HeroAnimationState.INTERACT,
                    triggerSerial = nextAnimationTriggerSerial++,
                    durationSec = 0.40f,
                    direction = heroAnimation.resolveActiveDirection(playerFacing)
                )
                map.unlockGate()
                cachedMiniMapTerrain = map.encodeMiniMapTerrain()
                queueSoundCue(SoundCue.LEVER)
                showToast("Браму до Багряного Святилища відчинено!")
                spawnParticleBurst(p.x, p.y, 10, 0.9f, 0.6f, 0.2f, config)
            }
            PropKind.NPC_CHRONICLER -> {
                if (!heroAnimation.isFacingLocked) {
                    playerFacing = Direction.fromVector(p.x - playerX, p.y - playerY, playerFacing)
                }
                activeDialogue = ActiveDialogue(
                    "chronicler",
                    DialogueScriptStore.getStartNode("chronicler", map.gateUnlocked)
                )
                heroAnimation.updateVisualOnly(0f, hasDialogue = true, isDefeat = false, liveFacing = playerFacing)
            }
            PropKind.NPC_CULTIST -> {
                if (!heroAnimation.isFacingLocked) {
                    playerFacing = Direction.fromVector(p.x - playerX, p.y - playerY, playerFacing)
                }
                activeDialogue = ActiveDialogue(
                    "cultist",
                    DialogueScriptStore.getStartNode("cultist", map.gateUnlocked)
                )
                heroAnimation.updateVisualOnly(0f, hasDialogue = true, isDefeat = false, liveFacing = playerFacing)
            }
            else -> Unit
        }
    }

    fun selectDialogueChoice(nextNodeId: String?, actionTag: String?) {
        synchronized(stateLock) {
            when (actionTag) {
                "HEAL_PLAYER" -> {
                    playerHp = playerMaxHp
                    playerStamina = playerMaxStamina
                    showToast("Здоров'я та витривалість відновлено")
                }
                "GIFT_POTION" -> if (!giftedPotion) {
                    giftedPotion = true
                    collectItem("crimson_potion")
                } else {
                    showToast("Ви вже отримали зілля")
                }
                "GIFT_HOOD" -> if (!giftedHood) {
                    giftedHood = true
                    collectItem("shadow_hood")
                } else {
                    showToast("Капюшон уже у вашому рюкзаку")
                }
            }
            if (nextNodeId == null) {
                activeDialogue = null
            } else {
                val node = DialogueScriptStore.getNode(nextNodeId)
                    ?: activeDialogue?.let { DialogueScriptStore.getStartNode(it.npcId, map.gateUnlocked) }
                activeDialogue = if (node != null && activeDialogue != null) {
                    activeDialogue!!.copy(node = node)
                } else null
            }
        }
    }

    fun equipOrUseItem(itemId: String) {
        synchronized(stateLock) {
            val idx = backpack.indexOfFirst { it.id == itemId }
            if (idx < 0) return
            val item = backpack[idx]
            when (item.type) {
                ItemType.CONSUMABLE -> usePotionInternal()
                ItemType.WEAPON -> {
                    val prev = equippedWeapon
                    equippedWeapon = item.copy(count = 1)
                    if (prev != null) backpack[idx] = prev else backpack.removeAt(idx)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.SHIELD -> {
                    val prev = equippedShield
                    equippedShield = item.copy(count = 1)
                    if (prev != null) backpack[idx] = prev else backpack.removeAt(idx)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.HELMET -> {
                    val prev = equippedHelmet
                    equippedHelmet = item.copy(count = 1)
                    if (prev != null) backpack[idx] = prev else backpack.removeAt(idx)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.CLOAK -> {
                    val prev = equippedCloak
                    equippedCloak = item.copy(count = 1)
                    if (prev != null) backpack[idx] = prev else backpack.removeAt(idx)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.GOLD -> Unit
            }
            refreshVisualLoadout()
        }
    }

    fun unequipSlot(type: ItemType) {
        synchronized(stateLock) {
            val removed: RpgItem? = when (type) {
                ItemType.WEAPON -> equippedWeapon?.also { equippedWeapon = null }
                ItemType.SHIELD -> equippedShield?.also { equippedShield = null }
                ItemType.HELMET -> equippedHelmet?.also { equippedHelmet = null }
                ItemType.CLOAK -> equippedCloak?.also { equippedCloak = null }
                ItemType.CONSUMABLE, ItemType.GOLD -> null
            }
            if (removed != null) {
                backpack.add(removed.copy(count = 1))
                showToast("Знято: ${removed.nameUk}")
            }
            refreshVisualLoadout()
        }
    }

    fun reorderBackpackItem(fromItemId: String, toItemId: String) {
        if (fromItemId == toItemId) return
        synchronized(stateLock) {
            val fromIdx = backpack.indexOfFirst { it.id == fromItemId }
            val toIdx = backpack.indexOfFirst { it.id == toItemId }
            if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
            val moved = backpack.removeAt(fromIdx)
            backpack.add(toIdx, moved)
        }
    }

    private fun usePotionInternal() {
        val idx = backpack.indexOfFirst { it.id == "crimson_potion" }
        if (idx < 0) {
            showToast("Немає зілля!")
            return
        }
        if (playerHp >= playerMaxHp) {
            showToast("Здоров'я вже повне")
            return
        }
        val pot = backpack[idx]
        val beforeHp = playerHp
        playerHp = (playerHp + pot.healAmount).coerceAtMost(playerMaxHp)
        val healed = (playerHp - beforeHp).coerceAtLeast(0)
        if (pot.count > 1) {
            backpack[idx] = pot.copy(count = pot.count - 1)
        } else {
            backpack.removeAt(idx)
        }
        if (healed > 0) {
            spawnFloatingText(
                worldX = playerX,
                worldY = playerY - 0.68f,
                amountText = "+$healed",
                tagText = "HP",
                affinity = ElementalAffinity.HEAL,
                effectiveness = AffinityEffectiveness.NEUTRAL,
                isCrit = false,
                isPlayerHit = false
            )
            queueSoundCue(SoundCue.POTION)
        }
        showToast("+${pot.healAmount} HP")
    }

    private fun collectItem(itemId: String) {
        if (itemId == "gold_coins") {
            val amount = 10 + (nextRand01() * 16).toInt()
            playerGold += amount
            showToast("+$amount золота")
            return
        }
        val template = itemCatalog[itemId] ?: return
        val existingIdx = backpack.indexOfFirst { it.id == itemId && it.type == ItemType.CONSUMABLE }
        if (existingIdx >= 0) {
            val old = backpack[existingIdx]
            backpack[existingIdx] = old.copy(count = old.count + 1)
        } else {
            backpack.add(template.copy(count = 1))
        }
        showToast("Отримано: ${template.nameUk}")
    }

    private fun showToast(msg: String) {
        pendingToastUk = msg
        toastTimer = 2.4f
    }

    fun showExternalToast(msg: String) {
        synchronized(stateLock) {
            showToast(msg)
        }
    }

    fun exportSnapshot(savedAtMs: Long = System.currentTimeMillis()): GameSaveSnapshot {
        synchronized(stateLock) {
            val savedProps = buildList {
                for (i in props.indices) {
                    val p = props[i]
                    add(SavedPropState(index = i, active = p.active, used = p.used))
                }
            }
            val savedEnemies = buildList {
                for (i in enemies.indices) {
                    val e = enemies[i]
                    add(
                        SavedEnemyState(
                            index = i,
                            active = e.active,
                            hp = e.hp,
                            x = e.x,
                            y = e.y,
                            facing = e.facing
                        )
                    )
                }
            }
            val savedPickups = buildList {
                for (pk in pickups) {
                    if (pk.active) {
                        add(SavedPickupState(x = pk.x, y = pk.y, itemId = pk.itemId))
                    }
                }
            }
            val savedBackpack = backpack.map { SavedItemStack(it.id, it.count) }
            return GameSaveSnapshot(
                savedAtEpochMs = savedAtMs,
                playerX = playerX,
                playerY = playerY,
                playerFacing = playerFacing,
                playerHp = playerHp,
                playerMaxHp = playerMaxHp,
                playerStamina = playerStamina,
                playerGold = playerGold,
                kills = kills,
                bossesDefeated = bossesDefeated,
                gateUnlocked = map.gateUnlocked,
                giftedPotion = giftedPotion,
                giftedHood = giftedHood,
                equippedWeaponId = equippedWeapon?.id,
                equippedShieldId = equippedShield?.id,
                equippedHelmetId = equippedHelmet?.id,
                equippedCloakId = equippedCloak?.id,
                backpack = savedBackpack,
                props = savedProps,
                enemies = savedEnemies,
                pickups = savedPickups,
                discoveredMask = cachedDiscoveredMask
            )
        }
    }

    fun restoreSnapshot(snapshot: GameSaveSnapshot) {
        synchronized(stateLock) {
            resetRun(GamePhase.PLAYING)
            if (snapshot.gateUnlocked) {
                map.unlockGate()
            }
            val savedPlayerX = snapshot.playerX
            val savedPlayerY = snapshot.playerY
            if (snapshot.version >= 2 &&
                map.isWalkable(savedPlayerX, savedPlayerY, PLAYER_COLLISION_RADIUS)
            ) {
                playerX = savedPlayerX
                playerY = savedPlayerY
            } else {
                // Legacy coordinates may now land inside new room walls: recover at the entrance.
                playerX = DungeonMap.START_X
                playerY = DungeonMap.START_Y
            }
            camX = playerX
            camY = playerY
            navigationPlayerTileX = Int.MIN_VALUE
            navigationPlayerTileY = Int.MIN_VALUE
            navigationMapRevision = Int.MIN_VALUE
            playerFacing = snapshot.playerFacing
            playerMaxHp = snapshot.playerMaxHp.coerceAtLeast(1)
            playerHp = snapshot.playerHp.coerceIn(1, playerMaxHp)
            playerStamina = snapshot.playerStamina.coerceIn(0f, playerMaxStamina)
            playerGold = snapshot.playerGold.coerceAtLeast(0)
            kills = snapshot.kills.coerceAtLeast(0)
            bossesDefeated = snapshot.bossesDefeated.coerceIn(0, totalBosses)
            giftedPotion = snapshot.giftedPotion
            giftedHood = snapshot.giftedHood

            equippedWeapon = snapshot.equippedWeaponId?.let { itemCatalog[it] } ?: itemCatalog["iron_sword"]
            equippedShield = snapshot.equippedShieldId?.let { itemCatalog[it] }
            equippedHelmet = snapshot.equippedHelmetId?.let { itemCatalog[it] }
            equippedCloak = snapshot.equippedCloakId?.let { itemCatalog[it] }
            refreshVisualLoadout()
            heroAnimation.reset(playerFacing)

            backpack.clear()
            snapshot.backpack.forEach { stack ->
                val template = itemCatalog[stack.itemId]
                if (template != null && stack.count > 0) {
                    backpack.add(template.copy(count = stack.count))
                }
            }

            // Version 1 stored indices for the old compact map population. Its props and
            // enemies no longer correspond to the expanded world's placement, so keep the
            // new deterministic population and migrate only player progression/inventory.
            if (snapshot.version >= 2) {
                snapshot.props.forEach { sp ->
                    if (sp.index in props.indices) {
                        props[sp.index].active = sp.active
                        props[sp.index].used = sp.used
                    }
                }

                snapshot.enemies.forEach { se ->
                    if (se.index in enemies.indices) {
                        val target = enemies[se.index]
                        if (se.active && !map.isWalkable(se.x, se.y, target.kind.radius)) {
                            target.active = false
                        } else {
                            target.active = se.active
                            target.hp = se.hp
                            target.x = se.x
                            target.y = se.y
                            target.facing = se.facing
                            target.attackWindup = 0f
                            target.attackWindupDuration = 0f
                            target.attackPattern = EnemyAttackPattern.NONE
                            target.hitFlash = 0f
                        }
                    }
                }

                pickups.forEach { it.active = false }
                snapshot.pickups.forEachIndexed { idx, sp ->
                    if (idx < pickups.size && map.isWalkable(sp.x, sp.y, 0.12f)) {
                        pickups[idx].active = true
                        pickups[idx].x = sp.x
                        pickups[idx].y = sp.y
                        pickups[idx].itemId = sp.itemId
                    }
                }
            } else {
                var legacyBossesToMarkDefeated = bossesDefeated
                for (enemy in enemies) {
                    if (legacyBossesToMarkDefeated <= 0) break
                    if (enemy.active && enemy.kind.isBoss) {
                        enemy.active = false
                        enemy.hp = 0
                        legacyBossesToMarkDefeated--
                    }
                }
            }

            if (snapshot.discoveredMask.length == discoveredTiles.size) {
                var count = 0
                for (i in discoveredTiles.indices) {
                    val revealed = snapshot.discoveredMask[i] == '1'
                    discoveredTiles[i] = revealed
                    if (revealed) count++
                }
                discoveredTileCount = count
            } else {
                discoveredTiles.fill(false)
                discoveredTileCount = 0
            }
            cachedMiniMapTerrain = map.encodeMiniMapTerrain()
            lastRevealTileX = -999
            lastRevealTileY = -999
            revealAroundPlayer(force = true)

            floatingTexts.forEach { it.active = false }
            hasActiveFloatingTexts = false
            clearSoundCues()

            findNearestInteractable()
            showToast("Збереження завантажено")
        }
    }

    fun buildHudSnapshot(governor: AdaptiveQualityGovernor, drawCalls: Int): HudUiState {
        synchronized(stateLock) {
            val potCount = backpack.firstOrNull { it.id == "crimson_potion" }?.count ?: 0
            val hint = when {
                !map.gateUnlocked -> "Мета: знайдіть важіль у Gearworks та відчиніть Браму"
                bossesDefeated < totalBosses -> "Мета: здолайте Варту Крипти (${bossesDefeated}/$totalBosses)"
                else -> "Крипту очищено!"
            }
            val cfg = governor.resolveRenderConfig()
            return HudUiState(
                phase = phase,
                hp = playerHp,
                maxHp = playerMaxHp,
                stamina = playerStamina,
                maxStamina = playerMaxStamina,
                gold = playerGold,
                atkTotal = playerAtkTotal(),
                defTotal = playerDefTotal(),
                kills = kills,
                totalBosses = totalBosses,
                bossesDefeated = bossesDefeated,
                gateUnlocked = map.gateUnlocked,
                questHintUk = hint,
                interactPromptUk = nearestInteractPrompt,
                cleaveCooldownRatio = (cleaveCooldown / 2.4f).coerceIn(0f, 1f),
                dashCooldownRatio = (dashCooldown / 1.15f).coerceIn(0f, 1f),
                potionCount = potCount,
                equippedWeapon = equippedWeapon,
                equippedShield = equippedShield,
                equippedHelmet = equippedHelmet,
                equippedCloak = equippedCloak,
                heroPortraitId = cachedVisualLoadout.portraitId,
                portraitMatchesLoadoutExactly = cachedVisualLoadout.portraitMatchesLoadoutExactly,
                backpack = backpack.toList(),
                activeDialogue = activeDialogue,
                toastMessageUk = pendingToastUk,
                telemetry = com.example.game.model.TelemetrySnapshot(
                    fps = governor.currentFps,
                    frameTimeMs = governor.currentFrameMs,
                    qualityMode = governor.mode,
                    activeTier = cfg.activeTier,
                    isCustomOverride = cfg.isCustomOverride,
                    resolutionScale = cfg.resolutionScale,
                    shaderLightingStrength = cfg.shaderLightingStrength,
                    activeEntities = activeEntitiesRendered,
                    activeParticles = activeParticlesCount,
                    drawCalls = drawCalls,
                    atlasDecodeMs = governor.atlasDecodeMs,
                    atlasUploadCallMs = governor.atlasUploadCallMs,
                    atlasLoadTotalCpuMs = governor.atlasLoadTotalCpuMs,
                    atlasTextureMiB = governor.atlasTextureMiB,
                    maxTextureSize = governor.maxTextureSize,
                    useLegacyHeroFallback = governor.useLegacyHeroFallback,
                    cpuSubmitP95Ms = governor.computeCpuSubmitP95Ms(),
                    cpuSubmitJankCount = governor.cpuSubmitJankCount,
                    sampleCount = governor.sampleCount
                ),
                miniMap = buildMiniMapSnapshot()
            )
        }
    }

    fun isTileDiscovered(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until DungeonMap.WIDTH || ty !in 0 until DungeonMap.HEIGHT) return false
        return discoveredTiles[ty * DungeonMap.WIDTH + tx]
    }

    fun revealAroundPlayer(force: Boolean = false) {
        val tx = floor(playerX.toDouble()).toInt()
        val ty = floor(playerY.toDouble()).toInt()
        if (!force && tx == lastRevealTileX && ty == lastRevealTileY) return

        lastRevealTileX = tx
        lastRevealTileY = ty
        val rInt = 6
        val rSq = VISION_RADIUS_TILES * VISION_RADIUS_TILES
        val minX = (tx - rInt).coerceAtLeast(0)
        val maxX = (tx + rInt).coerceAtMost(DungeonMap.WIDTH - 1)
        val minY = (ty - rInt).coerceAtLeast(0)
        val maxY = (ty + rInt).coerceAtMost(DungeonMap.HEIGHT - 1)

        var changed = force || cachedDiscoveredMask.isEmpty()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = (x + 0.5f) - playerX
                val dy = (y + 0.5f) - playerY
                if (dx * dx + dy * dy <= rSq) {
                    val idx = y * DungeonMap.WIDTH + x
                    if (!discoveredTiles[idx]) {
                        discoveredTiles[idx] = true
                        discoveredTileCount++
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            val sb = StringBuilder(discoveredTiles.size)
            for (b in discoveredTiles) {
                sb.append(if (b) '1' else '0')
            }
            cachedDiscoveredMask = sb.toString()
        }
    }

    private fun buildMiniMapSnapshot(): MiniMapState {
        val terrain = if (map.gateUnlocked && !cachedMiniMapTerrain.contains('O')) {
            cachedMiniMapTerrain = map.encodeMiniMapTerrain()
            cachedMiniMapTerrain
        } else {
            cachedMiniMapTerrain
        }
        val markers = buildList {
            for (p in props) {
                if (!p.active) continue
                val ptx = floor(p.x.toDouble()).toInt()
                val pty = floor(p.y.toDouble()).toInt()
                when (p.kind) {
                    PropKind.LEVER -> if (!p.used) {
                        add(MiniMapMarker(p.x, p.y, MiniMapMarkerKind.LEVER, isPrimaryQuest = !map.gateUnlocked))
                    }
                    PropKind.NPC_CHRONICLER, PropKind.NPC_CULTIST -> if (isTileDiscovered(ptx, pty)) {
                        add(MiniMapMarker(p.x, p.y, MiniMapMarkerKind.NPC, isPrimaryQuest = false))
                    }
                    PropKind.CHEST -> if (!p.used && isTileDiscovered(ptx, pty)) {
                        add(MiniMapMarker(p.x, p.y, MiniMapMarkerKind.CHEST, isPrimaryQuest = false))
                    }
                    else -> Unit
                }
            }

            add(
                MiniMapMarker(
                    x = DungeonMap.GATE_CENTER_X,
                    y = DungeonMap.GATE_CENTER_Y,
                    kind = if (map.gateUnlocked) MiniMapMarkerKind.GATE_OPEN else MiniMapMarkerKind.GATE_LOCKED,
                    isPrimaryQuest = !map.gateUnlocked
                )
            )

            for (e in enemies) {
                if (!e.active || !e.kind.isBoss) continue
                val bossTileX = floor(e.x.toDouble()).toInt()
                val bossTileY = floor(e.y.toDouble()).toInt()
                if (isTileDiscovered(bossTileX, bossTileY)) {
                    add(MiniMapMarker(e.x, e.y, MiniMapMarkerKind.BOSS, isPrimaryQuest = map.gateUnlocked))
                }
            }
        }

        val pct = ((discoveredTileCount * 100f) / discoveredTiles.size.toFloat()).toInt().coerceIn(0, 100)
        return MiniMapState(
            mapWidth = DungeonMap.WIDTH,
            mapHeight = DungeonMap.HEIGHT,
            playerX = playerX,
            playerY = playerY,
            playerFacing = playerFacing,
            terrainLayout = terrain,
            discoveredMask = cachedDiscoveredMask,
            discoveredPercent = pct,
            gateUnlocked = map.gateUnlocked,
            markers = markers
        )
    }

    fun spawnFloatingText(
        worldX: Float,
        worldY: Float,
        amountText: String,
        tagText: String,
        affinity: ElementalAffinity,
        effectiveness: AffinityEffectiveness,
        isCrit: Boolean,
        isPlayerHit: Boolean
    ) {
        var target = floatingTexts.firstOrNull { !it.active }
        if (target == null) {
            target = floatingTexts.maxByOrNull { it.age } ?: floatingTexts[0]
        }
        target.apply {
            active = true
            id = nextFloatingTextId++
            this.worldX = worldX
            this.worldY = worldY
            this.amountText = amountText
            this.tagText = tagText
            this.affinity = affinity
            this.effectiveness = effectiveness
            this.isCrit = isCrit
            this.isPlayerHit = isPlayerHit
            age = 0f
            duration = if (isCrit) 1.15f else 0.95f
            driftX = (nextRand01() - 0.5f) * 0.72f
        }
        hasActiveFloatingTexts = true
    }

    private fun updateFloatingTexts(dt: Float) {
        var anyActive = false
        for (ft in floatingTexts) {
            if (!ft.active) continue
            ft.age += dt
            if (ft.age >= ft.duration) {
                ft.active = false
            } else {
                anyActive = true
            }
        }
        hasActiveFloatingTexts = anyActive
    }

    fun exportFloatingTextSnapshot(renderCamX: Float = camX, renderCamY: Float = camY): FloatingTextFrameSnapshot {
        synchronized(stateLock) {
            if (!hasActiveFloatingTexts) {
                return FloatingTextFrameSnapshot(camX = renderCamX, camY = renderCamY)
            }
            val list = buildList {
                for (ft in floatingTexts) {
                    if (!ft.active) continue
                    add(
                        FloatingTextItem(
                            id = ft.id,
                            worldX = ft.worldX,
                            worldY = ft.worldY,
                            amountText = ft.amountText,
                            tagText = ft.tagText,
                            affinity = ft.affinity,
                            effectiveness = ft.effectiveness,
                            isCrit = ft.isCrit,
                            isPlayerHit = ft.isPlayerHit,
                            progress = (ft.age / ft.duration).coerceIn(0f, 1f),
                            driftX = ft.driftX
                        )
                    )
                }
            }
            return FloatingTextFrameSnapshot(
                camX = renderCamX,
                camY = renderCamY,
                items = list
            )
        }
    }

    private fun nextRand01(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return ((x ushr 1) and 0xFFFF) / 65536f
    }

    private fun buildItemCatalog(): Map<String, RpgItem> = mapOf(
        "iron_sword" to RpgItem(
            id = "iron_sword",
            nameUk = "Залізний Меч",
            descriptionUk = "Фіз. клинок (+6 ATK, УДАР: Магія)",
            type = ItemType.WEAPON,
            spriteId = SpriteId.ITEM_SWORD,
            atkBonus = 6,
            affinity = ElementalAffinity.PHYSICAL,
            critBonus = 0.0f
        ),
        "battle_axe" to RpgItem(
            id = "battle_axe",
            nameUk = "Бойова Сокира",
            descriptionUk = "Вогонь Кузні (+15 ATK, +12% Крит)",
            type = ItemType.WEAPON,
            spriteId = SpriteId.ITEM_AXE,
            atkBonus = 15,
            affinity = ElementalAffinity.FIRE,
            critBonus = 0.12f
        ),
        "kite_shield" to RpgItem(
            id = "kite_shield",
            nameUk = "Щит Лицаря",
            descriptionUk = "Кований щит із емблемою (+6 DEF)",
            type = ItemType.SHIELD,
            spriteId = SpriteId.ITEM_SHIELD,
            defBonus = 6
        ),
        "knight_helmet" to RpgItem(
            id = "knight_helmet",
            nameUk = "Шолом Вартового",
            descriptionUk = "Важкий сталевий шолом (+8 DEF)",
            type = ItemType.HELMET,
            spriteId = SpriteId.ITEM_HELMET,
            defBonus = 8
        ),
        "shadow_hood" to RpgItem(
            id = "shadow_hood",
            nameUk = "Тіньовий Капюшон",
            descriptionUk = "Легкий плащ (+3 DEF, +0.9 Швидк., +8% Крит)",
            type = ItemType.CLOAK,
            spriteId = SpriteId.ITEM_HOOD,
            defBonus = 3,
            spdBonus = 0.9f,
            critBonus = 0.08f
        ),
        "crimson_potion" to RpgItem(
            id = "crimson_potion",
            nameUk = "Червоне Зілля",
            descriptionUk = "Миттєво відновлює 55 HP",
            type = ItemType.CONSUMABLE,
            spriteId = SpriteId.ITEM_POTION,
            healAmount = 55
        )
    )

    private fun queueSoundCue(cue: SoundCue) {
        if (soundCueCount >= soundCueBuffer.size) {
            soundCueHead = (soundCueHead + 1) % soundCueBuffer.size
            soundCueCount--
        }
        soundCueBuffer[soundCueTail] = cue
        soundCueTail = (soundCueTail + 1) % soundCueBuffer.size
        soundCueCount++
        hasPendingSoundCues = true
    }

    private fun clearSoundCues() {
        soundCueHead = 0
        soundCueTail = 0
        soundCueCount = 0
        hasPendingSoundCues = false
    }

    fun drainSoundCues(out: MutableList<SoundCue>) {
        synchronized(stateLock) {
            while (soundCueCount > 0) {
                out.add(soundCueBuffer[soundCueHead])
                soundCueHead = (soundCueHead + 1) % soundCueBuffer.size
                soundCueCount--
            }
            hasPendingSoundCues = false
        }
    }
}
