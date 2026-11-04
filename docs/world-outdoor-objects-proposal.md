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

1. Source: new `WorldStreamScopes` use profile with the bounds above, a multi-placement,
   multi-group exterior factory and an immutable neutral per-tile object set, with
   synthetic chunk-stream tests (malformed, unsupported flags, interior groups, blend
   modes, caps and deterministic omission order).
2. Packet and child: an immutable per-tile objects file written next to the tile packet and
   named in the reserved tile-header range under a new session profile, so it shares the
   slot's OFFER/COMMIT/CANCEL/retirement lifetime. The child decodes it on the existing
   decoder worker, uploads in bounded steps and drops it with the slot generation. Object
   failure leaves the tile's terrain committed and is reported in status.
3. GUI: World Explorer status shows drawn and omitted building counts and the BASIC
   disclosure.

## Hot-path facts that shape delivery

- The hot child rebases to the camera every frame (`BgfxWorldStreamRenderer.draw`: origin is
  the float camera position, `u_worldRebase` subtracts it). An object pass computes each
  placement's model matrix as double placement position minus that origin, then casts once
  to float, on the native owner thread, after the terrain slot loop and before the ticket.
- The control protocol has no optional sections; every field is validated. Tile packets
  (`tile-<txn>.wst`) already carry OFFER/CREDIT/COMMIT/CANCEL lifetimes per slot.
- `WorldStreamScopes` admits one source operation at a time, and streaming mode has no
  `TerrainScene`. Object preparation is therefore its own serialized source operation per
  tile, queued after that tile's terrain write, never nested inside it.
- The existing package-private WMO entry points need a public `WorldTerrainStreaming`
  facade for the GUI, unwrapping the private scope token as `prepareAndWrite` does.

## Coordination

The dungeon lane's `WorldGlobalAssetFactory` and `BgfxGlobalWmoRenderer`
(feature/world-next-integration) target GLOBAL WMO-only maps with origin zero. This slice
reuses main's `BgfxWorldWmoPass` shaders and must not fork a second global renderer.
Shared WMO group qualification should converge once either side merges.
