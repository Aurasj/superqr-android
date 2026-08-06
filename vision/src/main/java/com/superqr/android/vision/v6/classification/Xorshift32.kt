package com.superqr.android.vision.v6.classification

class Xorshift32(seed: Int = 42) {
    private var state: Long = (seed.toLong() and 0xFFFFFFFFL)

    fun nextInt(): Int {
        var x = state
        x = x xor ((x shl 13) and 0xFFFFFFFFL)
        x = x xor ((x ushr 17) and 0xFFFFFFFFL)
        x = x xor ((x shl 5) and 0xFFFFFFFFL)
        state = x
        return x.toInt()
    }
}
