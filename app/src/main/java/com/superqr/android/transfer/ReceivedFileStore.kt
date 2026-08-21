package com.superqr.android.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile


data class SavedTransfer(
    val uri: Uri,
    val displayName: String,
    val locationLabel: String,
)

data class StagedTransfer(
    val metadata: IncomingPackageMetadata,
    val file: File,
    val uri: Uri,
    val displayName: String,
    val sha256Hex: String,
)

/** Saves verified transfer data without ever materializing the complete file in RAM. */
object ReceivedFileStore {
    fun stageVerified(context: Context, completion: TransferCompletion, file: File, sha256Hex: String): StagedTransfer {
        compactPayloadInPlace(file, completion.metadata)
        val name = sanitizeReceivedFilename(completion.metadata.filename)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return StagedTransfer(completion.metadata, file, uri, name, sha256Hex)
    }

    fun save(context: Context, staged: StagedTransfer): SavedTransfer {
        val name = staged.displayName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(context, staged, name)
        } else {
            saveLegacy(context, staged, name)
        }
    }

    fun discard(staged: StagedTransfer?) {
        staged?.file?.delete()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveMediaStore(context: Context, staged: StagedTransfer, name: String): SavedTransfer {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, staged.metadata.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SuperQR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("cannot create Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                staged.file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: throw IllegalStateException("cannot open Downloads entry")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedTransfer(uri, name, "Downloads/SuperQR")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun saveLegacy(context: Context, staged: StagedTransfer, name: String): SavedTransfer {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "SuperQR").apply { mkdirs() }
        val target = uniqueFile(dir, name)
        FileOutputStream(target).use { out -> staged.file.inputStream().use { it.copyTo(out, 1024 * 1024) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", target)
        return SavedTransfer(uri, target.name, target.absolutePath)
    }

    private fun compactPayloadInPlace(file: File, metadata: IncomingPackageMetadata) {
        RandomAccessFile(file, "rw").use { data ->
            var readOffset = metadata.dataOffset
            var writeOffset = 0L
            var remaining = metadata.fileSize
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0) {
                data.seek(readOffset)
                val count = data.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw IllegalStateException("temporary package ended early")
                data.seek(writeOffset)
                data.write(buffer, 0, count)
                readOffset += count
                writeOffset += count
                remaining -= count
            }
            data.setLength(metadata.fileSize)
            data.fd.sync()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($index)$ext")
            index++
        }
        return candidate
    }
}

private const val MAX_RECEIVED_FILENAME_CHARS = 180

internal fun sanitizeReceivedFilename(value: String): String {
    val leaf = value.replace('\\', '/').substringAfterLast('/')
    val cleaned = buildString(leaf.length) {
        leaf.forEach { character ->
            append(if (Character.isISOControl(character)) '_' else character)
        }
    }.trim()
    val usable = cleaned.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "superqr_file"
    return usable.take(MAX_RECEIVED_FILENAME_CHARS).ifBlank { "superqr_file" }
}
