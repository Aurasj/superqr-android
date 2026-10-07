package com.superqr.android.vision.lab.colorgrid8

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MacrochromaTile(
    val tileIndex: Int,
    val frameIndex: Int,
    val payload: ByteArray,
    val isParity: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MacrochromaTile) return false
        return tileIndex == other.tileIndex &&
                frameIndex == other.frameIndex &&
                isParity == other.isParity &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = tileIndex
        result = 31 * result + frameIndex
        result = 31 * result + isParity.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object MacrochromaCodec {
    const val SCHEMA = "superqr.macrochroma.transfer.v4"
    const val VERSION = 4
    const val HEADER_MAGIC = 0xC8F

    const val FINE_LUMA_LEVELS = 4
    const val FINE_LUMA_BITS = 2
    const val MACRO_CHROMA_STATES = 4
    const val MACRO_CHROMA_BITS = 2
    const val MACRO_BLOCK_SIZE = 2

    const val BITS_PER_MACROBLOCK = 10
    const val TILE_FINE_W = 24
    const val TILE_FINE_H = 24
    const val TILE_MACRO_W = TILE_FINE_W / MACRO_BLOCK_SIZE // 12
    const val TILE_MACRO_H = TILE_FINE_H / MACRO_BLOCK_SIZE // 12
    const val TILE_MACROBLOCKS = TILE_MACRO_W * TILE_MACRO_H // 144
    const val TILE_RAW_BITS = TILE_MACROBLOCKS * BITS_PER_MACROBLOCK // 1440 bits = 180 bytes
    const val TILE_RAW_BYTES = TILE_RAW_BITS / 8 // 180 bytes

    const val TILE_HEADER_BYTES = 2
    const val TILE_CRC_BYTES = 2
    const val TILE_INNER_FEC_BYTES = 4
    const val TILE_PAYLOAD_BYTES = TILE_RAW_BYTES - TILE_HEADER_BYTES - TILE_CRC_BYTES - TILE_INNER_FEC_BYTES // 172 bytes

    // 16-State sRGB Palette (4 Luma levels x 4 Chroma quadrant hues)
    val PALETTE_RGB = intArrayOf(
        0x501410, 0x104814, 0x102488, 0x441460, // L0: Dark (R, G, B, M)
        0xA84820, 0x208C28, 0x2064D4, 0x8C30BC, // L1: Dim  (R, G, B, M)
        0xE88C40, 0x40C848, 0x48AEFA, 0xD868FA, // L2: Bright (R, G, B, M)
        0xFACE90, 0x98FAA0, 0x90E4FA, 0xFAB0FA  // L3: High (R, G, B, M)
    )

    // GF(256) Tables
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)
    val rsGen: IntArray

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfExp[i + 255] = x
            gfLog[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) {
                x = x xor 0x11D
            }
        }
        gfLog[0] = 0

        // Compute RS generator polynomial for 4 parity symbols
        var g = intArrayOf(1)
        for (k in 1..TILE_INNER_FEC_BYTES) {
            val root = gfExp[k]
            val nextG = IntArray(g.size + 1)
            for (i in g.indices) {
                nextG[i] = gfAdd(nextG[i], g[i])
                nextG[i + 1] = gfAdd(nextG[i + 1], gfMul(g[i], root))
            }
            g = nextG
        }
        rsGen = g
    }

    fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a and 0xFF] + gfLog[b and 0xFF]]
    }

    fun gfAdd(a: Int, b: Int): Int = a xor b

    fun gfInv(a: Int): Int {
        if (a == 0) throw ArithmeticException("GF(256) division by zero")
        return gfExp[255 - gfLog[a and 0xFF]]
    }

    fun crc16Ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until (offset + length)) {
            val byte = data[i].toInt() and 0xFF
            crc = crc xor (byte shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    fun rsEncode(msg: ByteArray, nSym: Int = TILE_INNER_FEC_BYTES): ByteArray {
        val parity = IntArray(nSym)
        for (b in msg) {
            val byteVal = b.toInt() and 0xFF
            val feedback = byteVal xor parity[0]
            for (j in 0 until nSym - 1) {
                parity[j] = parity[j + 1]
            }
            parity[nSym - 1] = 0
            if (feedback != 0) {
                for (j in 0 until nSym) {
                    parity[j] = parity[j] xor gfMul(feedback, rsGen[j + 1])
                }
            }
        }
        val out = ByteArray(nSym)
        for (j in 0 until nSym) {
            out[j] = (parity[j] and 0xFF).toByte()
        }
        return out
    }

    fun rsCalcSyndromes(codeword: ByteArray, nSym: Int = TILE_INNER_FEC_BYTES): IntArray {
        val n = codeword.size
        val syndromes = IntArray(nSym)
        for (j in 0 until nSym) {
            var valSum = 0
            val expJ = j + 1
            for (i in 0 until n) {
                val byteVal = codeword[i].toInt() and 0xFF
                val power = ((n - 1 - i) * expJ) % 255
                valSum = valSum xor gfMul(byteVal, gfExp[power])
            }
            syndromes[j] = valSum
        }
        return syndromes
    }

    fun berlekampMassey(syndromes: IntArray, nSym: Int = TILE_INNER_FEC_BYTES): IntArray {
        var c = intArrayOf(1)
        var b = intArrayOf(1)
        var l = 0
        var m = 1
        for (k in 0 until nSym) {
            var d = syndromes[k]
            for (i in 1..l) {
                if (i < c.size) {
                    d = d xor gfMul(c[i], syndromes[k - i])
                }
            }
            if (d == 0) {
                m++
            } else {
                val t = c.copyOf()
                val scale = d
                val pad = IntArray(m)
                val scaledB = pad + b.map { gfMul(scale, it) }.toIntArray()
                val newC = IntArray(maxOf(c.size, scaledB.size))
                System.arraycopy(c, 0, newC, 0, c.size)
                for (i in scaledB.indices) {
                    newC[i] = newC[i] xor scaledB[i]
                }
                c = newC
                if (2 * l <= k) {
                    l = k + 1 - l
                    val invD = gfInv(d)
                    b = t.map { gfMul(it, invD) }.toIntArray()
                    m = 1
                } else {
                    m++
                }
            }
        }
        return c
    }

    fun chienSearch(lambdaPoly: IntArray, n: Int): IntArray? {
        var deg = lambdaPoly.size - 1
        var pLen = lambdaPoly.size
        while (pLen > 1 && lambdaPoly[pLen - 1] == 0) {
            pLen--
        }
        deg = pLen - 1
        if (deg == 0) return IntArray(0)

        val errorPositions = mutableListOf<Int>()
        for (i in 0 until n) {
            val p = n - 1 - i
            val invX = gfExp[(255 - (p % 255)) % 255]
            var sumVal = 0
            var term = 1
            for (j in 0 until pLen) {
                sumVal = sumVal xor gfMul(lambdaPoly[j], term)
                term = gfMul(term, invX)
            }
            if (sumVal == 0) {
                errorPositions.add(i)
            }
        }
        if (errorPositions.size != deg) return null
        return errorPositions.toIntArray()
    }

    fun forneyAlgorithm(syndromes: IntArray, lambdaPoly: IntArray, errorPositions: IntArray, n: Int): IntArray? {
        val omega = IntArray(syndromes.size)
        for (i in syndromes.indices) {
            for (j in lambdaPoly.indices) {
                if (i - j >= 0) {
                    omega[i] = omega[i] xor gfMul(syndromes[i - j], lambdaPoly[j])
                }
            }
        }
        val lambdaPrime = IntArray(lambdaPoly.size)
        for (i in 1 until lambdaPoly.size step 2) {
            lambdaPrime[i - 1] = lambdaPoly[i]
        }
        val magnitudes = IntArray(errorPositions.size)
        for (idx in errorPositions.indices) {
            val pos = errorPositions[idx]
            val p = n - 1 - pos
            val invX = gfExp[(255 - (p % 255)) % 255]
            var omegaVal = 0
            var term = 1
            for (coeff in omega) {
                omegaVal = omegaVal xor gfMul(coeff, term)
                term = gfMul(term, invX)
            }
            var lambdaPrimeVal = 0
            term = 1
            for (coeff in lambdaPrime) {
                lambdaPrimeVal = lambdaPrimeVal xor gfMul(coeff, term)
                term = gfMul(term, invX)
            }
            if (lambdaPrimeVal == 0) return null
            magnitudes[idx] = gfMul(omegaVal, gfInv(lambdaPrimeVal))
        }
        return magnitudes
    }

    fun rsDecode(raw: ByteArray, erasures: IntArray = IntArray(0)): ByteArray? {
        val n = raw.size
        val nSym = TILE_INNER_FEC_BYTES
        if (erasures.size > nSym) return null

        val syndromes = rsCalcSyndromes(raw, nSym)
        if (syndromes.all { it == 0 } && erasures.isEmpty()) {
            return raw.copyOf()
        }

        val errLambda = berlekampMassey(syndromes, nSym)
        val allPositions = chienSearch(errLambda, n) ?: return null
        val magnitudes = forneyAlgorithm(syndromes, errLambda, allPositions, n) ?: return null

        val corrected = raw.copyOf()
        for (i in allPositions.indices) {
            val pos = allPositions[i]
            corrected[pos] = (corrected[pos].toInt() xor magnitudes[i]).toByte()
        }

        val postSyndromes = rsCalcSyndromes(corrected, nSym)
        if (!postSyndromes.all { it == 0 }) return null
        return corrected
    }

    fun encodeTile(tile: MacrochromaTile): ByteArray {
        require(tile.payload.size == TILE_PAYLOAD_BYTES) { "Invalid payload size: ${tile.payload.size}" }
        val out = ByteArray(TILE_RAW_BYTES)
        out[0] = (tile.tileIndex and 0xFF).toByte()
        out[1] = (((tile.tileIndex shr 8) and 0x01) or
                ((tile.frameIndex and 0x3F) shl 1) or
                (if (tile.isParity) 0x80 else 0)).toByte()
        System.arraycopy(tile.payload, 0, out, TILE_HEADER_BYTES, TILE_PAYLOAD_BYTES)

        val crc = crc16Ccitt(out, 0, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES)
        out[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES] = ((crc ushr 8) and 0xFF).toByte()
        out[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1] = (crc and 0xFF).toByte()

        val parity = rsEncode(out.copyOfRange(0, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES), TILE_INNER_FEC_BYTES)
        System.arraycopy(parity, 0, out, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES, TILE_INNER_FEC_BYTES)
        return out
    }

    fun decodeTile(raw: ByteArray, erasures: IntArray = IntArray(0)): MacrochromaTile? {
        if (raw.size != TILE_RAW_BYTES) return null
        val corrected = rsDecode(raw, erasures) ?: return null

        val bodyLen = TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES
        val expectedCrc = ((corrected[bodyLen].toInt() and 0xFF) shl 8) or
                (corrected[bodyLen + 1].toInt() and 0xFF)
        if (crc16Ccitt(corrected, 0, bodyLen) != expectedCrc) return null

        val b0 = corrected[0].toInt() and 0xFF
        val b1 = corrected[1].toInt() and 0xFF
        val tileIndex = b0 or ((b1 and 0x01) shl 8)
        val frameIndex = (b1 shr 1) and 0x3F
        val isParity = (b1 and 0x80) != 0
        val payload = corrected.copyOfRange(TILE_HEADER_BYTES, bodyLen)
        return MacrochromaTile(tileIndex, frameIndex, payload, isParity)
    }

    fun generateSpatialParity(
        dataTiles: List<MacrochromaTile>,
        parityCount: Int,
        frameIndex: Int
    ): List<MacrochromaTile> {
        val k = dataTiles.size
        val mPerBlock = parityCount / 2
        val out = mutableListOf<MacrochromaTile>()
        for (b in 0 until 2) {
            val blockData = (b until k step 2).map { dataTiles[it] }
            for (pIdx in 0 until mPerBlock) {
                val parityPayload = ByteArray(TILE_PAYLOAD_BYTES)
                for (i in blockData.indices) {
                    val coeff = gfInv(pIdx xor (16 + i))
                    val dt = blockData[i].payload
                    for (byteI in 0 until TILE_PAYLOAD_BYTES) {
                        parityPayload[byteI] = (parityPayload[byteI].toInt() xor gfMul(dt[byteI].toInt() and 0xFF, coeff)).toByte()
                    }
                }
                val globalPIdx = b * mPerBlock + pIdx
                out.add(
                    MacrochromaTile(
                        tileIndex = k + globalPIdx,
                        frameIndex = frameIndex,
                        payload = parityPayload,
                        isParity = true,
                    )
                )
            }
        }
        return out
    }

    fun recoverSpatialErasedTiles(
        receivedTiles: List<MacrochromaTile>,
        dataTilesCount: Int = 300,
        parityTilesCount: Int = 20,
        frameIndex: Int = 0
    ): List<MacrochromaTile> {
        val byIdx = mutableMapOf<Int, MacrochromaTile>()
        for (t in receivedTiles) {
            if (t.frameIndex == frameIndex) {
                byIdx[t.tileIndex] = t
            }
        }
        val missingAll = (0 until dataTilesCount).filter { it !in byIdx }
        if (missingAll.isEmpty()) {
            return (0 until dataTilesCount).mapNotNull { byIdx[it] }
        }

        val mPerBlock = parityTilesCount / 2

        for (b in 0 until 2) {
            val blockMissing = missingAll.filter { it % 2 == b }.map { it / 2 }
            if (blockMissing.isEmpty()) continue

            val availParities = mutableListOf<Pair<Int, MacrochromaTile>>()
            for (p in 0 until mPerBlock) {
                val gPidx = dataTilesCount + b * mPerBlock + p
                val tile = byIdx[gPidx]
                if (tile != null) {
                    availParities.add(Pair(p, tile))
                }
            }

            if (blockMissing.size <= availParities.size) {
                val numMissing = blockMissing.size
                val usedParities = availParities.subList(0, numMissing)

                val a = Array(numMissing) { IntArray(numMissing) }
                val bArr = Array(numMissing) { ByteArray(TILE_PAYLOAD_BYTES) }

                val kBlock = (dataTilesCount + 1 - b) / 2

                for (r in 0 until numMissing) {
                    val pIdx = usedParities[r].first
                    val pTile = usedParities[r].second
                    System.arraycopy(pTile.payload, 0, bArr[r], 0, TILE_PAYLOAD_BYTES)

                    for (i in 0 until kBlock) {
                        val globalI = 2 * i + b
                        if (i !in blockMissing && globalI in byIdx) {
                            val coeff = gfInv(pIdx xor (16 + i))
                            val src = byIdx[globalI]!!.payload
                            for (byteI in 0 until TILE_PAYLOAD_BYTES) {
                                bArr[r][byteI] = (bArr[r][byteI].toInt() xor gfMul(src[byteI].toInt() and 0xFF, coeff)).toByte()
                            }
                        }
                    }
                    for (c in 0 until numMissing) {
                        val mIdx = blockMissing[c]
                        a[r][c] = gfInv(pIdx xor (16 + mIdx))
                    }
                }

                // Gaussian elimination over GF(256)
                for (i in 0 until numMissing) {
                    var pivot = i
                    while (pivot < numMissing && a[pivot][i] == 0) {
                        pivot++
                    }
                    if (pivot == numMissing) break
                    if (pivot != i) {
                        val tempA = a[i]
                        a[i] = a[pivot]
                        a[pivot] = tempA
                        val tempB = bArr[i]
                        bArr[i] = bArr[pivot]
                        bArr[pivot] = tempB
                    }

                    val invPivot = gfInv(a[i][i])
                    for (j in i until numMissing) {
                        a[i][j] = gfMul(a[i][j], invPivot)
                    }
                    for (byteI in 0 until TILE_PAYLOAD_BYTES) {
                        bArr[i][byteI] = (gfMul(bArr[i][byteI].toInt() and 0xFF, invPivot)).toByte()
                    }

                    for (r in 0 until numMissing) {
                        if (r != i && a[r][i] != 0) {
                            val factor = a[r][i]
                            for (j in i until numMissing) {
                                a[r][j] = a[r][j] xor gfMul(factor, a[i][j])
                            }
                            for (byteI in 0 until TILE_PAYLOAD_BYTES) {
                                bArr[r][byteI] = (bArr[r][byteI].toInt() xor gfMul(factor, bArr[i][byteI].toInt() and 0xFF)).toByte()
                            }
                        }
                    }
                }

                for (c in 0 until numMissing) {
                    val globalIdx = 2 * blockMissing[c] + b
                    byIdx[globalIdx] = MacrochromaTile(globalIdx, frameIndex, bArr[c].copyOf(), isParity = false)
                }
            }
        }

        return (0 until dataTilesCount).mapNotNull { byIdx[it] }
    }

    data class DecodedFrameResult(
        val headerValid: Boolean,
        val frameIndex: Int,
        val sessionId: Long,
        val validTiles: Int,
        val rsCorrectedTiles: Int,
        val failedTiles: Int,
        val tiles: List<MacrochromaTile>,
    )

    fun decodeFrameFromCellMeans(
        cellMeansY: ByteArray,
        cellMeansU: ByteArray,
        cellMeansV: ByteArray,
        cols: Int = 480,
        rows: Int = 388,
    ): DecodedFrameResult {
        if (cols != 480 || rows != 388) {
            return DecodedFrameResult(false, 0, 0L, 0, 0, 0, emptyList())
        }

        // 1. Majority-vote Header (10 bytes = 80 bits)
        val headerBitLen = 80
        val headerBytes = ByteArray(10)
        for (bitIdx in 0 until headerBitLen) {
            var votes = 0
            for (r in 0 until 4) {
                var col = bitIdx
                while (col < cols) {
                    val y = cellMeansY[r * cols + col].toInt() and 0xFF
                    if (y > 128) votes++ else votes--
                    col += headerBitLen
                }
            }
            if (votes > 0) {
                headerBytes[bitIdx / 8] = (headerBytes[bitIdx / 8].toInt() or (1 shl (7 - (bitIdx % 8)))).toByte()
            }
        }

        val magic = ((headerBytes[0].toInt() and 0xFF) shl 8) or (headerBytes[1].toInt() and 0xFF)
        val verFlag = headerBytes[2].toInt() and 0xFF
        val ver = (verFlag shr 4) and 0x0F
        val frameIdxHigh = verFlag and 0x0F
        val frameIdxLow = headerBytes[3].toInt() and 0xFF
        val frameIdx = (frameIdxHigh shl 8) or frameIdxLow
        val sessId = ((headerBytes[4].toLong() and 0xFF) shl 24) or
                ((headerBytes[5].toLong() and 0xFF) shl 16) or
                ((headerBytes[6].toLong() and 0xFF) shl 8) or
                (headerBytes[7].toLong() and 0xFF)
        val expectedCrc = ((headerBytes[8].toInt() and 0xFF) shl 8) or (headerBytes[9].toInt() and 0xFF)
        val calcCrc = crc16Ccitt(headerBytes, 0, 8)
        val headerValid = (magic == HEADER_MAGIC && ver == VERSION && expectedCrc == calcCrc)

        // 2. Decode Tiles
        val tilesX = cols / TILE_FINE_W // 20
        val tilesY = (rows - 4) / TILE_FINE_H // 16
        val tilesList = mutableListOf<MacrochromaTile>()
        var validCount = 0
        var rsCount = 0
        var failedCount = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val rawTile = ByteArray(TILE_RAW_BYTES)
                var bitBuf = 0
                var bitsInBuf = 0
                var byteOutIdx = 0

                for (mbY in 0 until TILE_MACRO_H) {
                    for (mbX in 0 until TILE_MACRO_W) {
                        val topR = 4 + ty * TILE_FINE_H + mbY * 2
                        val leftC = tx * TILE_FINE_W + mbX * 2

                        fun quantLuma(y: Int): Int = when {
                            y < 68 -> 0
                            y < 124 -> 1
                            y < 182 -> 2
                            else -> 3
                        }

                        val y00 = quantLuma(cellMeansY[topR * cols + leftC].toInt() and 0xFF)
                        val y01 = quantLuma(cellMeansY[topR * cols + leftC + 1].toInt() and 0xFF)
                        val y10 = quantLuma(cellMeansY[(topR + 1) * cols + leftC].toInt() and 0xFF)
                        val y11 = quantLuma(cellMeansY[(topR + 1) * cols + leftC + 1].toInt() and 0xFF)

                        val uVal = cellMeansU[(topR / 2) * (cols / 2) + (leftC / 2)].toInt() and 0xFF
                        val vVal = cellMeansV[(topR / 2) * (cols / 2) + (leftC / 2)].toInt() and 0xFF
                        val chroma = when {
                            uVal < 128 && vVal >= 128 -> 0 // Red
                            uVal < 128 && vVal < 128 -> 1 // Green
                            uVal >= 128 && vVal < 128 -> 2 // Blue
                            else -> 3 // Magenta
                        }

                        val sym = (y00 shl 8) or (y01 shl 6) or (y10 shl 4) or (y11 shl 2) or chroma
                        bitBuf = (bitBuf shl BITS_PER_MACROBLOCK) or sym
                        bitsInBuf += BITS_PER_MACROBLOCK

                        while (bitsInBuf >= 8 && byteOutIdx < TILE_RAW_BYTES) {
                            bitsInBuf -= 8
                            rawTile[byteOutIdx++] = ((bitBuf shr bitsInBuf) and 0xFF).toByte()
                        }
                    }
                }

                val tile = decodeTile(rawTile)
                if (tile != null) {
                    val rawMod64 = tile.frameIndex
                    var delta = rawMod64 - (frameIdx and 0x3F)
                    if (delta > 32) delta -= 64
                    else if (delta < -32) delta += 64
                    val resolvedFrameIdx = frameIdx + delta
                    val resolvedTile = if (resolvedFrameIdx == tile.frameIndex) tile else tile.copy(frameIndex = resolvedFrameIdx)
                    tilesList.add(resolvedTile)
                    validCount++
                } else {
                    failedCount++
                }
            }
        }

        return DecodedFrameResult(
            headerValid = headerValid,
            frameIndex = frameIdx,
            sessionId = sessId,
            validTiles = validCount,
            rsCorrectedTiles = rsCount,
            failedTiles = failedCount,
            tiles = tilesList,
        )
    }

    fun generateCarouselParityFrame(
        dataFramesTiles: List<List<MacrochromaTile>>,
        carouselFrameIndex: Int,
        dataTilesCount: Int = 300
    ): List<MacrochromaTile> {
        val numFrames = dataFramesTiles.size
        return (0 until dataTilesCount).map { tIdx ->
            val parityPayload = ByteArray(TILE_PAYLOAD_BYTES)
            for (f in 0 until numFrames) {
                val tile = dataFramesTiles[f].find { it.tileIndex == tIdx }
                if (tile != null) {
                    for (b in 0 until TILE_PAYLOAD_BYTES) {
                        parityPayload[b] = (parityPayload[b].toInt() xor (tile.payload[b].toInt() and 0xFF)).toByte()
                    }
                }
            }
            MacrochromaTile(
                tileIndex = tIdx,
                frameIndex = carouselFrameIndex,
                payload = parityPayload,
                isParity = true
            )
        }
    }

    data class MacrochromaMetadata(
        val filename: String,
        val mimeType: String,
        val fileSize: Long,
        val fileCrc32: Long,
        val dataOffset: Int,
        val packageBytes: Long,
    )

    class MacrochromaTransferSession(
        val dataTilesPerFrame: Int = 300,
        val parityTilesPerFrame: Int = 20,
        totalDataFrames: Int = 1,
        val carouselBlockSize: Int = 12,
    ) {
        private val rawTilesByFrame = mutableMapOf<Int, MutableMap<Int, MacrochromaTile>>()
        private val finalDataTiles = mutableMapOf<Int, MutableMap<Int, ByteArray>>()
        private val carouselParityFrames = mutableMapOf<Int, MutableMap<Int, ByteArray>>()

        var totalDataFrames: Int = totalDataFrames
            private set
        var metadata: MacrochromaMetadata? = null
            private set

        var totalValidTilesReceived = 0
            private set
        var spatialRecoveriesCount = 0
            private set
        var carouselRecoveriesCount = 0
            private set
        var rsCorrectedCount = 0
            private set
        var unrecoverableTilesCount = 0
            private set

        private fun tryParseMetadata() {
            if (metadata != null) return
            val tile0 = finalDataTiles[0]?.get(0) ?: return
            if (tile0.size < 20) return
            if (tile0[0] != 'S'.code.toByte() || tile0[1] != 'Q'.code.toByte() ||
                tile0[2] != 'P'.code.toByte() || tile0[3] != '7'.code.toByte()) return
            val buf = java.nio.ByteBuffer.wrap(tile0)
            buf.position(4)
            val fnLen = buf.short.toInt() and 0xFFFF
            val mimeLen = buf.short.toInt() and 0xFFFF
            val fSize = buf.long
            val fCrc = buf.int.toLong() and 0xFFFFFFFFL
            val dataOffset = 20 + fnLen + mimeLen
            val packageBytes = dataOffset + fSize
            val frameCap = dataTilesPerFrame * TILE_PAYLOAD_BYTES
            val neededFrames = ((packageBytes + frameCap - 1) / frameCap).toInt().coerceAtLeast(1)
            totalDataFrames = neededFrames
            val fn = if (fnLen in 1..(tile0.size - 20)) String(tile0, 20, fnLen, Charsets.UTF_8) else "macrochroma.bin"
            val mime = if (mimeLen in 1..(tile0.size - 20 - fnLen)) String(tile0, 20 + fnLen, mimeLen, Charsets.UTF_8) else "application/octet-stream"
            metadata = MacrochromaMetadata(fn, mime, fSize, fCrc, dataOffset, packageBytes)
        }

        @Synchronized
        fun acceptTile(tile: MacrochromaTile): Boolean {
            val fMap = rawTilesByFrame.getOrPut(tile.frameIndex) { mutableMapOf() }
            if (tile.tileIndex in fMap) return false
            fMap[tile.tileIndex] = tile
            totalValidTilesReceived++

            if (tile.frameIndex < totalDataFrames) {
                if (!tile.isParity && tile.tileIndex < dataTilesPerFrame) {
                    val dataMap = finalDataTiles.getOrPut(tile.frameIndex) { mutableMapOf() }
                    dataMap[tile.tileIndex] = tile.payload
                    if (tile.frameIndex == 0 && tile.tileIndex == 0) {
                        tryParseMetadata()
                    }
                }
                tryRecoverFrame(tile.frameIndex)
            } else {
                // Carousel parity frame
                val cMap = carouselParityFrames.getOrPut(tile.frameIndex) { mutableMapOf() }
                cMap[tile.tileIndex] = tile.payload
            }

            tryRecoverCarousel()
            return true
        }

        @Synchronized
        fun acceptFrameTiles(tiles: List<MacrochromaTile>, rsCorrected: Int = 0, failedTiles: Int = 0): Int {
            var accepted = 0
            rsCorrectedCount += rsCorrected
            unrecoverableTilesCount += failedTiles
            val touchedDataFrames = mutableSetOf<Int>()

            for (tile in tiles) {
                val fMap = rawTilesByFrame.getOrPut(tile.frameIndex) { mutableMapOf() }
                if (tile.tileIndex !in fMap) {
                    fMap[tile.tileIndex] = tile
                    totalValidTilesReceived++
                    accepted++
                    if (tile.frameIndex < totalDataFrames) {
                        touchedDataFrames.add(tile.frameIndex)
                        if (!tile.isParity && tile.tileIndex < dataTilesPerFrame) {
                            val dataMap = finalDataTiles.getOrPut(tile.frameIndex) { mutableMapOf() }
                            dataMap[tile.tileIndex] = tile.payload
                            if (tile.frameIndex == 0 && tile.tileIndex == 0) {
                                tryParseMetadata()
                            }
                        }
                    } else {
                        val cMap = carouselParityFrames.getOrPut(tile.frameIndex) { mutableMapOf() }
                        cMap[tile.tileIndex] = tile.payload
                    }
                }
            }
            for (fIdx in touchedDataFrames) {
                tryRecoverFrame(fIdx)
            }
            tryRecoverCarousel()
            return accepted
        }

        private fun tryRecoverFrame(frameIndex: Int) {
            val dataMap = finalDataTiles.getOrPut(frameIndex) { mutableMapOf() }
            if (dataMap.size == dataTilesPerFrame) {
                return // Already complete
            }

            val fMap = rawTilesByFrame[frameIndex] ?: return
            val missingCount = dataTilesPerFrame - dataMap.size
            val availableParities = (dataTilesPerFrame until (dataTilesPerFrame + parityTilesPerFrame)).count { it in fMap }

            if (missingCount in 1..availableParities) {
                val recovered = recoverSpatialErasedTiles(fMap.values.toList(), dataTilesPerFrame, parityTilesPerFrame, frameIndex)
                for (t in recovered) {
                    if (t.tileIndex < dataTilesPerFrame && t.tileIndex !in dataMap) {
                        dataMap[t.tileIndex] = t.payload
                        spatialRecoveriesCount++
                        if (frameIndex == 0 && t.tileIndex == 0) {
                            tryParseMetadata()
                        }
                    }
                }
            }
        }

        private fun tryRecoverCarousel() {
            val numBlocks = (totalDataFrames + carouselBlockSize - 1) / carouselBlockSize
            for (b in 0 until numBlocks) {
                val startF = b * carouselBlockSize
                val endF = minOf(totalDataFrames, (b + 1) * carouselBlockSize)
                val cFrameIdx = totalDataFrames + b
                val cMap = carouselParityFrames[cFrameIdx] ?: continue

                for (tIdx in 0 until dataTilesPerFrame) {
                    val cPayload = cMap[tIdx] ?: continue
                    val missingFrames = mutableListOf<Int>()
                    for (f in startF until endF) {
                        val dataMap = finalDataTiles.getOrPut(f) { mutableMapOf() }
                        if (tIdx !in dataMap) {
                            missingFrames.add(f)
                        }
                    }

                    if (missingFrames.size == 1) {
                        val targetF = missingFrames[0]
                        val recPayload = cPayload.copyOf()
                        for (f in startF until endF) {
                            if (f != targetF) {
                                val other = finalDataTiles[f]!![tIdx]!!
                                for (byteI in 0 until TILE_PAYLOAD_BYTES) {
                                    recPayload[byteI] = (recPayload[byteI].toInt() xor (other[byteI].toInt() and 0xFF)).toByte()
                                }
                            }
                        }
                        finalDataTiles[targetF]!![tIdx] = recPayload
                        carouselRecoveriesCount++
                        if (targetF == 0 && tIdx == 0) {
                            tryParseMetadata()
                        }
                    }
                }
            }
        }

        fun isFrameComplete(frameIndex: Int): Boolean {
            return (finalDataTiles[frameIndex]?.size ?: 0) == dataTilesPerFrame
        }

        fun isComplete(): Boolean {
            for (f in 0 until totalDataFrames) {
                if (!isFrameComplete(f)) return false
            }
            return true
        }

        fun getMissingTilesCount(frameIndex: Int): Int {
            return dataTilesPerFrame - (finalDataTiles[frameIndex]?.size ?: 0)
        }

        fun reassembleBytes(): ByteArray? {
            if (!isComplete()) return null
            val totalBytes = totalDataFrames * dataTilesPerFrame * TILE_PAYLOAD_BYTES
            val out = ByteArray(totalBytes)
            var cursor = 0
            for (f in 0 until totalDataFrames) {
                val dataMap = finalDataTiles[f] ?: return null
                for (tIdx in 0 until dataTilesPerFrame) {
                    val p = dataMap[tIdx] ?: return null
                    System.arraycopy(p, 0, out, cursor, TILE_PAYLOAD_BYTES)
                    cursor += TILE_PAYLOAD_BYTES
                }
            }
            return out
        }

        fun getVerifiedFile(): Pair<ByteArray, String>? {
            val raw = reassembleBytes() ?: return null
            val meta = metadata ?: return null
            if (raw.size < meta.dataOffset + meta.fileSize) return null
            val fileBytes = ByteArray(meta.fileSize.toInt())
            System.arraycopy(raw, meta.dataOffset, fileBytes, 0, meta.fileSize.toInt())
            val crc = java.util.zip.CRC32()
            crc.update(fileBytes)
            if (crc.value != meta.fileCrc32) return null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val shaBytes = digest.digest(fileBytes)
            val shaHex = shaBytes.joinToString("") { "%02x".format(it) }
            return Pair(fileBytes, shaHex)
        }
    }
}
