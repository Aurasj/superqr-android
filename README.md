# SuperQR Android

SuperQR Android is the screen-to-camera receiver for the V7 rebuild.

## Current status

Development is on `rebuild/v7-phase0-clean` while the Android foundation is rebuilt before physical Phase 1 PHY selection.

The app has one launcher and one receiver screen. There is no separate V6 scanner or separate Phase 1 Lab application in the active app surface.

Phase 2 remains preserved and dormant during Phase 0 / Phase 1 work.

## Active app architecture

```text
app/
  camera/       CameraX Preview + ImageAnalysis and coordinate transforms
  phase1/       Phase 1 manifest, acquisition scheduling, framing, recording, vision adapter
  session/      camera-session lifecycle and UI state
  diagnostics/  export/share integration
  ui/           Compose receiver UI and theme
```

The active Phase 1 vision adapter uses the dedicated V7 acquisition/decoder components (`V7CarrierAcquirer`, `V7Phase1QrDecoder`, `V7Phase1Receiver`). The underlying `vision/v7_capacity_lab` package name is retained temporarily to avoid a large algorithmic rename before physical validation; it is implementation history, not a second application architecture.

Legacy V6 vision code remains in the `vision` module only where it is still a dependency or regression reference. It no longer defines the active Android UI/camera/session architecture. Further extraction/deletion happens only after the new V7 physical path is proven.

## Phase 0 camera contract

- one real CameraX `PreviewView`, shown as a compact 16:9 viewfinder;
- Preview and ImageAnalysis share one valid CameraX `ViewPort`;
- ImageAnalysis targets 1280×720, 16:9, capture-rate preference and `KEEP_ONLY_LATEST`;
- unexpectedly large analysis buffers are rejected;
- analysis coordinates are mapped to Preview using CameraX `OutputTransform` / `CoordinateTransform`;
- camera callback generations stay internal to the camera layer;
- START begins a diagnostic session, STOP closes it, SHARE SESSION exports the session observations;
- forensic exact-frame capture is a later Phase 0 slice and must bind lossless analyzed bytes to metadata from the same frame.

## Build and test

Windows:

```powershell
.\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:assembleBenchmark
```

Linux/macOS:

```bash
./gradlew :vision:testDebugUnitTest :app:testDebugUnitTest :app:assembleBenchmark
```

The CI workflow runs the same verification on `main` and `rebuild/v7-phase0-clean`.

## Physical exit gate

Before Phase 0 is considered complete on Galaxy A53, verify repeated START/STOP/START, stable live preview, truthful QR/GRID detection states, correctly aligned preview overlays, robust tracking/reacquisition, and a shareable diagnostic session after STOP.
