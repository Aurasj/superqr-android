package com.superqr.android.camera

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

data class CameraQrDecodeResult(
    val payload: ByteArray,
    val elapsedMs: Double,
    val resultCount: Int,
    val errorType: String? = null,
    val errorMessage: String? = null,
)

/**
 * Synchronous, frame-scoped QR decode capability.
 *
 * Implementations are consumed while CameraX still owns the ImageProxy. Callers
 * must not retain this object beyond the CameraManager.onFrame callback.
 */
fun interface CameraQrDecoder {
    fun decode(): CameraQrDecodeResult
}

/**
 * ZXing-C++ adapter over the original CameraX Y plane.
 *
 * The official Android wrapper reads ImageProxy plane 0 directly, preserving the
 * row stride/crop/rotation metadata without an RGB bitmap or another full-frame
 * copy. SuperQR validation remains outside this camera adapter.
 */
class ZxingCppCameraQrReader {
    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = true,
            tryRotate = false,
            tryInvert = false,
            tryDownscale = false,
            tryDenoise = true,
            maxNumberOfSymbols = 1,
            returnErrors = true,
            textMode = BarcodeReader.TextMode.PLAIN,
        )
    )

    fun decoderFor(image: ImageProxy): CameraQrDecoder {
        var attempted = false
        var cached = CameraQrDecodeResult(
            payload = ByteArray(0),
            elapsedMs = 0.0,
            resultCount = 0,
        )

        return CameraQrDecoder {
            if (!attempted) {
                attempted = true
                cached = decode(image)
            }
            cached
        }
    }

    private fun decode(image: ImageProxy): CameraQrDecodeResult {
        val startedNs = System.nanoTime()
        return try {
            val results = reader.read(image)
            val success = results.firstOrNull { result ->
                val bytes = result.bytes
                result.format == BarcodeReader.Format.QR_CODE &&
                    result.error == null &&
                    bytes != null &&
                    bytes.isNotEmpty()
            }
            val successBytes = success?.bytes
            val firstError = results.firstOrNull { it.error != null }?.error
            CameraQrDecodeResult(
                payload = successBytes?.copyOf() ?: ByteArray(0),
                elapsedMs = elapsedMs(startedNs),
                resultCount = results.size,
                errorType = firstError?.type?.name,
                errorMessage = firstError?.message,
            )
        } catch (t: Throwable) {
            CameraQrDecodeResult(
                payload = ByteArray(0),
                elapsedMs = elapsedMs(startedNs),
                resultCount = 0,
                errorType = t.javaClass.simpleName,
                errorMessage = t.message,
            )
        }
    }

    private fun elapsedMs(startedNs: Long): Double =
        (System.nanoTime() - startedNs) / 1_000_000.0
}
