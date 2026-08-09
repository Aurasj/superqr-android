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
)

/** Decodes matching top/bottom sync bands to reject rolling-shutter transitions. */
class V7Phase1SyncDecoder {
    private val projected = DoubleArray(2)
    private val patch = IntArray(9)
    private val topPacket = ByteArray(V7LabRunEnvelope.PACKET_BYTES)
    private val bottomPacket = ByteArray(V7LabRunEnvelope.PACKET_BYTES)

    fun analyze(h: DoubleArray, luma: ByteArray, width: Int, height: Int): V7LabSyncResult {
        val black = sampleMedian(h, 300.0, 120.0, luma, width, height)
        val white = sampleMedian(h, 380.0, 120.0, luma, width, height)
        if (black < 0 || white < 0 || white - black < 32) {
            return V7LabSyncResult(null, "SYNC_LOW_CONTRAST", black, white)
        }
        val threshold = (black + white) * 0.5
        if (!decodeBand(h, luma, width, height, TOP_Y1, TOP_Y2, threshold, topPacket)) {
            return V7LabSyncResult(null, "SYNC_TOP_UNREADABLE", black, white)
        }
        if (!decodeBand(h, luma, width, height, BOTTOM_Y1, BOTTOM_Y2, threshold, bottomPacket)) {
            return V7LabSyncResult(null, "SYNC_BOTTOM_UNREADABLE", black, white)
        }
        val top = V7LabRunEnvelope.decode(topPacket)
            ?: return V7LabSyncResult(null, "SYNC_TOP_CRC_OR_HEADER", black, white)
        val bottom = V7LabRunEnvelope.decode(bottomPacket)
            ?: return V7LabSyncResult(null, "SYNC_BOTTOM_CRC_OR_HEADER", black, white)
        if (top != bottom) return V7LabSyncResult(null, "SYNC_TRANSITION_TOP_BOTTOM_MISMATCH", black, white)
        return V7LabSyncResult(top, "LOCKED", black, white)
    }

    private fun decodeBand(
        h: DoubleArray, luma: ByteArray, width: Int, height: Int,
        y1: Double, y2: Double, threshold: Double, output: ByteArray,
    ): Boolean {
        output.fill(0)
        for (bit in 0 until V7LabRunEnvelope.PACKET_BITS) {
            val row = bit / COLS; val col = bit % COLS
            val x = X1 + (col + 0.5) * (X2 - X1) / COLS
            val y = y1 + (row + 0.5) * (y2 - y1) / ROWS
            val sample = sampleMedian(h, x, y, luma, width, height)
            if (sample < 0) return false
            if (sample >= threshold) {
                output[bit / 8] = (output[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
        }
        return true
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
        private const val X1 = 200.0; private const val X2 = 800.0
        private const val TOP_Y1 = 145.0; private const val TOP_Y2 = 175.0
        private const val BOTTOM_Y1 = 825.0; private const val BOTTOM_Y2 = 855.0
        private const val ROWS = 2; private const val COLS = 40
    }
}
