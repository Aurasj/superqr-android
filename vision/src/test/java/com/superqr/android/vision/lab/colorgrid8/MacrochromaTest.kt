package com.superqr.android.vision.lab.colorgrid8

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.CRC32

class MacrochromaTest {

    @Test
    fun testCleanTileEncodeDecode() {
        val payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { (it * 7 + 13).toByte() }
        val tile = MacrochromaTile(tileIndex = 258, frameIndex = 15, payload = payload, isParity = false)

        val encoded = MacrochromaCodec.encodeTile(tile)
        assertEquals(MacrochromaCodec.TILE_RAW_BYTES, encoded.size)

        val decoded = MacrochromaCodec.decodeTile(encoded)
        assertNotNull(decoded)
        assertEquals(258, decoded!!.tileIndex)
        assertEquals(15, decoded.frameIndex)
        assertEquals(false, decoded.isParity)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testCorruptLuma2ByteInnerFecRecovery() {
        val payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { (it * 11 + 3).toByte() }
        val tile = MacrochromaTile(tileIndex = 10, frameIndex = 5, payload = payload, isParity = false)

        val encoded = MacrochromaCodec.encodeTile(tile)

        // Corrupt 2 bytes in payload
        encoded[20] = (encoded[20].toInt() xor 0x55).toByte()
        encoded[80] = (encoded[80].toInt() xor 0xAA).toByte()

        val decoded = MacrochromaCodec.decodeTile(encoded)
        assertNotNull(decoded)
        assertEquals(10, decoded!!.tileIndex)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testCorrupt3BytesRejection() {
        val payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { (it * 17 + 1).toByte() }
        val tile = MacrochromaTile(tileIndex = 7, frameIndex = 8, payload = payload, isParity = false)

        val encoded = MacrochromaCodec.encodeTile(tile)

        // Corrupt 3 bytes (beyond 2-error capacity)
        encoded[5] = (encoded[5].toInt() xor 0x11).toByte()
        encoded[50] = (encoded[50].toInt() xor 0x22).toByte()
        encoded[100] = (encoded[100].toInt() xor 0x33).toByte()

        val decoded = MacrochromaCodec.decodeTile(encoded)
        // Must fail safely without releasing corrupt payload
        assertNull(decoded)
    }

    @Test
    fun testOuterFecIndependentPerTileRecovery() {
        // Requirement 6: Frame A missing tile 3, Frame B missing tile 7, shared parity available -> both recovered independently
        val numDataTiles = 300
        val parityCount = 20

        // Frame A
        val frameATiles = (0 until numDataTiles).map { i ->
            MacrochromaTile(
                tileIndex = i,
                frameIndex = 0,
                payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { j -> ((i * 13 + j + 1) and 0xFF).toByte() }
            )
        }
        val frameAParities = MacrochromaCodec.generateSpatialParity(frameATiles, parityCount, frameIndex = 0)

        // Frame B
        val frameBTiles = (0 until numDataTiles).map { i ->
            MacrochromaTile(
                tileIndex = i,
                frameIndex = 1,
                payload = ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { j -> ((i * 29 + j + 7) and 0xFF).toByte() }
            )
        }
        val frameBParities = MacrochromaCodec.generateSpatialParity(frameBTiles, parityCount, frameIndex = 1)

        val session = MacrochromaCodec.MacrochromaTransferSession(
            dataTilesPerFrame = numDataTiles,
            parityTilesPerFrame = parityCount,
            totalDataFrames = 2
        )

        // Feed Frame A (missing tile 3, but with parity tiles)
        val survivingA = frameATiles.filter { it.tileIndex != 3 } + frameAParities
        session.acceptFrameTiles(survivingA)

        // Feed Frame B (missing tile 7, but with parity tiles)
        val survivingB = frameBTiles.filter { it.tileIndex != 7 } + frameBParities
        session.acceptFrameTiles(survivingB)

        assertTrue(session.isFrameComplete(0))
        assertTrue(session.isFrameComplete(1))
        assertTrue(session.isComplete())

        val reassembled = session.reassembleBytes()
        assertNotNull(reassembled)

        // Verify Frame A tile 3 was accurately restored
        val frameAExpected = ByteArray(numDataTiles * MacrochromaCodec.TILE_PAYLOAD_BYTES)
        for (i in 0 until numDataTiles) {
            System.arraycopy(frameATiles[i].payload, 0, frameAExpected, i * MacrochromaCodec.TILE_PAYLOAD_BYTES, MacrochromaCodec.TILE_PAYLOAD_BYTES)
        }
        val frameBExpected = ByteArray(numDataTiles * MacrochromaCodec.TILE_PAYLOAD_BYTES)
        for (i in 0 until numDataTiles) {
            System.arraycopy(frameBTiles[i].payload, 0, frameBExpected, i * MacrochromaCodec.TILE_PAYLOAD_BYTES, MacrochromaCodec.TILE_PAYLOAD_BYTES)
        }
        val expectedTotal = frameAExpected + frameBExpected
        assertArrayEquals(expectedTotal, reassembled)
    }

    @Test
    fun testRollingShutterMixedFrameSalvage() {
        // Requirement 5: Top portion contains Frame N, Bottom portion contains Frame N+1
        val numDataTiles = 300
        val parityCount = 20
        val frameN = 4
        val frameNPlus1 = 5

        val frameNTiles = (0 until numDataTiles).map { i ->
            MacrochromaTile(i, frameN, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { j -> ((frameN * 53 + i * 7 + j) and 0xFF).toByte() })
        }
        val frameNPlus1Tiles = (0 until numDataTiles).map { i ->
            MacrochromaTile(i, frameNPlus1, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { j -> ((frameNPlus1 * 53 + i * 7 + j) and 0xFF).toByte() })
        }

        val yMeans = ByteArray(480 * 388)
        val uMeans = ByteArray(480 * 388)
        val vMeans = ByteArray(480 * 388)

        // Write header for frame N
        writeSyntheticHeader(yMeans, 480, frameN, MacrochromaCodec.VERSION)

        // Top 8 tile rows (ty = 0..7, tiles 0..159) from Frame N
        val mapN = frameNTiles.associateBy { it.tileIndex }
        for (ty in 0 until 8) {
            for (tx in 0 until 20) {
                val tIdx = ty * 20 + tx
                val tile = mapN[tIdx] ?: continue
                writeSyntheticTile(yMeans, uMeans, vMeans, tile, tx, ty)
            }
        }

        // Bottom 8 tile rows (ty = 8..15, tiles 160..319) from Frame N+1
        val mapNPlus1 = frameNPlus1Tiles.associateBy { it.tileIndex }
        for (ty in 8 until 16) {
            for (tx in 0 until 20) {
                val tIdx = ty * 20 + tx
                val tile = mapNPlus1[tIdx] ?: continue
                writeSyntheticTile(yMeans, uMeans, vMeans, tile, tx, ty)
            }
        }

        val processor = ColorGrid8FrameProcessor()
        val profile = ColorGrid8Profile(480, 388, 30, version = MacrochromaCodec.VERSION)
        val result = processor.processFromGpuCellMeans(profile, yMeans, uMeans, vMeans)

        val tiles = result.analysis?.macrochromaTiles
        assertNotNull(tiles)
        assertTrue(tiles!!.size >= 300)

        val session = MacrochromaCodec.MacrochromaTransferSession(
            dataTilesPerFrame = numDataTiles,
            parityTilesPerFrame = parityCount,
            totalDataFrames = 10
        )
        session.acceptFrameTiles(tiles)

        // Verify Frame N got top tiles without contamination
        for (tIdx in 0 until 160) {
            val decoded = tiles.find { it.frameIndex == frameN && it.tileIndex == tIdx }
            assertNotNull(decoded)
            assertArrayEquals(frameNTiles[tIdx].payload, decoded!!.payload)
        }

        // Verify Frame N+1 got bottom tiles without contamination
        for (tIdx in 160 until 300) {
            val decoded = tiles.find { it.frameIndex == frameNPlus1 && it.tileIndex == tIdx }
            assertNotNull(decoded)
            assertArrayEquals(frameNPlus1Tiles[tIdx].payload, decoded!!.payload)
        }
    }

    @Test
    fun testFull10MbRuntimeEndToEndWithInjectedFailures() {
        // Requirement 1 & 2: Full 10 MiB source file (10,485,760 bytes)
        val sourceSize = 10 * 1024 * 1024 // 10,485,760 bytes
        val dataTilesPerFrame = 300
        val parityTilesPerFrame = 20
        val carouselBlockSize = 12
        val bytesPerFrame = dataTilesPerFrame * MacrochromaCodec.TILE_PAYLOAD_BYTES // 51,600 bytes
        val totalDataFrames = (sourceSize + bytesPerFrame - 1) / bytesPerFrame // 204 frames
        val paddedSize = totalDataFrames * bytesPerFrame // 10,526,400 bytes

        // Generate deterministic 10 MiB source data
        val sourceBytes = ByteArray(paddedSize) { i ->
            if (i < sourceSize) ((i * 179 + 43) and 0xFF).toByte() else 0.toByte()
        }
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(sourceBytes.copyOfRange(0, sourceSize)).joinToString("") { "%02x".format(it) }
        val expectedCrc32 = CRC32().apply { update(sourceBytes, 0, sourceSize) }.value

        val processor = ColorGrid8FrameProcessor()
        val session = MacrochromaCodec.MacrochromaTransferSession(
            dataTilesPerFrame = dataTilesPerFrame,
            parityTilesPerFrame = parityTilesPerFrame,
            totalDataFrames = totalDataFrames,
            carouselBlockSize = carouselBlockSize,
        )
        val profile = ColorGrid8Profile(480, 388, 30, version = MacrochromaCodec.VERSION)

        val startTime = System.currentTimeMillis()
        var totalTilesProcessed = 0

        // Create all data frames
        val allDataFrames = (0 until totalDataFrames).map { fIdx ->
            val fOffset = fIdx * bytesPerFrame
            (0 until dataTilesPerFrame).map { tIdx ->
                val tOffset = fOffset + tIdx * MacrochromaCodec.TILE_PAYLOAD_BYTES
                val p = sourceBytes.copyOfRange(tOffset, tOffset + MacrochromaCodec.TILE_PAYLOAD_BYTES)
                MacrochromaTile(tileIndex = tIdx, frameIndex = fIdx, payload = p, isParity = false)
            }
        }

        // Transmit each data frame through synthetic GPU cell means
        for (fIdx in 0 until totalDataFrames) {
            val dataTiles = allDataFrames[fIdx]
            val parities = MacrochromaCodec.generateSpatialParity(dataTiles, parityTilesPerFrame, frameIndex = fIdx)
            val allTiles = dataTiles + parities

            val yMeans = ByteArray(480 * 388)
            val uMeans = ByteArray(480 * 388)
            val vMeans = ByteArray(480 * 388)

            writeSyntheticHeader(yMeans, 480, fIdx, MacrochromaCodec.VERSION)

            val tilesMap = allTiles.associateBy { it.tileIndex }
            for (ty in 0 until 16) {
                for (tx in 0 until 20) {
                    val tIdx = ty * 20 + tx
                    val tile = tilesMap[tIdx] ?: continue
                    writeSyntheticTile(yMeans, uMeans, vMeans, tile, tx, ty)
                }
            }

            // Injected failure scenario 1: Drop whole Frame 39 (to test Carousel Inter-Frame Recovery)
            if (fIdx == 39) {
                // Completely skip sending frame 39
                continue
            }

            // Injected failure scenario 2: 1-byte and 2-byte RS errors in frames 10, 50, 100, 150
            if (fIdx in listOf(10, 50, 100, 150)) {
                // Corrupt 2 bytes in tile 15
                val mbY = 1
                val mbX = 2
                val topR = 4 + (15 / 20) * 24 + mbY * 2
                val leftC = (15 % 20) * 24 + mbX * 2
                yMeans[topR * 480 + leftC] = (yMeans[topR * 480 + leftC].toInt() xor 0xFF).toByte()
                yMeans[(topR + 1) * 480 + leftC + 1] = (yMeans[(topR + 1) * 480 + leftC + 1].toInt() xor 0xFF).toByte()
            }

            // Injected failure scenario 3: 5 missing tiles in frame 25 (spatial parity will recover)
            if (fIdx == 25) {
                for (erasedIdx in listOf(4, 18, 55, 120, 250)) {
                    val ty = erasedIdx / 20
                    val tx = erasedIdx % 20
                    for (r in 0 until 24) {
                        for (c in 0 until 24) {
                            yMeans[(4 + ty * 24 + r) * 480 + (tx * 24 + c)] = 0
                        }
                    }
                }
            }

            val processResult = processor.processFromGpuCellMeans(profile, yMeans, uMeans, vMeans)
            if (processResult.headerStatus == "VALID" && processResult.analysis?.macrochromaTiles != null) {
                val tiles = processResult.analysis!!.macrochromaTiles!!
                totalTilesProcessed += tiles.size
                session.acceptFrameTiles(tiles)
            }
        }

        // Transmit Carousel Parity Frames (for each block of 12 frames)
        val numCarouselBlocks = (totalDataFrames + carouselBlockSize - 1) / carouselBlockSize
        for (b in 0 until numCarouselBlocks) {
            val startF = b * carouselBlockSize
            val endF = minOf(totalDataFrames, (b + 1) * carouselBlockSize)
            val blockTiles = allDataFrames.subList(startF, endF)
            val carouselFrameIdx = totalDataFrames + b
            val carouselTiles = MacrochromaCodec.generateCarouselParityFrame(blockTiles, carouselFrameIdx, dataTilesPerFrame)

            val yMeans = ByteArray(480 * 388)
            val uMeans = ByteArray(480 * 388)
            val vMeans = ByteArray(480 * 388)

            writeSyntheticHeader(yMeans, 480, carouselFrameIdx, MacrochromaCodec.VERSION)
            val tilesMap = carouselTiles.associateBy { it.tileIndex }
            for (ty in 0 until 16) {
                for (tx in 0 until 20) {
                    val tIdx = ty * 20 + tx
                    val tile = tilesMap[tIdx] ?: continue
                    writeSyntheticTile(yMeans, uMeans, vMeans, tile, tx, ty)
                }
            }

            val processResult = processor.processFromGpuCellMeans(profile, yMeans, uMeans, vMeans)
            if (processResult.analysis?.macrochromaTiles != null) {
                val tiles = processResult.analysis!!.macrochromaTiles!!
                totalTilesProcessed += tiles.size
                session.acceptFrameTiles(tiles)
            }
        }

        val elapsedMs = System.currentTimeMillis() - startTime

        // Frame 39 must be completely restored via Carousel Recovery!
        assertTrue(session.isFrameComplete(39))
        assertTrue(session.isComplete())

        val reassembled = session.reassembleBytes()
        assertNotNull(reassembled)

        val actualSourcePortion = reassembled!!.copyOfRange(0, sourceSize)
        val actualCrc32 = CRC32().apply { update(actualSourcePortion) }.value
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(actualSourcePortion).joinToString("") { "%02x".format(it) }

        assertEquals(expectedCrc32, actualCrc32)
        assertEquals(expectedSha256, actualSha256)

        println("=== 10 MiB OFFLINE RUNTIME BENCHMARK ===")
        println("Source Size: $sourceSize bytes (10.00 MiB)")
        println("Total Optical Frames: ${totalDataFrames + numCarouselBlocks}")
        println("Data Frames: $totalDataFrames, Carousel Parity Frames: $numCarouselBlocks")
        println("Total Tiles Processed: $totalTilesProcessed")
        println("Spatial FEC Recoveries: ${session.spatialRecoveriesCount}")
        println("Carousel Inter-Frame Recoveries: ${session.carouselRecoveriesCount}")
        println("Elapsed Time: ${elapsedMs} ms")
        println("CRC32 Exact Match: $expectedCrc32 == $actualCrc32")
        println("SHA-256 Exact Match: $actualSha256")
    }

    @Test
    fun testUnrecoverableCorruptionRejection() {
        // Requirement 2: Deliberately unrecoverable corruption must not produce complete file
        val totalDataFrames = 12
        val dataTilesPerFrame = 300
        val session = MacrochromaCodec.MacrochromaTransferSession(
            dataTilesPerFrame = dataTilesPerFrame,
            parityTilesPerFrame = 20,
            totalDataFrames = totalDataFrames,
            carouselBlockSize = 12
        )

        // Provide valid frames for 0..9, but omit frame 10 and 11, and no carousel parity
        for (f in 0 until 10) {
            val tiles = (0 until dataTilesPerFrame).map { t ->
                MacrochromaTile(t, f, ByteArray(MacrochromaCodec.TILE_PAYLOAD_BYTES) { 1 }, isParity = false)
            }
            session.acceptFrameTiles(tiles)
        }

        // Must NOT be complete
        assertTrue(!session.isComplete())
        assertNull(session.reassembleBytes())
    }

    private fun writeSyntheticHeader(yMeans: ByteArray, cols: Int, frameIndex: Int, version: Int) {
        val headerRaw = byteArrayOf(
            (MacrochromaCodec.HEADER_MAGIC shr 8).toByte(),
            (MacrochromaCodec.HEADER_MAGIC and 0xFF).toByte(),
            ((version shl 4) or ((frameIndex shr 8) and 0x0F)).toByte(),
            (frameIndex and 0xFF).toByte(),
            0, 0, 0, 1 // sessionId = 1
        )
        val headerCrc = MacrochromaCodec.crc16Ccitt(headerRaw)
        val headerPayload = headerRaw + byteArrayOf((headerCrc shr 8).toByte(), (headerCrc and 0xFF).toByte())
        for (r in 0 until 4) {
            for (c in 0 until cols) {
                val bitIdx = c % (headerPayload.size * 8)
                val bit = (headerPayload[bitIdx / 8].toInt() shr (7 - (bitIdx % 8))) and 1
                yMeans[r * cols + c] = if (bit == 1) 220.toByte() else 30.toByte()
            }
        }
    }

    private fun writeSyntheticTile(
        yMeans: ByteArray,
        uMeans: ByteArray,
        vMeans: ByteArray,
        tile: MacrochromaTile,
        tx: Int,
        ty: Int
    ) {
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

                yMeans[topR * 480 + leftC] = lumaToVal(y00)
                yMeans[topR * 480 + leftC + 1] = lumaToVal(y01)
                yMeans[(topR + 1) * 480 + leftC] = lumaToVal(y10)
                yMeans[(topR + 1) * 480 + leftC + 1] = lumaToVal(y11)

                val (uVal, vVal) = when (chroma) {
                    0 -> 90.toByte() to 180.toByte()  // Red (U<128, V>=128)
                    1 -> 90.toByte() to 80.toByte()   // Green (U<128, V<128)
                    2 -> 180.toByte() to 80.toByte()  // Blue (U>=128, V<128)
                    else -> 180.toByte() to 180.toByte() // Magenta (U>=128, V>=128)
                }
                uMeans[(topR / 2) * 240 + (leftC / 2)] = uVal
                vMeans[(topR / 2) * 240 + (leftC / 2)] = vVal
            }
        }
    }
}
