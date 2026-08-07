package com.superqr.android.vision.v6.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6ReceiveUtilsTest {

    @Test
    fun testSanitizeFilename() {
        assertEquals("hello.txt", V6ReceiveUtils.sanitizeFilename("hello.txt"))
        assertEquals("test_file-123.bin", V6ReceiveUtils.sanitizeFilename("test_file-123.bin"))
        
        // Invalid characters should be replaced with underscores
        assertEquals("hello_world.txt", V6ReceiveUtils.sanitizeFilename("hello/world.txt"))
        assertEquals("_etc_passwd", V6ReceiveUtils.sanitizeFilename("/etc/passwd"))
        assertEquals(".._.._secret.txt", V6ReceiveUtils.sanitizeFilename("../../secret.txt"))
        assertEquals("test_name_with_spaces", V6ReceiveUtils.sanitizeFilename("test name with spaces"))
    }

    @Test
    fun testGeneratePreviewUtf8() {
        val text = "Hello, SuperQR!"
        val bytes = text.toByteArray(kotlin.text.Charsets.UTF_8)
        val preview = V6ReceiveUtils.generatePreview(bytes)
        
        assertEquals(text, preview)
    }

    @Test
    fun testGeneratePreviewBinary() {
        // Binary data with a NULL byte
        val bytes = byteArrayOf(0x48, 0x65, 0x6C, 0x00, 0x6F, 0x21)
        val preview = V6ReceiveUtils.generatePreview(bytes)
        
        assertEquals("48 65 6C 00 6F 21", preview)
    }
    
    @Test
    fun testGeneratePreviewEmpty() {
        val bytes = ByteArray(0)
        val preview = V6ReceiveUtils.generatePreview(bytes)
        
        assertEquals("", preview)
    }
    
    @Test
    fun testGeneratePreviewTruncationUtf8() {
        val text = "A".repeat(20000)
        val bytes = text.toByteArray(kotlin.text.Charsets.UTF_8)
        val preview = V6ReceiveUtils.generatePreview(bytes, maxTextBytes = 10)
        
        assertEquals("A".repeat(10), preview)
    }
    
    @Test
    fun testGeneratePreviewTruncationBinary() {
        val bytes = ByteArray(300) { 0x00.toByte() }
        val preview = V6ReceiveUtils.generatePreview(bytes, maxHexBytes = 10)
        
        val expectedHex = ByteArray(10) { 0x00.toByte() }.joinToString(" ") { "%02X".format(it) } + " ..."
        assertEquals(expectedHex, preview)
    }
}
