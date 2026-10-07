// Host-side bridge for tests/test_colorgrid8_native_interop.py in Desktop.
// Compile with colorgrid8_native.cpp; no Android runtime or camera is needed.
#include "colorgrid8_native.h"
#include <cstdio>
#include <cstdlib>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

int main(int argc, char** argv) {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    using namespace superqr::colorgrid8;
    NativeProfile profile;
    if (argc != 1 && argc != 3) return 2;
    profile.cols = argc == 3 ? std::atoi(argv[1]) : 336;
    profile.rows = argc == 3 ? std::atoi(argv[2]) : 288;
    if (profile.cols == 240 && profile.rows == 216) profile.profileId = 5;
    else if (profile.cols == 336 && profile.rows == 288) profile.profileId = 6;
    else if (profile.cols == 384 && profile.rows == 336) profile.profileId = 7;
    else return 2;
    profile.totalCells = profile.cols * profile.rows;
    profile.payloadCells = 0;
    for (int row = HEADER_ROWS; row < profile.rows; ++row)
        for (int col = 0; col < profile.cols; ++col)
            if (!isPilotCell(profile, row, col)) ++profile.payloadCells;
    profile.fps = 30;
    profile.seed = 0x4D3A;
    profile.version = 2;
    std::vector<uint8_t> planes(profile.totalCells * 3);
    if (std::fread(planes.data(), 1, planes.size(), stdin) != planes.size()) return 2;
    std::vector<uint8_t> payload(profile.payloadCells);
    auto result = decodeFromCellMeans(planes.data(), planes.data() + profile.totalCells,
        planes.data() + profile.totalCells * 2, profile, NativeConfig{}, payload.data());
    int32_t stats[] = {result.success ? 1 : 0, result.stage, result.header.frameIndex,
        result.erasures, result.classifiedSymbols};
    std::fwrite(stats, sizeof(int32_t), 5, stdout);
    if (result.success) std::fwrite(payload.data(), 1, payload.size(), stdout);
    return 0;
}
