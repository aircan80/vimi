# Standalone global WMO packet and native owner

Pre-code API/allocation checkpoint following G1 and G2 `497b7d7+a09e95e`.
G2 private payload implementation has its own review; this document does not assume
it is complete. This is an immutable **GLOBAL_WMO_ONLY** scene, not a terrain tile,
terrain annex, city placement, or hot global replacement. Existing forty shader
blobs and model/cold-terrain/hot-world protocols remain byte compatible.

## Fixed first presentation

Use the already qualified `GLOBAL_UNLIT_OPAQUE_RGB_V1` source recipe only: native
XYZ is G2's `(-sourceX,-sourceY,sourceZ)` with canonical positive zero and origin0;
XYZUV20, uint16 indices and opaque RGBA8. Every actual disjoint recorded MOBA span
is drawn in recorded group/batch order. Raw unbatched triangles remain undrawn.
No normal/color upload, alpha test/blending, outdoor lighting/fog, portal/BSP
culling, liquids, doodads, floors or collision. Draw both sides deliberately.

One sampler stage selects the batch's image, not eighteen simultaneous sampler
stages. Require eighteen texture objects, maximum image side256 and aggregate
1,179,648 pixels. Repeat U/V, linear min/mag, one selected mip0, no sRGB conversion
or extra mip generation. Fragment output is `vec4(sample.rgb,1)`. V0 samples the
first decoded row; do not introduce a CPU UV flip. Independently test texel-center
and repeated bilinear samples, including negative UVs.

Use the shared free-flight native XYZ/yaw/pitch camera math, with a distinct
`GlobalCamera` adapter enforcing finite camera XYZabs<=8192, pitch[-1.5,1.5],
800×500 output, near0.5 and reversed infinite-far projection. No terrain2200
whole-triangle rejection or fragment fog. Global bounds framing is an inspection
camera, **not** a claimed client entrance/spawn or floor. Users can fly through
walls. Source position error remains G2's exact0; shader/camera precision requires
separate authored GPU oracles, not inference from that source transform.

Depth is D32F, clear0, strictGREATER, write enabled; equal depth first source draw
wins. Require qualified zero-to-one depth. Homogeneous-depth OpenGL remains
UNAVAILABLE until its own precision proof; no silent LESS substitute. Vulkan can
qualify locally; Metal/D3D offline compilation is not hardware execution proof.
Background is an explicit flat opaque RGB(16,18,24), not outdoor sky.

## Closed source-to-file boundary

Proposed concepts-world extension is a fixed `withAssetAndWrite`, not an encoder
that accepts a Manifest or arbitrary choice of source IDs. It builds private G2,
then invokes the fixed writer while that construction/source operation is active.
Writer scratch, raw inputs and decoder frames retire before final derived stats,
fresh native Map/strictEOF+WDT, real cancellation drain and final scope/health gate.
A returned `WrittenGlobal<R>` owns G2 and the unadvertised R until successful final
validation. Failure closes both locally under the existing source active guard.

The GUI-owned fixed `WorldGlobalSourceBridge` is the only application adapter:

```
prepare(scope, recordedSelection, cancellation, PacketDestination) -> WrittenGlobal
WrittenGlobal.withWrittenResource(resource -> destination.adopt(resource.move()))
```

The second callback is a fresh actual pin on the returned source-owned R, checks
that its exact scope remains known/healthy/open, and runs off FX. MOVE happens
**inside that pin**, after source final checks and before the producer returns.
After MOVE the source R is an inert shell. Transport alone owns deletion while the
child may read; source close cannot delete an advertised input. Shared idempotent
close without ownership transfer is insufficient.

`withBorrow<T>` alone does not authorize an output file or certify post-borrow
source freshness. This extension needs explicit source-owned unreturned-R/final-gate
tests before a bridge can call it. A callback allocating a file then throwing before
return must close its local file. Unknown or dirty cleanup stays fatal.

Rendering-bgfx has no concepts-world dependency. Proposed neutral API:

```
WorldGlobalSceneWriter.write(PacketDestination, StructuralRead, Cancellation)
    -> UnadvertisedFile       // private constructor, owns its CREATE_NEW file
UnadvertisedFile.move() -> MovedInput // one-shot, separate actual deletion owner
GpuGlobalWmoSceneFile.read(ExpectedFile, cancellation) -> DecodedGlobal
BgfxGlobalWmoRenderer.upload(DecodedGlobal, cancellation) -> NativeGlobal
```

`StructuralRead` is a synchronous same-thread scalar/span adapter supplied by the
fixed bridge over G2's guarded borrow. No array/NIO/image/source-service escape;
reader and writer interfaces convey structural safety only, never source authority.
An external structural file/CLI cannot mint a qualified application Source result.
`PacketDestination` binds literal request identity, random128-bit instance nonce,
expected scope, private default-filesystem directory and exact `global.wgs` name.
No file path, matrix, public scene constructor or descriptive Manifest grants
positive ancestry. Preconditions precede allocation/copy/hash.

The writer uses CREATE_NEW+NOFOLLOW and tracks successful creation before cleanup.
It streams bounded chunks, closes its channel before returning R and never
advertises. It retains neither a full file mirror nor another mesh/RGBA copy.
File cleanup runs off FX, never deletes a preexisting/unowned path, and terminalizes
failure rather than retrying in a tight loop. The move helper must cover close/MOVE
races, fatal executor/delete errors and repeated future observations.

## Canonical standalone file V1

Little endian, maximum6,291,456B admission cap. Counts determine an exact canonical
actual length; no optional padding/trailing data or whole-file byte array. Maximum
encoded content is **5,577,600B**:

`1024 + 28*64 + 24*64 + 8*64 + 64*16 + 18*64 + 32768*20 + 98304*2 + 1179648*4`.

Header core offsets (bytes):

| Offset | Field |
| --- | --- |
|0|8-byte magic `VIMIGWS1`|
|8/12/16|u32version1/u32header1024/u64actualFileBytes|
|24/28/32/36|u32profile1/u32GLOBAL_ONLY1/u32D_ZERO_ORIGIN1/u32reserved0|
|40/48|u64MapID/16-byte instance nonce|
|64/96/128/160|32-byte scope/recipe/native-wire-mesh/whole-file SHA256|
|192|32-byte bounded canonical build+locale identity digest|
|224..260|tenu32: source/material/group/batch/image/vertex/index/rawTriangle/drawnTriangle/omissionFlags|
|264/288|threef64 origin exactly+0 /sixf32 derived native bounds|
|312..340|eightu32 offsets: source/material/group/batch/image/vertices/indices/RGBA|
|344/348/352/356|u32WDT/root/declarationOrdinal/unsignedUID|
|360|64-byte canonical actual zero-global MODF fields|
|424/428/432|u32bodyCRC32C/u32headerCRC32C/16reservedzero bytes|
|448..1023|18×32 original-source-RGBA SHA256 slots; unused slots zero|

Whole-file SHA is over actual bytes with160..191 zero. Header CRC is over1024bytes
with that SHA hole and428..431 zero. Body CRC covers bytes1024..EOF. The writer may
make a second bounded same-channel hash pass after filling CRCs; this is not a
second payload owner. Actual original-RGBA hashes are descriptive source facts:
reader cannot recover stripped source alpha and must not claim to recompute them.

Descriptors are contiguous in the listed order, actual counts only:

- Source64B: IDu32, roleu8, ordinalu8, flagsu16, actualLengthu32,
  declaredLengthu32, CK16B, bodySHA32B. Flagbit0 means declared length unknown
  (stored scalar0); all other bits zero. Role derives its fixed source cap:
  WDT1MiB/root64KiB/group1MiB/image64KiB. Order WDT,root,group ordinals,image slots.
  IDs unique; count is exactly2+groups+images. Known declared length equals actual.
- Material64B: sixteen original u32MOMT words, ordinal implied by record order.
  Preserve approved unused words; enforce the G1 zero-operand/texture1 profile.
- Group64B: sixu32 vertexFirst/count,indexFirst/count,batchFirst/count;
  ordinalu16/sourceDescriptorIndexu16/flagsu32;32B **native-wire group** SHA.
  This is not G1's original raw-float mesh digest: zero normalization makes that
  source digest unrecoverable. Actual group bodySHA remains in its source descriptor.
- Batch16B: groupu8/materialu8/flagsu16zero/globalFirstIndexu32/indexCountu32/
  reservedu32zero. Enforce group containment, triangle alignment and disjoint spans.
- Image64B: IDu32,slotu16,widthu16,heightu16,encodingu8,alphaDepthu8,
  alphaEncodingu8,mipFlagsu8,mipLevelu8,mipCountu8; rgbaOffsetu32/lengthu32;
  sourceDescriptorIndexu16/originalAlphaMinu8/originalAlphaMaxu8/nonopaqueCountu32;
  opaqueRGBA SHA32B. Encoding1=BC1 and2=BC3, never enum ordinals. Exact G1 tuples,
  mipFlags1/mip0, side<=256, finite count/stat relations; decoded payload alpha255.

Define the recipe/native-wire/group/build-locale digest domains and canonical field
orders in a small pure codec checkpoint with independent SHA oracles before coding
IO. Recipe includes explicit omissions and source-chain/alpha identities. Native
mesh digest binds exact XYZUV bytes, raw uint16 indices and batch/group coverage.
No source fingerprint is falsely equated with a lossy derivative hash.

Reader opens exactly one regular NOFOLLOW file handle, validates expected nonce,
scope, profile, exact count/offset products and header CRC BEFORE payload arrays,
then hashes/decodes those same bytes. Validate actual whole-file SHA against the
closed parent's ExpectedFile, bodyCRC, image/group/native-mesh hashes, final handle
length and exact terminalEOF. Reject gaps, overlap, integer overflow, unknown flags,
extra paths, nonfinite/out-of-profile coordinates/UV, invalid indices or ownership
cross-links. Decode into one private CPU mesh plus image owners; no public
ownership-taking Scene constructor or second concat/trim buffer. Partial allocation
failure closes every owned resource; unknown allocation/decode failure is fatal.

## One application GPU owner, immutable global mode

Extend the existing singleton `GpuPreviewService` with explicit GLOBAL request kind
and a consumed MovedInput; never infer mode from null terrain/model fields. Same
arbiter, Java/classpath matching, close protocol and actualProcessExit/worker/file
drain contract. Old model/terrain/hot context must actually drain before global
native allocations begin. Failed/unknown actual drain quarantines the permit.
No second global service/context or WMO annex added atop a terrain child.

Choose the existing **immutable v1 frame mapping primitive** for this first cold
mode, capped800×500 (4,800,256B), with a new fresh private directory/mapping per
request. Global content is fixed for that child; it cannot accept scene updates.
The parent associates the mapping with this request's nonce+scope+whole-file SHA,
not merely a path reused from an old child. It accepts no frames until strict
`GLOBAL_READY nonce scope fileSHA profile counts...` matches its owned ExpectedFile.
Add bounded `GLOBAL_INPUT_CONSUMED nonce fileSHA` only after all file reads close;
that status or actual confirmed process drain allows input deletion. Neither is
an accepted-pixel claim. ASCII/LF256B maximum, fixed arities and numeric bounds.
Child stdout writes use a bounded reliable off-native-owner writer with an actual
blocked-pipe deadline; reuse audited helpers where types permit. Freeze concrete
status arities in the pure protocol checkpoint, not ad-hoc log parsing.

Reuse WORLD camera control shape for free-flight, with new explicit global CLI
`--global-scene`+expectedSHA/nonce/scope, existing frames/parent arguments. Require
exactly one model/terrain/hot/global mode; global rejects VIEW, OFFER/COMMIT and
mixed arguments. No change to old flags or status acceptance. Frames capture actual
draw camera revision/dimensions; require current resize-policy revision floor,
matching dimensions and current request before detached pixel allocation, then
recheck before FX acceptance. Existing continuous-camera lag behavior must remain
nonstarving; no exact-latest-only loop. Because content is immutable, no invented
COMMITTED/terrain content revision or fake tile credit is needed.

Input consumed is not GPU-ready: complete all mesh/18image uploads before READY;
keep loading through first accepted actual frame. On unavailable global GPU, show
honest unavailable source/preview status and bounded retry; do not fabricate an ADT,
start a model owner alongside World, or claim an unimplemented CPU global fallback.

## Standalone child logical allocation envelope

Preserve the previously proposed **41,154,816B** total. This is not total RSS/VRAM
and is not additive to terrain121,893,172B. JVM/driver/bgfx context baselines stay
separately measured; no hardware headroom or FPS claim comes from this sum.

| Owner | Bytes |
| --- | ---: |
|One decoded CPU mesh|1,048,576|
|GPU mesh|851,968|
|Queued mesh upload|851,968|
|One direct mesh staging owner|851,968|
|Decoded CPU RGBA total|4,718,592|
|GPU RGBA total|4,718,592|
|Queued RGBA total|4,718,592|
|One direct image staging|262,144|
|File buffer|262,144|
|Constructor-copy allowance|262,144|
|Codec-copy allowance|262,144|
|Geometry conversion scratch|1,048,576|
|Bounded descriptors/material/shader/protocol/resource reserve|2,097,152|
|One target+depth3,200,000;3GPU readbacks4,800,000;3direct readbacks4,800,000;flip1,600,000;mapping4,800,256|19,200,256|
|Total|41,154,816|

One direct mesh block can service vertex+index creation; keep it pinned until BOTH
make-ref-release callbacks actually retire, or use bgfx_copy with its queued owner
charged. No per-batch mesh copies. Image bgfx_copy may copy from the reused262144B
direct staging block into independent native queued owners; queue aggregate never
exceeds4,718,592B and no new images upload after this immutable scene initializes.
Do not reuse a make_ref image staging block before callback. Preserve CPU payloads
through upload by default; an explicit later release cannot be assumed as budget
credit until actual queue/reference retirement. Destroy all handles on the owner
thread after draw/readback retirement; partial upload/cancel must use the same path.

Parent G2 source16MiB phases remain separate and unchanged. File is on disk, not a
new6MiB parent byte array. Parent mapping, detached frames and two FX bitmaps are
existing bridge/UI owners, **not** charged to source's16MiB: mapping4,800,256B,
up to one read scratch+one queued+one borrowed detached800×500 frame each1,600,000B,
and two FX bitmaps3,200,000B. Later implementation must count actual aliases once
and reject hidden additional copies. Keep one current+one pending source asset,
one finalized pending file and at most one actual child; actual drain precedes
successor native resources. Source A can retire after replacement frame acceptance;
its old raster may remain only as explicitly historical detached pixels.

## Staged acceptance before GUI enablement

1. Pure canonical descriptor/hash/CRC/layout codec checkpoint; hostile count/offset/
   finite/UV/source-role/alpha/hash/EOF and symlink cases, independent binary/SHA
   oracle, no source-positive or native claim. Exact digest domains/status arities
   and all allocation rows must be reviewable before IO/native code.
2. Closed G2 writer/MOVE bridge: actual28source reconsumption, final ALL/Map/WDT and
   post-drain scope gate; changed last binding closes unreturned file+asset;
   source close during write/MOVE vs child read; cancellation/dirty cleanup; no
   file deletion until INPUT_CONSUMED/confirmed actual exit. No GUI context yet.
3. Authored native owner: actual20B layout/uint16/batch order, negative-UV bilinear
   RGB oracle, alpha0/217 source RGB preserved/opaque output, asymmetric D/winding,
   inside-camera near clip, coplanar first-wins, no terrainfar/fog, three readback
   tickets with captured camera metadata, max counts, upload cancel/reuse and all
   partial-handle cleanup. Actual allocation/queued-copy and context delta proof.
4. New independent shader sources/resources only: vertex+fragment×4offlineprofiles
   (eight blobs) and distribution/probe manifests count48 including unchanged old40.
   Linux Vulkan actual proof; unavailable Metal/D3D/GL runtime stays NOT_RUN.
5. Source→owned file→one real child→accepted global pixels, camera/resize samePID,
   old model/terrain actual drain, hide/resume/software-unavailable/close, blocked
   stdout/EOF/invalidCLI, no source or native resource leaks. Actual Onyxia all59
   spans/28180drawn versus31219raw coverage, not screenshots alone. No floor,
   minimap, city/terrain composition, hot global streaming or animation claim.
