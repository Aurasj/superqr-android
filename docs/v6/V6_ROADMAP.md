# V6 — COMPLETED AND FROZEN

> **V6 STATUS: FROZEN BASELINE**
>
> V6 is a validated experimental baseline. The pacing investigation is complete.
> No new protocol, geometry, palette, features, or threshold tuning without critical
> bug evidence. Further micro-optimization of V6 cannot reach MB-scale throughput.
> New architectural work proceeds in V7.

Purpose of this document: record what we completed, what we learned, and why we
are moving on. It is a historical record, not a living task list.

---

## Completed Baseline (PHYSICALLY VALIDATED)

- Protocol transport (100-byte V6 frames, CRC-16, 91-byte payload)
- Physical camera receiver (CameraX Preview + ImageAnalysis, 30fps cadence)
- Border detection via contour-based quad acquisition (FULL_DETECTION)
- Orientation/anchor decoding and identity resolution
- Optical-flow tracking with 16 reference points (raw positions, no EMA)
- TRACKED_RESAMPLED V2/V3 — fresh classification from tracked geometry
- Explicit scanner lifecycle (IDLE → STARTING → SCANNING → COMPLETE)
- Automatic camera shutdown after verified completion
- WYSIWYG preview overlay via CameraX CoordinateTransform
- Full Transfer Stats instrumentation
- Benchmark build type (non-debuggable, profileable)

---

## Final Pacing Investigation (COMPLETE)

**File:** aurasboss.txt (2380 bytes)
**Build:** benchmark (non-debuggable)
**Phone/display:** development device, 30-30 CameraX session
**Delivery:** ~30 fps, zero delivered-frame skips in all runs

| Interval | Active transfer | CRC valid | CRC mismatch | Unique | Dups | 25% | 50% | 75% | 90% | 100% | Longest gap | User thrpt | Useful fps |
|----------|----------------|-----------|-------------|--------|------|-----|-----|-----|-----|------|-------------|-----------|-----------|
| 50 ms | 2.83s | 50 | 37 | 28/28 | 22 | 0.37s | 0.77s | 1.27s | 2.53s | 2.83s | 0.50s | 0.82 KB/s | 9.9 fps |
| 67 ms | 1.80s | 30 | 25 | 28/28 | 2 | 0.40s | 0.86s | 1.33s | 1.66s | 1.80s | 0.08s | 1.29 KB/s | 15.6 fps |
| 75 ms | 1.99s | 42 | 19 | 28/28 | 14 | 0.40s | 0.97s | 1.45s | 1.82s | 1.99s | 0.11s | 1.17 KB/s | 14.1 fps |

**Conclusions:**

1. **67 ms was the best observed pacing** of these three runs — shortest active
   transfer, tightest milestone spread, nearly eliminated the completion tail.

2. **50 ms showed a measurable completion tail:** 75% of unique frames acquired
   in 1.27s, remaining 25% took an additional 1.56s (0.50s longest gap). At 50ms
   the sender advances faster than the optical/classification path can reliably
   distinguish frames.

3. **Android processing was not the bottleneck.** The benchmark receiver
   sustained ~30 analysis fps with occupancy well below the 33.3ms interval.
   CPU micro-optimization cannot substantially improve V6 throughput.

4. **V6 throughput is fundamentally payload-density-limited:**
   - 400 cells × 2 bits = 100 raw bytes / visual frame
   - 91 transport payload bytes per frame
   - Best observed: ~1.3 KB/s user throughput at 15.6 useful fps
   - Peak theoretical (all 400 cells perfect, every frame distinct): ~2.7 KB/s at 30 fps

5. **CRC yield was highest at 75ms** (42 valid of 61 detected), and useful
   throughput (0.82–1.29 KB/s) was limited more by successful acquisition of
   distinct frame IDs than by raw frame-processing cadence.

*These are example measurements on the current development phone/display setup; not protocol guarantees.*

---

## V6 Freeze Checklist (COMPLETE)

- [x] All P0 pacing investigations complete with documented conclusions
- [x] Transfer throughput ceiling understood (payload-density-limited)
- [x] Completion-tail behavior characterized
- [x] All unit tests pass on local run
- [x] All offline benchmark tests pass with identical SER
- [x] Scanner lifecycle: all states tested
- [x] Camera config diagnostics report real resolution + FPS ranges
- [x] Transfer Stats render correctly after completion
- [x] Benchmark build assembles and installs
- [x] `docs/v6/V6_IMPLEMENTATION.md` reviewed and accurate
- [x] Known failure modes documented

---

## Optional V6 Maintenance (NOT REQUIRED FOR FREEZE)

These are low-risk ideas that could improve the current baseline without
architectural changes. None is required before starting V7.

- **Desktop sender default interval:** 100ms remains the conservative default.
  67ms is the best observed pacing on the development setup; could become the
  default after testing on additional displays.

- **Transfer Stats export:** JSON or CSV export for automated comparison between runs.

- **Reproducible physical test protocol:** Standardized sender file, window size,
  brightness, distance, ambient light; document procedure in `docs/v6/`.

- **Another phone model test:** Single benchmark-build test to confirm the
  receiver works on a second device.

- **Automated regression comparison:** Script to diff Transfer Stats between
  baseline and changed builds.

---

## Deferred V7 Research

V6 micro-optimization cannot reach MB-scale throughput. V7 needs:

### Why V7

- **Payload density must increase by orders of magnitude:** more cells, more
  bits per cell, or denser symbols — a geometry/palette change.
- **Erasure recovery is needed:** the completion-tail problem (waiting for
  specific missing frame IDs) is inherent to sequential frame-accumulator
  designs. Fountain-code-style or similar erasure coding would let the receiver
  complete after receiving *any* sufficient set of distinct frames, regardless
  of which specific IDs they carry.
- **Display-camera temporal coupling matters:** the pacing data shows
  sender interval interacts with CRC yield in ways that a fixed-interval
  sender cannot fully control. Smarter sender pacing, adaptive dwell, or
  display-synchronized rendering may be required.

### Strong V7 Research Candidates (not committed features)

- **Erasure / fountain-style FEC:** receiver can complete transfer after
  accumulating enough distinct transport symbols, without needing any specific
  frame ID. This would eliminate the completion tail entirely.
- **Higher-density grid or palette:** to increase raw bits per display frame.
- **Adaptive sender pacing or display-refresh-aware rendering.**
- **Multi-file / directory transfer.**
- **Compression at the package layer.**
- **Baseline Profile / AOT for the release build.**

### Explicitly Deferred From V6

Architectural changes requiring a version bump:

- Geometry changes (grid size, cell size, border, anchor/pilot layout)
- Palette changes (different colors, 3+ bits per cell)
- Frame format changes (header, payload size, CRC algorithm)
- FEC (any parity or erasure scheme)
- Compression
- Encryption (transport-level or file-level)
- New synchronization/control channels
- Sender-side adaptive rendering
