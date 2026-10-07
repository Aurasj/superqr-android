#include "colorgrid8_native.h"
#include "colorgrid8_neon.h"
#include <chrono>
#include <cstring>
#include <algorithm>
#ifdef __ANDROID__
#include <android/log.h>
#else
// Keep the optical decoder executable in host regression tests.
constexpr int ANDROID_LOG_DEBUG = 3;
static int __android_log_print(int, const char*, const char*, ...) { return 0; }
#endif

namespace superqr {
namespace colorgrid8 {

static const int32_t FPS_SWEEP_DIAGNOSTIC[] = { 15, 20, 24, 30 };
static const int32_t FPS_SWEEP_TRANSFER[] = { 30, 45, 60, 90 };

int32_t getFpsForCode(int32_t code, int32_t version) {
    if (code < 0 || code > 3) return 30;
    return (version == TRANSFER_HEADER_VERSION) ? FPS_SWEEP_TRANSFER[code] : FPS_SWEEP_DIAGNOSTIC[code];
}

bool computeHomography(const NativeQuad& quad, int32_t outerCols, int32_t outerRows, NativeHomography& outH) {
    float x0 = quad.x[0], y0 = quad.y[0];
    float x1 = quad.x[1], y1 = quad.y[1];
    float x2 = quad.x[2], y2 = quad.y[2];
    float x3 = quad.x[3], y3 = quad.y[3];

    float dx1 = x1 - x2;
    float dx2 = x3 - x2;
    float sx = x0 - x1 + x2 - x3;

    float dy1 = y1 - y2;
    float dy2 = y3 - y2;
    float sy = y0 - y1 + y2 - y3;

    if (std::abs(sx) < 1e-6f && std::abs(sy) < 1e-6f) {
        outH.h00 = x1 - x0; outH.h01 = x3 - x0; outH.h02 = x0;
        outH.h10 = y1 - y0; outH.h11 = y3 - y0; outH.h12 = y0;
        outH.h20 = 0.0f;    outH.h21 = 0.0f;    outH.h22 = 1.0f;
    } else {
        float det = dx1 * dy2 - dx2 * dy1;
        if (std::abs(det) < 1e-7f) return false;
        float g = (sx * dy2 - dx2 * sy) / det;
        float h = (dx1 * sy - sx * dy1) / det;
        outH.h00 = x1 - x0 + g * x1;
        outH.h01 = x3 - x0 + h * x3;
        outH.h02 = x0;
        outH.h10 = y1 - y0 + g * y1;
        outH.h11 = y3 - y0 + h * y3;
        outH.h12 = y0;
        outH.h20 = g;
        outH.h21 = h;
        outH.h22 = 1.0f;
    }

    float scaleU = 1.0f / static_cast<float>(std::max(1, outerCols - 1));
    float scaleV = 1.0f / static_cast<float>(std::max(1, outerRows - 1));

    outH.h00 *= scaleU;
    outH.h01 *= scaleV;
    outH.h10 *= scaleU;
    outH.h11 *= scaleV;
    outH.h20 *= scaleU;
    outH.h21 *= scaleV;
    return true;
}

uint32_t crc5Usb(const int32_t* bits, int32_t length) {
    uint32_t reg = 0x1F;
    for (int32_t i = 0; i < length; ++i) {
        uint32_t feedback = ((reg >> 4) & 1) ^ (bits[i] & 1);
        reg = (reg << 1) & 0x1F;
        if (feedback != 0) reg ^= 0x05;
    }
    return reg ^ 0x1F;
}

static inline int32_t readBits(const int32_t* bits, int32_t start, int32_t count) {
    int32_t val = 0;
    for (int32_t i = 0; i < count; ++i) {
        val = (val << 1) | (bits[start + i] & 1);
    }
    return val;
}

bool decodeHeaderBits(const int32_t* bits, NativeHeader& outHeader) {
    int32_t magic = readBits(bits, 0, 12);
    outHeader.rawMagic = magic;
    outHeader.score = 0;
    int32_t diff = magic ^ HEADER_MAGIC;
    while (diff > 0) {
        if (diff & 1) outHeader.score++;
        diff >>= 1;
    }

    if (outHeader.score != 0) {
        outHeader.valid = false;
        return false;
    }

    uint32_t expectedCrc = crc5Usb(bits, HEADER_PAYLOAD_BITS);
    uint32_t actualCrc = static_cast<uint32_t>(readBits(bits, HEADER_PAYLOAD_BITS, 5));
    if (expectedCrc != actualCrc) {
        outHeader.valid = false;
        return false;
    }

    outHeader.version = readBits(bits, 12, 2);
    outHeader.profileId = readBits(bits, 14, 3);
    if (outHeader.version != DIAGNOSTIC_HEADER_VERSION && outHeader.version != TRANSFER_HEADER_VERSION) {
        outHeader.valid = false;
        return false;
    }
    int32_t fpsCode = readBits(bits, 17, 2);
    outHeader.fps = getFpsForCode(fpsCode, outHeader.version);
    outHeader.frameIndex = readBits(bits, 19, 16);
    outHeader.seed = readBits(bits, 35, 16);
    outHeader.valid = true;
    return true;
}

NativeHeader probeHeaderDirect(
    const NativePlane& yPlane,
    const NativeHomography& H,
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outLumaStrip
) {
    NativeHeader bestHeader;
    bestHeader.valid = false;
    bestHeader.score = 12;

    int32_t cols = profile.cols;
    int32_t offset = config.fiducialOffsetCells;
    int32_t headerRows = HEADER_ROWS;

    // Sample header luma strip into local buffer
    // 2 rows x cols
    std::vector<uint8_t> lumaBuffer(headerRows * cols);
    uint8_t* luma = outLumaStrip ? outLumaStrip : lumaBuffer.data();

    for (int32_t r = 0; r < headerRows; ++r) {
        float v = static_cast<float>(r + offset);
        for (int32_t c = 0; c < cols; ++c) {
            float u = static_cast<float>(c + offset);
            float camX, camY;
            H.map(u, v, camX, camY);
            int32_t ix = static_cast<int32_t>(std::round(camX));
            int32_t iy = static_cast<int32_t>(std::round(camY));
            luma[r * cols + c] = yPlane.sample(ix, iy);
        }
    }

    int32_t maxShift = std::min(16, cols - HEADER_CELLS);
    std::array<float, HEADER_BITS> pairMeans;

    for (int32_t row = 0; row < headerRows; ++row) {
        for (bool reversed : { false, true }) {
            for (int32_t shift = 0; shift <= maxShift; ++shift) {
                float low = 255.0f;
                float high = 0.0f;

                for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                    int32_t c0 = reversed ? (cols - 1 - shift - (bit * 2)) : (shift + (bit * 2));
                    int32_t c1 = reversed ? (cols - 1 - shift - (bit * 2 + 1)) : (shift + (bit * 2 + 1));
                    uint8_t a = luma[row * cols + c0];
                    uint8_t b = luma[row * cols + c1];
                    float mean = (static_cast<float>(a) + static_cast<float>(b)) * 0.5f;
                    pairMeans[bit] = mean;
                    if (mean < low) low = mean;
                    if (mean > high) high = mean;
                }

                float contrast = high - low;
                if (contrast < 12.0f) continue;

                float c_0 = low;
                float c_1 = high;
                for (int32_t iter = 0; iter < 6; ++iter) {
                    float sum0 = 0.0f, sum1 = 0.0f;
                    int32_t n0 = 0, n1 = 0;
                    for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                        float val = pairMeans[bit];
                        if (std::abs(val - c_0) <= std::abs(val - c_1)) {
                            sum0 += val; n0++;
                        } else {
                            sum1 += val; n1++;
                        }
                    }
                    if (n0 > 0) c_0 = sum0 / static_cast<float>(n0);
                    if (n1 > 0) c_1 = sum1 / static_cast<float>(n1);
                }

                float threshold = (c_0 + c_1) * 0.5f;
                int32_t bits[HEADER_BITS];
                for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                    bits[bit] = (pairMeans[bit] > threshold) ? 1 : 0;
                }

                NativeHeader candidate;
                candidate.row = row;
                candidate.shift = shift;
                candidate.reversed = reversed;
                candidate.contrast = contrast;

                if (decodeHeaderBits(bits, candidate)) {
                    return candidate;
                }

                if (candidate.score < bestHeader.score ||
                    (candidate.score == bestHeader.score && contrast > bestHeader.contrast)) {
                    bestHeader = candidate;
                }
            }
        }
    }

    return bestHeader;
}

bool isPilotCell(const NativeProfile& profile, int32_t row, int32_t col) {
    if (row < HEADER_ROWS) return false;
    int32_t flat = (row - HEADER_ROWS) * profile.cols + col;
    return (flat % PILOT_PERIOD) == 0;
}

int32_t pilotSymbol(const NativeProfile& profile, int32_t row, int32_t col, int32_t frameIndex) {
    int32_t flat = (row - HEADER_ROWS) * profile.cols + col;
    return ((flat / PILOT_PERIOD) + frameIndex) & 7;
}

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
) {
    double sumY[8] = {0};
    double sumU[8] = {0};
    double sumV[8] = {0};
    double sumU2[8] = {0};
    double sumV2[8] = {0};
    int32_t count[8] = {0};

    int32_t offset = config.fiducialOffsetCells;
    int32_t cols = profile.cols;
    int32_t rows = profile.rows;

    for (int32_t r = HEADER_ROWS; r < rows; ++r) {
        float v = static_cast<float>(r + offset);
        for (int32_t c = 0; c < cols; ++c) {
            if (!isPilotCell(profile, r, c)) continue;
            int32_t sym = pilotSymbol(profile, r, c, frameIndex);

            float u = static_cast<float>(c + offset);
            float camX, camY;
            H.map(u, v, camX, camY);

            int32_t ix = static_cast<int32_t>(std::round(camX));
            int32_t iy = static_cast<int32_t>(std::round(camY));

            uint8_t yVal = yPlane.sample(ix, iy);
            uint8_t uVal = uPlane.sample(ix / 2, iy / 2);
            uint8_t vVal = vPlane.sample(ix / 2, iy / 2);

            sumY[sym] += yVal;
            sumU[sym] += uVal;
            sumV[sym] += vVal;
            sumU2[sym] += static_cast<double>(uVal) * uVal;
            sumV2[sym] += static_cast<double>(vVal) * vVal;
            count[sym]++;
        }
    }

    for (int32_t i = 0; i < 8; ++i) {
        if (count[i] < 4) return false;
    }

    double darkSum = 0.0;
    double lightSum = 0.0;

    for (int32_t i = 0; i < 8; ++i) {
        double n = static_cast<double>(count[i]);
        double meanU = sumU[i] / n;
        double meanV = sumV[i] / n;
        double meanY = sumY[i] / n;
        double varUv = std::max(0.0, sumU2[i] / n - meanU * meanU) +
                       std::max(0.0, sumV2[i] / n - meanV * meanV);

        outCentroids[i].y = static_cast<float>(meanY);
        outCentroids[i].u = static_cast<float>(meanU);
        outCentroids[i].v = static_cast<float>(meanV);
        outCentroids[i].sigmaUv = static_cast<float>(std::sqrt(varUv));
        outCentroids[i].count = count[i];

        if (i < 4) darkSum += meanY;
        else lightSum += meanY;
    }

    float darkMean = static_cast<float>(darkSum / 4.0);
    float lightMean = static_cast<float>(lightSum / 4.0);

    outLumaThreshold = (darkMean + lightMean) * 0.5f;
    outLumaHalfGap = std::max(1.0f, std::abs(lightMean - darkMean) * 0.5f);

    float minUv = 1e30f;
    for (int32_t luma = 0; luma <= 1; ++luma) {
        int32_t start = luma * 4;
        for (int32_t a = start; a < start + 4; ++a) {
            for (int32_t b = a + 1; b < start + 4; ++b) {
                float du = outCentroids[a].u - outCentroids[b].u;
                float dv = outCentroids[a].v - outCentroids[b].v;
                float dist = std::sqrt(du * du + dv * dv);
                if (dist < minUv) minUv = dist;
            }
        }
    }
    outPilotMinUvDistance = std::isfinite(minUv) ? minUv : 0.0f;
    return true;
}

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
) {
    int32_t offset = config.fiducialOffsetCells;
    int32_t cols = profile.cols;
    int32_t rows = profile.rows;
    int32_t cursor = 0;

    outClassified = 0;
    outErasures = 0;

    for (int32_t r = HEADER_ROWS; r < rows; ++r) {
        float v = static_cast<float>(r + offset);
        for (int32_t c = 0; c < cols; ++c) {
            if (isPilotCell(profile, r, c)) continue;

            float u = static_cast<float>(c + offset);
            float camX, camY;
            H.map(u, v, camX, camY);

            int32_t ix = static_cast<int32_t>(std::round(camX));
            int32_t iy = static_cast<int32_t>(std::round(camY));

            uint8_t yVal = yPlane.sample(ix, iy);
            uint8_t uVal = uPlane.sample(ix / 2, iy / 2);
            uint8_t vVal = vPlane.sample(ix / 2, iy / 2);

            bool isErasure = false;
            int32_t sym = classify_cell(
                yVal, uVal, vVal,
                centroids,
                lumaThreshold, lumaHalfGap,
                config.lumaErasureFraction, config.chromaMarginThreshold,
                &isErasure
            );

            if (isErasure) {
                outErasures++;
            } else {
                outClassified++;
            }
            if (outPayloadSymbols) {
                outPayloadSymbols[cursor] = static_cast<uint8_t>(sym & 7);
            }
            cursor++;
        }
    }
}

NativeResult decodeFrameDirect(
    const NativePlane& yPlane,
    const NativePlane& uPlane,
    const NativePlane& vPlane,
    const NativeQuad& quad,
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outPayloadSymbols
) {
    NativeResult result;
    auto tStart = std::chrono::high_resolution_clock::now();

    int32_t outerCols = profile.cols + config.fiducialOffsetCells * 2;
    int32_t outerRows = profile.rows + config.fiducialOffsetCells * 2;

    NativeHomography H;
    if (!computeHomography(quad, outerCols, outerRows, H)) {
        result.stage = STAGE_GEOMETRY;
        result.success = false;
        return result;
    }

    // 1. Header stage
    auto tHeaderStart = std::chrono::high_resolution_clock::now();
    result.header = probeHeaderDirect(yPlane, H, profile, config);
    auto tHeaderEnd = std::chrono::high_resolution_clock::now();
    result.headerMs = std::chrono::duration<double, std::milli>(tHeaderEnd - tHeaderStart).count();

    if (!result.header.valid) {
        result.stage = STAGE_HEADER;
        result.success = false;
        auto tTotalEnd = std::chrono::high_resolution_clock::now();
        result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
        return result;
    }

    // 2. Pilot stage
    auto tPilotStart = std::chrono::high_resolution_clock::now();
    float lumaThreshold = 0.0f;
    float lumaHalfGap = 1.0f;
    float pilotMinUvDistance = 0.0f;

    bool pilotsOk = calibratePilotsDirect(
        yPlane, uPlane, vPlane, H, profile, config,
        result.header.frameIndex,
        result.centroids,
        lumaThreshold, lumaHalfGap, pilotMinUvDistance
    );
    auto tPilotEnd = std::chrono::high_resolution_clock::now();
    result.pilotMs = std::chrono::duration<double, std::milli>(tPilotEnd - tPilotStart).count();

    if (!pilotsOk) {
        result.stage = STAGE_PILOTS;
        result.success = false;
        auto tTotalEnd = std::chrono::high_resolution_clock::now();
        result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
        return result;
    }

    result.lumaThreshold = lumaThreshold;
    result.pilotMinUvDistance = pilotMinUvDistance;

    // 3. Payload stage
    auto tPayloadStart = std::chrono::high_resolution_clock::now();
    decodePayloadDirect(
        yPlane, uPlane, vPlane, H, profile, config,
        result.centroids,
        lumaThreshold, lumaHalfGap,
        outPayloadSymbols,
        result.classifiedSymbols,
        result.erasures
    );
    auto tPayloadEnd = std::chrono::high_resolution_clock::now();
    result.payloadMs = std::chrono::duration<double, std::milli>(tPayloadEnd - tPayloadStart).count();

    result.stage = STAGE_PAYLOAD;
    result.success = true;

    auto tTotalEnd = std::chrono::high_resolution_clock::now();
    result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
    return result;
}

static NativeHeader probeHeaderFromCellMeans(
    const uint8_t* cellMeansY,
    const NativeProfile& profile
) {
    NativeHeader bestHeader;
    bestHeader.valid = false;
    bestHeader.score = 12;

    int32_t cols = profile.cols;
    if (cols < HEADER_CELLS || profile.rows < HEADER_ROWS) return bestHeader;
    int32_t headerRows = HEADER_ROWS;
    int32_t maxShift = 0;
    NativeHeader confirmed;
    int32_t confirmations = 0;
    std::array<float, HEADER_BITS> pairMeans;

    for (int32_t row = 0; row < headerRows; ++row) {
        for (bool reversed : { false }) {
            for (int32_t shift = 0; shift <= maxShift; ++shift) {
                float low = 255.0f;
                float high = 0.0f;

                for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                    int32_t c0 = reversed ? (cols - 1 - shift - (bit * 2)) : (shift + (bit * 2));
                    int32_t c1 = reversed ? (cols - 1 - shift - (bit * 2 + 1)) : (shift + (bit * 2 + 1));
                    uint8_t a = cellMeansY[row * cols + c0];
                    uint8_t b = cellMeansY[row * cols + c1];
                    float mean = (static_cast<float>(a) + static_cast<float>(b)) * 0.5f;
                    pairMeans[bit] = mean;
                    if (mean < low) low = mean;
                    if (mean > high) high = mean;
                }

                float contrast = high - low;
                if (contrast < 12.0f) continue;

                float c_0 = low;
                float c_1 = high;
                for (int32_t iter = 0; iter < 6; ++iter) {
                    float sum0 = 0.0f, sum1 = 0.0f;
                    int32_t n0 = 0, n1 = 0;
                    for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                        float val = pairMeans[bit];
                        if (std::abs(val - c_0) <= std::abs(val - c_1)) {
                            sum0 += val; n0++;
                        } else {
                            sum1 += val; n1++;
                        }
                    }
                    if (n0 > 0) c_0 = sum0 / static_cast<float>(n0);
                    if (n1 > 0) c_1 = sum1 / static_cast<float>(n1);
                }

                float threshold = (c_0 + c_1) * 0.5f;
                int32_t bits[HEADER_BITS];
                for (int32_t bit = 0; bit < HEADER_BITS; ++bit) {
                    bits[bit] = (pairMeans[bit] > threshold) ? 1 : 0;
                }

                NativeHeader candidate;
                candidate.row = row;
                candidate.shift = shift;
                candidate.reversed = reversed;
                candidate.contrast = contrast;

                if (decodeHeaderBits(bits, candidate) &&
                    candidate.version == profile.version && candidate.profileId == profile.profileId &&
                    candidate.fps == profile.fps && candidate.seed == profile.seed) {
                    if (confirmations > 0 && candidate.frameIndex != confirmed.frameIndex) {
                        return bestHeader;
                    }
                    confirmed = candidate;
                    confirmations++;
                }
                candidate.valid = false;
                if (candidate.score < bestHeader.score ||
                    (candidate.score == bestHeader.score && contrast > bestHeader.contrast)) {
                    bestHeader = candidate;
                }
            }
        }
    }

    if (confirmations == HEADER_ROWS) return confirmed;
    __android_log_print(ANDROID_LOG_DEBUG, "ColorGrid8Native",
        "probeHeader FAIL bestRow=%d shift=%d rev=%d contrast=%.1f score=%d magic=0x%03X",
        bestHeader.row, bestHeader.shift, bestHeader.reversed ? 1 : 0,
        bestHeader.contrast, bestHeader.score, bestHeader.rawMagic);
    return bestHeader;
}

static bool calibratePilotsFromCellMeans(
    const uint8_t* cellMeansY,
    const uint8_t* cellMeansU,
    const uint8_t* cellMeansV,
    const NativeProfile& profile,
    int32_t frameIndex,
    int32_t hintRow,
    int32_t hintShift,
    NativeCentroid* outCentroids,
    float& outLumaThreshold,
    float& outLumaHalfGap,
    float& outPilotMinUvDistance,
    int32_t& outBestDeltaRow,
    int32_t& outBestDeltaCol
) {
    int32_t cols = profile.cols;
    int32_t rows = profile.rows;

    // GPU output already has one sample per canonical cell. Searching shifted
    // payloads both invents alignment on noise and costs 121 full-grid scans.
    const int32_t bestDr = 0;
    const int32_t bestDc = 0;
    (void)hintRow;
    (void)hintShift;

    outBestDeltaRow = bestDr;
    outBestDeltaCol = bestDc;

    double sumY[8] = {0};
    double sumU[8] = {0};
    double sumV[8] = {0};
    double sumU2[8] = {0};
    double sumV2[8] = {0};
    int32_t count[8] = {0};

    for (int32_t r = HEADER_ROWS; r < rows; ++r) {
        int32_t physR = r + bestDr;
        if (physR < 0 || physR >= rows) continue;
        for (int32_t c = 0; c < cols; ++c) {
            if (!isPilotCell(profile, r, c)) continue;
            int32_t physC = c + bestDc;
            if (physC < 0 || physC >= cols) continue;

            int32_t sym = pilotSymbol(profile, r, c, frameIndex);

            int32_t idx = physR * cols + physC;
            uint8_t yVal = cellMeansY[idx];
            uint8_t uVal = cellMeansU[idx];
            uint8_t vVal = cellMeansV[idx];

            sumY[sym] += yVal;
            sumU[sym] += uVal;
            sumV[sym] += vVal;
            sumU2[sym] += static_cast<double>(uVal) * uVal;
            sumV2[sym] += static_cast<double>(vVal) * vVal;
            count[sym]++;
        }
    }

    for (int32_t i = 0; i < 8; ++i) {
        if (count[i] < 4) return false;
    }

    double darkSum = 0.0;
    double lightSum = 0.0;

    for (int32_t i = 0; i < 8; ++i) {
        double n = static_cast<double>(count[i]);
        double meanU = sumU[i] / n;
        double meanV = sumV[i] / n;
        double meanY = sumY[i] / n;
        double varUv = std::max(0.0, sumU2[i] / n - meanU * meanU) +
                       std::max(0.0, sumV2[i] / n - meanV * meanV);

        outCentroids[i].y = static_cast<float>(meanY);
        outCentroids[i].u = static_cast<float>(meanU);
        outCentroids[i].v = static_cast<float>(meanV);
        outCentroids[i].sigmaUv = static_cast<float>(std::sqrt(varUv));
        outCentroids[i].count = count[i];

        if (i < 4) darkSum += meanY;
        else lightSum += meanY;
    }

    float darkMean = static_cast<float>(darkSum / 4.0);
    float lightMean = static_cast<float>(lightSum / 4.0);

    outLumaThreshold = (darkMean + lightMean) * 0.5f;
    outLumaHalfGap = std::max(1.0f, std::abs(lightMean - darkMean) * 0.5f);

    float minUv = 1e30f;
    for (int32_t luma = 0; luma <= 1; ++luma) {
        int32_t start = luma * 4;
        for (int32_t a = start; a < start + 4; ++a) {
            for (int32_t b = a + 1; b < start + 4; ++b) {
                float du = outCentroids[a].u - outCentroids[b].u;
                float dv = outCentroids[a].v - outCentroids[b].v;
                float dist = std::sqrt(du * du + dv * dv);
                if (dist < minUv) minUv = dist;
            }
        }
    }
    outPilotMinUvDistance = std::isfinite(minUv) ? minUv : 0.0f;

    __android_log_print(ANDROID_LOG_DEBUG, "ColorGrid8Native",
        "Pilots calibrated: dr=%d dc=%d gap=%.1f lumaThresh=%.1f minUv=%.1f c0Y=%.1f c4Y=%.1f",
        bestDr, bestDc, lightMean - darkMean, outLumaThreshold, outPilotMinUvDistance, outCentroids[0].y, outCentroids[4].y);

    // Do not call overlapping color clusters a calibrated optical channel.
    return lightMean - darkMean >= 12.0f && outPilotMinUvDistance >= 8.0f;
}

static void decodePayloadFromCellMeans(
    const uint8_t* cellMeansY,
    const uint8_t* cellMeansU,
    const uint8_t* cellMeansV,
    const NativeProfile& profile,
    const NativeConfig& config,
    const NativeCentroid* centroids,
    float lumaThreshold,
    float lumaHalfGap,
    int32_t deltaRow,
    int32_t deltaCol,
    uint8_t* outPayloadSymbols,
    int32_t& outClassified,
    int32_t& outErasures
) {
    int32_t cols = profile.cols;
    int32_t rows = profile.rows;
    int32_t cursor = 0;

    outClassified = 0;
    outErasures = 0;

    for (int32_t r = HEADER_ROWS; r < rows; ++r) {
        int32_t physR = r + deltaRow;
        for (int32_t c = 0; c < cols; ++c) {
            if (isPilotCell(profile, r, c)) continue;

            int32_t physC = c + deltaCol;
            if (physR < 0 || physR >= rows || physC < 0 || physC >= cols) {
                outErasures++;
                if (outPayloadSymbols) outPayloadSymbols[cursor] = 0;
            } else {
                int32_t idx = physR * cols + physC;
                uint8_t yVal = cellMeansY[idx];
                uint8_t uVal = cellMeansU[idx];
                uint8_t vVal = cellMeansV[idx];

                bool isErasure = false;
                int32_t sym = classify_cell(
                    yVal, uVal, vVal,
                    centroids,
                    lumaThreshold, lumaHalfGap,
                    config.lumaErasureFraction, config.chromaMarginThreshold,
                    &isErasure
                );

                if (isErasure) {
                    outErasures++;
                } else {
                    outClassified++;
                }
                if (outPayloadSymbols) {
                    outPayloadSymbols[cursor] = static_cast<uint8_t>(sym & 7);
                }
            }
            cursor++;
        }
    }
}

NativeResult decodeFromCellMeans(
    const uint8_t* cellMeansY,
    const uint8_t* cellMeansU,
    const uint8_t* cellMeansV,
    const NativeProfile& profile,
    const NativeConfig& config,
    uint8_t* outPayloadSymbols
) {
    NativeResult result;
    auto tStart = std::chrono::high_resolution_clock::now();

    // 1. Header stage
    auto tHeaderStart = std::chrono::high_resolution_clock::now();
    result.header = probeHeaderFromCellMeans(cellMeansY, profile);
    auto tHeaderEnd = std::chrono::high_resolution_clock::now();
    result.headerMs = std::chrono::duration<double, std::milli>(tHeaderEnd - tHeaderStart).count();

    if (!result.header.valid) {
        result.stage = STAGE_HEADER;
        result.success = false;
        auto tTotalEnd = std::chrono::high_resolution_clock::now();
        result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
        return result;
    }

    // 2. Pilot stage
    auto tPilotStart = std::chrono::high_resolution_clock::now();
    float lumaThreshold = 0.0f;
    float lumaHalfGap = 1.0f;
    float pilotMinUvDistance = 0.0f;
    int32_t bestDeltaRow = 0;
    int32_t bestDeltaCol = 0;

    bool pilotsOk = calibratePilotsFromCellMeans(
        cellMeansY, cellMeansU, cellMeansV, profile,
        result.header.frameIndex,
        result.header.row,
        result.header.shift,
        result.centroids,
        lumaThreshold, lumaHalfGap, pilotMinUvDistance,
        bestDeltaRow, bestDeltaCol
    );
    auto tPilotEnd = std::chrono::high_resolution_clock::now();
    result.pilotMs = std::chrono::duration<double, std::milli>(tPilotEnd - tPilotStart).count();

    if (!pilotsOk) {
        result.stage = STAGE_PILOTS;
        result.success = false;
        auto tTotalEnd = std::chrono::high_resolution_clock::now();
        result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
        return result;
    }

    result.lumaThreshold = lumaThreshold;
    result.pilotMinUvDistance = pilotMinUvDistance;

    // 3. Payload stage
    auto tPayloadStart = std::chrono::high_resolution_clock::now();
    decodePayloadFromCellMeans(
        cellMeansY, cellMeansU, cellMeansV, profile, config,
        result.centroids,
        lumaThreshold, lumaHalfGap,
        bestDeltaRow, bestDeltaCol,
        outPayloadSymbols,
        result.classifiedSymbols,
        result.erasures
    );
    auto tPayloadEnd = std::chrono::high_resolution_clock::now();
    result.payloadMs = std::chrono::duration<double, std::milli>(tPayloadEnd - tPayloadStart).count();

    if (outPayloadSymbols && result.classifiedSymbols > 8) {
        __android_log_print(ANDROID_LOG_DEBUG, "ColorGrid8Native",
            "Payload decoded: dr=%d dc=%d sym0..7=[%d,%d,%d,%d,%d,%d,%d,%d] erasures=%d classified=%d",
            bestDeltaRow, bestDeltaCol,
            outPayloadSymbols[0], outPayloadSymbols[1], outPayloadSymbols[2], outPayloadSymbols[3],
            outPayloadSymbols[4], outPayloadSymbols[5], outPayloadSymbols[6], outPayloadSymbols[7],
            result.erasures, result.classifiedSymbols);
    }

    result.stage = STAGE_PAYLOAD;
    result.success = true;

    auto tTotalEnd = std::chrono::high_resolution_clock::now();
    result.totalMs = std::chrono::duration<double, std::milli>(tTotalEnd - tStart).count();
    return result;
}

} // namespace colorgrid8
} // namespace superqr
