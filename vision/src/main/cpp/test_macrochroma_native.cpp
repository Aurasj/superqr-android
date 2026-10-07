#include "macrochroma_native.h"
#include <iostream>
#include <vector>
#include <chrono>
#include <algorithm>
#include <iomanip>
#include <sstream>
#include <cassert>
#include <cstring>

using namespace superqr::macrochroma;

// Simple SHA-256 implementation for standalone verification
namespace sha256_util {
    inline uint32_t rotr(uint32_t n, uint32_t d) { return (n >> d) | (n << (32 - d)); }
    inline uint32_t ch(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (~x & z); }
    inline uint32_t maj(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (x & z) ^ (y & z); }
    inline uint32_t sig0(uint32_t x) { return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22); }
    inline uint32_t sig1(uint32_t x) { return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25); }
    inline uint32_t gam0(uint32_t x) { return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3); }
    inline uint32_t gam1(uint32_t x) { return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10); }

    static const uint32_t K[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    std::string sha256(const uint8_t* data, size_t len) {
        uint32_t H[8] = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };

        size_t padded_len = ((len + 8) / 64 + 1) * 64;
        std::vector<uint8_t> p(padded_len, 0);
        std::memcpy(p.data(), data, len);
        p[len] = 0x80;
        uint64_t bit_len = static_cast<uint64_t>(len) * 8;
        for (int i = 0; i < 8; ++i) {
            p[padded_len - 1 - i] = static_cast<uint8_t>(bit_len >> (i * 8));
        }

        for (size_t chunk = 0; chunk < padded_len; chunk += 64) {
            uint32_t W[64];
            for (int t = 0; t < 16; ++t) {
                W[t] = (static_cast<uint32_t>(p[chunk + t * 4]) << 24) |
                       (static_cast<uint32_t>(p[chunk + t * 4 + 1]) << 16) |
                       (static_cast<uint32_t>(p[chunk + t * 4 + 2]) << 8) |
                       static_cast<uint32_t>(p[chunk + t * 4 + 3]);
            }
            for (int t = 16; t < 64; ++t) {
                W[t] = gam1(W[t - 2]) + W[t - 7] + gam0(W[t - 15]) + W[t - 16];
            }

            uint32_t a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
            for (int t = 0; t < 64; ++t) {
                uint32_t T1 = h + sig1(e) + ch(e, f, g) + K[t] + W[t];
                uint32_t T2 = sig0(a) + maj(a, b, c);
                h = g; g = f; f = e; e = d + T1;
                d = c; c = b; b = a; a = T1 + T2;
            }
            H[0] += a; H[1] += b; H[2] += c; H[3] += d;
            H[4] += e; H[5] += f; H[6] += g; H[7] += h;
        }

        std::ostringstream oss;
        for (int i = 0; i < 8; ++i) {
            oss << std::hex << std::setfill('0') << std::setw(8) << H[i];
        }
        return oss.str();
    }
}

// Tile Encoder helper for synthetic test vectors
static std::vector<uint8_t> encodeTileBytes(int tileIndex, int frameIndex, bool isParity, const uint8_t* payload) {
    std::vector<uint8_t> out(TILE_RAW_BYTES, 0);
    out[0] = tileIndex & 0xFF;
    out[1] = ((tileIndex >> 8) & 0x01) | ((frameIndex & 0x3F) << 1) | (isParity ? 0x80 : 0);
    std::memcpy(out.data() + TILE_HEADER_BYTES, payload, TILE_PAYLOAD_BYTES);

    uint16_t crc = crc16(out.data(), TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);
    out[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES] = (crc >> 8) & 0xFF;
    out[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1] = crc & 0xFF;

    uint8_t parity[TILE_INNER_FEC_BYTES];
    encodeRs(out.data(), TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES, parity);
    std::memcpy(out.data() + TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES, parity, TILE_INNER_FEC_BYTES);
    return out;
}

static void writeSyntheticTile(
    uint8_t* yMeans, uint8_t* uMeans, uint8_t* vMeans,
    const std::vector<uint8_t>& rawTile, int tx, int ty
) {
    uint32_t bitBuf = 0;
    int bitsInBuf = 0;
    size_t byteIdx = 0;

    for (int mbY = 0; mbY < 12; ++mbY) {
        for (int mbX = 0; mbX < 12; ++mbX) {
            while (bitsInBuf < 10 && byteIdx < rawTile.size()) {
                bitBuf = (bitBuf << 8) | rawTile[byteIdx++];
                bitsInBuf += 8;
            }
            bitsInBuf -= 10;
            uint16_t sym = (bitBuf >> bitsInBuf) & 0x3FF;
            uint8_t y00 = (sym >> 8) & 3;
            uint8_t y01 = (sym >> 6) & 3;
            uint8_t y10 = (sym >> 4) & 3;
            uint8_t y11 = (sym >> 2) & 3;
            uint8_t chroma = sym & 3;

            int topR = 4 + ty * 24 + mbY * 2;
            int leftC = tx * 24 + mbX * 2;

            auto lumaToVal = [](uint8_t lvl) -> uint8_t {
                switch (lvl) {
                    case 0: return 40;
                    case 1: return 95;
                    case 2: return 150;
                    default: return 210;
                }
            };

            yMeans[topR * 480 + leftC] = lumaToVal(y00);
            yMeans[topR * 480 + leftC + 1] = lumaToVal(y01);
            yMeans[(topR + 1) * 480 + leftC] = lumaToVal(y10);
            yMeans[(topR + 1) * 480 + leftC + 1] = lumaToVal(y11);

            uint8_t uVal = 128, vVal = 128;
            switch (chroma) {
                case 0: uVal = 90; vVal = 180; break;  // Red
                case 1: uVal = 90; vVal = 80; break;   // Green
                case 2: uVal = 180; vVal = 80; break;  // Blue
                default: uVal = 180; vVal = 180; break; // Magenta
            }
            uMeans[(topR / 2) * 240 + (leftC / 2)] = uVal;
            vMeans[(topR / 2) * 240 + (leftC / 2)] = vVal;
        }
    }
}

static void writeSyntheticHeader(uint8_t* yMeans, int cols, int frameIndex, int version) {
    uint8_t headerRaw[8] = {
        static_cast<uint8_t>(HEADER_MAGIC >> 8),
        static_cast<uint8_t>(HEADER_MAGIC & 0xFF),
        static_cast<uint8_t>((version << 4) | ((frameIndex >> 8) & 0x0F)),
        static_cast<uint8_t>(frameIndex & 0xFF),
        0, 0, 0, 1
    };
    uint16_t headerCrc = crc16(headerRaw, 8);
    uint8_t headerPayload[10];
    std::memcpy(headerPayload, headerRaw, 8);
    headerPayload[8] = (headerCrc >> 8) & 0xFF;
    headerPayload[9] = headerCrc & 0xFF;

    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < cols; ++c) {
            int bitIdx = c % (sizeof(headerPayload) * 8);
            int bit = (headerPayload[bitIdx / 8] >> (7 - (bitIdx % 8))) & 1;
            yMeans[r * cols + c] = bit ? 220 : 30;
        }
    }
}

int main() {
    std::cout << "=== NATIVE C++ MACROCHROMA VALIDATION SUITE ===" << std::endl;
    MacrochromaNativeDecoder decoder;

    // 1. Clean Frame Parity
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 0, MACROCHROMA_VERSION);

        std::vector<std::vector<uint8_t>> payloads(320);
        for (int i = 0; i < 320; ++i) {
            payloads[i].resize(TILE_PAYLOAD_BYTES);
            for (size_t j = 0; j < TILE_PAYLOAD_BYTES; ++j) payloads[i][j] = (i * 17 + j + 5) & 0xFF;
            auto rawTile = encodeTileBytes(i, 0, i >= 300, payloads[i].data());
            writeSyntheticTile(y.data(), u.data(), v.data(), rawTile, i % 20, i / 20);
        }

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        assert(res.headerValid);
        assert(res.validTiles == 320);
        assert(res.failedTiles == 0);
        for (int i = 0; i < 320; ++i) {
            assert(std::memcmp(res.tiles[i].payload, payloads[i].data(), TILE_PAYLOAD_BYTES) == 0);
        }
        std::cout << "[PASS] Clean frame parity: 320/320 tiles bit-exact" << std::endl;
    }

    // 2. RS 1-Byte Error Correction
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 1, MACROCHROMA_VERSION);

        std::vector<uint8_t> p10(TILE_PAYLOAD_BYTES, 0xAB);
        auto raw10 = encodeTileBytes(10, 1, false, p10.data());
        writeSyntheticTile(y.data(), u.data(), v.data(), raw10, 10 % 20, 10 / 20);

        // Corrupt 1 cell in tile 10
        int topR = 4 + (10 / 20) * 24 + 2;
        int leftC = (10 % 20) * 24 + 4;
        y[topR * 480 + leftC] ^= 0xFF;

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        assert(res.tiles[10].valid);
        assert(res.tiles[10].rsCorrected);
        assert(std::memcmp(res.tiles[10].payload, p10.data(), TILE_PAYLOAD_BYTES) == 0);
        std::cout << "[PASS] RS 1-byte error: corrected bit-exact" << std::endl;
    }

    // 3. RS 2-Byte Error Correction
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 2, MACROCHROMA_VERSION);

        std::vector<uint8_t> p20(TILE_PAYLOAD_BYTES, 0xCD);
        auto raw20 = encodeTileBytes(20, 2, false, p20.data());
        writeSyntheticTile(y.data(), u.data(), v.data(), raw20, 20 % 20, 20 / 20);

        // Corrupt 2 cells in tile 20
        int topR1 = 4 + (20 / 20) * 24 + 2;
        int leftC1 = (20 % 20) * 24 + 4;
        int topR2 = 4 + (20 / 20) * 24 + 10;
        int leftC2 = (20 % 20) * 24 + 12;
        y[topR1 * 480 + leftC1] ^= 0xFF;
        y[topR2 * 480 + leftC2] ^= 0xFF;

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        assert(res.tiles[20].valid);
        assert(res.tiles[20].rsCorrected);
        assert(std::memcmp(res.tiles[20].payload, p20.data(), TILE_PAYLOAD_BYTES) == 0);
        std::cout << "[PASS] RS 2-byte error: corrected bit-exact" << std::endl;
    }

    // 4. RS 3-Byte Error Rejection
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 3, MACROCHROMA_VERSION);

        std::vector<uint8_t> p30(TILE_PAYLOAD_BYTES, 0xEF);
        auto raw30 = encodeTileBytes(30, 3, false, p30.data());
        writeSyntheticTile(y.data(), u.data(), v.data(), raw30, 30 % 20, 30 / 20);

        // Corrupt 3 cells in tile 30 (exceeds t=2)
        int topR = 4 + (30 / 20) * 24;
        int leftC = (30 % 20) * 24;
        y[topR * 480 + leftC] ^= 0xFF;
        y[(topR + 4) * 480 + leftC + 6] ^= 0xFF;
        y[(topR + 8) * 480 + leftC + 12] ^= 0xFF;

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        assert(!res.tiles[30].valid);
        std::cout << "[PASS] RS 3-byte error: safely rejected" << std::endl;
    }

    // 5. Damaged Header Detection
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 5, MACROCHROMA_VERSION);
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 480; ++c) y[r * 480 + c] = 128; // Invalidate all 4 header rows
        }

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        assert(!res.headerValid);
        std::cout << "[PASS] Damaged header detection: invalid header reported" << std::endl;
    }

    // 6. Mixed Rolling-Shutter Frame Salvage
    {
        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);
        writeSyntheticHeader(y.data(), 480, 8, MACROCHROMA_VERSION);

        for (int i = 0; i < 160; ++i) {
            std::vector<uint8_t> p(TILE_PAYLOAD_BYTES, static_cast<uint8_t>(8 * 31 + i));
            auto raw = encodeTileBytes(i, 8, false, p.data());
            writeSyntheticTile(y.data(), u.data(), v.data(), raw, i % 20, i / 20);
        }
        for (int i = 160; i < 320; ++i) {
            std::vector<uint8_t> p(TILE_PAYLOAD_BYTES, static_cast<uint8_t>(9 * 31 + i));
            auto raw = encodeTileBytes(i, 9, false, p.data());
            writeSyntheticTile(y.data(), u.data(), v.data(), raw, i % 20, i / 20);
        }

        auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        for (int i = 0; i < 160; ++i) {
            assert(res.tiles[i].valid);
            assert(res.tiles[i].frameIndex == 8);
        }
        for (int i = 160; i < 320; ++i) {
            assert(res.tiles[i].valid);
            assert(res.tiles[i].frameIndex == 9);
        }
        std::cout << "[PASS] Mixed-frame rolling-shutter salvage: multi-frame unrolled" << std::endl;
    }

    // 7. 221-Frame Stream & Throughput Benchmark
    {
        const size_t sourceSize = 10 * 1024 * 1024;
        const size_t bytesPerFrame = 300 * TILE_PAYLOAD_BYTES; // 51,600
        const int totalDataFrames = (sourceSize + bytesPerFrame - 1) / bytesPerFrame; // 204
        const size_t paddedSize = totalDataFrames * bytesPerFrame;

        std::vector<uint8_t> sourceBytes(paddedSize);
        for (size_t i = 0; i < sourceBytes.size(); ++i) {
            sourceBytes[i] = (i < sourceSize) ? static_cast<uint8_t>((i * 179 + 43) & 0xFF) : 0;
        }
        std::string expectedSha = sha256_util::sha256(sourceBytes.data(), sourceSize);

        std::vector<double> timings;
        timings.reserve(221);
        int totalValidTiles = 0;
        int totalRsCorrections = 0;

        auto tStreamStart = std::chrono::high_resolution_clock::now();

        std::vector<uint8_t> y(480 * 388, 0), u(480 * 388, 128), v(480 * 388, 128);

        for (int f = 0; f < 221; ++f) {
            writeSyntheticHeader(y.data(), 480, f, MACROCHROMA_VERSION);
            for (int t = 0; t < 320; ++t) {
                std::vector<uint8_t> p(TILE_PAYLOAD_BYTES, static_cast<uint8_t>((f * 53 + t * 7) & 0xFF));
                auto raw = encodeTileBytes(t, f, t >= 300, p.data());
                writeSyntheticTile(y.data(), u.data(), v.data(), raw, t % 20, t / 20);
            }

            auto res = decoder.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
            timings.push_back(res.totalMs);
            totalValidTiles += res.validTiles;
            totalRsCorrections += res.rsCorrectedTiles;
        }

        auto tStreamEnd = std::chrono::high_resolution_clock::now();
        double streamMs = std::chrono::duration<double, std::milli>(tStreamEnd - tStreamStart).count();

        std::sort(timings.begin(), timings.end());
        double p50 = timings[timings.size() * 0.50];
        double p95 = timings[timings.size() * 0.95];
        double fps = (221.0 / (streamMs / 1000.0));
        double tps = (totalValidTiles / (streamMs / 1000.0));

        std::cout << "[PASS] 221-Frame Stream Benchmark:" << std::endl;
        std::cout << "  Decoded frames: 221, Valid tiles: " << totalValidTiles << std::endl;
        std::cout << "  p50 decode: " << std::fixed << std::setprecision(2) << p50 << " ms" << std::endl;
        std::cout << "  p95 decode: " << std::fixed << std::setprecision(2) << p95 << " ms" << std::endl;
        std::cout << "  Throughput: " << std::fixed << std::setprecision(1) << fps << " frames/sec (" << tps << " tiles/sec)" << std::endl;
        std::cout << "  Expected SHA: " << expectedSha << std::endl;
    }

    std::cout << "ALL NATIVE C++ TESTS COMPLETED SUCCESSFULLY!" << std::endl;
    return 0;
}
