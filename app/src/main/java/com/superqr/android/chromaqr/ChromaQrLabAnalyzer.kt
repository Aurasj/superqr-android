package com.superqr.android.chromaqr

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max

/** One successfully decoded ChromaQR V40-L frame plus its color-channel quality. */
data class ChromaQrLabResult(
    val frameIndex: Int,
    val senderFps: Int,
    val moduleCount: Int,
    val observedBits: Int,
    val bitErrors: Int,
    val erasedBits: Int,
    val goodBits: Int,
    val ber: Double,
    val erasureRate: Double,
    val neutralU: Double,
    val neutralClusterDistance: Double,
    val chromaClusterDistance: Double,
    val threshold: Double,
    val rawCombinedKibS: Double,
    val qualityAdjustedKibS: Double,
    val analysisMs: Double,
)

/**
 * LAB-only ChromaQR decoder.
 *
 * The normal V40-L QR remains a valid luminance QR. Every one of its 177x177
 * modules additionally carries one bit in chroma:
 *   neutral color (black/white) = 0
 *   saturated color (blue/yellow) = 1
 *
 * The classifier uses only the camera U plane. Neutral U is estimated from the
 * frame median, then a two-cluster fit separates neutral from saturated modules.
 */
class ChromaQrLabAnalyzer {
    companion object {
        const val QR_MODULES = 177
        const val TOTAL_BITS = QR_MODULES * QR_MODULES
        const val BASE_USEFUL_BYTES = 2933
        private const val FRAME_BYTES = 2953
        private const val SEED_SALT = 0xC04A7A11.toInt()
        private val MARKER = byteArrayOf('C'.code.toByte(), 'Q'.code.toByte(), '4'.code.toByte(), 'B'.code.toByte())

        data class Header(val frameIndex: Int, val seed: Int, val senderFps: Int)

        internal fun parseHeader(payload: ByteArray): Header? {
            if (payload.size != FRAME_BYTES || payload.size < 36) return null
            if (payload[0] != 'S'.code.toByte() || payload[1] != 'Q'.code.toByte() ||
                payload[2] != 'P'.code.toByte() || payload[3] != '1'.code.toByte()) return null
            if ((payload[4].toInt() and 0xFF) != 40 || (payload[5].toInt() and 0xFF) != 1) return null
            for (index in MARKER.indices) if (payload[26 + index] != MARKER[index]) return null
            if ((payload[30].toInt() and 0xFF) != 1 || (payload[31].toInt() and 0xFF) != 1) return null
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if ((buffer.getShort(32).toInt() and 0xFFFF) != QR_MODULES) return null
            val expectedCrc = buffer.getInt(payload.size - 4).toLong() and 0xFFFFFFFFL
            val actualCrc = CRC32().apply { update(payload, 0, payload.size - 4) }.value and 0xFFFFFFFFL
            if (actualCrc != expectedCrc) return null
            val frameIndex = buffer.getInt(6)
            if (frameIndex !in 0..255) return null
            val senderFps = payload[34].toInt() and 0xFF
            if (senderFps !in 1..60) return null
            return Header(frameIndex, buffer.getInt(10), senderFps)
        }

        internal fun expectedBits(seed: Int, frameIndex: Int): ByteArray {
            var state = seed xor (frameIndex * 0x9E3779B1.toInt()) xor SEED_SALT
            if (state == 0) state = 1
            val out = ByteArray(TOTAL_BITS)
            for (index in out.indices) {
                var x = state
                x = x xor (x shl 13)
                x = x xor (x ushr 17)
                x = x xor (x shl 5)
                state = x
                out[index] = (x and 1).toByte()
            }
            return out
        }
    }

    private val samplesU = IntArray(TOTAL_BITS)
    private val sortedU = IntArray(TOTAL_BITS)
    private val uv = IntArray(2)
    private val expected = ByteArray(TOTAL_BITS)

    fun analyze(
        payload: ByteArray,
        quad: List<DoubleArray>,
        chroma: ChromaPixelReader,
    ): ChromaQrLabResult? {
        val header = parseHeader(payload) ?: return null
        if (quad.size != 4 || quad.any { it.size < 2 }) return null
        val started = System.nanoTime()
        samplesU.fill(-1)
        fillExpected(header.seed, header.frameIndex)
        val projection = SquareToQuad.from(quad) ?: return null

        var valid = 0
        for (index in 0 until TOTAL_BITS) {
            val row = index / QR_MODULES
            val col = index - row * QR_MODULES
            val u = (col + 0.5) / QR_MODULES.toDouble()
            val v = (row + 0.5) / QR_MODULES.toDouble()
            val point = projection.map(u, v) ?: continue
            if (chroma.read(point.first, point.second, uv)) {
                val sample = uv[0].coerceIn(0, 255)
                samplesU[index] = sample
                sortedU[valid++] = sample
            }
        }
        if (valid < TOTAL_BITS / 2) return null

        Arrays.sort(sortedU, 0, valid)
        val neutralU = sortedU[valid / 2].toDouble()
        var c0 = Double.POSITIVE_INFINITY
        var c1 = Double.NEGATIVE_INFINITY
        for (index in samplesU.indices) {
            val sample = samplesU[index]
            if (sample < 0) continue
            val distance = abs(sample - neutralU)
            if (distance < c0) c0 = distance
            if (distance > c1) c1 = distance
        }
        if (!c0.isFinite() || !c1.isFinite() || c1 - c0 < 4.0) return null

        repeat(7) {
            var sum0 = 0.0
            var sum1 = 0.0
            var n0 = 0
            var n1 = 0
            for (sample in samplesU) {
                if (sample < 0) continue
                val d = abs(sample - neutralU)
                if (abs(d - c0) <= abs(d - c1)) {
                    sum0 += d; n0++
                } else {
                    sum1 += d; n1++
                }
            }
            if (n0 > 0) c0 = sum0 / n0
            if (n1 > 0) c1 = sum1 / n1
        }
        if (c0 > c1) {
            val swap = c0; c0 = c1; c1 = swap
        }
        val threshold = (c0 + c1) * 0.5
        val ambiguity = max(3.0, (c1 - c0) * 0.08)

        var errors = 0
        var erasures = TOTAL_BITS - valid
        for (index in samplesU.indices) {
            val sample = samplesU[index]
            if (sample < 0) continue
            val distance = abs(sample - neutralU)
            if (abs(distance - threshold) <= ambiguity) {
                erasures++
                continue
            }
            val observed = if (distance > threshold) 1 else 0
            if (observed != expected[index].toInt()) errors++
        }

        val classified = (TOTAL_BITS - erasures).coerceAtLeast(1)
        val good = (TOTAL_BITS - erasures - errors).coerceAtLeast(0)
        val ber = errors.toDouble() / classified
        val erasureRate = erasures.toDouble() / TOTAL_BITS
        val rawBytesPerFrame = BASE_USEFUL_BYTES + TOTAL_BITS / 8.0
        val adjustedBytesPerFrame = BASE_USEFUL_BYTES + good / 8.0
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        return ChromaQrLabResult(
            frameIndex = header.frameIndex,
            senderFps = header.senderFps,
            moduleCount = QR_MODULES,
            observedBits = valid,
            bitErrors = errors,
            erasedBits = erasures,
            goodBits = good,
            ber = ber,
            erasureRate = erasureRate,
            neutralU = neutralU,
            neutralClusterDistance = c0,
            chromaClusterDistance = c1,
            threshold = threshold,
            rawCombinedKibS = rawBytesPerFrame * header.senderFps / 1024.0,
            qualityAdjustedKibS = adjustedBytesPerFrame * header.senderFps / 1024.0,
            analysisMs = elapsedMs,
        )
    }

    private fun fillExpected(seed: Int, frameIndex: Int) {
        var state = seed xor (frameIndex * 0x9E3779B1.toInt()) xor SEED_SALT
        if (state == 0) state = 1
        for (index in expected.indices) {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            expected[index] = (x and 1).toByte()
        }
    }

    private data class SquareToQuad(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double,
        val g: Double, val h: Double,
    ) {
        fun map(u: Double, v: Double): Pair<Double, Double>? {
            val denominator = g * u + h * v + 1.0
            if (!denominator.isFinite() || abs(denominator) < 1e-9) return null
            val x = (a * u + b * v + c) / denominator
            val y = (d * u + e * v + f) / denominator
            return if (x.isFinite() && y.isFinite()) x to y else null
        }

        companion object {
            fun from(quad: List<DoubleArray>): SquareToQuad? {
                val p0 = quad[0]; val p1 = quad[1]; val p2 = quad[2]; val p3 = quad[3]
                val dx1 = p1[0] - p2[0]
                val dx2 = p3[0] - p2[0]
                val dx3 = p0[0] - p1[0] + p2[0] - p3[0]
                val dy1 = p1[1] - p2[1]
                val dy2 = p3[1] - p2[1]
                val dy3 = p0[1] - p1[1] + p2[1] - p3[1]
                val denominator = dx1 * dy2 - dx2 * dy1
                val g: Double
                val h: Double
                if (abs(dx3) < 1e-9 && abs(dy3) < 1e-9) {
                    g = 0.0; h = 0.0
                } else {
                    if (abs(denominator) < 1e-9) return null
                    g = (dx3 * dy2 - dx2 * dy3) / denominator
                    h = (dx1 * dy3 - dx3 * dy1) / denominator
                }
                return SquareToQuad(
                    a = p1[0] - p0[0] + g * p1[0],
                    b = p3[0] - p0[0] + h * p3[0],
                    c = p0[0],
                    d = p1[1] - p0[1] + g * p1[1],
                    e = p3[1] - p0[1] + h * p3[1],
                    f = p0[1],
                    g = g,
                    h = h,
                )
            }
        }
    }
}
