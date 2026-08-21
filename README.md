# SuperQR Android

Android receiver and physical-layer research application for SuperQR offline screen-to-camera transfer.

`main` is the integrated branch. The application keeps normal file receiving separate from explicit LAB surfaces.

## Application surfaces

The app opens on **RECEIVE** by default.

- **RECEIVE** — production V7 file receiver for the Desktop V40 QR stream.
- **LAB** — general Phase 1 acquisition/measurement tools.
- **COLOR LAB** — experimental ChromaQR measurements.
- **GRID8 LAB** — experimental ColorGrid8 YUV receiver and channel metrics.

The LAB tabs are research tools. Their theoretical/estimated rates are not production transfer claims.

## Production RECEIVE path

The production receiver uses CameraX Preview + ImageAnalysis and decodes the V40 QR transport independently of the LAB session stack.

It:

- analyzes `YUV_420_888` camera frames with `KEEP_ONLY_LATEST`;
- auto-detects the supported V40-L/V40-M sender variants;
- accepts out-of-order and repeated frame IDs;
- writes received frame payloads to a disk-backed sparse package rather than keeping the complete file in RAM;
- verifies the final file CRC before saving;
- saves to `Downloads/SuperQR` on Android 10+;
- exposes the saved file through a scoped `FileProvider` fallback on older Android versions.

If the receiver reaches **VERIFYING**, the completed package is allowed to finish verification/save even if the Compose receive screen is disposed. Leaving RECEIVE earlier, while a file is still incomplete, abandons that incomplete session.

## Build and test

Windows:

```powershell
.\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:assembleBenchmark
```

Linux/macOS:

```bash
./gradlew :vision:testDebugUnitTest :app:testDebugUnitTest :app:assembleBenchmark
```

The `benchmark` variant inherits release optimization and uses debug signing so it can be installed for realistic physical performance tests:

```powershell
.\gradlew.bat :app:installBenchmark
```

Use the normal debug variant for development/debugging and the optimized benchmark variant when comparing camera/decode pipeline timing.

## Basic end-to-end test

1. Install/open the Android app and stay on **RECEIVE**.
2. Start SuperQR Desktop and select the default V40-L 15 FPS mode.
3. Send a small known file first.
4. Keep all QR modules visible in the camera preview until Android shows **File received ✓**.
5. Open the saved result and compare its bytes/hash with the source.
6. Repeat with larger files and faster 20/30 FPS profiles only after the baseline is reliable.

## ColorGrid8 physical test

ColorGrid8 remains LAB-only. Match the Android GRID8 LAB profile/FPS to the desktop ColorGrid8 sender.

Recommended progression:

1. `128×96 @ 15 FPS`
2. `144×112 @ 20 FPS`
3. `160×136 @ 24 FPS`
4. `168×144 @ 30 FPS`
5. `176×144 @ 30 FPS` stress test

Watch frame delivery/unique frames, SER, BER, erasures, pilot UV separation, p50/p95 pipeline time, and recoverable post-FEC estimate. Frame loss is included in the LAB redundancy estimate rather than being ignored.

## Architecture

```text
app/
  transfer/       production V40 QR receive/accumulate/verify/save path
  camera/         shared camera utilities
  colorgrid8/     isolated ColorGrid8 camera helpers/metrics
  phase1/         experimental Phase 1 campaign integration
  session/        LAB diagnostic session lifecycle/state
  diagnostics/    LAB bundle export/share integration
  ui/             Compose production + LAB screens

vision/
  v7/             V7 transport/receiver components
  v7_capacity_lab experimental PHY/ColorGrid8/ShapeGrid components
  v6/             preserved compatibility/reference code where still required
```

Package names containing `v7_capacity_lab` intentionally mark experimental physical-layer code; they do not represent a second production application.

## Shared contracts

Canonical shared contracts and research artifacts live in `superqr-protocol`. JSON assets mirrored into the Android app should remain byte-for-byte synchronized where practical so a hash difference signals real drift rather than formatting differences.

Before publishing a release build, run unit tests, build the optimized benchmark/release path, and repeat physical production + LAB tests on real hardware.
