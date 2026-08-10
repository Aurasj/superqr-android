package com.superqr.android.diagnostics

import android.content.Context
import com.superqr.android.session.DiagnosticSession

object SessionExporter {

    fun shareSession(context: Context, session: DiagnosticSession) {
        session.shareSession(context)
    }
}
