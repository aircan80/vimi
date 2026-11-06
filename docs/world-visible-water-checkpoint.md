# First visible water — checkpoint

Static flat water now renders in the World viewer's persistent GPU terrain path. It is an
untextured **diagnostic teal** surface (sRGB 20,115,148, constant 0.40 blend), not a claim of
client liquid appearance: no animation, refraction, shoreline foam, liquid type colour or
underwater optics.

## What changed versus the earlier proposal

`world-flat-water-visible-proposal.md` (on `feature/world-next-integration`) proposed a separate
WATERv1 upload/commit lane, an RGBA8 auxiliary target and one selected water tile. This slice
takes a smaller route that keeps every existing lifetime rule:

- **Water travels inside its terrain tile packet.** After the actual terrain Source write and
  before the packet's one-shot MOVE, the producer asks the reviewed `WorldWaterPreview` for that
  exact `WrittenTile`, copies the scalar mesh inside the writer callback and closes the water
  owner at once. No water Source credit outlives the producer. The copy is appended to the
  still-unadvertised packet as a `VIMIWTR1` trailer, so water shares terrain's slot, lease,
  commit and retirement. Removal or replacement of a tile removes its water atomically. There is
  no separate revision to keep causal.
- **Every resident tile can carry water**, up to nine tiles. The per-tile bound is unchanged at
  8192 cells, 32768 vertices and 49152 indices.
- **No new shaders.** Water triangles are packed after terrain in the same per-slot instance
  buffer and drawn with the existing world-stream program, atlas disabled. They are drawn after
  all opaque terrain with strict reversed-depth GREATER, depth writes on, RGB-only writes and a
  constant BLEND_FACTOR. Coincident duplicates at shared chunk edges fail the strict depth test,
  so they cannot blend twice. Output alpha stays 255. Avoiding shader changes avoids new DX11
  compiles on Windows.

## Wire format (`WorldStreamTileFile`)

The trailer is optional. It is present only when the expected packet length exceeds the
terrain header total.

- **Header: 128 bytes, little-endian.**

  | Offset | Content |
  | --- | --- |
  | 0 | magic `VIMIWTR1` |
  | 8 | version 1 |
  | 12 | cells |
  | 16 | vertices (= 4 × cells) |
  | 20 | indices (= 6 × cells) |
  | 24 | layers |
  | 28 | zero |
  | 32 | terrain geometry SHA-256, raw |
  | 64 | ROOT FDID |
  | 72 | ROOT CKey |
  | 88 onward | zero |

- **Body:** float32 positions as native x north, y west, z up; then u8 depths; then u32 indices.
- **Child-side validation:**
  - geometry SHA, ROOT FDID and ROOT CKey equal the terrain header;
  - counts are exact;
  - the length is exact;
  - every cell uses the fixed index pattern `b,b+2,b+1 / b+1,b+2,b+3`;
  - positions are finite and lie within the terrain vertex footprint ± one liquid cell;
  - the whole-file SHA from the offer covers the trailer.
- **Append (parent):** re-hashes the existing bytes against their expectation, appends, forces
  to disk and returns the new expectation.
  - On failure or cancellation it truncates back to terrain-only.
  - If the restore is confirmed, the producer moves terrain-only, because water never removes
    terrain.
  - Otherwise the producer fails and the packet is deleted.
- **Bound:** `MAX_PACKET_BYTES` grows 2,284,544 → 2,907,264. The extra 622,720 B is the maximum
  water trailer.

## Memory (logical)

- **Child, per slot:** the instance buffer grows from 65,536 to 81,920 triangles, +786,432 B.
  Across 10 slots that is +7.5 MiB GPU.
- **Child, per upload:** one packed upload grows by the same amount, and the decoded water
  arrays add ≤ 622 KB. The upload ticket bound goes from 48 to 64 (a 64-bit mask): 40 geometry
  chunks plus 16 atlas rows.
- **Parent:** one water copy (≤ 622 KB) plus a 64 KiB re-hash scratch per producer. The
  re-hash reads the ≤ 2.3 MB terrain packet once more.

## Liquid tables

Indirect layers need native evidence for LiquidType and LiquidMaterial. Terrain alone never
prepared them, so on a library without them water stayed honestly `UNRESOLVED/TABLE_PROFILE`.

The controller now starts `BuildWorld.prepareWaterTables` (configured source, BACKGROUND
priority) when it opens. It waits at most 15 s before admitting the first tile. On timeout or
failure, terrain proceeds without water and the status reads "water tables unavailable".
`-Dvimi.world.water=false` disables water entirely.

## Verification

- `check assemble` passes, including the new `WorldStreamTileWaterTest`, which covers:
  - a maximum terrain+water packet that exactly fills the new bound and packs after terrain;
  - terrain-only compatibility;
  - foreign geometry and off-tile water refused;
  - tampered terrain or trailer refused;
  - canceled append restoring terrain-only;
  - an exact replacement MOVE;
  - invented or repaired geometry refused.
- **Private actual-data Vulkan proof.** It used the read-only library and the real child, ran
  headless through `WorldStreamSourceController`, and used Azeroth tile 33,32 (ROOT 777952,
  READY, 819 cells). The flat water fills the actual lake basin, matches the shoreline and is
  occluded by nearer terrain from four headings.
  - With `-Dvimi.world.water=false`, the same camera shows the bare lake bed.
  - The app-like run prepares no tables up front; `prepareWaterTables` makes water appear.
  - Each run ends with all owners drained and atlas claims at 0.
  - Frames and logs are under `$HOME/.cache/vimi-tmp/water-visible-proof/` and are not
    committed, because they derive from game assets.

## Independent implementation review

A fresh reviewer, given only the code, found no blocker in 1aa76b62. Fixed in follow-up:

- **MAJOR, fixed.** Only the child checked the footprint, so off-tile water would have made the
  child reject the whole tile, terrain included. `PacketFile` now records the terrain footprint
  at write time, and `move(water)` applies the same `onFootprint` check before appending.
  Failing water is dropped (`waterDropped`) and terrain moves unchanged.
- Pre-write refusals (`IllegalArgumentException`, buffer overflow) degrade to terrain-only. They
  no longer fail the tile.
- Pre-verification failures (`PacketChanged`: bytes or size changed before the append) and a
  failed rollback never advertise the packet. The tile fails and its owner deletes the file.
  Cleanup failures are suppressed onto the primary failure, and cancellation is re-checked
  before any degraded MOVE.
- `prepareWater` swallows only clean leaf `IllegalArgumentException`/`IllegalStateException`
  and clean refusals. Source faults with a cause or suppressed failures stay fatal.
  `WaterCopy.of` bounds index and depth values before allocating.
- New writer tests cover:
  - attached water decoding in the child;
  - off-tile water dropped while terrain moves;
  - cancellation propagating with the packet still owned and deleted on close;
  - a changed packet never moving.
- Accepted: append IO runs under `PacketFile`'s monitor. That is not a deadlock: close releases
  the monitor before deleting, and producer stops cancel first.

## Not yet verified or done

- **Untested backends:** Metal and DX11 are unexecuted, and only Linux Vulkan was exercised.
  The blend factor is core bgfx state on every backend.
- **No GUI-level automated test exercises a positive water tile:** the authored GUI fixtures
  have no liquid tables. That path is covered only by the private actual-data proof.
- **No CPU fallback water.** The software renderer remains terrain-only.
- **Tiles produced before the table wait finished** stay without water until they are produced
  again. A late table-ready event does not refresh them.
- **Out of scope here:** nonplanar layers (for example the seven in ROOT 778147) remained
  unresolved, and liquid appearance, animation and type colours are a later slice. The section
  below lifts the nonplanar, overlap and cap limits.

## Sloped, overlapping and whole-tile water (#171)

Measured on real data (`wowf` 1.60.1), most water the Source declined was not exotic: river and
lake layers whose used heights differ (often by under 0.05 yards of float jitter, sometimes by
metres on rivers and falls), two instances in one chunk, and coast or open-sea tiles over the
old 128-layer / 8192-cell owner caps.

- **Heights.** Every qualified height+depth (LVF 0) layer keeps its actual used vertex heights.
  A flat layer produces exactly the geometry it did before. A sloped cell is still the two
  triangles of the fixed NE–SW diagonal; for a nonplanar cell that diagonal is a renderer
  convention, not a source fact: at the cell centre the two diagonals differ by half the cell's
  twist, |NW + SE − NE − SW| / 2. `NONPLANAR_PROFILE` is gone; `planar(layer)` stays as a
  fact.
- **Overlap.** Several instances of one chunk covering the same cell are all kept: they are
  separate source surfaces (for example a river layer above a lake). `MULTILAYER` is gone. Two
  layers at one height cannot blend twice: the water pass uses strict reversed depth with depth
  writes.
- **Caps.** One owner now holds a whole root: 1024 layers (256 chunk headers x 4 instances),
  16,384 cells and 82,944 height samples. A full-tile owner is about 1.3 MB of heap; two credits
  remain the limit.
- **Packet and GPU budget unchanged.** The bridge copies a full chunk (8 x 8 cells) whose used
  heights are all equal as one quad over the chunk, which draws the same plane. Open sea then
  costs 256 quads per tile instead of 16,384 cells. The trailer stays at 8192 quads and the
  renderer at 16,384 water triangles per slot, so #276's 26-slot terrain budget does not grow.
  Layers are copied whole in source order; a layer that would pass 8192 quads is left out and
  the tile's water note says "capped". `MAX_WATER_LAYERS` (a count in the trailer header) grows
  from 128 to 1024.
- **Still declined.** Layers of another vertex format (LVF 1 and 3: every magma, slime, fel and
  tar type in this build) stay unsupported. Because their vertex stream length is not
  qualified yet, a tile with one of them still declines all its water (`UNRESOLVED_FOOTPRINT`).
  Every LiquidType whose material qualifies (LVF 0, materials 1 and 130) is a water type
  (SoundBank 0 or 1), so the teal diagnostic never paints lava as water.

| real data, actual Source | before | after |
| --- | --- | --- |
| Zephras Isle (map 2991), 16 tiles with MH2O: layers drawn | 717 of 764 | 764 of 764 |
| Zephras Isle: source cells / copied quads | 29,795 / 29,795 | 31,654 / 15,085 |
| Elwynn and its coast (map 0, x 46–51, y 28–37), 47 tiles: tiles with water | 34 | 44 |
| Elwynn: layers drawn | 1,580 of 3,668 | 3,556 of 3,668 |
| Elwynn: source cells / copied quads | 83,798 / 83,798 | 176,780 / 56,450 |
| Largest tile (cells before, quads after; bound 8192) | 8,192, capped | 4,711 |

The three Elwynn tiles still without water carry Slow Magma (LiquidType 7, LVF 1).

## Layers without vertex heights (#171)

Layers that carry no per-vertex height are now decoded generally:

- **No vertex data** (`vertexOffset` 0): every vertex takes the instance's declared height, when
  the declared minimum and maximum are one value (signed zeros are equal). Depths are 0. A declared
  range gives no per-vertex height and stays a stated refusal (`VERTEX_ABSENT`).
- **Depth-only streams (LVF 2):** the stream holds one depth byte per vertex. Heights come from the
  declared height the same way. A direct format-2 stream's length is now known, so it is reserved
  in the overlap checks like format 0.
- **LiquidType 2** with a LiquidObject is depth-only. This matches the established extractor
  convention: a secondary value below 42 is the vertex format itself, LiquidType 2 is depth-only
  without a table lookup, and any other type takes its LiquidType → LiquidMaterial LVF. When this
  source's own LiquidType row 2 resolves another format, the owner records the disagreement in
  `issues()` instead of obeying or hiding it.
- **Water formats:** formats 0 and 2 are water. Formats 1 and 3 (magma, slime) stay unsupported.
- **Height rule:** the extractor convention places depth-only and no-vertex layers at height 0.
  The declared-height rule agrees with it whenever the declared height is 0. Another declared
  level follows the source's own declaration, and the owner records an issue saying how many
  layers departed from the convention.

None of these occur in `wowf` 1.60.1: the earlier survey of all 591 Eastern Kingdoms tiles with
MH2O found no layer without vertex data and no LiquidType 2. Ocean there is LiquidType 1250
through LiquidObject 42 with LVF 0 heights, which the sloped/whole-tile work above already draws.
They are covered by authored tests only.

The frame probe was rerun with the merged branch: an Elwynn river tile around a 1.69-yard slope
rendered three of four headings with water on every resident tile. The fourth heading produced
no frame within its window, which is a probe settle issue to retry on the measurement open.
