package com.superqr.android.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

data class SendFileMetadata(
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val crc32: Long,
    val sha256Hex: String,
)

/** Bounded-memory producer for the compatibility-frozen V40 QR wire. */
class PreparedQrSendSession private constructor(
    private val sourceFile: File,
    private val deleteSourceOnClose: Boolean,
    val metadata: SendFileMetadata,
    initialProfile: ProductionQrProfile,
) : AutoCloseable {
    private val source = RandomAccessFile(sourceFile, "r")
    private val random = SecureRandom()
    private val packagePrefix = buildPackagePrefix(metadata)

    var profile: ProductionQrProfile = initialProfile
        private set
    var sessionId: Int = nextSessionId()
        private set
    val packageSize: Long get() = packagePrefix.size.toLong() + metadata.fileSize
    val totalFrames: Int
        get() {
            val count = ((packageSize + profile.payloadBytes - 1) / profile.payloadBytes).coerceAtLeast(1)
            require(count <= 2_000_000L) { "transfer exceeds the receiver's 2,000,000-frame safety limit" }
            return count.toInt()
        }

    @Synchronized
    fun setProfile(value: ProductionQrProfile) {
        profile = value
        sessionId = nextSessionId()
    }

    @Synchronized
    fun frameBytes(frameId: Int): ByteArray {
        require(frameId in 0 until totalFrames)
        val payloadOffset = frameId.toLong() * profile.payloadBytes
        val payload = readPackageSlice(payloadOffset, profile.payloadBytes)
        val out = ByteArray(profile.frameBytes)
        val buffer = ByteBuffer.wrap(out)
        buffer.put('S'.code.toByte())
        buffer.put('Q'.code.toByte())
        buffer.put(ProductionQrContract.TRANSPORT_VERSION.toByte())
        buffer.put(profile.id.toByte())
        buffer.putShort(sessionId.toShort())
        buffer.putInt(frameId)
        buffer.putInt(totalFrames)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        buffer.position(out.size - 4)
        buffer.putInt(CRC32().apply { update(out, 0, out.size - 4) }.value.toInt())
        return out
    }

    private fun readPackageSlice(offsetStart: Long, length: Int): ByteArray {
        var offset = offsetStart
        val end = minOf(packageSize, offset + length)
        if (offset >= end) return ByteArray(0)
        val out = ByteArray((end - offset).toInt())
        var cursor = 0
        if (offset < packagePrefix.size) {
            val count = minOf(end, packagePrefix.size.toLong()).toInt() - offset.toInt()
            packagePrefix.copyInto(out, 0, offset.toInt(), offset.toInt() + count)
            cursor += count
            offset += count
        }
        if (offset < end) {
            source.seek(offset - packagePrefix.size)
            source.readFully(out, cursor, (end - offset).toInt())
        }
        return out
    }

    private fun nextSessionId(): Int = random.nextInt(0xFFFF) + 1

    override fun close() {
        source.close()
        if (deleteSourceOnClose) sourceFile.delete()
    }

    companion object {
        fun fromFile(
            file: File,
            filename: String = file.name,
            mimeType: String = "application/octet-stream",
            profile: ProductionQrProfile = ProductionQrContract.defaultProfile,
            deleteOnClose: Boolean = false,
        ): PreparedQrSendSession {
            val crc = CRC32()
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    crc.update(buffer, 0, count)
                    sha.update(buffer, 0, count)
                }
            }
            val metadata = SendFileMetadata(
                filename = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "superqr_file" },
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                fileSize = file.length(),
                crc32 = crc.value and 0xFFFFFFFFL,
                sha256Hex = sha.digest().joinToString("") { "%02x".format(it) },
            )
            return PreparedQrSendSession(file, deleteOnClose, metadata, profile)
        }

        fun fromUri(
            context: Context,
            uri: Uri,
            profile: ProductionQrProfile = ProductionQrContract.defaultProfile,
        ): PreparedQrSendSession {
            var displayName = "superqr_file"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
            }
            val stagingDir = File(context.cacheDir, "superqr-send").apply { mkdirs() }
            val staged = File.createTempFile("send_", ".bin", stagingDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: throw IllegalArgumentException("cannot open selected file")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                return fromFile(staged, displayName, mime, profile, deleteOnClose = true)
            } catch (t: Throwable) {
                staged.delete()
                throw t
            }
        }

        private fun buildPackagePrefix(metadata: SendFileMetadata): ByteArray {
            val name = metadata.filename.toByteArray(Charsets.UTF_8)
            val mime = metadata.mimeType.toByteArray(Charsets.UTF_8)
            require(name.size in 1..1024)
            require(mime.size <= 255)
            return ByteBuffer.allocate(20 + name.size + mime.size).apply {
                put(byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte()))
                putShort(name.size.toShort())
                putShort(mime.size.toShort())
                putLong(metadata.fileSize)
                putInt(metadata.crc32.toInt())
                put(name)
                put(mime)
            }.array()
        }
    }
}
