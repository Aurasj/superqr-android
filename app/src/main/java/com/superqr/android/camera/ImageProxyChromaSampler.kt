package com.superqr.android.camera

import androidx.camera.core.ImageProxy
import com.superqr.android.vision.v6.classification.ChromaPixelReader

class ChromaSampleBuffers {
    val centerU = IntArray(16)
    val centerV = IntArray(16)
    val quietZoneU = IntArray(32)
    val quietZoneV = IntArray(32)
}

class ImageProxyChromaSampler(
    imageProxy: ImageProxy,
    private val buffers: ChromaSampleBuffers,
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
        val nx = imageX.toInt()
        val ny = imageY.toInt()

        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rotation)

        val chromaX = (cropLeft + rx) / 2
        val chromaY = (cropTop + ry) / 2
        val u = uPlane.get(chromaX, chromaY) ?: return false
        val v = vPlane.get(chromaX, chromaY) ?: return false
        destination[0] = u
        destination[1] = v
        return true
    }

    private class PlaneReader(plane: ImageProxy.PlaneProxy) {
        private val buffer = plane.buffer.duplicate()
        private val start = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        fun get(column: Int, row: Int): Int? {
            if (column < 0 || row < 0) {
                return null
            }

            val index = start + row * rowStride + column * pixelStride

            if (index !in start until buffer.limit()) {
                return null
            }

            return buffer.get(index).toInt() and 0xFF
        }
    }
}
