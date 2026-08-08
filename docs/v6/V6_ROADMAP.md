# V6 Remaining Work Before V7

Purpose: Define what we should still investigate, measure, or optimize before declaring V6 mature enough to freeze and begin V7.

This is a living document. Not every item must be implemented — some are experimental
investigations that may show a particular direction is not worthwhile.

---

## Current Baseline (IMPLEMENTED / PHYSICALLY VALIDATED)

- Protocol transport (100-byte V6 frames, CRC-16, 91-byte payload)
- Physical camera receiver (CameraX Preview + ImageAnalysis)
- Border detection via contour-based quad acquisition (FULL_DETECTION)
- Orientation/anchor decoding and identity resolution
- Optical-flow tracking with 16 reference points (raw positions, no EMA)
- TRACKED_RESAMPLED V2/V3 — fresh classification from tracked geometry, skipping contour acquisition and redundant warp/anchor work
- File reconstruction via `V6SessionAccumulator` (duplicate detection, conflict reporting, package CRC-32)
- WYSIWYG preview overlay via CameraX `CoordinateTransform`
- Full Transfer Stats instrumentation (camera config, cadence, detector timing, stage timing, CRC outcomes, unique acquisition latency, dropped-frame indicators)
- Benchmark build type (non-debuggable, profileable by shell, debug-signed)
- Explicit scanner lifecycle (IDLE → STARTING → SCANNING → COMPLETE → IDLE)
- Automatic camera shutdown after verified completion (stats/result survive)

---

## P0 — Complete Current Temporal/Pacing Investigation

**Goal:** Understand why transfers stall at the completion tail despite the receiver having spare CPU capacity.

**Data already collected:**
- 50ms sender: ~30 analysis fps, zero skips, ~40-60% CRC yield, completion tail >7s
- 100ms sender: ~30 analysis fps, zero skips, ~40-60% CRC yield, completion 2-3s
- 200ms sender: ~30 analysis fps, zero skips, completion 1-2s
- 30fps camera delivery ceiling confirmed (288×640 resolution on test device)

**To do:**
- [ ] Test 67ms sender pacing
- [ ] Test 75ms sender pacing
- [ ] For each interval, record: unique acquisition milestones (25/50/75/90/100%), longest gap, last 5 unique frame IDs, frame hits per ID, CRC yield
- [ ] Determine whether difficult frame IDs repeat across different runs on the same file
- [ ] Check if certain frame IDs consistently have fewer valid hits than others
- [ ] Compare the physical CRC-mismatch frame IDs against the known deterministic_random expected bits for those grid positions

**What would suggest display-camera temporal phase problem:**
- Difficult IDs vary randomly across runs with the same file
- Frame hit distribution is relatively uniform
- CRC yield varies with sender interval even when analysis fps is constant

**What would suggest content/pattern-specific problem:**
- Same few frame IDs are consistently difficult across multiple runs
- Those IDs correspond to cells with specific color patterns near classification boundaries
- Frame hit distribution shows those IDs with significantly fewer hits

**What would suggest detector/classifier problem:**
- CRC yield drops significantly with frame displacement/motion
- Higher CRC mismatch on tracked frames vs freshly detected frames
- Pilot distance ratios deviate during difficult frames

**Do not conclude until measurements exist.**

---

## P1 — Maximize Reliable V6 Throughput

**Only if justified by P0 measurements:**

- [ ] Adaptive sender pacing: slower pacing on difficult frames, faster on easy frames (requires sender-side logic change)
- [ ] Display refresh synchronization investigation: does aligning sender intervals to display refresh cadence improve CRC yield?
- [ ] Sender frame dwell strategy: hold each logical frame for a minimum number of display refreshes even at shorter intervals
- [ ] Analysis resolution experiments: does lower resolution image → faster analysis → higher effective frame rate compensate for potentially lower classification accuracy?
- [ ] CameraX session profile experiments (where supported): `Camera2Interop.Extender` behavior at different profiles
- [ ] Overlay/display UI throttling independent of transport processing: draw overlay at lower rate, process transport at full analyzer cadence

**Non-goal:** reducing pre-detector work (currently ~12.5ms) until P0 confirms CPU is the issue (current evidence says it is not).

---

## P1 — Robustness

**Potential measured work (not threshold tuning):**

- [ ] Exposure/white-balance behavior: does AWB shift pilot colors between frames, affecting classification?
- [ ] Brightness/display variability: test at several sender display brightness levels
- [ ] Distance/angle sweeps: characterize border detection failure boundary and CRC yield degradation
- [ ] Motion experiments: controlled linear/rotational motion at known velocities
- [ ] Dropped-frame recovery: how quickly does the receiver recover after a frame drop?
- [ ] Different phones/displays: test on at least one other phone model with the same benchmark build
- [ ] Difficult color conditions: ambient light, reflections, glare scenarios

**Constraint:** Do not retune classification thresholds globally based on one bad capture. The single known physical outlier frame (capture-08 from the 10-ZIP benchmark) already has a known cause — do not optimize for it at the expense of the other 9 frames.

---

## P2 — CPU / Thermal Efficiency

**Only after profiling with the benchmark build shows these are limiting:**

- [ ] Further allocation cleanup in diagnostic cell-detail construction (currently ~400 V6CellDiagnosticDetail objects per frame for FULL_DETECTION; controlled for TRACKED_RESAMPLED by the 45-frame cadence)
- [ ] Coarse multicore cell classification: split the 400-cell grid into 2–4 parallel tasks on the analysis executor's thread pool (currently single-threaded)
- [ ] OpenCV thread behavior: inspect whether OpenCV Mats are shared across threads safely
- [ ] Possible NDK/C++ hot path for the per-probe homography mapping + sampling (currently Kotlin/JVM with OpenCV Java bindings)
- [ ] ARM64/NEON for pixel sampling if NDK path proves viable
- [ ] Baseline Profiles / AOT compilation for the benchmark build
- [ ] ADPF (Android Dynamic Performance Framework) / thermal awareness: throttle analysis cadence when device approaches thermal limits

**Why GPU/Vulkan should only be considered after CPU profiling:**
The current bottleneck is not the homography solve or warp (both are OpenCV-optimized). Per-probe pixel sampling (2020 calls/frame) goes through Java JNI → OpenCV → Java, with per-call overhead. A GPU compute path would add texture upload/download latency and may not be faster than the current CPU path for 1000×1000 warped images. Only profile after all CPU fast-path work.

---

## P2 — Diagnostics / Productization

**Ideas, not commitments:**

- [ ] Compact "normal user" scanner UI vs current detailed developer Transfer Stats card
- [ ] Export benchmark/transfer stats to JSON or CSV for automated comparison
- [ ] Reproducible physical test protocol: standardized sender file, sender window size, sender brightness, phone distance, ambient light conditions
- [ ] Dataset capture/replay workflow improvements: current 10-ZIP benchmark validates core detection but not tracking behavior (single-frame replay creates fresh detectors)
- [ ] Automated regression comparison: run benchmark before/after a change and compare Transfer Stats fields

---

## V6 Exit Criteria (PROPOSED)

These are engineering targets for discussion, not contractual guarantees:

- [x] Stable end-to-end file transfer on current development phone
- [x] CameraX WYSIWYG overlay mapping physically validated
- [x] TRACKED_RESAMPLED reduces per-frame cost on trusted tracked frames
- [x] Deterministic protocol tests pass (10/10 benchmark border + orientation + SER)
- [ ] No known lifecycle/resource leaks (camera release, detector close)
- [ ] Completion-tail behavior characterized with pacing data
- [ ] Measured performance across several sender intervals (50/67/75/100/200ms)
- [ ] Known failure modes documented with root causes
- [ ] At least one other phone model tested
- [ ] Benchmark/regression suite exists and is green

---

## Explicitly Deferred To V7

Architectural changes that should require a version bump:

- **Geometry changes**: grid size, cell size, border dimensions, anchor/pilot layout
- **Palette changes**: different colors, different bit depth per cell (3+ bits)
- **Frame format changes**: different header layout, different payload size, different CRC algorithm
- **FEC (Forward Error Correction)**: Reed-Solomon, LDPC, or any parity scheme at the transport layer
- **Compression**: zstd, LZ4, or any compression before frame encoding
- **Encryption**: any form of transport-level or file-level encryption
- **New synchronization/control channels**: bidirectional optical feedback, dedicated sync frames, control markers
- **Major encoding changes**: run-length encoding, arithmetic coding, variable-length symbols
- **Multi-file / directory transfer**: current V6 sends one file per session
- **Sender-side adaptive rendering**: gaze tracking, attention-based encoding, per-frame difficulty estimation

These are V7 candidates, not promised V7 features.

---

## V6 Freeze Checklist

When we are ready to freeze V6 and begin V7:

- [ ] All P0 pacing investigations complete with documented conclusions
- [ ] Transfer throughput ceiling understood for 30fps receivers
- [ ] Completion-tail behavior characterized
- [ ] All unit tests pass on CI-equivalent local run
- [ ] All offline benchmark tests pass with identical SER
- [ ] Scanner lifecycle: IDLE, STARTING, SCANNING, COMPLETE, ERROR, ON_STOP all tested
- [ ] Camera config diagnostics report real resolution + FPS ranges
- [ ] Transfer Stats render correctly after completion
- [ ] Benchmark build assembles and installs
- [ ] `git diff` reviewed, no unrelated changes
- [ ] `docs/v6/V6_IMPLEMENTATION.md` reviewed and accurate
- [ ] Known failure modes documented
