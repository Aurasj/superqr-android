package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V7SoftLumaClassifierTest {
    @Test fun `classifies black white and uncertain samples with signed likelihoods`() {
        val classifier = V7SoftLumaClassifier()
        val result = classifier.classify(
            samples = intArrayOf(20, 230, 126, 128),
            validMask = byteArrayOf(1, 1, 1, 0),
            cells = 4,
            blackReferenceY = 20,
            whiteReferenceY = 230,
        )
        assertEquals(2, result.classified)
        assertEquals(2, result.erasures)
        assertEquals(0, classifier.bits()[0].toInt())
        assertEquals(1, classifier.bits()[1].toInt())
        assertEquals(V7SoftLumaClassifier.ERASURE, classifier.bits()[2])
        assertTrue(classifier.llr()[0] < 0f)
        assertTrue(classifier.llr()[1] > 0f)
    }

    @Test fun `erases all cells when reference contrast is too low`() {
        val classifier = V7SoftLumaClassifier()
        val result = classifier.classify(
            intArrayOf(100, 110), byteArrayOf(1, 1), 2, 100, 120,
        )
        assertEquals(2, result.erasures)
    }
}
