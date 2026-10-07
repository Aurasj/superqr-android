# SuperQR Android

Android sender and receiver for offline screen-to-camera file transfer.

> **Unfinished — development paused.** Production compatibility is retained;
> ColorGrid8/Macrochroma are research work, not a validated high-speed product.
> See [project status and known limitations](https://github.com/Aurasj/superqr-protocol/blob/main/docs/PROJECT_STATUS.md).

The Compose application has four separate areas:

- **SEND** — production V40 QR sender.
- **RECEIVE** — production camera receiver with exact verification and preview-before-save.
- **COLORGRID** — dedicated experimental receiver fixed at 336×288 / 30 FPS.
- **LAB** — ColorGrid8 v2 high-speed file receive with 8+1 XOR recovery, CRC32/SHA-256 verification, preview-before-save, and camera/finder/geometry/warp/header/pilot/payload diagnostics.

For Desktop's 240×216 / 30 FPS sender, use the **LAB** profile controls to select
the same grid and sender FPS. The dedicated COLORGRID screen does not auto-switch.

Production V40 is isolated from LAB and uses the canonical packaged contract.
Macrochroma/native/GPU research code is preserved as unfinished work. Camera
geometry and color errors still prevent a reliable physical ColorGrid file-transfer
claim; synthetic CRC/SHA-256 tests are not evidence of live transfer speed.

## Build and test

Windows:

Use JDK 17 or the compatible Android Studio JDK, SDK Platform 37, NDK
27.2.12479018 and CMake 3.22.1. Host OpenCV tests provision the Windows native
library automatically; GitHub CI uses Windows for that existing test path.

    .\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleBenchmark

Install the optimized benchmark build on a connected device:

    adb install -r app\build\outputs\apk\benchmark\app-benchmark.apk

Refresh canonical artifacts from a sibling protocol checkout:

    python scripts\sync_protocol_artifacts.py ..\superqr-protocol

The vision module contains the production V7 parser, OpenCV runtime, ColorGrid8
optical front-end, native/GPU pipeline and unfinished Macrochroma research code.
The benchmark APK is locally debug-signed for testing, not a Play Store release.
