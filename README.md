# SuperQR Android

SuperQR Android is the receiver for offline screen-to-camera file transfer.

The user-facing app is a **single V7 scanner**. It reuses the physically validated V6 carrier detector/geometry as an internal acquisition foundation; there is no separate V6/V7 scanner mode in the product.

## Current V7 baseline

- CameraX Preview + ImageAnalysis with `STRATEGY_KEEP_ONLY_LATEST`;
- YUV_420_888 processing;
- V6-proven carrier acquisition, anchors, orientation, homography and tracking;
- V7 dense 40/48/56/64+ payload profiles;
- current reliable baseline: **40×40 / 4 colors**;
- AUTO optical profile detection from the marker, with debug force override;
- CROSS_5 / CENTER_1 sampling;
- calibrated soft classification, explicit erasures and bounded CRC-guided recovery;
- exact-frame pre-FEC transport baseline pending V7 inner FEC + fountain stages;
- rich live overlay/debug ZIP export.

## V7.0 measurement foundation

Debug now distinguishes:

- camera-delivered FPS;
- completed analysis FPS;
- full analyzer pipeline time;
- carrier detector time;
- V7 profile/sampling/classification/transport times;
- useful unique CRC-valid logical frames/s;
- decoded pre-package payload KiB/s;
- unexpected analyzer exception count.

The debug ZIP `diagnostics.json` uses the canonical protocol measurement schema v1 and retains events, frame summaries, calibration and per-cell evidence.

Configured/theoretical sender bitrate is not treated as actual file goodput.

## Build & test

```bash
./gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin
```

or assemble a debug APK with:

```bash
./gradlew.bat assembleDebug
```

## Architecture rule

`app` owns Android lifecycle/UI/camera integration. `vision` owns optical decoding and protocol-facing receiver logic. V6 vision code remains the proven acquisition foundation; V7 evolves payload/transport above it without creating a second app or camera stack.
