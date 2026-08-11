package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import java.util.zip.CRC32

class ShapeGridDecodeException(message: String) : RuntimeException(message)

data class ShapeGridRsResult(
    val data: ByteArray,
    val correctedCodeword: ByteArray,
    val errorsCorrected: Int,
    val erasuresUsed: Int,
)

data class ShapeGridBlockDecode(
    val blockId: Int,
    val valid: Boolean,
    val payload: ByteArray,
    val rsErrors: Int,
    val rsErasures: Int,
    val shapeSymbolErrors: Int,
    val colorSymbolErrors: Int,
    val symbolErasures: Int,
    val failure: String? = null,
)

/** Lab-only RS implementation. Deliberately independent from the dormant Phase 2 modem package. */
object ShapeGridReedSolomon {
    private const val PRIMITIVE = 0x11D
    private val exp = IntArray(512)
    private val log = IntArray(256)
    private val generators = HashMap<Int, IntArray>()

    init {
        var value = 1
        for (index in 0 until 255) {
            exp[index] = value
            log[value] = index
            value = value shl 1
            if (value and 0x100 != 0) value = value xor PRIMITIVE
        }
        for (index in 255 until 512) exp[index] = exp[index - 255]
    }

    fun encode(message: ByteArray, parityBytes: Int = 48): ByteArray {
        require(parityBytes in 1..254 && message.isNotEmpty() && message.size <= 255 - parityBytes)
        val generator = generator(parityBytes)
        val work = IntArray(message.size + parityBytes)
        for (i in message.indices) work[i] = message[i].toInt() and 0xFF
        for (i in message.indices) {
            val coefficient = work[i]
            if (coefficient != 0) {
                for (j in 1 until generator.size) {
                    work[i + j] = work[i + j] xor mul(generator[j], coefficient)
                }
            }
        }
        val out = ByteArray(work.size)
        message.copyInto(out)
        for (i in 0 until parityBytes) out[message.size + i] = work[message.size + i].toByte()
        return out
    }

    fun decode(
        codeword: ByteArray,
        parityBytes: Int = 48,
        erasurePositions: IntArray = IntArray(0),
    ): ShapeGridRsResult {
        if (parityBytes !in 1 until codeword.size || codeword.size > 255) {
            throw ShapeGridDecodeException("invalid ShapeGrid RS codeword")
        }
        val erasures = erasurePositions.distinct().sorted()
        if (erasures.size > parityBytes) throw ShapeGridDecodeException("too many ShapeGrid RS erasures")
        if (erasures.any { it !in codeword.indices }) throw ShapeGridDecodeException("ShapeGrid RS erasure outside codeword")
        val original = IntArray(codeword.size) { codeword[it].toInt() and 0xFF }
        val working = original.copyOf()
        for (position in erasures) working[position] = 0
        val syndromes = syndromes(working, parityBytes)
        if (syndromes.all { it == 0 }) {
            val corrected = ByteArray(working.size) { working[it].toByte() }
            return ShapeGridRsResult(
                data = corrected.copyOfRange(0, corrected.size - parityBytes),
                correctedCodeword = corrected,
                errorsCorrected = 0,
                erasuresUsed = erasures.size,
            )
        }
        val forney = forneySyndromes(syndromes, erasures, working.size)
        val errorLocator = findErrorLocator(forney, parityBytes, erasures.size)
        val errorPositions = findErrorPositions(errorLocator.reversedArray(), working.size)
        val positions = (erasures + errorPositions.toList()).distinct().sorted()
        val corrected = correctErrata(working, syndromes, positions)
        if (syndromes(corrected, parityBytes).any { it != 0 }) {
            throw ShapeGridDecodeException("ShapeGrid RS codeword is not correctable")
        }
        val erasureSet = erasures.toHashSet()
        var errors = 0
        for (index in corrected.indices) {
            if (index !in erasureSet && corrected[index] != original[index]) errors++
        }
        val correctedBytes = ByteArray(corrected.size) { corrected[it].toByte() }
        return ShapeGridRsResult(
            data = correctedBytes.copyOfRange(0, correctedBytes.size - parityBytes),
            correctedCodeword = correctedBytes,
            errorsCorrected = errors,
            erasuresUsed = erasures.size,
        )
    }

    private fun generator(parity: Int): IntArray = synchronized(generators) {
        generators.getOrPut(parity) {
            var result = intArrayOf(1)
            for (index in 0 until parity) result = polyMul(result, intArrayOf(1, pow(2, index)))
            result
        }
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int): Int {
        if (b == 0) throw ArithmeticException("GF divide by zero")
        return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    }
    private fun pow(a: Int, power: Int): Int = if (a == 0) 0 else exp[((log[a].toLong() * power).mod(255)).toInt()]
    private fun inverse(a: Int): Int {
        if (a == 0) throw ArithmeticException("GF inverse zero")
        return exp[255 - log[a]]
    }
    private fun polyScale(poly: IntArray, scalar: Int) = IntArray(poly.size) { mul(poly[it], scalar) }
    private fun polyAdd(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(maxOf(a.size, b.size))
        for (i in a.indices) out[i + out.size - a.size] = out[i + out.size - a.size] xor a[i]
        for (i in b.indices) out[i + out.size - b.size] = out[i + out.size - b.size] xor b[i]
        return out
    }
    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) if (a[i] != 0) for (j in b.indices) if (b[j] != 0) {
            out[i + j] = out[i + j] xor mul(a[i], b[j])
        }
        return out
    }
    private fun polyEval(poly: IntArray, x: Int): Int {
        var value = poly[0]
        for (i in 1 until poly.size) value = mul(value, x) xor poly[i]
        return value
    }
    private fun syndromes(codeword: IntArray, parity: Int) = IntArray(parity + 1) { index ->
        if (index == 0) 0 else polyEval(codeword, pow(2, index - 1))
    }
    private fun errataLocator(coefficientPositions: List<Int>): IntArray {
        var locator = intArrayOf(1)
        for (position in coefficientPositions) locator = polyMul(locator, intArrayOf(pow(2, position), 1))
        return locator
    }
    private fun errorEvaluator(reversedSyndromes: IntArray, locator: IntArray, degree: Int): IntArray {
        val product = polyMul(reversedSyndromes, locator)
        return product.copyOfRange(product.size - degree - 1, product.size)
    }
    private fun forneySyndromes(syndromes: IntArray, erasures: List<Int>, codewordLen: Int): IntArray {
        val out = syndromes.copyOfRange(1, syndromes.size).toMutableList()
        for (position in erasures) {
            val x = pow(2, codewordLen - 1 - position)
            for (i in 0 until out.size - 1) out[i] = mul(out[i], x) xor out[i + 1]
            out.removeAt(out.lastIndex)
        }
        return out.toIntArray()
    }
    private fun findErrorLocator(syndromes: IntArray, parity: Int, erasureCount: Int): IntArray {
        var locator = intArrayOf(1)
        var oldLocator = intArrayOf(1)
        val shift = maxOf(0, syndromes.size - parity)
        for (index in 0 until parity - erasureCount) {
            val syndromeIndex = index + shift
            var delta = syndromes[syndromeIndex]
            for (coefficient in 1 until locator.size) {
                delta = delta xor mul(locator[locator.size - coefficient - 1], syndromes[syndromeIndex - coefficient])
            }
            oldLocator = oldLocator + 0
            if (delta != 0) {
                if (oldLocator.size > locator.size) {
                    val newLocator = polyScale(oldLocator, delta)
                    oldLocator = polyScale(locator, inverse(delta))
                    locator = newLocator
                }
                locator = polyAdd(locator, polyScale(oldLocator, delta))
            }
        }
        var first = 0
        while (first < locator.lastIndex && locator[first] == 0) first++
        locator = locator.copyOfRange(first, locator.size)
        val errors = locator.size - 1
        if (errors * 2 + erasureCount > parity) throw ShapeGridDecodeException("ShapeGrid RS correction capacity exceeded")
        return locator
    }
    private fun findErrorPositions(locator: IntArray, codewordLen: Int): IntArray {
        val expected = locator.size - 1
        val positions = ArrayList<Int>(expected)
        for (index in 0 until codewordLen) {
            if (polyEval(locator, pow(2, index)) == 0) positions += codewordLen - 1 - index
        }
        if (positions.size != expected) throw ShapeGridDecodeException("ShapeGrid RS error positions could not be located")
        return positions.toIntArray()
    }
    private fun correctErrata(codeword: IntArray, syndromes: IntArray, positions: List<Int>): IntArray {
        if (positions.isEmpty()) return codeword
        val coefficients = positions.map { codeword.size - 1 - it }
        val locator = errataLocator(coefficients)
        val evaluator = errorEvaluator(syndromes.reversedArray(), locator, locator.size - 1).reversedArray()
        val roots = coefficients.map { pow(2, -(255 - it)) }
        val corrections = IntArray(codeword.size)
        for (index in roots.indices) {
            val inverseRoot = inverse(roots[index])
            var derivative = 1
            for (other in roots.indices) if (other != index) {
                derivative = mul(derivative, 1 xor mul(inverseRoot, roots[other]))
            }
            if (derivative == 0) throw ShapeGridDecodeException("invalid ShapeGrid RS locator derivative")
            var numerator = polyEval(evaluator.reversedArray(), inverseRoot)
            numerator = mul(roots[index], numerator)
            corrections[positions[index]] = div(numerator, derivative)
        }
        return polyAdd(codeword, corrections)
    }
}

object ShapeGridBlockCodec {
    private const val RS_N = 255
    private const val RS_K = 207
    private const val PARITY = 48
    private const val INTERLEAVER_MULTIPLIER = 73
    private const val FRAME_OFFSET_MULTIPLIER = 17
    private const val BLOCK_OFFSET_MULTIPLIER = 131

    data class UnpackedBytes(val bytes: ByteArray, val erasures: BooleanArray)

    fun deinterleaveSymbols(
        physicalSymbols: IntArray,
        activeSymbols: Int,
        blockId: Int,
        frameIndex: Int,
    ): IntArray {
        require(activeSymbols <= physicalSymbols.size)
        val blockCells = physicalSymbols.size
        val offset = (FRAME_OFFSET_MULTIPLIER * frameIndex + BLOCK_OFFSET_MULTIPLIER * blockId) % blockCells
        return IntArray(activeSymbols) { logical ->
            physicalSymbols[(INTERLEAVER_MULTIPLIER * logical + offset) % blockCells]
        }
    }

    fun symbolsToBytes(symbols: IntArray): UnpackedBytes {
        require(symbols.size % 4 == 0)
        val out = ByteArray(symbols.size / 4 * 3)
        val erased = BooleanArray(out.size)
        var symbolIndex = 0
        var byteIndex = 0
        while (symbolIndex < symbols.size) {
            val s0 = symbols[symbolIndex]
            val s1 = symbols[symbolIndex + 1]
            val s2 = symbols[symbolIndex + 2]
            val s3 = symbols[symbolIndex + 3]
            val v0 = if (s0 >= 0) s0 else 0
            val v1 = if (s1 >= 0) s1 else 0
            val v2 = if (s2 >= 0) s2 else 0
            val v3 = if (s3 >= 0) s3 else 0
            out[byteIndex] = ((v0 shl 2) or (v1 ushr 4)).toByte()
            out[byteIndex + 1] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
            out[byteIndex + 2] = (((v2 and 0x03) shl 6) or v3).toByte()
            if (s0 < 0 || s1 < 0) erased[byteIndex] = true
            if (s1 < 0 || s2 < 0) erased[byteIndex + 1] = true
            if (s2 < 0 || s3 < 0) erased[byteIndex + 2] = true
            symbolIndex += 4
            byteIndex += 3
        }
        return UnpackedBytes(out, erased)
    }

    fun decode(
        profile: ShapeGridProfile,
        blockId: Int,
        outerEnvelope: V7LabRunEnvelope,
        physicalSymbols: IntArray,
    ): ShapeGridBlockDecode {
        val observedSymbolErasures = physicalSymbols.count { it < 0 }
        return try {
            require(blockId in 0 until 8)
            require(physicalSymbols.size == profile.blockCells)
            val logical = deinterleaveSymbols(
                physicalSymbols,
                profile.activeSymbolsPerBlock,
                blockId,
                outerEnvelope.frameIndex,
            )
            val unpacked = symbolsToBytes(logical)
            require(unpacked.bytes.size == profile.encodedBytesPerBlock)
            val blockData = ByteArray(profile.rsCodewordsPerBlock * RS_K)
            val correctedEncoded = ByteArray(profile.encodedBytesPerBlock)
            var totalErrors = 0
            var totalErasures = 0
            repeat(profile.rsCodewordsPerBlock) { shard ->
                val codeOffset = shard * RS_N
                val codeword = unpacked.bytes.copyOfRange(codeOffset, codeOffset + RS_N)
                val erasurePositions = IntArray(
                    (0 until RS_N).count { unpacked.erasures[codeOffset + it] }
                )
                var cursor = 0
                for (i in 0 until RS_N) if (unpacked.erasures[codeOffset + i]) erasurePositions[cursor++] = i
                val rs = ShapeGridReedSolomon.decode(codeword, PARITY, erasurePositions)
                rs.data.copyInto(blockData, shard * RS_K)
                rs.correctedCodeword.copyInto(correctedEncoded, codeOffset)
                totalErrors += rs.errorsCorrected
                totalErasures += rs.erasuresUsed
            }
            validateBlockData(profile, blockId, outerEnvelope, blockData)
            val correctedSymbols = bytesToSymbols6(correctedEncoded)
            require(correctedSymbols.size == logical.size)
            var shapeErrors = 0
            var colorErrors = 0
            for (index in logical.indices) {
                val observed = logical[index]
                if (observed < 0) continue
                val expected = correctedSymbols[index]
                if ((observed ushr 2) != (expected ushr 2)) shapeErrors++
                if ((observed and 0x03) != (expected and 0x03)) colorErrors++
            }
            val payload = blockData.copyOfRange(26, 26 + profile.usefulBytesPerBlock)
            ShapeGridBlockDecode(
                blockId = blockId,
                valid = true,
                payload = payload,
                rsErrors = totalErrors,
                rsErasures = totalErasures,
                shapeSymbolErrors = shapeErrors,
                colorSymbolErrors = colorErrors,
                symbolErasures = observedSymbolErasures,
            )
        } catch (error: Throwable) {
            ShapeGridBlockDecode(
                blockId = blockId,
                valid = false,
                payload = ByteArray(0),
                rsErrors = 0,
                rsErasures = 0,
                shapeSymbolErrors = 0,
                colorSymbolErrors = 0,
                symbolErasures = observedSymbolErasures,
                failure = "${error::class.simpleName}:${error.message}",
            )
        }
    }

    private fun bytesToSymbols6(data: ByteArray): IntArray {
        require((data.size * 8) % 6 == 0)
        val output = IntArray(data.size * 8 / 6)
        var accumulator = 0L
        var bitCount = 0
        var cursor = 0
        for (byte in data) {
            accumulator = (accumulator shl 8) or (byte.toLong() and 0xFF)
            bitCount += 8
            while (bitCount >= 6) {
                bitCount -= 6
                output[cursor++] = ((accumulator ushr bitCount) and 0x3F).toInt()
                accumulator = if (bitCount == 0) 0 else accumulator and ((1L shl bitCount) - 1)
            }
        }
        require(bitCount == 0 && cursor == output.size)
        return output
    }

    private fun validateBlockData(
        profile: ShapeGridProfile,
        blockId: Int,
        outerEnvelope: V7LabRunEnvelope,
        data: ByteArray,
    ) {
        require(data.size == profile.rsCodewordsPerBlock * RS_K)
        require(data[0].toInt() and 0xFF == 'S'.code)
        require(data[1].toInt() and 0xFF == 'Q'.code)
        require(data[2].toInt() and 0xFF == 'S'.code)
        require(data[3].toInt() and 0xFF == '1'.code)
        require(u8(data[4]) == 1)
        require(u8(data[5]) == blockId)
        require(u8(data[6]) == 8)
        require(u8(data[7]) == 0)
        val inner = requireNotNull(V7LabRunEnvelope.decode(data, 8))
        require(inner == outerEnvelope)
        require(u32le(data, 18) == 42L)
        require(u16le(data, 22) == profile.usefulBytesPerBlock)
        require(u8(data[24]) == 4)
        require(u8(data[25]) == 2)
        val crcOffset = 26 + profile.usefulBytesPerBlock
        require(crcOffset + 4 == data.size)
        val crc = CRC32().apply { update(data, 0, crcOffset) }.value
        require(u32le(data, crcOffset) == crc)
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
    private fun u16le(bytes: ByteArray, offset: Int): Int = u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    private fun u32le(bytes: ByteArray, offset: Int): Long =
        u8(bytes[offset]).toLong() or
            (u8(bytes[offset + 1]).toLong() shl 8) or
            (u8(bytes[offset + 2]).toLong() shl 16) or
            (u8(bytes[offset + 3]).toLong() shl 24)
}
