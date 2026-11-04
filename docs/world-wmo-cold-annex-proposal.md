# Optional cold BASIC WMO annex and native draw-pass contract

Status: concrete API/layout for review before implementation. Source factory a22b82d is a separate review. This proposal adds one optional exterior to the current immutable nine-tile world snapshot, in the same native child and framebuffer. It does not change terrain topology, view distance, hot protocol or global-instance origins. No public DTO, checksum or decoded packet authenticates a game asset.

## Module and authority boundary

Keep `WorldBasicWmo` and its checked source borrows private to concepts-world. Add an explicitly **structural**, source-neutral `BasicWmoData.View` contract in rendering-world (primitive read-only positions/normals/UV/indices, bounded batches/materials/images and descriptive fixed metadata). A view implementation can be authored or forged; accepting it in a codec validates presentation shape only and cannot mint a source claim, private scope, checked placement or live factory borrow. A child `GpuWorldWmoAnnex.Decoded` similarly means structurally safe presentation bytes, never VERIFIED_SOURCE. Do not offer `qualifySources(dto)` or a positive `sourceVerified` flag.

The trusted source-service composition adapter implements the neutral view only while pinned inside `WorldBasicWmo.Output.writeUnadvertised`. It writes the base terrain snapshot with `GpuTerrainSceneFile.writeBound`, then writes the optional WMO annex synchronously, in the same actual source admission. Parent service integration must expose only an operation that performs this transaction, not accept a caller-supplied positive view/DTO. No new concepts-world→rendering-bgfx dependency: the adapter/optional service hook connects the two existing module dependencies at the integration boundary. Its exact public optional facade is a later source-service review; first codec tests use authored structural views and make no source claim.

Proposed codec API in a NEW independent rendering-bgfx file:

```
GpuWorldWmoAnnex.write(Path createNewDestination,
    GpuTerrainSceneFile.FileDigest actualOwnedBaseWrite,
    TerrainScene sameBaseScene, BasicWmoData.View synchronousBorrow,
    BooleanSupplier canceled) -> void
GpuWorldWmoAnnex.read(Path source,
    GpuTerrainSceneFile.Decoded actualConsumedBase,
    BooleanSupplier canceled) -> Decoded
```

`FileDigest` already has a private constructor and comes only from actual bounded base-file read/write bytes; `requireScene` compares all tile fingerprints. The writer verifies the selected tile ordinal/coordinates/actual geometry SHA and exact shared origin against that actual file-bound scene. It cannot consume a caller-provided SHA string as a replacement. Reader compares the exact full base length/SHA and selected tile fingerprint against actual consumed geometry. A different base file with the same selected tile but different other eight tiles must fail. Existing other tiles keep their prior source contract; this is no new claim that all nine came from the current admission.

Both files stay UNADVERTISED scratch owners. A composition owner is registered locally before either CREATE_NEW write; any exception before returning R closes channels and deletes both partial files. After callback return the engine revalidates ALL actual source bindings and scope/generation; on failure it closes/deletes R. Child launch/GUI success/cache publication occurs only after successful transfer. Checksums bind bytes, not origin authority. No writable game-library access and no game assets in Git.

## Exact bounded binary profile

Little endian, magic ASCII `VIMIWM01`, version1, flags0, recipe1=BASIC_WMO_PREVIEW_V1, fog1=WMO_FRAGMENT_DEPTH_FOG_V1, lighting1 and sampler1 as below. Exactly one asset/placement; no compression, strings, paths, embedded shader bytecode, arbitrary material program or extension section. All reserved bytes zero. File length≤2MiB; checked offsets are canonical contiguous derived values, never an allocation request. No trailing bytes; checked end must equal stat length and actual EOF.

Fixed512-byte header:

| Offset | Bytes | Field |
| --- | ---: | --- |
| 0 | 8 | magic |
| 8 | 4+4 | version, flags |
| 16 | 8+8 | exact annex length, actual base-file length |
| 32 | 32 | actual base-file SHA256 |
| 64 | 32 | annex SHA256, computed with this range excluded |
| 96 | 32+32 | descriptive scope digest, selected root geometry fingerprint |
| 160 | 4+4 | recipe ID, fog ID |
| 168 | 4+4+4 | selected tile ordinal, filename X,Y |
| 180 | 4+4+4 | MODF ordinal, expected/actual UID unsigned32, Map ID unsigned32 |
| 192 | 8+8 | descriptive scope owner nonce, generation |
| 208 | 4+4+4+4+4+4 | vertex/index/batch/material/image/source-binding counts |
| 232 | 4+4 | raw root ambient color, zero reserved |
| 240 | 24 | actual base origin X,Y,Z float64 |
| 264 | 40 | raw MODF position3/rotation3 float32, scale/flags/doodadSet/nameSet unsigned32 |
| 304 | 48 | computed translation3/scale/cos/sin float64 |
| 352 | 56 | absolute offsets of bindings/materials/batches/images/vertices/indices/pixels |
| 408 | 4+4 | lighting ID, sampler ID |
| 416 | 96 | zero reserved |

Counts: 1..512 vertices; 3..1536 indices divisible by3; 1..6 batches; 1..6 materials; 1..6 distinct images; source bindings exactly5+imageCount≤11. Raw placement follows exact approved ADT flags/rotation/scale domain. Float32 source fields finite and bounds checked; computed doubles finite and independently recomputed from raw placement with the normative StrictMath recipe before acceptance. Stored translation/scale/cos/sin must match recomputed raw double bits, including signed-zero policy pinned by authored cases. The codec is not permitted to guess a global WDT transform. Origin raw double bits must match the consumed base scene's origin.

Canonical sections, in order:

- Sources:64 bytes each: unsigned32 FDID, unsigned32 role,16-byte CK, unsigned64 consumedLength, unsigned64 beforeReadDeclaredLength (`FFFFFFFFFFFFFFFF` means unknown), unsigned32 exact sourceLimit,20 zero bytes. Roles in order WDT/rootADT/OBJ0/WMO-root/WMO-group, then images in first referenced material order. Lengths/limits match fixed role ceilings1MiB/2MiB/8MiB/1KiB/32KiB/64KiB; known declared length equals actual consumed length. Numeric IDs/CK are descriptive; no new source lookup or guessed ancestry occurs in codec. No default-zero invented receipt. Nonempty images remain distinct, source count/order exact; repeated material textures deduplicate into one image.
- Materials:64 bytes each: all16 original unsigned MOMT words. Structural recipe checks flags/shader/blend/unused runtime/texture-stage domains just as source recipe; positive texture1 matches exactly one image descriptor/consumed image binding. Retain the intentionally ignored color/group operands, never reinterpret black color1 as a diffuse tint.
- Batches:16 bytes each: firstIndex/indexCount/materialOrdinal unsigned32 and reserved0. Positive triangle-aligned spans, in recorded batch order, partition all recorded indices exactly once (no sorting/overlap/gap); material index valid. Preserve original index order/winding. No MOPY collision/material visibility filter.
- Images:64 bytes each: unsigned32 FDID,width,height,RGBA byteLength; unsigned64 absolute pixelOffset;32-byte selected RGBA SHA256;8 zero bytes. Dimensions1..256, selected mip0 RGBA8, opaque alpha255, width×height×4 exact, total pixels≤393216. Image domain/decoding was checked by the source factory; IPC validates bounded dimensions/bytes/alpha/digest. Canonical pixel order is descriptor order, no aliasing/gaps.
- Vertices:32 bytes each: position3, normalized local normal3, UV2 float32. Finite; source normal length within codec's documented float tolerance; no transform baking or origin subtraction from local mesh. Every transformed native coordinate bounded by TerrainScene.MAX_COORDINATE. UV finite without invented clamping/replacement.
- Indices: unsigned16 per index, each<vertexCount. This narrows IPC/GPU storage only; parent source indices remain int32.
- Pixels: tightly packed top-left RGBA8 descriptor order, no mip chain/row padding. Stream in≤64KiB chunks, compute digests incrementally, validate alpha on consumed bytes.

Maximum canonical length is512 +11×64 +6×64 +6×16 +6×64 +512×32 +1536×2 +393216×4 = **1,594,400 bytes**. A checksum failure, wrong base lease/selected fingerprint, unknown field, unsupported recipe or any count/offset/length failure yields no uploadable object. Validate header/counts/offset arithmetic before allocating arrays. Reader holds at most one per-image allocation/constructor defensive copy phase, not an annex-sized byte array. Complete semantic and full-file SHA/EOF validation precedes publication; failed owned buffers are retired. Structural safe Decoded does not imply native allocation/upload succeeded.

## Native draw pass: independent files, existing world owner

After API review, a separately owned `BgfxWorldWmoPass` and new shader directory `main/shaders/world-wmo/` may implement this recipe. Do not edit hot renderer files concurrently. Parent's cold scene renderer invokes the pass **after terrain draws on sequential view0**, using the same camera/frame/scene origin. Terrain owns view clear, sky, framebuffer and lifecycle; WMO pass never sets a framebuffer, clears color/depth, constructs another camera or starts another bgfx context. State is RGB/A/Z writes plus reversed GREATER, no blending/alpha test and no cull flag (explicit two-sided BASIC approximation). Geometry is indexed, no triangle duplication or normal-based winding changes.

Vertex stride32 is POSITION3/NORMAL3/TEXCOORD0_2 float32; immutable vertex buffer≤16384B and unsigned16 index buffer≤3072B. Six batches select source material's one existing texture handle. RGBA8 textures have one physical mip, linear min/mag, repeat U/V, point mip selection, no anisotropy/sRGB conversion. Sampler defaults must be asserted as named flags, not left to an undocumented state inherited from terrain. Upload/readback/destruction runs only on the native context owner. Invalid create/upload handles close every already-created handle and leave terrain operational with an explicit WMO unavailable code.

Build model-to-origin matrix in Java double from actual raw placement; subtract the exact shared origin in double **before** casting each uniform coefficient once. Column-major matrix columns are `(-s*co,-s*si,0,0)`, `(s*si,-s*co,0,0)`, `(0,0,s,0)`, `(Tx-Ox,Ty-Oy,Tz-Oz,1)`. No FMA, snap, second ADT swizzle or camera fit. Use the SAME `TerrainCamera.modelToEye(origin)` and `reversedProjection(width,height)` values as terrain. A distinct rotation-only normal matrix uses D·Rz(h), no translation/scale; normalize interpolated normal per fragment. Uniform storage remains in the named metadata/shader reserve, not another retained mesh.

### Explicit fragment depth, lighting and fog decision

Choose **WMO_FRAGMENT_DEPTH_FOG_V1**. Vertex shader calculates actual eye position with shared eye/model matrices and passes positive eyeDepth=-eye.z as a perspective-correct varying. Fragment shader discards depth>2200; hardware reversed projection clips near0.5, no finite far projection or per-whole-triangle CPU drop. This is explicitly different from legacy terrain's maximum-original-triangle depth fog/whole-triangle far policy.

For normalized interpolated native normal n and L=normalize(-1,-1,2): `I=1/3+(2/3)*max(0,dot(n,L))`. Sample source RGB in its existing byte-normalized space (no sRGB correction), opaque alpha1. `f=clamp((eyeDepth-600)/1600,0,0.85)`. Output RGB=`floor(sampleRGB*255*I*(1-f)+vec3(153,184,196)*f)/255`, alpha1. Root ambient/material colors/groupType/sky/fog are explicitly not lighting operands of this named approximation. No face-normal substitutions, backface normal reversal or hidden tint. Terrain and WMO may show different fog across shared edges due to their named depth rules; do not claim client or terrain-flat fog parity.

Authored CPU oracle pins depths0.5/600/1400/1960/2200 and clipping just beyond2200, normal cardinal/interpolation, repeat/bilinear UV boundaries, opaque output and floor semantics. Matrix tests use all cardinal headings, non-origin translation, nonunit positive scale, nonzero shared origin, asymmetric camera yaw/pitch, zero/negative zero and independent eye-space expected values. A real Vulkan fixture must show one terrain occluder and one textured WMO triangle intersecting with GREATER shared depth; camera-only motion retains the same context/buffers. Native analytic/pixel tolerance is explicit and independent of source/parser test success.

## Named ownership and compatibility gates

Existing immutable textured terrain baseline121,893,172B + proposed WMO additional6,175,744B =128,068,916B, below134,217,728 by6,148,812. No cap increase. Additional rows stay CPUmesh22528; GPUmesh19456; queuedmesh19456; meshstaging19456; CPU/GPU/queued RGBA each1572864; one texture upload/file buffer/constructor copy/codec defensive copy each262144; geometry scratch65536; metadata/shader reserve262144. Do not retain an annex-sized byte array, texture CPU duplicates after their charged phase, another terrain geometry or extra framebuffer. Source helper's separate16MiB phased envelope/lower metadata ledgers do not become a child allowance. Actual allocation/upload queues/overlap and cleanup still require independent proof against these exact owners; arithmetic is not a runtime memory measurement.

Absence of the new optional annex preserves existing cold terrain/texture behavior. Invalid WMO annex or unsupported native capabilities must leave the base world operational and report an explicit unavailable/approximation state. Public process arguments need a separately reviewed optional `--world-wmo-annex` path after codec/pass review; control/frame/hot protocols remain unchanged. All new ABI fields are versioned and fixed; no filenames or source acquisition in the child.

Implementation order: review this concrete API/layout/fog decision; implement neutral structural view+codec/authored tamper/caps/EOF/cancel/base-swap fixtures independently; source adapter/private scratch write proof after a22 approval and observed-row Map gate; native owner then implements NEW WMO pass/shader files with matrix/depth/sampling tests; finally parent owns minimal cold launch/renderer integration and actual same-camera frame proof. A valid codec or source chain alone is not a visible city release.
