# SuperQR V6 Android Receiver

> **V6 STATUS: FROZEN BASELINE**
>
> V6 is a validated experimental baseline. No new protocol, geometry, palette,
> or feature work. Only critical correctness fixes should modify V6.
> New architectural work moves to V7.

## 1. What SuperQR V6 Is

SuperQR is an **offline optical data transfer system**. Data flows from a sender display to a receiver camera — no Wi-Fi, Bluetooth, or internet. The sender encodes data into a visual marker; the receiver decodes it by analyzing camera frames.

V6 is the current protocol version. It uses a **20×20 grid of 4-color cells** (BLACK, WHITE, RED, BLUE) embedded in a fixed visual contract (border, anchors, pilots, data grid). Each cell carries 2 bits, yielding 400 palette indexes → 100 raw bytes per frame.

The canonical wire/protocol specification lives in the `superqr-protocol` repository alongside this project.

**Key numbers:**
- Grid: 20×20 = 400 cells, 2 bits/cell
- Raw frame: 100 bytes (91 bytes payload, 9 bytes header/CRC)
- Canonical canvas: 1000×1000 px
- Transport: frame magic 0xA5, version 0x06, CRC-16/CCITT-FALSE

---

## 2. End-to-End Data Flow

```
┌────────────────────────────────────────────────────────────────┐
│ SENDER (desktop app, Python + PyGame)                         │
│                                                                │
│  File → TransferPackage → V6 transport frames →               │
│  palette indexes → 1000×1000 render → display                 │
└────────────────────────────────────────────────────────────────┘
                              │
                    optical path (camera sensor)
                              ▼
┌────────────────────────────────────────────────────────────────┐
│ RECEIVER (Android app)                                         │
│                                                                │
│  CameraX sensor → ImageAnalysis (30fps) →                     │
│  YUV plane extraction → V6StaticDetector →                    │
│  border detection / optical-flow tracking →                   │
│  homography → pilots → 400-cell classification →             │
│  transport frame decode (CRC-16) →                            │
│  V6SessionAccumulator → package reconstruction →              │
│  file CRC-32 verification → completed file                    │
└────────────────────────────────────────────────────────────────┘
```

Intermediate frames that pass CRC-16 are accumulated by the session accumulator.
Frames that fail CRC are counted as mismatches (diagnostic metric).
When all unique frames for a session are received, the accumulator reconstructs
the TransferPackage and verifies the final file CRC-32.

---

## 3. Visual Contract

The visual contract defines the fixed geometry rendered by the sender and decoded by the receiver:

| Element | Canonical coordinates | Purpose |
|---------|----------------------|---------|
| Canvas | 1000×1000 px | Global coordinate space |
| Border | [60,60] to [940,940] | 10px black stroke, defines outer boundary |
| Anchors | 80×80 px at 4 corners | Identity patterns 1000/0100/0010/0001 |
| Pilots | Near top, centers ~[300..700, 120] | BLACK, WHITE, RED, BLUE reference colors |
| Data grid | [200,200] to [800,800] | 20×20 cells, 30px each |
| Palette | 4 colors: BLACK(0), WHITE(1), RED(2), BLUE(3) | 2 bits/cell |

Source of truth: `superqr-protocol/.../visual_contract.json`. The Android app
vendors a synchronized snapshot at `vision/src/main/assets/visual_contract.json`.

The rendering rule is nearest-neighbor with no antialiasing.
The sampling rule is the central 15% of each cell area (5-probe median Y/U/V).

---

## 4. CameraX Pipeline

The app uses **CameraX 1.6.1** with:

| Component | Configuration |
|-----------|--------------|
| Preview | `PreviewView.FILL_CENTER`, `COMPATIBLE` mode |
| ImageAnalysis | `STRATEGY_KEEP_ONLY_LATEST` |
| Use cases | Preview + ImageAnalysis in a single `SessionConfig` |
| ViewPort | Shared via `previewView.viewPort` (WYSIWYG) |
| Executor | Single-thread `Executors.newSingleThreadExecutor()` |
| Rotation | Matched to display rotation |

**Frame rate**: The app queries `getSupportedFrameRateRanges(SessionConfig)` for the exact Preview + ImageAnalysis session. Selection policy:
1. `Range(60, 60)` if supported
2. Otherwise highest lower-bound range with upper == 60
3. Otherwise `Range(30, 30)`
4. Otherwise default (no forced range)

The selected range is set via `SessionConfig.Builder.setFrameRateRange(...)`.

**Why no manual crop/offset**: `PreviewView` and `ImageAnalysis` share the same `ViewPort`. CameraX ensures both use-case crop rects represent the same sensor region visible to the user. `ImageProxyTransformFactory` with `setUsingCropRect(true)` + `setUsingRotationDegrees(true)` converts detector coordinates to PreviewView coordinates via `CoordinateTransform`. No manual pixel offsets.

---

## 5. Scanner Lifecycle

```
    ┌─────────────────────────────────┐
    │          IDLE                   │ ← screen opens, camera OFF
    │   [START SCAN] button           │
    └──────────────┬──────────────────┘
                   │ user taps START SCAN
                   ▼
    ┌─────────────────────────────────┐
    │        STARTING                 │
    │   binding camera + SessionConfig│
    └──────────────┬──────────────────┘
         ┌────────┴────────┐
         │ success         │ failure
         ▼                 ▼
    ┌──────────────┐  ┌─────────────────────┐
    │  SCANNING    │  │       ERROR         │
    │ camera ON    │  │ [START SCAN] retry  │
    │ frame flow   │  └─────────────────────┘
    └──────┬───────┘
           │ transfer completes + CRC-32 verified
           ▼
    ┌─────────────────────────────────────┐
    │            COMPLETE                 │
    │ camera OFF, result/stats visible    │
    │ [SCAN ANOTHER] [Share/Preview]      │
    └──────────┬──────────────────────────┘
               │ user taps SCAN ANOTHER → STARTING
```

**Key behaviors:**
- Camera is **OFF** on screen open. IDLE screen shows START SCAN button.
- `doStartScan()`: resets accumulator, stats collector, creates fresh detector, increments `scanGeneration` (prevents stale callbacks from publishing results), transitions to STARTING.
- `prepareAndBindCamera()`: builds `Preview`, `ImageAnalysis`, queries supported FPS, negotiates SessionConfig, binds, sets `scannerState = SCANNING`.
- **Completion**: Transport frame decoded → accumulator accepts → package reconstructed → file CRC-32 verified → frozen stats → `releaseCamera()` → `scannerState = COMPLETE`.
- **ON_STOP** (background): `LifecycleEventObserver` releases camera → `scannerState = IDLE`. No auto-restart. User must tap START SCAN again.
- **keepScreenOn**: Only while `scannerState == SCANNING`.

**Shutdown safety:** `releaseCamera()` (a) sets `detectorRef = null`, (b) increments `scanGeneration`, (c) clears live UI state, (d) unbinds exact `SessionConfig` via `provider.unbind(sc)`, (e) queues `detector.close()` on the analysis executor so it serializes behind any in-flight analyzer callback.

---

## 6. Detector Architecture

`V6StaticDetector` (`vision/src/main/java/com/superqr/android/vision/v6/detection/V6StaticDetector.kt`, ~999 lines) is the core detection class. Each instance is single-use per scan session.

### Entry point

**`detect(luma, width, height, mode, chromaReader, exportDebugImage, cacheDir): V6StaticResult`** — called per ImageProxy frame.

### Classification sources (modes)

| Mode | Quad source | Pilots | 400-cell | Transport | UI label |
|------|-----------|--------|----------|-----------|----------|
| `FULL_DETECTION` | Contour (GaussianBlur → Canny → findContours → quad approx) | Fresh | Fresh | Fresh (emitted if CRC valid) | "FRESH" |
| `TRACKED_RESAMPLED` | Optical flow (16-pt tracking) | Fresh | Fresh | Fresh (emitted if CRC valid) | "FRESH" |
| `TRACKED_HOMOGRAPHY` | Optical flow (held by tracker) | Stale | Stale | **Blocked** (`HELD_TRACKING_NOT_FRESH`) | "HELD" |

### FULL_DETECTION path

1. `GaussianBlur` → `Canny` → `findContours` (line ~165-170)
2. Quad candidate search: contour area ≥ 10000, polygon approximation (DP), convex hull fallback
3. Quad → canonical homography (`solveHomographySimple`, 8-DOF DLT)
4. `warpPerspective` (1000×1000, INTER_NEAREST)
5. Anchor decoding (4 anchors × 5 median luma probes each)
6. Orientation resolution (`V6OrientationEvaluator`)
7. Corrected homography (identity-to-physical corner mapping)
8. Pilot sampling (4 pilots × 5 YUV probes)
9. 400-cell classification (5-probe median YUV, nearest-pilot distance)
10. Transport decode (gated on source == `FULL_DETECTION || TRACKED_RESAMPLED`)

### TRACKED_RESAMPLED V2/V3 path

Proactive gate (before contour work, line ~155):
```kotlin
if (tracker.tryProactiveTracking(gray) != null) {
    bestPts = trackedQuad
    classificationSource = "TRACKED_RESAMPLED"
}
```

**V2 (skipped operations):** `GaussianBlur`, `Canny`, `findContours`, contour/quad search, `warpPerspective` (×2), anchor core/ring sampling, orientation decoding, corrected homography. The tracker provides corners in TL/TR/BR/BL order, so no identity correction is needed.

**V3 (allocation reduction):** Replaced per-probe `Point`/`SampleReadResult`/`IntArray`/`List<Int>` allocations with reusable flat arrays. Replaced 1209 `sorted()[2]` median computations with `median5()` (zero-alloc 9-comparison bubble-min). Replaced 400 `MutableMap<String, Double>` per cell with 4 local double variables.

**Safety:** Corner ordering assumes corners from the tracker are TL/TR/BR/BL (validated by the 16-point tracking primitive). `finalInvHArr` is computed directly from `solveHomographySimple(canonicalPoints, corners)` — no orientation correction step.

### Key private helper

**`solveHomographySimple(src, dst): DoubleArray?`** — 4-point DLT homography solver (Gaussian elimination, 8-DOF). Used for both initial and corrected homographies.

---

## 7. Tracker (`V6TemporalTracker`)

`vision/src/main/java/com/superqr/android/vision/v6/tracking/V6TemporalTracker.kt` (~360 lines)

**State machine:** `SEARCHING → ACQUIRING → LOCKED → TRACKING → REACQUIRING`

**16 canonical reference points:** 4 corners + 4 anchor centers + 8 border-tracking markers. Defined as canonical coordinates mapped through the inverse homography on each successful FULL_DETECTION.

### Key functions

| Function | Purpose |
|----------|---------|
| `tryProactiveTracking(currMat)` | Public entry: runs optical flow when LOCKED/TRACKING and periodic check is not due. Returns `Array<Point>?` (4 raw corner positions). |
| `recoverQuad(currMat)` | Reactive fallback: runs optical flow when contour acquisition fails. Same underlying `trackCurrentQuad()`. |
| `processFrame(grayMat, staticResult, homographyInv)` | Updates state machine, stores `prevGray`, on successful detection updates `trackedPoints` from homography; on detection failure runs OF fallback. |
| `trackCurrentQuad(currMat)` | **Private shared primitive**: `calcOpticalFlowPyrLK` on all 16 points, status/displacement checks (≤ 65px), convex quad test, stores raw current-frame positions (no EMA). |

**Periodic re-detection:** Every 45th frame (`frameCounter % 45 == 0`), `tryProactiveTracking` returns null, forcing contour-based FULL_DETECTION to prevent slow drift accumulation.

**No EMA:** Raw optical-flow positions are stored directly in `trackedPoints`. The old `alpha = 0.35` was removed — current physical baseline uses unsmoothed geometry.

---

## 8. WYSIWYG Preview Mapping

`app/src/main/java/com/superqr/android/ui/v6/V6PreviewOverlayMapper.kt`

**`V6PreviewOverlayMapper.map(result, source, target): V6PreviewOverlayGeometry?`**
- Takes detector result + `source` OutputTransform (ImageAnalysis → sensor) + `target` OutputTransform (PreviewView → display)
- Creates `CoordinateTransform(source, target)`
- Maps the detected quad, grid segments, and pilot positions through the transform
- Grid is only drawn for `FULL_DETECTION` or `TRACKED_RESAMPLED` (`isFresh`)

No manual coordinate offsets. The `ImageTransformFactory.getOutputTransform(imageProxy)` is captured while `ImageProxy` is valid. The target transform is read from `previewView.outputTransform` on the main thread.

---

## 9. Transport / Accumulator

`vision/src/main/java/com/superqr/android/vision/v6/transport/V6Transport.kt` (~117 lines)

**`V6Transport.parseFrame(frameData): V6TransportFrame`**
Validates: magic (0xA5), version (0x06), sessionId (≠ 0), frameId < totalFrames, totalFrames ≥ 2, CRC-16/CCITT-FALSE.

**`V6Transport.paletteIndexesToBytes(indexes): ByteArray`**
Packs 400 2-bit palette indexes into 100 bytes (MSB-first).

`vision/src/main/java/com/superqr/android/vision/v6/transport/V6SessionAccumulator.kt` (~180 lines)

**`V6SessionAccumulator.addFrame(frame): V6TransferPackage?`**
- Session-gated: only one session at a time
- Duplicate detection (same frameId + same payload → increment `duplicateCount`)
- Conflict detection (same frameId + different payload → increment `conflictCount`)
- Frame 0 carries `packageLength` (uint32)
- Completion: `frames.size == totalFrames && packageLength ≥ 9`
- `buildAndParsePackage()` → file CRC-32 verification → `V6TransferPackage`
- Internally calls `reset()` on completion (all state cleared)

---

## 10. Transfer Stats and Diagnostics

`vision/src/main/java/com/superqr/android/vision/v6/diagnostic/V6TransferStats.kt` (~260 data classes + derived getters + formatSummary)

`vision/src/main/java/com/superqr/android/vision/v6/diagnostic/V6TransferStatsCollector.kt` (~532 lines, passive observer, state machine: SCANNING → ACTIVE → COMPLETE)

### Camera config (session-level, survives transfer reset)

| Field | Meaning |
|-------|---------|
| `analysisWidth` × `analysisHeight` | Actual ImageProxy resolution from first frame |
| `cameraSupportedRanges` | All ranges from `getSupportedFrameRateRanges(SessionConfig)` |
| `cameraSelectedRange` | What we chose from the range menu |
| `cameraBoundRange` | What was actually bound (may be DEFAULT on fallback) |
| `cameraFpsFallback` | True if high-FPS bind failed and we fell back |
| `cameraFpsHighSucceeded` | Derived: selected range contains "60" && !fallback && delivery fps ≥ 50 |

### Cadence (active phase only)

| Metric | Source |
|--------|--------|
| Sensor timestamp deltas | `imageProxy.imageInfo.timestamp` (monotonic timebase, relative only) |
| Callback arrival deltas | `System.nanoTime()` between analyzer callback entries |
| Analyzer occupancy | Callback entry → `imageProxy.close()` |
| Skipped delivery count | Sensor Δ ≥ 50ms (≈ 1.5× nominal 33.3ms) |
| Delivery FPS | Derived from sensor timestamp delta distribution |

**"Delivery FPS" is NOT "raw sensor FPS".** ImageProxy timestamps represent delivered analysis frames. `KEEP_ONLY_LATEST` may drop sensor frames.

### Stage timing

| Stage | What it measures |
|-------|-----------------|
| Pre-detector | Callback entry → just before `detect()` |
| Detector (total) | `detect()` → return (per-source: FULL / TRACKED) |
| Post-detector | `detect()` return → `imageProxy.close()` |

Per-source timing includes mean, p50, p95.

### Transport outcomes

| Field | Meaning |
|-------|---------|
| `crcValidFrames` | `transportFrame != null` |
| `crcMismatchFrames` | Transport error == `"Frame CRC16 mismatch"` |
| `uncertainFrames` | Transport error == `"Uncertain cells present"` |
| `otherTransportRejectedFrames` | All other non-null transport errors |

### Unique acquisition latency

| Field | Meaning |
|-------|---------|
| 25%/50%/75%/90%/100% seconds | `ceil(totalFrames * fraction)` → time when that many unique frames were first acquired |
| Longest gap | Max interval between consecutive unique frame acceptances |
| Last 5 IDs | Frame IDs of the 5 most recent unique acceptances |
| Last unique at | Individual acceptance times for those 5 |
| Frame hits | Min/max `validHits` (CRC-valid samples per frame ID) |

### Invariant

`crcValidFrames == uniqueFramesAccepted + duplicateFrames` (conflict-free same-session transfers).

---

## 11. Performance Evolution

V6 implemented several optimization stages:

1. **Initial FULL_DETECTION** — GaussianBlur + Canny + findContours every frame
2. **TRACKED_RESAMPLED V1** — Optical-flow tracked quad skips contour acquisition on trusted frames; ~15ms → consistent detection under hand motion
3. **TRACKED_RESAMPLED V2** — Skips `warpPerspective` (×2) + anchor core/ring sampling + orientation decoding + corrected homography on tracked frames; ~8ms TRACKED mean
4. **TRACKED_RESAMPLED V3** — Allocation reduction: reusable arrays, `median5()`, inline nearest-neighbor; ~7ms TRACKED p50 on benchmark build
5. **Non-debuggable benchmark build** — Isolates debuggable runtime overhead from real performance
6. **Explicit scanner lifecycle** — Camera OFF when idle; thermal/idle CPU zero

**Physical observation (current development phone, benchmark build):**
- Receiver processes ~30 analyzed frames/sec with zero delivered-frame skips
- p50 detector occupancy ≈ 24ms, with TRACKED_RESAMPLED p50 ≈ 7–9ms
- These are measurements, not guarantees

---

## 12. Final Pacing Results

**Sender pacing experiments completed.** The Android benchmark receiver sustained
~30 delivered analysis frames/sec with zero delivered-frame skips at all tested
sender intervals. CPU/analyzer was not the dominant bottleneck.

Same file (2380 bytes), same benchmark build, same phone/display setup:

| Interval | Active transfer | CRC valid | CRC mismatch | Unique | Dups | 25% | 50% | 75% | 90% | 100% | Longest gap | Thrpt (user) | Useful fps |
|----------|----------------|-----------|-------------|--------|------|-----|-----|-----|-----|------|-------------|-------------|-----------|
| 50 ms | 2.83s | 50 | 37 | 28/28 | 22 | 0.37s | 0.77s | 1.27s | 2.53s | 2.83s | 0.50s | 0.82 KB/s | 9.9 fps |
| 67 ms | 1.80s | 30 | 25 | 28/28 | 2 | 0.40s | 0.86s | 1.33s | 1.66s | 1.80s | 0.08s | 1.29 KB/s | 15.6 fps |
| 75 ms | 1.99s | 42 | 19 | 28/28 | 14 | 0.40s | 0.97s | 1.45s | 1.82s | 1.99s | 0.11s | 1.17 KB/s | 14.1 fps |

**Key findings:**

1. **67 ms was the best observed pacing** of these three final runs — shortest
   active transfer, tightest milestone spread, nearly zero completion tail.

2. **50 ms showed a measurable completion tail:** 75% of unique frames were
   acquired in 1.27s, but the remaining 25% took an additional 1.56s (including
   a 0.50s longest gap between consecutive unique frame acceptances).

3. **67 ms nearly eliminated that tail** (longest gap dropped to 0.08s, 90→100%
   spread only 0.14s).

4. **Android processing was not the bottleneck:** the receiver sustained ~30
   analysis fps with occupancy well below the 33.3ms camera interval.

5. **V6 throughput is fundamentally limited by its optical payload density:**
   400 cells × 2 bits = 100 raw bytes per visual frame, with 91 transport
   payload bytes. Even at 15.6 useful fps (67ms sender), peak user throughput
   is ~1.3 KB/s. This is a density ceiling, not a CPU ceiling.

6. **Further V6 micro-optimization cannot reach MB-scale throughput.**
   Reaching orders-of-magnitude higher rates requires architectural changes
   (payload density, erasure recovery) that belong in V7.

*These are example measurements on the current development phone/display setup; not protocol guarantees.*

### 12.1 V6 Freeze and Why V7

**V6 is frozen as a validated experimental baseline.** The pacing investigation is complete.

**Key limit:** V6 throughput is fundamentally payload-density-bound. At 30 delivered
frames/sec with 91 transport payload bytes per frame, the theoretical ceiling is
~2.7 KB/s even at 100% CRC yield with every frame carrying a distinct logical ID.
No amount of CPU micro-optimization can push past this ceiling.

**V7 needs to address:**
- **Payload density:** substantially more bits per displayed frame (geometry, palette, or both)
- **Erasure recovery:** fountain-code-style or equivalent FEC so the receiver can
  complete after accumulating any sufficient set of distinct symbols — eliminating
  the completion-tail problem of waiting for specific missing frame IDs
- **Smarter sender-receiver temporal coupling** beyond fixed-interval pacing

V7 architectural work will be designed and scoped separately. V6 remains the
stable baseline for receiver testing and as a reference implementation.

---

## 13. Benchmark Build

`app/build.gradle.kts`:
```kotlin
create("benchmark") {
    initWith(getByName("release"))
    isDebuggable = false
    matchingFallbacks += listOf("release")
    ndk { abiFilters += "arm64-v8a" }
    signingConfig = signingConfigs.getByName("debug")
}
```

`app/src/benchmark/AndroidManifest.xml`:
```xml
<profileable android:shell="true" tools:targetApi="29" />
```

**Key properties:**
- Non-debuggable (removes debugger runtime overhead)
- Debug signing (convenient local install)
- Profileable by shell (for `adb shell cmd profile`)
- arm64-v8a only (matches development device)
- Inherits `optimization { enable = false }` from release (R8 disabled)
- Transfer stats and all diagnostics are fully available

**Build commands:**
```
./gradlew :vision:testDebugUnitTest --no-configuration-cache
./gradlew :app:assembleBenchmark --no-configuration-cache
./gradlew :app:assembleDebug --no-configuration-cache        # debug variant
```

Output: `app/build/outputs/apk/benchmark/app-benchmark.apk`

Install: `adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk`

---

## 14. Code Map

| File | Responsibility | Key functions/types | Notes |
|------|---------------|---------------------|-------|
| `app/.../ui/v6/V6StaticScreen.kt` (~1061 lines) | Scanner lifecycle, CameraX binding, analyzer callback, UI | `V6StaticScreen()`, `prepareAndBindCamera()`, `releaseCamera()`, `doStartScan()`, `scannerState`, `scanGeneration` | Composition-scoped; `DisposableEffect` + `LifecycleEventObserver` for lifecycle |
| `vision/.../detection/V6StaticDetector.kt` (~999 lines) | Frame detection: border, tracking, homography, classification, transport | `detect()`, `ensureOpenCvInitialized()`, `solveHomographySimple()`, `median5()` (file-level) | Per-instance tracker; FULL_DETECTION + TRACKED_RESAMPLED fork |
| `vision/.../tracking/V6TemporalTracker.kt` (~360 lines) | Optical-flow tracking, state machine, point management | `tryProactiveTracking()`, `trackCurrentQuad()`, `recoverQuad()`, `processFrame()`, `tryOpticalFlowTracking()` | 16 reference points; raw OF (no EMA); 45-frame drift check |
| `vision/.../diagnostic/V6TransferStats.kt` (~260 lines) | Per-transfer statistics data class, derived getters, format summary | `V6TransferStats`, `formatSummary()`, `acquisitionSeconds`, various mean/p50/p95 getters | All counters immutable; snapshot at completion |
| `vision/.../diagnostic/V6TransferStatsCollector.kt` (~532 lines) | Passive observer, before/after deltas, SCANNING→ACTIVE→COMPLETE | `recordAnalyzerFrame()`, `recordDetectorResult()`, `recordCrcOutcome()`, `recordAccumulatorOutcome()`, `buildSnapshot()` | Thread-safe via @Synchronized; camera config survives reset |
| `vision/.../transport/V6Transport.kt` (~117 lines) | Frame construction, parsing, CRC-16, palette packing | `parseFrame()`, `paletteIndexesToBytes()`, `crc16CcittFalse()` | Canonical byte-level matching `superqr-protocol` |
| `vision/.../transport/V6SessionAccumulator.kt` (~180 lines) | Per-session frame accumulation, package reconstruction | `addFrame()`, `buildAndParsePackage()`, `parseTransferPackage()` | Self-resetting on completion; duplicate/conflict counting |
| `vision/.../contract/V6Contract.kt` (~144 lines) | Visual contract geometry accessors, SHA-256 verification | `loadAndVerifyBytes()`, `getAnchorBBox()`, `getGridBBox()`, `getPilotCoreBBox()` | Vendored snapshot from `superqr-protocol` |
| `vision/.../detection/V6OrientationEvaluator.kt` (~80 lines) | Anchor identity evaluation from 4-bit patterns | `evaluateAnchorBits()`, `isOrientationResolved()` | Confidence requires: contrast ≥ 20, bestDist == 0, margin > 0 |
| `app/.../ui/V6PreviewOverlayMapper.kt` (~131 lines) | CameraX CoordinateTransform → PreviewView overlay | `V6PreviewOverlayMapper.map()`, `buildGridSegments()` | FULL_DETECTION + TRACKED_RESAMPLED treated as fresh |
| `app/build.gradle.kts` | Build types: debug, release, benchmark | `create("benchmark")` | Bench is non-debuggable + debug-signed |
| `vision/build.gradle.kts` | Vision module + OpenCV Windows native provisioning | `opencvNativePrepare` task | Downloads official OpenCV 5.0 Windows DLLs for JVM tests |
| `app/src/benchmark/AndroidManifest.xml` | Profileable by shell | `<profileable android:shell="true"/>` | Benchmark build only |

---

## 15. Invariants

**Do not change these without measured evidence:**

- Protocol bytes defined in `superqr-protocol` — the Android app is a consumer
- Visual geometry, palette, cell layout — synced from protocol
- No EMA in tracking (raw optical-flow positions are physically validated)
- Preview mapping must use CameraX `CoordinateTransform` — no manual offsets
- Held/stale geometry must never produce fresh transport data (`HELD_TRACKING_NOT_FRESH`)
- `KEEP_ONLY_LATEST` is intentional — the detector processes the most recent frame
- Final transfer result + stats must survive camera shutdown (frozen before `releaseCamera()`)
- Camera must be OFF in IDLE and COMPLETE states
- Performance experiments change one variable at a time

**Debug vs benchmark measurements must not be mixed** — debug builds have different runtime characteristics.

---

## 16. How To Validate A Change

1. **Unit tests**: `./gradlew :vision:testDebugUnitTest --no-configuration-cache`
2. **Offline benchmark**: `$env:SUPERQR_V6_DATASET="E:\Hackerman\V6Bench"` + `./gradlew :vision:testDebugUnitTest --tests "com.superqr.android.vision.v6.benchmark.V6OfflineReplayBenchmarkTest" --rerun-tasks --no-configuration-cache`
   - Must pass: 10/10 border, 10/10 orientation, identical deterministic_random SER
3. **Benchmark build**: `./gradlew :app:assembleBenchmark --no-configuration-cache`
4. **Physical test**: Install benchmark APK, run transfer at multiple sender intervals, capture Transfer Stats
5. **What to capture**: All fields from the Transfer Stats card — especially camera config, delivery fps, detector p50/p95, CRC valid/mismatch ratios, unique acquisition milestones, last 5 IDs
6. **Compare**: benchmark build before/after at same sender interval, same phone, same file
