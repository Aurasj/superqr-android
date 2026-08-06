package com.superqr.android.vision.v6.diagnostic

import java.io.File

object V6FullDiagnosticExporter {
    const val IS_SUPPORTED: Boolean = false

    fun exportToZip(bundle: V6CapturedFrameBundle, outputDir: File): File? {
        // Disabled in release builds
        return null
    }
}
