# Global WMO instance source, coordinates and BASIC interior proposal

Status: design/source diagnostics only. No source scope, parser, packet, renderer, shader, GUI or budget is approved/modified by this document. The separate exterior source a22b82d/01ddd9e and cold structural codec f6ce9c2 keep their existing profiles/caps. Onyxia is the first actual global-instance candidate; a successfully read raw body or unreviewed partial-Map witness is not a positive source/renderable capability.


Followup design/evidence: see [global opaque RGB proposal](world-global-opaque-rgb-profile-proposal.md). Actual image bodies now establish17BC1 plus one meaningfully nonopaque BC3 body, so this document's original strict-BC1 candidate remains refused. The separately named followup proposes deliberate alpha omission under actual opaque MOMT operands, an unlit20-byte vertex layout and phased budgets with headroom. Historical stat-only/witness-pending and32-byte zero-headroom notes below describe this original checkpoint; they are superseded only where the followup explicitly says so, never silently relaxed in a production profile.

## Actual selected-build evidence and its limits

Selected wowf1.60.1.70755/enUS readable Map249 records Onyxia's Lair/OnyxiaLairInstance/type2 and WDT789922. The saved Map capture has73observed of81declared rows,3encrypted excluded sections/8records. This establishes the specific observed row; it does not establish complete instance discovery or absence of an unobserved ID. The proposed private observed-row native witness is being reviewed separately. No scope is promoted by this investigation.

WDT789922 is163980bytes, CKf0f56c70a2d122b12e925530e58a406c, MPHD0x241, zero active MAIN tiles and zero positive MAID terrain/root/obj0/minimap references. Its one actual MODF record has direct root109474, unsigned UID0xffffffff, flags8, position/rotation all0, raw scale1024, sets0. This is **GLOBAL_WMO_ONLY**, not a synthetic tile(0,0). Flags8 do not enable scale. Root109474 is6408bytes, CK7d98c803c26039b616c1d33910380099, WMO17/flags5/noLOD, eight exact GFID groups109475..109482; the complete root+groups total1327017bytes. No root/group filename guess is needed.

The eight raw groups contain23076vertices/31219triangles/59MOBA batches, one UV stream each; seven have MOCV, three have MLIQ. Materials19 reference18distinct texture1 IDs; all observed flags/shader/blend are0 and extra texture stages0. All18 current file-stat declarations are≤44876bytes (one23044), but they are NOT_STORED in the read-only diagnostic sources and their actual BLP encodings/mips/pixels remain unqualified. Do not infer BC1/base dimensions from length. Read-only metadata availability is not downloaded image availability.

New readonly diagnostics establish that batch spans are disjoint but do not partition MOVI in six groups:

| Group / FDID | Vertices | Raw triangles | MOBA batches | Unbatched indices | MOCV | MLIQ |
| --- | ---: | ---: | ---: | ---: | --- | --- |
|0 /109475|793|860|6|0|no|no|
|1 /109476|3313|5574|12|2625|yes|yes|
|2 /109477|2636|4397|9|1989|yes|yes|
|3 /109478|311|482|6|99|yes|no|
|4 /109479|2061|3227|6|966|yes|no|
|5 /109480|1913|3370|7|1467|yes|yes|
|6 /109481|108|129|3|0|yes|no|
|7 /109482|11941|13180|10|1971|yes|no|

Total9117unbatched indices=3039raw triangles; the actual recorded batches cover28180triangles. The exterior's complete-index-partition admission must not be reused. Render only exact declared MOBA spans with their actual material, preserving index order; preserve/report unbatched inventory without guessing it is collision, visible or assigned a fallback material. Overlapping batch spans or invalid ranges still refuse.

Raw MOCV BGRA channels are not straight opacity: group6 has108/108alpha0 vertices; in groups1/2/5/7 alpha0 counts are2388/1706/1235/6209 respectively and none has alpha255. Group3 has17alpha255 vertices. Raw RGB is often dim and nonuniform. Multiplying opaque diffuse by raw RGBA would erase/darken surfaces incorrectly. These are observed bytes, not a complete client-lighting interpretation.

MOHD raw counts are[19materials,8groups,7portals,14lights,25model-name declarations,208doodad declarations,1set]. Actual MOLT is672bytes, MODD3040bytes, MODI172bytes. A40-byte MODD interpretation yields76physical records and MODI yields43IDs, which do not simply equal the respective header declarations. Preserve this discrepancy/semantic uncertainty; no complete embedded-model inventory or NPC/door support follows. Raw root ambient is4279371779. Exact source receipts, all group/header fields, three asymmetric source points/normals per group, batch coverage and color statistics are in private `/tmp/vimi-world-global-primary/onyxia-global-diagnostic.log`. The original library and previously acquired scratch store were both opened READONLY with a local-only source; this followup performed no CDN acquisition or store write. No assets are in Git.

## Coordinate proposal: zero-placement native frame, not ADT translation

Propose `GLOBAL_ZERO_NATIVE_V1`, limited to one actual direct global MODF, flags exactly8, sets0, raw position/rotation exactly zero (either signed zero), raw scale1024 preserved but disabled by its flag. Refuse other transforms until separately proved. No source ID participates in this policy.

The primary chain gives a concrete frame rather than fitting a camera: Trinity's WMO extractor reads/writes MOVT coordinates unchanged; its global placement adds the shared horizontal base to the fixed-axis MODF position. Its model instance uses an identity rotation at recorded zero angles and scale1 when flag0x4 is absent. [Extractor](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/vmap4_extractor/wmo.cpp), [axis conversion](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/vmap4_extractor/wmo.h), [instance transform](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/common/Collision/Models/ModelInstance.cpp).

Trinity's native query conversion subtracts each internal horizontal coordinate from the same base and preserves height. Combining those two actual operations, rather than applying ADT placement blindly, yields the proposed global-native mapping below. [Native query conversion](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/common/Collision/Management/VMapManager.cpp).

```
raw WMO local v=(x,y,z)
internal global = (C,C,0) + v
native = (C-internal.x,C-internal.y,internal.z)
GLOBAL_ZERO_NATIVE_V1(v) = (-x,-y,z)
normal = (-nx,-ny,nz)
```

The two horizontal sign flips are a proper rotation (determinant+1), so preserve winding. Eliminate the equal base translations algebraically: promote source float components to double and negate X/Y directly, with no C-offset, extra permutation, snap, Euler fallback or float round trip through17066.666…. The resulting matrix is D=diag(-1,-1,+1), translation0, scale1. The raw bbox permutation(y,z,x) was only a source-format bound comparison; it is NOT this matrix.

The independent viewer provides an orientation check, with an important origin distinction. wow.export's loader swizzles raw vertices for its Y-up renderer; its global producer passes the raw placement unchanged and its global draw/camera use the same horizontally shifted display frame. Removing that *explicit display-origin translation* produces the same D orientation. It does not independently establish absolute WoW native position; do not silently claim its global display coordinates equal native map coordinates. [Raw producer](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/modules/tab_maps.js), [loader](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WMOLoader.js), [global draw matrix](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/WMORenderer.js), [camera display frame](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/MapViewerScreen.js).

Actual source corroboration: root-local bounds(-63.245945,-26.111956,-96.47517)..(218.83295,286.4644,49.59112); raw WDT bounds are exactly their(y,z,x) permutation. Applying extractor bound conversion plus global offset and native conversion gives proposed native bounds(-218.83295,-286.4644,-96.47517)..(63.245945,26.111956,49.59112). Bounds alone do not prove rotation; the independent operation trace and asymmetric point/normal tests are required together.

Example actual group0 vertex0 raw(-12.912879,12.712559,28.187777) maps to proposed native(12.912879,-12.712559,28.187777); vertex396 raw(1.776808,17.89688,10.596087) maps to(-1.776808,-17.89688,10.596087). Its source normal(-0.18179601,-0.97722435,-0.10946596) maps to(0.18179601,0.97722435,-0.10946596). No origin-fitting or axis-guess makes these expected values. Authored asymmetric triangles must test determinant/winding, inverse mapping and all axis signs; actual group points must remain within derived group/root native bounds. A reference emulating Trinity's intermediate FLOAT base addition may differ by roughly its base-coordinate ULP; document that comparison tolerance rather than claiming bit identity with its float round trip. Vimi's canonical direct sign-flip policy itself is exact for finite raw float operands. This is a proposal pending independent transform approval and actual same-camera native evidence.

## Separate global admission: never a fake terrain tile

Add an optional source-scene inventory based on the actual WDT, not Map.InstanceType: TERRAIN_ONLY, GLOBAL_WMO_ONLY, MIXED and unresolved/partial evidence. InstanceType remains descriptive. Terrain-present instance maps33/36/47 are counterexamples to choosing format from dungeon/raid type. Global-only can coexist with an empty terrain selection without being mislabeled unavailable terrain.

Proposed package-internal source API, preserving current constructors/custom providers and existing tile operations:

```
record GlobalSelection(long mapId, int modfOrdinal, long expectedUnsignedUid) {}
<R extends AutoCloseable> R withRecordedGlobal(
    Scope scope, GlobalSelection comparisonOnly,
    GlobalProfile.BASIC_INTERIOR_DIFFUSE_V1, Cancellation external,
    GlobalSourceUse<R> use);
```

`GlobalAdmission` has a private constructor and is distinct from TileAdmission. Its private source proof includes the native observed Map row/stamp/ID-domain witness when required, exact consumed WDT CK/length/hash, source scene kind, actual MODF ordinal/UID/raw64bytes and direct root reference. Selection supplies no root, group, texture, placement matrix or positive boolean. First profile requires recorded GLOBAL_WMO_ONLY, one exact MODF, no positive terrain references; mixed cases remain unsupported here. Read/parse actual WDT under the same current/fresh identity contract, compare the selection and reject stale/duplicate/conflicting UID or reordered declaration. Unknown Map IDs remain NOT_OBSERVED when capture completeness is limited; never fabricate absence from73/81.

Callback derives root only from that private actual declaration; root's exact GFID inventory yields group IDs, qualified actual MOMT.texture1 yields images. No guessed paths/filename roots, alternative model displays, obj0 reads or fake MAID. Root/GFID/no-MOTX/material domain needs its own interior shape qualification; source-neutral decoded fields never mint this admission. Native witness work currently unreviewed is DIAGNOSTICS only until separately approved. No automatic table promotion/repair/preparation inside this facade.

One active source operation spans callback and final checks; one bounded metadata-only replacement is optional later. All source IO/cancellation callbacks run off FX and outside metadata locks. Reserve one live raw input before open/allocation, at most1MiB, serial through retirement. Profile-proposed caps: WDT1MiB/root64KiB/group1MiB/image64KiB, ≤8groups/24materials/18distinct images/28consumed bindings (WDT+root+8groups+18images). These are a NEW reviewed profile proposal; do not widen BASIC_WMO's1KiB/32KiB/6image/11binding limits. Declared-null lengths remain unknown; actual complete/no-hole bounded streams, CK/FDID/consumed length and final fresh Map/WDT/ALL-binding identity must still be checked.

Every intermediate and partial unadvertised output belongs to local try/finally before R exists; cancellation or callback failure closes it. Engine owns R through final validation and closes it on stale generation/source. Output callback writes only private scratch inside that guard. UI/child publication follows successful final transfer and latest-view match; current old child drains before successor GPU allocation. No library/settings/account write. A later public optional API has explicit UNSUPPORTED default for custom providers, never a new required abstract method.

## BASIC_INTERIOR_DIFFUSE_V1: useful, explicit approximation

Propose the first global recipe as **opaque unlit diffuse preview**, I=1, source UV0/repeat/bilinear/one selected mip/no sRGB correction, alpha1, two-sided recorded batch draws. It is not the exterior's directional-light recipe and not client-lighting parity. All19materials must satisfy the exact opaque one-texture profile; all18images must be read/decoded/qualified before a complete textured candidate is admitted. Initially test strict BC1/alphaDepth0/alphaEncoding0/mipFlags1/mip0/base side≤256 and aggregate≤1179648pixels. These proposed image facts are NOT yet established from bodies. If any actual image falls outside this profile, decline with its reason; do not invent neutral pixels or silently relax the image cap. Explicit diagnostic geometry-only output can be a separately labeled opt-in later.

Validate raw MOCV declaration count/length against actual vertices and preserve its digest/statistics/coverage, but deliberately do NOT apply its RGB or alpha, ambient subtraction, local-light interpolation or interior/exterior blend. Do not confuse zero MOCV alpha with transparent geometry. Another viewer's color preprocessing depends on header/group flags and batch partitions, confirming raw bytes need a named interpretation; it does not make a generic raw-color multiplication correct. [Primary color-processing implementation](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/WMORenderer.js). A future source-backed interior-lighting recipe is separately versioned; this useful texture preview need not wait for full parity.

Coverage is per feature, not one misleading supported flag:

| Feature | First proposed behavior |
| --- | --- |
|Eight recorded group meshes|Admit all actual groups after bounds/indices/UV/normal/range checks|
|59MOBA commands|Draw exact spans; report28180batch triangles separately from31219raw triangles|
|Unbatched indices|Retain inventory, do not draw or guess collision/material semantics|
|Opaque diffuse textures|Require every recorded material/image in bounded profile; explicit loading until complete|
|MOCV7groups / root ambient /14local lights / MOMT lighting colors|Validated source declarations, intentionally ignored by named unlit preview|
|MLIQ in3groups|Recorded and explicitly omitted; no blanket blue surface or inferred water height|
|Embedded MODD/MODI/model declarations|Unsupported/omitted with raw counts and mismatch diagnostic; no invented full doodad count|
|Portal records|Retained facts; conservative all-group draw, no guessed portal/room/floor culling|
|Other shader/alpha/multiple UV/color streams or unknown render-affecting chunks|Explicit unsupported profile, not silently rendered neutral|

No group flags are assumed exterior. First profile enumerates observed allowed group flag shapes0xA09/0x3805/0x2A05/0x2005/0x2805, one UV, ≤1MOCV stream, optional bounded MLIQ ignored as above; unknown features decline. WMO17/root flags5/noLOD/eight-or-fewer exact GFIDs/noMOTX and matching geometry/material counts are separately checked. Do not require ignored doodad header/body declarations to agree and then falsely reject the core static shell; carry the discrepancy as unsupported scenery evidence. Conversely, an inconsistent consumed draw/geometry/material count is a blocker.

## Scene/camera/presentation API

Global-only needs a real new scene mode, e.g. `WorldScene.GlobalWmoFrame(origin,bounds,sourceFrameFingerprint,groups,images,coverage)`, distinct from `WorldScene.TerrainFrame`. It has **zero** terrain tiles/slots/atlases, not an empty/fake TerrainScene. Reuse common free-flight camera math and the existing arbiter-owned World child/frame target; selecting an instance replaces the old world mode after actual drain, never starts a model-orbit camera/window/context.

The exterior f6annex requires an actual terrain base file/selected terrain fingerprint and cannot represent a global-only scene. Do not abuse its flags/magic or create a fake base file. A future `GpuGlobalWmoSceneFile`/typed launch profile must bind its own actual source/global-frame/geometry fingerprint with streamed checksum/EOF and describe GLOBAL_ZERO_NATIVE_V1 explicitly. Neutral packet/DTO still never establishes game-source authority. This separate codec/CLI/native integration needs API review before code; current terrain/model modes remain unchanged.

Choose origin from the actual transformed admitted geometry bounds in double, then rebase positions before float uniform casts. Source declaration translation remains0; choosing a rendering origin does not change world coordinates. Camera reset is explicitly **Frame geometry**, derived from admitted bounds; it is not a discovered dungeon entrance, walkable spawn or floor. Use source-backed starts only after a separate qualified table/source join. Reuse flight controls/near0.5/shared GREATER depth. Initially draw all≤8groups with a neutral dark background and no outdoor blue fog; keep a conservative camera-space distance policy separately reviewed so useful interior geometry does not disappear on movement. No collision/portal optimization is needed for this bounded first preview.

Loading must remain visible through Map qualification→WDT→root→groups8→textures18→final source checks→GPU upload→accepted first frame. Mesh appearance is not completion while textures are pending. Show the feature omissions/approximation and loading/error states in the viewport, with off-FX source/parser/decode/upload scheduling. Same-camera movement/resize does not restart source acquisition or spawn a new child.

Existing ADT area/subzone/minimap queries are unavailable for this mode; do not apply their rectangles or invent a MAID minimap tile. Earlier UiMap investigation had no positive Map249 join/floor evidence. WMO groups/height ordering are not dungeon floors. Future WMOAreaTable/volume or qualified floor/minimap artwork paths need their own evidence. A clearly labeled geometry overview is possible later, not disguised client minimap art or quest coordinates.

## Provisional separate ownership ledger, not an admission grant

Shape proposal: ≤8groups,≤32768aggregate vertices,≤32768raw triangles/98304indices,≤64batches,≤24materials,≤18images and1179648selected pixels. Per-group source bound1MiB and logical facts≤1MiB; actual largest group11941vertices/13180triangles fits proposed16384per-group vertex/triangle ceilings. Source color statistics are small metadata only because this first recipe does not upload MOCV. Do not silently raise existing raw parser/default/codec caps; new bounded limits/profile need independent review.

Provisional standalone global child rows: CPUmesh1441792; GPUmesh1245184; queuedmesh1245184; meshstaging1245184; CPU/GPU/queued RGBA each4718592; one texture upload/file buffer/constructor/codec copy each262144; geometry scratch1048576; group/source/material/culling/shader reserve2097152. Existing800×500 shared bridge owners are separately19200256bytes: one color+depth target3200000, three GPU readback textures4800000, three direct CPU readback buffers4800000, flip buffer1600000, and mapped3slot file4800256. No second target/readback ring. Total proposed **42727680bytes**, below a provisional64MiB standalone global logical ceiling and the unchanged128MiB application child ceiling. No claim that driver/RSS follows this arithmetic. It is not an extra42.7MiB annex atop the121893172byte terrain child, and gives no combined hot/global/city budget.

Parent preparation may remain within the existing16MiB phased envelope only with exact phases and ownership: two maximum issued/building CPU snapshots consume12320768bytes. Final existing4MiB Map metadata phase plus262144fixed snapshot/source/array-header metadata reaches16777216exactly, leaving no unassigned row. Image decode phase must retire group/raw facts first; two snapshots+one64KiBraw image+1MiBdecode scratch+1MiBwriter staging+262144metadata=14745600. Group phase holds old snapshot+building mesh+one1MiBraw group+one1MiBfacts owner, no building RGBA yet. A dedicated allocation/overlap review must prove this, lower caps if another owner exists, and keep the existing prepared-table/lower-store open ledgers separate. Do not implement a positive factory on this arithmetic alone or hide another whole packet/geometry/color snapshot.

## Incremental acceptance

1. Independently approve zero-global transform/source API/feature approximations; preserve this proposal while exterior cold WMO delivery proceeds.
2. Prove reviewed native observed Map249 witness and actual WDT/global MODF→root/GFID/groups in one private source transaction; current unreviewed witness remains diagnostics only. Verify actual image bodies/pixels/caps in private scratch before choosing a complete texture profile.
3. Add bounded global admission/geometry/MOCV-descriptor handling and authored negative fixtures (wrong flags/nonzero transform/scale domain, duplicate/reordered globals, stale/all-binding/final callback changes, partial ID ambiguity, GFID/LOD/counts, missing images, unbatched vs overlapping spans, body/header mismatches). No IDs hardcoded for positivity.
4. Review standalone global packet/native owner/memory phases; authored asymmetric zero-global vertices/normals/winding/camera-inside/near clipping and raw-vs-batch coverage oracles precede GPU claims. Test every actual allocation/queue/retirement row and accepted pixels in the same World owner; no outer camera/target clone.
5. Actual Onyxia GUI proof: declared group/batch/image coverage, native movement/resize in one child, explicit omissions, pending texture activity, map-change/hide/cancel/close drain and untouched original store. Other backends/client lighting/water/doodads/floors remain unclaimed until exercised.
