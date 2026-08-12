package com.superqr.android.transfer

data class ProductionQrProfile(
    val id: Int,
    val label: String,
    val ecc: String,
    val senderFps: Double,
    val frameBytes: Int,
) {
    val payloadBytes: Int get() = frameBytes - 20
}

/** Production QR modes selectable on the V40 sender. */
object ProductionQrContract {
    const val TRANSPORT_VERSION = 7
    const val QR_VERSION = 40
    const val CAMERA_TARGET_FPS = 30

    val profiles = listOf(
        ProductionQrProfile(0, "V40-L • 15 FPS", "L", 15.0, 2953),
        ProductionQrProfile(1, "V40-M • 15 FPS", "M", 15.0, 2331),
        ProductionQrProfile(2, "V40-L • 20 FPS", "L", 20.0, 2953),
        ProductionQrProfile(3, "V40-M • 20 FPS", "M", 20.0, 2331),
        ProductionQrProfile(4, "V40-L • 30 FPS", "L", 30.0, 2953),
        ProductionQrProfile(5, "V40-M • 30 FPS", "M", 30.0, 2331),
    )

    private val byId = profiles.associateBy { it.id }
    val defaultProfile: ProductionQrProfile = requireNotNull(byId[0])

    // Backward-compatible aliases for tests/callers that mean the default mode.
    const val PROFILE_ID = 0
    const val FRAME_BYTES = 2953
    const val PAYLOAD_BYTES = FRAME_BYTES - 20
    const val TARGET_SENDER_FPS = 15.0

    fun profileForId(id: Int): ProductionQrProfile? = byId[id]

    fun profileForFrame(bytes: ByteArray): ProductionQrProfile? {
        if (bytes.size < 4) return null
        if (bytes[0] != 'S'.code.toByte() || bytes[1] != 'Q'.code.toByte()) return null
        if ((bytes[2].toInt() and 0xFF) != TRANSPORT_VERSION) return null
        val profile = byId[bytes[3].toInt() and 0xFF] ?: return null
        return profile.takeIf { bytes.size == it.frameBytes }
    }

    fun looksLikeProductionFrame(bytes: ByteArray): Boolean = profileForFrame(bytes) != null
}
