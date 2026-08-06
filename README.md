# SuperQR Android (V6)

Standalone Android V6 app for real-time camera tracking and optical pattern evaluation.

## Features
- **V6 Static Optical Marker Detector**: OpenCV-based 4-sided contour detection, homography estimation, 4 corner identity anchors, and pilot calibration.
- **Optical Tracking**: Temporal tracker with optical flow corner refinement.
- **Full Diagnostic Capture**: Debug-only full frame diagnostic capture producing raw Y/U/V planes, coordinate overlays, cell metrics CSVs, frame trace log, and exportable ZIP.

## Build & Test

```bash
# Run unit tests and assemble debug APK
./gradlew.bat testDebugUnitTest assembleDebug
```
