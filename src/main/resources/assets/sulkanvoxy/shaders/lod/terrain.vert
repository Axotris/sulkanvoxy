#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

// Voxy's LOD vertex stage (voxy:lod/gl46/quads3.vert) in its "patched shader"
// configuration, which leaves lighting to the fragment stage and passes the raw
// light id and tint colour: interData = (flags, light id, tint colour, 0).
// Additionally outputs the camera-relative position Sulkan's terrain needs.

#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif

#define PATCHED_SHADER
#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
layout(location = 1) out vec2 uv;
layout(location = 2) out vec3 relativePosition;

// getQuadCornerPos() from voxy:lod/quad_util.glsl without the final projection.
vec3 cornerPoint(in QuadData quad, uint cornerId) {
    uvec2 cornerBits = uvec2((cornerId >> 1) & 1u, cornerId & 1u);
    vec2 cornerMask = vec2(cornerBits) * quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * cornerMask, 0));

    if (quad.fluidCornerHeights != uint(-1)) {
        uint heightIndex = 0u;
        bool applyHeight = false;
        if (quad.face == 1u) {
            heightIndex = (cornerBits.x << 1) | cornerBits.y;
            applyHeight = true;
        } else if (quad.axis == 1u && cornerBits.y == 1u) {
            heightIndex = (cornerBits.x << 1) | (quad.face & 1u);
            applyHeight = true;
        } else if (quad.axis == 2u && cornerBits.x == 1u) {
            heightIndex = ((quad.face & 1u) << 1) | cornerBits.y;
            applyHeight = true;
        }

        if (applyHeight) {
            float height = float(((quad.fluidCornerHeights >> (heightIndex * 3u)) & 7u) + 1u) / 8.0;
            point.y += (height - 1.0) * quad.lodScale;
        }
    }
    return point;
}

void main() {
    QuadData quad;
    uvec2 pos = positionBuffer[gl_InstanceIndex];
    setupQuad(quad, quadData[uint(gl_VertexIndex) >> 2], pos, (gl_VertexIndex & 3) == 1);

    uint cornerId = gl_VertexIndex & 3;
    vec3 point = cornerPoint(quad, cornerId);
    gl_Position = MVP * vec4(point, 1.0);
    uv = getCornerUV(quad, cornerId);
    interData = quad.attributeData;
    // MVP is relative to baseSectionPos * 32; cameraSubPos is the camera's offset from it.
    relativePosition = point - cameraSubPos;
}
