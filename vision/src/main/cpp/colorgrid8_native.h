#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>
#include <vector>
#include <array>
#include <string>

namespace superqr {
namespace colorgrid8 {

constexpr int32_t HEADER_MAGIC = 0xC8D;
constexpr int32_t HEADER_PAYLOAD_BITS = 51;
constexpr int32_t HEADER_BITS = 56;
constexpr int32_t HEADER_CELLS = 112;
constexpr int32_t HEADER_ROWS = 2;
constexpr int32_t PILOT_PERIOD = 25;
constexpr int32_t FIDUCIAL_OFFSET_CELLS = 8;
constexpr int32_t DIAGNOSTIC_HEADER_VERSION = 1;
constexpr int32_t TRANSFER_HEADER_VERSION = 2;

enum NativeStage : int32_t {
    STAGE_CAMERA = 0,
    STAGE_FINDER_ROI = 1,
    STAGE_GEOMETRY = 2,
    STAGE_ORIENTATION = 3,
    STAGE_HEADER = 4,
    STAGE_PILOTS = 5,
    STAGE_PAYLOAD = 6,
    STAGE_TRANSPORT = 7
};

struct NativePlane {
    const uint8_t* data = nullptr;
    int32_t rowStride = 0;
    int32_t pixelStride = 1;
    int32_t width = 0;
    int32_t height = 0;

    inline uint8_t sample(int32_t x, int32_t y) const {
        if (x < 0) x = 0; else if (x >= width) x = width - 1;
        if (y < 0) y = 0; else if (y >= height) y = height - 1;
        return data[y * rowStride + x * pixelStride];
    }
};

struct NativeQuad {
    float x[4]; // [TL, TR, BR, BL]
    float y[4];
};

struct NativeHomography {
    // 3x3 perspective matrix mapping canonical outer grid (U, V, 1) -> (x', y', w)
    // where x = x'/w, y = y'/w in camera plane coordinates.
    float h00 = 1.0f, h01 = 0.0f, h02 = 0.0f;
    float h10 = 0.0f, h11 = 1.0f, h12 = 0.0f;
    float h20 = 0.0f, h21 = 0.0f, h22 = 1.0f;

    inline void map(float u, float v, float& outX, float& outY) const {
        float w = h20 * u + h21 * v + h22;
        float invW = (std::abs(w) > 1e-7f) ? (1.0f / w) : 1.0f;
        outX = (h00 * u + h01 * v + h02) * invW;
        outY = (h10 * u + h11 * v + h12) * invW;
    }
};

struct NativeProfile {
    int32_t cols = 0;
    int32_t rows = 0;
    int32_t totalCells = 0;
    int32_t payloadCells = 0;
    int32_t fps = 30;
    int32_t seed = 0;
    int32_t version = 2;
    int32_t profileId = 0;
};

struct NativeConfig {
    float lumaErasureFraction = 0.08f;
    float chromaMarginThreshold = 0.10f;
    int32_t fiducialOffsetCells = FIDUCIAL_OFFSET_CELLS;
};

struct NativeCentroid {
    float y = 0.0f;
    float u = 0.0f;
    float v = 0.0f;
    float sigmaUv = 0.0f;
    int32_t count = 0;
};

struct NativeHeader {
    int32_t profileId = 0;
    int32_t fps = 0;
    int32_t version = 0;
    int32_t seed = 0;
    int32_t frameIndex = 0;
    int32_t rawMagic = 0;
    int32_t score = 12;
    float contrast = 0.0f;
    int32_t row = 0;
    int32_t shift = 0;
    bool reversed = false;
    bool valid = false;
};

struct NativeResult {
    NativeHeader header;
    NativeStage stage = STAGE_CAMERA;
    int32_t classifiedSymbols = 0;
    int32_t erasures = 0;
    float lumaThreshold = 0.0f;
    float pilotMinUvDistance = 0.0f;
    NativeCentroid centroids[8];
    double headerMs = 0.0;
    double pilotMs = 0.0;
    double payloadMs = 0.0;
    double totalMs = 0.0;
    bool success = false;
};

// Computes 3x3 homography mapping outer grid coordinates [0, outerCols-1] x [0, outerRows-1] to camera quad
bool computeHomography(const NativeQuad& quad, int32_t outerCols, int32_t outerRows, NativeHomography& outH);

// CRC5 verification and bit decoding
uint32_t crc5Usb(uint64_t data, int32_t numBits);
bool decodeHeaderBits(const int32_t* bits, NativeHeader& outHeader);

// Fast header probe directly from Y plane
NativeHeader probeHeaderDirect(
    const NativePlane& yPlane,
    const NativeHomography& H,
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outLumaStrip = nullptr
);

// Pilot pattern helpers
bool isPilotCell(const NativeProfile& profile, int32_t row, int32_t col);
int32_t pilotSymbol(const NativeProfile& profile, int32_t row, int32_t col, int32_t frameIndex);

// Pilot calibration from planes
bool calibratePilotsDirect(
    const NativePlane& yPlane,
    const NativePlane& uPlane,
    const NativePlane& vPlane,
    const NativeHomography& H,
    const NativeProfile& profile,
    const NativeConfig& config,
    int32_t frameIndex,
    NativeCentroid* outCentroids,
    float& outLumaThreshold,
    float& outLumaHalfGap,
    float& outPilotMinUvDistance
);

// Payload classification with NEON / scalar fallback
void decodePayloadDirect(
    const NativePlane& yPlane,
    const NativePlane& uPlane,
    const NativePlane& vPlane,
    const NativeHomography& H,
    const NativeProfile& profile,
    const NativeConfig& config,
    const NativeCentroid* centroids,
    float lumaThreshold,
    float lumaHalfGap,
    uint8_t* outPayloadSymbols,
    int32_t& outClassified,
    int32_t& outErasures
);

// High-level full frame decoder entry
NativeResult decodeFrameDirect(
    const NativePlane& yPlane,
    const NativePlane& uPlane,
    const NativePlane& vPlane,
    const NativeQuad& quad,
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outPayloadSymbols
);

// Decode from pre-computed per-cell Y/U/V means (from GPU sampling)
NativeResult decodeFromCellMeans(
    const uint8_t* cellMeansY,   // cols*rows Y values
    const uint8_t* cellMeansU,   // cols*rows U values
    const uint8_t* cellMeansV,   // cols*rows V values
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outPayloadSymbols
);

} // namespace colorgrid8
} // namespace superqr
