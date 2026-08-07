package com.superqr.android.vision.v6.transport

object V6ReceiveUtils {
    /**
     * Sanitizes a filename to ensure it is safe to use as a local cache file name,
     * preventing path traversal or invalid characters.
     */
    fun sanitizeFilename(filename: String): String {
        return filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * Generates a preview string for the file data.
     * If the data is valid UTF-8 text (determined by the absence of NULL bytes), it returns a text preview.
     * Otherwise, it returns a hex dump of the first bytes.
     */
    fun generatePreview(fileData: ByteArray, maxTextBytes: Int = 16 * 1024, maxHexBytes: Int = 256): String {
        val previewBytes = fileData.take(maxTextBytes).toByteArray()
        if (previewBytes.isEmpty()) return ""

        val isBinary = previewBytes.any { it == 0.toByte() }
        return if (isBinary) {
            val hexBytes = fileData.take(maxHexBytes).toByteArray()
            val hexString = hexBytes.joinToString(" ") { "%02X".format(it) }
            if (fileData.size > maxHexBytes) "$hexString ..." else hexString
        } else {
            String(previewBytes, kotlin.text.Charsets.UTF_8)
        }
    }
}
