#version 450
// Replaces Voxy's LOD composite: blends the LOD colour into Minecraft's frame,
// writes the LOD's vanilla-projection depth, and copies the LOD values of every
// Sulkan scene target (e.g. surface.data) into Sulkan's own targets, so Sulkan's
// post passes see LOD terrain exactly like vanilla terrain. The depth test keeps
// vanilla terrain that is in front.

layout(set = 0, binding = 0) uniform sampler2D sv_lodColour;
layout(set = 0, binding = 1) uniform sampler2D sv_lodDepth;
SV_TARGET_DECLARATIONS
layout(location = 0) out vec4 sv_colour;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(sv_lodDepth, pixel, 0).r;
    // Cleared to -1 where no LOD fragment landed. Real LOD depth can dip slightly
    // below 0 past vanilla's far plane (when the far plane is not extended).
    if (depth <= -0.5) discard;
    vec4 colour = texelFetch(sv_lodColour, pixel, 0);
    if (colour.a == 0.0) discard;
    gl_FragDepth = clamp(depth, SV_MIN_DEPTH, SV_MAX_DEPTH);
    sv_colour = colour;
SV_TARGET_WRITES
}
