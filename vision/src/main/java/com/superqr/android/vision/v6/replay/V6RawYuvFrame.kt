package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.normalization.FrameRotationHelper

data class V6RawYuvFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val rotationDegrees: Int,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vRowStride: Int,
    val vPixelStride: Int,
    val yPlaneBytes: ByteArray,
    val uPlaneBytes: ByteArray,
    val vPlaneBytes: ByteArray,
    val timestamp: Long = 0L,
    val patternName: String? = null,
    val transportSessionId: Int? = null,
    val transportFrameId: Int? = null,
    val transportTotalFrames: Int? = null,
    val transportPayloadHex: String? = null
) {
    val cropWidth: Int
        get() = cropRight - cropLeft

    val cropHeight: Int
        get() = cropBottom - cropTop

    fun getNormalizedDimensions(): Pair<Int, Int> {
        return FrameRotationHelper.getNormalizedDimensions(cropWidth, cropHeight, rotationDegrees)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as V6RawYuvFrame
        return imageWidth == other.imageWidth &&
                imageHeight == other.imageHeight &&
                cropLeft == other.cropLeft &&
                cropTop == other.cropTop &&
                cropRight == other.cropRight &&
                cropBottom == other.cropBottom &&
                rotationDegrees == other.rotationDegrees &&
                yRowStride == other.yRowStride &&
                yPixelStride == other.yPixelStride &&
                uRowStride == other.uRowStride &&
                uPixelStride == other.uPixelStride &&
                vRowStride == other.vRowStride &&
                vPixelStride == other.vPixelStride &&
                yPlaneBytes.contentEquals(other.yPlaneBytes) &&
                uPlaneBytes.contentEquals(other.uPlaneBytes) &&
                vPlaneBytes.contentEquals(other.vPlaneBytes)
    }

    override fun hashCode(): Int {
        var result = imageWidth
        result = 31 * result + imageHeight
        result = 31 * result + cropLeft
        result = 31 * result + cropTop
        result = 31 * result + cropRight
        result = 31 * result + cropBottom
        result = 31 * result + rotationDegrees
        result = 31 * result + yRowStride
        result = 31 * result + yPixelStride
        result = 31 * result + uRowStride
        result = 31 * result + uPixelStride
        result = 31 * result + vRowStride
        result = 31 * result + vPixelStride
        result = 31 * result + yPlaneBytes.contentHashCode()
        result = 31 * result + uPlaneBytes.contentHashCode()
        result = 31 * result + vPlaneBytes.contentHashCode()
        return result
    }
}
