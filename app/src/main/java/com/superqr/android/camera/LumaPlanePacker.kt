package com.superqr.android.camera

import java.nio.ByteBuffer

/** Allocation-free row-stride aware luma rotation into a reusable byte array. */
object LumaPlanePacker {
    fun normalizedWidth(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Int =
        if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth

    fun normalizedHeight(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Int =
        if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

    fun pack(
        source: ByteBuffer,
        cropLeft: Int,
        cropTop: Int,
        rawWidth: Int,
        rawHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
        rowBuffer: ByteArray,
        destination: ByteArray,
    ): Boolean {
        if (rawWidth <= 0 || rawHeight <= 0 || rowStride <= 0 || pixelStride <= 0) return false
        val normalizedWidth = normalizedWidth(rawWidth, rawHeight, rotationDegrees)
        val normalizedHeight = normalizedHeight(rawWidth, rawHeight, rotationDegrees)
        if (destination.size < normalizedWidth * normalizedHeight) return false
        if (rowBuffer.size < rawWidth * pixelStride) return false
        val input = source.duplicate()

        for (rawY in 0 until rawHeight) {
            val rowStart = (cropTop + rawY) * rowStride + cropLeft * pixelStride
            val rowEnd = rowStart + (rawWidth - 1) * pixelStride
            if (rowStart < 0 || rowEnd >= input.limit()) return false
            input.position(rowStart)

            if (rotationDegrees == 0 && pixelStride == 1) {
                input.get(destination, rawY * normalizedWidth, rawWidth)
                continue
            }

            input.get(rowBuffer, 0, rawWidth * pixelStride)
            when (rotationDegrees) {
                90 -> for (rawX in 0 until rawWidth) {
                    val normalizedX = rawHeight - 1 - rawY
                    val normalizedY = rawX
                    destination[normalizedY * normalizedWidth + normalizedX] = rowBuffer[rawX * pixelStride]
                }
                180 -> for (rawX in 0 until rawWidth) {
                    val normalizedX = rawWidth - 1 - rawX
                    val normalizedY = rawHeight - 1 - rawY
                    destination[normalizedY * normalizedWidth + normalizedX] = rowBuffer[rawX * pixelStride]
                }
                270 -> for (rawX in 0 until rawWidth) {
                    val normalizedX = rawY
                    val normalizedY = rawWidth - 1 - rawX
                    destination[normalizedY * normalizedWidth + normalizedX] = rowBuffer[rawX * pixelStride]
                }
                else -> for (rawX in 0 until rawWidth) {
                    destination[rawY * normalizedWidth + rawX] = rowBuffer[rawX * pixelStride]
                }
            }
        }
        return true
    }
}
