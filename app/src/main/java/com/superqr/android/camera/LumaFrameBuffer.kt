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
        val nw = LumaPlanePacker.normalizedWidth(w, h, rotation)
        val nh = LumaPlanePacker.normalizedHeight(w, h, rotation)

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

        return LumaPlanePacker.pack(
            source = plane.buffer,
            cropLeft = crop.left,
            cropTop = crop.top,
            rawWidth = w,
            rawHeight = h,
            rowStride = rowStride,
            pixelStride = pixelStride,
            rotationDegrees = rotation,
            rowBuffer = rowBuffer,
            destination = bytes,
        )
    }
}
