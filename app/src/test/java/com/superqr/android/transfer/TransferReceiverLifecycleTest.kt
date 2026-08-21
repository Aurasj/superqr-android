package com.superqr.android.transfer

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferReceiverLifecycleTest {
    @Test
    fun closeWaitsForVerificationBeforeDeletingTemporaryFile() {
        val tempFile = Files.createTempFile("superqr-verifying", ".part").toFile()
        val verificationStarted = CountDownLatch(1)
        val allowVerificationToFinish = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val verificationFailure = AtomicReference<Throwable?>()
        val queue = ReceiverWorkQueue()

        assertTrue(queue.execute {
            try {
                verificationStarted.countDown()
                assertTrue(tempFile.exists())
                assertTrue(allowVerificationToFinish.await(5, TimeUnit.SECONDS))
                assertTrue(tempFile.exists())
            } catch (failure: Throwable) {
                verificationFailure.set(failure)
            }
        })
        assertTrue(verificationStarted.await(5, TimeUnit.SECONDS))

        queue.closeAfterPendingWork {
            assertTrue(tempFile.delete())
            cleanupFinished.countDown()
        }
        assertTrue(tempFile.exists())
        allowVerificationToFinish.countDown()

        assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS))
        assertFalse(tempFile.exists())
        verificationFailure.get()?.let { throw it }
    }
}
