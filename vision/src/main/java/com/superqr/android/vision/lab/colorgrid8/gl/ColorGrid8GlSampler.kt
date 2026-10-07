package com.superqr.android.vision.lab.colorgrid8.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream

class ColorGrid8GlSampler {

    private var finderProgram = 0
    private var cellProgram = 0
    private var macrochromaCellProgram = 0

    private val fbos = IntArray(3)
    private val fboTextures = IntArray(3)

    companion object {
        const val FINDER_WIDTH = 960
        const val FINDER_HEIGHT = 720
    }

    private val finderBuf = ByteBuffer.allocateDirect(FINDER_WIDTH * FINDER_HEIGHT * 4).order(ByteOrder.nativeOrder())
    private val finderResult = ByteArray(FINDER_WIDTH * FINDER_HEIGHT * 4)
    private val headerBuf = ByteBuffer.allocateDirect(ColorGrid8HeaderSearch.READBACK_BYTES).order(ByteOrder.nativeOrder())
    private val headerRaw = ByteArray(ColorGrid8HeaderSearch.READBACK_BYTES)
    private val headerLuma = ByteArray(ColorGrid8HeaderSearch.WIDTH * ColorGrid8HeaderSearch.HEIGHT)

    private var cellBuf: ByteBuffer? = null
    private var cellRaw: ByteArray? = null
    private var cellY: ByteArray? = null
    private var cellU: ByteArray? = null
    private var cellV: ByteArray? = null
    private var cellTextureCols = 0
    private var cellTextureRows = 0
    var readbackMs = 0.0
        private set
    var readbackBytes = 0
        private set

    fun beginFrame() { readbackMs = 0.0; readbackBytes = 0 }

    private fun prepareCellTarget(cols: Int, rows: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTextures[1])
        if (cellTextureCols != cols || cellTextureRows != rows) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, cols, rows, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            cellTextureCols = cols
            cellTextureRows = rows
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[1])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTextures[1], 0)
    }

    private fun ensureCellBuffers(len: Int) {
        if (cellBuf == null || cellBuf!!.capacity() != len * 4) {
            cellBuf = ByteBuffer.allocateDirect(len * 4).order(ByteOrder.nativeOrder())
            cellRaw = ByteArray(len * 4)
            cellY = ByteArray(len)
            cellU = ByteArray(len)
            cellV = ByteArray(len)
        }
    }

    private var camWidth = 0
    private var camHeight = 0

    private var quadVao = 0
    private var cellVao = 0

    fun setCameraDimensions(cameraWidth: Int, cameraHeight: Int) {
        camWidth = cameraWidth
        camHeight = cameraHeight
    }

    fun init(cameraWidth: Int, cameraHeight: Int) {
        camWidth = cameraWidth
        camHeight = cameraHeight

        finderProgram = createProgram(ColorGrid8GlShaders.PASSTHROUGH_VERTEX, ColorGrid8GlShaders.FINDER_THUMBNAIL_FRAGMENT)
        cellProgram = createProgram(ColorGrid8GlShaders.PASSTHROUGH_VERTEX, ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT)
        macrochromaCellProgram = createProgram(ColorGrid8GlShaders.PASSTHROUGH_VERTEX, ColorGrid8GlShaders.MACROCHROMA_CELL_SAMPLE_FRAGMENT)

        GLES30.glGenFramebuffers(fbos.size, fbos, 0)
        GLES30.glGenTextures(fboTextures.size, fboTextures, 0)

        // Finder FBO
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTextures[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, FINDER_WIDTH, FINDER_HEIGHT, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTextures[0], 0)

        // Header acquisition has its own fixed target: never resize the payload
        // texture back and forth when switching between acquisition and decode.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTextures[2])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            ColorGrid8HeaderSearch.WIDTH, ColorGrid8HeaderSearch.HEIGHT, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[2])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTextures[2], 0)

        // Setup VBO for quad
        val vaos = IntArray(2)
        GLES30.glGenVertexArrays(2, vaos, 0)
        quadVao = vaos[0]
        cellVao = vaos[1]

        val quadCoords = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        val fb = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(quadCoords).position(0)

        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES30.GL_STATIC_DRAW)

        val posLoc = GLES30.glGetAttribLocation(finderProgram, "aPosition")
        val texLoc = GLES30.glGetAttribLocation(finderProgram, "aTexCoord")
        if (posLoc >= 0) {
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, 0)
        }
        if (texLoc >= 0) {
            GLES30.glEnableVertexAttribArray(texLoc)
            GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, 8)
        }

        // Cell VBO (just a point)
        GLES30.glBindVertexArray(cellVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[1])
        val pointFb = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        pointFb.put(floatArrayOf(0f, 0f)).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pointFb.capacity() * 4, pointFb, GLES30.GL_STATIC_DRAW)
        val cPosLoc = GLES30.glGetAttribLocation(cellProgram, "aPosition")
        if (cPosLoc >= 0) {
            GLES30.glEnableVertexAttribArray(cPosLoc)
            GLES30.glVertexAttribPointer(cPosLoc, 2, GLES30.GL_FLOAT, false, 8, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    fun renderFinderThumbnail(textureId: Int, texMatrix: FloatArray): ByteArray {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[0])
        GLES30.glViewport(0, 0, FINDER_WIDTH, FINDER_HEIGHT)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(finderProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finderProgram, "uCaptureColor"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val locTex = GLES30.glGetUniformLocation(finderProgram, "uTexture")
        GLES30.glUniform1i(locTex, 0)
        val locMat = GLES30.glGetUniformLocation(finderProgram, "uTexMatrix")
        GLES30.glUniformMatrix4fv(locMat, 1, false, texMatrix, 0)

        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        finderBuf.clear()
        val readStart = System.nanoTime()
        GLES30.glReadPixels(0, 0, FINDER_WIDTH, FINDER_HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, finderBuf)
        finderBuf.get(finderResult)
        readbackMs += (System.nanoTime() - readStart) / 1_000_000.0
        readbackBytes += finderResult.size

        return finderResult
    }

    /** One raw diagnostic frame, at most 4K RGBA (32 MiB), with the same
     * SurfaceTexture transform and row order as the finder/header sampler.
     * This intentionally stalls and must not be included in throughput runs. */
    fun captureCameraRgba(textureId: Int, texMatrix: FloatArray, output: File) {
        require(camWidth > 0 && camHeight > 0 && camWidth.toLong() * camHeight <= 3840L * 2160L)
        val captureFbo = IntArray(1)
        val captureTexture = IntArray(1)
        GLES30.glGenFramebuffers(1, captureFbo, 0)
        GLES30.glGenTextures(1, captureTexture, 0)
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTexture[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, camWidth, camHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, captureTexture[0], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glViewport(0, 0, camWidth, camHeight)
            GLES30.glUseProgram(finderProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(finderProgram, "uTexture"), 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(finderProgram, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(finderProgram, "uCaptureColor"), 1)
            GLES30.glBindVertexArray(quadVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            val raw = ByteBuffer.allocateDirect(camWidth * camHeight * 4)
            GLES30.glReadPixels(0, 0, camWidth, camHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, raw)
            raw.position(0)
            FileOutputStream(output).channel.use { channel ->
                while (raw.hasRemaining()) channel.write(raw)
            }
        } finally {
            GLES30.glUseProgram(finderProgram)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(finderProgram, "uCaptureColor"), 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, captureFbo, 0)
            GLES30.glDeleteTextures(1, captureTexture, 0)
        }
    }

    fun renderHeaderCandidates(textureId: Int, texMatrix: FloatArray, homographies: FloatArray,
                               fiducialOffset: Int, sampleMode: Int): ByteArray {
        require(homographies.size == ColorGrid8HeaderSearch.ROTATIONS * 9)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[2])
        GLES30.glViewport(0, 0, ColorGrid8HeaderSearch.WIDTH, ColorGrid8HeaderSearch.HEIGHT)
        GLES30.glUseProgram(cellProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(cellProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(cellProgram, "uHeaderHomographies[0]"),
            ColorGrid8HeaderSearch.ROTATIONS, false, homographies, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uHeaderSearch"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uSampleMode"), sampleMode)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cellProgram, "uCameraSize"), camWidth.toFloat(), camHeight.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uFiducialOffset"), fiducialOffset)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        headerBuf.clear()
        val readStart = System.nanoTime()
        GLES30.glReadPixels(0, 0, ColorGrid8HeaderSearch.WIDTH, ColorGrid8HeaderSearch.HEIGHT,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, headerBuf)
        headerBuf.get(headerRaw)
        readbackMs += (System.nanoTime() - readStart) / 1_000_000.0
        readbackBytes += headerRaw.size
        for (i in headerLuma.indices) headerLuma[i] = headerRaw[i * 4]
        return headerLuma
    }

    fun renderCellMeans(textureId: Int, texMatrix: FloatArray, homography: FloatArray, cols: Int, rows: Int, fiducialOffset: Int, sampleMode: Int, cellPitchPx: Float = 1.0f, offsetX: Float = 0f, offsetY: Float = 0f): Triple<ByteArray, ByteArray, ByteArray> {
        // Init/resize cell FBO texture if needed
        prepareCellTarget(cols, rows)

        GLES30.glViewport(0, 0, cols, rows)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(cellProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(cellProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(cellProgram, "uHomography"), 1, false, homography, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(cellProgram, "uGridSize"), cols, rows)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uSampleMode"), sampleMode)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cellProgram, "uCameraSize"), camWidth.toFloat(), camHeight.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uFiducialOffset"), fiducialOffset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(cellProgram, "uCellPitchPx"), cellPitchPx)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cellProgram, "uHeaderSearch"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cellProgram, "uCellOffset"), offsetX, offsetY)

        // Draw fullscreen quad; fragment shader uses gl_FragCoord to determine cell (col, row)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val len = cols * rows
        ensureCellBuffers(len)
        val buf = cellBuf!!
        val raw = cellRaw!!
        val y = cellY!!
        val u = cellU!!
        val v = cellV!!
        buf.clear()
        val readStart = System.nanoTime()
        GLES30.glReadPixels(0, 0, cols, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.get(raw, 0, len * 4)
        readbackMs += (System.nanoTime() - readStart) / 1_000_000.0
        readbackBytes += len * 4

        for (i in 0 until len) {
            y[i] = raw[i * 4]
            u[i] = raw[i * 4 + 1]
            v[i] = raw[i * 4 + 2]
        }
        return Triple(y, u, v)
    }

    fun renderMacrochromaCellMeans(textureId: Int, texMatrix: FloatArray, homography: FloatArray, cols: Int, rows: Int, fiducialOffset: Int, sampleMode: Int, cellPitchPx: Float = 1.0f): Triple<ByteArray, ByteArray, ByteArray> {
        prepareCellTarget(cols, rows)

        GLES30.glViewport(0, 0, cols, rows)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(macrochromaCellProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(macrochromaCellProgram, "uTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(macrochromaCellProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(macrochromaCellProgram, "uHomography"), 1, false, homography, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(macrochromaCellProgram, "uGridSize"), cols, rows)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(macrochromaCellProgram, "uSampleMode"), sampleMode)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(macrochromaCellProgram, "uCameraSize"), camWidth.toFloat(), camHeight.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(macrochromaCellProgram, "uFiducialOffset"), fiducialOffset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(macrochromaCellProgram, "uCellPitchPx"), cellPitchPx)

        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val len = cols * rows
        ensureCellBuffers(len)
        val buf = cellBuf!!
        val raw = cellRaw!!
        val y = cellY!!
        val u = cellU!!
        val v = cellV!!
        buf.clear()
        GLES30.glReadPixels(0, 0, cols, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.get(raw, 0, len * 4)

        for (i in 0 until len) {
            y[i] = raw[i * 4]
            u[i] = raw[i * 4 + 1]
            v[i] = raw[i * 4 + 2]
        }
        return Triple(y, u, v)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
        GLES30.glDeleteTextures(fboTextures.size, fboTextures, 0)
        GLES30.glDeleteProgram(finderProgram)
        GLES30.glDeleteProgram(cellProgram)
        GLES30.glDeleteProgram(macrochromaCellProgram)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            try { android.util.Log.e("ColorGrid8Sampler", "Program link error: $log") } catch (_: Throwable) {}
        } else {
            try { android.util.Log.i("ColorGrid8Sampler", "Program linked successfully ($program)") } catch (_: Throwable) {}
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            try { android.util.Log.e("ColorGrid8Sampler", "Shader compile error ($type): $log") } catch (_: Throwable) {}
        }
        return shader
    }
}
