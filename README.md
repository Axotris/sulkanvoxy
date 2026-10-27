# Sulkan Voxy

A client-side Fabric bridge that renders [Voxy](https://github.com/MCRcortex/voxy)'s LOD terrain with the active
[Sulkan](https://www.sulkan.org/) shader pack, so shaders apply to every chunk: vanilla and LOD.

## Requirements

| Mod       | Version tested                                   |
|-----------|--------------------------------------------------|
| Minecraft | 26.3, Vulkan backend                             |
| Sulkan    | 0.6.0 (Sulkan Realistic, the built-in pack)      |
| Voxy      | Voxyrium `0.2.20-beta-b66da98`, native Vulkan renderer |
| Sodium    | 0.9.2                                            |

The bridge only works with Voxy's **native Vulkan** renderer. With the OpenGL or Blaze3D renderers, or with shaders
turned off, it does nothing and Voxy renders as usual.

## What it does

Without the bridge, Sulkan only replaces Sodium's terrain shaders. Voxy draws LODs with its own Vulkan pipeline and
vanilla-style lighting, then composites them clamped onto vanilla's far plane. Sulkan's post-processing then treats
LOD pixels as unlit, entity-like surfaces at render distance.

With the bridge:

- **LODs run the pack's own terrain shader.** The bridge takes the fragment shader Sulkan built for Sodium's
  terrain, with the pack's current options, and compiles it into Voxy's LOD pipeline. Voxy's quads are decoded into the
  same inputs Sulkan's terrain vertex shader provides: world position, tint and face shading, sky/block light, and
  the Sulkan material. LODs get the same sun and sky lighting, shadows (where cascades reach), specular, wetness,
  emissive accents and water as normal chunks.
- **Sulkan's G-buffer covers LODs.** When the LODs are composited, the bridge also writes them into Sulkan's scene
  targets (Sulkan Realistic's `surface.data`). Atmosphere, AO, sun shafts, mist and water reflections then see LODs
  as terrain.
- **Correct LOD depth.** While the bridge is active, vanilla's far plane is raised so LOD depth is no longer clamped.
  Fog and mist use real LOD distances. Reverse-Z float depth keeps near-plane precision.
- **Per-block materials.** Every LOD model carries its vanilla block-state id. A small GPU table maps it to the
  pack's material id (water, leaves, ice, glowing blocks, …) and is rebuilt when the pack or its options change.
- **LOD water** refracts and absorbs the LOD terrain beneath it, using a snapshot of the opaque LODs.

Voxy keeps its culling, geometry streaming and draw-command generation; the bridge only replaces the LOD draws and the
composite.

If the bridge can't adapt a pack (see the log), it logs why and leaves LOD rendering to Voxy until the pack changes.
If something it needs isn't ready for a frame, such as during a resource reload, Voxy renders that frame.

## Configuration

`config/sulkanvoxy.json` (read at startup):

| Key                 | Default    | Meaning                                                                  |
|---------------------|------------|--------------------------------------------------------------------------|
| `enabled`           | `true`     | Master switch. `false` leaves LOD rendering entirely to Voxy.            |
| `minimumFarPlane`   | `262144.0` | Far plane in blocks while the bridge is active (0 keeps vanilla's).      |
| `dumpShaderOnError` | `true`     | Log the generated LOD shader if it fails to compile.                     |

Tip: Sulkan Realistic has its own ambient occlusion. Voxy's SSAO adds a second AO pass on LODs; set Voxy's SSAO to off
if distant terrain looks too dark in creases.

## Pack compatibility

The bridge relies on Sulkan's terrain shader interface, which Sulkan Realistic defines:

- fragment inputs named `tint`, `uv`, `relativePosition`, `lightLevel`, `fade`, `materialId` and `terrainMaterial`
  (any other inputs are zero on LODs, and a warning is logged);
- atlas sampling through `sulkanSampleTerrainMaterial` (from Sulkan's `terrain_material.glsl`);
- uniforms and samplers only (no storage buffers, images or push constants in the terrain fragment shader).

Packs that follow it work without changes.

## Limitations

- LODs receive Sulkan's shadows but don't cast them: Sulkan's shadow map only contains vanilla terrain and entities.
- Sulkan Realistic's distance mist is tuned for vanilla render distances, so far LODs stay fairly crisp compared to
  Voxy's own fog.
- The extra LOD targets use about 20 bytes per pixel (about 40 MiB at 1080p).
- Voxyrium is not Vulkan validation-layer clean by itself, so validation runs of this stack fail inside Voxy.

## Building

Sulkan and Voxyrium aren't published to a maven repository. The bridge compiles against the jars in `libs/` (their
names are in `gradle.properties`). Replace them when updating either mod, then run:

```
./gradlew build
```

The mod jar is written to `build/libs/`.

## License

CC0-1.0. Sulkan and Voxy are separate projects under their own licenses.
