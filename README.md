# SuperQR Android

Android sender and receiver for offline screen-to-camera file transfer.

The Compose application has three separate areas:

- **SEND** — production V40 QR sender.
- **RECEIVE** — production camera receiver with exact verification and preview-before-save.
- **LAB** — ColorGrid8 v2 high-speed file receive with 8+1 XOR recovery, CRC32/SHA-256 verification, preview-before-save, and camera/finder/geometry/warp/header/pilot/payload diagnostics.

No obsolete PHY generation is exposed in the product. Production V40 is isolated from LAB and uses the canonical packaged contract.

## Build and test

Windows:

    .\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleBenchmark

Install the optimized benchmark build on a connected device:

    adb install -r app\build\outputs\apk\benchmark\app-benchmark.apk

Refresh canonical artifacts from a sibling protocol checkout:

    python scripts\sync_protocol_artifacts.py ..\superqr-protocol

The vision module now contains only the OpenCV runtime and retained ColorGrid8 optical front-end.
