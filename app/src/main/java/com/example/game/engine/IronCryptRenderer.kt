package com.example.game.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.example.R
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 2.0 renderer tuned for Exynos 7870 (Mali-T830 MP1):
 * - Single 1024x1024 GL_NEAREST sprite atlas + tiny 64x64 procedural glow/shadow texture
 * - Viewport-culled tilemap + Y-sorted entities using pre-allocated buffers (zero GC)
 * - Strictly caps active objects/particles and updates shader uniforms from [RenderConfig]
 */
class IronCryptRenderer(
    private val context: Context,
    private val simulation: GameSimulation,
    private val governor: ExynosQualityGovernor,
    private val onScaleRequested: (Float) -> Unit,
    private val onHudUpdate: () -> Unit
) : GLSurfaceView.Renderer {

    private val batcher = GlSpriteBatcher(maxQuads = 1400)
    private var atlasTexId = 0
    private var glowTexId = 0

    private val projMatrix = FloatArray(16)
    private var viewWidth = 1280
    private var viewHeight = 720
    private val visibleWorldHeight = 9.2f

    private var lastFrameNs = 0L
    private var lastHudSyncNs = 0L
    private var lastDispatchedScale: Float = -1f

    @Volatile
    var lastDrawCalls: Int = 0
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
        atlasTexId = loadAtlasTexture()
        glowTexId = createSoftRadialTexture()
        lastFrameNs = System.nanoTime()
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

        // 2) Pass 2: Shadows & crisp enemy HP bars (uses pure white center of glowTexId)
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

        val sprite: SpriteId
        var flipX = false
        if (sim.attackAnimTimer > 0f) {
            sprite = if (sim.attackAnimTimer > 0.11f) SpriteId.HERO_ATTACK_1 else SpriteId.HERO_ATTACK_2
            flipX = sim.playerFacing == Direction.WEST
        } else {
            sprite = when (sim.playerFacing) {
                Direction.SOUTH -> SpriteId.HERO_SOUTH
                Direction.NORTH -> SpriteId.HERO_NORTH
                Direction.EAST -> SpriteId.HERO_EAST
                Direction.WEST -> SpriteId.HERO_WEST
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

    private fun drawEnemyHealthBars() {
        for (e in simulation.enemies) {
            if (!e.active || e.hp >= e.maxHp) continue
            val ratio = (e.hp.toFloat() / e.maxHp.toFloat()).coerceIn(0f, 1f)
            val bw = if (e.kind.isBoss) 1.35f else 0.78f
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

    private fun loadAtlasTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.ironcrypt_atlas, opts)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return texIds[0]
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
