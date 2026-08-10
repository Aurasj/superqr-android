package com.superqr.android.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.superqr.android.session.DiagnosticSession

/** Android sharing belongs here; the measurement recorder remains UI/platform agnostic. */
object SessionExporter {
    fun shareSession(context: Context, session: DiagnosticSession): Boolean {
        val file = session.exportSession() ?: return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share SuperQR diagnostic bundle"))
        return true
    }
}
