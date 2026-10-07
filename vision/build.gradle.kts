import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.superqr.android.vision"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.open.cv)
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// OpenCV Windows native library provisioning for desktop JVM unit tests.
// ---------------------------------------------------------------------------
val opencvWinVersion = "5.0.0"

abstract class OpenCvNativePrepare : DefaultTask() {

    @get:Input
    abstract val opencvVersion: Property<String>

    @get:Input
    abstract val cacheRootPath: Property<String>

    @get:OutputFile
    abstract val markerFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            didWork = false
            return
        }

        val version = opencvVersion.get()
        val cacheRoot = File(cacheRootPath.get())
        val dllDir = File(cacheRoot, "dlls-$version")
        val marker = markerFile.asFile.get()

        if (marker.exists()) {
            logger.lifecycle("[opencv-native] Using cached DLLs: ${dllDir.absolutePath}")
            return
        }

        // -- download -------------------------------------------------------
        val exeFile = File(cacheRoot, "opencv-$version-windows.exe")
        if (!exeFile.exists()) {
            val url = URI.create(
                "https://github.com/opencv/opencv/releases/download/" +
                    "$version/opencv-$version-windows.exe"
            ).toURL()
            logger.lifecycle("[opencv-native] Downloading $version Windows release (195 MB)...")
            try {
                url.openStream().use { input ->
                    Files.copy(input, exeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Failed to download OpenCV Windows release: ${e.message}. " +
                    "Set env SUPERQR_OPENCV_NATIVE to a directory containing " +
                    "opencv_java5.dll to skip the download."
                )
            }
            logger.lifecycle("[opencv-native] Cached at ${exeFile.absolutePath}")
        }

        // -- extract (7-Zip SFX, no external tools required) ---------------
        val extractDir = File(cacheRoot, "extract-$version")
        if (!extractDir.exists()) {
            extractDir.mkdirs()
            logger.lifecycle("[opencv-native] Extracting (this may take 1-2 minutes)...")
            val pb = ProcessBuilder(
                exeFile.absolutePath,
                "-y",
                "-o${extractDir.absolutePath}"
            )
            pb.inheritIO()
            val process = pb.start()
            if (process.waitFor() != 0) {
                throw GradleException(
                    "OpenCV self-extraction failed (exit code ${process.exitValue()})."
                )
            }
        }

        // -- locate DLLs in the extracted tree ------------------------------
        val extractedRoot = File(extractDir, "opencv")
        val javaDll = extractedRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("opencv_java") && it.extension == "dll" }
            .maxByOrNull { it.name.length }
            ?: throw GradleException("Could not find opencv_java*.dll in extracted tree.")
        logger.lifecycle("[opencv-native] Found: ${javaDll.absolutePath}")

        dllDir.mkdirs()
        javaDll.parentFile.listFiles { f -> f.extension.lowercase() == "dll" }?.forEach { dll ->
            Files.copy(dll.toPath(), File(dllDir, dll.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val dllCount = dllDir.listFiles()?.size ?: 0
        logger.lifecycle("[opencv-native] Copied $dllCount DLLs to ${dllDir.absolutePath}")

        marker.writeText(version)
    }
}

val opencvNativePrepare by tasks.registering(OpenCvNativePrepare::class) {
    description = "Download and extract OpenCV Windows DLLs for desktop JVM tests"
    opencvVersion.set(opencvWinVersion)
    cacheRootPath.set(System.getProperty("user.home") + "/.gradle/opencv-windows")
    markerFile.set(
        File(System.getProperty("user.home"), ".gradle/opencv-windows/dlls-$opencvWinVersion/.extracted")
    )
}

// Wire native preparation into every test task.  Using a string task name
// avoids capturing the TaskProvider in a closure, which would not be safe
// for the configuration cache.
tasks.withType<Test>().configureEach {
    dependsOn("opencvNativePrepare")
}
