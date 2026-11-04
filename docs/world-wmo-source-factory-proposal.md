# BASIC_WMO source and renderable-factory API checkpoint

Status: concrete design/API for review before implementation. WmoFormats5dbc64c is approved for raw facts only. This proposal builds on the World source admission contract941833b/e2e171b and the corrected one-asset ledger076e5a9. No positive factory, source helper, codec or renderer code is added here.

## Deliverable and API boundary

First deliver one positively qualified, textured static ADT-placed exterior in the current3×3 World Explorer. Its named recipe is BASIC_WMO_PREVIEW_V1, deliberately approximate lighting. It does not require hot streaming, full city coverage or client-lighting parity. Global-WMO instances remain a separate origin/scene kind: this API cannot open Onyxia by inventing Tile(0,0).

Implement the first checkpoint as package-internal concepts-world APIs, preserving existing BuildWorld/WorldService constructors and custom providers. Any eventual public optional hook needs an explicit UNSUPPORTED default; no new abstract method is required now. Proposed internal surface:

```java
record WmoSelection(long mapId, WorldFormats.Tile selector,
                    int modfOrdinal, long expectedUniqueId) {}

interface WmoOutput<R extends AutoCloseable> {
    R writeUnadvertised(WmoBorrow input, CheckedSceneMatch scene,
                       Cancellation token);
}

<R extends AutoCloseable> R prepareBasicWmo(
    WorldStreamScopes engine, Scope scope, WmoSelection selection,
    TerrainScene candidateScene, WmoOutput<R> output, Cancellation token);
```

Names are provisional; visibility/lifetime requirements are not. Selection stores only unsigned numeric IDs, tile coordinates/all eight bounded MAID selector operands and ordinal0..4095; no model ID, transform, path, image receipt, arbitrary profile or positive boolean is accepted. Map ID must match the private scope. The selector tuple is comparison-only. The ordinal selects an actual parsed MODF declaration and must match expectedUniqueId; reordered/replaced selections return STALE rather than showing a different object. Scan the bounded source's MODF list for duplicate selected IDs: conflicting declarations refuse; no last-writer-wins. No private sample FileDataID/placement ID participates in admission policy.

The method calls exactly:

```java
engine.withRecordedTile(scope, selection.selector(), UseProfile.BASIC_WMO,
    token, admission -> qualifyComposeAndWrite(admission, selection,
                                              candidateScene, output));
```

`WmoBorrow` and `CheckedSceneMatch` have private constructors, no positive public DTO factory. Their callback data is immutable/read-only, bounded, and expires after use. Private checked edges/placement/root-frame values are constructed only from actual admission-owned reads. The output callback is a trusted composition adapter: it may create owned UNADVERTISED scratch files, not publish a child/packet or source claim. Engine final validation follows any write delay, then transfers the nonnull owned result. On failure/cancellation/stale scope the engine closes the returned result and registered inputs exactly once. BEFORE R exists, each factory/output callback must own every allocated intermediate through local try/finally or an engine-registered cleanup owner: scratch files/channels, decoded images, leases and partially built results must close if the callback throws before returning. The engine cannot discover or close an unreturned opaque R. Register an output owner before starting throwable IO, transfer it only after successful construction, and never leave an unreturned resource outside a local/registered cleanup scope. Renderer data/IPC checksums are descriptive; they do not authorize a game-source claim.

For isolated source/factory tests, the output can own detached checked mesh/images without writing files, through a private explicit ownership transfer or bounded copies; retaining expired callback views is never a transfer. The future cold packet adapter runs in the same operation. A callback returning arrays and later writing/advertising outside this guard is not equivalent to final validation after a delayed composition.

## Actual source transaction

1. Resolve the private current/replacement scope and fresh qualified native Map row/stamp/strict EOF; validate current WDT identity and all eight selector references. Admission provides the recorded tuple copied from the registry, scoped cancellation and tracked actual reads. Existing legacy UNKNOWN Map evidence requires a separate explicit native-preparation action; this factory never decodes/repairs/acquires a table implicitly. New native readiness still does not bypass layout/profile checks.
2. Read the admitted root ADT through the bounded source facade (at most2MiB, matching the existing basic terrain source ceiling), parse the canonical full256-chunk grid and calculate `TerrainGeometryFingerprint` revision`native-terrain-geometry-v1`. Construct private CheckedRootFrame metadata: scope identity/generation/digest, tile tuple, root FileDataID/CK/actual consumed length and geometry SHA. Retire the root input/grid/extra geometry before WMO/image preparation. No additional full TerrainScene is retained behind this token.
3. Read ONLY admitted obj0, at most8MiB, complete/no holes. Parse actual WorldPlacements facts. Construct private CheckedPlacement from the selected actual record and its exact obj0 receipt/parser revision/ordinal/uniqueID. Public WorldObject, Reference, LoadedTile and WorldPlacementSource.Receipt values cannot mint this edge. Do not merge/probe obj1, resolve names, snap to terrain or substitute creature displays. Retire the complete obj0 input/other records after extracting the bounded selected facts.
4. Derive root WMO FileDataID only from that checked MODF direct-reference record. Read at most1KiB; parse WmoFormats with the one-asset shape limits. Qualify the direct texture/reference profile described below. Derive the sole group ID from the actual GFID inventory, not a path or caller ID. Read that group at most32KiB and validate its raw facts and render profile.
5. Derive at most six distinct image IDs solely from consumed MOMT texture1 references. Read/decode one at a time, at most64KiB per source, through the existing strict BLP path. No missing/alternate texture, minimap image or guessed filename is substituted. Construct private checked image bindings with original/selected dimensions, encoding/mip facts, actual byte length, CK and selected pixel digest.
6. Qualify the private asset independently, then match its already-checked root geometry to exactly one selected tile in candidateScene. Compose/write only through the callback with all owned views pinned. Engine revalidates ALL consumed bindings plus fresh Map stamp/WDT/scope/generation after composition and before transfer. Earlier per-read checks alone do not cover changes during a held texture/decode/write callback.

BASIC_WMO has an exact11-binding ceiling: WDT, selected root ADT, obj0, WMO root, one group and six images. Map proof/stamp metadata is separately bounded by the source engine. The source engine reserves exactly ONE live raw input owner before opening/allocating it, with aggregate live raw bytes at most8MiB for BASIC_WMO (the largest permitted obj0). A second live input is refused; closing the owner clears bytes/stream and releases its reservation before the next read. Binding-count limits alone do not bound simultaneous buffers. Factories must retire/copy the needed bounded facts before closing input, then retire old decoded phase data before opening the next phase. Each read requires matching requested/resolved numeric ID, valid CK, complete/no-hole source and actual length within its ceiling, with known declared length consistency. First source admission should reject unknown declared size before opening these tiny derived assets; storage/path/format enrichment must not invalidate identity. Any additional dependency needs a new reviewed profile, not TERRAIN_BASIC's larger binding budget. Shared file/CDN/BLTE/keys caches remain separately named lower owners, not this helper's payload allowance.

No positive cache is needed initially. Later cache reuse requires private proven source receipts/recipe identity and admission tracking/revalidation; a raw parser DTO or old public receipt is insufficient. Final staleness yields no advertised derivative/cache success. No original user-library write or asset-in-Git policy is changed by this design.

## Exact first structural/material domain

This is a general structural source profile, not a whitelist of private IDs:

- Recorded ADT MODF flags exactly0xC, FILE_DATA_ID reference positive, raw X/Z rotations exactly zero (either sign of zero accepted), positive unsigned scale, finite source bounds; name/doodad sets0 for this first slice. Other origins/flags/rotations decline explicitly.
- WMO version17, root flags0, LOD count0, exactly one group and one positive GFID entry; no MOTX block. This combination is the reviewed modern GFID/direct-material domain. The raw parser still reports UNRESOLVED_UNSIGNED32: only this profile plus positive build-file/image checks consumes texture1 as a FileDataID. A legacy MOTX offset or missing/ambiguous group domain refuses rather than opportunistically resolving an integer that happens to exist.
- One source group, flags exactly0x9, one UV stream and no MOCV/MLIQ/ambient/extra material-bearing streams. At most512vertices/512triangles/sixMOBA batches. Every batch uses flags0/materialByte in the reviewed MOMT range, positive triangle-aligned index count; spans partition the recorded index inventory without overlap/gaps. Preserve recorded indices/winding; no MOPY collision/material-byte filtering or normal-based triangle flipping.
- One to six MOMT records, all flags0/shader0/blend0, positive texture1 and texture2/3zero. Flags3/runtime words must be0. Every recorded draw uses a qualified material; first delivery refuses the whole asset on an unsupported batch instead of painting missing pieces neutral. Source geometry stays static; no animation, embedded M2, skinning, lights or portals are implied.
- Header portal/light/model/doodad counts must be0; at most one default empty set. MODD/MOLT/portal payloads must be absent or explicitly zero length. Root names/group-info, collision BSP data, default set metadata, root sky/fog declarations have a pinned explicit ignore/shape allowlist because they are not consumed by this BASIC recipe. Unknown nonzero render-affecting declarations refuse. In particular, the raw parser's unparsed-descriptor presence is not evidence that a feature is absent. The fixed first allowlist is: MOGN≤64bytes; MOGI exactly32bytes; MOSB absent or4bytes (ignored root sky); MFOG absent/0/48bytes (ignored root fog); MODS absent or32×the declared0..1 empty-set count; MOPV/MOPT/MOPR/MOVV/MOVB/MOLT/MODD absent or0bytes. No other non-core root payload is admitted. MOGI descriptive metadata does not override the consumed actual MOGP geometry/flags. Beyond parsed mesh tags, the group may contain only MOBN with size divisible by16 and MOBR with size divisible by2, both still under32KiB total source; they are explicitly ignored collision BSP data. All other unparsed group payloads decline. Pin these exact cases in positive/negative tests.
- Images: existing STRICT decoder, original/base side at most256, mip0, BC1, alphaDepth0, alphaEncoding0, mipFlags1, no handmade fallback. At most six handles and393,216selected pixels globally. The observed six images satisfy these exact facts. Other decoder-supported formats are not automatically part of this first material profile.

MOMT color1/color1b/color2/color3, root ambient and groupType are intentionally not applied as client tint/emission/specular terms. Preserve their raw declarations in detached bounded diagnostics/recipe details and label the result simplified lighting. Their nonzero values are not automatically unsupported, and opaque-black color1 must not become an invented black diffuse multiplier. Ignoring these named lighting operands is an explicit BASIC approximation, not an incomplete-client-parity blocker. Unknown shader/texture-stage/visibility semantics remain unsupported.

## Normative ADT heading and FP policy

Use raw source floats promoted directly to double. With h=rawRotationY, v=(x,y,z), p=(p0,p1,p2):

```
C = 51200.0 / 3.0
s = unsignedScale / 1024.0
theta = ((double)h) * (StrictMath.PI / 180.0)
co = StrictMath.cos(theta); si = StrictMath.sin(theta)
T = (C - (double)p2, C - (double)p0, (double)p1)
qX = T.x + s * ((-co*x) + (si*y))
qY = T.y + s * ((-si*x) + (-co*y))
qZ = T.z + s*z
```

This chosen recipe is the proper rotation D·Rz(h), D=diag(-1,-1,+1), with positive uniform scale. The independently traced WMO chains are Trinity's raw WMO vertices/ModelInstance/fixCoords/internal-native conversion and wow.export's WMO vertex swizzle/world matrix/native-basis conversion. Do not copy an M2 loader swizzle, apply C twice or use the separate global-WMO origin. Full pitch/roll is not admitted.

No FMA, ground correction or source-float “repair” is specified. Cdouble is17066.666666666668; a source float at the rounded map base produces the known0.0006510416678793263residual, not an invented exact zero. Validate source/transformed coordinates against the existing TerrainScene.MAX_COORDINATE1,000,000 bound. Compute world translation and subtract the shared scene origin in double before casting each matrix/uniform coefficient to float once. Preserve mesh-local source positions; use the same frame origin/camera as terrain. Parent double oracle and native float arithmetic need a stated quantization tolerance, not bit-equality or a fitted orbit camera.

Normalize each nonzero finite source vertex normal in double then cast to float once; require source length within1±0.001 for this narrow profile. Positive uniform scale cancels after inverse-transpose normalization, so normal direction uses D·Rz(h). Normalize the interpolated transformed normal per fragment. Preserve source triangle order even when its cross-product and smooth vertex normals disagree; no majority-sign heuristic. Authored asymmetric cardinal/scale/normal/winding oracles and an actual terrain camera alignment precede renderable approval. The actual root-bound residual0.000567 is corroboration, not proof of every sign/order.

## Explicit BASIC drawing recipe

Use source UV0 unchanged. Sample selected RGBA8 mip0 with repeatU/repeatV, bilinear min/mag, one physical mip/no cross-mip interpolation, no anisotropy and no sRGB transfer correction. This matches a named preview recipe, not client color-space fidelity.

Use normalized source vertex normals, fixed native light L=normalize(-1,-1,2), intensity`1/3 + (2/3)*max(0,dot(n,L))`, source RGB divided by255, opaque alpha1 and a deliberately separate WMO fog rule to be pinned before renderer implementation. Source/factory admission does not depend on fog. Current terrain fog uses the maximum original-triangle eye depth as a flat value; ordinary indexed WMO vertices cannot silently claim that rule while using fragment depth. Renderer review must choose an explicitly named WMO fragment-depth recipe or budget a true per-triangle implementation and test it independently. Root ambient/MOMT lighting colors/root fog/sky do not override this explicit recipe. No face-normal substitution, hidden tint, alpha cutoff, blending or diffuse replacement is permitted.

First BASIC preview proposes two-sided batch rasterization with no triangle duplication or per-face normal reversal. This is an explicit preview culling choice, not inferred client double-sided support; preserve/test winding nonetheless. Use shared reversed GREATER depth and RGB/A/Z writes in the same terrain framebuffer; never clear terrain depth or create a model-preview camera/context. If material review prefers one-sided state, pin its exact bgfx winding against an independent authored native fixture before implementation rather than infer it from vertex-normal sign. The observed mesh has both normal-sign cases, so “make all triangles agree with normals” is invalid.

## Matching the existing nine-tile scene

CheckedRootFrame retains only small private receipt+geometry fingerprint metadata. The already-qualified asset can bind to candidateScene only when exactly one tile has the selected filename coordinates and exact original geometry SHA. Missing/duplicate/mismatched tile rejects. Other eight tiles retain the existing terrain contract; this operation makes no new source claim about them. This comparison cannot mint WMO positivity from a public TerrainScene, so a fully private nine-tile proof is unnecessary and view distance stays unchanged.

The cold writer hashes the ACTUAL serialized base-scene file with bounded streaming input, binds that whole-file SHA, scope digest, selected ordinal/fingerprint, asset recipe/body fingerprint and shared origin into the optional WMO annex, and verifies the selected geometry from actual written/consumed bytes. Caller-supplied SHA/metadata alone cannot mint a binding. Write only bounded unadvertised owners inside admission; advertise/start the child after successful final transfer. The packet/annex profile and its≤2MiB streaming body cap still need codec review. Existing terrain files/control remain compatible and height/texture fallback remains honest. No whole annex allocation or separate object readback target is allowed.

This cold path inherits the current neighborhood handoff limitation. Camera-only motion retains scene/child/uploads. A future hot asset protocol is independent and does not block this one-asset delivery.

## Worker, pins and close

The future async helper owns one source worker, one metadata-only replaceable pending selection, and an off-caller bounded cancellation lane. It never performs source calls or invokes cancellation callbacks on FX. A busy shared source guard returns BUSY/queued-latest status, never a fabricated unsupported asset or an unbounded task. Superseded requests resolve as cancellation and release inputs; camera-only updates do not enqueue another preparation.

At most two separately charged issued/building asset slots exist (current plus pending). Retiring a pinned old result does not create capacity until its callback returns. Admission/output borrows increment ownership under a short monitor; callbacks, IO, encode and destruction run outside monitors. Close rejects new work promptly, signals scoped cancellation off caller, drains the active source operation, expires views, closes current/pending owners, and completes after active writer/borrow pins retire. Double close is harmless. If a borrowed provider/callback ignores cancellation, drain remains pending; do not kill it or close caller-owned BuildFiles/tables/stores to pretend completion.

The prepared result carries detached≤11source tuples, fixed scope/root-frame/recipe digests and at most32fixed-code diagnostics, with total canonical metadata≤16KiB before copying/hashing. No schema, capture, heavy MAID proof, live admission, input owner or borrowed source handle is retained in it. Callback views must not escape. Wrapper methods check expiry, but Java cannot revoke an already-returned read-only NIO view; those primitive views are contractual synchronous borrows, not independently owned results or source proof. Source publication and the parent latest-view generation are both checked; a valid historical snapshot does not authorize a stale view to replace the user's current map.

## Memory gates and implementation order

The approved-design arithmetic remains additional child6,175,744bytes plus corrected immutable textured terrain121,893,172 =128,068,916bytes, leaving6,148,812below128MiB. This is a proposed named-owner envelope, not runtime allocation proof. Preserve its per-texture streaming/constructor/upload/queued-copy rows. Source-frame metadata adds no retained terrain duplicate. Existing shader/program overhead and every actual codec/native allocation must fit the named reserve or lower caps; no silent cap raise.

Proposed parent helper envelope is16MiB of NEW logical preparation ownership, excluding separately named file/network/prepared-table subsystems and existing terrain owners: at most one old asset plus one building/ready pending asset; root read/grid/fingerprint phase≤4MiB; obj0 phase≤8MiB input plus≤3MiB bounded records; WMO facts≤256KiB plus tiny root/group inputs; one≤64KiB image input plus≤1MiB decoder/copy scratch; two CPU mesh/pixel snapshots≤3,190,784bytes; metadata/packet staging≤1MiB. The explicit conservative maximum8MiB raw +3MiB records +3,190,784bytes two snapshots +1MiB staging is15,773,696bytes, below16MiB by1,003,520bytes. This relies on the one-live-input reservation and exact two-slot ownership, not just11tracked receipts. These phases are serialized: retire root grid before obj0, and retire all unselected obj0 records/input before WMO/images. Final Map-stamp phase has its own existing4MiB allowance and cannot retain prior raw buffers. Before implementation approval, assign actual representations to this phase maximum and instrument pin/close overlap; counts alone are insufficient. Do not include an existing16,384record placement cache for free: this first factory can parse one actual obj0 and retain only selected fixed facts, without another persistent raw cache.

Review this API/material/transform policy, then implement the private source/factory and authored source-mutation/forgery/normal/material/limits/close fixtures. Test held callback mutations of Map/WDT/rootADT/obj0/WMO/group/each image, UID reordering/conflict, source-authority forgery from public DTOs, unknown domains, count/pixel limits before allocation, stale scene/whole-file swaps, callback exceptions, canceled/closed drain and one-active+pending admission. Independently verify non-origin asymmetric transforms/normal policy and basic lighting/sampler arithmetic. Only after those pass, review the cold annex/native ownership implementation and actual Vulkan shared-depth/UV/fog/camera frames. No parser/source test is a visible-city release.

## Evidence and references

An additional read-only-original/private-scratch proof now completes the small example chain: readable Map row0 records Eastern Kingdoms/Azeroth/type0/WDT775971. That WDT is294,988bytes, CKe4fb77fc12097f3b41adf2aac2d945e7, MPHD0x3CA,736active tiles, no global MODF. Actual MAID tile32,32 records(root777947,obj0777948,obj1777949,tex0777950,lod1287116,mapTexture1292902,mapTextureN1292903,minimap204477). Its MAI2 declaration is not interpreted by this base-MAID profile; no HD/SD/LOD alternative claim follows.

Actual root ADT777947 is454,764bytes, CKf7396b8dc27c75f3bce6b9b8c3056ae1. The existing canonical256-chunk grid/fingerprint algorithm yields c032876b1c24752cb8f2bedf95e710510402addf9ee98e89b6bff675601ae6df. This supplies the concrete selected-tile binding example without retaining an extra scene. Actual obj0777948/MODF ordinal2/UID173696 then links to root106905/group106906 and six opaque BC1 mip0 images (335,872pixels); all six additionally report alphaEncoding0/mipFlags1. The source mesh has346positive and32negative triangle-cross/smooth-normal dot signs, confirming that normal-based triangle flipping would rewrite recorded data. Private logs are `wmo-map-wdt-root-chain.log` and `wmo-basic-policy-facts.log` under `/tmp/vimi-world-city-private`. These are source facts and policy evidence, not a hardcoded eligibility list or a positive native-Map factory test; that saved Map capture remains legacy/PARTIAL in the investigation and the production factory still requires fresh VERIFIED_NATIVE admission. Onyxia's actual Map249 global root has MOCV/MLIQ and a different origin; it cannot enter this API/profile. See `world-city-wmo-source-checkpoint.md` and `world-instance-source-checkpoint.md`.

Primary transform/domain references: [TrinityCore WMO extractor ec72c19](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/vmap4_extractor/wmo.cpp), [ModelInstance](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/common/Collision/Models/ModelInstance.cpp), [wow.export WMO loader4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WMOLoader.js) and [world renderer](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/map-viewer/WMORenderer.js). Preview policy is Vimi's explicit choice, not a claim copied from those clients.
