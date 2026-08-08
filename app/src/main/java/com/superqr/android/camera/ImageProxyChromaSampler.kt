package com.superqr.android.camera

import androidx.camera.core.ImageProxy
import com.superqr.android.vision.v6.classification.ChromaPixelReader
import kotlin.math.floor
import kotlin.math.roundToInt

class ChromaSampleBuffers {
    val centerU = IntArray(16)
    val centerV = IntArray(16)
    val quietZoneU = IntArray(32)
    val quietZoneV = IntArray(32)
}

/**
 * Chroma reader for CameraX YUV_420_888 analysis frames.
 *
 * Luma is full resolution while U/V are normally one sample per 2x2 luma block.
 * We preserve sub-pixel coordinates through rotation and bilinearly interpolate
 * surrounding chroma samples. The hot read() path is allocation-free; CROSS_5 can
 * call it thousands of times per frame without creating Pair objects for rotation.
 */
class ImageProxyChromaSampler(
    imageProxy: ImageProxy,
    @Suppress("UNUSED_PARAMETER") buffers: ChromaSampleBuffers,
) : ChromaPixelReader {
    private val cropLeft = imageProxy.cropRect.left
    private val cropTop = imageProxy.cropRect.top
    private val rawWidth = imageProxy.cropRect.width()
    private val rawHeight = imageProxy.cropRect.height()
    private val rotation = imageProxy.imageInfo.rotationDegrees
    private val uPlane = PlaneReader(imageProxy.planes[1])
    private val vPlane = PlaneReader(imageProxy.planes[2])

    override fun read(
        imageX: Double,
        imageY: Double,
        destination: IntArray,
    ): Boolean {
        require(destination.size >= 2)
        if (!imageX.isFinite() || !imageY.isFinite()) return false

        val rawX: Double
        val rawY: Double
        when (rotation) {
            90 -> {
                rawX = imageY
                rawY = rawHeight - 1.0 - imageX
            }
            180 -> {
                rawX = rawWidth - 1.0 - imageX
                rawY = rawHeight - 1.0 - imageY
            }
            270 -> {
                rawX = rawWidth - 1.0 - imageY
                rawY = imageX
            }
            else -> {
                rawX = imageX
                rawY = imageY
            }
        }

        val fullX = cropLeft.toDouble() + rawX
        val fullY = cropTop.toDouble() + rawY
        if (fullX < 0.0 || fullY < 0.0) return false

        // Chroma plane coordinates are half-resolution. Keeping fractional
        // positions lets odd luma coordinates blend adjacent chroma samples rather
        // than snapping to one arbitrary 2x2 block.
        val chromaX = fullX * 0.5
        val chromaY = fullY * 0.5
        val u = uPlane.sampleBilinear(chromaX, chromaY) ?: return false
        val v = vPlane.sampleBilinear(chromaX, chromaY) ?: return false
        destination[0] = u
        destination[1] = v
        return true
    }

    private class PlaneReader(plane: ImageProxy.PlaneProxy) {
        private val buffer = plane.buffer.duplicate()
        private val start = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        fun sampleBilinear(x: Double, y: Double): Int? {
            if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0) return null

            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0

            val v00 = get(x0, y0) ?: return null
            val v10 = get(x0 + 1, y0)
            val v01 = get(x0, y0 + 1)
            val v11 = get(x0 + 1, y0 + 1)

            // At the final chroma row/column a neighbor can legitimately be
            // outside the plane. Fall back to the nearest valid sample there.
            if (v10 == null || v01 == null || v11 == null) return v00

            val top = v00 * (1.0 - fx) + v10 * fx
            val bottom = v01 * (1.0 - fx) + v11 * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }

        private fun get(column: Int, row: Int): Int? {
            if (column < 0 || row < 0) return null
            val index = start + row * rowStride + column * pixelStride
            if (index !in start until buffer.limit()) return null
            return buffer.get(index).toInt() and 0xFF
        }
    }
}
