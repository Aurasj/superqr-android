package com.superqr.android.colorgrid8

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ColorGrid8DiagnosticCaptureTest {
    @Test fun captureIsExplicitOneShotAndRequestsCoalesce() {
        ColorGrid8DiagnosticCapture.take()
        assertNull(ColorGrid8DiagnosticCapture.take())
        val root = File("test-external-files")
        repeat(3) { ColorGrid8DiagnosticCapture.request(root) }
        assertEquals(File(root, "colorgrid-diagnostic"), ColorGrid8DiagnosticCapture.take())
        assertNull(ColorGrid8DiagnosticCapture.take())
    }
}
