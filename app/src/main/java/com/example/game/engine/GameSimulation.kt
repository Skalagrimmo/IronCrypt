package com.example.game.engine

import com.example.game.model.ActiveDialogue
import com.example.game.model.Direction
import com.example.game.model.GamePhase
import com.example.game.model.HudUiState
import com.example.game.model.ItemType
import com.example.game.model.QualityTier
import com.example.game.model.RenderConfig
import com.example.game.model.RpgItem
import com.example.game.model.GameSaveSnapshot
import com.example.game.model.SavedEnemyState
import com.example.game.model.SavedItemStack
import com.example.game.model.SavedPickupState
import com.example.game.model.SavedPropState
import com.example.game.model.SpriteId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
    var attackCooldown = 0f
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

/**
 * Fixed-pool real-time RPG simulation. Zero allocations during update ticks so Cortex-A53
 * cores on Exynos 7870 avoid GC frame hitches.
 */
class GameSimulation(val map: DungeonMap = DungeonMap()) {

    companion object {
        const val MAX_ENEMIES = 24
        const val MAX_PROPS = 40
        const val MAX_PROJECTILES = 24
        const val MAX_PICKUPS = 24
        const val MAX_PARTICLES = 64
    }

    val enemies = Array(MAX_ENEMIES) { EnemyActor() }
    val props = Array(MAX_PROPS) { WorldProp() }
    val projectiles = Array(MAX_PROJECTILES) { Projectile() }
    val pickups = Array(MAX_PICKUPS) { Pickup() }
    val particles = Array(MAX_PARTICLES) { Particle() }

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
    var playerX = 4.5f
    var playerY = 12.0f
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
    var camX = 4.5f
    var camY = 12.0f

    // RPG Equipment & Inventory (mutated under simulationLock when opened)
    val stateLock = Any()
    private val itemCatalog = buildItemCatalog()
    var equippedWeapon: RpgItem? = itemCatalog["iron_sword"]
    var equippedShield: RpgItem? = null
    var equippedHelmet: RpgItem? = null
    var equippedCloak: RpgItem? = null
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

    private var rngState = 0x5A17C931

    init {
        resetRun(GamePhase.TITLE)
    }

    fun resetRun(startPhase: GamePhase = GamePhase.PLAYING) {
        synchronized(stateLock) {
            map.reset()
            phase = startPhase
            playerX = 4.5f
            playerY = 12.0f
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
            backpack.clear()
            backpack.add(itemCatalog["crimson_potion"]!!.copy(count = 2))

            enemies.forEach { it.active = false }
            props.forEach { it.active = false }
            projectiles.forEach { it.active = false }
            pickups.forEach { it.active = false }
            particles.forEach { it.active = false }

            populateDungeon()
        }
    }

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

        // Torches along walls (drive cheap per-vertex warm torch lighting)
        addProp(PropKind.TORCH, 3.5f, 2.2f)
        addProp(PropKind.TORCH, 10.5f, 2.2f)
        addProp(PropKind.TORCH, 15.5f, 2.2f)
        addProp(PropKind.TORCH, 21.5f, 2.2f)
        addProp(PropKind.TORCH, 26.5f, 2.2f)
        addProp(PropKind.TORCH, 32.5f, 2.2f)
        addProp(PropKind.TORCH, 26.5f, 19.5f)
        addProp(PropKind.TORCH, 32.5f, 19.5f)

        // NPCs
        addProp(PropKind.NPC_CHRONICLER, 5.8f, 9.2f, solid = true)
        addProp(PropKind.NPC_CULTIST, 15.2f, 19.5f, solid = true)

        // Interactive Lever in north wing of center corridor
        addProp(PropKind.LEVER, 19.5f, 3.6f, solid = true)
        addProp(PropKind.GEARS, 21.0f, 3.4f, solid = true)

        // Treasure Chests
        addProp(PropKind.CHEST, 11.2f, 4.2f, solid = true, loot = "kite_shield")
        addProp(PropKind.CHEST, 18.0f, 4.2f, solid = true, loot = "battle_axe")
        addProp(PropKind.CHEST, 29.5f, 3.5f, solid = true, loot = "knight_helmet")

        // Destructible Barrels & Decorative Props
        addProp(PropKind.BARREL, 3.2f, 15.5f, solid = true)
        addProp(PropKind.BARREL, 4.2f, 16.0f, solid = true)
        addProp(PropKind.BARREL, 15.0f, 9.0f, solid = true)
        addProp(PropKind.BARREL, 20.5f, 16.5f, solid = true)
        addProp(PropKind.BARREL, 26.0f, 11.5f, solid = true)
        addProp(PropKind.BARRICADE, 15.5f, 14.5f, solid = true)
        addProp(PropKind.SKULLS, 24.8f, 4.2f, solid = false)
        addProp(PropKind.SKULLS, 33.2f, 18.8f, solid = false)
        addProp(PropKind.CHANDELIER, 29.5f, 11.5f, solid = false)

        // Enemies across the 3 chambers
        spawnEnemy(EnemyKind.SKELETON, 9.5f, 6.5f)
        spawnEnemy(EnemyKind.SKELETON, 10.5f, 15.5f)
        spawnEnemy(EnemyKind.SKELETON, 15.5f, 11.5f)
        spawnEnemy(EnemyKind.GARGOYLE, 17.5f, 10.0f)
        spawnEnemy(EnemyKind.GARGOYLE, 20.5f, 13.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 18.5f, 5.5f)
        spawnEnemy(EnemyKind.AUTOMATON, 21.0f, 18.0f)
        spawnEnemy(EnemyKind.SKELETON, 26.5f, 9.0f)
        spawnEnemy(EnemyKind.GARGOYLE, 27.0f, 15.0f)
        spawnEnemy(EnemyKind.BOSS_MECH, 31.0f, 10.0f)
        spawnEnemy(EnemyKind.BOSS_DEMON, 31.8f, 14.2f)
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
            return
        }

        synchronized(stateLock) {
            updatePlayer(dt, config)
            updateEnemies(dt, config)
            updateProjectiles(dt, config)
            updatePickups(dt, config)
            updateParticles(dt)
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
                } else {
                    dashDirX = when (playerFacing) {
                        Direction.EAST -> 1f; Direction.WEST -> -1f; else -> 0f
                    }
                    dashDirY = when (playerFacing) {
                        Direction.SOUTH -> 1f; Direction.NORTH -> -1f; else -> 0f
                    }
                }
                spawnParticleBurst(playerX, playerY + 0.2f, 6, 0.7f, 0.75f, 0.85f, config)
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
                playerFacing = if (abs(nx) > abs(ny)) {
                    if (nx > 0f) Direction.EAST else Direction.WEST
                } else {
                    if (ny > 0f) Direction.SOUTH else Direction.NORTH
                }
            }
        }

        // Primary Slash
        if (attackQueued) {
            attackQueued = false
            if (attackCooldown <= 0f) {
                attackCooldown = 0.30f
                attackAnimTimer = 0.22f
                performMeleeStrike(radius = 1.65f, coneCos = -0.15f, damage = playerAtkTotal(), knockback = 4.2f, config = config)
            }
        }

        // Heavy 360 Cleave
        if (cleaveQueued) {
            cleaveQueued = false
            if (cleaveCooldown <= 0f && playerStamina >= 25f) {
                playerStamina -= 25f
                cleaveCooldown = 2.4f
                attackAnimTimer = 0.28f
                performMeleeStrike(radius = 2.25f, coneCos = -1.1f, damage = (playerAtkTotal() * 1.8f).toInt(), knockback = 7.0f, config = config)
                spawnParticleBurst(playerX, playerY, 14, 0.95f, 0.65f, 0.2f, config)
                if (config.enableScreenShake) {
                    screenShakeTimer = 0.16f
                    screenShakeStrength = 0.12f
                }
            }
        }

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
        config: RenderConfig
    ) {
        val fx = when (playerFacing) {
            Direction.EAST -> 1f; Direction.WEST -> -1f; else -> 0f
        }
        val fy = when (playerFacing) {
            Direction.SOUTH -> 1f; Direction.NORTH -> -1f; else -> 0f
        }

        // Check enemies
        for (e in enemies) {
            if (!e.active) continue
            val dx = e.x - playerX
            val dy = e.y - playerY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= radius + e.kind.radius) {
                val dot = if (dist > 0.001f) (dx * fx + dy * fy) / dist else 1f
                if (dot >= coneCos) {
                    damageEnemy(e, damage, dx, dy, dist, knockback, config)
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
        spawnParticleBurst(e.x, e.y, 7, 1f, 0.35f, 0.25f, config)

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
            val aggroRange = if (e.kind.isBoss) 9.5f else 7.0f

            if (e.attackWindup > 0f) {
                e.attackWindup -= dt
                e.hitFlash = 0.45f
                if (e.attackWindup <= 0f) {
                    if (e.kind.isRanged) {
                        fireEnemyProjectile(e, dx, dy, dist)
                    } else if (dist <= e.kind.radius + 1.05f) {
                        damagePlayer(e.kind.atk, config)
                    }
                    e.attackCooldown = if (e.kind.isBoss) 1.15f else 1.45f
                }
                continue
            }

            if (dist < aggroRange) {
                e.facing = if (abs(dx) > abs(dy)) {
                    if (dx >= 0f) Direction.EAST else Direction.WEST
                } else {
                    if (dy >= 0f) Direction.SOUTH else Direction.NORTH
                }

                val desiredRange = if (e.kind.isRanged) 4.4f else (e.kind.radius + 0.62f)
                if (dist > desiredRange) {
                    val step = e.kind.speed * dt
                    val nx = (dx / dist) * step
                    val ny = (dy / dist) * step
                    if (map.isWalkable(e.x + nx, e.y, e.kind.radius)) e.x += nx
                    if (map.isWalkable(e.x, e.y + ny, e.kind.radius)) e.y += ny
                } else if (e.attackCooldown <= 0f) {
                    e.attackWindup = 0.38f
                }
            }
        }
    }

    private fun fireEnemyProjectile(e: EnemyActor, dx: Float, dy: Float, dist: Float) {
        val baseAngle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val count = if (e.kind == EnemyKind.BOSS_DEMON) 3 else 1
        for (i in 0 until count) {
            val offset = if (count == 1) 0f else (i - 1) * 0.28f
            val angle = baseAngle + offset
            for (p in projectiles) {
                if (!p.active) {
                    p.active = true
                    p.fromPlayer = false
                    p.x = e.x
                    p.y = e.y
                    p.vx = cos(angle) * 5.8f
                    p.vy = sin(angle) * 5.8f
                    p.damage = e.kind.atk
                    p.life = 2.2f
                    p.r = 1f; p.g = 0.32f; p.b = 0.18f
                    break
                }
            }
        }
    }

    private fun damagePlayer(rawAtk: Int, config: RenderConfig) {
        if (invulnTimer > 0f || phase != GamePhase.PLAYING) return
        val mitigated = (rawAtk - playerDefTotal()).coerceAtLeast(3)
        playerHp = (playerHp - mitigated).coerceAtLeast(0)
        invulnTimer = 0.55f
        playerHitFlash = 1f
        spawnParticleBurst(playerX, playerY, 8, 0.95f, 0.2f, 0.2f, config)
        if (config.enableScreenShake) {
            screenShakeTimer = 0.15f
            screenShakeStrength = 0.10f
        }
        if (playerHp <= 0) {
            phase = GamePhase.DEFEAT
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
                    damagePlayer(p.damage, config)
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
                collectItem(p.lootId)
                spawnParticleBurst(p.x, p.y, 12, 0.95f, 0.8f, 0.25f, config)
            }
            PropKind.LEVER -> if (!p.used) {
                p.used = true
                map.unlockGate()
                showToast("Залізну Браму до Святилища відчинено!")
                spawnParticleBurst(p.x, p.y, 10, 0.9f, 0.6f, 0.2f, config)
            }
            PropKind.NPC_CHRONICLER -> {
                activeDialogue = ActiveDialogue(
                    "chronicler",
                    DialogueScriptStore.getStartNode("chronicler", map.gateUnlocked)
                )
            }
            PropKind.NPC_CULTIST -> {
                activeDialogue = ActiveDialogue(
                    "cultist",
                    DialogueScriptStore.getStartNode("cultist", map.gateUnlocked)
                )
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
                    backpack.removeAt(idx)
                    if (prev != null) backpack.add(prev)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.SHIELD -> {
                    val prev = equippedShield
                    equippedShield = item.copy(count = 1)
                    backpack.removeAt(idx)
                    if (prev != null) backpack.add(prev)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.HELMET -> {
                    val prev = equippedHelmet
                    equippedHelmet = item.copy(count = 1)
                    backpack.removeAt(idx)
                    if (prev != null) backpack.add(prev)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.CLOAK -> {
                    val prev = equippedCloak
                    equippedCloak = item.copy(count = 1)
                    backpack.removeAt(idx)
                    if (prev != null) backpack.add(prev)
                    showToast("Екіпіровано: ${item.nameUk}")
                }
                ItemType.GOLD -> Unit
            }
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
        playerHp = (playerHp + pot.healAmount).coerceAtMost(playerMaxHp)
        if (pot.count > 1) {
            backpack[idx] = pot.copy(count = pot.count - 1)
        } else {
            backpack.removeAt(idx)
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
                pickups = savedPickups
            )
        }
    }

    fun restoreSnapshot(snapshot: GameSaveSnapshot) {
        synchronized(stateLock) {
            resetRun(GamePhase.PLAYING)
            if (snapshot.gateUnlocked) {
                map.unlockGate()
            }
            playerX = snapshot.playerX
            playerY = snapshot.playerY
            camX = playerX
            camY = playerY
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

            backpack.clear()
            snapshot.backpack.forEach { stack ->
                val template = itemCatalog[stack.itemId]
                if (template != null && stack.count > 0) {
                    backpack.add(template.copy(count = stack.count))
                }
            }

            snapshot.props.forEach { sp ->
                if (sp.index in props.indices) {
                    props[sp.index].active = sp.active
                    props[sp.index].used = sp.used
                }
            }

            snapshot.enemies.forEach { se ->
                if (se.index in enemies.indices) {
                    val target = enemies[se.index]
                    target.active = se.active
                    target.hp = se.hp
                    target.x = se.x
                    target.y = se.y
                    target.facing = se.facing
                    target.attackWindup = 0f
                    target.hitFlash = 0f
                }
            }

            pickups.forEach { it.active = false }
            snapshot.pickups.forEachIndexed { idx, sp ->
                if (idx < pickups.size) {
                    pickups[idx].active = true
                    pickups[idx].x = sp.x
                    pickups[idx].y = sp.y
                    pickups[idx].itemId = sp.itemId
                }
            }

            findNearestInteractable()
            showToast("Збереження завантажено")
        }
    }

    fun buildHudSnapshot(governor: ExynosQualityGovernor, drawCalls: Int): HudUiState {
        synchronized(stateLock) {
            val potCount = backpack.firstOrNull { it.id == "crimson_potion" }?.count ?: 0
            val hint = when {
                !map.gateUnlocked -> "Мета: знайдіть важіль у коридорі та відчиніть Браму"
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
                    drawCalls = drawCalls
                )
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
            descriptionUk = "Надійний клинок стража (+6 ATK)",
            type = ItemType.WEAPON,
            spriteId = SpriteId.ITEM_SWORD,
            atkBonus = 6
        ),
        "battle_axe" to RpgItem(
            id = "battle_axe",
            nameUk = "Бойова Сокира",
            descriptionUk = "Важке лезо, що трощить броню (+15 ATK)",
            type = ItemType.WEAPON,
            spriteId = SpriteId.ITEM_AXE,
            atkBonus = 15
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
            descriptionUk = "Легкий плащ (+3 DEF, +0.9 Швидкість)",
            type = ItemType.CLOAK,
            spriteId = SpriteId.ITEM_HOOD,
            defBonus = 3,
            spdBonus = 0.9f
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
}
