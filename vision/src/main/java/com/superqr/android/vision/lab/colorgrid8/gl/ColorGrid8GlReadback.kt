package com.superqr.android.vision.lab.colorgrid8.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ColorGrid8GlReadback {

    private val PBO_COUNT = 3
    private val pbos = IntArray(PBO_COUNT)
    private val fences = LongArray(PBO_COUNT) { 0L }
    private var ringIndex = 0
    private var initialized = false

    fun init(bufferSize: Int) {
        GLES30.glGenBuffers(PBO_COUNT, pbos, 0)
        for (i in 0 until PBO_COUNT) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bufferSize, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        initialized = true
    }

    fun beginReadback(fbo: Int, width: Int, height: Int, format: Int) {
        if (!initialized) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[ringIndex])

        GLES30.glReadPixels(0, 0, width, height, format, GLES30.GL_UNSIGNED_BYTE, 0)

        if (fences[ringIndex] != 0L) {
            GLES30.glDeleteSync(fences[ringIndex])
        }
        fences[ringIndex] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun waitAndMap(): ByteBuffer? {
        if (!initialized) return null

        val fence = fences[ringIndex]
        if (fence == 0L) {
            ringIndex = (ringIndex + 1) % PBO_COUNT
            return null
        }

        val waitResult = GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 5000000L) // 5ms timeout
        if (waitResult == GLES30.GL_TIMEOUT_EXPIRED || waitResult == GLES30.GL_WAIT_FAILED) {
            return null
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[ringIndex])
        val mappedBuffer = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, 0, GLES30.GL_MAP_READ_BIT) as? ByteBuffer

        if (mappedBuffer != null) {
            mappedBuffer.order(ByteOrder.nativeOrder())
            // The buffer remains mapped until unmapped by the caller or next use
        }

        ringIndex = (ringIndex + 1) % PBO_COUNT
        return mappedBuffer
    }

    fun unmap() {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[(ringIndex - 1 + PBO_COUNT) % PBO_COUNT])
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun release() {
        if (!initialized) return
        GLES30.glDeleteBuffers(PBO_COUNT, pbos, 0)
        for (i in 0 until PBO_COUNT) {
            if (fences[i] != 0L) {
                GLES30.glDeleteSync(fences[i])
            }
        }
        initialized = false
    }
}
