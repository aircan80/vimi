# Terrain objects: source-first proposal

Status: design for independent review, not an implementation approval. This is separate from the preserved packaged GPU qualification work. Terrain imagery, buildings, doodads and water are the next user priorities; small completed increments may share a prerelease.

## What exists

`formats-world/WorldFormats.wdt` reads WDT version 18 and MAID, but `Tile` retains only the root ADT, map texture and minimap IDs. The recorded obj0, obj1 and tex0 ADT references are discarded. `WorldFormats.parseAdt` reads MCNK terrain heights/holes/area metadata, skipping placement, texture and liquid chunks. There is **no WMO reader**, MDDF/MODF placement reader or MH2O/MCLQ liquid reader in this repository.

`formats-assets/BuildModels` already resolves recorded M2 references and reads native rest SKIN, draw declarations and optional geometry/bone facts. `M2Appearance`, `M2BindPoseFacts` and the reviewed static-material machinery provide useful primitives, but a creature display lookup is not a world-object appearance policy. A world placement must not manufacture CreatureDisplayInfo, replacement textures, animation, geoset visibility or a rig.

`rendering-world/TerrainScene` and `rendering-bgfx/BgfxTerrainRenderer` currently carry terrain only. Their world camera, native axes, rebased positions and reversed D32F depth must remain authoritative. Opening a separate model preview owner for every object would replace terrain instead of composing with it.

## Recommended first increment

First implement and review **recorded placement metadata**, then a bounded **static M2 doodad** draw profile in the existing terrain child. This reuses actual readers and can produce visible scenery without pretending a WMO parser already exists. WMO buildings follow through a new root/group source reader and separately reviewed material admission. Metadata-only discovery is a prerequisite checkpoint, not a claim that buildings have been rendered.

Water and layered terrain textures are independent formats/passes. They may proceed in parallel after their own exact source profiles are reviewed; do not hide their complexity inside the placement renderer. A simple rendered M2 slice should not delay a concrete independently ready terrain-texture or WMO slice.

## Recorded source chain and placement facts

Preserve all eight MAID fields with names and unsigned IDs. A backward-compatible five-argument `Tile` constructor may delegate zero object/texture IDs to an extended record, or the WDT may expose a separate immutable per-tile source record. Review this API before changing every caller. Current five-field consumers and terrain serialization must retain their meaning.

For the initial modern profile, read **only positive recorded obj0 IDs**; do not synthesize `_obj0.adt`, `_obj1.adt`, WMO group names or SKIN paths. Missing obj0 is an explicit unavailable source. Do not combine obj0 and obj1 placement streams until their ownership/LOD relationship is demonstrated; duplicates are not extra objects.

The metadata reader accepts an exact version-18 chunk stream, requires MDDF size divisible by 36 and MODF by 64, checks all chunk intervals/count products before allocation, rejects duplicate singleton chunks, and distinguishes malformed metadata from a valid empty placement list. It exposes raw flags, unsigned unique ID, source ordinal, position/rotation floats, unsigned scale and original reference kind. MODF additionally preserves bounds, doodad set and name set. Bounds are descriptive source facts, not collision geometry.

Modern MDDF direct FileDataID is selected by flag `0x40`; modern MODF direct FileDataID by `0x8`. Do not infer the reference kind just because a name block is absent. The initial reader can preserve unsupported legacy name references without resolving them. Later legacy resolution must use the exact checked MMID/MMDX or MWID/MWMO offset and a recorded source path, with bounded NUL termination and no traversal/extension guessing.

Source facts retain obj0 FileDataID plus content key and source identity. The service rechecks a receipt after reading. A failed optional object read leaves terrain available with object counts/reasons; it must not certify a complete derivative cache. The helper borrows build files, closes only streams it opens, checks cancellation while parsing and between acquisitions, and owns at most one active plus one replaceable pending neighborhood request.

Deduplication is scoped to one map/build/placement kind and source unique ID. Identical repeated declarations across adjacent tiles can share one placement. Conflicting declarations for one ID are diagnosed and omitted rather than arbitrarily taking the last copy. A model ID is an asset identity, never a placement identity: many different placements may share one model.

## Coordinate gate before rendering

Placement positions use a different file convention from MCNK native terrain coordinates. Upstream renderers also swizzle M2 and WMO vertices differently. Their matrices cannot be copied directly into Vimi's native `(X north, Y west, Z altitude)` scene.

Before admitting a placement transform, pin the source-to-native affine map, Euler axis/order/signs, degree conversion, scale rule and normal/winding treatment against two primary implementations. Independently authored tests must cover a translated asymmetric tetrahedron, each positive quarter-turn separately, noncommuting combined rotations, positive scale 512/1024/2048, transformed normals and a repeated placement across tile boundaries. Zero scale, unsupported flag-dependent scaling and nonfinite/out-of-range values remain rejected. MODF scale must be interpreted only under its proven enabling flag; do not use `scale == 0 ? 1024 : scale` as a guessed policy.

A private real obj0/M2 sample must then align its transformed source bounds/geometry with the same ADT terrain at several cameras. The current evidence establishes record layouts and IDs, **not** a reviewed Vimi placement matrix. That transform is an explicit blocker for renderer implementation, not a deferred cosmetic adjustment.

## Bounded static M2 draw profile

Use source-native M2/SKIN positions and UV0 through existing strict readers. First admit one ordinary texture stage with an explicit texture source, identity texture transform, proven static opacity, and the reviewed opaque/cutout material cases. Unknown replacement texture types, visibility policy, animated transforms, ENV stages, blended materials, particles, ribbons and billboard cards are unresolved sections. Do not paint unsupported transparent geometry as opaque neutral surfaces: the earlier mount defect demonstrated why that creates false buildings/armor.

The object pass shares the terrain frame target, projection/rebase origin and reversed depth attachment. It must not clear terrain depth, use the model viewer's LESS state, restart the child for camera changes or reuse orbit-camera bounds fitting. Opaque/cutout sections use explicit shared-depth state and two-sided/culling rules from the approved profile. Sky is drawn once. A software fallback must either render the same admitted objects with the same native transform or visibly state that its fallback is terrain-only; it cannot silently imply equivalent scenery.

Suggested conservative starting limits, subject to a concrete ownership ledger before implementation:

| Boundary | Initial hard cap |
| --- | --- |
| Active terrain neighborhood | existing 3×3 only |
| One obj0 source | 8 MiB, at most 4,096 MDDF and 4,096 MODF records |
| Retained rendered placements | 128, deterministic nearest-first with stable ID tie-break |
| Distinct prepared M2 assets | 16 |
| Source M2/SKIN file | 8 MiB each; one preparation at a time |
| One drawable mesh | at most 65,536 vertices, explicit ushort-safe indices |
| All prepared geometry | 131,072 vertices and 262,144 triangles total |
| Admitted draw submissions | 256 per frame |
| Texture residency | 8 images, selected mip at most 512²; aggregate 2,097,152 pixels |

Caps are global to the active scene, not multiplied by every placement. Shared immutable geometry/textures are uploaded once and referenced by placement transforms. Count and report omitted objects; capped output is partial. Cache byte accounting, CPU copies, decode scratch, upload staging, GPU residency and replacement overlap require an explicit ledger in addition to these element counts. Do not present the table as a proven total-memory ceiling yet.

## WMO buildings follow-up

Add a separate strict WMO reader in formats-assets: version/root header, group inventory and **recorded GFID LOD0** source chain first; then nested MOGP boundaries and MOVT/MOVI/MONR/MOTV/MOBA/MOPY declarations. Validate group count versus GFID/LOD dimensions, index ranges, triangle/batch spans, material references and alternate material-index flag encodings. Missing one group gives partial source evidence, not an invented sealed building.

Do not treat collision MOPY triangles as the complete visible mesh or draw every triangle merely because it exists. Rendering batches, material policy, vertex colors and indoor/outdoor lighting need their own reviewed contract. Start with an exact opaque single-texture MOMT profile only after raw samples establish shader, blend, texture reference mode and flags. No generic reinterpretation as an M2 material. Interiors/portals, WMO doodad sets, WMO liquids, animated lighting and transparent glass are explicit follow-ups. WDT global-WMO maps also need their own source/placement path; absence of ordinary terrain tiles is not an empty building map.

## Water and terrain-texture gates

Water requires a bounded MH2O reader for the exact liquid-layer/vertex-format/type profile, bitmap holes, chunk-relative offsets and heights; legacy MCLQ and WMO MLIQ are separate profiles. Reject unresolved format/type metadata. Do not infer sea level or average min/max heights when an unsupported layout lacks a decoded height stream. A later rendering contract must specify opaque-terrain depth testing, transparent ordering/depth writes, alpha, underwater behavior and what is deliberately static. A blue blanket across tile bounds is not decoded water.

Terrain textures first need the recorded MAID tex0 chain, exact texture-ID/name declarations and MCLY/MCAL profile, including the WDT/chunk flags controlling alpha layout. A proven one-layer/no-alpha profile is a reasonable first appearance subset. Multi-layer alpha, compressed alpha, edge handling and height blending require independent numeric fixtures before claiming full terrain texturing. Minimap or mapTexture imagery is not a substitute terrain material source.

## Acceptance gates

1. Authored parser binaries cover exact layouts, direct-ID flags versus name indices, unsigned values, duplicate/conflicting sources, truncation/overlap/overflow, cancellation and immutable facts. No live credentials or game assets in Git.
2. Independent affine/rotation oracles and same-camera world alignment precede native object draw code. Dedup and nearest-cap fixtures span adjacent tiles and retain stable identities.
3. Render a known-color asymmetric placed mesh against terrain with foreground/background overlap, near clipping, same-depth submission order, cutout holes and several camera positions. Expected pixels come from analytic geometry, not only CPU/GPU agreement sharing one matrix bug.
4. Delayed source/preparation tests cover neighborhood replacement, stale receipts, close/cancellation, bounded queues and terrain survival when optional objects fail. Camera-only updates retain the child and immutable uploads.
5. Private actual-source proof uses read-only originals and isolated acquired bytes, logs every source ID/key and admitted/omitted profile, and compares cold/fresh restored preparation plus CPU/actual Vulkan images. Metal/D3D hardware remains untested until actually run; offline shader compilation does not close that gate.

## Primary references inspected

These are format/implementation evidence, not code to copy into this repository:

- [wow.export WDTLoader, pinned 4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WDTLoader.js): eight MAID fields and global-WMO placement path.
- [wow.export ADTLoader, same revision](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/ADTLoader.js): MDDF/MODF records and the separate MH2O layer reader. Its permissive liquid-layout inference is not an approved Vimi profile.
- [TrinityCore ADT extractor, pinned ec72c19](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/vmap4_extractor/adtfile.cpp): direct-ID flags distinguish M2/WMO references. This collision extractor is not a visible-material oracle.
- [wow.export M2 world renderer, pinned 4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/M2Renderer.js) and [WMO world renderer](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/WMORenderer.js): distinct source swizzles/placement transforms and shared asset residency.
- [wow.export WMO loader, same revision](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WMOLoader.js): root/group separation, GFID, nested MOGP and batch/material structures.
