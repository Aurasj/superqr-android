package com.superqr.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedFileStoreTest {
    @Test
    fun stripsSenderPathComponentsAndControlCharacters() {
        assertEquals("report_.txt", ReceivedFileStore.safeName("../folder/report\u0000.txt"))
        assertEquals("photo.jpg", ReceivedFileStore.safeName("C:\\fake\\path\\photo.jpg"))
    }

    @Test
    fun replacesBlankAndDotDirectoryNames() {
        assertEquals("superqr_file", ReceivedFileStore.safeName(""))
        assertEquals("superqr_file", ReceivedFileStore.safeName("."))
        assertEquals("superqr_file", ReceivedFileStore.safeName(".."))
    }

    @Test
    fun boundsStoredDisplayNameLength() {
        assertEquals(180, ReceivedFileStore.safeName("a".repeat(400)).length)
    }
}
