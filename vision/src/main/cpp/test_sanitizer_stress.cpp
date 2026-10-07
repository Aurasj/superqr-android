// Allow test access to private members
#define private public
#include "macrochroma_native.h"
#undef private
#include <iostream>
#include <vector>
#include <cstring>
#include <cassert>
#include <random>
#include <chrono>

using namespace superqr::macrochroma;

static int g_pass = 0;
static int g_fail = 0;

#define CHECK(cond, msg) do { \
    if (!(cond)) { \
        std::cerr << "[FAIL] " << msg << " at line " << __LINE__ << std::endl; \
        g_fail++; \
    } else { \
        g_pass++; \
    } \
} while(0)

// Encode a valid tile into rawTile[180]
static void encodeValidTile(MacrochromaNativeDecoder& dec, uint8_t* rawTile, int tileIndex, int frameIndex, bool isParity, const uint8_t* payload) {
    memset(rawTile, 0, TILE_RAW_BYTES);
    rawTile[0] = tileIndex & 0xFF;
    rawTile[1] = ((tileIndex >> 8) & 0x01) | ((frameIndex & 0x3F) << 1) | (isParity ? 0x80 : 0);
    memcpy(rawTile + TILE_HEADER_BYTES, payload, TILE_PAYLOAD_BYTES);
    uint16_t crc = dec.crc16(rawTile, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);
    rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES] = (crc >> 8) & 0xFF;
    rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1] = crc & 0xFF;
    uint8_t parity[TILE_INNER_FEC_BYTES];
    dec.encodeRs(rawTile, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES, parity);
    memcpy(rawTile + TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + TILE_CRC_BYTES, parity, TILE_INNER_FEC_BYTES);
}

// Write a synthetic frame with given tiles
static void writeSyntheticFrame(MacrochromaNativeDecoder& dec, uint8_t* y, uint8_t* u, uint8_t* v, int frameIdx, const std::vector<std::pair<int,std::vector<uint8_t>>>& tiles) {
    memset(y, 128, 480 * 388);
    memset(u, 128, 240 * 194);
    memset(v, 128, 240 * 194);

    // Write header
    uint8_t headerRaw[8] = {
        static_cast<uint8_t>(HEADER_MAGIC >> 8),
        static_cast<uint8_t>(HEADER_MAGIC & 0xFF),
        static_cast<uint8_t>((MACROCHROMA_VERSION << 4) | ((frameIdx >> 8) & 0x0F)),
        static_cast<uint8_t>(frameIdx & 0xFF),
        0, 0, 0, 1
    };
    uint16_t hCrc = dec.crc16(headerRaw, 8);
    uint8_t hp[10];
    memcpy(hp, headerRaw, 8);
    hp[8] = (hCrc >> 8) & 0xFF;
    hp[9] = hCrc & 0xFF;
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 480; ++c) {
            int bitIdx = c % 80;
            int bit = (hp[bitIdx / 8] >> (7 - (bitIdx % 8))) & 1;
            y[r * 480 + c] = bit ? 220 : 30;
        }
    }

    // Write tiles
    for (auto& [tIdx, rawTile] : tiles) {
        int tx = tIdx % 20;
        int ty = tIdx / 20;
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
                uint8_t y00 = (sym >> 8) & 3, y01 = (sym >> 6) & 3;
                uint8_t y10 = (sym >> 4) & 3, y11 = (sym >> 2) & 3;
                uint8_t chroma = sym & 3;
                int topR = 4 + ty * 24 + mbY * 2;
                int leftC = tx * 24 + mbX * 2;
                auto lv = [](uint8_t l) -> uint8_t {
                    switch(l) { case 0: return 40; case 1: return 95; case 2: return 150; default: return 210; }
                };
                y[topR * 480 + leftC] = lv(y00);
                y[topR * 480 + leftC + 1] = lv(y01);
                y[(topR+1) * 480 + leftC] = lv(y10);
                y[(topR+1) * 480 + leftC + 1] = lv(y11);
                uint8_t uV = 128, vV = 128;
                switch(chroma) {
                    case 0: uV=90; vV=180; break;
                    case 1: uV=90; vV=80; break;
                    case 2: uV=180; vV=80; break;
                    default: uV=180; vV=180; break;
                }
                u[(topR/2) * 240 + (leftC/2)] = uV;
                v[(topR/2) * 240 + (leftC/2)] = vV;
            }
        }
    }
}

void test_rs_stress() {
    std::cout << "=== RS/GF(256) STRESS ===" << std::endl;
    MacrochromaNativeDecoder dec;
    std::mt19937 rng(42);

    int recoveredOk = 0, rejectedOk = 0, silentCorrupt = 0;

    for (int trial = 0; trial < 10000; ++trial) {
        uint8_t payload[TILE_PAYLOAD_BYTES];
        for (auto& b : payload) b = rng() & 0xFF;

        uint8_t rawTile[TILE_RAW_BYTES];
        encodeValidTile(dec, rawTile, trial % 320, 0, false, payload);
        uint8_t origTile[TILE_RAW_BYTES];
        memcpy(origTile, rawTile, TILE_RAW_BYTES);

        int nErrors = rng() % 5; // 0..4 errors

        std::vector<int> errorPositions;
        for (int e = 0; e < nErrors; ++e) {
            int pos;
            do { pos = rng() % TILE_RAW_BYTES; }
            while (std::find(errorPositions.begin(), errorPositions.end(), pos) != errorPositions.end());
            errorPositions.push_back(pos);
            rawTile[pos] ^= (1 + (rng() % 255)); // Nonzero XOR
        }

        bool wasCorrected = false;
        bool rsOk = dec.decodeRs(rawTile, wasCorrected);

        if (nErrors <= 2) {
            // Should recover
            if (rsOk) {
                // Verify CRC still passes
                uint16_t tileCrc = (static_cast<uint16_t>(rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES]) << 8) |
                                   rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1];
                uint16_t computedCrc = dec.crc16(rawTile, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);
                if (tileCrc == computedCrc) {
                    // Verify payload matches
                    if (memcmp(rawTile + TILE_HEADER_BYTES, payload, TILE_PAYLOAD_BYTES) == 0) {
                        recoveredOk++;
                    } else {
                        silentCorrupt++;
                    }
                }
            }
        } else {
            // Should either fail or, if RS "fixes" it, CRC must catch
            if (rsOk) {
                uint16_t tileCrc = (static_cast<uint16_t>(rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES]) << 8) |
                                   rawTile[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1];
                uint16_t computedCrc = dec.crc16(rawTile, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);
                if (tileCrc == computedCrc && memcmp(rawTile + TILE_HEADER_BYTES, payload, TILE_PAYLOAD_BYTES) != 0) {
                    silentCorrupt++;
                }
            } else {
                rejectedOk++;
            }
        }
    }

    CHECK(silentCorrupt == 0, "RS silent corruption count must be 0");
    std::cout << "  Recovered: " << recoveredOk << ", Rejected: " << rejectedOk << ", Silent corrupt: " << silentCorrupt << std::endl;

    // Specific boundary tests
    // Error in first byte
    {
        uint8_t p[TILE_PAYLOAD_BYTES] = {0xAB};
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 0, false, p);
        raw[0] ^= 0x11;
        bool wc; bool ok = dec.decodeRs(raw, wc);
        CHECK(ok && memcmp(raw + TILE_HEADER_BYTES, p, TILE_PAYLOAD_BYTES) == 0, "Error in first byte");
    }
    // Error in last byte (RS parity)
    {
        uint8_t p[TILE_PAYLOAD_BYTES] = {0xCD};
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 0, false, p);
        raw[TILE_RAW_BYTES - 1] ^= 0x22;
        bool wc; bool ok = dec.decodeRs(raw, wc);
        CHECK(ok, "Error in last byte (parity) recovers");
    }
    // Error in CRC bytes
    {
        uint8_t p[TILE_PAYLOAD_BYTES] = {0xEF};
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 0, false, p);
        raw[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES] ^= 0x33;
        bool wc; bool ok = dec.decodeRs(raw, wc);
        CHECK(ok, "Error in CRC byte recovers");
    }
    std::cout << "  RS stress complete" << std::endl;
}

void test_adversarial_parser() {
    std::cout << "=== ADVERSARIAL PARSER ===" << std::endl;
    MacrochromaNativeDecoder dec;

    // All-zero buffer
    {
        std::vector<uint8_t> y(480*388, 0), u(240*194, 0), v(240*194, 0);
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(!res.headerValid || res.validTiles == 0, "All-zero buffer: no valid data");
    }
    // All-0xFF buffer
    {
        std::vector<uint8_t> y(480*388, 0xFF), u(240*194, 0xFF), v(240*194, 0xFF);
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(true, "All-0xFF buffer: no crash");
    }
    // Random bytes buffer
    {
        std::mt19937 rng(123);
        std::vector<uint8_t> y(480*388), u(240*194), v(240*194);
        for (auto& b : y) b = rng() & 0xFF;
        for (auto& b : u) b = rng() & 0xFF;
        for (auto& b : v) b = rng() & 0xFF;
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(true, "Random bytes: no crash");
    }
    // Invalid dimensions
    {
        std::vector<uint8_t> y(100*100, 128), u(50*50, 128), v(50*50, 128);
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 100, 100);
        CHECK(!res.headerValid && res.validTiles == 0, "Invalid dims rejected");
    }
    // Zero dimensions
    {
        uint8_t dummy = 0;
        auto res = dec.decodeFrame(&dummy, &dummy, &dummy, 0, 0);
        CHECK(!res.headerValid && res.validTiles == 0, "Zero dims rejected");
    }
    // Negative dimensions (cast to int)
    {
        uint8_t dummy = 0;
        auto res = dec.decodeFrame(&dummy, &dummy, &dummy, -1, -1);
        CHECK(!res.headerValid && res.validTiles == 0, "Negative dims rejected");
    }

    // 1000 random frames
    {
        std::mt19937 rng(456);
        for (int i = 0; i < 1000; ++i) {
            std::vector<uint8_t> y(480*388), u(240*194), v(240*194);
            for (auto& b : y) b = rng() & 0xFF;
            for (auto& b : u) b = rng() & 0xFF;
            for (auto& b : v) b = rng() & 0xFF;
            auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
            // Just ensure no crash/hang - any result is acceptable
        }
        CHECK(true, "1000 random frames: no crash");
    }

    std::cout << "  Adversarial parser complete" << std::endl;
}

void test_header_fuzz() {
    std::cout << "=== HEADER FUZZING ===" << std::endl;
    MacrochromaNativeDecoder dec;

    // Valid header baseline
    {
        std::vector<uint8_t> y(480*388, 128), u(240*194, 128), v(240*194, 128);
        uint8_t hr[8] = {
            static_cast<uint8_t>(HEADER_MAGIC >> 8), static_cast<uint8_t>(HEADER_MAGIC & 0xFF),
            static_cast<uint8_t>((MACROCHROMA_VERSION << 4) | 0), 0, 0, 0, 0, 1
        };
        uint16_t hc = dec.crc16(hr, 8);
        uint8_t hp[10]; memcpy(hp, hr, 8); hp[8]=(hc>>8)&0xFF; hp[9]=hc&0xFF;
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 480; ++c)
                y[r*480+c] = (hp[(c%80)/8] >> (7-((c%80)%8))) & 1 ? 220 : 30;
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(res.headerValid, "Valid header baseline");
    }

    // Single-bit corruption in every position
    int singleBitRejected = 0;
    for (int bitPos = 0; bitPos < 80; ++bitPos) {
        std::vector<uint8_t> y(480*388, 128), u(240*194, 128), v(240*194, 128);
        uint8_t hr[8] = {
            static_cast<uint8_t>(HEADER_MAGIC >> 8), static_cast<uint8_t>(HEADER_MAGIC & 0xFF),
            static_cast<uint8_t>((MACROCHROMA_VERSION << 4) | 0), 0, 0, 0, 0, 1
        };
        uint16_t hc = dec.crc16(hr, 8);
        uint8_t hp[10]; memcpy(hp, hr, 8); hp[8]=(hc>>8)&0xFF; hp[9]=hc&0xFF;
        hp[bitPos/8] ^= (1 << (7 - (bitPos % 8)));
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 480; ++c)
                y[r*480+c] = (hp[(c%80)/8] >> (7-((c%80)%8))) & 1 ? 220 : 30;
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        if (!res.headerValid) singleBitRejected++;
    }
    CHECK(singleBitRejected == 80, "All single-bit header corruptions rejected");
    std::cout << "  Single-bit rejections: " << singleBitRejected << "/80" << std::endl;

    // Invalid magic
    {
        std::vector<uint8_t> y(480*388, 128), u(240*194, 128), v(240*194, 128);
        uint8_t hr[8] = { 0x00, 0x00, static_cast<uint8_t>((MACROCHROMA_VERSION << 4) | 0), 0, 0, 0, 0, 1 };
        uint16_t hc = dec.crc16(hr, 8);
        uint8_t hp[10]; memcpy(hp, hr, 8); hp[8]=(hc>>8)&0xFF; hp[9]=hc&0xFF;
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 480; ++c)
                y[r*480+c] = (hp[(c%80)/8] >> (7-((c%80)%8))) & 1 ? 220 : 30;
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(!res.headerValid, "Invalid magic rejected");
    }

    // Unsupported version
    {
        std::vector<uint8_t> y(480*388, 128), u(240*194, 128), v(240*194, 128);
        uint8_t hr[8] = {
            static_cast<uint8_t>(HEADER_MAGIC >> 8), static_cast<uint8_t>(HEADER_MAGIC & 0xFF),
            static_cast<uint8_t>((15 << 4) | 0), 0, 0, 0, 0, 1
        };
        uint16_t hc = dec.crc16(hr, 8);
        uint8_t hp[10]; memcpy(hp, hr, 8); hp[8]=(hc>>8)&0xFF; hp[9]=hc&0xFF;
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 480; ++c)
                y[r*480+c] = (hp[(c%80)/8] >> (7-((c%80)%8))) & 1 ? 220 : 30;
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(!res.headerValid, "Unsupported version rejected");
    }

    std::cout << "  Header fuzzing complete" << std::endl;
}

void test_full_frame_roundtrip() {
    std::cout << "=== FULL FRAME ROUNDTRIP (1000 frames) ===" << std::endl;
    MacrochromaNativeDecoder dec;
    std::mt19937 rng(999);

    int totalFramesTested = 0;
    int totalTilesVerified = 0;

    for (int frame = 0; frame < 1000; ++frame) {
        int frameIdx = rng() % 4096;
        std::vector<uint8_t> y(480*388), u(240*194), v(240*194);
        std::vector<std::pair<int,std::vector<uint8_t>>> tiles;
        std::vector<std::vector<uint8_t>> payloads(320);

        for (int t = 0; t < 320; ++t) {
            payloads[t].resize(TILE_PAYLOAD_BYTES);
            for (auto& b : payloads[t]) b = rng() & 0xFF;
            uint8_t rawTile[TILE_RAW_BYTES];
            encodeValidTile(dec, rawTile, t, frameIdx, t >= 300, payloads[t].data());
            tiles.push_back({t, std::vector<uint8_t>(rawTile, rawTile + TILE_RAW_BYTES)});
        }

        writeSyntheticFrame(dec, y.data(), u.data(), v.data(), frameIdx, tiles);
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);

        if (res.headerValid && res.validTiles == 320) {
            bool allMatch = true;
            for (int t = 0; t < 320; ++t) {
                if (memcmp(res.tiles[t].payload, payloads[t].data(), TILE_PAYLOAD_BYTES) != 0) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) totalTilesVerified += 320;
        }
        totalFramesTested++;
    }

    CHECK(totalTilesVerified == 320 * 1000, "All 1000 frames roundtrip exactly");
    std::cout << "  Frames tested: " << totalFramesTested << ", Tiles verified: " << totalTilesVerified << std::endl;
}

void test_edge_cases() {
    std::cout << "=== EDGE CASES ===" << std::endl;
    MacrochromaNativeDecoder dec;

    // Tile index 0 and 319
    for (int tIdx : {0, 319}) {
        uint8_t p[TILE_PAYLOAD_BYTES];
        memset(p, tIdx & 0xFF, TILE_PAYLOAD_BYTES);
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, tIdx, 0, false, p);
        // Decode in isolation
        bool wc;
        bool ok = dec.decodeRs(raw, wc);
        uint16_t tileCrc = (static_cast<uint16_t>(raw[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES]) << 8) |
                           raw[TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES + 1];
        uint16_t compCrc = dec.crc16(raw, TILE_HEADER_BYTES + TILE_PAYLOAD_BYTES);
        CHECK(ok && tileCrc == compCrc, "Tile index boundary");
    }

    // Frame index near max (12-bit = 4095)
    {
        std::vector<uint8_t> y(480*388), u(240*194), v(240*194);
        uint8_t p[TILE_PAYLOAD_BYTES] = {0xAA};
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 4095, false, p);
        std::vector<std::pair<int,std::vector<uint8_t>>> tiles = {{0, std::vector<uint8_t>(raw, raw + TILE_RAW_BYTES)}};
        writeSyntheticFrame(dec, y.data(), u.data(), v.data(), 4095, tiles);
        auto res = dec.decodeFrame(y.data(), u.data(), v.data(), 480, 388);
        CHECK(res.headerValid, "Frame index 4095 header valid");
        if (res.validTiles > 0) {
            CHECK(res.tiles[0].valid, "Tile at frame 4095 valid");
        }
    }

    // CRC edge: payload of all zeros
    {
        uint8_t p[TILE_PAYLOAD_BYTES];
        memset(p, 0, TILE_PAYLOAD_BYTES);
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 0, false, p);
        bool wc;
        bool ok = dec.decodeRs(raw, wc);
        CHECK(ok && !wc, "All-zero payload clean");
    }

    // CRC edge: payload of all 0xFF
    {
        uint8_t p[TILE_PAYLOAD_BYTES];
        memset(p, 0xFF, TILE_PAYLOAD_BYTES);
        uint8_t raw[TILE_RAW_BYTES];
        encodeValidTile(dec, raw, 0, 0, false, p);
        bool wc;
        bool ok = dec.decodeRs(raw, wc);
        CHECK(ok && !wc, "All-0xFF payload clean");
    }

    std::cout << "  Edge cases complete" << std::endl;
}

int main() {
    std::cout << "=== C++ MACROCHROMA SANITIZER & STRESS TEST ===" << std::endl;
    std::cout << "Compiled with:";
#ifdef __SANITIZE_ADDRESS__
    std::cout << " ASan";
#endif
#if defined(__has_feature)
#if __has_feature(address_sanitizer)
    std::cout << " ASan";
#endif
#if __has_feature(undefined_behavior_sanitizer)
    std::cout << " UBSan";
#endif
#endif
    std::cout << std::endl;

    test_rs_stress();
    test_adversarial_parser();
    test_header_fuzz();
    test_full_frame_roundtrip();
    test_edge_cases();

    std::cout << "\n=== SUMMARY ===" << std::endl;
    std::cout << "PASS: " << g_pass << std::endl;
    std::cout << "FAIL: " << g_fail << std::endl;
    std::cout << (g_fail == 0 ? "ALL TESTS PASSED" : "SOME TESTS FAILED") << std::endl;
    return g_fail > 0 ? 1 : 0;
}
