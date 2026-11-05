# BASIC_EXTERIOR_V1 packet and hot-child checkpoint (issue #172, slice 2)

This implements delivery step 2 of `world-outdoor-objects-proposal.md`. Outdoor WMO building
exteriors from slice 1 (`WorldExteriorObjects.prepare`) are now written per tile, decoded and
uploaded by the hot streaming child, and drawn in the GUI World Explorer in the same frame,
camera and depth buffer as terrain. Lighting is BASIC: texture1 only with the fixed world-wmo
light and fog, not client parity.

## Status: opt-in

Exteriors are **off by default** since the follow-up to #217. Pass
`-Dvimi.world.exteriors=true` to the GUI to see outdoor buildings. The reason is the readiness
lag below: under profile 2 a tile's terrain OFFER waits for that tile's object preparation, which
took 0.4 to 21 s per tile on a cold cache. That is not acceptable as a default. Under the default
profile the producer never starts object preparation, so terrain stages and commits exactly as
before #217 (`WorldStreamSourceBridgeTest` holds an injected object preparer blocked and still
gets the terrain packet).

Turning exteriors back on by default needs a decoupled delivery: terrain committed first, and
the objects file delivered later for the same tile and slot generation, attached only if the
generation still matches. That needs a new control message and child slot state, an upload into
an already active slot, and scheduling on the single source lane, because `WorldStreamScopes`
admits one source operation at a time and an object preparation would otherwise hold up the
next tile's terrain. None of this exists yet.

## Session profile

`WorldStreamEnvelope.Session` has a new `exteriorObjects` flag. The fourth profile word is `1`
for terrain only (unchanged bytes) and `2` for BASIC_EXTERIOR_V1. Under profile 2:

- the child announces `SESSION TERRAIN_EXTERIOR_V1`, and the parent requires the announcement to
  match its own session;
- tile headers may name an objects file;
- `STAGED` carries a tenth field: `0` no objects file, `1` objects drawn, `2` objects failed.

A profile 1 session never accepts a header that names objects; the child rejects that packet.
`WorldStreamTransport.Spec` gains the flag, and its old three-argument constructor keeps
profile 1. The GUI selects profile 2 only with `-Dvimi.world.exteriors=true`; by default it opens
the terrain-only profile 1 (see "Status: opt-in" above).

## Objects file (`WorldStreamObjectsFile`, `tile-<txn>.wso`)

One immutable file per tile, published with CREATE_NEW and an atomic move next to
`tile-<txn>.wst`. Little-endian:

| Offset | Content |
| --- | --- |
| 0 | magic `VIMIWSO1`, version 1, header bytes 256, total length |
| 24 | session nonce, scope SHA-256, transaction |
| 80 | lease generation, slot, tile x, y, zero, lease serial |
| 112 | counts: models, meshes, ranges, placements, images, vertices, indices, pixels, draws, zero |
| 152 onward | zero |

The body follows: the model, mesh, range, placement and image tables, then 32-byte vertices
(position, normal, uv), u16 mesh-relative indices padded to 4 bytes, and RGBA8 pixels.

- **Tile header reference.** The terrain header's reserved range names the file: bytes 656 to 660
  hold the length, 660 to 664 are zero, and 664 to 696 hold the raw SHA-256. The rest of the
  range up to 1024 stays zero. The whole-packet SHA from the OFFER covers these bytes.
- **Ceiling.** 8 MiB. The maximum slice profile is 7,952,832 bytes (98,304 vertices, 294,912
  indices, 1,048,576 pixels, 24 models, 256 meshes, 1,024 ranges, 64 placements, 64 images). A
  test writes and reads back a set at the vertex, index and pixel caps.
- **Draw budget.** At most 4,096 draws per tile, counted as the sum over placements of their
  model's ranges. So nine tiles stay far below bgfx's per-frame draw limit.
- **Validation (reader and builder share it).**
  - The identity must equal the tile header's: nonce, scope, transaction, lease, x and y.
  - The length must be exact, every reserved byte zero and the digest must match.
  - Tables must be contiguous, and every index must be within its mesh.
  - Every range must be a triangle multiple within its mesh and name an existing image.
  - Floats must be finite and bounded.
  - Placements must be finite, with a scale in (0, 64] and a unit heading.
  - Image sides must be powers of two up to 256, and the pixels must sum exactly.

## Ownership and lifetime

- The GUI bridge (`WorldStreamSourceBridge.Producer`) prepares the object set first, as its own
  serialized BASIC_EXTERIOR source operation that is never nested in the terrain write. It
  copies the set into `WorldStreamTileWriter.Exterior`, closes the source set and publishes the
  objects file.
- The terrain write then names the file in its header. `WorldStreamPacketFile` adopts the
  sibling path, so close or retirement deletes both files together.
- Before adoption, the bridge's `ObjectsFile` owner deletes the objects file on any failure.
- The objects file therefore shares the packet's OFFER/INPUT_CONSUMED/retirement lifetime. The
  child reads both files on its existing decoder worker before `INPUT_CONSUMED`.
- `WorldTerrainStreaming.prepareExteriorObjects` is the new public facade. It unwraps the private
  scope token as `prepareAndWrite` does. `WorldExteriorObjects` and its result types are now
  public read-only views.

## Child: decode, upload, draw

- **Decode.** `WorldStreamSession.decodeTile` runs on the existing decoder worker.
  - A terrain packet failure still rejects the tile.
  - Any objects failure (missing, truncated, changed bytes, invalid tables) leaves the terrain
    decoded with objects state 2.
  - Cancellation still cancels the whole tile.
  - Valid objects are packed into native-order upload memory (`WorldStreamPackedObjects`).
- **Upload.** `BgfxWorldStreamRenderer` runs it after the tile's terrain submission, in steps of
  at most 262,144 bytes.
  - Resource creation comes first: one dynamic vertex buffer and one u16 dynamic index buffer per
    tile.
  - Then vertex slices, index slices, and one RGBA8 texture per step.
  - Every slice is a `bgfx_copy`, so there are no new release-callback tickets, and the packed
    input is freed as soon as the last slice is issued.
  - The tile stages only after its objects finish or fail, so objects and terrain share the slot
    generation.
  - A resource failure destroys only that tile's objects (state 2).
  - A new lease on the slot, a staged cancel or renderer close destroys the old generation's
    objects.
- **Draw.** The pass runs after the terrain slot loop and before static water. It uses the
  existing `world-wmo` program unchanged.
  - Placements share terrain's eye matrix, reversed projection and float camera origin.
  - Each model matrix is the double placement position minus that origin, cast to float once.
  - The rotation is the reviewed ADT heading rule.
  - Draw state is `BgfxWorldWmoPass.DRAW_STATE` (strict GREATER depth, no culling) with its
    sampler state.
  - If the world-wmo program or its uniforms cannot be created, terrain is unaffected and every
    objects tile reports failed.
- **Stats.** `BgfxWorldStreamRenderer.Stats.objectBytes` charges the issued GPU bytes of resident
  and building object sets.

D3D11: the world-wmo shaders are not instanced. They use `POSITION`, `NORMAL` and `TEXCOORD0`,
with no `i_dataN`, so the PR #204 instance-semantic convention does not apply. No shader or
binary changed, so nothing depends on #204's compile-script guard.

## Status

`WorldStreamTransport.Resident.exteriorObjects()` reports `NOT_IN_PROFILE`, `NONE`, `DRAWN` or
`FAILED`. The World Explorer ready line adds "N BASIC exterior buildings on M" and "building
objects failed on K". The bridge's `exteriorNote()` keeps the source-side drawn/omitted counts or
the refusal site. Detailed omission counts in the GUI remain delivery step 3.

## Validation

- `check assemble` passes.
- `WorldStreamObjectsFileTest` has 12 authored tests:
  - a round trip of every table, with tile binding and no-replace publication;
  - the maximum profile under the 8 MiB ceiling, cap refusals and the draw budget;
  - changed bytes and a wrong digest;
  - truncation and length disagreement;
  - invalid indices, image sides and transforms;
  - a canceled write leaving no file;
  - the header reserved-range reference;
  - valid objects decoding beside terrain;
  - **child-side failure isolation**: changed, truncated and missing objects files each keep the
    terrain tile with objects state 2;
  - the session profile governing headers, a cancellation inside the objects read cancelling the
    whole tile, and the envelope, `SESSION` and `STAGED` codecs;
  - the packet owner deleting both files;
  - the model matrix rebasing the double placement before one float cast.
- **Private actual-data Vulkan proof.** It ran through `WorldStreamSourceController` with the real
  hot child, headless, on Azeroth tile 33,32 (ROOT 777952). Object sources came from a private
  scratch cache filled from the configured CDN; the original library was opened read-only.
  - The status read "6 BASIC exterior buildings on 1". The source set had 6 placements drawn and
    8,277 vertices.
  - Four headings show textured buildings (thatched houses, a chapel and its bell tower) seated
    on the terrain, occluded by and occluding terrain correctly.
  - With `-Dvimi.world.exteriors=false`, the same camera shows bare terrain.
  - All owners drained, with atlas claims at 0.
  - Frames and logs are under `$HOME/.cache/vimi-tmp/exterior-visible-proof/` and are not
    committed, because they derive from game assets.
- **Measured on 3×3 real tiles (31..33, 31..33):**
  - 3 to 10 placements drawn per tile;
  - 3,478 to 18,582 vertices;
  - the 1,048,576-pixel budget is reached on most tiles, so later batches report
    `BATCH_MISSING_IMAGE` / `IMAGE_CAPPED`;
  - source preparation took 0.4 to 21 s per tile on a cold network cache.

## Independent review

A fresh reviewer read the code at ba97007e. Its findings and the fixes:

- **MAJOR, fixed.** Parent-side exterior failures could still fail the tile. Examples were a parser
  NPE, an `UncheckedIOException` and a refusal that carries a cause. Now only cancellation
  propagates. Every other runtime failure degrades to terrain only, because the terrain write that
  follows re-checks the source.
- **Fixed.**
  - The bridge caps placements in source order to the 4,096-draw budget, instead of failing the
    whole set.
  - Parent-side failures now count in "building objects failed on K".
  - An `OutOfMemoryError` while reading or packing objects no longer rejects the terrain tile.
  - Uniforms are set once per placement instead of once per draw.
  - An objects-delete failure no longer masks the packet delete.
  - `STAGED` refuses a tenth field outside the exterior profile.
- **Accepted as nits.** The size check in `finalized`, the check-then-move race in a private
  session directory, and charging `objectBytes` for a retired slot until reuse.

## Not done

- **Backends.** Metal and DX11 were not run; only Linux Vulkan was exercised.
- **Readiness lag (opt-in only).** Under `-Dvimi.world.exteriors=true` a tile's terrain waits
  for its object preparation before its OFFER, so the first terrain appears later on a cold
  cache. This is why exteriors are off by default; see "Status: opt-in".
- **Delivery step 3.** GUI omission counts and the BASIC disclosure text.
- **Not in this slice.** Doodads, cutout and blended materials, cross-tile sharing of duplicate
  WMOs, and a larger view distance.
