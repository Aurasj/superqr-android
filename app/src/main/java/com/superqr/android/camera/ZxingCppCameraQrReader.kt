package com.superqr.android.camera

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

data class CameraQrSymbol(
    val payload: ByteArray,
    val quad: List<DoubleArray>? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
)

data class CameraQrDecodeResult(
    val payload: ByteArray,
    val elapsedMs: Double,
    val resultCount: Int,
    val quad: List<DoubleArray>? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val symbols: List<CameraQrSymbol> = emptyList(),
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
 * Phase 0 advanced PHY uses at most four spatial QR lanes. One native read returns
 * all visible symbols; legacy callers keep receiving the first successful symbol.
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
            maxNumberOfSymbols = 4,
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
            val qrResults = results.filter { it.format == BarcodeReader.Format.QR_CODE }
            val symbols = qrResults.map { result ->
                val quad = result.position?.let { position ->
                    listOf(
                        doubleArrayOf(position.topLeft.x.toDouble(), position.topLeft.y.toDouble()),
                        doubleArrayOf(position.topRight.x.toDouble(), position.topRight.y.toDouble()),
                        doubleArrayOf(position.bottomRight.x.toDouble(), position.bottomRight.y.toDouble()),
                        doubleArrayOf(position.bottomLeft.x.toDouble(), position.bottomLeft.y.toDouble()),
                    )
                }
                CameraQrSymbol(
                    payload = result.bytes?.copyOf() ?: ByteArray(0),
                    quad = quad,
                    errorType = result.error?.type?.name,
                    errorMessage = result.error?.message,
                )
            }
            val success = symbols.firstOrNull { it.payload.isNotEmpty() && it.errorType == null }
            val geometry = success ?: symbols.firstOrNull()
            CameraQrDecodeResult(
                payload = success?.payload?.copyOf() ?: ByteArray(0),
                elapsedMs = elapsedMs(startedNs),
                resultCount = results.size,
                quad = geometry?.quad?.map { it.copyOf() },
                errorType = geometry?.errorType,
                errorMessage = geometry?.errorMessage,
                symbols = symbols,
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
