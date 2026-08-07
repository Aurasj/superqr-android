package com.superqr.android.vision.v6.replay

import org.json.JSONObject
import java.io.File

data class V6BenchmarkTestCase(
    val zip: String,
    val label: String,
    val expectedSessionId: Int? = null,
    val expectedFrameId: Int? = null,
    val expectedTotalFrames: Int? = null,
    val expectedFrameHex: String? = null,
    val patternName: String? = null
)

data class V6BenchmarkManifest(
    val cases: List<V6BenchmarkTestCase>
) {
    companion object {
        fun loadFromFile(manifestFile: File): V6BenchmarkManifest {
            require(manifestFile.exists() && manifestFile.isFile) { "Manifest file not found: ${manifestFile.absolutePath}" }
            val jsonStr = manifestFile.readText(Charsets.UTF_8)
            return parseJson(jsonStr)
        }

        fun parseJson(jsonStr: String): V6BenchmarkManifest {
            val root = JSONObject(jsonStr)
            val casesArray = root.optJSONArray("cases") ?: return V6BenchmarkManifest(emptyList())
            val cases = mutableListOf<V6BenchmarkTestCase>()

            for (i in 0 until casesArray.length()) {
                val obj = casesArray.getJSONObject(i)
                val zip = obj.getString("zip")
                val label = obj.optString("label", zip)
                val expectedSessionId = if (obj.has("expectedSessionId") && !obj.isNull("expectedSessionId")) obj.getInt("expectedSessionId") else null
                val expectedFrameId = if (obj.has("expectedFrameId") && !obj.isNull("expectedFrameId")) obj.getInt("expectedFrameId") else null
                val expectedTotalFrames = if (obj.has("expectedTotalFrames") && !obj.isNull("expectedTotalFrames")) obj.getInt("expectedTotalFrames") else null
                val expectedFrameHex = if (obj.has("expectedFrameHex") && !obj.isNull("expectedFrameHex")) obj.getString("expectedFrameHex") else null
                val patternName = if (obj.has("patternName") && !obj.isNull("patternName")) obj.getString("patternName") else null

                cases.add(
                    V6BenchmarkTestCase(
                        zip = zip,
                        label = label,
                        expectedSessionId = expectedSessionId,
                        expectedFrameId = expectedFrameId,
                        expectedTotalFrames = expectedTotalFrames,
                        expectedFrameHex = expectedFrameHex,
                        patternName = patternName
                    )
                )
            }
            return V6BenchmarkManifest(cases)
        }
    }
}
