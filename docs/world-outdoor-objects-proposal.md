# Outdoor WMO exteriors in the streaming World Explorer

Status: implementation plan for issue #172 (cities, buildings and doodads). This builds on
the merged one-asset BASIC_WMO work (`world-wmo-*-checkpoint.md`) instead of replacing it.

## Why the merged WMO pass is not yet visible

Main has a bounded raw WMO parser (`WmoFormats`), a source factory admitting exactly one
single-group ADT-placed WMO (`WorldBasicWmo`, at most 512 vertices and six images), a
structural annex codec (`GpuWorldWmoAnnex`), a native pass (`BgfxWorldWmoPass`) and an
optional `--wmo-annex` flag on the **cold** immutable terrain child. The GUI World Explorer
uses the **hot** streaming child (`WorldStreamMain`/`BgfxWorldStreamRenderer`) whenever GPU
rendering is enabled for a `WorldService` handle with terrain streaming (`WorldView`
`streamingMode`); no GUI path passes `--wmo-annex`, so none of that work is reachable from
the application. In addition,
real buildings exceed the one-group/512-vertex preview profile by orders of magnitude.

## First visible slice: BASIC_EXTERIOR_V1

Goal: real textured building exteriors placed on terrain in the existing 3x3 streaming
neighborhood, drawn in the same frame, camera and depth buffer as terrain, with honest
counts of what was not drawn.

Admitted (everything else is counted as unsupported, never guessed):

- Placements: obj0 MODF records with the direct-FileDataID flag `0x8` required, and only
  the scale-enable `0x4` and LOD-capable `0x2` flags besides it (LOD0 groups are always
  drawn). Any other flag (for example `0x1`) is an unsupported placement. Finite position,
  heading-only rotation (pitch/roll zero). With `0x4` the recorded scale/1024 applies and
  must be positive; without it the raw field is not interpreted and scale is 1.0. The
  transform is the reviewed ADT heading rule pinned in `WorldBasicWmo.Transform`.
  Identical duplicate unique IDs within one obj0 share one placement; conflicting
  duplicates are all omitted.
- Roots: `WmoFormats` facts. GFID is flat with LOD0 in the first groupCount entries (pinned
  wow.export WMOLoader 4474fa4 and TrinityCore vmap4 ec72c19); the GFID count must be a
  positive multiple of groupCount. Zero entries are unsupported groups. Roots with a MOTX
  name block are unsupported, because their texture words are name offsets.
- Groups: only groups whose MOGP flags carry exterior `0x8` and not interior `0x2000`.
  Interior-only groups, portals and BSP are not evaluated; walking inside is not a claim.
- Batches: plain material-byte batches (MOBA flags 0) whose material has blend mode 0
  (opaque) and a nonzero texture1 FileDataID. Other blend modes (alpha key, blended glass,
  additive) are unsupported batches. Unlike `WorldBasicWmo`, material shader IDs other than
  0 are admitted and approximated by their texture1 stage only; this is disclosed as BASIC
  lighting, not client parity, and pinned by tests.
- Images: existing strict BLP2 decoder; the largest mip with both sides at most 256 is
  selected, falling to 128 or 64 when the tile pixel budget is nearly spent. Missing,
  protected, oversized or undecodable images omit only their batches.
- Shading: existing world-wmo shaders unchanged (fixed light, terrain-matching fog, discard
  beyond 2200). No vertex colors, local lights, doodad sets, liquids or animated materials.

Not in this slice: M2 doodads (WMO doodad sets and MDDF placements), cutout/blended
materials, global WMO-only maps (dungeon lane, #170), larger view distance (#173).

## Bounds

Caps are **per tile**, because each tile's object set is an immutable output of one
serialized source operation and travels with that tile's slot. Selection must not depend
on the camera or on which neighbors are loaded, so omission order is obj0 source order.

| Per tile | Cap |
| --- | ---: |
| Placements drawn | 64 |
| Distinct roots read | 24 |
| Group sources read | 256 |
| Image sources read | 64 |
| Vertices / indices | 98,304 / 294,912 |
| Draw ranges / meshes | 1,024 / 256 |
| Decoded pixels | 1,048,576 (4 MiB RGBA) |
| Root, group, image source | 1 MiB each |

One raw source input is live at a time, as in the existing scope engine. A root whose LOD0
groups would exceed the remaining group read budget, or whose geometry exceeds the
remaining vertex/index/draw budget, is omitted whole: caps never split a building.
Every omission is counted by reason: unsupported placement, capped placement, failed root,
interior group, unsupported group, unsupported batch, missing image and unavailable source
(missing, protected or larger than the 1 MiB source cap, reported separately rather than
silently dropped).

Worst case per tile file: 3 MiB vertices (32 bytes each), 576 KiB indices, 4 MiB pixels
and small tables, so about 7.6 MiB. The child holds at most 10 slots (9 active plus one
staged), so the worst-case object GPU payload is about 76 MiB in addition to the existing
terrain preallocation. The objects file needs its own length and SHA in the tile header
under the new profile, its own size ceiling (8 MiB), and the child must charge it in its
stats. Real tiles are expected to be far below this; the slice should measure them.

Cross-tile duplicates: a large WMO recorded in several neighboring obj0 files is drawn
once per carrying tile. Identical geometry at identical depth fails the strict GREATER test,
so the extra draws are invisible but cost memory; sharing assets across slots is a later
optimization. Many serialized reads (up to about 350 per tile) mean object preparation
lags terrain. It runs after terrain on the same lane and stays cancelable between reads.

## Delivery

1. Source (done: `world-outdoor-exterior-source-checkpoint.md`): new `WorldStreamScopes` use profile with the bounds above, a multi-placement,
   multi-group exterior factory and an immutable neutral per-tile object set, with
   synthetic chunk-stream tests (malformed, unsupported flags, interior groups, blend
   modes, caps and deterministic omission order).
2. Packet and child (done: `world-outdoor-exterior-packet-checkpoint.md`): an immutable per-tile objects file written next to the tile packet and
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
