# Outdoor WMO exteriors in the streaming World Explorer

Status: implementation plan for issue #172 (cities, buildings and doodads). This builds on
the merged one-asset BASIC_WMO work (`world-wmo-*-checkpoint.md`) instead of replacing it.

## Why the merged WMO pass is not yet visible

Main has a bounded raw WMO parser (`WmoFormats`), a source factory admitting exactly one
single-group ADT-placed WMO (`WorldBasicWmo`, at most 512 vertices and six images), a
structural annex codec (`GpuWorldWmoAnnex`), a native pass (`BgfxWorldWmoPass`) and an
optional `--wmo-annex` flag on the **cold** immutable terrain child. The GUI World Explorer
uses the **hot** streaming child (`WorldStreamMain`/`BgfxWorldStreamRenderer`) whenever GPU
rendering is enabled, so none of that work is reachable from the application. In addition,
real buildings exceed the one-group/512-vertex preview profile by orders of magnitude.

## First visible slice: BASIC_EXTERIOR_V1

Goal: real textured building exteriors placed on terrain in the existing 3x3 streaming
neighborhood, drawn in the same frame, camera and depth buffer as terrain, with honest
counts of what was not drawn.

Admitted (everything else is counted as unsupported, never guessed):

- Placements: obj0 MODF records with the direct-FileDataID flag `0x8`, optional scale flag
  `0x4`, no other flags, finite position, heading-only rotation (pitch/roll zero), positive
  scale. Transform is the reviewed ADT heading rule already pinned in
  `WorldBasicWmo.Transform`. Identical duplicate unique IDs across neighboring tiles share
  one placement; conflicting duplicates are omitted.
- Roots: `WmoFormats.Limits.standard()` facts, GFID inventory with a nonzero LOD0 entry per
  group. Legacy MOTX name-only roots are unsupported.
- Groups: only groups whose MOGP flags carry exterior `0x8` and not interior `0x2000`.
  Interior-only groups, portals and BSP are not evaluated; walking inside is not a claim.
- Batches: the material's blend mode 0 (opaque) with a nonzero texture1 FileDataID. Other
  blend modes (alpha key, blended glass, additive) are counted as unsupported batches.
  Material shader IDs other than diffuse are approximated by their texture1 stage only;
  this is disclosed as BASIC lighting, not client parity.
- Images: existing strict BLP2 decoder; the largest mip with both sides at most 256 is
  selected. Missing, protected or undecodable images omit only their batches.
- Shading: existing world-wmo shaders unchanged (fixed light, terrain-matching fog, discard
  beyond 2200). No vertex colors, local lights, doodad sets, liquids or animated materials.

Not in this slice: M2 doodads (WMO doodad sets and MDDF placements), cutout/blended
materials, global WMO-only maps (dungeon lane, #170), larger view distance (#173).

## Bounds

Per neighborhood: at most 9 obj0 sources (existing 8 MiB each), 192 placements, 64 distinct
roots, 1,024 admitted groups, 262,144 vertices, 393,216 triangles, 2,048 draw batches,
128 images of at most 256x256 (8 MiB RGBA). Root and group sources at most 1 MiB each, image
sources at most 1 MiB. One raw source input is live at a time, as in the existing scope
engine. Exceeding a cap omits the farthest placements (stable distance then unique-ID
order) and is reported, never silently truncated inside a building.

## Delivery

1. Source + neutral data + packet codec (this repository's concepts-world, rendering-world,
   rendering-bgfx) with synthetic tests.
2. Hot child object pass and control command: one immutable objects packet per committed
   neighborhood, uploaded on the native owner thread, drawn after terrain with the same
   camera; failure leaves terrain unchanged and is reported.
3. GUI: World Explorer status shows drawn/omitted building counts and the BASIC disclosure.
