package com.superqr.android.camera

import com.superqr.android.vision.v6.normalization.FrameRotationHelper as VisionFrameRotationHelper

object FrameRotationHelper {

    fun getNormalizedDimensions(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        return VisionFrameRotationHelper.getNormalizedDimensions(rawWidth, rawHeight, rotationDegrees)
    }

    fun mapRawToNormalized(
        rx: Int,
        ry: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        return VisionFrameRotationHelper.mapRawToNormalized(rx, ry, rawWidth, rawHeight, rotationDegrees)
    }

    fun mapNormalizedToRaw(
        nx: Int,
        ny: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        return VisionFrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rotationDegrees)
    }
}
