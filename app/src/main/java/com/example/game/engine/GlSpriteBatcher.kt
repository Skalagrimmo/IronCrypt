package com.example.game.engine

import android.opengl.GLES20
import com.example.game.model.SpriteRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Zero-allocation OpenGL ES 2.0 quad batcher designed for Mali-T830 MP1 (Exynos 7870).
 * Exposes lightweight mediump shader uniforms (uLightingStrength, uFlickerFactor, uWarmthTint)
 * with zero fragment branching so quality settings adjust lighting & grading at full speed.
 */
class GlSpriteBatcher(private val maxQuads: Int = 1400) {

    companion object {
        private const val FLOATS_PER_VERTEX = 9 // x, y, u, v, r, g, b, a, flash
        private const val VERTICES_PER_QUAD = 4
        private const val INDICES_PER_QUAD = 6
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            attribute vec4 aColor;
            attribute float aFlash;
            varying vec2 vTexCoord;
            varying vec4 vColor;
            varying float vFlash;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
                vColor = aColor;
                vFlash = aFlash;
            }
        """

        // Strict mediump branchless fragment shader driven by uniform quality parameters
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec4 vColor;
            varying float vFlash;
            uniform sampler2D uTexture;
            uniform float uLightingStrength;
            uniform float uFlickerFactor;
            uniform float uWarmthTint;
            void main() {
                vec4 tex = texture2D(uTexture, vTexCoord);
                vec3 effectiveLight = mix(vec3(1.0), vColor.rgb * uFlickerFactor, uLightingStrength);
                vec3 lit = tex.rgb * effectiveLight;
                vec3 graded = vec3(
                    lit.r * (1.0 + 0.08 * uWarmthTint),
                    lit.g * (1.0 + 0.02 * uWarmthTint),
                    lit.b * (1.0 - 0.06 * uWarmthTint)
                );
                vec3 finalRgb = mix(graded, vec3(1.0, 0.92, 0.80), vFlash);
                gl_FragColor = vec4(finalRgb, tex.a * vColor.a);
            }
        """
    }

    private val vertexData = FloatArray(maxQuads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(maxQuads * INDICES_PER_QUAD * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()

    private var quadCount = 0
    private var currentTextureId = 0
    var drawCallsThisFrame = 0
        private set

    private var programId = 0
    private var uMvpLoc = 0
    private var uTextureLoc = 0
    private var uLightingStrengthLoc = 0
    private var uFlickerFactorLoc = 0
    private var uWarmthTintLoc = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var aColLoc = 0
    private var aFlashLoc = 0

    private var mvpMatrix = FloatArray(16)

    init {
        val indices = ShortArray(maxQuads * INDICES_PER_QUAD)
        var v = 0
        for (i in 0 until maxQuads) {
            val base = i * INDICES_PER_QUAD
            indices[base] = v.toShort()
            indices[base + 1] = (v + 1).toShort()
            indices[base + 2] = (v + 2).toShort()
            indices[base + 3] = (v + 2).toShort()
            indices[base + 4] = (v + 3).toShort()
            indices[base + 5] = v.toShort()
            v += 4
        }
        indexBuffer.put(indices)
        indexBuffer.position(0)
    }

    fun initGl() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
        }
        uMvpLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")
        uLightingStrengthLoc = GLES20.glGetUniformLocation(programId, "uLightingStrength")
        uFlickerFactorLoc = GLES20.glGetUniformLocation(programId, "uFlickerFactor")
        uWarmthTintLoc = GLES20.glGetUniformLocation(programId, "uWarmthTint")
        aPosLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        aColLoc = GLES20.glGetAttribLocation(programId, "aColor")
        aFlashLoc = GLES20.glGetAttribLocation(programId, "aFlash")
    }

    fun beginFrame(mvp: FloatArray) {
        mvpMatrix = mvp
        drawCallsThisFrame = 0
        currentTextureId = 0
    }

    fun beginBatch(
        textureId: Int,
        additive: Boolean = false,
        lightingStrength: Float = 1f,
        flickerFactor: Float = 1f,
        warmthTint: Float = 1f
    ) {
        quadCount = 0
        currentTextureId = textureId
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(uLightingStrengthLoc, lightingStrength)
        GLES20.glUniform1f(uFlickerFactorLoc, flickerFactor)
        GLES20.glUniform1f(uWarmthTintLoc, warmthTint)

        if (additive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    fun switchTextureIfNeeded(textureId: Int) {
        if (textureId == 0 || textureId == currentTextureId) return
        flush()
        currentTextureId = textureId
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
    }

    fun drawSprite(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rect: SpriteRect,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        flash: Float = 0f,
        flipX: Boolean = false
    ) {
        drawUv(
            x = x, y = y, w = w, h = h,
            u0 = if (flipX) rect.u1 else rect.u0,
            v0 = rect.v0,
            u1 = if (flipX) rect.u0 else rect.u1,
            v1 = rect.v1,
            r = r, g = g, b = b, a = a, flash = flash
        )
    }

    fun drawUv(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        flash: Float = 0f
    ) {
        if (quadCount >= maxQuads) {
            flush()
        }
        val idx = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        val x2 = x + w
        val y2 = y + h

        // Top-left
        vertexData[idx] = x; vertexData[idx + 1] = y
        vertexData[idx + 2] = u0; vertexData[idx + 3] = v0
        vertexData[idx + 4] = r; vertexData[idx + 5] = g; vertexData[idx + 6] = b; vertexData[idx + 7] = a
        vertexData[idx + 8] = flash

        // Bottom-left
        val v1i = idx + FLOATS_PER_VERTEX
        vertexData[v1i] = x; vertexData[v1i + 1] = y2
        vertexData[v1i + 2] = u0; vertexData[v1i + 3] = v1
        vertexData[v1i + 4] = r; vertexData[v1i + 5] = g; vertexData[v1i + 6] = b; vertexData[v1i + 7] = a
        vertexData[v1i + 8] = flash

        // Bottom-right
        val v2i = v1i + FLOATS_PER_VERTEX
        vertexData[v2i] = x2; vertexData[v2i + 1] = y2
        vertexData[v2i + 2] = u1; vertexData[v2i + 3] = v1
        vertexData[v2i + 4] = r; vertexData[v2i + 5] = g; vertexData[v2i + 6] = b; vertexData[v2i + 7] = a
        vertexData[v2i + 8] = flash

        // Top-right
        val v3i = v2i + FLOATS_PER_VERTEX
        vertexData[v3i] = x2; vertexData[v3i + 1] = y
        vertexData[v3i + 2] = u1; vertexData[v3i + 3] = v0
        vertexData[v3i + 4] = r; vertexData[v3i + 5] = g; vertexData[v3i + 6] = b; vertexData[v3i + 7] = a
        vertexData[v3i + 8] = flash

        quadCount++
    }

    fun flush() {
        if (quadCount == 0) return
        val floatCount = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        vertexBuffer.clear()
        vertexBuffer.put(vertexData, 0, floatCount)
        vertexBuffer.position(0)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glEnableVertexAttribArray(aColLoc)
        GLES20.glEnableVertexAttribArray(aFlashLoc)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)

        vertexBuffer.position(4)
        GLES20.glVertexAttribPointer(aColLoc, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)

        vertexBuffer.position(8)
        GLES20.glVertexAttribPointer(aFlashLoc, 1, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)

        indexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            quadCount * INDICES_PER_QUAD,
            GLES20.GL_UNSIGNED_SHORT,
            indexBuffer
        )

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
        GLES20.glDisableVertexAttribArray(aColLoc)
        GLES20.glDisableVertexAttribArray(aFlashLoc)

        drawCallsThisFrame++
        quadCount = 0
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
}
