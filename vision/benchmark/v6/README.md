# SuperQR V6 Offline Optical Benchmark / Replay Harness

This harness replays captured physical camera frames (exported via diagnostic ZIPs) offline through `V6StaticDetector` to compute reproducible optical detection, classification and transport metrics.

## Running the benchmark

Specify the directory containing your diagnostic ZIP dataset using the `SUPERQR_V6_DATASET` environment variable:

```powershell
$env:SUPERQR_V6_DATASET="C:\SuperQR\V6Bench"
.\gradlew :vision:testDebugUnitTest --tests "*V6OfflineReplayBenchmarkTest"
```

## Dataset directory structure

Place diagnostic ZIP files inside your dataset directory:

```text
V6Bench/
    manifest.json (optional)
    frame0_good.zip
    frame1_good.zip
    perspective_30deg.zip
    small_400px.zip
```

If `manifest.json` is omitted, the runner automatically scans and evaluates all `.zip` files in the dataset folder.

## Manifest format (`manifest.json`)

See `manifest.example.json` for structure:

```json
{
  "cases": [
    {
      "zip": "frame0_good.zip",
      "label": "hello-frame0-600px",
      "expectedSessionId": 3,
      "expectedFrameId": 0,
      "expectedTotalFrames": 2,
      "expectedFrameHex": "A5060300000002..."
    }
  ]
}
```

## Output reports

Benchmark results are generated at:

- `build/reports/v6-replay/summary.txt`
- `build/reports/v6-replay/results.csv`
- `build/reports/v6-replay/results.json`
