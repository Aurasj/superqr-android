package com.superqr.android.camera

import androidx.camera.core.ImageProxy

/**
 * Reusable luma byte buffer for the camera analysis pipeline.
 */
class LumaFrameBuffer {
    var bytes: ByteArray = ByteArray(0)
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    private var rowBuffer: ByteArray = ByteArray(0)

    fun packFrom(imageProxy: ImageProxy): Boolean {
        val crop = imageProxy.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return false

        val plane = imageProxy.planes.firstOrNull() ?: return false
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return false

        val rotation = imageProxy.imageInfo.rotationDegrees
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(w, h, rotation)

        val required = nw * nh
        if (bytes.size != required) {
            bytes = ByteArray(required)
        }
        width = nw
        height = nh

        val requiredRowBuffer = w * pixelStride
        if (rowBuffer.size < requiredRowBuffer) {
            rowBuffer = ByteArray(requiredRowBuffer)
        }

        val buffer = plane.buffer.duplicate()

        for (ry in 0 until h) {
            val sourceRowStart = (crop.top + ry) * rowStride + crop.left * pixelStride
            val sourceRowEnd = sourceRowStart + (w - 1) * pixelStride
            if (sourceRowStart < 0 || sourceRowEnd >= buffer.limit()) return false

            buffer.position(sourceRowStart)
            buffer.get(rowBuffer, 0, w * pixelStride)

            for (rx in 0 until w) {
                val v = rowBuffer[rx * pixelStride]
                val (nx, ny) = FrameRotationHelper.mapRawToNormalized(rx, ry, w, h, rotation)
                bytes[ny * nw + nx] = v
            }
        }
        return true
    }
}
