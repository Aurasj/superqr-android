#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace superqr::macrochroma {

constexpr int MACROCHROMA_VERSION = 4;
constexpr uint16_t HEADER_MAGIC = 0xC8F;

constexpr int HEADER_ROWS = 4;
constexpr int TILE_FINE_W = 24;
constexpr int TILE_FINE_H = 24;
constexpr int MACRO_BLOCK_SIZE = 2;

constexpr int TILE_MACRO_W = TILE_FINE_W / MACRO_BLOCK_SIZE; // 12
constexpr int TILE_MACRO_H = TILE_FINE_H / MACRO_BLOCK_SIZE; // 12
constexpr int TILE_MACROBLOCKS = TILE_MACRO_W * TILE_MACRO_H; // 144

constexpr int BITS_PER_MACROBLOCK = 10;
constexpr int TILE_RAW_BITS = TILE_MACROBLOCKS * BITS_PER_MACROBLOCK; // 1440
constexpr int TILE_RAW_BYTES = TILE_RAW_BITS / 8; // 180

constexpr int TILE_HEADER_BYTES = 2;
constexpr int TILE_CRC_BYTES = 2;
constexpr int TILE_INNER_FEC_BYTES = 4;
constexpr int TILE_PAYLOAD_BYTES = TILE_RAW_BYTES - TILE_HEADER_BYTES - TILE_CRC_BYTES - TILE_INNER_FEC_BYTES; // 172

struct MacrochromaHeader {
    uint16_t magic = 0;
    uint8_t version = 0;
    int frameIndex = 0;
    uint32_t sessionId = 0;
    bool valid = false;
};

struct NativeMacrochromaTile {
    int tileIndex = 0;
    int frameIndex = 0;
    bool isParity = false;
    bool valid = false;
    bool rsCorrected = false;
    uint8_t payload[TILE_PAYLOAD_BYTES] = {0};
};

struct MacrochromaFrameResult {
    bool headerValid = false;
    MacrochromaHeader header;
    int validTiles = 0;
    int rsCorrectedTiles = 0;
    int failedTiles = 0;
    double headerMs = 0.0;
    double decodeMs = 0.0;
    double rsMs = 0.0;
    double totalMs = 0.0;
    std::vector<NativeMacrochromaTile> tiles;
};

class MacrochromaNativeDecoder {
public:
    MacrochromaNativeDecoder();

    uint16_t crc16(const uint8_t* data, size_t len) const;
    void encodeRs(const uint8_t* msg, size_t msgLen, uint8_t* parityOut) const;

    MacrochromaFrameResult decodeFrame(
        const uint8_t* cellMeansY,
        const uint8_t* cellMeansU,
        const uint8_t* cellMeansV,
        int cols,
        int rows
    );

private:
    uint8_t gfExp[512] = {0};
    uint8_t gfLog[256] = {0};
    uint8_t rsGen[TILE_INNER_FEC_BYTES + 1] = {0};

    void initGf();
    uint8_t gfAdd(uint8_t a, uint8_t b) const { return a ^ b; }
    uint8_t gfMul(uint8_t a, uint8_t b) const;
    uint8_t gfInv(uint8_t a) const;

    bool decodeRs(uint8_t* codeword, bool& wasCorrected) const;
};

uint16_t crc16(const uint8_t* data, size_t len);
void encodeRs(const uint8_t* msg, size_t msgLen, uint8_t* parityOut);

} // namespace superqr::macrochroma
