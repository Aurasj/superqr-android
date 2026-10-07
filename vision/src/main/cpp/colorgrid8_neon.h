#pragma once

#include "colorgrid8_native.h"
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace superqr {
namespace colorgrid8 {

inline int32_t classify_cell_scalar(
    uint8_t y, uint8_t u, uint8_t v,
    const NativeCentroid* centroids,
    float lumaThreshold, float lumaHalfGap,
    float lumaErasureFraction, float chromaMarginThreshold,
    bool* isErasure = nullptr
) {
    float yF = static_cast<float>(y);
    float lumaConf = std::abs(yF - lumaThreshold) / lumaHalfGap;
    bool erasure = (lumaConf < lumaErasureFraction);
    int32_t luma = (yF > lumaThreshold) ? 1 : 0;
    int32_t start = luma * 4;

    float uF = static_cast<float>(u);
    float vF = static_cast<float>(v);

    int32_t bestSymbol = start;
    float best = 1e30f;
    float second = 1e30f;

    for (int32_t symbol = start; symbol < start + 4; ++symbol) {
        float du = uF - centroids[symbol].u;
        float dv = vF - centroids[symbol].v;
        float distance = du * du + dv * dv;
        if (distance < best) {
            second = best;
            best = distance;
            bestSymbol = symbol;
        } else if (distance < second) {
            second = distance;
        }
    }

    if (!std::isfinite(best) || !std::isfinite(second)) {
        if (isErasure) *isErasure = true;
        return (isErasure == nullptr) ? -1 : bestSymbol;
    }
    float bestRoot = std::sqrt(best);
    float secondRoot = std::sqrt(second);
    float denom = (secondRoot > 1.0f) ? secondRoot : 1.0f;
    float margin = (secondRoot - bestRoot) / denom;
    float allowedRadius = std::max(35.0f, centroids[bestSymbol].sigmaUv * 5.0f);

    if (bestRoot > allowedRadius || margin < chromaMarginThreshold) {
        erasure = true;
    }
    if (isErasure) *isErasure = erasure;
    return (isErasure == nullptr && erasure) ? -1 : bestSymbol;
}

#if defined(__ARM_NEON) || defined(__aarch64__)
inline int32_t classify_cell_neon(
    uint8_t y, uint8_t u, uint8_t v,
    const NativeCentroid* centroids,
    float lumaThreshold, float lumaHalfGap,
    float lumaErasureFraction, float chromaMarginThreshold,
    bool* isErasure = nullptr
) {
    float yF = static_cast<float>(y);
    float lumaConf = std::abs(yF - lumaThreshold) / lumaHalfGap;
    bool erasure = (lumaConf < lumaErasureFraction);
    int32_t luma = (yF > lumaThreshold) ? 1 : 0;
    int32_t start = luma * 4;

    float32x4_t u_val = vdupq_n_f32(static_cast<float>(u));
    float32x4_t v_val = vdupq_n_f32(static_cast<float>(v));

    float cu[4] = { centroids[start].u, centroids[start+1].u, centroids[start+2].u, centroids[start+3].u };
    float cv[4] = { centroids[start].v, centroids[start+1].v, centroids[start+2].v, centroids[start+3].v };
    float32x4_t c_u = vld1q_f32(cu);
    float32x4_t c_v = vld1q_f32(cv);

    float32x4_t du = vsubq_f32(u_val, c_u);
    float32x4_t dv = vsubq_f32(v_val, c_v);

    float32x4_t distSq = vmlaq_f32(vmulq_f32(du, du), dv, dv);

    alignas(16) float d[4];
    vst1q_f32(d, distSq);

    int32_t bestIdx = 0;
    float bestDist = d[0];
    float secondDist = 1e30f;

    for (int i = 1; i < 4; ++i) {
        if (d[i] < bestDist) {
            secondDist = bestDist;
            bestDist = d[i];
            bestIdx = i;
        } else if (d[i] < secondDist) {
            secondDist = d[i];
        }
    }
    if (secondDist >= 1e29f) {
        secondDist = d[(bestIdx + 1) % 4];
        for (int i = 0; i < 4; ++i) {
            if (i != bestIdx && d[i] < secondDist) secondDist = d[i];
        }
    }

    int32_t bestSymbol = start + bestIdx;
    float bestRoot = std::sqrt(bestDist);
    float secondRoot = std::sqrt(secondDist);
    float denom = (secondRoot > 1.0f) ? secondRoot : 1.0f;
    float margin = (secondRoot - bestRoot) / denom;
    float allowedRadius = std::max(35.0f, centroids[bestSymbol].sigmaUv * 5.0f);

    if (bestRoot > allowedRadius || margin < chromaMarginThreshold) {
        erasure = true;
    }
    if (isErasure) *isErasure = erasure;
    return (isErasure == nullptr && erasure) ? -1 : bestSymbol;
}
#endif

inline int32_t classify_cell(
    uint8_t y, uint8_t u, uint8_t v,
    const NativeCentroid* centroids,
    float lumaThreshold, float lumaHalfGap,
    float lumaErasureFraction, float chromaMarginThreshold,
    bool* isErasure = nullptr
) {
#if defined(__ARM_NEON) || defined(__aarch64__)
    return classify_cell_neon(y, u, v, centroids, lumaThreshold, lumaHalfGap, lumaErasureFraction, chromaMarginThreshold, isErasure);
#else
    return classify_cell_scalar(y, u, v, centroids, lumaThreshold, lumaHalfGap, lumaErasureFraction, chromaMarginThreshold, isErasure);
#endif
}

} // namespace colorgrid8
} // namespace superqr
