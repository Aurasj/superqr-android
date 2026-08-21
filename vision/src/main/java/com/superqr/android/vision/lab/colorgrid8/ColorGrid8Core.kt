package com.superqr.android.vision.lab.colorgrid8

import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** LAB-only ColorGrid8 constants. Nothing here is part of production V7. */
object ColorGrid8Spec {
    const val BITS_PER_CELL = 3
    const val CHROMA_BITS = 2
    const val HEADER_MAGIC = 0xC8D
    const val DIAGNOSTIC_HEADER_VERSION = 1
    const val TRANSFER_HEADER_VERSION = 2
    const val HEADER_VERSION = DIAGNOSTIC_HEADER_VERSION
    const val HEADER_PAYLOAD_BITS = 51
    const val HEADER_BITS = 56
    const val HEADER_CELLS = 112
    const val HEADER_ROWS = 2
    const val PILOT_PERIOD = 25
    const val DEFAULT_SEED = 0x4D3A
    const val FIDUCIAL_OFFSET_CELLS = 4
    const val TARGET_POST_FEC_KIB_S = 200.0
    const val TARGET_PIPELINE_P95_MS = 25.0
    private const val GOLDEN_STEP: Int = -1640531535 // 0x9E3779B1
    private const val PAYLOAD_SALT: Int = -1072166259 // 0xC0180A8D

    val diagnosticGrids: List<Pair<Int, Int>> = listOf(
        128 to 96,
        144 to 112,
        160 to 136,
        168 to 144,
        176 to 144,
    )
    val transferGrids: List<Pair<Int, Int>> = listOf(
        240 to 216,
        336 to 288,
        384 to 336,
    )
    val grids: List<Pair<Int, Int>> = diagnosticGrids + transferGrids
    val fpsSweep: List<Int> = listOf(15, 20, 24, 30)
    val transferFpsSweep: List<Int> = listOf(30, 45, 60, 90)

    fun profileId(cols: Int, rows: Int): Int = grids.indexOf(cols to rows)
    fun fpsCode(fps: Int, version: Int = HEADER_VERSION): Int =
        (if (version == TRANSFER_HEADER_VERSION) transferFpsSweep else fpsSweep).indexOf(fps)

    fun fpsForCode(code: Int, version: Int): Int? =
        (if (version == TRANSFER_HEADER_VERSION) transferFpsSweep else fpsSweep).getOrNull(code)

    fun profileForId(
        profileId: Int,
        fps: Int,
        seed: Int = DEFAULT_SEED,
        version: Int = HEADER_VERSION,
    ): ColorGrid8Profile? {
        val dims = grids.getOrNull(profileId) ?: return null
        return runCatching { ColorGrid8Profile(dims.first, dims.second, fps, seed, version) }.getOrNull()
    }

    internal fun payloadSeed(profile: ColorGrid8Profile, frameIndex: Int): Int =
        (profile.seed shl 16) xor (frameIndex * GOLDEN_STEP) xor PAYLOAD_SALT
}


data class ColorGrid8Profile(
    val cols: Int,
    val rows: Int,
    val fps: Int = 30,
    val seed: Int = ColorGrid8Spec.DEFAULT_SEED,
    val version: Int = ColorGrid8Spec.HEADER_VERSION,
) {
    init {
        require(ColorGrid8Spec.profileId(cols, rows) >= 0) { "unsupported ColorGrid8 grid: ${cols}x${rows}" }
        require(version in setOf(ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION, ColorGrid8Spec.TRANSFER_HEADER_VERSION)) {
            "unsupported ColorGrid8 version: $version"
        }
        val allowedGrids = if (version == ColorGrid8Spec.TRANSFER_HEADER_VERSION) {
            ColorGrid8Spec.transferGrids
        } else {
            ColorGrid8Spec.diagnosticGrids
        }
        require(cols to rows in allowedGrids) { "grid ${cols}x${rows} is not valid for ColorGrid8 v$version" }
        require(ColorGrid8Spec.fpsCode(fps, version) >= 0) { "unsupported ColorGrid8 FPS: $fps" }
        require(cols >= ColorGrid8Spec.HEADER_CELLS) { "grid is too narrow for header" }
        require(seed in 0..0xFFFF) { "seed must fit 16 bits" }
    }

    val profileId: Int get() = ColorGrid8Spec.profileId(cols, rows)
    val totalCells: Int get() = cols * rows
    val pilotCells: Int get() {
        val usable = (rows - ColorGrid8Spec.HEADER_ROWS) * cols
        return (usable + ColorGrid8Spec.PILOT_PERIOD - 1) / ColorGrid8Spec.PILOT_PERIOD
    }
    val payloadCells: Int get() = totalCells - cols * ColorGrid8Spec.HEADER_ROWS - pilotCells
    val byteCapacity: Int get() = payloadCells * ColorGrid8Spec.BITS_PER_CELL / 8
    val rawKibS: Double get() = totalCells * 3.0 * fps / 8.0 / 1024.0
    val payloadKibS: Double get() = payloadCells * 3.0 * fps / 8.0 / 1024.0
    fun postFecKibS(fecFraction: Double = 0.20): Double = payloadKibS * (1.0 - fecFraction)
}


data class ColorGrid8Header(
    val profileId: Int,
    val fps: Int,
    val frameIndex: Int,
    val seed: Int,
    val version: Int = ColorGrid8Spec.HEADER_VERSION,
)


data class ColorGrid8Centroid(
    val y: Double,
    val u: Double,
    val v: Double,
    val sigmaUv: Double,
    val count: Int,
)


data class ColorGrid8AnalysisResult(
    val header: ColorGrid8Header,
    val payloadCells: Int,
    val classifiedSymbols: Int,
    val symbolErrors: Int,
    val bitErrors: Int,
    val erasures: Int,
    val symbolErrorRate: Double,
    val bitErrorRate: Double,
    val erasureRate: Double,
    val fecLoad: Double,
    val estimatedPostFecKibS: Double,
    val pilotMinUvDistance: Double,
    val lumaThreshold: Double,
    val centroids: List<ColorGrid8Centroid>,
    val confusionMatrix: IntArray,
    val analysisMs: Double,
    val payloadSymbols: ByteArray? = null,
)

enum class ColorGrid8Stage {
    CAMERA,
    FINDERS,
    GEOMETRY,
    WARP,
    HEADER,
    PROFILE,
    PILOTS,
    PAYLOAD,
}

data class ColorGrid8AnalysisAttempt(
    val result: ColorGrid8AnalysisResult?,
    val stage: ColorGrid8Stage,
    val failure: String?,
    val detectedHeader: ColorGrid8Header? = null,
    val headerContrast: Double = 0.0,
    val rawHeaderMagic: Int? = null,
    val headerScore: Int = Int.MAX_VALUE,
)

/** Dense one-byte-per-cell Y/U/V means after perspective warp and block averaging. */
data class ColorGrid8CellMeans(
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    init {
        require(y.size == u.size && y.size == v.size)
    }
}


private class Xorshift32(seed: Int) {
    private var state: Int = if (seed != 0) seed else 1

    fun next(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }
}


object ColorGrid8Codec {
    fun isPilot(profile: ColorGrid8Profile, row: Int, col: Int): Boolean {
        if (row < ColorGrid8Spec.HEADER_ROWS) return false
        val flat = (row - ColorGrid8Spec.HEADER_ROWS) * profile.cols + col
        return flat % ColorGrid8Spec.PILOT_PERIOD == 0
    }

    fun pilotSymbol(profile: ColorGrid8Profile, row: Int, col: Int, frameIndex: Int): Int {
        val flat = (row - ColorGrid8Spec.HEADER_ROWS) * profile.cols + col
        return ((flat / ColorGrid8Spec.PILOT_PERIOD) + frameIndex) and 7
    }

    private fun crc5(bits: IntArray, length: Int): Int {
        var reg = 0x1F
        for (index in 0 until length) {
            val feedback = ((reg ushr 4) and 1) xor (bits[index] and 1)
            reg = (reg shl 1) and 0x1F
            if (feedback != 0) reg = reg xor 0x05
        }
        return reg xor 0x1F
    }

    private fun appendBits(out: IntArray, cursorStart: Int, value: Int, width: Int): Int {
        var cursor = cursorStart
        for (shift in width - 1 downTo 0) out[cursor++] = (value ushr shift) and 1
        return cursor
    }

    fun headerBits(profile: ColorGrid8Profile, frameIndex: Int): IntArray {
        val bits = IntArray(ColorGrid8Spec.HEADER_BITS)
        var cursor = 0
        cursor = appendBits(bits, cursor, ColorGrid8Spec.HEADER_MAGIC, 12)
        cursor = appendBits(bits, cursor, profile.version, 2)
        cursor = appendBits(bits, cursor, profile.profileId, 3)
        cursor = appendBits(bits, cursor, ColorGrid8Spec.fpsCode(profile.fps, profile.version), 2)
        cursor = appendBits(bits, cursor, frameIndex and 0xFFFF, 16)
        cursor = appendBits(bits, cursor, profile.seed, 16)
        check(cursor == ColorGrid8Spec.HEADER_PAYLOAD_BITS)
        appendBits(bits, cursor, crc5(bits, ColorGrid8Spec.HEADER_PAYLOAD_BITS), 5)
        return bits
    }

    fun headerSymbols(profile: ColorGrid8Profile, frameIndex: Int): ByteArray {
        val out = ByteArray(profile.cols)
        var cursor = 0
        for (bit in headerBits(profile, frameIndex)) {
            val symbol = if (bit != 0) 4.toByte() else 0.toByte()
            out[cursor++] = symbol
            out[cursor++] = symbol
        }
        while (cursor < out.size) {
            out[cursor] = if ((cursor and 1) != 0) 4 else 0
            cursor++
        }
        return out
    }

    fun decodeHeaderBits(bits: IntArray): ColorGrid8Header? {
        if (bits.size < ColorGrid8Spec.HEADER_BITS) return null
        val expectedCrc = (bits[51] shl 4) or (bits[52] shl 3) or (bits[53] shl 2) or
            (bits[54] shl 1) or bits[55]
        if (crc5(bits, ColorGrid8Spec.HEADER_PAYLOAD_BITS) != expectedCrc) return null
        var cursor = 0
        fun take(width: Int): Int {
            var value = 0
            repeat(width) { value = (value shl 1) or (bits[cursor++] and 1) }
            return value
        }
        val magic = take(12)
        val version = take(2)
        val profileId = take(3)
        val fpsCode = take(2)
        val frameIndex = take(16)
        val seed = take(16)
        if (magic != ColorGrid8Spec.HEADER_MAGIC || version !in setOf(
                ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION,
                ColorGrid8Spec.TRANSFER_HEADER_VERSION,
            )) return null
        val fps = ColorGrid8Spec.fpsForCode(fpsCode, version) ?: return null
        if (profileId !in ColorGrid8Spec.grids.indices) return null
        return ColorGrid8Header(profileId, fps, frameIndex, seed, version)
    }

    fun buildSymbols(profile: ColorGrid8Profile, frameIndex: Int): ByteArray {
        val out = ByteArray(profile.totalCells)
        val header = headerSymbols(profile, frameIndex)
        repeat(ColorGrid8Spec.HEADER_ROWS) { row -> header.copyInto(out, row * profile.cols) }
        val prng = Xorshift32(ColorGrid8Spec.payloadSeed(profile, frameIndex))
        for (row in ColorGrid8Spec.HEADER_ROWS until profile.rows) {
            val base = row * profile.cols
            for (col in 0 until profile.cols) {
                out[base + col] = if (isPilot(profile, row, col)) {
                    pilotSymbol(profile, row, col, frameIndex).toByte()
                } else {
                    (prng.next() and 7).toByte()
                }
            }
        }
        return out
    }

    fun crc32(profile: ColorGrid8Profile, frameIndex: Int): Long =
        CRC32().apply { update(buildSymbols(profile, frameIndex)) }.value
}


class ColorGrid8Analyzer(
    private val lumaErasureFraction: Double = 0.08,
    private val chromaMarginThreshold: Double = 0.10,
) {
    private data class HeaderLocation(val row: Int, val shift: Int, val reversed: Boolean)

    private data class HeaderProbe(
        val header: ColorGrid8Header?,
        val contrast: Double,
        val rawMagic: Int,
        val score: Int,
        val location: String,
        val headerLocation: HeaderLocation,
    )

    private var lockedHeaderLocation: HeaderLocation? = null

    fun reset() {
        lockedHeaderLocation = null
    }

    fun analyze(expectedProfile: ColorGrid8Profile, means: ColorGrid8CellMeans): ColorGrid8AnalysisResult? {
        return analyzeDetailed(expectedProfile, means).result
    }

    fun analyzeDetailed(expectedProfile: ColorGrid8Profile, means: ColorGrid8CellMeans): ColorGrid8AnalysisAttempt {
        if (
            means.y.size != expectedProfile.totalCells ||
            means.u.size != expectedProfile.totalCells ||
            means.v.size != expectedProfile.totalCells
        ) {
            return ColorGrid8AnalysisAttempt(null, ColorGrid8Stage.WARP, "cell-mean dimensions do not match expected grid")
        }
        val started = System.nanoTime()
        val headerProbe = decodeHeaderFromLuma(means.y, expectedProfile.cols, expectedProfile.rows)
        val header = headerProbe.header
            ?: return ColorGrid8AnalysisAttempt(
                null,
                ColorGrid8Stage.HEADER,
                "header invalid: contrast %.1f raw magic %03X distance %d at %s".format(
                    headerProbe.contrast,
                    headerProbe.rawMagic,
                    headerProbe.score,
                    headerProbe.location,
                ),
                headerContrast = headerProbe.contrast,
                rawHeaderMagic = headerProbe.rawMagic,
                headerScore = headerProbe.score,
            )
        if (
            header.profileId != expectedProfile.profileId ||
            header.fps != expectedProfile.fps ||
            header.version != expectedProfile.version
        ) {
            return ColorGrid8AnalysisAttempt(
                null,
                ColorGrid8Stage.PROFILE,
                "detected profile ${header.profileId}@${header.fps} does not match expected ${expectedProfile.profileId}@${expectedProfile.fps}",
                header,
                headerProbe.contrast,
                headerProbe.rawMagic,
                headerProbe.score,
            )
        }
        val profile = ColorGrid8Profile(
            expectedProfile.cols,
            expectedProfile.rows,
            header.fps,
            header.seed,
            header.version,
        )
        val centroids = calibratePilots(profile, header.frameIndex, means)
            ?: return ColorGrid8AnalysisAttempt(
                null,
                ColorGrid8Stage.PILOTS,
                "not enough valid samples for every pilot symbol",
                header,
                headerProbe.contrast,
                headerProbe.rawMagic,
                headerProbe.score,
            )

        val darkMean = centroids.subList(0, 4).map { it.y }.average()
        val lightMean = centroids.subList(4, 8).map { it.y }.average()
        val lumaThreshold = (darkMean + lightMean) * 0.5
        val lumaHalfGap = max(1.0, abs(lightMean - darkMean) * 0.5)
        val pilotMinUvDistance = minimumPilotUvDistance(centroids)

        val diagnostic = header.version == ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION
        val prng = if (diagnostic) Xorshift32(ColorGrid8Spec.payloadSeed(profile, header.frameIndex)) else null
        val payloadSymbols = if (diagnostic) null else ByteArray(profile.payloadCells)
        var payloadCursor = 0
        val confusion = IntArray(64)
        var classified = 0
        var symbolErrors = 0
        var bitErrors = 0
        var erasures = 0

        for (row in ColorGrid8Spec.HEADER_ROWS until profile.rows) {
            val base = row * profile.cols
            for (col in 0 until profile.cols) {
                if (ColorGrid8Codec.isPilot(profile, row, col)) continue
                val expected = prng?.next()?.and(7) ?: 0
                val index = base + col
                val y = means.y[index].toInt() and 0xFF
                val u = means.u[index].toInt() and 0xFF
                val v = means.v[index].toInt() and 0xFF
                val observed = classify(y, u, v, centroids, lumaThreshold, lumaHalfGap)
                if (observed < 0) {
                    erasures++
                    payloadSymbols?.set(payloadCursor, 0)
                    payloadCursor++
                    continue
                }
                payloadSymbols?.set(payloadCursor, observed.toByte())
                payloadCursor++
                classified++
                if (diagnostic) confusion[expected * 8 + observed]++
                if (diagnostic && observed != expected) {
                    symbolErrors++
                    bitErrors += Integer.bitCount(observed xor expected)
                }
            }
        }

        val payload = profile.payloadCells.coerceAtLeast(1)
        val symbolErrorRate = if (!diagnostic) 0.0 else if (classified > 0) symbolErrors.toDouble() / classified else 1.0
        val bitErrorRate = if (!diagnostic) 0.0 else if (classified > 0) bitErrors.toDouble() / (classified * 3.0) else 1.0
        val erasureRate = erasures.toDouble() / payload
        // Conservative parity-load estimate: error ~= 2 parity symbols, erasure ~= 1.
        val fecLoad = (2.0 * symbolErrors + erasures) / payload
        val budget = profile.postFecKibS(0.20)
        val estimatedPostFec = if (fecLoad <= 0.20) budget else budget * (0.20 / fecLoad).coerceIn(0.0, 1.0)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val result = ColorGrid8AnalysisResult(
            header = header,
            payloadCells = profile.payloadCells,
            classifiedSymbols = classified,
            symbolErrors = symbolErrors,
            bitErrors = bitErrors,
            erasures = erasures,
            symbolErrorRate = symbolErrorRate,
            bitErrorRate = bitErrorRate,
            erasureRate = erasureRate,
            fecLoad = fecLoad,
            estimatedPostFecKibS = estimatedPostFec,
            pilotMinUvDistance = pilotMinUvDistance,
            lumaThreshold = lumaThreshold,
            centroids = centroids,
            confusionMatrix = confusion,
            analysisMs = elapsedMs,
            payloadSymbols = payloadSymbols,
        )
        return ColorGrid8AnalysisAttempt(
            result,
            ColorGrid8Stage.PAYLOAD,
            null,
            header,
            headerProbe.contrast,
            headerProbe.rawMagic,
            headerProbe.score,
        )
    }

    private fun decodeHeaderFromLuma(y: ByteArray, cols: Int, rows: Int): HeaderProbe {
        lockedHeaderLocation?.let { location ->
            return probeLocation(y, cols, location)
        }
        var best: HeaderProbe? = null
        val rowCandidates = ((0 until minOf(8, rows)) +
            ((rows - minOf(8, rows)) until rows)).distinct()
        val maxShift = minOf(16, cols - ColorGrid8Spec.HEADER_CELLS)
        for (row in rowCandidates) {
            for (reversed in listOf(false, true)) {
                for (shift in 0..maxShift) {
                    val location = HeaderLocation(row, shift, reversed)
                    val probe = probeLocation(y, cols, location)
                    if (probe.header != null) {
                        lockedHeaderLocation = location
                        return probe
                    }
                    val current = best
                    if (
                        current == null || probe.score < current.score ||
                        (probe.score == current.score && probe.contrast > current.contrast)
                    ) best = probe
                }
            }
        }
        return best ?: HeaderProbe(
            null,
            0.0,
            0,
            12,
            "no candidate",
            HeaderLocation(0, 0, false),
        )
    }

    private fun probeLocation(y: ByteArray, cols: Int, location: HeaderLocation): HeaderProbe {
        val cells = ByteArray(ColorGrid8Spec.HEADER_CELLS)
        for (index in cells.indices) {
            val col = if (location.reversed) cols - 1 - location.shift - index else location.shift + index
            cells[index] = y[location.row * cols + col]
        }
        val label = "row=${location.row} shift=${location.shift} ${if (location.reversed) "reverse" else "forward"}"
        return decodeHeaderCells(cells, label, location)
    }

    private fun decodeHeaderCells(y: ByteArray, label: String, location: HeaderLocation): HeaderProbe {
        val pairMeans = DoubleArray(ColorGrid8Spec.HEADER_BITS)
        var low = 255.0
        var high = 0.0
        for (bit in 0 until ColorGrid8Spec.HEADER_BITS) {
            val a = y[bit * 2].toInt() and 0xFF
            val b = y[bit * 2 + 1].toInt() and 0xFF
            val mean = (a + b) * 0.5
            pairMeans[bit] = mean
            if (mean < low) low = mean
            if (mean > high) high = mean
        }
        val contrast = high - low
        if (contrast < 12.0) return HeaderProbe(null, contrast, 0, 12, label, location)
        var c0 = low
        var c1 = high
        repeat(6) {
            var sum0 = 0.0
            var sum1 = 0.0
            var n0 = 0
            var n1 = 0
            for (value in pairMeans) {
                if (abs(value - c0) <= abs(value - c1)) {
                    sum0 += value; n0++
                } else {
                    sum1 += value; n1++
                }
            }
            if (n0 > 0) c0 = sum0 / n0
            if (n1 > 0) c1 = sum1 / n1
        }
        val threshold = (c0 + c1) * 0.5
        val bits = IntArray(ColorGrid8Spec.HEADER_BITS) { index -> if (pairMeans[index] > threshold) 1 else 0 }
        var rawMagic = 0
        for (index in 0 until 12) rawMagic = (rawMagic shl 1) or bits[index]
        val score = Integer.bitCount(rawMagic xor ColorGrid8Spec.HEADER_MAGIC)
        return HeaderProbe(ColorGrid8Codec.decodeHeaderBits(bits), contrast, rawMagic, score, label, location)
    }

    private fun calibratePilots(
        profile: ColorGrid8Profile,
        frameIndex: Int,
        means: ColorGrid8CellMeans,
    ): List<ColorGrid8Centroid>? {
        val sumY = DoubleArray(8)
        val sumU = DoubleArray(8)
        val sumV = DoubleArray(8)
        val sumU2 = DoubleArray(8)
        val sumV2 = DoubleArray(8)
        val count = IntArray(8)
        for (row in ColorGrid8Spec.HEADER_ROWS until profile.rows) {
            val base = row * profile.cols
            for (col in 0 until profile.cols) {
                if (!ColorGrid8Codec.isPilot(profile, row, col)) continue
                val symbol = ColorGrid8Codec.pilotSymbol(profile, row, col, frameIndex)
                val index = base + col
                val y = (means.y[index].toInt() and 0xFF).toDouble()
                val u = (means.u[index].toInt() and 0xFF).toDouble()
                val v = (means.v[index].toInt() and 0xFF).toDouble()
                sumY[symbol] += y
                sumU[symbol] += u
                sumV[symbol] += v
                sumU2[symbol] += u * u
                sumV2[symbol] += v * v
                count[symbol]++
            }
        }
        if (count.any { it < 4 }) return null
        return List(8) { symbol ->
            val n = count[symbol].toDouble()
            val meanU = sumU[symbol] / n
            val meanV = sumV[symbol] / n
            val varianceUv = max(0.0, sumU2[symbol] / n - meanU * meanU) +
                max(0.0, sumV2[symbol] / n - meanV * meanV)
            ColorGrid8Centroid(
                y = sumY[symbol] / n,
                u = meanU,
                v = meanV,
                sigmaUv = sqrt(varianceUv),
                count = count[symbol],
            )
        }
    }

    private fun classify(
        y: Int,
        u: Int,
        v: Int,
        centroids: List<ColorGrid8Centroid>,
        lumaThreshold: Double,
        lumaHalfGap: Double,
    ): Int {
        val lumaConfidence = abs(y - lumaThreshold) / lumaHalfGap
        if (lumaConfidence < lumaErasureFraction) return -1
        val luma = if (y > lumaThreshold) 1 else 0
        val start = luma * 4
        var bestSymbol = -1
        var best = Double.POSITIVE_INFINITY
        var second = Double.POSITIVE_INFINITY
        for (symbol in start until start + 4) {
            val c = centroids[symbol]
            val du = u - c.u
            val dv = v - c.v
            val distance = du * du + dv * dv
            if (distance < best) {
                second = best
                best = distance
                bestSymbol = symbol
            } else if (distance < second) {
                second = distance
            }
        }
        if (bestSymbol < 0 || !best.isFinite() || !second.isFinite()) return -1
        val bestRoot = sqrt(best)
        val secondRoot = sqrt(second)
        val margin = (secondRoot - bestRoot) / max(1.0, secondRoot)
        val allowedRadius = max(12.0, centroids[bestSymbol].sigmaUv * 4.5)
        if (bestRoot > allowedRadius || margin < chromaMarginThreshold) return -1
        return bestSymbol
    }

    private fun minimumPilotUvDistance(centroids: List<ColorGrid8Centroid>): Double {
        var minimum = Double.POSITIVE_INFINITY
        for (luma in 0..1) {
            val start = luma * 4
            for (a in start until start + 4) {
                for (b in a + 1 until start + 4) {
                    val du = centroids[a].u - centroids[b].u
                    val dv = centroids[a].v - centroids[b].v
                    minimum = minOf(minimum, sqrt(du * du + dv * dv))
                }
            }
        }
        return if (minimum.isFinite()) minimum else 0.0
    }
}
