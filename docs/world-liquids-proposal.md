# Source-first static terrain liquids

Status: source-backed pre-code proposal. Textured terrain has priority. This document requests review of a raw metadata/parser/API checkpoint; rendering, shared-resource integration and visual policy require separate approval before implementation. No production source, game assets, account state or original-library bytes changed during this investigation.

## First deliverable and exclusions

Preserve bounded raw MH2O declarations from an otherwise validated ADT18 root. Resolve only a positively qualified **height+depth vertex format0** through exact prepared LiquidType/LiquidMaterial profiles, producing typed local liquid geometry facts. Missing/unsupported liquid metadata must leave terrain readable with counts and reasons. A valid absent MH2O is distinct from malformed, protected or unresolved liquid metadata.

A later first visible increment can show decoded static water surfaces in the existing inline terrain target, with an explicitly reviewed diagnostic diffuse/transparency policy. It must not claim PBR water, waves, refraction, underwater fog, swimming, flow animation or foam. Legacy MCLQ, WMO MLIQ and other MH2O vertex formats remain separate profiles. A rectangle at sea level or average min/max height is not decoded water.

## Primary source contract

Pinned TrinityCore `ec72c1914da121c07620dd5fe8ad084458dad9d1` provides independently inspectable record and resolver evidence:

- [adt.h liquid structs](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/adt.h#L129-L180): 256 twelve-byte headers; each instance24bytes, with raw u16 liquid type and secondary field, two float height bounds, four byte offsets/dimensions, bitmap offset and vertex offset. Offsets are relative to the MH2O **payload**, not the outer ADT or chunk tag.
- [GetLiquidVertexFormat](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/System.cpp#L402-L417): secondary<42 is a direct format selector. For secondary≥42, the raw LiquidType selects LiquidType.MaterialID → LiquidMaterial.LVF; raw type2 has a separate depth-only special case. Unsupported relations must remain unresolved, not default to0.
- [Instance dimensions](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/adt.h#L138-L153): secondary≥42 uses offset0,0 and dimensions8,8 independently of the raw dimension bytes. Preserve both raw and effective facts.
- [Height/depth readers](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/adt.h#L201-L237): format0 stores `(width+1)*(height+1)` float heights followed by the same count of depth bytes. No per-vertex interleaving or min/max interpolation.
- [Bitmap/grid traversal](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/System.cpp#L603-L659): least-significant bitmap bit advances across X cells, then Y. The extractor's map array axes are not a ready-made Vimi native coordinate transform.

The secondary field is described as a LiquidObject ID for≥42, but it is **not** the index used by the pinned LVF resolver. LiquidObject can supply additional source facts; absence of that row does not authorize guessing its semantics or prevent an otherwise proven LiquidType→Material geometry decode. The exact42 case below makes that distinction material. Collision extraction does not establish water rendering, color or transparency semantics.

## Actual Forever70755 source evidence

Existing private roots were read directly, with no acquisition. Root777947 has no MH2O. Root777952 contains12076bytes of MH2O and20instances; root778147 contains14834bytes and26instances. All sampled instances have a present vertex stream and secondary≥42, hence effective8×8 cells. These are source declarations, not a guess from their equal bounds.

| Root | Raw type/secondary | Instances | Bitmap-present cells | Used vertex heights |
| --- | --- | ---: | ---: | --- |
| 777952 | 1240/5974 | 4 | 89 | 14.668346405029297 |
| 777952 | 1250/42 | 16 | 730 | 0 |
| 778147 | 1240/5975 | 13 | 343 | 54.335811614990234 |
| 778147 | 1288/5976 | 13 | 339 | 29.601730346679688..35.08052444458008 |

Some masked-out vertex slots contain0 even when declared bounds and all vertices actually used by present cells have a different constant height. Preserve finite raw heights, but apply bound-consistency checks to vertices used by emitted cells; do not overwrite unused values with the bounds or reject valid masked padding merely because it is below minHeight.

Existing prepared tables, original and private sources opened read-only:

| Table | FileDataID/layout | Readable/declared | Content key |
| --- | --- | --- | --- |
| LiquidType | 1371380/D1ECEEC9 | 57/57 | d784f88dd10590fd4c0f86002537d8ac |
| LiquidMaterial | 1132538/98E5D7AA | 5/5 | 1309378564e73a7c2a78af8745d2c44d |
| LiquidObject | 1308058/CB0D39E8 | 1270/1270 | 204fa97eb11ecedf54d0aca83ad12823 |

Types1240/1250/1288 are named Generic Lake/Ocean/River; all have SoundBank0 and MaterialID130. Material130 has Flags1 and LVF0. Objects5974/5975 reference1240, object5976 references1288; their flow direction/speed are0. **Object42 is absent**, while raw type1250 still positively resolves material130/LVF0. The proof does not depend on a fabricated object42 or an ocean sea-level default. Names and PBR parameter arrays are not interpreted as a rendering profile.

The LiquidObject logical LiquidTypeID carries a narrowing annotation; any use beyond retained raw metadata needs exact logical-width/relationship qualification. The geometry dependency is the independently qualified LiquidType.MaterialID and LiquidMaterial.LVF, not permissive table-name lookup. Unknown/protected row channels or unverified fields cannot certify this relationship.

Private evidence is in `/tmp/vimi-hd-sd-audit/liquid-tables-70755.log`, `liquid-objects-70755.log` and existing `/tmp/vimi-terrain-texture-proof/root-*.adt`; no source assets enter Git.

## Proposed raw/parser API checkpoint

Use a separate formats-world type, provisionally `WorldLiquids`, coordinated with the shared checked ADT walker and texture-grid metadata work. Do not extend `TerrainScene.Tile` to pretend liquid instances are terrain tiles, and do not introduce a competing root-grid API.

Proposed signatures for review:

```java
RawLiquids readMh2o(byte[] validatedRootBytes, BooleanSupplier canceled);
DecodedLayer decodeHeightDepth0(RawLiquids owner, int layerOrdinal,
                               BooleanSupplier canceled);
```

`RawLiquids` distinguishes ABSENT and PRESENT (including a present empty header table). Malformed structure throws a typed format error, not an empty list. It owns **one private immutable copy of the MH2O payload**, at most2MiB, never the whole root and never one payload copy per layer. Inspect first validates the source chunk envelope and all fixed header/instance metadata before that one copy. RawLayer values are immutable bounded descriptors into that owner, identified by header and instance ordinal; they do not own or expose mutable source storage. The descriptor retains both raw u16 fields, raw bounds/rectangle, bitmap/vertex offsets and header attribute facts. Unknown-format vertex lengths remain unknown; inspection does not copy a guessed tail or assume405bytes merely because another layer uses format0.

The same authenticated root read produces the RawLiquids owner. It retains source identity through the concepts owner's private request/receipt context. Decode receives the owner and a checked ordinal, not a freely transplantable descriptor plus unrelated bytes. The decoder recomputes the exact known format0 rectangle/vertex/bitmap ranges against that owner's immutable metadata and bytes before copying detached heights/depth. Header attributes are parsed **once per header** and shared by its layers; their legitimate reuse is not an overlapping-payload error. Exact known geometry payload overlap/alias validation is a separate decode admission check. Unresolved-format intervals cannot certify nonoverlap by guessing an end.

`decodeHeightDepth0` is explicitly a **low-level layout reader**, not a source-qualification capability. It can parse layout0 when asked; it does not assert that this source's secondary field authorizes that layout. There is no public receipt factory or formats→concepts dependency. The concepts owner alone qualifies LiquidType/Material field profiles, row issue channels and source receipts, determines that the current raw layer resolves LVF0, and only then calls this layout reader. It never publishes a parse-only result as qualified geometry. Source/table failure yields typed unresolved coverage. The concepts owner releases the single raw-payload owner after bounded decoding; only detached admitted geometry/metadata can enter its cache or scene.

DecodedLayer retains effective rectangle, exact present-cell mask, original row-major height/depth arrays and source ordinals; readers return immutable views. Parser types do not know model materials, UI state or GPU resources. The concrete concepts wrapper/resolver signatures remain a separate implementation checkpoint for independent review.

Initial hard bounds: existing root source limit unchanged; MH2O≤2MiB; exactly256headers; ≤4instances/header and≤1024instances/root; dimensions1..8 with offsets+extent≤8 for direct format0; full8×8 for qualified≥42 form;≤81vertices and64cells/layer. Cap the single detached raw MH2O payload to2MiB/root before copying; include that owner plus detached decode overlap in the later ledger. Unknown formats are retained as bounded unsupported metadata without guessing their payload lengths. No whole-root clone in a raw result.

Validate all header and instance products before reading; duplicate MH2O/version declarations, truncated arrays, offsets into headers/instance tables, invalid interval products and out-of-range attributes/bitmaps/known-format payloads are malformed. Exact known-format payload lengths derive from dimensions and format, not from whichever unrelated offset happens to follow. Shared aliases must be diagnosed unless an independently reviewed identical-slice rule is added; arbitrary overlaps are not accepted. Zero bitmap offset follows the pinned all-present rule only after exact source-profile qualification. Zero vertex offset remains unsupported for this first geometry profile, even if min=max. Unknown secondary<42 formats, raw type2, unresolved MaterialID/LVF and positive unknown LVF are explicitly unsupported.

All float bounds and retained heights must be finite and within existing world coordinate policy; min≤max. Emit only cells whose bitmap bits are set and whose referenced heights pass the reviewed bounds rule. Do not intersect liquid existence with terrain holes: water and terrain holes are independent source masks. Source ordinal, effective XY rectangle and the validated root chunk anchor determine geometry; no filename/screen-axis transposition.

## Service and scene ownership follow-up

Optional liquid failure cannot remove terrain. A future service returns READY/PARTIAL/UNAVAILABLE with per-layer counts/reasons, exact root/table receipts and source generation, distinguishing no liquid from unreadable liquid. Recheck receipts before cache publication and bind reader/resolver profile revisions. Borrow files/tables and close only owned streams/workers. One active plus one latest pending request/preparation; cancellation callbacks must not block FX or discard an uncanceled queued target.

For the first visible scene, propose an aggregate cap128admitted layers,8192cells,10368vertices and16384triangles across the existing nine source tiles. Select nearest first with stable tile/chunk/instance ties, reporting capped farther coverage. More raw source layers may exist; never multiply these caps per tile. Overlapping present cells between different layers need a reviewed multilayer policy; initially decline such cells/layers rather than arbitrarily select one.

Geometry construction must use validated native MCNK anchors and explicitly verified row/column mapping. The raw liquid grid X follows the source chunk's X axis, not necessarily nativeX; an asymmetric two-neighbor/one-cell fixture and an actual overlay proof must close this gate. A quad requires an explicit diagonal convention; pin it from a second primary renderer and test nonplanar heights before a render implementation. The supplied river heights make this more than a cosmetic question.

## Candidate static render policy — separate approval required

An initial honest preview can use a documented constant diagnostic teal and alpha, existing terrain fog, and the exact decoded water surface. Do not claim that LiquidType.Color/Float arrays or MinimapStaticCol establish client PBR appearance. Choosing the color/alpha and enabling default visibility are product/render decisions still open for review.

**Decision (#171, PR #586):** water keeps the diagnostic teal. Magma, fel, tar and the other non-water liquids (vertex formats 1 and 3) are drawn in their LiquidType's `MinimapStaticCol` as a diagnostic flat colour, so they show where they are instead of being left out. That colour is not a claim of the client's appearance. LiquidType's `SoundBank` does not separate ocean from water on 1.60.1 (both 0) and is not read.

Keep the same native camera, rebasing, reversed D32F attachment, near clipping and far policy. Render opaque terrain/objects before water. A possible bounded nearest-surface strategy uses a color-disabled water-depth prepass into the existing depth attachment, then an EQUAL-depth alpha-composition pass with depth writes disabled. This avoids a new full-size render target and avoids pretending centroid sorting is correct for arbitrary transparent overlaps. It deliberately renders only the nearest admitted water surface; multiple-water-layer optics and refraction remain unsupported. Shared-edge ownership, coincident-water ties and CPU/GPU depth precision must be independently tested. No model-viewer LESS state, clear-depth between passes or blue blanket is allowed.

This strategy changes final depth contents and must be reviewed with future object/transparency passes before code. Its correctness, output-alpha rules and ordering are not approved merely by the source metadata proposal. Software must implement the same supported policy or clearly report terrain-only fallback.

## Resource ledger gate and acceptance

At the proposed scene caps, position/index payload is at most321024bytes, GPU48-byte triangle instances786432bytes. Immutable geometry, native upload-copy lifetime and GPU instances must each be charged separately; descriptors/source facts need a fixed envelope. A provisional liquid-native delta≤3MiB appears compatible with the texture design's115.25MiB logical child ceiling, but the actual combined allocation ledger must be reviewed before integration. Do not claim a128MiB measured process ceiling.

Parent parsing must not reread/clone nine32MiB roots concurrently. Prefer optional liquid extraction while an existing bounded root worker owns authenticated bytes, then detach and release only admitted liquid payloads. A new independent root-read worker would need an explicit source/copy/cache/replacement budget; it is not authorized by this document. Root bytes, table pages, decoded geometry, immutable-cache copies, scene serialization, old+pending snapshots and any CPU depth scratch all belong in that ledger.

Required authored gates:

1. ADT18/MH2O header/24byte records, payload-relative offsets, exact format0 planar heights+depth, bitmap bit order, absent versus malformed/protected, finite/extent/product/duplicate/overlap/count/cancellation negatives.
2. Direct format0 versus≥42 resolution; exact LiquidType→Material widths/profile/receipt/issue-channel guards; Object42 absent still resolves geometry; missing material/unknown LVF/zero vertex data stays unavailable. No min/max-height fill.
3. Source axis and chunk ordinal oracles, masked unused zero heights, shoreline holes, all-clear bitmap, adjacent tile continuity and nonplanar quad diagonal. Preserve duplicate-layer identity and diagnose overlaps.
4. Analytic depth/alpha/near/far/fog tests with foreground terrain, crossing shorelines, shared edges and nearest-water surfaces; CPU plus actual native Vulkan proof, not only renderer agreement. Other backends remain unexecuted until tested.
5. Bounded nine-tile scene, optional-source failure, cancellation/late-source retirement/cache restore and geometry survival; measured clone/upload lifetime and first-surface latency.

No production parser, geometry, scene format, shader or GUI is included in this checkpoint.
