package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.classification.Xorshift32

object V6ExpectedGridGenerator {

    fun generateForStaticPattern(patternName: String): IntArray {
        val prng = Xorshift32(42)
        val expected = IntArray(400)
        for (r in 0 until 20) {
            for (c in 0 until 20) {
                val idx = r * 20 + c
                expected[idx] = when (patternName.lowercase()) {
                    "black", "all-black" -> 0
                    "white", "all-white" -> 1
                    "checkerboard" -> (r + c) % 4
                    else -> (prng.nextInt() ushr 16) and 3
                }
            }
        }
        return expected
    }

    fun parseHexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "").replace("\r", "").trim()
        require(clean.length % 2 == 0) { "Hex string length must be even: ${clean.length}" }
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    fun bytesToPaletteIndexes(bytes: ByteArray): IntArray {
        require(bytes.size == 100) { "Must provide exactly 100 bytes (got ${bytes.size})" }
        val indexes = IntArray(400)
        for (i in 0 until 100) {
            val b = bytes[i].toInt() and 0xFF
            indexes[i * 4] = (b ushr 6) and 0x03
            indexes[i * 4 + 1] = (b ushr 4) and 0x03
            indexes[i * 4 + 2] = (b ushr 2) and 0x03
            indexes[i * 4 + 3] = b and 0x03
        }
        return indexes
    }
}
