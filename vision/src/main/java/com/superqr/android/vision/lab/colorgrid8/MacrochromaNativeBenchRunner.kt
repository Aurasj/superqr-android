package com.superqr.android.vision.lab.colorgrid8

import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.roundToInt

object MacrochromaNativeBenchRunner {
    private const val TAG = "ColorGrid8NativeBench"

    data class BenchmarkReport(
        val device: String,
        val abi: String,
        val nativeLoaded: Boolean,
        val realJniInvoked: Boolean,
        val framesExecutedNative: Int,
        val kotlinFallbackCount: Int,
        val cleanFrameParity: Boolean,
        val rs1ByteParity: Boolean,
        val rs2ByteParity: Boolean,
        val rs3ByteRejection: Boolean,
        val missingTileRecovery: Boolean,
        val damagedHeaderDetection: Boolean,
        val mixedFrameSalvage: Boolean,
        val fullSha256Match: Boolean,
        val expectedSha256: String,
        val actualSha256: String,
        val nativeP50Ms: Double,
        val nativeP95Ms: Double,
        val tilesPerSec: Double,
        val framesPerSec: Double,
        val totalValidTiles: Int,
        val totalRsCorrections: Int,
        val totalFailedTiles: Int,
        val logDetails: List<String>,
    )

    fun runOnDeviceBenchmark(): BenchmarkReport {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add(msg)
            try { Log.i(TAG, msg) } catch (_: Throwable) { println(msg) }
        }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val nativeLoaded = ColorGrid8NativeDecoder.isNativeLoaded

        log("=== MACROCHROMA ON-DEVICE NATIVE VALIDATION ===")
        log("Device: $deviceModel")
        log("ABI: $abi")
        log("Native Loaded: $nativeLoaded (Error: ${ColorGrid8NativeDecoder.loadError ?: "none"})")

        val scratch = ColorGrid8NativeDecoder.NativeDecoderScratch()
        var framesExecutedNative = 0
        var kotlinFallbackCount = 0

        // -------------------------------------------------------------
        // Case 1: Clean Frame Parity
        // -------------------------------------------------------------
        val cleanTiles = (0 until 300).map { i ->
            MacrochromaTile(i, 0, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { j -> ((i * 17 + j + 5) and 0xFF).toByte() })
        }
        val cleanParities = MacrochromaCodec.generateSpatialParity(cleanTiles, 20, 0)
        val (cleanY, cleanU, cleanV) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 0)

        val cleanNative = if (nativeLoaded) {
            scratch.decodeMacrochroma(cleanY, cleanU, cleanV, 480, 388)
        } else null

        if (cleanNative != null) framesExecutedNative++ else kotlinFallbackCount++

        val cleanFrameParity = if (cleanNative != null) {
            cleanNative.headerValid && cleanNative.validTiles == 320 && cleanNative.tiles.size == 320 &&
                    cleanNative.tiles.take(300).indices.all { i ->
                        cleanNative.tiles[i].payload.contentEquals(cleanTiles[i].payload)
                    }
        } else false
        log("Case 1 (Clean Frame Parity): ${if (cleanFrameParity) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 2: 1-Byte RS Error Correction
        // -------------------------------------------------------------
        val (rs1Y, rs1U, rs1V) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 1)
        // Corrupt 1 luma cell in tile 10
        val topR1 = 4 + (10 / 20) * 24 + 2
        val leftC1 = (10 % 20) * 24 + 4
        rs1Y[topR1 * 480 + leftC1] = (rs1Y[topR1 * 480 + leftC1].toInt() xor 0xFF).toByte()

        val rs1Native = if (nativeLoaded) scratch.decodeMacrochroma(rs1Y, rs1U, rs1V, 480, 388) else null
        if (rs1Native != null) framesExecutedNative++ else kotlinFallbackCount++

        val rs1ByteParity = if (rs1Native != null) {
            val t10 = rs1Native.tiles.find { it.tileIndex == 10 }
            t10 != null && t10.payload.contentEquals(cleanTiles[10].payload)
        } else false
        log("Case 2 (RS 1-Byte Correction): ${if (rs1ByteParity) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 3: 2-Byte RS Error Correction
        // -------------------------------------------------------------
        val (rs2Y, rs2U, rs2V) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 2)
        // Corrupt 2 distinct macroblocks in tile 20
        val topR2a = 4 + (20 / 20) * 24 + 2
        val leftC2a = (20 % 20) * 24 + 4
        val topR2b = 4 + (20 / 20) * 24 + 10
        val leftC2b = (20 % 20) * 24 + 12
        rs2Y[topR2a * 480 + leftC2a] = (rs2Y[topR2a * 480 + leftC2a].toInt() xor 0xFF).toByte()
        rs2Y[topR2b * 480 + leftC2b] = (rs2Y[topR2b * 480 + leftC2b].toInt() xor 0xFF).toByte()

        val rs2Native = if (nativeLoaded) scratch.decodeMacrochroma(rs2Y, rs2U, rs2V, 480, 388) else null
        if (rs2Native != null) framesExecutedNative++ else kotlinFallbackCount++

        val rs2ByteParity = if (rs2Native != null) {
            val t20 = rs2Native.tiles.find { it.tileIndex == 20 }
            t20 != null && t20.payload.contentEquals(cleanTiles[20].payload)
        } else false
        log("Case 3 (RS 2-Byte Correction): ${if (rs2ByteParity) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 4: 3-Byte RS Error Rejection
        // -------------------------------------------------------------
        val (rs3Y, rs3U, rs3V) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 3)
        // Corrupt 3 macroblocks in tile 30 (beyond t=2)
        val t30_r = 4 + (30 / 20) * 24
        val t30_c = (30 % 20) * 24
        rs3Y[t30_r * 480 + t30_c] = (rs3Y[t30_r * 480 + t30_c].toInt() xor 0xFF).toByte()
        rs3Y[(t30_r + 4) * 480 + t30_c + 6] = (rs3Y[(t30_r + 4) * 480 + t30_c + 6].toInt() xor 0xFF).toByte()
        rs3Y[(t30_r + 8) * 480 + t30_c + 12] = (rs3Y[(t30_r + 8) * 480 + t30_c + 12].toInt() xor 0xFF).toByte()

        val rs3Native = if (nativeLoaded) scratch.decodeMacrochroma(rs3Y, rs3U, rs3V, 480, 388) else null
        if (rs3Native != null) framesExecutedNative++ else kotlinFallbackCount++

        val rs3ByteRejection = if (rs3Native != null) {
            rs3Native.tiles.none { it.tileIndex == 30 }
        } else false
        log("Case 4 (RS 3-Byte Rejection): ${if (rs3ByteRejection) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 5: Missing Tile / Spatial Recovery
        // -------------------------------------------------------------
        val (misY, misU, misV) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 4)
        // Erase tile 5 completely
        val t5_r = 4 + (5 / 20) * 24
        val t5_c = (5 % 20) * 24
        for (r in 0 until 24) {
            for (c in 0 until 24) {
                misY[(t5_r + r) * 480 + (t5_c + c)] = 0
            }
        }
        val misNative = if (nativeLoaded) scratch.decodeMacrochroma(misY, misU, misV, 480, 388) else null
        if (misNative != null) framesExecutedNative++ else kotlinFallbackCount++

        val sessionSpatial = MacrochromaCodec.MacrochromaTransferSession(300, 20, 1)
        if (misNative != null) sessionSpatial.acceptFrameTiles(misNative.tiles)
        val missingTileRecovery = sessionSpatial.isComplete()
        log("Case 5 (Missing Tile Spatial Recovery): ${if (missingTileRecovery) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 6: Damaged Header Detection
        // -------------------------------------------------------------
        val (badHdrY, badHdrU, badHdrV) = buildSyntheticBuffers(cleanTiles + cleanParities, frameIndex = 5)
        for (r in 0 until 4) {
            for (c in 0 until 480) badHdrY[r * 480 + c] = 128.toByte() // Invalidate all 4 header rows
        }

        val badHdrNative = if (nativeLoaded) scratch.decodeMacrochroma(badHdrY, badHdrU, badHdrV, 480, 388) else null
        if (badHdrNative != null) framesExecutedNative++ else kotlinFallbackCount++
        val damagedHeaderDetection = if (badHdrNative != null) !badHdrNative.headerValid else false
        log("Case 6 (Damaged Header Detection): ${if (damagedHeaderDetection) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 7: Mixed Rolling-Shutter Frame Salvage
        // -------------------------------------------------------------
        val frameA = (0 until 300).map { i -> MacrochromaTile(i, 8, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { (8 * 31 + i + it).toByte() }) }
        val frameB = (0 until 300).map { i -> MacrochromaTile(i, 9, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { (9 * 31 + i + it).toByte() }) }
        val (rsY, rsU, rsV) = buildMixedRollingShutterBuffers(frameA, frameB, frameN = 8, frameNPlus1 = 9)

        val rsNative = if (nativeLoaded) scratch.decodeMacrochroma(rsY, rsU, rsV, 480, 388) else null
        if (rsNative != null) framesExecutedNative++ else kotlinFallbackCount++

        val mixedFrameSalvage = if (rsNative != null) {
            val hasTopA = (0 until 160).all { idx ->
                val t = rsNative.tiles.find { it.frameIndex == 8 && it.tileIndex == idx }
                t != null && t.payload.contentEquals(frameA[idx].payload)
            }
            val hasBottomB = (160 until 300).all { idx ->
                val t = rsNative.tiles.find { it.frameIndex == 9 && it.tileIndex == idx }
                t != null && t.payload.contentEquals(frameB[idx].payload)
            }
            hasTopA && hasBottomB
        } else false
        log("Case 7 (Mixed Rolling-Shutter Salvage): ${if (mixedFrameSalvage) "PASS" else "FAIL"}")

        // -------------------------------------------------------------
        // Case 8: Long-Run 221-Frame 10 MiB Stream Execution
        // -------------------------------------------------------------
        log("Starting 221-Frame 10 MiB Stream Benchmark...")
        val sourceSize = 10 * 1024 * 1024
        val bytesPerFrame = 300 * MacrochromaCodec.TILE_PAYLOAD_BYTES // 51,600
        val totalDataFrames = (sourceSize + bytesPerFrame - 1) / bytesPerFrame // 204
        val paddedSize = totalDataFrames * bytesPerFrame

        val sourceBytes = ByteArray(paddedSize) { i ->
            if (i < sourceSize) ((i * 179 + 43) and 0xFF).toByte() else 0.toByte()
        }
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(sourceBytes.copyOfRange(0, sourceSize)).joinToString("") { "%02x".format(it) }

        val allDataFrames = (0 until totalDataFrames).map { fIdx ->
            val fOffset = fIdx * bytesPerFrame
            (0 until 300).map { tIdx ->
                val tOffset = fOffset + tIdx * MacrochromaCodec.TILE_PAYLOAD_BYTES
                val p = sourceBytes.copyOfRange(tOffset, tOffset + MacrochromaCodec.TILE_PAYLOAD_BYTES)
                MacrochromaTile(tileIndex = tIdx, frameIndex = fIdx, payload = p, isParity = false)
            }
        }

        val session10Mb = MacrochromaCodec.MacrochromaTransferSession(
            dataTilesPerFrame = 300,
            parityTilesPerFrame = 20,
            totalDataFrames = totalDataFrames,
            carouselBlockSize = 12,
        )

        val timingsList = mutableListOf<Double>()
        var totalValidTiles = 0
        var totalRsCorrections = 0
        var totalFailedTiles = 0

        val streamStart = System.nanoTime()

        // 204 Data Frames
        for (fIdx in 0 until totalDataFrames) {
            if (fIdx == 39) {
                // Drop whole frame 39 for carousel recovery
                continue
            }

            val dataTiles = allDataFrames[fIdx]
            val parities = MacrochromaCodec.generateSpatialParity(dataTiles, 20, fIdx)
            val (yBuf, uBuf, vBuf) = buildSyntheticBuffers(dataTiles + parities, frameIndex = fIdx)

            if (fIdx in listOf(10, 50, 100, 150)) {
                // Inject 2-byte RS error in tile 15
                val topR = 4 + (15 / 20) * 24 + 2
                val leftC = (15 % 20) * 24 + 4
                yBuf[topR * 480 + leftC] = (yBuf[topR * 480 + leftC].toInt() xor 0xFF).toByte()
                yBuf[(topR + 1) * 480 + leftC + 1] = (yBuf[(topR + 1) * 480 + leftC + 1].toInt() xor 0xFF).toByte()
            }

            val decoded = if (nativeLoaded) scratch.decodeMacrochroma(yBuf, uBuf, vBuf, 480, 388) else null
            if (decoded != null) {
                framesExecutedNative++
                timingsList.add(decoded.nativeTotalMs)
                totalValidTiles += decoded.validTiles
                totalRsCorrections += decoded.rsCorrectedTiles
                totalFailedTiles += decoded.failedTiles
                session10Mb.acceptFrameTiles(decoded.tiles, decoded.rsCorrectedTiles, decoded.failedTiles)
            } else {
                kotlinFallbackCount++
                val kt = MacrochromaCodec.decodeFrameFromCellMeans(yBuf, uBuf, vBuf, 480, 388)
                session10Mb.acceptFrameTiles(kt.tiles, kt.rsCorrectedTiles, kt.failedTiles)
            }
        }

        // 17 Carousel Parity Frames
        val numCarouselBlocks = (totalDataFrames + 12 - 1) / 12
        for (b in 0 until numCarouselBlocks) {
            val startF = b * 12
            val endF = minOf(totalDataFrames, (b + 1) * 12)
            val blockTiles = allDataFrames.subList(startF, endF)
            val carouselFrameIdx = totalDataFrames + b
            val carouselTiles = MacrochromaCodec.generateCarouselParityFrame(blockTiles, carouselFrameIdx, 300)
            val (yBuf, uBuf, vBuf) = buildSyntheticBuffers(carouselTiles, frameIndex = carouselFrameIdx)

            val decoded = if (nativeLoaded) scratch.decodeMacrochroma(yBuf, uBuf, vBuf, 480, 388) else null
            if (decoded != null) {
                framesExecutedNative++
                timingsList.add(decoded.nativeTotalMs)
                totalValidTiles += decoded.validTiles
                session10Mb.acceptFrameTiles(decoded.tiles)
            } else {
                kotlinFallbackCount++
                val kt = MacrochromaCodec.decodeFrameFromCellMeans(yBuf, uBuf, vBuf, 480, 388)
                session10Mb.acceptFrameTiles(kt.tiles)
            }
        }

        val totalStreamDurationMs = (System.nanoTime() - streamStart) / 1_000_000.0
        val isComplete = session10Mb.isComplete()
        val reassembled = session10Mb.reassembleBytes()
        val actualSha256 = if (reassembled != null) {
            val src = reassembled.copyOfRange(0, sourceSize)
            MessageDigest.getInstance("SHA-256").digest(src).joinToString("") { "%02x".format(it) }
        } else "INCOMPLETE"

        val fullSha256Match = (isComplete && reassembled != null && actualSha256 == expectedSha256)

        // Calculate Percentiles & Throughput
        timingsList.sort()
        val p50 = if (timingsList.isNotEmpty()) timingsList[(timingsList.size * 0.50).toInt()] else 0.0
        val p95 = if (timingsList.isNotEmpty()) timingsList[(timingsList.size * 0.95).toInt()] else 0.0
        val totalTiles = totalValidTiles + totalFailedTiles
        val tilesPerSec = if (totalStreamDurationMs > 0) (totalTiles / (totalStreamDurationMs / 1000.0)) else 0.0
        val framesPerSec = if (totalStreamDurationMs > 0) (timingsList.size / (totalStreamDurationMs / 1000.0)) else 0.0

        log("10 MiB Stream Completed: isComplete=$isComplete, SHA Match=$fullSha256Match")
        log("Frames Executed Native: $framesExecutedNative, Fallback: $kotlinFallbackCount")
        log("Native Timings: p50 = ${"%.2f".format(p50)} ms, p95 = ${"%.2f".format(p95)} ms")
        log("Decode Throughput: ${"%.1f".format(framesPerSec)} frames/sec, ${"%.1f".format(tilesPerSec)} tiles/sec")

        return BenchmarkReport(
            device = deviceModel,
            abi = abi,
            nativeLoaded = nativeLoaded,
            realJniInvoked = framesExecutedNative > 0,
            framesExecutedNative = framesExecutedNative,
            kotlinFallbackCount = kotlinFallbackCount,
            cleanFrameParity = cleanFrameParity,
            rs1ByteParity = rs1ByteParity,
            rs2ByteParity = rs2ByteParity,
            rs3ByteRejection = rs3ByteRejection,
            missingTileRecovery = missingTileRecovery,
            damagedHeaderDetection = damagedHeaderDetection,
            mixedFrameSalvage = mixedFrameSalvage,
            fullSha256Match = fullSha256Match,
            expectedSha256 = expectedSha256,
            actualSha256 = actualSha256,
            nativeP50Ms = p50,
            nativeP95Ms = p95,
            tilesPerSec = tilesPerSec,
            framesPerSec = framesPerSec,
            totalValidTiles = totalValidTiles,
            totalRsCorrections = totalRsCorrections,
            totalFailedTiles = totalFailedTiles,
            logDetails = logs,
        )
    }

    private fun buildSyntheticBuffers(tiles: List<MacrochromaTile>, frameIndex: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(480 * 388)
        val u = ByteArray(480 * 388)
        val v = ByteArray(480 * 388)

        // 12-bit header
        val headerRaw = byteArrayOf(
            (MacrochromaCodec.HEADER_MAGIC shr 8).toByte(),
            (MacrochromaCodec.HEADER_MAGIC and 0xFF).toByte(),
            ((MacrochromaCodec.VERSION shl 4) or ((frameIndex shr 8) and 0x0F)).toByte(),
            (frameIndex and 0xFF).toByte(),
            0, 0, 0, 1
        )
        val headerCrc = MacrochromaCodec.crc16Ccitt(headerRaw)
        val headerPayload = headerRaw + byteArrayOf((headerCrc shr 8).toByte(), (headerCrc and 0xFF).toByte())
        for (r in 0 until 4) {
            for (c in 0 until 480) {
                val bitIdx = c % (headerPayload.size * 8)
                val bit = (headerPayload[bitIdx / 8].toInt() shr (7 - (bitIdx % 8))) and 1
                y[r * 480 + c] = if (bit == 1) 220.toByte() else 30.toByte()
            }
        }

        val map = tiles.associateBy { it.tileIndex }
        for (ty in 0 until 16) {
            for (tx in 0 until 20) {
                val tIdx = ty * 20 + tx
                val tile = map[tIdx] ?: continue
                writeSyntheticTile(y, u, v, tile, tx, ty)
            }
        }
        return Triple(y, u, v)
    }

    private fun buildMixedRollingShutterBuffers(
        frameA: List<MacrochromaTile>,
        frameB: List<MacrochromaTile>,
        frameN: Int,
        frameNPlus1: Int
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(480 * 388)
        val u = ByteArray(480 * 388)
        val v = ByteArray(480 * 388)

        val headerRaw = byteArrayOf(
            (MacrochromaCodec.HEADER_MAGIC shr 8).toByte(),
            (MacrochromaCodec.HEADER_MAGIC and 0xFF).toByte(),
            ((MacrochromaCodec.VERSION shl 4) or ((frameN shr 8) and 0x0F)).toByte(),
            (frameN and 0xFF).toByte(),
            0, 0, 0, 1
        )
        val headerCrc = MacrochromaCodec.crc16Ccitt(headerRaw)
        val headerPayload = headerRaw + byteArrayOf((headerCrc shr 8).toByte(), (headerCrc and 0xFF).toByte())
        for (r in 0 until 4) {
            for (c in 0 until 480) {
                val bitIdx = c % (headerPayload.size * 8)
                val bit = (headerPayload[bitIdx / 8].toInt() shr (7 - (bitIdx % 8))) and 1
                y[r * 480 + c] = if (bit == 1) 220.toByte() else 30.toByte()
            }
        }

        val mapA = frameA.associateBy { it.tileIndex }
        for (ty in 0 until 8) {
            for (tx in 0 until 20) {
                val tIdx = ty * 20 + tx
                val tile = mapA[tIdx] ?: continue
                writeSyntheticTile(y, u, v, tile, tx, ty)
            }
        }

        val mapB = frameB.associateBy { it.tileIndex }
        for (ty in 8 until 16) {
            for (tx in 0 until 20) {
                val tIdx = ty * 20 + tx
                val tile = mapB[tIdx] ?: continue
                writeSyntheticTile(y, u, v, tile, tx, ty)
            }
        }
        return Triple(y, u, v)
    }

    private fun writeSyntheticTile(y: ByteArray, u: ByteArray, v: ByteArray, tile: MacrochromaTile, tx: Int, ty: Int) {
        val encoded = MacrochromaCodec.encodeTile(tile)
        var bitBuf = 0
        var bitsInBuf = 0
        var byteIdx = 0

        for (mbY in 0 until 12) {
            for (mbX in 0 until 12) {
                while (bitsInBuf < 10 && byteIdx < encoded.size) {
                    bitBuf = (bitBuf shl 8) or (encoded[byteIdx].toInt() and 0xFF)
                    bitsInBuf += 8
                    byteIdx++
                }
                bitsInBuf -= 10
                val sym = (bitBuf shr bitsInBuf) and 0x3FF
                val y00 = (sym shr 8) and 3
                val y01 = (sym shr 6) and 3
                val y10 = (sym shr 4) and 3
                val y11 = (sym shr 2) and 3
                val chroma = sym and 3

                val topR = 4 + ty * 24 + mbY * 2
                val leftC = tx * 24 + mbX * 2

                fun lumaToVal(lvl: Int): Byte = when (lvl) {
                    0 -> 40.toByte()
                    1 -> 95.toByte()
                    2 -> 150.toByte()
                    else -> 210.toByte()
                }

                y[topR * 480 + leftC] = lumaToVal(y00)
                y[topR * 480 + leftC + 1] = lumaToVal(y01)
                y[(topR + 1) * 480 + leftC] = lumaToVal(y10)
                y[(topR + 1) * 480 + leftC + 1] = lumaToVal(y11)

                val (uVal, vVal) = when (chroma) {
                    0 -> 90.toByte() to 180.toByte()  // Red (U<128, V>=128)
                    1 -> 90.toByte() to 80.toByte()   // Green (U<128, V<128)
                    2 -> 180.toByte() to 80.toByte()  // Blue (U>=128, V<128)
                    else -> 180.toByte() to 180.toByte() // Magenta (U>=128, V>=128)
                }
                u[(topR / 2) * 240 + (leftC / 2)] = uVal
                v[(topR / 2) * 240 + (leftC / 2)] = vVal
            }
        }
    }
}
