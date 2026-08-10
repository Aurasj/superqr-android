package com.superqr.android.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.superqr.android.session.CompletedTransfer

object SessionExporter {

    fun share(context: Context, transfer: CompletedTransfer) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            transfer.file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${transfer.filename}"))
    }
}
