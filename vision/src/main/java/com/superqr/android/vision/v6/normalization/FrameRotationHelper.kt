package com.superqr.android.vision.v6.normalization

object FrameRotationHelper {

    fun getNormalizedDimensions(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        return if (rotationDegrees == 90 || rotationDegrees == 270) {
            Pair(rawHeight, rawWidth)
        } else {
            Pair(rawWidth, rawHeight)
        }
    }

    fun mapRawToNormalized(
        rx: Int,
        ry: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        return when (rotationDegrees) {
            90 -> Pair(rawHeight - 1 - ry, rx)
            180 -> Pair(rawWidth - 1 - rx, rawHeight - 1 - ry)
            270 -> Pair(ry, rawWidth - 1 - rx)
            else -> Pair(rx, ry)
        }
    }

    fun mapNormalizedToRaw(
        nx: Int,
        ny: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        return when (rotationDegrees) {
            90 -> Pair(ny, rawHeight - 1 - nx)
            180 -> Pair(rawWidth - 1 - nx, rawHeight - 1 - ny)
            270 -> Pair(rawWidth - 1 - ny, nx)
            else -> Pair(nx, ny)
        }
    }
}
