package com.superqr.android.vision.v6.detection

import com.superqr.android.vision.v6.contract.V6Contract

data class AnchorMatchResult(
    val key: String,
    val decodedBits: String,
    val bestMatchId: String,
    val bestDist: Int,
    val secondBestDist: Int,
    val margin: Int,
    val blackWhiteContrast: Int,
    val isConfident: Boolean
)

object V6OrientationEvaluator {
    val anchorKeys = listOf("TL", "TR", "BR", "BL")

    fun evaluateAnchorBits(
        key: String,
        decodedBits: String,
        blackWhiteContrast: Int
    ): AnchorMatchResult {
        var bestId = "TL"
        var bestDist = 5
        var secondBestDist = 5

        for (targetId in anchorKeys) {
            val expectedPattern = V6Contract.getAnchorIdentityPattern(targetId)
            var dist = 0
            for (i in 0..3) {
                if (i < decodedBits.length && i < expectedPattern.length) {
                    if (decodedBits[i] != expectedPattern[i]) dist++
                } else {
                    dist++
                }
            }
            if (dist < bestDist) {
                secondBestDist = bestDist
                bestDist = dist
                bestId = targetId
            } else if (dist < secondBestDist) {
                secondBestDist = dist
            }
        }

        val margin = secondBestDist - bestDist

        // Require:
        // 1) Sufficient luma contrast between core white reference and black ring
        // 2) Exact match to one of the one-hot anchor identity patterns (bestDist == 0)
        // 3) Positive second-best margin (margin > 0)
        val isConfident = (blackWhiteContrast >= 20) && (bestDist == 0) && (margin > 0)

        return AnchorMatchResult(
            key = key,
            decodedBits = decodedBits,
            bestMatchId = bestId,
            bestDist = bestDist,
            secondBestDist = secondBestDist,
            margin = margin,
            blackWhiteContrast = blackWhiteContrast,
            isConfident = isConfident
        )
    }

    fun isOrientationResolved(anchorResults: Map<String, AnchorMatchResult>): Boolean {
        if (anchorResults.size != 4) return false

        // Every anchor identity must be individually confident
        for (key in anchorKeys) {
            val res = anchorResults[key] ?: return false
            if (!res.isConfident) return false
        }

        // All 4 confident IDs must be unique
        val matchedIds = anchorResults.values.map { it.bestMatchId }.toSet()
        return matchedIds.size == 4 && matchedIds.containsAll(anchorKeys)
    }
}
