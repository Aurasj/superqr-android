package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.normalization.FrameRotationHelper

data class V6PackedLuma(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
)

object V6ReplayLumaPacker {

    fun pack(frame: V6RawYuvFrame): V6PackedLuma {
        val w = frame.cropWidth
        val h = frame.cropHeight
        require(w > 0 && h > 0) { "Crop dimensions must be positive: ${w}x${h}" }

        val rotation = frame.rotationDegrees
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(w, h, rotation)
        val lumaBytes = ByteArray(nw * nh)

        for (ry in 0 until h) {
            val sourceRowStart = (frame.cropTop + ry) * frame.yRowStride + frame.cropLeft * frame.yPixelStride
            for (rx in 0 until w) {
                val index = sourceRowStart + rx * frame.yPixelStride
                val v = frame.yPlaneBytes[index]
                val (nx, ny) = FrameRotationHelper.mapRawToNormalized(rx, ry, w, h, rotation)
                lumaBytes[ny * nw + nx] = v
            }
        }
        return V6PackedLuma(lumaBytes, nw, nh)
    }
}
