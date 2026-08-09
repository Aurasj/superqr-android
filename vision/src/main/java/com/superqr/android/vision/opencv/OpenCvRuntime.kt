package com.superqr.android.vision.opencv

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File

data class OpenCvRuntimeInfo(val version: String)

class OpenCvInitializationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * The single native-loading boundary for the vision module.
 *
 * No OpenCV native object may be constructed before [ensureLoaded] succeeds.
 * Android uses the native libraries bundled by the OpenCV AAR. Desktop JVM
 * tests use the Gradle-managed Windows cache.
 */
object OpenCvRuntime {
    @Volatile private var runtimeInfo: OpenCvRuntimeInfo? = null

    @JvmStatic
    @Synchronized
    fun ensureLoaded(): OpenCvRuntimeInfo {
        runtimeInfo?.let { return it }

        var primaryFailure: Throwable? = null
        try {
            if (!OpenCVLoader.initLocal()) {
                throw OpenCvInitializationException("OpenCVLoader.initLocal() returned false")
            }
            return verifyAndRemember()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        if (isAndroidRuntime()) {
            throw OpenCvInitializationException(
                "The OpenCV native runtime bundled in the APK could not be loaded.",
                primaryFailure,
            )
        }

        // A prior JVM test may already have loaded the native library.
        try {
            return verifyAndRemember()
        } catch (_: Throwable) {
            // Continue to the explicit desktop-native cache.
        }

        if (tryLoadDesktopNative()) {
            try {
                return verifyAndRemember()
            } catch (failure: Throwable) {
                throw OpenCvInitializationException(
                    "OpenCV was found in the desktop cache but failed its native version check.",
                    failure,
                )
            }
        }

        throw OpenCvInitializationException(
            "OpenCV native runtime is unavailable. Android must package libopencv_java5.so; " +
                "desktop tests require the Gradle-managed OpenCV cache.",
            primaryFailure,
        )
    }

    fun loadedInfo(): OpenCvRuntimeInfo? = runtimeInfo

    private fun verifyAndRemember(): OpenCvRuntimeInfo {
        val version = Core.getVersionString()
        if (version.isBlank()) throw OpenCvInitializationException("OpenCV returned an empty version")
        return OpenCvRuntimeInfo(version).also { runtimeInfo = it }
    }

    private fun isAndroidRuntime(): Boolean {
        val vmName = System.getProperty("java.vm.name").orEmpty()
        return vmName.contains("Dalvik", ignoreCase = true)
    }

    private fun tryLoadDesktopNative(): Boolean {
        val override = System.getenv("SUPERQR_OPENCV_NATIVE")
        if (override != null && tryLoadFromDir(File(override))) return true
        val cacheRoot = File(System.getProperty("user.home"), ".gradle/opencv-windows")
        if (!cacheRoot.isDirectory) return false
        cacheRoot.listFiles { file -> file.isDirectory && file.name.startsWith("dlls-") }
            ?.sortedByDescending { it.name }
            ?.forEach { if (tryLoadFromDir(it)) return true }
        return false
    }

    private fun tryLoadFromDir(directory: File): Boolean {
        val library = File(directory, System.mapLibraryName(Core.NATIVE_LIBRARY_NAME))
        if (!library.isFile) return false
        return try {
            System.load(library.absolutePath)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
