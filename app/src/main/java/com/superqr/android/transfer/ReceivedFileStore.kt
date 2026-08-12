package com.superqr.android.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile


data class SavedTransfer(
    val uri: Uri,
    val displayName: String,
    val locationLabel: String,
)

/** Saves verified transfer data without ever materializing the complete file in RAM. */
object ReceivedFileStore {
    fun save(context: Context, completion: TransferCompletion): SavedTransfer {
        val name = safeName(completion.metadata.filename)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(context, completion, name)
        } else {
            saveLegacy(context, completion, name)
        }
    }

    private fun saveMediaStore(context: Context, completion: TransferCompletion, name: String): SavedTransfer {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, completion.metadata.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SuperQR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("cannot create Downloads entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                copyPayload(completion, out)
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

    private fun saveLegacy(context: Context, completion: TransferCompletion, name: String): SavedTransfer {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "SuperQR").apply { mkdirs() }
        val target = uniqueFile(dir, name)
        FileOutputStream(target).use { copyPayload(completion, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", target)
        return SavedTransfer(uri, target.name, target.absolutePath)
    }

    private fun copyPayload(completion: TransferCompletion, output: OutputStream) {
        RandomAccessFile(completion.tempFile, "r").use { input ->
            input.seek(completion.metadata.dataOffset)
            var remaining = completion.metadata.fileSize
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw IllegalStateException("temporary package ended early")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun safeName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .trim()
        return leaf.ifBlank { "superqr_file" }.take(180)
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
