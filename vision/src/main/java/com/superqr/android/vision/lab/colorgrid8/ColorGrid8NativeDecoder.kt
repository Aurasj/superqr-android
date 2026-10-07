package com.superqr.android.vision.lab.colorgrid8

import org.opencv.core.Point

object ColorGrid8NativeDecoder {
    var loadError: String? = null
    val isNativeLoaded: Boolean = try {
        System.loadLibrary("colorgrid8_native")
        try { android.util.Log.i("ColorGrid8Native", "libcolorgrid8_native.so loaded successfully!") } catch (_: Throwable) {}
        true
    } catch (e: Throwable) {
        loadError = "${e::class.java.simpleName}: ${e.message}"
        try { android.util.Log.e("ColorGrid8Native", "Failed to load libcolorgrid8_native.so: $e") } catch (_: Throwable) {}
        false
    }

    data class NativeTimings(
        val headerMs: Double,
        val pilotMs: Double,
        val payloadMs: Double,
        val nativeTotalMs: Double,
        val jniTotalMs: Double,
    )

    data class NativeDecodedFrame(
        val success: Boolean,
        val stage: ColorGrid8Stage,
        val header: ColorGrid8Header?,
        val classifiedSymbols: Int,
        val erasures: Int,
        val contrast: Double,
        val lumaThreshold: Double,
        val pilotMinUvDistance: Double,
        val centroids: List<ColorGrid8Centroid>,
        val payloadSymbols: ByteArray,
        val timings: NativeTimings,
    )

    @JvmStatic
    external fun nativeDecodeFrame(
        yBuffer: Any,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: Any,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: Any,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        quadX: FloatArray,
        quadY: FloatArray,
        profileCols: Int,
        profileRows: Int,
        profileFps: Int,
        profileSeed: Int,
        profileVersion: Int,
        profileId: Int,
        lumaErasureFraction: Float,
        chromaMarginThreshold: Float,
        fiducialOffsetCells: Int,
        outPayloadSymbols: ByteArray,
        outTimings: DoubleArray,
        outIntStats: IntArray,
        outFloatStats: FloatArray,
        outCentroids: FloatArray,
    ): Boolean

    @JvmStatic
    external fun nativeDecodeFromCellMeans(
        cellMeansY: ByteArray,
        cellMeansU: ByteArray,
        cellMeansV: ByteArray,
        profileCols: Int,
        profileRows: Int,
        profileFps: Int,
        profileSeed: Int,
        profileVersion: Int,
        profileId: Int,
        lumaErasureFraction: Float,
        chromaMarginThreshold: Float,
        outPayloadSymbols: ByteArray,
        outTimings: DoubleArray,
        outIntStats: IntArray,
        outFloatStats: FloatArray,
        outCentroids: FloatArray,
    ): Boolean

    @JvmStatic
    external fun nativeDecodeMacrochromaCellMeans(
        cellMeansY: Any,
        cellMeansU: Any,
        cellMeansV: Any,
        cols: Int,
        rows: Int,
        outTileIndices: IntArray,
        outFrameIndices: IntArray,
        outParityFlags: ByteArray,
        outValidFlags: ByteArray,
        outRsCorrectedFlags: ByteArray,
        outPayloadBytes: ByteArray,
        outTimings: DoubleArray,
        outIntStats: IntArray,
    ): Boolean

    @JvmStatic
    external fun nativeSampleHeader(
        yBuffer: Any,
        yRowStride: Int,
        yPixelStride: Int,
        width: Int,
        height: Int,
        quadX: FloatArray,
        quadY: FloatArray,
        profileCols: Int,
        profileRows: Int,
        fiducialOffsetCells: Int,
        outHeaderLuma: ByteArray,
    ): Boolean

    data class NativeDecodedMacrochromaFrame(
        val headerValid: Boolean,
        val magic: Int,
        val version: Int,
        val frameIndex: Int,
        val sessionId: Long,
        val validTiles: Int,
        val rsCorrectedTiles: Int,
        val failedTiles: Int,
        val tiles: List<MacrochromaTile>,
        val headerMs: Double,
        val decodeMs: Double,
        val nativeTotalMs: Double,
        val jniTotalMs: Double,
    )

    class NativeDecoderScratch(val maxPayloadCells: Int = 384 * 336) {
        val payloadBuffer = ByteArray(maxPayloadCells)
        val timingsBuffer = DoubleArray(5)
        val intStatsBuffer = IntArray(10)
        val floatStatsBuffer = FloatArray(4)
        val centroidsBuffer = FloatArray(32)
        val quadXBuffer = FloatArray(4)
        val quadYBuffer = FloatArray(4)

        fun decode(
            yBuffer: Any,
            yRowStride: Int,
            yPixelStride: Int,
            uBuffer: Any,
            uRowStride: Int,
            uPixelStride: Int,
            vBuffer: Any,
            vRowStride: Int,
            vPixelStride: Int,
            width: Int,
            height: Int,
            quad: Array<Point>,
            profile: ColorGrid8Profile,
            lumaErasureFraction: Float = 0.08f,
            chromaMarginThreshold: Float = 0.10f,
            fiducialOffsetCells: Int = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS,
        ): NativeDecodedFrame? {
            if (!isNativeLoaded) return null

            for (i in 0 until 4) {
                quadXBuffer[i] = quad[i].x.toFloat()
                quadYBuffer[i] = quad[i].y.toFloat()
            }

            val success = nativeDecodeFrame(
                yBuffer = yBuffer,
                yRowStride = yRowStride,
                yPixelStride = yPixelStride,
                uBuffer = uBuffer,
                uRowStride = uRowStride,
                uPixelStride = uPixelStride,
                vBuffer = vBuffer,
                vRowStride = vRowStride,
                vPixelStride = vPixelStride,
                width = width,
                height = height,
                quadX = quadXBuffer,
                quadY = quadYBuffer,
                profileCols = profile.cols,
                profileRows = profile.rows,
                profileFps = profile.fps,
                profileSeed = profile.seed,
                profileVersion = profile.version,
                profileId = profile.profileId,
                lumaErasureFraction = lumaErasureFraction,
                chromaMarginThreshold = chromaMarginThreshold,
                fiducialOffsetCells = fiducialOffsetCells,
                outPayloadSymbols = payloadBuffer,
                outTimings = timingsBuffer,
                outIntStats = intStatsBuffer,
                outFloatStats = floatStatsBuffer,
                outCentroids = centroidsBuffer,
            )

            val stageIdx = intStatsBuffer[0]
            val stage = when (stageIdx) {
                4 -> ColorGrid8Stage.HEADER
                5 -> ColorGrid8Stage.PILOTS
                6 -> ColorGrid8Stage.PAYLOAD
                else -> ColorGrid8Stage.CAMERA
            }

            val headerValid = intStatsBuffer[6] != 0
            val header = if (headerValid) {
                ColorGrid8Header(
                    profileId = intStatsBuffer[7],
                    fps = intStatsBuffer[8],
                    frameIndex = intStatsBuffer[9],
                    seed = profile.seed,
                    version = profile.version,
                )
            } else null

            val centroids = List(8) { i ->
                ColorGrid8Centroid(
                    y = centroidsBuffer[i * 4 + 0].toDouble(),
                    u = centroidsBuffer[i * 4 + 1].toDouble(),
                    v = centroidsBuffer[i * 4 + 2].toDouble(),
                    sigmaUv = centroidsBuffer[i * 4 + 3].toDouble(),
                    count = 0,
                )
            }

            val symbols = if (success) {
                payloadBuffer.copyOf(profile.payloadCells)
            } else ByteArray(0)

            return NativeDecodedFrame(
                success = success,
                stage = stage,
                header = header,
                classifiedSymbols = intStatsBuffer[2],
                erasures = intStatsBuffer[3],
                contrast = floatStatsBuffer[0].toDouble(),
                lumaThreshold = floatStatsBuffer[1].toDouble(),
                pilotMinUvDistance = floatStatsBuffer[2].toDouble(),
                centroids = centroids,
                payloadSymbols = symbols,
                timings = NativeTimings(
                    headerMs = timingsBuffer[0],
                    pilotMs = timingsBuffer[1],
                    payloadMs = timingsBuffer[2],
                    nativeTotalMs = timingsBuffer[3],
                    jniTotalMs = timingsBuffer[4],
                )
            )
        }

        fun decodeFromCellMeans(
            cellMeansY: ByteArray,
            cellMeansU: ByteArray,
            cellMeansV: ByteArray,
            profile: ColorGrid8Profile,
            lumaErasureFraction: Float = 0.08f,
            chromaMarginThreshold: Float = 0.10f,
        ): NativeDecodedFrame? {
            if (!isNativeLoaded) return null

            val success = nativeDecodeFromCellMeans(
                cellMeansY = cellMeansY,
                cellMeansU = cellMeansU,
                cellMeansV = cellMeansV,
                profileCols = profile.cols,
                profileRows = profile.rows,
                profileFps = profile.fps,
                profileSeed = profile.seed,
                profileVersion = profile.version,
                profileId = profile.profileId,
                lumaErasureFraction = lumaErasureFraction,
                chromaMarginThreshold = chromaMarginThreshold,
                outPayloadSymbols = payloadBuffer,
                outTimings = timingsBuffer,
                outIntStats = intStatsBuffer,
                outFloatStats = floatStatsBuffer,
                outCentroids = centroidsBuffer,
            )

            val stageIdx = intStatsBuffer[0]
            val stage = when (stageIdx) {
                4 -> ColorGrid8Stage.HEADER
                5 -> ColorGrid8Stage.PILOTS
                6 -> ColorGrid8Stage.PAYLOAD
                else -> ColorGrid8Stage.CAMERA
            }

            val headerValid = intStatsBuffer[6] != 0
            val header = if (headerValid) {
                ColorGrid8Header(
                    profileId = intStatsBuffer[7],
                    fps = intStatsBuffer[8],
                    frameIndex = intStatsBuffer[9],
                    seed = profile.seed,
                    version = profile.version,
                )
            } else null

            val centroids = List(8) { i ->
                ColorGrid8Centroid(
                    y = centroidsBuffer[i * 4 + 0].toDouble(),
                    u = centroidsBuffer[i * 4 + 1].toDouble(),
                    v = centroidsBuffer[i * 4 + 2].toDouble(),
                    sigmaUv = centroidsBuffer[i * 4 + 3].toDouble(),
                    count = 0,
                )
            }

            val symbols = if (success) {
                payloadBuffer.copyOf(profile.payloadCells)
            } else ByteArray(0)

            return NativeDecodedFrame(
                success = success,
                stage = stage,
                header = header,
                classifiedSymbols = intStatsBuffer[2],
                erasures = intStatsBuffer[3],
                contrast = floatStatsBuffer[0].toDouble(),
                lumaThreshold = floatStatsBuffer[1].toDouble(),
                pilotMinUvDistance = floatStatsBuffer[2].toDouble(),
                centroids = centroids,
                payloadSymbols = symbols,
                timings = NativeTimings(
                    headerMs = timingsBuffer[0],
                    pilotMs = timingsBuffer[1],
                    payloadMs = timingsBuffer[2],
                    nativeTotalMs = timingsBuffer[3],
                    jniTotalMs = timingsBuffer[4],
                )
            )
        }

        // Macrochroma C1 preallocated buffers
        private val macroTileIndices = IntArray(320)
        private val macroFrameIndices = IntArray(320)
        private val macroParityFlags = ByteArray(320)
        private val macroValidFlags = ByteArray(320)
        private val macroRsCorrectedFlags = ByteArray(320)
        private val macroPayloadBytes = ByteArray(320 * MacrochromaCodec.TILE_PAYLOAD_BYTES)
        private val macroTimings = DoubleArray(4)
        private val macroIntStats = IntArray(8)

        fun decodeMacrochroma(
            cellMeansY: ByteArray,
            cellMeansU: ByteArray,
            cellMeansV: ByteArray,
            cols: Int = 480,
            rows: Int = 388,
        ): NativeDecodedMacrochromaFrame? {
            if (!isNativeLoaded) return null

            val success = nativeDecodeMacrochromaCellMeans(
                cellMeansY = cellMeansY,
                cellMeansU = cellMeansU,
                cellMeansV = cellMeansV,
                cols = cols,
                rows = rows,
                outTileIndices = macroTileIndices,
                outFrameIndices = macroFrameIndices,
                outParityFlags = macroParityFlags,
                outValidFlags = macroValidFlags,
                outRsCorrectedFlags = macroRsCorrectedFlags,
                outPayloadBytes = macroPayloadBytes,
                outTimings = macroTimings,
                outIntStats = macroIntStats,
            )

            val headerValid = macroIntStats[0] != 0
            val magic = macroIntStats[1]
            val version = macroIntStats[2]
            val frameIndex = macroIntStats[3]
            val sessionId = macroIntStats[4].toLong() and 0xFFFFFFFFL
            val validTiles = macroIntStats[5]
            val rsCorrectedTiles = macroIntStats[6]
            val failedTiles = macroIntStats[7]

            val tilesList = mutableListOf<MacrochromaTile>()
            for (i in 0 until 320) {
                if (macroValidFlags[i].toInt() != 0) {
                    val tileIdx = macroTileIndices[i]
                    val fIdx = macroFrameIndices[i]
                    val isParity = macroParityFlags[i].toInt() != 0
                    val payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES)
                    System.arraycopy(
                        macroPayloadBytes,
                        i * MacrochromaCodec.TILE_PAYLOAD_BYTES,
                        payload,
                        0,
                        MacrochromaCodec.TILE_PAYLOAD_BYTES
                    )
                    tilesList.add(MacrochromaTile(tileIdx, fIdx, payload, isParity))
                }
            }

            return NativeDecodedMacrochromaFrame(
                headerValid = headerValid,
                magic = magic,
                version = version,
                frameIndex = frameIndex,
                sessionId = sessionId,
                validTiles = validTiles,
                rsCorrectedTiles = rsCorrectedTiles,
                failedTiles = failedTiles,
                tiles = tilesList,
                headerMs = macroTimings[0],
                decodeMs = macroTimings[1],
                nativeTotalMs = macroTimings[2],
                jniTotalMs = macroTimings[3],
            )
        }
    }
}
