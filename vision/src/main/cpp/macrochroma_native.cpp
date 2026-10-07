#include "macrochroma_native.h"
#include <chrono>
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "MacroNative", __VA_ARGS__)

namespace superqr::macrochroma {

MacrochromaNativeDecoder::MacrochromaNativeDecoder() {
    initGf();
}

void MacrochromaNativeDecoder::initGf() {
    int x = 1;
    for (int i = 0; i < 255; ++i) {
        gfExp[i] = static_cast<uint8_t>(x);
        gfExp[i + 255] = static_cast<uint8_t>(x);
        gfLog[x] = static_cast<uint8_t>(i);
        x <<= 1;
        if (x & 0x100) {
            x ^= 0x11D;
        }
    }
    gfLog[0] = 0;

    // Compute generator polynomial for t=4 parity symbols
    uint8_t g[TILE_INNER_FEC_BYTES + 1] = {1, 0, 0, 0, 0};
    int gLen = 1;
    for (int k = 1; k <= TILE_INNER_FEC_BYTES; ++k) {
        uint8_t root = gfExp[k];
        uint8_t nextG[TILE_INNER_FEC_BYTES + 1] = {0};
        for (int i = 0; i < gLen; ++i) {
            nextG[i] ^= g[i];
            nextG[i + 1] ^= gfMul(g[i], root);
        }
        gLen++;
        for (int i = 0; i < gLen; ++i) {
            g[i] = nextG[i];
        }
    }
    for (int i = 0; i <= TILE_INNER_FEC_BYTES; ++i) {
        rsGen[i] = g[i];
    }
}

uint8_t MacrochromaNativeDecoder::gfMul(uint8_t a, uint8_t b) const {
    if (a == 0 || b == 0) return 0;
    return gfExp[gfLog[a] + gfLog[b]];
}

uint8_t MacrochromaNativeDecoder::gfInv(uint8_t a) const {
    if (a == 0) return 0;
    return gfExp[255 - gfLog[a]];
}

uint16_t MacrochromaNativeDecoder::crc16(const uint8_t* data, size_t len) const {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; ++i) {
        crc ^= (static_cast<uint16_t>(data[i]) << 8);
        for (int bit = 0; bit < 8; ++bit) {
            if (crc & 0x8000) {
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
            } else {
                crc = (crc << 1) & 0xFFFF;
            }
        }
    }
    return crc;
}

void MacrochromaNativeDecoder::encodeRs(const uint8_t* msg, size_t msgLen, uint8_t* parityOut) const {
    std::memset(parityOut, 0, TILE_INNER_FEC_BYTES);
    for (size_t i = 0; i < msgLen; ++i) {
        uint8_t coef = msg[i] ^ parityOut[0];
        if (coef != 0) {
            for (int j = 1; j <= TILE_INNER_FEC_BYTES; ++j) {
                uint8_t p = (j < TILE_INNER_FEC_BYTES) ? parityOut[j] : 0;
                parityOut[j - 1] = p ^ gfMul(rsGen[j], coef);
            }
        } else {
            for (int j = 0; j < TILE_INNER_FEC_BYTES - 1; ++j) {
                parityOut[j] = parityOut[j + 1];
            }
            parityOut[TILE_INNER_FEC_BYTES - 1] = 0;
        }
    }
}

uint16_t crc16(const uint8_t* data, size_t len) {
    static MacrochromaNativeDecoder s_decoder;
    return s_decoder.crc16(data, len);
}

void encodeRs(const uint8_t* msg, size_t msgLen, uint8_t* parityOut) {
    static MacrochromaNativeDecoder s_decoder;
    s_decoder.encodeRs(msg, msgLen, parityOut);
}

bool MacrochromaNativeDecoder::decodeRs(uint8_t* codeword, bool& wasCorrected) const {
    wasCorrected = false;
    constexpr int n = TILE_RAW_BYTES; // 180
    constexpr int nSym = TILE_INNER_FEC_BYTES; // 4

    // 1. Calculate syndromes
    uint8_t syndromes[nSym] = {0};
    bool hasError = false;
    for (int j = 0; j < nSym; ++j) {
        uint8_t val = 0;
        int expJ = j + 1;
        for (int i = 0; i < n; ++i) {
            int power = ((n - 1 - i) * expJ) % 255;
            val ^= gfMul(codeword[i], gfExp[power]);
        }
        syndromes[j] = val;
        if (val != 0) hasError = true;
    }

    if (!hasError) {
        return true; // Clean codeword
    }

    // 2. Berlekamp-Massey for error locator polynomial Lambda(x)
    uint8_t c[nSym + 1] = {1, 0, 0, 0, 0};
    uint8_t b[nSym + 1] = {1, 0, 0, 0, 0};
    int cLen = 1;
    int bLen = 1;
    int l = 0;
    int m = 1;

    for (int k = 0; k < nSym; ++k) {
        uint8_t d = syndromes[k];
        for (int i = 1; i <= l; ++i) {
            if (i < cLen) {
                d ^= gfMul(c[i], syndromes[k - i]);
            }
        }
        if (d == 0) {
            m++;
        } else {
            uint8_t t[nSym + 1];
            int tLen = cLen;
            std::memcpy(t, c, sizeof(c));

            uint8_t scaledB[nSym + 1] = {0};
            int scaledBLen = bLen + m;
            if (scaledBLen > nSym + 1) scaledBLen = nSym + 1;
            for (int i = 0; i < bLen && (i + m) <= nSym; ++i) {
                scaledB[i + m] = gfMul(d, b[i]);
            }

            int maxLen = std::max(cLen, scaledBLen);
            for (int i = 0; i < maxLen; ++i) {
                c[i] ^= scaledB[i];
            }
            cLen = maxLen;

            if (2 * l <= k) {
                l = k + 1 - l;
                uint8_t invD = gfInv(d);
                for (int i = 0; i < tLen; ++i) {
                    b[i] = gfMul(t[i], invD);
                }
                bLen = tLen;
                m = 1;
            } else {
                m++;
            }
        }
    }

    // Trim trailing zeros from Lambda
    while (cLen > 1 && c[cLen - 1] == 0) {
        cLen--;
    }
    int deg = cLen - 1;
    if (deg == 0 || deg > 2) {
        return false; // Beyond 2-error capacity
    }

    // 3. Chien search for error positions
    int errorPositions[2] = {-1, -1};
    int foundErrors = 0;
    for (int i = 0; i < n; ++i) {
        int p = n - 1 - i;
        uint8_t invX = gfExp[(255 - (p % 255)) % 255];
        uint8_t sumVal = 0;
        uint8_t term = 1;
        for (int j = 0; j < cLen; ++j) {
            sumVal ^= gfMul(c[j], term);
            term = gfMul(term, invX);
        }
        if (sumVal == 0) {
            if (foundErrors < 2) {
                errorPositions[foundErrors] = i;
            }
            foundErrors++;
        }
    }

    if (foundErrors != deg) {
        return false;
    }

    // 4. Forney algorithm for error magnitudes
    uint8_t omega[nSym] = {0};
    for (int i = 0; i < nSym; ++i) {
        for (int j = 0; j < cLen; ++j) {
            if (i - j >= 0) {
                omega[i] ^= gfMul(syndromes[i - j], c[j]);
            }
        }
    }

    uint8_t lambdaPrime[nSym] = {0};
    for (int i = 1; i < cLen; i += 2) {
        lambdaPrime[i - 1] = c[i];
    }

    for (int eIdx = 0; eIdx < foundErrors; ++eIdx) {
        int pos = errorPositions[eIdx];
        int p = n - 1 - pos;
        uint8_t invX = gfExp[(255 - (p % 255)) % 255];

        uint8_t omegaVal = 0;
        uint8_t term = 1;
        for (int j = 0; j < nSym; ++j) {
            omegaVal ^= gfMul(omega[j], term);
            term = gfMul(term, invX);
        }

        uint8_t lambdaPrimeVal = 0;
        term = 1;
        for (int j = 0; j < nSym; ++j) {
            lambdaPrimeVal ^= gfMul(lambdaPrime[j], term);
            term = gfMul(term, invX);
        }

        if (lambdaPrimeVal == 0) return false;
        uint8_t mag = gfMul(omegaVal, gfInv(lambdaPrimeVal));
        codeword[pos] ^= mag;
    }

    wasCorrected = true;
    return true;
}

MacrochromaFrameResult MacrochromaNativeDecoder::decodeFrame(
    const uint8_t* cellMeansY,
    const uint8_t* cellMeansU,
    const uint8_t* cellMeansV,
    int cols,
    int rows
) {
    auto tStart = std::chrono::high_resolution_clock::now();
    MacrochromaFrameResult result;
    if (cols != 480 || rows != 388) {
        return result;
    }

    // 1. Header Decode (top 4 rows)
    auto tHeaderStart = std::chrono::high_resolution_clock::now();
    uint8_t minY = 255, maxY = 0;
    uint32_t sumY = 0;
    for (int r = 0; r < HEADER_ROWS; ++r) {
        for (int c = 0; c < cols; ++c) {
            uint8_t y = cellMeansY[r * cols + c];
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            sumY += y;
        }
    }

    constexpr int headerBitLen = 80;
    uint8_t headerBytes[10] = {0};
    result.headerValid = false;
    result.header.valid = false;

    bool headerAtBottom = false;
    static int s_logCounter = 0;
    bool shouldLog = (++s_logCounter % 40 == 0);

    for (int isBottom : {0, 1}) {
        int rOffset = isBottom ? (rows - HEADER_ROWS) : 0;

        // Compute min/max Y for this candidate region
        uint8_t localMinY = 255, localMaxY = 0;
        uint32_t localSumY = 0;
        for (int r = 0; r < HEADER_ROWS; ++r) {
            for (int c = 0; c < cols; ++c) {
                uint8_t y = cellMeansY[(rOffset + r) * cols + c];
                if (y < localMinY) localMinY = y;
                if (y > localMaxY) localMaxY = y;
                localSumY += y;
            }
        }

        uint8_t mid = static_cast<uint8_t>((localMinY + localMaxY) / 2);
        uint8_t avg = static_cast<uint8_t>(localSumY / (HEADER_ROWS * cols));
        uint8_t spanY = localMaxY - localMinY;
        uint8_t candidateThresh[] = {
            mid,
            avg,
            static_cast<uint8_t>(localMinY + (spanY * 4) / 10),
            static_cast<uint8_t>(localMinY + (spanY * 6) / 10),
            128,
            100,
            140
        };

        for (uint8_t thresh : candidateThresh) {
            std::memset(headerBytes, 0, sizeof(headerBytes));
            for (int bitIdx = 0; bitIdx < headerBitLen; ++bitIdx) {
                int votes = 0;
                for (int r = 0; r < HEADER_ROWS; ++r) {
                    for (int col = bitIdx; col < cols; col += headerBitLen) {
                        uint8_t y = cellMeansY[(rOffset + r) * cols + col];
                        if (y > thresh) votes++;
                        else votes--;
                    }
                }
                if (votes > 0) {
                    headerBytes[bitIdx / 8] |= (1 << (7 - (bitIdx % 8)));
                }
            }

            uint16_t magic = (static_cast<uint16_t>(headerBytes[0]) << 8) | headerBytes[1];
            uint8_t verFlag = headerBytes[2];
            uint8_t ver = (verFlag >> 4) & 0x0F;
            uint8_t frameIdxHigh = verFlag & 0x0F;
            uint8_t frameIdxLow = headerBytes[3];
            int frameIdx = (frameIdxHigh << 8) | frameIdxLow;
            uint32_t sessId = (static_cast<uint32_t>(headerBytes[4]) << 24) |
                              (static_cast<uint32_t>(headerBytes[5]) << 16) |
                              (static_cast<uint32_t>(headerBytes[6]) << 8) |
                              headerBytes[7];
            uint16_t expectedCrc = (static_cast<uint16_t>(headerBytes[8]) << 8) | headerBytes[9];
            uint16_t calcCrc = crc16(headerBytes, 8);

            if (shouldLog && thresh == candidateThresh[0]) {
                LOGD("loc=%s thresh=%d: minY=%d maxY=%d magic=0x%04X ver=%d fIdx=%d expCrc=0x%04X calcCrc=0x%04X b[0..3]=%02X %02X %02X %02X",
                     isBottom ? "BOT" : "TOP", thresh, localMinY, localMaxY, magic, ver, frameIdx, expectedCrc, calcCrc,
                     headerBytes[0], headerBytes[1], headerBytes[2], headerBytes[3]);
            }

            if (magic == HEADER_MAGIC && ver == MACROCHROMA_VERSION && expectedCrc == calcCrc) {
                result.header.magic = magic;
                result.header.version = ver;
                result.header.frameIndex = frameIdx;
                result.header.sessionId = sessId;
                result.header.valid = true;
                result.headerValid = true;
                headerAtBottom = (isBottom != 0);
                minY = localMinY;
                maxY = localMaxY;
                LOGD("HEADER VALID! loc=%s thresh=%d frameIdx=%d sessId=0x%08X",
                     isBottom ? "BOT" : "TOP", thresh, frameIdx, sessId);
                break;
            }
        }
        if (result.headerValid) break;
    }

    auto tHeaderEnd = std::chrono::high_resolution_clock::now();
    result.headerMs = std::chrono::duration<double, std::milli>(tHeaderEnd - tHeaderStart).count();

    // 2. Decode 320 Tiles (20 cols x 16 rows)
    auto tDecodeStart = std::chrono::high_resolution_clock::now();
    int tilesX = cols / TILE_FINE_W; // 20
    int tilesY = (rows - HEADER_ROWS) / TILE_FINE_H; // 16
    int totalTiles = tilesX * tilesY; // 320

    result.tiles.resize(totalTiles);
    int frameIdx = result.header.valid ? result.header.frameIndex : 0;

    int payloadStartRow = headerAtBottom ? 0 : HEADER_ROWS;
    int span = maxY - minY;
    uint8_t th1 = (span >= 40) ? static_cast<uint8_t>(minY + (span * 68) / 255) : 68;
    uint8_t th2 = (span >= 40) ? static_cast<uint8_t>(minY + (span * 124) / 255) : 124;
    uint8_t th3 = (span >= 40) ? static_cast<uint8_t>(minY + (span * 181) / 255) : 182;

    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            int tIdx = ty * tilesX + tx;
            NativeMacrochromaTile& tile = result.tiles[tIdx];

            // Extract 144 macroblocks (each 10 bits) -> pack into 180 raw bytes
            uint8_t rawTile[TILE_RAW_BYTES] = {0};
            uint32_t bitBuf = 0;
            int bitsInBuf = 0;
            int byteOutIdx = 0;

            for (int mbY = 0; mbY < TILE_MACRO_H; ++mbY) {
                for (int mbX = 0; mbX < TILE_MACRO_W; ++mbX) {
                    int topR = payloadStartRow + ty * TILE_FINE_H + mbY * 2;
                    int leftC = tx * TILE_FINE_W + mbX * 2;

                    // 4 Fine Luma Levels
                    auto quantLuma = [th1, th2, th3](uint8_t y) -> uint8_t {
                        if (y < th1) return 0;
                        if (y < th2) return 1;
                        if (y < th3) return 2;
                        return 3;
                    };
                    uint8_t y00 = quantLuma(cellMeansY[topR * cols + leftC]);
                    uint8_t y01 = quantLuma(cellMeansY[topR * cols + leftC + 1]);
                    uint8_t y10 = quantLuma(cellMeansY[(topR + 1) * cols + leftC]);
                    uint8_t y11 = quantLuma(cellMeansY[(topR + 1) * cols + leftC + 1]);

                    // Macroblock Chroma Quadrant (U, V)
                    uint8_t uVal = cellMeansU[topR * cols + leftC];
                    uint8_t vVal = cellMeansV[topR * cols + leftC];
                    uint8_t chroma = 0;
                    if (uVal < 128 && vVal >= 128) chroma = 0; // Red
                    else if (uVal < 128 && vVal < 128) chroma = 1; // Green
                    else if (uVal >= 128 && vVal < 128) chroma = 2; // Blue
                    else chroma = 3; // Magenta

                    uint16_t sym = (y00 << 8) | (y01 << 6) | (y10 << 4) | (y11 << 2) | chroma;
                    bitBuf = (bitBuf << BITS_PER_MACROBLOCK) | sym;
                    bitsInBuf += BITS_PER_MACROBLOCK;

                    while (bitsInBuf >= 8 && byteOutIdx < TILE_RAW_BYTES) {
                        bitsInBuf -= 8;
                        rawTile[byteOutIdx++] = static_cast<uint8_t>((bitBuf >> bitsInBuf) & 0xFF);
                    }
                }
            }

            // RS inner decode & CRC verification
            bool wasCorrected = false;
            bool rsOk = decodeRs(rawTile, wasCorrected);

            if (rsOk) {
                uint16_t tileCrc = (static_cast<uint16_t>(rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES]) << 8) |
                                   rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1];
                uint16_t computedCrc = crc16(rawTile, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);

                if (tileCrc == computedCrc) {
                    tile.valid = true;
                    tile.rsCorrected = wasCorrected;
                    uint8_t b0 = rawTile[0];
                    uint8_t b1 = rawTile[1];
                    tile.tileIndex = b0 | ((b1 & 0x01) << 8);
                    int rawMod64 = (b1 >> 1) & 0x3F;
                    int delta = rawMod64 - (frameIdx & 0x3F);
                    if (delta > 32) delta -= 64;
                    else if (delta < -32) delta += 64;
                    tile.frameIndex = frameIdx + delta;
                    tile.isParity = (b1 & 0x80) != 0;
                    std::memcpy(tile.payload, rawTile + TILE_HEADER_BYTES, TILE_PAYLOAD_BYTES);

                    result.validTiles++;
                    if (wasCorrected) result.rsCorrectedTiles++;
                } else {
                    result.failedTiles++;
                }
            } else {
                result.failedTiles++;
            }
        }
    }

    auto tDecodeEnd = std::chrono::high_resolution_clock::now();
    result.decodeMs = std::chrono::duration<double, std::milli>(tDecodeEnd - tDecodeStart).count();
    result.totalMs = std::chrono::duration<double, std::milli>(tDecodeEnd - tStart).count();

    return result;
}

} // namespace superqr::macrochroma
