package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V7SoftCrcListDecoderTest {

    @Test
    fun `four color payload estimator recovers from collapsed red blue pilots`() {
        // These values are representative of the supplied 40x40 diagnostic ZIP:
        // the persistent pilot calibration had collapsed RED/BLUE, while the
        // payload itself still contained four clean clusters.
        val collapsed = arrayOf(
            intArrayOf(62, 129, 126),
            intArrayOf(224, 119, 126),
            intArrayOf(158, 102, 157),
            intArrayOf(117, 123, 166),
        )
        val expected = arrayOf(
            intArrayOf(48, 130, 130),  // black
            intArrayOf(237, 124, 128), // white
            intArrayOf(125, 99, 206),  // red
            intArrayOf(82, 201, 123),  // blue
        )

        val perColor = 40
        val total = perColor * 4
        val y = IntArray(total)
        val u = IntArray(total)
        val v = IntArray(total)
        for (s in 0 until 4) {
            for (j in 0 until perColor) {
                val i = s * perColor + j
                val wobble = (j % 5) - 2
                y[i] = expected[s][0] + wobble
                u[i] = expected[s][1] + ((j * 2) % 5 - 2)
                v[i] = expected[s][2] + ((j * 3) % 5 - 2)
            }
        }

        val classifier = V7SoftClassifier().also {
            it.setCenters(collapsed)
            it.setChannelWeights(6, 2, 2)
            it.setCellCount(total)
        }
        classifier.classify(y, u, v, total)

        for (s in 0 until 4) {
            for (j in 0 until perColor) {
                assertEquals(s, classifier.bestSymbols[s * perColor + j].toInt())
            }
        }
        assertEquals(0, classifier.bestSymbols.count { it == V7SoftClassifier.ERASURE_MARKER.toByte() })

        val effective = classifier.getEffectiveCentersSnapshot()
        for (s in 0 until 4) {
            assertTrue(kotlin.math.abs(effective[s][0] - expected[s][0]) <= 3)
            assertTrue(kotlin.math.abs(effective[s][1] - expected[s][1]) <= 3)
            assertTrue(kotlin.math.abs(effective[s][2] - expected[s][2]) <= 3)
        }
    }

    @Test
    fun `soft list decoder repairs two ambiguous symbol mistakes`() {
        val profile = V7OpticalProfiles.byId(0)!!
        val frame = V7Transport.buildFrame(9, 2, 7, "soft-list-test".toByteArray(), profile)
        val correct = V7Transport.bytesToSymbols(frame, profile)
        val corrupted = correct.copyOf()

        val first = 211
        val second = 733
        corrupted[first] = ((corrupted[first].toInt() + 1) and 3).toByte()
        corrupted[second] = ((corrupted[second].toInt() + 2) and 3).toByte()

        val secondBest = ByteArray(profile.cellCount)
        val bestDistances = IntArray(profile.cellCount) { 10 }
        val secondDistances = IntArray(profile.cellCount) { 1000 }
        for (i in 0 until profile.cellCount) secondBest[i] = correct[i]
        bestDistances[first] = 900; secondDistances[first] = 1000
        bestDistances[second] = 850; secondDistances[second] = 1000

        val outcome = V7SoftCrcListDecoder.repair(
            baseSymbols = corrupted,
            currentBestSymbols = corrupted,
            secondBestSymbols = secondBest,
            bestDistances = bestDistances,
            secondBestDistances = secondDistances,
            profile = profile,
        )

        assertNotNull(outcome.frame)
        assertEquals(2, outcome.flips)
        assertEquals(2, outcome.frame!!.frameId)
        assertArrayEquals("soft-list-test".toByteArray(), outcome.frame!!.payload)
    }
}
