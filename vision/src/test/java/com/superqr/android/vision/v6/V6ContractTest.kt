package com.superqr.android.vision.v6

import com.superqr.android.vision.v6.contract.V6Contract
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V6ContractTest {

    @Test
    fun testCalculatedCanonicalHash() {
        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist at ${contractFile.absolutePath}", contractFile.exists())

        val bytes = contractFile.readBytes()
        V6Contract.loadAndVerifyBytes(bytes)

        assertEquals("4b3e90a0a24106795066eabfbbddf6bdafa9b14584709c2ebb0614a12d07d757", V6Contract.EXPECTED_HASH)
        assertEquals(V6Contract.EXPECTED_HASH, V6Contract.canonicalHash)
        assertTrue("rawFileHash should be non-empty for diagnostics", V6Contract.rawFileHash.isNotEmpty())
    }

    @Test
    fun testWhitespaceInvariance() {
        val contractFile = File("src/main/assets/visual_contract.json")
        val rawContent = contractFile.readText(Charsets.UTF_8)
        val jsonObject = org.json.JSONObject(rawContent)

        // Generate valid JSON with different indentation (4 spaces instead of 2) and line formatting
        val formattedWithWhitespace = jsonObject.toString(4)

        val canonicalHash = V6Contract.computeCanonicalHash(formattedWithWhitespace)
        assertEquals(V6Contract.EXPECTED_HASH, canonicalHash)
    }

    @Test
    fun testObjectKeyOrderInvariance() {
        val contractFile = File("src/main/assets/visual_contract.json")
        val rawContent = contractFile.readText(Charsets.UTF_8)

        val modifiedContent = rawContent.replace(
            "\"canvas\": {\n    \"width\": 1000,\n    \"height\": 1000\n  },\n  \"quiet_zone\"",
            "\"quiet_zone\""
        ).replace(
            "\"border\":",
            "\"canvas\": {\n    \"width\": 1000,\n    \"height\": 1000\n  },\n  \"border\":"
        )

        val canonicalHash = V6Contract.computeCanonicalHash(modifiedContent)
        assertEquals(V6Contract.EXPECTED_HASH, canonicalHash)
    }

    @Test
    fun testSemanticChangeFailure() {
        val contractFile = File("src/main/assets/visual_contract.json")
        val rawContent = contractFile.readText(Charsets.UTF_8)

        val modifiedContent = rawContent.replace("\"cols\": 20", "\"cols\": 21")
        val modifiedBytes = modifiedContent.toByteArray(Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            V6Contract.loadAndVerifyBytes(modifiedBytes)
        }
    }

    @Test
    fun testContractGeometry() {
        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        assertEquals(20, V6Contract.getGridCols())
        assertEquals(20, V6Contract.getGridRows())
        assertEquals(30.0, V6Contract.getCellSize(), 0.0001)
        assertEquals(0.15, V6Contract.getCentralRegionRatio(), 0.0001)
        
        val tlAnchor = V6Contract.getAnchorBBox("TL")
        assertEquals(100.0, tlAnchor.x1, 0.0001)
        assertEquals(100.0, tlAnchor.y1, 0.0001)
        assertEquals(180.0, tlAnchor.x2, 0.0001)
        assertEquals(180.0, tlAnchor.y2, 0.0001)

        val gridBox = V6Contract.getGridBBox()
        assertEquals(200.0, gridBox.x1, 0.0001)
        assertEquals(200.0, gridBox.y1, 0.0001)
        assertEquals(800.0, gridBox.x2, 0.0001)
        assertEquals(800.0, gridBox.y2, 0.0001)
    }
}
