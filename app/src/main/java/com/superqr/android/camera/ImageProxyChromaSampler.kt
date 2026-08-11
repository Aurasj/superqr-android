package com.superqr.android.camera

import androidx.camera.core.ImageProxy
import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v7_capacity_lab.ExternalQrDecodeResult
import com.superqr.android.vision.v7_capacity_lab.ExternalQrFrameDecoder
import kotlin.math.floor
import kotlin.math.roundToInt

class ChromaSampleBuffers {
    val centerU = IntArray(16)
    val centerV = IntArray(16)
    val quietZoneU = IntArray(32)
    val quietZoneV = IntArray(32)
}

/**
 * CameraX frame-scoped sampler/capability object.
 *
 * Chroma reads remain allocation-free. The optional QR capability is bound to the
 * same ImageProxy and is invoked only when VisionEngine is on its QR path, before
 * CameraManager closes that image.
 */
class ImageProxyChromaSampler(
    @Suppress("UNUSED_PARAMETER") buffers: ChromaSampleBuffers,
) : ChromaPixelReader, ExternalQrFrameDecoder {
    private var cropLeft = 0
    private var cropTop = 0
    private var rawWidth = 0
    private var rawHeight = 0
    private var rotation = 0
    private val uPlane = PlaneReader()
    private val vPlane = PlaneReader()
    private var qrDecoder: CameraQrDecoder? = null

    constructor(imageProxy: ImageProxy, buffers: ChromaSampleBuffers) : this(buffers) {
        bind(imageProxy)
    }

    /** Rebind this reader to the current ImageProxy without allocating readers or ByteBuffer duplicates. */
    fun bind(imageProxy: ImageProxy): ImageProxyChromaSampler {
        cropLeft = imageProxy.cropRect.left
        cropTop = imageProxy.cropRect.top
        rawWidth = imageProxy.cropRect.width()
        rawHeight = imageProxy.cropRect.height()
        rotation = imageProxy.imageInfo.rotationDegrees
        uPlane.bind(imageProxy.planes[1])
        vPlane.bind(imageProxy.planes[2])
        qrDecoder = null
        return this
    }

    fun bindQrDecoder(decoder: CameraQrDecoder): ImageProxyChromaSampler {
        qrDecoder = decoder
        return this
    }

    override fun decodeQr(): ExternalQrDecodeResult {
        val result = qrDecoder?.decode()
            ?: return ExternalQrDecodeResult(
                payload = ByteArray(0),
                elapsedMs = 0.0,
                resultCount = 0,
                source = "ZXING_CPP",
                errorType = "UNBOUND",
            )
        return ExternalQrDecodeResult(
            payload = result.payload,
            elapsedMs = result.elapsedMs,
            resultCount = result.resultCount,
            source = "ZXING_CPP",
            quad = result.quad?.map { point -> point.copyOf() },
            errorType = result.errorType,
            errorMessage = result.errorMessage,
        )
    }

    override fun read(
        imageX: Double,
        imageY: Double,
        destination: IntArray,
    ): Boolean {
        require(destination.size >= 2)
        if (!imageX.isFinite() || !imageY.isFinite()) return false

        val rawX: Double
        val rawY: Double
        when (rotation) {
            90 -> {
                rawX = imageY
                rawY = rawHeight - 1.0 - imageX
            }
            180 -> {
                rawX = rawWidth - 1.0 - imageX
                rawY = rawHeight - 1.0 - imageY
            }
            270 -> {
                rawX = rawWidth - 1.0 - imageY
                rawY = imageX
            }
            else -> {
                rawX = imageX
                rawY = imageY
            }
        }

        val fullX = cropLeft.toDouble() + rawX
        val fullY = cropTop.toDouble() + rawY
        if (fullX < 0.0 || fullY < 0.0) return false

        // Chroma plane coordinates are half-resolution. Keeping fractional
        // positions lets odd luma coordinates blend adjacent chroma samples rather
        // than snapping to one arbitrary 2x2 block.
        val chromaX = fullX * 0.5
        val chromaY = fullY * 0.5
        val u = uPlane.sampleBilinear(chromaX, chromaY) ?: return false
        val v = vPlane.sampleBilinear(chromaX, chromaY) ?: return false
        destination[0] = u
        destination[1] = v
        return true
    }

    private class PlaneReader {
        private lateinit var buffer: java.nio.ByteBuffer
        private var start = 0
        private var rowStride = 0
        private var pixelStride = 0

        fun bind(plane: ImageProxy.PlaneProxy) {
            buffer = plane.buffer
            start = buffer.position()
            rowStride = plane.rowStride
            pixelStride = plane.pixelStride
        }

        fun sampleBilinear(x: Double, y: Double): Int? {
            if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0) return null

            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0

            val v00 = get(x0, y0) ?: return null
            val v10 = get(x0 + 1, y0)
            val v01 = get(x0, y0 + 1)
            val v11 = get(x0 + 1, y0 + 1)

            // At the final chroma row/column a neighbor can legitimately be
            // outside the plane. Fall back to the nearest valid sample there.
            if (v10 == null || v01 == null || v11 == null) return v00

            val top = v00 * (1.0 - fx) + v10 * fx
            val bottom = v01 * (1.0 - fx) + v11 * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }

        private fun get(column: Int, row: Int): Int? {
            if (column < 0 || row < 0) return null
            val index = start + row * rowStride + column * pixelStride
            if (index !in start until buffer.limit()) return null
            return buffer.get(index).toInt() and 0xFF
        }
    }
}
