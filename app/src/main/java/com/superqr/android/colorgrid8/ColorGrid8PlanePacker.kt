package com.superqr.android.colorgrid8

import java.nio.ByteBuffer

/**
 * LAB-only row/pixel-stride aware plane packer.
 *
 * Unlike the older shared luma helper, this implementation deliberately follows
 * android.media.Image plane semantics needed by ColorGrid8:
 * - the current ByteBuffer position is the plane base,
 * - crop offsets are relative to that base,
 * - for pixelStride > 1 the final row only requires bytes through the last sample,
 *   not an extra trailing stride that some producers do not expose.
 *
 * The destination is always a dense, rotation-normalized one-byte-per-sample plane.
 */
object ColorGrid8PlanePacker {
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
        if (cropLeft < 0 || cropTop < 0) return false
        val normalizedWidth = normalizedWidth(rawWidth, rawHeight, rotationDegrees)
        val normalizedHeight = normalizedHeight(rawWidth, rawHeight, rotationDegrees)
        if (destination.size < normalizedWidth * normalizedHeight) return false

        val bytesPerRow = (rawWidth - 1) * pixelStride + 1
        if (rowBuffer.size < bytesPerRow) return false

        val input = source.duplicate()
        val base = input.position()
        val limit = input.limit()

        for (rawY in 0 until rawHeight) {
            val rowStart = base + (cropTop + rawY) * rowStride + cropLeft * pixelStride
            val rowExclusiveEnd = rowStart + bytesPerRow
            if (rowStart < base || rowExclusiveEnd > limit) return false
            input.position(rowStart)

            if (rotationDegrees == 0 && pixelStride == 1) {
                input.get(destination, rawY * normalizedWidth, rawWidth)
                continue
            }

            input.get(rowBuffer, 0, bytesPerRow)
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
