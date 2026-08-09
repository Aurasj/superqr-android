package com.superqr.android.ui.phase1

import android.graphics.Bitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

data class Phase1AnalysisPreview(
    val bitmap: Bitmap? = null,
    val geometry: Phase1FramingGeometry = Phase1FramingGeometry.empty(),
    val measuring: Boolean = false,
    val sequence: Long = 0,
)

/**
 * Bounded, throttled renderer for the exact normalized luma frame analyzed by
 * V7. The analyzer only performs one bounded byte copy; bitmap conversion runs
 * on a dedicated executor and is completely suppressed during RUNNING.
 */
class Phase1AnalysisPreviewPublisher(
    private val executor: ExecutorService,
    private val gate: Phase1PreviewGate = Phase1PreviewGate(),
) {
    private val measuring = AtomicBoolean(false)
    private var sequence = 0L

    fun setMeasuring(value: Boolean) {
        measuring.set(value)
        gate.setMeasuring(value)
    }

    fun offer(
        luma: ByteArray,
        width: Int,
        height: Int,
        geometry: Phase1FramingGeometry,
        nowNs: Long,
        deliver: (Phase1AnalysisPreview) -> Unit,
    ) {
        if (!gate.tryAcquire(nowNs)) return
        val copy = luma.copyOf(width * height)
        executor.execute {
            try {
                if (measuring.get()) return@execute
                val bitmap = renderLuma(copy, width, height)
                if (measuring.get()) {
                    bitmap.recycle()
                    return@execute
                }
                deliver(Phase1AnalysisPreview(bitmap, geometry, measuring = false, sequence = ++sequence))
            } finally {
                gate.release()
            }
        }
    }

    private fun renderLuma(luma: ByteArray, width: Int, height: Int): Bitmap {
        val scale = minOf(1.0, MAX_PREVIEW_EDGE.toDouble() / maxOf(width, height))
        val outputWidth = maxOf(1, (width * scale).toInt())
        val outputHeight = maxOf(1, (height * scale).toInt())
        val pixels = IntArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) {
            if (measuring.get()) break
            val sourceY = (y.toLong() * height / outputHeight).toInt().coerceAtMost(height - 1)
            val sourceRow = sourceY * width
            val outputRow = y * outputWidth
            for (x in 0 until outputWidth) {
                val sourceX = (x.toLong() * width / outputWidth).toInt().coerceAtMost(width - 1)
                val value = luma[sourceRow + sourceX].toInt() and 0xFF
                pixels[outputRow + x] = -0x1000000 or (value shl 16) or (value shl 8) or value
            }
        }
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.RGB_565)
    }

    private companion object {
        const val MAX_PREVIEW_EDGE = 720
    }
}
