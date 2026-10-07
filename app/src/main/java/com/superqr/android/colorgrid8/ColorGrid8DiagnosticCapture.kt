package com.superqr.android.colorgrid8

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Explicit, one-shot benchmark request. No periodic captures or exported service. */
object ColorGrid8DiagnosticCapture {
    private val pending = AtomicReference<File?>(null)

    fun request(externalFilesDir: File) {
        pending.set(File(externalFilesDir, "colorgrid-diagnostic"))
    }

    fun take(): File? = pending.getAndSet(null)
}
