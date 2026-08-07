package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.normalization.FrameRotationHelper

class V6ReplayChromaSampler(
    private val frame: V6RawYuvFrame
) : ChromaPixelReader {
    private val cropLeft = frame.cropLeft
    private val cropTop = frame.cropTop
    private val rawWidth = frame.cropWidth
    private val rawHeight = frame.cropHeight
    private val rotation = frame.rotationDegrees

    override fun read(
        imageX: Double,
        imageY: Double,
        destination: IntArray
    ): Boolean {
        require(destination.size >= 2)
        val nx = imageX.toInt()
        val ny = imageY.toInt()

        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rotation)

        val chromaX = (cropLeft + rx) / 2
        val chromaY = (cropTop + ry) / 2

        val uIndex = chromaY * frame.uRowStride + chromaX * frame.uPixelStride
        val vIndex = chromaY * frame.vRowStride + chromaX * frame.vPixelStride

        if (uIndex < 0 || uIndex >= frame.uPlaneBytes.size || vIndex < 0 || vIndex >= frame.vPlaneBytes.size) {
            return false
        }

        destination[0] = frame.uPlaneBytes[uIndex].toInt() and 0xFF
        destination[1] = frame.vPlaneBytes[vIndex].toInt() and 0xFF
        return true
    }
}
