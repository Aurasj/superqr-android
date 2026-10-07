#include "colorgrid8_native.h"
#include "macrochroma_native.h"
#include <jni.h>
#include <chrono>
#include <vector>

#ifdef ANDROID
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ColorGrid8Native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ColorGrid8Native", __VA_ARGS__)
#else
#define LOGI(...)
#define LOGE(...)
#endif

using namespace superqr::colorgrid8;

static uint64_t s_nativeCallCount = 0;

struct BufferAccessor {
    JNIEnv* env;
    jobject obj;
    const uint8_t* ptr = nullptr;
    jbyteArray arr = nullptr;
    jboolean isCopy = JNI_FALSE;

    BufferAccessor(JNIEnv* e, jobject bufferObj) : env(e), obj(bufferObj) {
        if (!obj) return;
        // Try direct buffer first
        void* direct = env->GetDirectBufferAddress(obj);
        if (direct) {
            ptr = static_cast<const uint8_t*>(direct);
        } else {
            // Check if it is a byte array
            arr = reinterpret_cast<jbyteArray>(obj);
            ptr = reinterpret_cast<const uint8_t*>(env->GetByteArrayElements(arr, &isCopy));
        }
    }

    ~BufferAccessor() {
        if (arr && ptr) {
            env->ReleaseByteArrayElements(arr, reinterpret_cast<jbyte*>(const_cast<uint8_t*>(ptr)), JNI_ABORT);
        }
    }
};

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    LOGI("libcolorgrid8_native.so JNI_OnLoad initialized successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_superqr_android_vision_lab_colorgrid8_ColorGrid8NativeDecoder_nativeDecodeFrame(
    JNIEnv* env,
    jclass /* clazz */,
    jobject yBufferObj,
    jint yRowStride,
    jint yPixelStride,
    jobject uBufferObj,
    jint uRowStride,
    jint uPixelStride,
    jobject vBufferObj,
    jint vRowStride,
    jint vPixelStride,
    jint width,
    jint height,
    jfloatArray quadXArr,
    jfloatArray quadYArr,
    jint profileCols,
    jint profileRows,
    jint profileFps,
    jint profileSeed,
    jint profileVersion,
    jint profileId,
    jfloat lumaErasureFraction,
    jfloat chromaMarginThreshold,
    jint fiducialOffsetCells,
    jbyteArray outPayloadSymbolsArr,
    jdoubleArray outTimingsArr,
    jintArray outIntStatsArr,
    jfloatArray outFloatStatsArr,
    jfloatArray outCentroidsArr
) {
    auto jniStart = std::chrono::high_resolution_clock::now();

    BufferAccessor yAcc(env, yBufferObj);
    BufferAccessor uAcc(env, uBufferObj);
    BufferAccessor vAcc(env, vBufferObj);

    if (!yAcc.ptr || !uAcc.ptr || !vAcc.ptr) {
        return JNI_FALSE;
    }

    NativePlane yPlane = { yAcc.ptr, yRowStride, yPixelStride, width, height };
    NativePlane uPlane = { uAcc.ptr, uRowStride, uPixelStride, width / 2, height / 2 };
    NativePlane vPlane = { vAcc.ptr, vRowStride, vPixelStride, width / 2, height / 2 };

    NativeQuad quad;
    jfloat* qx = env->GetFloatArrayElements(quadXArr, nullptr);
    jfloat* qy = env->GetFloatArrayElements(quadYArr, nullptr);
    for (int i = 0; i < 4; ++i) {
        quad.x[i] = qx[i];
        quad.y[i] = qy[i];
    }
    env->ReleaseFloatArrayElements(quadXArr, qx, JNI_ABORT);
    env->ReleaseFloatArrayElements(quadYArr, qy, JNI_ABORT);

    NativeProfile profile;
    profile.cols = profileCols;
    profile.rows = profileRows;
    profile.fps = profileFps;
    profile.seed = profileSeed;
    profile.version = profileVersion;
    profile.profileId = profileId;
    profile.totalCells = profileCols * profileRows;
    profile.payloadCells = profile.totalCells - profileCols * HEADER_ROWS -
                           (profile.totalCells - profileCols * HEADER_ROWS + PILOT_PERIOD - 1) / PILOT_PERIOD;

    NativeConfig config;
    config.lumaErasureFraction = lumaErasureFraction;
    config.chromaMarginThreshold = chromaMarginThreshold;
    config.fiducialOffsetCells = fiducialOffsetCells;

    uint8_t* payloadOutPtr = nullptr;
    jboolean isPayloadCopy = JNI_FALSE;
    if (outPayloadSymbolsArr) {
        payloadOutPtr = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(outPayloadSymbolsArr, &isPayloadCopy));
    }

    NativeResult result = decodeFrameDirect(yPlane, uPlane, vPlane, quad, profile, config, payloadOutPtr);

    if (outPayloadSymbolsArr && payloadOutPtr) {
        env->ReleasePrimitiveArrayCritical(outPayloadSymbolsArr, payloadOutPtr, 0);
    }

    auto jniEnd = std::chrono::high_resolution_clock::now();
    double jniTotalMs = std::chrono::duration<double, std::milli>(jniEnd - jniStart).count();

    s_nativeCallCount++;
    if (s_nativeCallCount == 1 || (s_nativeCallCount % 60 == 0) || result.success) {
        LOGI("nativeDecodeFrame: #%llu success=%d stage=%d validHeader=%d erasures=%d/%d nativeMs=%.2f jniMs=%.2f",
             (unsigned long long)s_nativeCallCount, result.success ? 1 : 0, static_cast<int>(result.stage),
             result.header.valid ? 1 : 0, result.erasures, profile.payloadCells,
             result.totalMs, jniTotalMs);
    }

    // Fill output arrays
    if (outTimingsArr) {
        jdouble timings[5] = {
            result.headerMs,
            result.pilotMs,
            result.payloadMs,
            result.totalMs,
            jniTotalMs
        };
        env->SetDoubleArrayRegion(outTimingsArr, 0, 5, timings);
    }

    if (outIntStatsArr) {
        jint intStats[10] = {
            static_cast<jint>(result.stage),
            result.success ? 1 : 0,
            result.classifiedSymbols,
            result.erasures,
            result.header.rawMagic,
            result.header.score,
            result.header.valid ? 1 : 0,
            result.header.profileId,
            result.header.fps,
            result.header.frameIndex
        };
        env->SetIntArrayRegion(outIntStatsArr, 0, 10, intStats);
    }

    if (outFloatStatsArr) {
        jfloat floatStats[4] = {
            result.header.contrast,
            result.lumaThreshold,
            result.pilotMinUvDistance,
            0.0f
        };
        env->SetFloatArrayRegion(outFloatStatsArr, 0, 4, floatStats);
    }

    if (outCentroidsArr) {
        jfloat centroidsFlat[32];
        for (int i = 0; i < 8; ++i) {
            centroidsFlat[i * 4 + 0] = result.centroids[i].y;
            centroidsFlat[i * 4 + 1] = result.centroids[i].u;
            centroidsFlat[i * 4 + 2] = result.centroids[i].v;
            centroidsFlat[i * 4 + 3] = result.centroids[i].sigmaUv;
        }
        env->SetFloatArrayRegion(outCentroidsArr, 0, 32, centroidsFlat);
    }

    return result.success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_superqr_android_vision_lab_colorgrid8_ColorGrid8NativeDecoder_nativeSampleHeader(
    JNIEnv* env,
    jclass /* clazz */,
    jobject yBufferObj,
    jint yRowStride,
    jint yPixelStride,
    jint width,
    jint height,
    jfloatArray quadXArr,
    jfloatArray quadYArr,
    jint profileCols,
    jint profileRows,
    jint fiducialOffsetCells,
    jbyteArray outHeaderLumaArr
) {
    BufferAccessor yAcc(env, yBufferObj);
    if (!yAcc.ptr) return JNI_FALSE;

    NativePlane yPlane = { yAcc.ptr, yRowStride, yPixelStride, width, height };

    NativeQuad quad;
    jfloat* qx = env->GetFloatArrayElements(quadXArr, nullptr);
    jfloat* qy = env->GetFloatArrayElements(quadYArr, nullptr);
    for (int i = 0; i < 4; ++i) {
        quad.x[i] = qx[i];
        quad.y[i] = qy[i];
    }
    env->ReleaseFloatArrayElements(quadXArr, qx, JNI_ABORT);
    env->ReleaseFloatArrayElements(quadYArr, qy, JNI_ABORT);

    int32_t outerCols = profileCols + fiducialOffsetCells * 2;
    int32_t outerRows = profileRows + fiducialOffsetCells * 2;

    NativeHomography H;
    if (!computeHomography(quad, outerCols, outerRows, H)) {
        return JNI_FALSE;
    }

    NativeProfile profile;
    profile.cols = profileCols;
    profile.rows = profileRows;

    NativeConfig config;
    config.fiducialOffsetCells = fiducialOffsetCells;

    uint8_t* outLuma = nullptr;
    if (outHeaderLumaArr) {
        outLuma = reinterpret_cast<uint8_t*>(env->GetPrimitiveArrayCritical(outHeaderLumaArr, nullptr));
    }

    NativeHeader header = probeHeaderDirect(yPlane, H, profile, config, outLuma);

    if (outHeaderLumaArr && outLuma) {
        env->ReleasePrimitiveArrayCritical(outHeaderLumaArr, outLuma, 0);
    }

    return header.valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_superqr_android_vision_lab_colorgrid8_ColorGrid8NativeDecoder_nativeDecodeFromCellMeans(
    JNIEnv* env, jclass,
    jbyteArray cellMeansY,
    jbyteArray cellMeansU,
    jbyteArray cellMeansV,
    jint profileCols, jint profileRows, jint profileFps, jint profileSeed,
    jint profileVersion, jint profileId,
    jfloat lumaErasureFraction, jfloat chromaMarginThreshold,
    jbyteArray outPayloadSymbols,
    jdoubleArray outTimings,
    jintArray outIntStats,
    jfloatArray outFloatStats,
    jfloatArray outCentroids
) {
    auto jniStart = std::chrono::high_resolution_clock::now();

    BufferAccessor yAcc(env, cellMeansY);
    BufferAccessor uAcc(env, cellMeansU);
    BufferAccessor vAcc(env, cellMeansV);

    if (!yAcc.ptr || !uAcc.ptr || !vAcc.ptr) {
        return JNI_FALSE;
    }

    NativeProfile profile;
    profile.cols = profileCols;
    profile.rows = profileRows;
    profile.fps = profileFps;
    profile.seed = profileSeed;
    profile.version = profileVersion;
    profile.profileId = profileId;
    profile.totalCells = profileCols * profileRows;
    profile.payloadCells = profile.totalCells - profileCols * HEADER_ROWS -
                           (profile.totalCells - profileCols * HEADER_ROWS + PILOT_PERIOD - 1) / PILOT_PERIOD;

    NativeConfig config;
    config.lumaErasureFraction = lumaErasureFraction;
    config.chromaMarginThreshold = chromaMarginThreshold;
    config.fiducialOffsetCells = FIDUCIAL_OFFSET_CELLS;

    uint8_t* payloadOutPtr = nullptr;
    jboolean isPayloadCopy = JNI_FALSE;
    if (outPayloadSymbols) {
        payloadOutPtr = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(outPayloadSymbols, &isPayloadCopy));
    }

    NativeResult result = decodeFromCellMeans(yAcc.ptr, uAcc.ptr, vAcc.ptr, profile, config, payloadOutPtr);

    if (outPayloadSymbols && payloadOutPtr) {
        env->ReleaseByteArrayElements(outPayloadSymbols, reinterpret_cast<jbyte*>(payloadOutPtr), 0);
    }

    auto jniEnd = std::chrono::high_resolution_clock::now();
    double jniTotalMs = std::chrono::duration<double, std::milli>(jniEnd - jniStart).count();

    if (outTimings) {
        jdouble timings[5] = {
            result.headerMs,
            result.pilotMs,
            result.payloadMs,
            result.totalMs,
            jniTotalMs
        };
        env->SetDoubleArrayRegion(outTimings, 0, 5, timings);
    }

    if (outIntStats) {
        jint intStats[10] = {
            static_cast<jint>(result.stage),
            result.success ? 1 : 0,
            result.classifiedSymbols,
            result.erasures,
            result.header.rawMagic,
            result.header.score,
            result.header.valid ? 1 : 0,
            result.header.profileId,
            result.header.fps,
            result.header.frameIndex
        };
        env->SetIntArrayRegion(outIntStats, 0, 10, intStats);
    }

    if (outFloatStats) {
        jfloat floatStats[4] = {
            result.header.contrast,
            result.lumaThreshold,
            result.pilotMinUvDistance,
            0.0f
        };
        env->SetFloatArrayRegion(outFloatStats, 0, 4, floatStats);
    }

    if (outCentroids) {
        jfloat centroidsFlat[32];
        for (int i = 0; i < 8; ++i) {
            centroidsFlat[i * 4 + 0] = result.centroids[i].y;
            centroidsFlat[i * 4 + 1] = result.centroids[i].u;
            centroidsFlat[i * 4 + 2] = result.centroids[i].v;
            centroidsFlat[i * 4 + 3] = result.centroids[i].sigmaUv;
        }
        env->SetFloatArrayRegion(outCentroids, 0, 32, centroidsFlat);
    }

    return result.success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_superqr_android_vision_lab_colorgrid8_ColorGrid8NativeDecoder_nativeDecodeMacrochromaCellMeans(
    JNIEnv* env,
    jclass /* clazz */,
    jobject yBufferObj,
    jobject uBufferObj,
    jobject vBufferObj,
    jint cols,
    jint rows,
    jintArray outTileIndicesArr,
    jintArray outFrameIndicesArr,
    jbyteArray outParityFlagsArr,
    jbyteArray outValidFlagsArr,
    jbyteArray outRsCorrectedFlagsArr,
    jbyteArray outPayloadBytesArr,
    jdoubleArray outTimingsArr,
    jintArray outIntStatsArr
) {
    auto jniStart = std::chrono::high_resolution_clock::now();

    BufferAccessor yAcc(env, yBufferObj);
    BufferAccessor uAcc(env, uBufferObj);
    BufferAccessor vAcc(env, vBufferObj);

    if (!yAcc.ptr || !uAcc.ptr || !vAcc.ptr) {
        return JNI_FALSE;
    }

    static superqr::macrochroma::MacrochromaNativeDecoder s_macroDecoder;
    auto result = s_macroDecoder.decodeFrame(yAcc.ptr, uAcc.ptr, vAcc.ptr, cols, rows);

    auto jniEnd = std::chrono::high_resolution_clock::now();
    double jniTotalMs = std::chrono::duration<double, std::milli>(jniEnd - jniStart).count();

    int totalTiles = static_cast<int>(result.tiles.size());

    if (outTileIndicesArr && outFrameIndicesArr && outParityFlagsArr && outValidFlagsArr && outRsCorrectedFlagsArr && outPayloadBytesArr) {
        jint* tileIndices = env->GetIntArrayElements(outTileIndicesArr, nullptr);
        jint* frameIndices = env->GetIntArrayElements(outFrameIndicesArr, nullptr);
        jbyte* parityFlags = env->GetByteArrayElements(outParityFlagsArr, nullptr);
        jbyte* validFlags = env->GetByteArrayElements(outValidFlagsArr, nullptr);
        jbyte* rsCorrectedFlags = env->GetByteArrayElements(outRsCorrectedFlagsArr, nullptr);
        jbyte* payloadBytes = env->GetByteArrayElements(outPayloadBytesArr, nullptr);

        for (int i = 0; i < totalTiles; ++i) {
            const auto& tile = result.tiles[i];
            tileIndices[i] = tile.tileIndex;
            frameIndices[i] = tile.frameIndex;
            parityFlags[i] = tile.isParity ? 1 : 0;
            validFlags[i] = tile.valid ? 1 : 0;
            rsCorrectedFlags[i] = tile.rsCorrected ? 1 : 0;
            std::memcpy(payloadBytes + (i * superqr::macrochroma::TILE_PAYLOAD_BYTES), tile.payload, superqr::macrochroma::TILE_PAYLOAD_BYTES);
        }

        env->ReleaseIntArrayElements(outTileIndicesArr, tileIndices, 0);
        env->ReleaseIntArrayElements(outFrameIndicesArr, frameIndices, 0);
        env->ReleaseByteArrayElements(outParityFlagsArr, parityFlags, 0);
        env->ReleaseByteArrayElements(outValidFlagsArr, validFlags, 0);
        env->ReleaseByteArrayElements(outRsCorrectedFlagsArr, rsCorrectedFlags, 0);
        env->ReleaseByteArrayElements(outPayloadBytesArr, payloadBytes, 0);
    }

    if (outTimingsArr) {
        jdouble timings[4] = {
            result.headerMs,
            result.decodeMs,
            result.totalMs,
            jniTotalMs
        };
        env->SetDoubleArrayRegion(outTimingsArr, 0, 4, timings);
    }

    if (outIntStatsArr) {
        jint stats[8] = {
            result.headerValid ? 1 : 0,
            result.header.magic,
            result.header.version,
            result.header.frameIndex,
            static_cast<jint>(result.header.sessionId),
            result.validTiles,
            result.rsCorrectedTiles,
            result.failedTiles
        };
        env->SetIntArrayRegion(outIntStatsArr, 0, 8, stats);
    }

    return result.headerValid ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
