package com.superqr.android.vision.v7_capacity_lab

/**
 * Optional frame-scoped binary QR decoder supplied by the camera layer.
 *
 * The capability is valid only during the synchronous analysis callback for the
 * current camera frame. It keeps ImageProxy/native details out of vision code.
 */
interface ExternalQrFrameDecoder {
    fun decodeQr(): ExternalQrDecodeResult
}

data class ExternalQrDecodeResult(
    val payload: ByteArray,
    val elapsedMs: Double,
    val resultCount: Int,
    val source: String,
    /** QR quadrangle in the same rotation-normalized ImageAnalysis coordinates as luma. */
    val quad: List<DoubleArray>? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
)
