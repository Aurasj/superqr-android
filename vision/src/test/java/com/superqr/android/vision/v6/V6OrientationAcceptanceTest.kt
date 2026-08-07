package com.superqr.android.vision.v6

import com.superqr.android.vision.v6.detection.V6OrientationEvaluator
import org.junit.Assert.*
import org.junit.Test

class V6OrientationAcceptanceTest {

    private fun decodeAnchorSamples(
        ringLuma: Int,
        medTL: Int,
        medTR: Int,
        medBR: Int,
        medBL: Int
    ): Pair<String, Int> {
        val sortedCore = listOf(medTL, medTR, medBR, medBL).sorted()
        val coreWhiteRef = (sortedCore[1] + sortedCore[2] + sortedCore[3]) / 3
        val contrast = coreWhiteRef - ringLuma
        val threshold = (ringLuma + coreWhiteRef) / 2

        val bTL = if (medTL < threshold) 1 else 0
        val bTR = if (medTR < threshold) 1 else 0
        val bBR = if (medBR < threshold) 1 else 0
        val bBL = if (medBL < threshold) 1 else 0

        val decodedBits = "$bTL$bTR$bBR$bBL"
        return Pair(decodedBits, contrast)
    }

    @Test
    fun testPhysicalTlSamplesDecodeTo1000() {
        // Physical device TL evidence: TL=10, TR=216, BR=234, BL=210
        val (decodedBits, contrast) = decodeAnchorSamples(12, 10, 216, 234, 210)
        assertEquals("1000", decodedBits)
        assertTrue("Contrast must be sufficient", contrast >= 20)

        val eval = V6OrientationEvaluator.evaluateAnchorBits("TL", decodedBits, contrast)
        assertEquals("TL", eval.bestMatchId)
        assertEquals(0, eval.bestDist)
        assertEquals(2, eval.margin)
        assertTrue(eval.isConfident)
    }

    @Test
    fun testPhysicalTrSamplesDecodeTo0100() {
        val (decodedBits, contrast) = decodeAnchorSamples(12, 220, 15, 230, 225)
        assertEquals("0100", decodedBits)
        assertTrue("Contrast must be sufficient", contrast >= 20)

        val eval = V6OrientationEvaluator.evaluateAnchorBits("TR", decodedBits, contrast)
        assertEquals("TR", eval.bestMatchId)
        assertEquals(0, eval.bestDist)
        assertEquals(2, eval.margin)
        assertTrue(eval.isConfident)
    }

    @Test
    fun testPhysicalBrSamplesDecodeTo0010() {
        val (decodedBits, contrast) = decodeAnchorSamples(12, 220, 225, 12, 230)
        assertEquals("0010", decodedBits)
        assertTrue("Contrast must be sufficient", contrast >= 20)

        val eval = V6OrientationEvaluator.evaluateAnchorBits("BR", decodedBits, contrast)
        assertEquals("BR", eval.bestMatchId)
        assertEquals(0, eval.bestDist)
        assertEquals(2, eval.margin)
        assertTrue(eval.isConfident)
    }

    @Test
    fun testPhysicalBlSamplesDecodeTo0001() {
        // Physical device BL evidence: TL=222, TR=238, BR=225, BL=7
        val (decodedBits, contrast) = decodeAnchorSamples(12, 222, 238, 225, 7)
        assertEquals("0001", decodedBits)
        assertTrue("Contrast must be sufficient", contrast >= 20)

        val eval = V6OrientationEvaluator.evaluateAnchorBits("BL", decodedBits, contrast)
        assertEquals("BL", eval.bestMatchId)
        assertEquals(0, eval.bestDist)
        assertEquals(2, eval.margin)
        assertTrue(eval.isConfident)
    }

    @Test
    fun testPermutedPhysicalIdentitiesBeforeOrientationResolution() {
        // Physical anchor identities permuted among geometric corners (90-degree rotated marker)
        val (bits0, c0) = decodeAnchorSamples(12, 220, 15, 230, 225) // TR pattern at pos 0
        val (bits1, c1) = decodeAnchorSamples(12, 220, 225, 12, 230) // BR pattern at pos 1
        val (bits2, c2) = decodeAnchorSamples(12, 222, 238, 225, 7)  // BL pattern at pos 2
        val (bits3, c3) = decodeAnchorSamples(12, 10, 216, 234, 210)  // TL pattern at pos 3

        val evalMap = mapOf(
            "TL" to V6OrientationEvaluator.evaluateAnchorBits("TL", bits0, c0),
            "TR" to V6OrientationEvaluator.evaluateAnchorBits("TR", bits1, c1),
            "BR" to V6OrientationEvaluator.evaluateAnchorBits("BR", bits2, c2),
            "BL" to V6OrientationEvaluator.evaluateAnchorBits("BL", bits3, c3)
        )

        assertTrue("Permuted physical identities must be correctly reordered and accepted", V6OrientationEvaluator.isOrientationResolved(evalMap))
        assertEquals("TR", evalMap["TL"]?.bestMatchId)
        assertEquals("BR", evalMap["TR"]?.bestMatchId)
        assertEquals("BL", evalMap["BR"]?.bestMatchId)
        assertEquals("TL", evalMap["BL"]?.bestMatchId)
    }

    @Test
    fun testDuplicateIdentitiesRejected() {
        val evalMap = mapOf(
            "TL" to V6OrientationEvaluator.evaluateAnchorBits("TL", "1000", 50),
            "TR" to V6OrientationEvaluator.evaluateAnchorBits("TR", "1000", 50), // Duplicate TL
            "BR" to V6OrientationEvaluator.evaluateAnchorBits("BR", "0010", 50),
            "BL" to V6OrientationEvaluator.evaluateAnchorBits("BL", "0001", 50)
        )

        assertFalse("Duplicate anchor identities must be rejected", V6OrientationEvaluator.isOrientationResolved(evalMap))
    }

    @Test
    fun testMarginZeroAmbiguousIdentitiesRejected() {
        // Real-device diagnostic example: 0110 (dist 1 to TR/BR, margin 0)
        val evalMap = mapOf(
            "TL" to V6OrientationEvaluator.evaluateAnchorBits("TL", "0110", 50),
            "TR" to V6OrientationEvaluator.evaluateAnchorBits("TR", "0100", 50),
            "BR" to V6OrientationEvaluator.evaluateAnchorBits("BR", "0010", 50),
            "BL" to V6OrientationEvaluator.evaluateAnchorBits("BL", "0001", 50)
        )

        assertFalse("Ambiguous identities with margin=0 must be rejected", V6OrientationEvaluator.isOrientationResolved(evalMap))
        assertFalse("TL anchor must be marked unconfident", evalMap["TL"]?.isConfident == true)
    }

    @Test
    fun testGarbagePatternRejected() {
        // Real-device diagnostic example: 1111 (dist 3 to all patterns, margin 0)
        val evalMap = mapOf(
            "TL" to V6OrientationEvaluator.evaluateAnchorBits("TL", "1111", 50),
            "TR" to V6OrientationEvaluator.evaluateAnchorBits("TR", "0100", 50),
            "BR" to V6OrientationEvaluator.evaluateAnchorBits("BR", "0010", 50),
            "BL" to V6OrientationEvaluator.evaluateAnchorBits("BL", "0001", 50)
        )

        assertFalse("Garbage patterns like 1111 must be rejected", V6OrientationEvaluator.isOrientationResolved(evalMap))
    }

    @Test
    fun testInsufficientContrastRejected() {
        val (decodedBits, contrast) = decodeAnchorSamples(180, 190, 195, 192, 188)
        assertTrue("Contrast must be low for ambiguous frame", contrast < 20)

        val evalMap = mapOf(
            "TL" to V6OrientationEvaluator.evaluateAnchorBits("TL", decodedBits, contrast),
            "TR" to V6OrientationEvaluator.evaluateAnchorBits("TR", "0100", 50),
            "BR" to V6OrientationEvaluator.evaluateAnchorBits("BR", "0010", 50),
            "BL" to V6OrientationEvaluator.evaluateAnchorBits("BL", "0001", 50)
        )

        assertFalse("Anchors with insufficient luma contrast must be rejected", V6OrientationEvaluator.isOrientationResolved(evalMap))
    }
}
