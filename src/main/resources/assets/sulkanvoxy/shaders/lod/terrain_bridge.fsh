// ---- Sulkan Voxy bridge: LOD fragment entry point ----
// Appended after the pack's terrain fragment shader. The pack's main() was renamed
// to sv_packMain() and its vertex inputs turned into globals; this entry point
// decodes a Voxy LOD quad into exactly what Sulkan's terrain vertex shader hands
// the pack for a Sodium chunk, then runs the pack unchanged.

// Voxy's model buffer (voxy:lod/block_model.glsl) and atlas, redeclared under
// bridge-only names so nothing can collide with identifiers in the pack.
struct SvBlockModel {
    uint faceData[6];
    uint flagsA;
    uint colourTint;
    uint customId;
    uint _pad[7];
};
layout(set = 0, binding = 3, std430) readonly restrict buffer SvModelBuffer { SvBlockModel sv_modelData[]; };
layout(set = 0, binding = 8) uniform sampler2D sv_blockModelAtlas;
layout(set = 0, binding = 10) uniform sampler2D sv_depthBound;
// Vanilla block-state id -> Sulkan material id, for the active pack.
layout(set = 0, binding = 11, std430) readonly restrict buffer SvMaterialBuffer { uint sv_materials[]; };
layout(set = 0, binding = 12, std140) uniform SvBridgeParams {
    // Projection * view Minecraft rendered vanilla terrain with this frame.
    mat4 sv_vanillaViewProjection;
};

layout(location = 0) flat in uvec4 sv_interData;
layout(location = 1) in vec2 sv_quadUv;
layout(location = 2) in vec3 sv_relPos;

// Vanilla-projection depth of this LOD fragment; the composite writes it into
// Minecraft's depth buffer so it matches what the pack stored in its targets.
layout(location = SV_DEPTH_LOCATION) out float sv_lodDepth;

vec4 sv_tint;
vec2 sv_uv;
vec2 sv_lightLevel;
uint sv_material;
uint sv_sodiumMaterial;

vec4 sv_unpackRGBA(uint colour) {
    return vec4((uvec4(colour) >> uvec4(24u, 16u, 8u, 0u)) & uvec4(0xFFu)) / 255.0;
}

// Sodium bakes Minecraft's directional face shading into vertex colour; Voxy
// leaves it to the fragment stage, so apply the same cardinal lighting here.
float sv_faceShade(bool shaded, uint face) {
    if (!shaded) return SV_NO_SHADE_FACE_TINT;
    if ((face >> 1) == 1u) return SV_Z_AXIS_FACE_TINT;
    if ((face >> 1) == 2u) return SV_X_AXIS_FACE_TINT;
    if (face == 1u) return SV_UP_FACE_TINT;
    return SV_DOWN_FACE_TINT;
}

void main() {
    uint flags = sv_interData.x;
    uint face = (flags >> 4) & 7u;
    uint modelId = flags >> 16;
    vec2 quadExtent = vec2((flags >> 8) & 0xFu, (flags >> 12) & 0xFu);

    // Atlas addressing from voxy:lod/gl46/quads.frag: each model owns a 3x2 grid
    // of 16px face tiles, and merged quads repeat the tile across their extent.
    const vec2 faceTile = 1.0 / (vec2(3.0, 2.0) * 256.0);
    vec2 tile;
    vec2 inTile = modf(sv_quadUv, tile) * faceTile;
    vec2 baseUv = vec2(modelId & 0xFFu, (modelId >> 8) & 0xFFu) * (1.0 / 256.0) + vec2(face >> 1, face & 1u) * faceTile;
    vec2 texPos = inTile + baseUv;
    vec2 gradUv = sv_quadUv * faceTile;
    vec4 albedo = textureGrad(sv_blockModelAtlas, texPos, dFdx(gradUv), dFdy(gradUv));
    vec4 exact = textureLod(sv_blockModelAtlas, texPos, 0.0);

    // Voxy's discard rules. Applied after the pack ran so every lane of the quad
    // stays alive for the pack's own derivatives.
    bool kill = any(notEqual(clamp(tile, vec2(0.0), quadExtent), tile));
    kill = kill || SV_IN_FRONT(gl_FragCoord.z, texelFetch(sv_depthBound, ivec2(gl_FragCoord.xy), 0).r);
#ifdef SV_TRANSLUCENT
    kill = kill || exact.a == 0.0;
#else
    kill = kill || ((flags & 1u) == 1u && exact.a <= 0.1);
    albedo.a = 1.0;
#endif

    SvBlockModel model = sv_modelData[modelId];
    uint tintState = (flags >> 2) & 3u;
    bool doTint = tintState == 2u;
    if (tintState == 1u) {
        doTint = abs(exact.r - exact.g) < 0.02 && abs(exact.g - exact.b) < 0.02;
    }
    vec4 biomeTint = doTint ? sv_unpackRGBA(sv_interData.z).yzwx : vec4(1.0);
    float shade = sv_faceShade((model.flagsA & 8u) != 0u, face);
    uint light = sv_interData.y & 0xFFu;
    uint stateId = model.customId;

    sv_tint = vec4(biomeTint.rgb * shade, biomeTint.a);
    sv_uv = texPos;
    // Sodium's light ABI is (level + 0.5) / 15, so sky light 0 reads as 1/30.
    sv_lightLevel = clamp((vec2((light >> 4) & 15u, light & 15u) + 0.5) / 15.0, 0.0, 1.0);
    sv_material = stateId < uint(sv_materials.length()) ? (sv_materials[stateId] & 0xFFFFu) : 0u;
    // Voxy already resolved cutouts above, so the pack must not alpha test again.
    sv_sodiumMaterial = 0u;
    sv_LodAlbedo = albedo;

    vec4 clip = sv_vanillaViewProjection * vec4(sv_relPos, 1.0);
    float vanillaDepth = clip.z / clip.w;
    sv_FragCoord = vec4(gl_FragCoord.xy, vanillaDepth, gl_FragCoord.w);

SV_ASSIGN_INPUTS
    sv_packMain();

#ifndef SV_TRANSLUCENT
    // Voxy's composite blends by alpha; opaque LOD terrain must cover fully.
    SV_COLOR_OUTPUT.a = 1.0;
#endif
    sv_lodDepth = vanillaDepth;
    if (kill) discard;
}
