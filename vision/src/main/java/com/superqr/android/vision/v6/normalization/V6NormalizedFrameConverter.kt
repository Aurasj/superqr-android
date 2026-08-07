package com.superqr.android.vision.v6.normalization

import com.superqr.android.vision.v6.replay.V6RawYuvFrame
import kotlin.math.roundToInt

data class NormalizedRgbResult(
    val bgrBytes: ByteArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NormalizedRgbResult
        if (!bgrBytes.contentEquals(other.bgrBytes)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bgrBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

object V6NormalizedFrameConverter {

    /**
     * Converts raw YUV planes to a normalized RGB (BGR format) byte array matching the detector's
     * normalized coordinate space (taking into account cropRect, row/pixel strides, and rotationDegrees).
     */
    fun convertRawYuvToNormalizedBgr(frame: V6RawYuvFrame): NormalizedRgbResult {
        val cropW = frame.cropWidth
        val cropH = frame.cropHeight
        require(cropW > 0 && cropH > 0) { "Invalid crop dimensions: ${cropW}x${cropH}" }

        val rot = frame.rotationDegrees
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(cropW, cropH, rot)
        val bgrBytes = ByteArray(nw * nh * 3)

        for (ny in 0 until nh) {
            for (nx in 0 until nw) {
                val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, cropW, cropH, rot)
                val rawX = frame.cropLeft + rx
                val rawY = frame.cropTop + ry

                val yIdx = rawY * frame.yRowStride + rawX * frame.yPixelStride
                val uIdx = (rawY / 2) * frame.uRowStride + (rawX / 2) * frame.uPixelStride
                val vIdx = (rawY / 2) * frame.vRowStride + (rawX / 2) * frame.vPixelStride

                val yVal = if (yIdx in frame.yPlaneBytes.indices) frame.yPlaneBytes[yIdx].toInt() and 0xFF else 128
                val uVal = if (uIdx in frame.uPlaneBytes.indices) frame.uPlaneBytes[uIdx].toInt() and 0xFF else 128
                val vVal = if (vIdx in frame.vPlaneBytes.indices) frame.vPlaneBytes[vIdx].toInt() and 0xFF else 128

                val b = (yVal + 1.772 * (uVal - 128)).roundToInt().coerceIn(0, 255)
                val g = (yVal - 0.344136 * (uVal - 128) - 0.714136 * (vVal - 128)).roundToInt().coerceIn(0, 255)
                val r = (yVal + 1.402 * (vVal - 128)).roundToInt().coerceIn(0, 255)

                val destIdx = (ny * nw + nx) * 3
                bgrBytes[destIdx] = b.toByte()
                bgrBytes[destIdx + 1] = g.toByte()
                bgrBytes[destIdx + 2] = r.toByte()
            }
        }

        return NormalizedRgbResult(bgrBytes, nw, nh)
    }

    /**
     * Converts raw YUV planes to a raw-orientation RGB (BGR format) byte array matching raw sensor dimensions.
     */
    fun convertRawYuvToRawBgr(frame: V6RawYuvFrame): NormalizedRgbResult {
        val w = frame.imageWidth
        val h = frame.imageHeight
        val bgrBytes = ByteArray(w * h * 3)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val yIdx = y * frame.yRowStride + x * frame.yPixelStride
                val uIdx = (y / 2) * frame.uRowStride + (x / 2) * frame.uPixelStride
                val vIdx = (y / 2) * frame.vRowStride + (x / 2) * frame.vPixelStride

                val yVal = if (yIdx in frame.yPlaneBytes.indices) frame.yPlaneBytes[yIdx].toInt() and 0xFF else 128
                val uVal = if (uIdx in frame.uPlaneBytes.indices) frame.uPlaneBytes[uIdx].toInt() and 0xFF else 128
                val vVal = if (vIdx in frame.vPlaneBytes.indices) frame.vPlaneBytes[vIdx].toInt() and 0xFF else 128

                val b = (yVal + 1.772 * (uVal - 128)).roundToInt().coerceIn(0, 255)
                val g = (yVal - 0.344136 * (uVal - 128) - 0.714136 * (vVal - 128)).roundToInt().coerceIn(0, 255)
                val r = (yVal + 1.402 * (vVal - 128)).roundToInt().coerceIn(0, 255)

                val destIdx = (y * w + x) * 3
                bgrBytes[destIdx] = b.toByte()
                bgrBytes[destIdx + 1] = g.toByte()
                bgrBytes[destIdx + 2] = r.toByte()
            }
        }

        return NormalizedRgbResult(bgrBytes, w, h)
    }
}
