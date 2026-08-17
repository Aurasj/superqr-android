# SuperQR Android

Android receiver for the SuperQR offline screen-to-camera data-transfer project.

The app captures optical frames from a sender display, detects the SuperQR carrier, samples the payload grid and reconstructs protocol frames in real time.

## Status

SuperQR is experimental and under active development. The user-facing app uses the V7 scanner while keeping the V6 carrier detector and geometry as the stable acquisition foundation.

The current reliable baseline is a 40×40 grid with a 4-color palette. Higher-density profiles remain experimental until physical measurements show they are reliable across devices.

## Requirements

- Android Studio
- Android SDK Platform 37
- JDK compatible with the project toolchain
- Android 8.0 / API 26 or newer
- ARM64 Android device for the current physical-camera test path

## Setup

```powershell
git clone https://github.com/Aurasj/superqr-android.git
cd superqr-android
```

Open the repository root in Android Studio and let Gradle Sync finish.

Run the unit tests and Kotlin compilation from Windows:

```powershell
.\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin
```

Build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux/macOS:

```bash
./gradlew :vision:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin
```

## What is implemented

- CameraX Preview and ImageAnalysis
- YUV_420_888 frame processing
- carrier detection, orientation, homography and tracking
- V7 40/48/56/64+ grid profiles
- optical profile auto-detection
- calibrated multi-sample cell classification
- erasure handling and CRC-gated frame acceptance
- receiver timing and throughput measurements
- diagnostic export for physical benchmark runs

## Architecture

The project has two main Gradle modules:

```text
app     Android lifecycle, UI, CameraX integration and receive workflow
vision  Detection, sampling, classification, transport decoding and diagnostics
```

`vision` is kept independent from the application UI so the optical pipeline can be tested separately.

The canonical shared protocol lives in `Aurasj/superqr-protocol`. Vendored contracts in this repository are synchronized copies needed for a standalone Android build.

## Measurements

The receiver tracks camera delivery rate, completed analysis rate, pipeline timing and useful decoded payload rate separately. Configured sender bitrate or theoretical grid capacity should not be interpreted as measured file throughput.

## Contributing

See `CONTRIBUTING.md`.

## License

MIT License. See `LICENSE`.
