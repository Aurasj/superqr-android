package com.superqr.android.vision.v7_capacity_lab

import kotlin.math.abs
import kotlin.math.roundToInt

enum class V7LabRunState(val wireValue: Int) {
    READY(0), RUNNING(1), DONE(2);

    companion object {
        fun fromWire(value: Int): V7LabRunState? = entries.firstOrNull { it.wireValue == value }
    }
}

data class V7LabRunEnvelope(
    val state: V7LabRunState,
    val profileId: Int,
    val runToken: Int,
    val frameIndex: Int,
    val frameCount: Int,
    val dwellEpochs: Int,
) {
    fun encode(): ByteArray {
        require(profileId in 0..255 && runToken in 0..65535 && frameIndex in 0..255)
        require(frameCount in 1..256 && dwellEpochs in 2..3)
        val packet = ByteArray(PACKET_BYTES)
        packet[0] = MAGIC.toByte(); packet[1] = VERSION.toByte(); packet[2] = state.wireValue.toByte()
        packet[3] = profileId.toByte(); packet[4] = runToken.toByte(); packet[5] = (runToken ushr 8).toByte()
        packet[6] = frameIndex.toByte(); packet[7] = (if (frameCount == 256) 0 else frameCount).toByte()
        packet[8] = dwellEpochs.toByte(); packet[9] = crc8(packet, 9).toByte()
        return packet
    }

    companion object {
        const val MAGIC = 0xD7
        const val VERSION = 1
        const val PACKET_BYTES = 10
        const val PACKET_BITS = 80

        fun decode(packet: ByteArray, offset: Int = 0): V7LabRunEnvelope? {
            if (packet.size - offset < PACKET_BYTES) return null
            if (u8(packet[offset]) != MAGIC || u8(packet[offset + 1]) != VERSION) return null
            if (crc8(packet, 9, offset) != u8(packet[offset + 9])) return null
            val state = V7LabRunState.fromWire(u8(packet[offset + 2])) ?: return null
            val dwell = u8(packet[offset + 8])
            if (dwell !in 2..3) return null
            return V7LabRunEnvelope(
                state = state,
                profileId = u8(packet[offset + 3]),
                runToken = u8(packet[offset + 4]) or (u8(packet[offset + 5]) shl 8),
                frameIndex = u8(packet[offset + 6]),
                frameCount = u8(packet[offset + 7]).let { if (it == 0) 256 else it },
                dwellEpochs = dwell,
            )
        }

        fun crc8(bytes: ByteArray, length: Int, offset: Int = 0): Int {
            var crc = 0
            for (index in 0 until length) {
                crc = crc xor u8(bytes[offset + index])
                repeat(8) { crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc
        }

        private fun u8(value: Byte): Int = value.toInt() and 0xFF
    }
}

data class V7LabSyncResult(
    val envelope: V7LabRunEnvelope?,
    val status: String,
    val blackY: Int,
    val whiteY: Int,
    val topContrast: Int = 0,
    val bottomContrast: Int = 0,
    val minimumCellMargin: Int = 0,
)

data class V7CarrierSpec(
    val canvasSize: Double = 1000.0,
    val borderBbox: DoubleArray = doubleArrayOf(50.0, 50.0, 950.0, 950.0),
    val borderThickness: Double = 20.0,
    val syncTopBbox: DoubleArray = doubleArrayOf(200.0, 145.0, 800.0, 175.0),
    val syncBottomBbox: DoubleArray = doubleArrayOf(200.0, 825.0, 800.0, 855.0),
    val syncRows: Int = 2,
    val syncCols: Int = 40,
    val candidateContourEdges: DoubleArray = doubleArrayOf(50.0, 70.0),
) {
    init {
        require(borderBbox.size == 4 && syncTopBbox.size == 4 && syncBottomBbox.size == 4)
        require(syncRows * syncCols == V7LabRunEnvelope.PACKET_BITS)
        require(candidateContourEdges.isNotEmpty())
    }
}

/** Decodes matching top/bottom sync bands to reject rolling-shutter transitions. */
class V7Phase1SyncDecoder(private val spec: V7CarrierSpec = V7CarrierSpec()) {
    private data class BandLevels(val black: Int, val white: Int, val threshold: Double)

    private val projected = DoubleArray(2)
    private val patch = IntArray(9)
    private val topPacket = ByteArray(V7LabRunEnvelope.PACKET_BYTES)
    private val bottomPacket = ByteArray(V7LabRunEnvelope.PACKET_BYTES)
    private val topSamples = IntArray(V7LabRunEnvelope.PACKET_BITS)
    private val bottomSamples = IntArray(V7LabRunEnvelope.PACKET_BITS)

    fun analyze(h: DoubleArray, luma: ByteArray, width: Int, height: Int): V7LabSyncResult {
        if (!sampleBand(h, luma, width, height, spec.syncTopBbox, topSamples)) {
            return V7LabSyncResult(null, "SYNC_TOP_UNREADABLE", -1, -1)
        }
        if (!sampleBand(h, luma, width, height, spec.syncBottomBbox, bottomSamples)) {
            return V7LabSyncResult(null, "SYNC_BOTTOM_UNREADABLE", -1, -1)
        }
        val topLevels = estimateLevels(topSamples)
            ?: return V7LabSyncResult(null, "SYNC_TOP_LOW_CONTRAST", -1, -1)
        val bottomLevels = estimateLevels(bottomSamples)
            ?: return V7LabSyncResult(null, "SYNC_BOTTOM_LOW_CONTRAST", -1, -1)
        val topContrast = topLevels.white - topLevels.black
        val bottomContrast = bottomLevels.white - bottomLevels.black
        if (topContrast < MIN_BAND_CONTRAST || bottomContrast < MIN_BAND_CONTRAST) {
            return V7LabSyncResult(
                null, "SYNC_LOW_CONTRAST",
                (topLevels.black + bottomLevels.black) / 2,
                (topLevels.white + bottomLevels.white) / 2,
                topContrast, bottomContrast,
            )
        }
        val topMargin = decodeBand(topSamples, topLevels.threshold, topPacket)
        val bottomMargin = decodeBand(bottomSamples, bottomLevels.threshold, bottomPacket)
        val black = (topLevels.black + bottomLevels.black) / 2
        val white = (topLevels.white + bottomLevels.white) / 2
        val top = V7LabRunEnvelope.decode(topPacket)
            ?: return V7LabSyncResult(null, "SYNC_TOP_CRC_OR_HEADER", black, white, topContrast, bottomContrast, minOf(topMargin, bottomMargin))
        val bottom = V7LabRunEnvelope.decode(bottomPacket)
            ?: return V7LabSyncResult(null, "SYNC_BOTTOM_CRC_OR_HEADER", black, white, topContrast, bottomContrast, minOf(topMargin, bottomMargin))
        if (top != bottom) {
            return V7LabSyncResult(null, "SYNC_TRANSITION_TOP_BOTTOM_MISMATCH", black, white, topContrast, bottomContrast, minOf(topMargin, bottomMargin))
        }
        return V7LabSyncResult(top, "LOCKED", black, white, topContrast, bottomContrast, minOf(topMargin, bottomMargin))
    }

    private fun sampleBand(
        h: DoubleArray,
        luma: ByteArray,
        width: Int,
        height: Int,
        bbox: DoubleArray,
        output: IntArray,
    ): Boolean {
        val x1 = bbox[0]; val y1 = bbox[1]; val x2 = bbox[2]; val y2 = bbox[3]
        for (bit in output.indices) {
            val row = bit / spec.syncCols; val col = bit % spec.syncCols
            val x = x1 + (col + 0.5) * (x2 - x1) / spec.syncCols
            val y = y1 + (row + 0.5) * (y2 - y1) / spec.syncRows
            val sample = sampleMedian(h, x, y, luma, width, height)
            if (sample < 0) return false
            output[bit] = sample
        }
        return true
    }

    private fun estimateLevels(samples: IntArray): BandLevels? {
        var low = samples.minOrNull() ?: return null
        var high = samples.maxOrNull() ?: return null
        if (high <= low) return null
        repeat(5) {
            val split = (low + high) * 0.5
            var lowSum = 0; var lowCount = 0
            var highSum = 0; var highCount = 0
            for (sample in samples) {
                if (sample < split) { lowSum += sample; lowCount++ }
                else { highSum += sample; highCount++ }
            }
            if (lowCount < MIN_CLUSTER_CELLS || highCount < MIN_CLUSTER_CELLS) return null
            low = lowSum / lowCount
            high = highSum / highCount
        }
        return BandLevels(low, high, (low + high) * 0.5)
    }

    private fun decodeBand(samples: IntArray, threshold: Double, output: ByteArray): Int {
        output.fill(0)
        var minimumMargin = Int.MAX_VALUE
        for (bit in samples.indices) {
            val sample = samples[bit]
            minimumMargin = minOf(minimumMargin, abs(sample - threshold).roundToInt())
            if (sample >= threshold) {
                output[bit / 8] = (output[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
        }
        return minimumMargin
    }

    private fun sampleMedian(h: DoubleArray, x: Double, y: Double, luma: ByteArray, width: Int, height: Int): Int {
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            val value = sample(h, x + dx * 2.0, y + dy * 2.0, luma, width, height)
            if (value >= 0) patch[count++] = value
        }
        if (count == 0) return -1
        for (i in 1 until count) {
            val value = patch[i]; var j = i - 1
            while (j >= 0 && patch[j] > value) { patch[j + 1] = patch[j]; j-- }
            patch[j + 1] = value
        }
        return patch[count / 2]
    }

    private fun sample(h: DoubleArray, x: Double, y: Double, luma: ByteArray, width: Int, height: Int): Int {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || abs(den) < 1e-9) return -1
        projected[0] = (h[0] * x + h[1] * y + h[2]) / den
        projected[1] = (h[3] * x + h[4] * y + h[5]) / den
        val px = projected[0].roundToInt(); val py = projected[1].roundToInt()
        return if (px in 0 until width && py in 0 until height) luma[py * width + px].toInt() and 0xFF else -1
    }

    companion object {
        private const val MIN_BAND_CONTRAST = 24
        private const val MIN_CLUSTER_CELLS = 8
    }
}
