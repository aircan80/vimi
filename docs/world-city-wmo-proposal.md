# First visible WMO city buildings

Status: source/design checkpoint only. The narrower [one-asset BASIC_WMO_PREVIEW proposal](world-wmo-basic-preview-budget.md) can deliver a first exterior within the existing immutable world path; it does not require hot streaming or the general32MiB envelope below. No parser, renderer, protocol, GUI or application-library changes. The goal is actual textured building exteriors in the existing World Explorer, with honest partial coverage, rather than city labels or bounding-box substitutes.

## Why cities are absent and what can be reused

Current main/appearance render terrain only. MAID object references and the ADT18 MDDF/MODF parser are implemented, and `WorldPlacementSource` already reads recorded obj0 metadata with bounded acquisition, source receipts, cancellation and cache ownership. They are not wired to object drawing. There is no WMO root/group parser, building-material qualifier, building GPU resource owner, or building pass in `BgfxTerrainRenderer`.

Reuse the existing exact build/file handles, placement receipts, direct-reference flags, neighborhood deduplication rules, BLP decoder, native world camera/rebase and reversed-depth target. Reuse bgfx infrastructure and the hot-world session direction. Existing M2/SKIN readers, creature display lookup and model-preview orbit framing do not supply WMO geometry or appearance rules. The M2 placement transform research is useful reference evidence, not approval for a WMO matrix.

## Concrete selected-build evidence

Existing read-only-original plus isolated-scratch placement proofs for wowf1.60.1.70755 enUS found MODF counts8/7/12 in Eastern Kingdoms filename tiles32,32 /32,33 /33,32. Sample declarations have direct-ID flags0xC, heading-only rotations and scale1024. Their exact obj0 source receipts are documented in `world-placement-source-proposal.md`. These are recorded building candidates, not a claim that those tiles contain Stormwind or that the referenced assets have been rendered.

A new local-only read-only library probe resolved these actual declared root references:

| Root FileDataID | Declared bytes | Content key | Local body |
| --- | ---: | --- | --- |
|106973|24,224|c964848c55fa0d63185451848046703a|NEEDS_NETWORK|
|106899|10,640|afa4e6299d143f375e3f8b66531fe2a6|NEEDS_NETWORK|
|106905|712|6304edd95070891d675438415302bcbd|NEEDS_NETWORK|
|106885|1,132|d63cb692824535622ce1568573008587|NEEDS_NETWORK|
|108024|24,224|4b310a4221ebe3fe424d5a6e032fd5c7|NEEDS_NETWORK|

That initial local-only probe installed no network source, acquired no body and mutated no original store. A subsequent authorized isolated acquisition now establishes a concrete small exterior source candidate; see [the source checkpoint](world-city-wmo-source-checkpoint.md). Private bootstrap/logs are under `/tmp/vimi-world-city-private`; no source assets or screenshots are committed. Missing local bodies mean group inventory, material tuples, visible coverage and transform alignment remain **unproven**. The next proof should acquire exact recorded candidates into a new scratch store, with fresh pre/post CK validation, rather than guessing group paths.

For an actual city acceptance case, obtain the selected build's recorded city map/area bounds, enumerate overlapping existing WDT tiles using the reviewed native grid, and inspect their MODF declarations. Use Stormwind as the first target if its current-source chain is readable. Record root/group IDs and placement IDs explicitly before calling it a city proof. Do not infer an object's identity from its numeric ID, nearby minimap image or a filename-coordinate guess. A small sampled rural WMO can close parser gates but cannot close the city-coverage gate.

## Exact first source slice

Add a separate `formats-assets` WMO fact reader with independent root/group DTOs. Source reads stay in a build-scoped concepts helper borrowing `BuildFiles`; raw parsing does not resolve assets or choose visible groups.

1. Accept checked version17 chunk streams. Validate every interval, singleton duplication, count product and nesting boundary before allocation. Root facts include MOHD counts/flags/bounds, MOMT64-byte declarations, optional MOTX string-block evidence, group descriptors and GFID inventory. Reject ambiguous/truncated inventories rather than truncating them.
2. Follow only qualified recorded GFID LOD0 FileDataIDs. Pin MOHD LOD flags/count and GFID dimensional interpretation before admitting them; zero group IDs are explicit unavailable entries. No `_000.wmo` probing or arbitrary first-group selection. Legacy path references require a later bounded recorded-path profile.
3. Parse group MOGP nesting and MOVT/MOVI/MONR/MOTV/MOBA/MOPY declarations. Validate finite data, vertex/index bounds, index-count divisibility, batch spans and material references. Multiple UV/color streams are preserved or explicitly declined. Resolve alternate MOBA material-index encoding only after exact flag qualification. MOPY collision filtering is not the visible draw list.
4. Keep source facts separate from render admission. Missing/protected/changed/oversized groups leave explicit partial evidence. A valid empty root is different from a failed root. A missing group must not manufacture an empty complete building.

Source recipe keys include build/locale, obj0 CK and placement identity, root CK, each consumed group CK, all consumed texture CKs, parser/material/transform revisions and chosen group/LOD ordinals. Exact source ID/key/size checks occur before and after each read; the final batch revalidates all positive dependencies before publication. No cached result can assert freshness from an unchanged filename or root ID alone.

## Transform gate

Start with exact MODF flags0xC only after validating their two consumed meanings: direct root ID and scale enabled. Admit positive uniform scale, finite bounds and heading-only rotations; do not infer scale from a zero field or broaden to unknown flags. Preserve doodad/name-set fields without pretending those sets are rendered.

Candidate heading rule for raw local WMO vertex(x,y,z), placement(p0,p1,p2), heading h in degrees and s=scale/1024 is:

```
C = 51200.0 / 3.0
T = (C-p2, C-p0, p1)
q = T + s*(-cos(h)*x + sin(h)*y, -sin(h)*x - cos(h)*y, z)
```

This is a research hypothesis obtained by tracing the primary renderer's WMO vertex swizzle into Vimi native(X north,Y west,Z altitude), not approved implementation. Validate it independently against a second WMO-specific chain. Author asymmetric cardinal/noncommuting rotation, scale, normal and winding oracles; full pitch/roll stays out until disagreements are resolved. Promote original floats to double, subtract the common scene origin in double before GPU floats, and never snap geometry to terrain. Compare real placed root/group bounds and mesh against actual ADT triangles at several asymmetric cameras. Record float residuals instead of hiding them with offsets.

## First material/exterior profile

Target ordinary static diffuse shader0, opaque blend0, one qualified texture stage and UV0. This is a proposed narrow admission set, **not** a claim sampled roots contain it. Confirm actual MOMT flags, texture-ID versus MOTX-offset semantics, sampler/wrap, color/tint, normals and group lighting on real root/group/texture tuples before enabling it. Unknown extra material operands or flags decline the batch. Cutout can follow after an independently pinned alpha test/profile; glass, blended windows, layered/ENV/specular shaders, animated effects and liquids are separate increments.

Reuse BLP decoding under explicit source/dimension/pixel limits and recorded texture IDs, not terrain atlases or M2 material enums. Bind decoded images to the material source receipt. Name the initial shading BASIC_EXTERIOR: correct source geometry/UV/diffuse with a documented simple light/fog recipe, not client lighting fidelity. Vertex-color alpha/ambient processing can change visibility; it requires a qualified rule or a rejected group, not an ignored color stream that silently changes the building.

Admit only positively identified exterior groups under reviewed flags. Keep unresolved indoor/exterior-lit combinations as unsupported evidence; do not draw every group as exterior. Group omissions produce per-building partial counts. Interiors, portal traversal, WMO doodad sets/furniture, embedded M2, local lights/fog volumes, collision and WMO liquids are excluded. Walking inside has no collision guarantee. WDT global-WMO-only maps require a separate placement route and are not enabled by this terrain-neighborhood slice.

## Loading, culling and ownership

One latest-neighborhood coordinator owns one active batch plus one replaceable metadata-only pending request. Stream obj0 sources; deduplicate build/locale/map/kind/unsigned-unique-ID and compare complete raw records. Identical copies share one placement with contributing receipts; conflicts omit the identity. Root/group/texture sharing is separate from placement identity. Shared geometry is uploaded once per content recipe, never expanded per placement.

Use checked transformed group bounds for frustum/distance culling; source MODF bounds are conservative descriptive evidence, not exact visible geometry or collision. Never cull a huge city root solely by its anchor or owning ADT tile. Retain/deduplicate placements spanning neighboring tiles. Use group bounds, stable distance/ID ordering and hysteresis for bounded admission, and expose omitted counts. Keep the existing distance setting unchanged initially. Camera-only changes update transforms/culling, without source acquisition or whole-scene rebuilds.

Integrate into the same persistent world child and terrain depth target. No per-building preview child, context or framebuffer clear. Opaque groups share reversed GREATER depth/write policy; winding/cull state is explicit per admitted transform/material. The native owner thread performs bounded uploads/destruction; no files/BLP parsing occur there or on FX. Terrain stays visible while buildings prepare; activity distinguishes placement discovery, group loading, texture preparation and GPU upload. A settled partial view shows missing/unsupported counts, not an endless spinner. Cancellation/replacement discards stale generations and drains owned work before freeing borrowed data.

The current v2 terrain packet/slot protocol does not carry arbitrary objects. Do not squeeze buildings into its nine-tile material fields or reuse tile slot generations as asset identities. Propose a separately reviewed typed world-object annex or next profile: scope+session nonce, asset recipe digest, child-granted asset slot+generation/credit, bounded geometry/material packet, and a metadata-only instance draw table. Offers consume ownership on every outcome, upload completion precedes commit, and removals retire only after frame/GPU barriers. Asset residency survives camera-only and terrain-tile commits; scope/map changes drain it. Parent source-qualified factories establish positive claims; wire checksums do not authenticate game sources.

## Initial limits and combined-budget gate

Proposed starting caps: existing3×3 source neighborhood,32 visible placements,8 distinct roots,64 admitted group draw batches,65,536 unique vertices and131,072 unique triangles globally; maximum1,048,576 instanced triangles/frame; at most eight texture handles and1,048,576pixels globally (four512²-equivalent images), maximum side512; this preserves the six-image small candidate described in the source checkpoint. Per-source root/group byte cap8MiB, at most256 root groups and256 materials, one group source/decode/upload at a time. These are proposals to test against a real city, not approved coverage or memory guarantees. A city exceeding them remains visibly partial; do not call it complete.

A plausible additional child payload envelope is32MiB, including CPU mesh arrays, GPU buffers, bgfx queued copies, one upload staging owner, CPU/GPU/queued texture copies, codec temporaries, instance/material tables and shader reserve. Name and calculate every representation before renderer approval. The existing textured-terrain ledger120,844,580bytes plus32MiB is154,399,012bytes, which **exceeds128MiB**. A combined160MiB logical ceiling would leave13,373,148bytes for remaining named owners, but is only a proposal requiring review. Hot-world ten-slot/resident-replacement overlap must be recalculated from its actual latest ledger; this older terrain baseline does not automatically approve the hot+building combination. No silent budget increase or hidden reuse of terrain texture slots.

The parent needs its own bounded preparation ledger covering old/current/new asset owners, root/group raw bytes, decoded facts, texture pixels, source/provenance/dedup metadata, disk packets and retired-but-not-drained requests. Initial planning allowance64MiB is separate from existing terrain/helper budgets; quantify combined peaks and lower subsystem caches before implementation. Texture leases and candidate owners release exactly once on success, refusal, stale scope, cancellation, closed view and child failure. Old/new GPU coexistence is charged until the actual retirement fence, not merely until a new draw table is desired.

## Deliverable gates

1. **Root/group source qualification:** authored malformed/overflow/duplicate/nested/ID/material-span fixtures; exact city placements and real root/GFID/group/material inventory; immutable bounded facts and cancellation/source-freshness proof. This is a prerequisite checkpoint, not a visible-building release.
2. **Visible exterior vertical slice:** independent WMO transform/alignment, one admitted real textured exterior, complete combined memory ledger, shared terrain depth/native-thread ownership, actual Vulkan analytic foreground/background/winding/UV/culling proof. Compile other backends; Metal/D3D hardware remains untested until run.
3. **Moving city view:** bounded hot-world asset/instance protocol, stale-scope/ABA/cancel/close/fence/queue refusal tests, constant child identity during motion, no repeated geometry upload on camera-only updates, and honest source/material/loading/partial UI. Real city cold/warm travel and resident counts/latency measurements close this release gate.
4. **Coverage follow-ups:** additional material/cutout profiles and larger supported exterior coverage first; then interiors/portals/doodad sets/global-WMO maps. Water and greater terrain distance remain separate reviewed tracks.

The first visible release may combine gates2and3 when independently ready. Metadata-only progress must not be described as rendered cities.

## Primary evidence

Pinned [wow.export WMO loader4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WMOLoader.js) separates roots/groups and records the geometry/material structures. Its permissive filename fallback is not adopted.

Pinned [world WMO renderer4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/WMORenderer.js) provides transform, exterior/lighting and residency research; [its shader mapper](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/WMOShaderMapper.js) distinguishes diffuse from multi-stage materials. These are research references, not code copied into Vimi.

Pinned [TrinityCore WMO extractor ec72c19](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/vmap4_extractor/wmo.cpp) independently records GFID LOD0 traversal and scale-enable handling; its collision output is not a visible-render oracle.
