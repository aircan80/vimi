# BASIC_EXTERIOR_V1 source checkpoint (issue #172, slice 1)

This implements delivery step 1 of `world-outdoor-objects-proposal.md`: the source side only.
There is still no packet, child, renderer or GUI path, so nothing new is visible yet.

## What exists

- `WorldStreamScopes.UseProfile.BASIC_EXTERIOR`: per-tile read budgets of 24 WMO roots, 256
  group sources and 64 images, each source capped at 1 MiB, and at most 346 consumed bindings
  (WDT, OBJ0 and those reads). Profile limits now live on the enum; the existing
  `TERRAIN_BASIC`, `BASIC_WMO` and `LIQUID_INSPECTION` values are unchanged.
- `WorldExteriorObjects.prepare(engine, scope, tile, token)`: one serialized source operation
  per recorded ADT tile. It returns an immutable neutral `TileObjects`: shared per-root
  `Model`s of exterior LOD0 `Mesh`es (32-byte local-space vertices, compacted indices,
  `DrawRange`s), decoded opaque `Image`s and per-placement double ADT transforms. The engine
  revalidates every consumed binding after the factory returns, and closes the set on
  staleness or cancellation.
- The placement transform reuses `WorldBasicWmo.Transform` (the reviewed ADT heading rule)
  with a qualified scale, rather than restating it.

## Qualification (as documented, nothing guessed)

- Placements need MODF flag `0x8` and may also carry only `0x4` and `0x2`. Pitch and roll
  must be zero, and the translation must be finite and within `TerrainScene.MAX_COORDINATE`.
  `0x4` applies scale/1024 and needs it to be positive; without `0x4` the scale is 1.0.
  Identical duplicate unique IDs share one placement; conflicting duplicates are all omitted.
- Roots: no MOTX, GFID present, positive group count, GFID count a positive multiple of
  it. Only the first groupCount (LOD0) entries are read, and zero entries count as
  unsupported groups.
- Groups: `WmoGroupSpans` bounds (at most 16,384 vertices, 64 batches and one UV stream).
  The interior flag `0x2000` wins over exterior `0x8`; groups with neither are unsupported.
  Overlapping batches and zero-length normals are unsupported. Other normals are normalized in
  double before one float cast. MOCV and MLIQ are ignored, not drawn.
- Batches: MOBA flags 0, an in-range material, blend mode 0 and a nonzero texture1. Material
  shader IDs other than 0 are approximated by texture1 only (BASIC lighting, not parity).
- Images: strict BLP2 at the largest declared mip with both sides at most 256, falling to 128
  or 64 near the 1,048,576-pixel tile budget. Alpha is forced to 255 for blend mode 0. A
  missing level is undecodable; no level is substituted.

## Caps and accounting

Caps never split a root. If a root's LOD0 reads exceed the remaining group budget, or its
geometry exceeds the remaining 98,304 vertices, 294,912 indices, 1,024 ranges or 256 meshes,
the whole root is capped, and so are its placements. A later, smaller root can still fit.
Order is obj0 source order only.

`TileObjects.count(Count)` reports mutually exclusive buckets:

- Every MODF record lands in one PLACEMENT bucket.
- Every root that was read lands in one ROOT bucket.
- LOD0 groups and their batches are counted for roots that were not failed or capped.
- Every needed texture1 lands in one IMAGE bucket.
- MDDF doodads are counted as not in this slice.

Unavailable sources (a `SOURCE_INPUT` refusal: missing, protected, or over the 1 MiB cap,
which is declined before opening) count as `*_UNAVAILABLE`. Any other refusal, including a
source changing during a read, fails the whole set.

## Validation

`WorldExteriorObjectsTest` has 13 authored chunk-stream tests and uses no game assets. They
cover the multi-placement and multi-group chain with the transform oracle, placement flags,
rotation, scale and duplicates, root failures and unavailable sources, group outcomes, the
LOD1 entries that are never read, batch and blend qualification, missing and undecodable
images, mip selection, every cap, cancellation between reads, source change and determinism.
Causal negative controls failed the suite as expected:

- disabling the interior check failed 2 tests;
- disabling the geometry cap failed 1 test;
- admitting non-opaque blend modes failed 2 tests.

There is no real-data measurement in this slice.

## Next slice

The next slice is delivery step 2: an immutable per-tile objects file next to `tile-<txn>.wst`,
named in the reserved tile-header range under a new session profile with its own length, SHA
and 8 MiB ceiling. The hot child would decode and upload it in bounded steps, and draw it with
the existing world-wmo shaders after the terrain slot loop, rebased to the camera.
