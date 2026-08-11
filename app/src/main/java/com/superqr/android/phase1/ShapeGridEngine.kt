package com.superqr.android.phase1

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquirer
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt


data class ShapeGridBlockObservation(
    val blockId: Int,
    val frameIndex: Int,
    val usefulBytes: Int,
    val symbolErasures: Int,
    val shapeSymbolErrors: Int,
    val colorSymbolErrors: Int,
    val rsErrors: Int,
    val rsErasures: Int,
    val postFecValid: Boolean,
    val failure: String? = null,
)

data class ShapeGridResult(
    val profile: ShapeGridProfile,
    val envelope: V7LabRunEnvelope,
    val observations: List<ShapeGridBlockObservation>,
    val recoveredBlocks: Int,
    val projectedTilePitchPx: Double,
    val shapegridTotalMs: Double,
    val carrierSource: String,
    val failure: String? = null,
)

private data class ShapeGridPalette(
    val colors: Array<IntArray>, // [color bits][Y,U,V]
    val backgroundY: Int,
)

/**
 * Lab-only ShapeGrid receiver. One shared carrier acquisition feeds eight
 * independently decoded blocks. Expected lab data is never used to decide
 * validity; RS + embedded envelope + CRC32 are authoritative.
 */
class ShapeGridEngine(
    private val manifest: ShapeGridManifest,
    carrierSpec: V7CarrierSpec = V7CarrierSpec(),
) : AutoCloseable {
    private val acquirer = V7CarrierAcquirer(carrierSpec)
    private val projected = DoubleArray(2)
    private val yuv = IntArray(3)
    private val colorAccumulator = IntArray(3)
    private val guardSamples = IntArray(96)
    private val foregroundPositions = Array(16) { shape ->
        IntArray(25) { it }.filter { position ->
            val bit = 24 - position
            ((manifest.glyphMasks[shape] ushr bit) and 1) != 0
        }.toIntArray()
    }

    @Synchronized
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader,
    ): ShapeGridResult? {
        val started = System.nanoTime()
        val acquisition = acquirer.analyze(luma, width, height, diagnostics = false)
        val h = acquisition.canonicalToImageHomography ?: return null
        val envelope = acquisition.sync.envelope ?: return null
        val profile = manifest.profile(envelope.profileId) ?: return null
        val palette = calibratePalette(profile, h, luma, width, height, chromaReader)
            ?: return ShapeGridResult(
                profile, envelope, emptyList(), 0,
                projectedTilePitch(profile, h), elapsedMs(started), acquisition.source,
                failure = "SHAPEGRID_PALETTE_UNREADABLE",
            )

        val gridSymbols = sampleGrid(
            profile, h, luma, width, height, chromaReader, palette
        )
        val observations = ArrayList<ShapeGridBlockObservation>(8)
        var recovered = 0
        for (blockId in 0 until 8) {
            val physical = extractBlock(profile, gridSymbols, blockId)
            val decoded = ShapeGridBlockCodec.decode(profile, blockId, envelope, physical)
            if (decoded.valid) recovered++
            observations += ShapeGridBlockObservation(
                blockId = blockId,
                frameIndex = envelope.frameIndex,
                usefulBytes = if (decoded.valid) decoded.payload.size else 0,
                symbolErasures = decoded.symbolErasures,
                shapeSymbolErrors = decoded.shapeSymbolErrors,
                colorSymbolErrors = decoded.colorSymbolErrors,
                rsErrors = decoded.rsErrors,
                rsErasures = decoded.rsErasures,
                postFecValid = decoded.valid,
                failure = decoded.failure,
            )
        }
        return ShapeGridResult(
            profile = profile,
            envelope = envelope,
            observations = observations,
            recoveredBlocks = recovered,
            projectedTilePitchPx = projectedTilePitch(profile, h),
            shapegridTotalMs = elapsedMs(started),
            carrierSource = acquisition.source,
            failure = if (recovered == 0) "SHAPEGRID_NO_VALID_BLOCKS" else null,
        )
    }

    private fun sampleGrid(
        profile: ShapeGridProfile,
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader,
        palette: ShapeGridPalette,
    ): IntArray {
        val cols = profile.gridCols
        val rows = profile.gridRows
        val bbox = profile.canonicalGridBbox
        val pitch = (bbox[2] - bbox[0]) / cols
        val sub = pitch / 6.0
        val symbols = IntArray(cols * rows) { -1 }
        for (row in 0 until rows) {
            val tileY = bbox[1] + row * pitch
            for (col in 0 until cols) {
                val tileX = bbox[0] + col * pitch
                var unreadable = 0
                var ambiguous = 0
                var observedMask = 0
                for (sy in 0 until 5) {
                    val cy = tileY + (sy + 0.5) * sub
                    for (sx in 0 until 5) {
                        val cx = tileX + (sx + 0.5) * sub
                        val y = sampleLuma(h, cx, cy, luma, width, height)
                        observedMask = observedMask shl 1
                        if (y < 0) {
                            unreadable++
                        } else {
                            val backgroundDistance = abs(y - palette.backgroundY)
                            var foregroundDistance = Int.MAX_VALUE
                            for (color in palette.colors) {
                                foregroundDistance = minOf(foregroundDistance, abs(y - color[0]))
                            }
                            if (foregroundDistance + LUMA_CLASS_MARGIN < backgroundDistance) {
                                observedMask = observedMask or 1
                            } else if (backgroundDistance + LUMA_CLASS_MARGIN >= foregroundDistance) {
                                // Ambiguous points still choose their nearest class for Hamming matching,
                                // but too many ambiguous subcells turn the whole tile into an erasure.
                                ambiguous++
                                if (foregroundDistance < backgroundDistance) observedMask = observedMask or 1
                            }
                        }
                    }
                }
                if (unreadable > MAX_UNREADABLE_SUBCELLS || ambiguous > MAX_AMBIGUOUS_SUBCELLS) continue
                val shape = classifyShape(observedMask) ?: continue
                val colorBits = classifyColor(
                    shape, tileX, tileY, sub, h, chromaReader, palette
                ) ?: continue
                symbols[row * cols + col] = (shape shl 2) or colorBits
            }
        }
        return symbols
    }

    private fun classifyShape(observedMask: Int): Int? {
        var bestId = -1
        var bestDistance = Int.MAX_VALUE
        var secondDistance = Int.MAX_VALUE
        for (shape in 0 until 16) {
            val distance = Integer.bitCount(observedMask xor manifest.glyphMasks[shape])
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                bestId = shape
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        if (bestDistance > MAX_SHAPE_HAMMING) return null
        if (secondDistance - bestDistance < MIN_SHAPE_MARGIN) return null
        return bestId
    }

    private fun classifyColor(
        shape: Int,
        tileX: Double,
        tileY: Double,
        sub: Double,
        h: DoubleArray,
        reader: ChromaPixelReader,
        palette: ShapeGridPalette,
    ): Int? {
        val positions = foregroundPositions[shape]
        if (positions.isEmpty()) return null
        colorAccumulator.fill(0)
        var count = 0
        val wanted = minOf(COLOR_SAMPLES_PER_TILE, positions.size)
        for (sampleIndex in 0 until wanted) {
            val pick = if (wanted == 1) 0 else sampleIndex * (positions.size - 1) / (wanted - 1)
            val position = positions[pick]
            val sx = position % 5
            val sy = position / 5
            val canonicalX = tileX + (sx + 0.5) * sub
            val canonicalY = tileY + (sy + 0.5) * sub
            if (!map(h, canonicalX, canonicalY, projected)) continue
            if (!reader.read(projected[0], projected[1], yuv)) continue
            colorAccumulator[0] += yuv[0]
            colorAccumulator[1] += yuv[1]
            colorAccumulator[2] += yuv[2]
            count++
        }
        if (count < MIN_COLOR_SAMPLES) return null
        val observed = intArrayOf(
            colorAccumulator[0] / count,
            colorAccumulator[1] / count,
            colorAccumulator[2] / count,
        )
        var best = -1
        var bestDistance = Double.POSITIVE_INFINITY
        var secondDistance = Double.POSITIVE_INFINITY
        for (colorBits in 0 until 4) {
            val distance = colorDistance(observed, palette.colors[colorBits])
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                best = colorBits
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        if (!bestDistance.isFinite() || !secondDistance.isFinite()) return null
        if (secondDistance < bestDistance * MIN_COLOR_DISTANCE_RATIO + MIN_COLOR_DISTANCE_MARGIN) return null
        return best
    }

    private fun calibratePalette(
        profile: ShapeGridProfile,
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        reader: ChromaPixelReader,
    ): ShapeGridPalette? {
        val colors = Array(4) { IntArray(3) }
        for (colorBits in 0 until 4) {
            val pilot = manifest.pilotsByColorBits[colorBits]
            if (!map(h, pilot.canonicalX, pilot.canonicalY, projected)) return null
            if (!reader.read(projected[0], projected[1], yuv)) return null
            colors[colorBits] = yuv.copyOf()
        }
        var count = 0
        val bbox = profile.canonicalGridBbox
        val pitch = (bbox[2] - bbox[0]) / profile.gridCols
        val sub = pitch / 6.0
        val rowStep = maxOf(1, profile.gridRows / 8)
        val colStep = maxOf(1, profile.gridCols / 10)
        loop@ for (row in 0 until profile.gridRows step rowStep) {
            for (col in 0 until profile.gridCols step colStep) {
                val x = bbox[0] + col * pitch + 5.5 * sub
                val y = bbox[1] + row * pitch + 5.5 * sub
                val sample = sampleLuma(h, x, y, luma, width, height)
                if (sample >= 0 && count < guardSamples.size) guardSamples[count++] = sample
                if (count == guardSamples.size) break@loop
            }
        }
        if (count < 12) return null
        guardSamples.sort(0, count)
        val backgroundY = guardSamples[count / 2]
        return ShapeGridPalette(colors, backgroundY)
    }

    private fun extractBlock(profile: ShapeGridProfile, grid: IntArray, blockId: Int): IntArray {
        val out = IntArray(profile.blockCells)
        val blockX = (blockId % 4) * profile.blockCols
        val blockY = (blockId / 4) * profile.blockRows
        var cursor = 0
        for (row in 0 until profile.blockRows) {
            val source = (blockY + row) * profile.gridCols + blockX
            for (col in 0 until profile.blockCols) out[cursor++] = grid[source + col]
        }
        return out
    }

    private fun projectedTilePitch(profile: ShapeGridProfile, h: DoubleArray): Double {
        val bbox = profile.canonicalGridBbox
        val pitch = (bbox[2] - bbox[0]) / profile.gridCols
        val cx = (bbox[0] + bbox[2]) * 0.5
        val cy = (bbox[1] + bbox[3]) * 0.5
        val p0 = DoubleArray(2); val px = DoubleArray(2); val py = DoubleArray(2)
        if (!map(h, cx, cy, p0) || !map(h, cx + pitch, cy, px) || !map(h, cx, cy + pitch, py)) return 0.0
        return minOf(hypot(px[0] - p0[0], px[1] - p0[1]), hypot(py[0] - p0[0], py[1] - p0[1]))
    }

    private fun sampleLuma(
        h: DoubleArray,
        canonicalX: Double,
        canonicalY: Double,
        luma: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        if (!map(h, canonicalX, canonicalY, projected)) return -1
        val x = projected[0].roundToInt()
        val y = projected[1].roundToInt()
        return if (x in 0 until width && y in 0 until height) luma[y * width + x].toInt() and 0xFF else -1
    }

    private fun map(h: DoubleArray, x: Double, y: Double, output: DoubleArray): Boolean {
        val denominator = h[6] * x + h[7] * y + h[8]
        if (!denominator.isFinite() || abs(denominator) < 1e-9) return false
        val px = (h[0] * x + h[1] * y + h[2]) / denominator
        val py = (h[3] * x + h[4] * y + h[5]) / denominator
        if (!px.isFinite() || !py.isFinite()) return false
        output[0] = px; output[1] = py
        return true
    }

    private fun colorDistance(a: IntArray, b: IntArray): Double {
        val dy = (a[0] - b[0]) / 32.0
        val du = (a[1] - b[1]) / 24.0
        val dv = (a[2] - b[2]) / 24.0
        return dy * dy + du * du + dv * dv
    }

    private fun elapsedMs(startedNs: Long): Double = (System.nanoTime() - startedNs) / 1_000_000.0

    override fun close() = acquirer.close()

    companion object {
        private const val LUMA_CLASS_MARGIN = 5
        private const val MAX_UNREADABLE_SUBCELLS = 2
        private const val MAX_AMBIGUOUS_SUBCELLS = 5
        private const val MAX_SHAPE_HAMMING = 4
        private const val MIN_SHAPE_MARGIN = 2
        private const val COLOR_SAMPLES_PER_TILE = 4
        private const val MIN_COLOR_SAMPLES = 2
        private const val MIN_COLOR_DISTANCE_RATIO = 1.12
        private const val MIN_COLOR_DISTANCE_MARGIN = 0.05
    }
}
