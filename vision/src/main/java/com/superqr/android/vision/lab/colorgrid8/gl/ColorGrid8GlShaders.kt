package com.superqr.android.vision.lab.colorgrid8.gl

object ColorGrid8GlShaders {
    const val PASSTHROUGH_VERTEX = """#version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    const val FINDER_THUMBNAIL_FRAGMENT = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : enable
        #extension GL_OES_EGL_image_external : enable
        precision highp float;

        in vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        uniform bool uCaptureColor;

        out vec4 FragColor;

        void main() {
            vec2 texCoord = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
            vec4 color = texture(uTexture, texCoord);
            float y = 0.299 * color.r + 0.587 * color.g + 0.114 * color.b;
            FragColor = uCaptureColor ? color : vec4(y, y, y, 1.0);
        }
    """

    const val CELL_SAMPLE_FRAGMENT = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : enable
        #extension GL_OES_EGL_image_external : enable
        precision highp float;

        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        uniform mat3 uHomography;
        uniform ivec2 uGridSize;
        uniform int uSampleMode;
        uniform vec2 uCameraSize;
        uniform int uFiducialOffset;
        uniform float uCellPitchPx;
        uniform vec2 uCellOffset;
        uniform bool uHeaderSearch;
        uniform mat3 uHeaderHomographies[4];

        out vec4 FragColor;

        vec3 rgb2yuv(vec3 rgb) {
            float y = 0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
            float u = -0.169 * rgb.r - 0.331 * rgb.g + 0.500 * rgb.b + 128.0 / 255.0;
            float v = 0.500 * rgb.r - 0.419 * rgb.g - 0.081 * rgb.b + 128.0 / 255.0;
            return vec3(y, u, v);
        }

        vec3 sampleAtCamPixel(vec2 camPixel) {
            vec2 normCam = vec2(camPixel.x / uCameraSize.x, 1.0 - camPixel.y / uCameraSize.y);
            vec2 texCoord = (uTexMatrix * vec4(normCam, 0.0, 1.0)).xy;
            vec4 color = texture(uTexture, texCoord);
            return rgb2yuv(color.rgb);
        }

        void main() {
            int col = int(floor(gl_FragCoord.x));
            int row = int(floor(gl_FragCoord.y));
            mat3 transform = uHomography;
            vec2 alignment = uCellOffset;
            if (uHeaderSearch) {
                // Packed as [rotation][5x5 sub-cell offsets][two header rows].
                // Keep this layout synchronized with ColorGrid8HeaderSearch.
                int candidate = row / 2;
                int offsetIndex = candidate % 25;
                row = row % 2;
                transform = uHeaderHomographies[candidate / 25];
                alignment = vec2(float(offsetIndex % 5 - 2), float(offsetIndex / 5 - 2)) * 0.25;
            }
            float cx = float(col + uFiducialOffset) + 0.5 + alignment.x;
            float cy = float(row + uFiducialOffset) + 0.5 + alignment.y;

            vec3 hom = transform * vec3(cx, cy, 1.0);
            vec2 centerCam = hom.xy / hom.z;

            // Offset in canonical cell space, then project each sample. This
            // follows perspective and anisotropic camera pixels correctly.
            float offset = 0.25;

            vec3 yuv = vec3(0.0);
            if (uSampleMode == 0) {
                yuv = sampleAtCamPixel(centerCam);
            } else if (uSampleMode == 1) {
                vec3 h0 = transform * vec3(cx - offset, cy - offset, 1.0);
                vec3 h1 = transform * vec3(cx + offset, cy - offset, 1.0);
                vec3 h2 = transform * vec3(cx - offset, cy + offset, 1.0);
                vec3 h3 = transform * vec3(cx + offset, cy + offset, 1.0);
                yuv += sampleAtCamPixel(h0.xy / h0.z);
                yuv += sampleAtCamPixel(h1.xy / h1.z);
                yuv += sampleAtCamPixel(h2.xy / h2.z);
                yuv += sampleAtCamPixel(h3.xy / h3.z);
                yuv *= 0.25;
            } else {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec3 h = transform * vec3(cx + float(dx)*offset, cy + float(dy)*offset, 1.0);
                        yuv += sampleAtCamPixel(h.xy / h.z);
                    }
                }
                yuv /= 9.0;
            }
            FragColor = vec4(yuv, 1.0);
        }
    """

    const val MACROCHROMA_CELL_SAMPLE_FRAGMENT = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : enable
        #extension GL_OES_EGL_image_external : enable
        precision highp float;

        uniform samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        uniform mat3 uHomography;
        uniform ivec2 uGridSize;
        uniform int uSampleMode;
        uniform vec2 uCameraSize;
        uniform int uFiducialOffset;
        uniform float uCellPitchPx;

        out vec4 FragColor;

        vec3 rgb2yuv(vec3 rgb) {
            float y = 0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
            float u = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.500 * rgb.b + 128.0 / 255.0;
            float v = 0.500 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b + 128.0 / 255.0;
            return vec3(y, u, v);
        }

        vec3 sampleAtCamPixel(vec2 camPixel) {
            vec2 normCam = vec2(camPixel.x / uCameraSize.x, 1.0 - camPixel.y / uCameraSize.y);
            vec2 texCoord = (uTexMatrix * vec4(normCam, 0.0, 1.0)).xy;
            vec4 color = texture(uTexture, texCoord);
            return rgb2yuv(color.rgb);
        }

        void main() {
            int col = int(floor(gl_FragCoord.x));
            int row = int(floor(gl_FragCoord.y));

            // Fine cell center
            float cx = float(col + uFiducialOffset) + 0.5;
            float cy = float(row + uFiducialOffset) + 0.5;
            vec3 hom = uHomography * vec3(cx, cy, 1.0);
            vec2 centerCam = hom.xy / hom.z;

            // 1. Sample Fine Cell Luma Y
            float y = sampleAtCamPixel(centerCam).r;

            // 2. Sample 2x2 Macroblock Center for Box-Filtered Chroma (U, V)
            int mbCol = (col / 2) * 2;
            int mbRow = (row / 2) * 2;
            float mbCx = float(mbCol + uFiducialOffset) + 1.0;
            float mbCy = float(mbRow + uFiducialOffset) + 1.0;
            vec3 mbHom = uHomography * vec3(mbCx, mbCy, 1.0);
            vec2 mbCenterCam = mbHom.xy / mbHom.z;

            float mbOffset = uCellPitchPx * 0.5;
            vec3 mbSample0 = sampleAtCamPixel(mbCenterCam + vec2(-mbOffset, -mbOffset));
            vec3 mbSample1 = sampleAtCamPixel(mbCenterCam + vec2( mbOffset, -mbOffset));
            vec3 mbSample2 = sampleAtCamPixel(mbCenterCam + vec2(-mbOffset,  mbOffset));
            vec3 mbSample3 = sampleAtCamPixel(mbCenterCam + vec2( mbOffset,  mbOffset));
            vec2 uv = (mbSample0.gb + mbSample1.gb + mbSample2.gb + mbSample3.gb) * 0.25;

            FragColor = vec4(y, uv.x, uv.y, 1.0);
        }
    """
}
