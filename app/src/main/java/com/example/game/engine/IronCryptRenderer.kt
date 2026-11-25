package com.example.game.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.example.R
import com.example.game.model.AtlasId
import com.example.game.model.Direction
import com.example.game.model.GamePhase
import com.example.game.model.RenderConfig
import com.example.game.model.SpriteId
import com.example.game.model.SpriteRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 2.0 mobile renderer with a conservative, allocation-conscious draw path:
 * - Single 1024x1024 GL_NEAREST sprite atlas + tiny 64x64 procedural glow/shadow texture
 * - Viewport-culled tilemap + Y-sorted entities using pre-allocated buffers (zero GC)
 * - Strictly caps active objects/particles and updates shader uniforms from [RenderConfig]
 */
class IronCryptRenderer(
    private val context: Context,
    private val simulation: GameSimulation,
    private val governor: AdaptiveQualityGovernor,
    private val onScaleRequested: (Float) -> Unit,
    private val onHudUpdate: () -> Unit,
    private val onSoundCuesReady: () -> Unit = {}
) : GLSurfaceView.Renderer {

    private val batcher = GlSpriteBatcher(maxQuads = 1400)
    private var atlasTexId = 0
    private var heroCoreTexId = 0
    private var heroVariantsTexId = 0
    private var glowTexId = 0
    private var useLegacyHeroFallback = false
    private var maxTextureSize = 0
    private val glQueryBuf = IntArray(1)

    private val projMatrix = FloatArray(16)
    private var viewWidth = 1280
    private var viewHeight = 720
    private val visibleWorldHeight = 9.2f

    private var lastFrameNs = 0L
    private var lastHudSyncNs = 0L
    private var lastPerfLogNs = 0L
    private var lastDispatchedScale: Float = -1f

    @Volatile
    var lastDrawCalls: Int = 0
        private set

    @Volatile
    var renderCamX: Float = simulation.camX
        private set

    @Volatile
    var renderCamY: Float = simulation.camY
        private set

    // Pre-allocated sort buffers for Y-sorted actors/props (zero per-frame heap alloc)
    private val maxRenderables = GameSimulation.MAX_PROPS + GameSimulation.MAX_ENEMIES + 2
    private val sortKeys = FloatArray(maxRenderables)
    private val sortTypes = IntArray(maxRenderables) // 0=prop, 1=enemy, 2=hero
    private val sortIndices = IntArray(maxRenderables)
    private var renderableCount = 0

    private val fullUvRect = SpriteRect(0, 0, 1024, 1024)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.067f, 0.086f, 1.0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        batcher.initGl()

        atlasTexId = 0
        heroCoreTexId = 0
        heroVariantsTexId = 0
        glowTexId = 0
        useLegacyHeroFallback = false

        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, glQueryBuf, 0)
        maxTextureSize = glQueryBuf[0]
        drainGlErrors()

        val loadStartNs = System.nanoTime()
        var totalDecodeNs = 0L
        var totalUploadNs = 0L

        val mainTiming = loadAtlasTexture(R.drawable.ironcrypt_atlas)
        atlasTexId = mainTiming.texId
        totalDecodeNs += mainTiming.decodeNs
        totalUploadNs += mainTiming.uploadNs
        glowTexId = createSoftRadialTexture()

        if (maxTextureSize >= 2048) {
            val coreResult = loadAtlasTexture(AtlasId.HERO_CORE.drawableRes)
            totalDecodeNs += coreResult.decodeNs
            totalUploadNs += coreResult.uploadNs

            val variantsResult = if (coreResult.success) {
                loadAtlasTexture(AtlasId.HERO_VARIANTS.drawableRes)
            } else {
                TextureLoadResult(0, 0L, 0L, false, coreResult.errorCode)
            }
            totalDecodeNs += variantsResult.decodeNs
            totalUploadNs += variantsResult.uploadNs

            if (coreResult.success && variantsResult.success) {
                heroCoreTexId = coreResult.texId
                heroVariantsTexId = variantsResult.texId
                useLegacyHeroFallback = false
            } else {
                releaseHeroTexturesOnFailure(coreResult.texId, variantsResult.texId)
                useLegacyHeroFallback = true
                Log.w(
                    "IRONCRYPT_GL",
                    "Dark Hero atlas upload failed (coreOk=${coreResult.success}, variantsOk=${variantsResult.success}, glErr=0x${Integer.toHexString(variantsResult.errorCode)}), activating legacy hero fallback"
                )
            }
        } else {
            useLegacyHeroFallback = true
            Log.w(
                "IRONCRYPT_GL",
                "GL_MAX_TEXTURE_SIZE=$maxTextureSize < 2048, activating legacy hero fallback"
            )
        }

        val totalLoadMs = (System.nanoTime() - loadStartNs) / 1_000_000f
        val decodeMs = totalDecodeNs / 1_000_000f
        val uploadMs = totalUploadNs / 1_000_000f
        val texMiB = if (useLegacyHeroFallback) 4.0f else 36.0f
        governor.updateAtlasTelemetry(
            decodeMs = decodeMs,
            uploadCallMs = uploadMs,
            totalCpuMs = totalLoadMs,
            textureMiB = texMiB,
            glMaxTextureSize = maxTextureSize,
            legacyFallback = useLegacyHeroFallback
        )
        Log.i(
            "IRONCRYPT_GL",
            "Atlases ready: glMax=$maxTextureSize fallback=$useLegacyHeroFallback texMiB=$texMiB decodeMs=$decodeMs uploadMs=$uploadMs totalMs=$totalLoadMs"
        )

        lastFrameNs = System.nanoTime()
        lastPerfLogNs = lastFrameNs
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((frameStartNs - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = frameStartNs

        val config = governor.resolveRenderConfig()
        simulation.update(dt, config)
        if (simulation.hasPendingSoundCues) {
            onSoundCuesReady()
        }

        val aspect = viewWidth.toFloat() / viewHeight.toFloat()
        val halfH = visibleWorldHeight * 0.5f
        val halfW = halfH * aspect

        var shakeX = 0f
        var shakeY = 0f
        if (config.enableScreenShake && simulation.screenShakeTimer > 0f) {
            val s = simulation.screenShakeStrength * (simulation.screenShakeTimer / 0.25f).coerceIn(0f, 1f)
            shakeX = sin(simulation.globalTime * 55f) * s
            shakeY = cos(simulation.globalTime * 48f) * s
        }

        val cx = simulation.camX + shakeX
        val cy = simulation.camY + shakeY
        renderCamX = cx
        renderCamY = cy
        Matrix.orthoM(projMatrix, 0, cx - halfW, cx + halfW, cy + halfH, cy - halfH, -1f, 1f)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        batcher.beginFrame(projMatrix)

        val flicker = if (config.torchFlickerEnabled) {
            0.94f + sin(simulation.globalTime * 11f) * 0.06f
        } else 1.0f

        // 1) Pass 1: Viewport-culled tiles + pickups + Y-sorted props/hero/enemies (1 texture bind!)
        batcher.beginBatch(
            textureId = atlasTexId,
            additive = false,
            lightingStrength = config.shaderLightingStrength,
            flickerFactor = flicker,
            warmthTint = config.warmthTintStrength
        )
        drawVisibleTiles(cx, cy, halfW, halfH, config)
        drawPickups(cx, cy, halfW, halfH)
        drawSortedWorldActors(cx, cy, halfW, halfH, config)
        batcher.flush()

        // 2) Pass 2: Shadows, enemy warning zones and crisp HP bars (soft radial glow texture)
        batcher.beginBatch(
            textureId = glowTexId,
            additive = false,
            lightingStrength = 0f,
            flickerFactor = 1f,
            warmthTint = 0f
        )
        if (config.enableGroundShadows) {
            drawGroundShadows(cx, cy, halfW, halfH)
        }
        drawEnemyTelegraphs(cx, cy, halfW, halfH)
        drawEnemyHealthBars()
        batcher.flush()

        // 3) Pass 3: Additive Torch Halos + Projectiles + Pooled Combat Particles
        batcher.beginBatch(
            textureId = glowTexId,
            additive = true,
            lightingStrength = 0f,
            flickerFactor = flicker,
            warmthTint = config.warmthTintStrength
        )
        if (config.enableTorchGlow) {
            drawTorchHalos(cx, cy, halfW, halfH, flicker)
        }
        drawProjectilesAndParticles(cx, cy, halfW, halfH)
        batcher.flush()
        lastDrawCalls = batcher.drawCallsThisFrame

        val workDoneNs = System.nanoTime()
        val workMs = (workDoneNs - frameStartNs) / 1_000_000f
        governor.recordFrame(workDoneNs, workMs)

        val nextScale = governor.resolveRenderConfig().resolutionScale
        if (nextScale != lastDispatchedScale) {
            lastDispatchedScale = nextScale
            onScaleRequested(nextScale)
        }

        // Sync HUD snapshot at ~15 Hz to avoid excessive Compose recomposition
        if (workDoneNs - lastHudSyncNs >= 66_000_000L) {
            lastHudSyncNs = workDoneNs
            onHudUpdate()
        }

        // Emit periodic structured performance log for mobile OpenGL ES 2.0 telemetry
        if (workDoneNs - lastPerfLogNs >= 2_500_000_000L) {
            lastPerfLogNs = workDoneNs
            Log.i(
                "IRONCRYPT_PERF",
                governor.formatPerfReportLine(
                    drawCalls = lastDrawCalls,
                    entities = simulation.activeEntitiesRendered,
                    particles = simulation.activeParticlesCount
                )
            )
        }
    }

    private fun drawVisibleTiles(cx: Float, cy: Float, halfW: Float, halfH: Float, config: RenderConfig) {
        val minTx = floor((cx - halfW - 1f).toDouble()).toInt().coerceAtLeast(0)
        val maxTx = floor((cx + halfW + 1f).toDouble()).toInt().coerceAtMost(DungeonMap.WIDTH - 1)
        val minTy = floor((cy - halfH - 1f).toDouble()).toInt().coerceAtLeast(0)
        val maxTy = floor((cy + halfH + 1f).toDouble()).toInt().coerceAtMost(DungeonMap.HEIGHT - 1)

        val map = simulation.map
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val type = map.getTile(tx, ty)
                val sprite = map.spriteForTile(type)
                val light = if (config.shaderLightingStrength <= 0.05f) 1f else computeTorchLight(tx + 0.5f, ty + 0.5f)
                val r = (light * 1.04f).coerceAtMost(1f)
                val g = (light * 0.98f).coerceAtMost(1f)
                val b = (light * 0.94f).coerceAtMost(1f)
                batcher.drawSprite(
                    x = tx.toFloat(),
                    y = ty.toFloat(),
                    w = 1.01f,
                    h = 1.01f,
                    rect = sprite.rect,
                    r = r, g = g, b = b, a = 1f
                )
            }
        }
    }

    private fun drawPickups(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        for (pk in simulation.pickups) {
            if (!pk.active) continue
            if (abs(pk.x - cx) > halfW + 1.5f || abs(pk.y - cy) > halfH + 1.5f) continue
            val bob = sin(pk.bobPhase) * 0.08f
            val sprite = when (pk.itemId) {
                "gold_coins" -> SpriteId.ITEM_COINS
                "crimson_potion" -> SpriteId.ITEM_POTION
                "battle_axe" -> SpriteId.ITEM_AXE
                "kite_shield" -> SpriteId.ITEM_SHIELD
                "knight_helmet" -> SpriteId.ITEM_HELMET
                "shadow_hood" -> SpriteId.ITEM_HOOD
                else -> SpriteId.ITEM_SWORD
            }
            batcher.drawSprite(
                x = pk.x - 0.28f,
                y = pk.y - 0.34f + bob,
                w = 0.56f,
                h = 0.56f,
                rect = sprite.rect
            )
        }
    }

    private fun drawSortedWorldActors(cx: Float, cy: Float, halfW: Float, halfH: Float, config: RenderConfig) {
        renderableCount = 0
        var entityBudgetUsed = 0

        // Collect visible props
        for (i in simulation.props.indices) {
            val p = simulation.props[i]
            if (!p.active) continue
            if (abs(p.x - cx) > halfW + 1.8f || abs(p.y - cy) > halfH + 1.8f) continue
            if (entityBudgetUsed >= config.maxSimultaneousEntities && p.kind != PropKind.TORCH && p.kind != PropKind.LEVER) {
                continue
            }
            sortKeys[renderableCount] = p.y
            sortTypes[renderableCount] = 0
            sortIndices[renderableCount] = i
            renderableCount++
            entityBudgetUsed++
        }

        // Collect visible enemies
        for (i in simulation.enemies.indices) {
            val e = simulation.enemies[i]
            if (!e.active) continue
            if (abs(e.x - cx) > halfW + 2f || abs(e.y - cy) > halfH + 2f) continue
            if (!e.kind.isBoss && entityBudgetUsed >= config.maxSimultaneousEntities) continue
            sortKeys[renderableCount] = e.y
            sortTypes[renderableCount] = 1
            sortIndices[renderableCount] = i
            renderableCount++
            entityBudgetUsed++
        }
        simulation.activeEntitiesRendered = entityBudgetUsed

        // Hero
        sortKeys[renderableCount] = simulation.playerY
        sortTypes[renderableCount] = 2
        sortIndices[renderableCount] = 0
        renderableCount++

        // Insertion sort (tiny N <= 66, zero allocation, fastest for nearly-sorted frames)
        for (i in 1 until renderableCount) {
            val key = sortKeys[i]
            val type = sortTypes[i]
            val idx = sortIndices[i]
            var j = i - 1
            while (j >= 0 && sortKeys[j] > key) {
                sortKeys[j + 1] = sortKeys[j]
                sortTypes[j + 1] = sortTypes[j]
                sortIndices[j + 1] = sortIndices[j]
                j--
            }
            sortKeys[j + 1] = key
            sortTypes[j + 1] = type
            sortIndices[j + 1] = idx
        }

        for (i in 0 until renderableCount) {
            when (sortTypes[i]) {
                0 -> drawProp(simulation.props[sortIndices[i]])
                1 -> drawEnemy(simulation.enemies[sortIndices[i]])
                2 -> drawHero()
            }
        }
    }

    private fun drawProp(p: WorldProp) {
        val sprite = when (p.kind) {
            PropKind.TORCH -> SpriteId.PROP_TORCH
            PropKind.BARREL -> SpriteId.PROP_BARREL
            PropKind.CHEST -> SpriteId.PROP_CHEST
            PropKind.LEVER -> SpriteId.PROP_LEVER
            PropKind.GEARS -> SpriteId.PROP_GEARS
            PropKind.BARRICADE -> SpriteId.PROP_BARRICADE
            PropKind.SKULLS -> SpriteId.PROP_SKULLS
            PropKind.CHANDELIER -> SpriteId.PROP_CHANDELIER
            PropKind.NPC_CHRONICLER -> SpriteId.NPC_SHADOW
            PropKind.NPC_CULTIST -> SpriteId.NPC_CULTIST
        }
        val (w, h) = when (p.kind) {
            PropKind.TORCH -> 0.42f to 1.05f
            PropKind.BARREL -> 0.72f to 0.98f
            PropKind.CHEST -> 0.95f to 0.88f
            PropKind.LEVER -> 0.62f to 1.05f
            PropKind.GEARS -> 1.15f to 1.15f
            PropKind.BARRICADE -> 1.15f to 0.96f
            PropKind.SKULLS -> 1.05f to 0.78f
            PropKind.CHANDELIER -> 1.15f to 1.15f
            PropKind.NPC_CHRONICLER, PropKind.NPC_CULTIST -> 0.78f to 1.18f
        }
        val tint = if (p.used) 0.58f else 1f
        val flip = p.kind == PropKind.LEVER && p.used
        batcher.drawSprite(
            x = p.x - w * 0.5f,
            y = p.y - h * 0.82f,
            w = w,
            h = h,
            rect = sprite.rect,
            r = tint, g = tint, b = tint, a = 1f,
            flipX = flip
        )
    }

    private fun drawHero() {
        val sim = simulation
        if (!useLegacyHeroFallback && heroCoreTexId != 0 && heroVariantsTexId != 0) {
            val rect = sim.resolveHeroFrameRect()
            val wut = rect.worldUnitsPerTexel
            if (wut > 0f) {
                val targetTexId = when (rect.atlasId) {
                    AtlasId.HERO_CORE -> heroCoreTexId
                    AtlasId.HERO_VARIANTS -> heroVariantsTexId
                    AtlasId.WORLD_MAIN -> atlasTexId
                }
                val pivotInTrimX = rect.footAnchorX - rect.trimOffsetX
                val pivotInTrimY = rect.footAnchorY - rect.trimOffsetY
                val drawW = rect.w * wut
                val drawH = rect.h * wut
                val drawX = sim.playerX - pivotInTrimX * wut
                val drawY = sim.playerY - pivotInTrimY * wut
                val isDead = sim.phase == GamePhase.DEFEAT
                val alpha = if (!isDead && sim.invulnTimer > 0f && ((sim.globalTime * 20f).toInt() % 2 == 0)) {
                    0.55f
                } else 1f
                val flash = if (isDead) 0f else sim.playerHitFlash

                batcher.switchTextureIfNeeded(targetTexId)
                batcher.drawSprite(
                    x = drawX,
                    y = drawY,
                    w = drawW,
                    h = drawH,
                    rect = rect,
                    r = 1f, g = 1f, b = 1f, a = alpha,
                    flash = flash,
                    flipX = false
                )
                batcher.switchTextureIfNeeded(atlasTexId)
                return
            }
        }

        drawLegacyHeroFallback(sim)
    }

    private fun drawLegacyHeroFallback(sim: GameSimulation) {
        if (sim.phase == GamePhase.DEFEAT) {
            batcher.drawSprite(
                x = sim.playerX - 0.55f,
                y = sim.playerY - 0.62f,
                w = 1.10f,
                h = 0.85f,
                rect = SpriteId.HERO_DYING.rect
            )
            return
        }

        val facingWestHalf = sim.playerFacing == Direction.WEST ||
            sim.playerFacing == Direction.NORTH_WEST ||
            sim.playerFacing == Direction.SOUTH_WEST
        val facingEastHalf = sim.playerFacing == Direction.EAST ||
            sim.playerFacing == Direction.NORTH_EAST ||
            sim.playerFacing == Direction.SOUTH_EAST

        val sprite: SpriteId
        var flipX = false
        if (sim.attackAnimTimer > 0f) {
            sprite = if (sim.attackAnimTimer > 0.11f) SpriteId.HERO_ATTACK_1 else SpriteId.HERO_ATTACK_2
            flipX = facingWestHalf
        } else {
            sprite = when {
                facingEastHalf -> SpriteId.HERO_EAST
                facingWestHalf -> SpriteId.HERO_WEST
                sim.playerFacing == Direction.NORTH -> SpriteId.HERO_NORTH
                else -> SpriteId.HERO_SOUTH
            }
        }

        val bob = if (abs(sim.joyX) + abs(sim.joyY) > 0.15f && sim.attackAnimTimer <= 0f) {
            abs(sin(sim.walkAnimTime)) * 0.08f
        } else 0f

        val alpha = if (sim.invulnTimer > 0f && ((sim.globalTime * 20f).toInt() % 2 == 0)) 0.55f else 1f
        val w = if (sprite == SpriteId.HERO_EAST || sprite == SpriteId.HERO_ATTACK_1 || sprite == SpriteId.HERO_ATTACK_2) 1.02f else 0.82f
        val h = 1.16f

        batcher.drawSprite(
            x = sim.playerX - w * 0.5f,
            y = sim.playerY - h * 0.82f - bob,
            w = w,
            h = h,
            rect = sprite.rect,
            r = 1f, g = 1f, b = 1f, a = alpha,
            flash = sim.playerHitFlash,
            flipX = flipX
        )
    }

    private fun drawEnemy(e: EnemyActor) {
        var flipX = false
        val sprite = when (e.kind) {
            EnemyKind.SKELETON -> {
                flipX = e.facing == Direction.WEST
                SpriteId.ENEMY_SKELETON
            }
            EnemyKind.GARGOYLE -> {
                flipX = e.facing == Direction.WEST
                SpriteId.ENEMY_GARGOYLE
            }
            EnemyKind.AUTOMATON -> {
                flipX = e.facing == Direction.WEST
                SpriteId.ENEMY_AUTOMATON
            }
            EnemyKind.BOSS_MECH -> {
                val frame = ((e.animTime * 1.4f).toInt() % 5)
                flipX = e.facing == Direction.WEST
                when (frame) {
                    0 -> SpriteId.BOSS_MECH_1
                    1 -> SpriteId.BOSS_MECH_2
                    2 -> SpriteId.BOSS_MECH_3
                    3 -> SpriteId.BOSS_MECH_4
                    else -> SpriteId.BOSS_MECH_5
                }
            }
            EnemyKind.BOSS_DEMON -> when {
                e.attackWindup > 0f -> SpriteId.BOSS_DEMON_ATTACK
                e.facing == Direction.EAST -> SpriteId.BOSS_DEMON_EAST
                e.facing == Direction.WEST -> SpriteId.BOSS_DEMON_WEST
                else -> SpriteId.BOSS_DEMON_IDLE
            }
        }
        val bob = sin(e.animTime) * 0.04f
        batcher.drawSprite(
            x = e.x - e.kind.scaleW * 0.5f,
            y = e.y - e.kind.scaleH * 0.82f + bob,
            w = e.kind.scaleW,
            h = e.kind.scaleH,
            rect = sprite.rect,
            flash = e.hitFlash,
            flipX = flipX
        )
    }

    /**
     * Ground warnings are drawn before projectiles and actors so the player can read
     * the committed attack lane/area without hiding the enemy or the world.
     */
    private fun drawEnemyTelegraphs(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        for (e in simulation.enemies) {
            if (!e.active || e.attackWindup <= 0f || e.attackPattern == EnemyAttackPattern.NONE) continue
            val pulse = 0.82f + sin(simulation.globalTime * 17f) * 0.12f
            val progress = if (e.attackWindupDuration > 0f) {
                (1f - e.attackWindup / e.attackWindupDuration).coerceIn(0f, 1f)
            } else 0f
            val alpha = (0.34f + progress * 0.28f) * pulse

            when (e.attackPattern) {
                EnemyAttackPattern.NONE -> Unit

                EnemyAttackPattern.SLASH -> {
                    // Three short red lanes show the skeleton's committed frontal sweep.
                    val baseAngle = atan2(e.attackDirY.toDouble(), e.attackDirX.toDouble()).toFloat()
                    for (lane in -1..1) {
                        val angle = baseAngle + lane * 0.66f
                        val dirX = cos(angle)
                        val dirY = sin(angle)
                        drawTelegraphRay(
                            e.x + dirX * 0.42f,
                            e.y + dirY * 0.42f,
                            dirX,
                            dirY,
                            length = 1.16f,
                            dots = 3,
                            diameter = 0.44f,
                            r = 1f, g = 0.10f, b = 0.07f, a = alpha,
                            cx = cx, cy = cy, halfW = halfW, halfH = halfH
                        )
                    }
                }

                EnemyAttackPattern.BASH -> {
                    // A short amber lane warns of the automaton's close-range punch.
                    drawTelegraphRay(
                        e.x + e.attackDirX * 0.30f,
                        e.y + e.attackDirY * 0.30f,
                        e.attackDirX,
                        e.attackDirY,
                        length = 1.05f,
                        dots = 3,
                        diameter = 0.48f,
                        r = 1f, g = 0.54f, b = 0.08f, a = alpha,
                        cx = cx, cy = cy, halfW = halfW, halfH = halfH
                    )
                }

                EnemyAttackPattern.POUNCE -> {
                    // This fixed circle is the gargoyle's take-off target, not the live hero position.
                    drawTelegraphDisc(
                        e.attackTargetX, e.attackTargetY, 2.05f,
                        1f, 0.24f, 0.06f, alpha,
                        cx, cy, halfW, halfH
                    )
                    drawTelegraphDisc(
                        e.attackTargetX, e.attackTargetY, 0.52f,
                        1f, 0.58f, 0.18f, alpha * 0.72f,
                        cx, cy, halfW, halfH
                    )
                }

                EnemyAttackPattern.SLAM -> {
                    // Three soft rings communicate the automaton's broad impact radius.
                    drawTelegraphDisc(e.x, e.y, 4.05f, 1f, 0.36f, 0.06f, alpha * 0.48f, cx, cy, halfW, halfH)
                    drawTelegraphDisc(e.x, e.y, 2.90f, 1f, 0.48f, 0.08f, alpha * 0.62f, cx, cy, halfW, halfH)
                    drawTelegraphDisc(e.x, e.y, 1.35f, 1f, 0.64f, 0.12f, alpha * 0.78f, cx, cy, halfW, halfH)
                }

                EnemyAttackPattern.CHARGE -> {
                    // A dotted crimson lane shows the boss's committed charge direction.
                    drawTelegraphRay(
                        e.x, e.y, e.attackDirX, e.attackDirY,
                        length = 4.18f,
                        dots = 9,
                        diameter = 0.52f,
                        r = 1f, g = 0.06f, b = 0.04f, a = alpha,
                        cx = cx, cy = cy, halfW = halfW, halfH = halfH
                    )
                }

                EnemyAttackPattern.FIRE_FAN -> {
                    // Five violet lanes map directly to the five fireballs released at impact.
                    val baseAngle = atan2(e.attackDirY.toDouble(), e.attackDirX.toDouble()).toFloat()
                    for (lane in -2..2) {
                        val angle = baseAngle + lane * 0.20f
                        drawTelegraphRay(
                            e.x, e.y, cos(angle), sin(angle),
                            length = 3.25f,
                            dots = 3,
                            diameter = 0.34f,
                            r = 0.72f, g = 0.22f, b = 1f, a = alpha * 0.86f,
                            cx = cx, cy = cy, halfW = halfW, halfH = halfH
                        )
                    }
                }

                EnemyAttackPattern.FIRE_ORB -> {
                    // A single heavy orb is aimed at close range; its lane is narrower and brighter.
                    drawTelegraphRay(
                        e.x, e.y, e.attackDirX, e.attackDirY,
                        length = 3.0f,
                        dots = 5,
                        diameter = 0.46f,
                        r = 0.92f, g = 0.16f, b = 1f, a = alpha,
                        cx = cx, cy = cy, halfW = halfW, halfH = halfH
                    )
                }
            }
        }
    }

    private fun drawTelegraphRay(
        startX: Float,
        startY: Float,
        dirX: Float,
        dirY: Float,
        length: Float,
        dots: Int,
        diameter: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float
    ) {
        for (i in 1..dots) {
            val distance = length * i.toFloat() / dots.toFloat()
            drawTelegraphDisc(
                startX + dirX * distance,
                startY + dirY * distance,
                diameter,
                r, g, b, a,
                cx, cy, halfW, halfH
            )
        }
    }

    private fun drawTelegraphDisc(
        worldX: Float,
        worldY: Float,
        diameter: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float
    ) {
        if (abs(worldX - cx) > halfW + diameter || abs(worldY - cy) > halfH + diameter) return
        val radius = diameter * 0.5f
        batcher.drawSprite(
            x = worldX - radius,
            y = worldY - radius,
            w = diameter,
            h = diameter,
            rect = fullUvRect,
            r = r, g = g, b = b, a = a
        )
    }

    private fun drawEnemyHealthBars() {
        for (e in simulation.enemies) {
            if (!e.active || e.hp >= e.maxHp) continue
            val ratio = (e.hp.toFloat() / e.maxHp.toFloat()).coerceIn(0f, 1f)
            val bw = if (e.kind.isBoss) (e.kind.scaleW * 0.78f).coerceIn(1.55f, 1.90f) else 0.78f
            val bx = e.x - bw * 0.5f
            val by = e.y - e.kind.scaleH * 0.92f
            // Dark frame
            batcher.drawUv(bx - 0.03f, by - 0.02f, bw + 0.06f, 0.13f, 0.5f, 0.5f, 0.5f, 0.5f, 0.08f, 0.08f, 0.1f, 0.88f)
            // Crimson fill
            batcher.drawUv(bx, by, bw * ratio, 0.09f, 0.5f, 0.5f, 0.5f, 0.5f, 0.92f, 0.20f, 0.20f, 1f)
        }
    }

    private fun drawGroundShadows(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        // Hero shadow
        batcher.drawSprite(
            x = simulation.playerX - 0.42f,
            y = simulation.playerY - 0.16f,
            w = 0.84f,
            h = 0.34f,
            rect = fullUvRect,
            r = 0f, g = 0f, b = 0f, a = 0.45f
        )
        for (e in simulation.enemies) {
            if (!e.active) continue
            if (abs(e.x - cx) > halfW + 1.5f || abs(e.y - cy) > halfH + 1.5f) continue
            val sw = e.kind.scaleW * 0.82f
            batcher.drawSprite(
                x = e.x - sw * 0.5f,
                y = e.y - 0.16f,
                w = sw,
                h = 0.34f,
                rect = fullUvRect,
                r = 0f, g = 0f, b = 0f, a = 0.45f
            )
        }
    }

    private fun drawTorchHalos(cx: Float, cy: Float, halfW: Float, halfH: Float, flicker: Float) {
        for (p in simulation.props) {
            if (!p.active || p.kind != PropKind.TORCH) continue
            if (abs(p.x - cx) > halfW + 2.5f || abs(p.y - cy) > halfH + 2.5f) continue
            val r = 2.15f * flicker
            batcher.drawSprite(
                x = p.x - r,
                y = p.y - 0.55f - r,
                w = r * 2f,
                h = r * 2f,
                rect = fullUvRect,
                r = 1.0f, g = 0.58f, b = 0.18f, a = 0.26f
            )
        }
    }

    private fun drawProjectilesAndParticles(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        for (proj in simulation.projectiles) {
            if (!proj.active) continue
            if (abs(proj.x - cx) > halfW + 1f || abs(proj.y - cy) > halfH + 1f) continue
            batcher.drawSprite(
                x = proj.x - 0.26f,
                y = proj.y - 0.26f,
                w = 0.52f,
                h = 0.52f,
                rect = fullUvRect,
                r = proj.r, g = proj.g, b = proj.b, a = 0.95f
            )
        }
        for (pt in simulation.particles) {
            if (!pt.active) continue
            val alpha = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            batcher.drawSprite(
                x = pt.x - pt.size,
                y = pt.y - pt.size,
                w = pt.size * 2f,
                h = pt.size * 2f,
                rect = fullUvRect,
                r = pt.r, g = pt.g, b = pt.b, a = alpha
            )
        }
    }

    private fun computeTorchLight(wx: Float, wy: Float): Float {
        var best = 0.62f
        val props = simulation.props
        for (i in props.indices) {
            val p = props[i]
            if (!p.active || p.kind != PropKind.TORCH) continue
            val dx = wx - p.x
            val dy = wy - p.y
            val d2 = dx * dx + dy * dy
            if (d2 < 24f) {
                val contrib = 0.62f + (1f - d2 / 24f) * 0.42f
                if (contrib > best) best = contrib
            }
        }
        return best
    }

    private data class TextureLoadResult(
        val texId: Int,
        val decodeNs: Long,
        val uploadNs: Long,
        val success: Boolean,
        val errorCode: Int = GLES20.GL_NO_ERROR
    )

    private fun drainGlErrors(): Int {
        var lastErr = GLES20.GL_NO_ERROR
        while (true) {
            val err = GLES20.glGetError()
            if (err == GLES20.GL_NO_ERROR) break
            lastErr = err
        }
        return lastErr
    }

    private fun releaseHeroTexturesOnFailure(vararg ids: Int) {
        val toDelete = IntArray(ids.size)
        var count = 0
        for (id in ids) {
            if (id != 0) {
                toDelete[count++] = id
            }
        }
        if (count > 0) {
            GLES20.glDeleteTextures(count, toDelete, 0)
        }
        heroCoreTexId = 0
        heroVariantsTexId = 0
        drainGlErrors()
    }

    private fun loadAtlasTexture(drawableRes: Int): TextureLoadResult {
        drainGlErrors()
        var texId = 0
        var decodeNs = 0L
        var uploadNs = 0L
        var bmp: Bitmap? = null
        return try {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decStart = System.nanoTime()
            bmp = BitmapFactory.decodeResource(context.resources, drawableRes, opts)
            decodeNs = System.nanoTime() - decStart
            if (bmp == null) {
                return TextureLoadResult(0, decodeNs, 0L, false, GLES20.GL_OUT_OF_MEMORY)
            }

            val upStart = System.nanoTime()
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texId = texIds[0]
            if (texId == 0) {
                uploadNs = System.nanoTime() - upStart
                val err = drainGlErrors().let { if (it == GLES20.GL_NO_ERROR) GLES20.GL_OUT_OF_MEMORY else it }
                return TextureLoadResult(0, decodeNs, uploadNs, false, err)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            uploadNs = System.nanoTime() - upStart

            val glErr = drainGlErrors()
            if (glErr != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                drainGlErrors()
                TextureLoadResult(0, decodeNs, uploadNs, false, glErr)
            } else {
                TextureLoadResult(texId, decodeNs, uploadNs, true, GLES20.GL_NO_ERROR)
            }
        } catch (_: OutOfMemoryError) {
            if (texId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            }
            drainGlErrors()
            TextureLoadResult(0, decodeNs, uploadNs, false, GLES20.GL_OUT_OF_MEMORY)
        } finally {
            bmp?.recycle()
        }
    }

    private fun createSoftRadialTexture(): Int {
        val size = 64
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        val half = size * 0.5f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x + 0.5f - half) / half
                val dy = (y + 0.5f - half) / half
                val dist = sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                val alpha = ((1f - dist) * (1f - dist) * 255f).toInt().coerceIn(0, 255)
                buf.put(255.toByte())
                buf.put(255.toByte())
                buf.put(255.toByte())
                buf.put(alpha.toByte())
            }
        }
        buf.position(0)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        return texIds[0]
    }
}
