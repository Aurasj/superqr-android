package com.superqr.android.phase1

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v7_capacity_lab.ExternalQrDecodeResult
import com.superqr.android.vision.v7_capacity_lab.ExternalQrFrameDecoder
import com.superqr.android.vision.v7_capacity_lab.ExternalQrSymbol
import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquirer
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7LabPrng
import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.roundToInt

data class AdvancedLaneObservation(
    val laneId: Int,
    val kind: String,
    val frameIndex: Int,
    val usefulBytes: Int,
    val observedBits: Int,
    val bitErrors: Int,
    val erasedBits: Int,
    val frameValid: Boolean,
    val postFecValid: Boolean,
    val validSamples: Int = 0,
    val failure: String? = null,
)

data class AdvancedPhyResult(
    val profile: AdvancedProfile,
    val envelope: V7LabRunEnvelope,
    val observations: List<AdvancedLaneObservation>,
    val decodedQrLanes: Int,
    val carrierSource: String?,
    val pipelineMs: Double,
    val failure: String? = null,
)

/**
 * Lab-only composite PHY receiver.
 *
 * One native ZXing read can contribute up to four independent SQA1 QR lanes.
 * Hybrid profiles then reuse one acquisition-carrier homography for every color
 * lane. No path here changes the canonical Phase1 VisionEngine.
 */
class AdvancedPhyEngine(
    private val manifest: AdvancedPhyManifest,
    carrierSpec: V7CarrierSpec,
) : AutoCloseable {
    private val carrierAcquirer = V7CarrierAcquirer(carrierSpec)
    private val gridStates = HashMap<Int, GridLaneState>()
    private val projected = DoubleArray(2)
    private val chroma = IntArray(2)

    private data class ValidQr(
        val profile: AdvancedProfile,
        val lane: AdvancedLane.Qr,
        val envelope: V7LabRunEnvelope,
        val frameIndex: Int,
    )

    private data class GroupKey(
        val profileId: Int,
        val runToken: Int,
        val frameIndex: Int,
        val frameCount: Int,
        val dwellEpochs: Int,
        val state: V7LabRunState,
    )

    private data class GridLaneState(
        val sampler: V7HighDensitySampler,
        val classifier: V7SoftClassifier,
    )

    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
        arrivalNs: Long,
    ): AdvancedPhyResult? {
        val external = (chromaReader as? ExternalQrFrameDecoder)?.decodeQr() ?: return null
        val symbols = externalSymbols(external)
        val valid = symbols.mapNotNull(::validateQr)
        if (valid.isEmpty()) return null

        // A rolling-shutter transition can expose symbols from adjacent display
        // epochs. Never aggregate them. Pick the largest internally consistent set.
        val grouped = valid.groupBy { item ->
            val e = item.envelope
            GroupKey(e.profileId, e.runToken, e.frameIndex, e.frameCount, e.dwellEpochs, e.state)
        }
        val best = grouped.values
            .map { group -> group.distinctBy { it.lane.laneId } }
            .maxWithOrNull(compareBy<List<ValidQr>> { it.size }.thenBy { it.first().envelope.frameIndex })
            ?: return null
        val first = best.first()
        val profile = first.profile
        val envelope = first.envelope
        val observations = ArrayList<AdvancedLaneObservation>(profile.laneCount)

        if (envelope.state == V7LabRunState.RUNNING) {
            for (item in best) {
                observations += AdvancedLaneObservation(
                    laneId = item.lane.laneId,
                    kind = "qr",
                    frameIndex = item.frameIndex,
                    usefulBytes = item.lane.usefulBytes,
                    observedBits = item.lane.frameBytes * 8,
                    bitErrors = 0,
                    erasedBits = 0,
                    frameValid = true,
                    postFecValid = true,
                )
            }
        }

        var carrierSource: String? = null
        var failure: String? = null
        if (profile.requiresCarrier) {
            val acquisition = carrierAcquirer.analyze(luma, width, height, diagnostics = false)
            carrierSource = acquisition.source
            val h = acquisition.canonicalToImageHomography
            val syncEnvelope = acquisition.sync.envelope
            if (h == null) {
                failure = "ADVANCED_CARRIER_NOT_FOUND"
            } else if (syncEnvelope != envelope) {
                // QR and carrier sync must describe the exact same epoch; this is
                // the composite equivalent of the top/bottom transition guard.
                failure = "ADVANCED_SYNC_QR_MISMATCH"
            } else if (envelope.state == V7LabRunState.RUNNING) {
                for (lane in profile.lanes.filterIsInstance<AdvancedLane.Grid>()) {
                    observations += analyzeGridLane(
                        profile, lane, envelope.frameIndex, h, luma, width, height, chromaReader,
                    )
                }
            }
        }

        return AdvancedPhyResult(
            profile = profile,
            envelope = envelope,
            observations = observations.sortedBy { it.laneId },
            decodedQrLanes = best.size,
            carrierSource = carrierSource,
            pipelineMs = (System.nanoTime() - arrivalNs) / 1_000_000.0,
            failure = failure,
        )
    }

    private fun externalSymbols(result: ExternalQrDecodeResult): List<ExternalQrSymbol> {
        if (result.symbols.isNotEmpty()) return result.symbols
        if (result.payload.isEmpty() && result.quad == null) return emptyList()
        return listOf(
            ExternalQrSymbol(
                payload = result.payload,
                quad = result.quad,
                errorType = result.errorType,
                errorMessage = result.errorMessage,
            )
        )
    }

    private fun validateQr(symbol: ExternalQrSymbol): ValidQr? {
        val payload = symbol.payload
        if (payload.size < 34) return null
        if (payload[0] != 'S'.code.toByte() || payload[1] != 'Q'.code.toByte() ||
            payload[2] != 'A'.code.toByte() || payload[3] != '1'.code.toByte()
        ) return null
        val version = u8(payload[4])
        val eccId = u8(payload[5])
        val laneId = u8(payload[6])
        val laneCount = u8(payload[7])
        val frameIndex = le32(payload, 8)
        val seed = le32(payload, 12)
        val declaredBytes = le16(payload, 16)
        val envelope = V7LabRunEnvelope.decode(payload, 18) ?: return null
        val profile = manifest.profile(envelope.profileId) ?: return null
        if (laneCount != profile.laneCount || frameIndex != envelope.frameIndex || seed != manifest.seed) return null
        val lane = profile.lanes.filterIsInstance<AdvancedLane.Qr>().firstOrNull { it.laneId == laneId } ?: return null
        if (lane.version != version || lane.eccId != eccId || lane.frameBytes != declaredBytes || payload.size != lane.frameBytes) return null
        if (u8(payload[28]) != 1 || u8(payload[29]) != 0) return null
        if (!crc32Matches(payload)) return null
        return ValidQr(profile, lane, envelope, frameIndex)
    }

    private fun analyzeGridLane(
        profile: AdvancedProfile,
        lane: AdvancedLane.Grid,
        frameIndex: Int,
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
    ): AdvancedLaneObservation {
        if (chromaReader == null) {
            return failedGrid(lane, frameIndex, "ADVANCED_CHROMA_UNAVAILABLE")
        }
        val palette = manifest.palettes[lane.palette]
            ?: return failedGrid(lane, frameIndex, "ADVANCED_PALETTE_UNKNOWN")
        if (palette.bitsPerCell != lane.bitsPerCell) {
            return failedGrid(lane, frameIndex, "ADVANCED_PALETTE_BITS_MISMATCH")
        }
        val key = profile.id * 16 + lane.laneId
        val state = gridStates.getOrPut(key) {
            val sampler = V7HighDensitySampler().apply {
                setGridShape(lane.rows, lane.cols, lane.dataBbox)
            }
            val classifier = V7SoftClassifier().apply {
                setCellCount(lane.rows * lane.cols)
            }
            GridLaneState(sampler, classifier)
        }
        val centers = samplePalettePilots(lane, palette.colors.size, h, luma, width, height, chromaReader)
            ?: return failedGrid(lane, frameIndex, "ADVANCED_PALETTE_PILOTS_UNREADABLE")
        state.classifier.setCenters(centers)
        val validSamples = state.sampler.sampleCross5(h, luma, width, height, chromaReader)
        state.classifier.classify(
            state.sampler.getYCross5(),
            state.sampler.getUCross5(),
            state.sampler.getVCross5(),
            lane.rows * lane.cols,
        )
        val expected = expectedSymbols(profile.id, lane, frameIndex)
        val decoded = state.classifier.bestSymbols
        val cells = expected.size
        var bitErrors = 0
        var erasedBits = 0
        val byteError = BooleanArray(lane.rawBytes)
        val byteErasure = BooleanArray(lane.rawBytes)
        for (cell in 0 until cells) {
            val actual = decoded[cell].toInt()
            val expectedSymbol = expected[cell].toInt() and 0xFF
            val erased = actual < 0
            val wrong = !erased && actual != expectedSymbol
            if (erased) erasedBits += lane.bitsPerCell
            if (wrong) bitErrors += Integer.bitCount(actual xor expectedSymbol)
            if (erased || wrong) {
                val firstBit = cell * lane.bitsPerCell
                val lastBit = firstBit + lane.bitsPerCell - 1
                for (byteIndex in firstBit / 8..lastBit / 8) {
                    if (byteIndex in 0 until lane.rawBytes) {
                        if (erased) byteErasure[byteIndex] = true else if (!byteErasure[byteIndex]) byteError[byteIndex] = true
                    }
                }
            }
        }
        val postFec = postFecValid(lane, byteError, byteErasure)
        return AdvancedLaneObservation(
            laneId = lane.laneId,
            kind = "grid",
            frameIndex = frameIndex,
            usefulBytes = lane.usefulBytes,
            observedBits = cells * lane.bitsPerCell,
            bitErrors = bitErrors,
            erasedBits = erasedBits,
            frameValid = bitErrors == 0 && erasedBits == 0,
            postFecValid = postFec,
            validSamples = validSamples,
            failure = if (postFec) null else "ADVANCED_GRID_FEC_FAILED",
        )
    }

    private fun samplePalettePilots(
        lane: AdvancedLane.Grid,
        count: Int,
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader,
    ): Array<IntArray>? {
        val bbox = lane.pilotBbox
        val centers = Array(count) { IntArray(3) }
        for (symbol in 0 until count) {
            val x = bbox[0] + (symbol + 0.5) * (bbox[2] - bbox[0]) / count
            val y = (bbox[1] + bbox[3]) * 0.5
            if (!project(h, x, y)) return null
            val px = projected[0].roundToInt()
            val py = projected[1].roundToInt()
            if (px !in 0 until width || py !in 0 until height) return null
            if (!chromaReader.read(projected[0], projected[1], chroma)) return null
            centers[symbol][0] = luma[py * width + px].toInt() and 0xFF
            centers[symbol][1] = chroma[0]
            centers[symbol][2] = chroma[1]
        }
        return centers
    }

    private fun expectedSymbols(profileId: Int, lane: AdvancedLane.Grid, frameIndex: Int): ByteArray {
        var mixed = manifest.seed xor (profileId shl 16) xor (lane.laneId shl 8) xor frameIndex
        if (mixed == 0) mixed = 1
        val prng = V7LabPrng(mixed)
        return ByteArray(lane.rows * lane.cols) { prng.nextSymbol(lane.bitsPerCell).toByte() }
    }

    private fun postFecValid(
        lane: AdvancedLane.Grid,
        byteError: BooleanArray,
        byteErasure: BooleanArray,
    ): Boolean {
        val blockCount = (lane.rawBytes + 254) / 255
        val errors = IntArray(blockCount)
        val erasures = IntArray(blockCount)
        val baseLength = lane.rawBytes / blockCount
        val extraLength = lane.rawBytes % blockCount
        var byte = 0
        for (block in 0 until blockCount) {
            val length = baseLength + if (block < extraLength) 1 else 0
            repeat(length) {
                if (byteErasure[byte]) erasures[block]++ else if (byteError[byte]) errors[block]++
                byte++
            }
        }
        val parityBytes = ceil(lane.rawBytes * lane.fecParityRatio).toInt()
        val parityBase = parityBytes / blockCount
        val parityExtra = parityBytes % blockCount
        return (0 until blockCount).all { block ->
            val parity = parityBase + if (block < parityExtra) 1 else 0
            2 * errors[block] + erasures[block] <= parity
        }
    }

    private fun failedGrid(lane: AdvancedLane.Grid, frameIndex: Int, reason: String) =
        AdvancedLaneObservation(
            laneId = lane.laneId,
            kind = "grid",
            frameIndex = frameIndex,
            usefulBytes = lane.usefulBytes,
            observedBits = lane.rows * lane.cols * lane.bitsPerCell,
            bitErrors = 0,
            erasedBits = lane.rows * lane.cols * lane.bitsPerCell,
            frameValid = false,
            postFecValid = false,
            failure = reason,
        )

    private fun project(h: DoubleArray, x: Double, y: Double): Boolean {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || kotlin.math.abs(den) < 1e-9) return false
        val px = (h[0] * x + h[1] * y + h[2]) / den
        val py = (h[3] * x + h[4] * y + h[5]) / den
        if (!px.isFinite() || !py.isFinite()) return false
        projected[0] = px
        projected[1] = py
        return true
    }

    private fun crc32Matches(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        val crc = CRC32()
        crc.update(payload, 0, payload.size - 4)
        val expected = le32(payload, payload.size - 4).toLong() and 0xFFFFFFFFL
        return crc.value == expected
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
    private fun le16(bytes: ByteArray, offset: Int): Int =
        u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    private fun le32(bytes: ByteArray, offset: Int): Int =
        u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8) or
            (u8(bytes[offset + 2]) shl 16) or (u8(bytes[offset + 3]) shl 24)

    override fun close() {
        carrierAcquirer.close()
    }
}
