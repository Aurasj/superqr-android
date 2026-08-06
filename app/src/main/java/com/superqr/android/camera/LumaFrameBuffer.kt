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

    fun packFrom(imageProxy: ImageProxy): Boolean {
        val crop = imageProxy.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return false

        val plane = imageProxy.planes.firstOrNull() ?: return false
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return false

        val required = w * h
        if (bytes.size != required) {
            bytes = ByteArray(required)
        }
        width = w
        height = h

        val buffer = plane.buffer.duplicate()

        for (row in 0 until h) {
            val sourceRowStart = (crop.top + row) * rowStride + crop.left * pixelStride
            val sourceRowEnd = sourceRowStart + (w - 1) * pixelStride
            if (sourceRowStart < 0 || sourceRowEnd >= buffer.limit()) return false
            val destinationStart = row * w

            if (pixelStride == 1) {
                val rowBuffer = buffer.duplicate()
                rowBuffer.position(sourceRowStart)
                rowBuffer.get(bytes, destinationStart, w)
            } else {
                for (column in 0 until w) {
                    bytes[destinationStart + column] =
                        buffer.get(sourceRowStart + column * pixelStride)
                }
            }
        }
        return true
    }
}
