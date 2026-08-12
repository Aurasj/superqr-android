package com.superqr.android.transfer

/** Production optical mode selected from the Phase 0 physical sweep. */
object ProductionQrContract {
    const val PROFILE_ID = 0
    const val TRANSPORT_VERSION = 7
    const val QR_VERSION = 40
    const val QR_ECC = "L"
    const val FRAME_BYTES = 2953
    const val PAYLOAD_BYTES = FRAME_BYTES - 20
    const val TARGET_SENDER_FPS = 15.0
    const val CAMERA_TARGET_FPS = 30

    fun looksLikeProductionFrame(bytes: ByteArray): Boolean =
        bytes.size == FRAME_BYTES &&
            bytes[0] == 'S'.code.toByte() &&
            bytes[1] == 'Q'.code.toByte() &&
            (bytes[2].toInt() and 0xFF) == TRANSPORT_VERSION &&
            (bytes[3].toInt() and 0xFF) == PROFILE_ID
}
