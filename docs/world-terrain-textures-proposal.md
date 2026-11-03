# First bounded terrain texture preview

Status: source-backed pre-code proposal. Root requested a shared CPU/GPU512²
precomposed tile atlas with an explicit detail cap. No production reader, renderer
or format admission is changed here. Packaged GPU diagnostics are preserved and
paused; terrain imagery, objects and water have priority. Ready features may
share a prerelease.

## Concrete first delivery

Texture the existing at-most-nine real ADT tiles using recorded split tex0
references and a narrowly qualified eight-bit diffuse-layer profile. Compose one
512x512 RGBA8 atlas per tile off the FX thread, then use the same immutable atlas
in software and the existing inline terrain GPU child. Each admitted source MCNK
occupies32x32 pixels. This is a deliberately coarse diffuse preview: eight diffuse
repeats per chunk means four atlas texels per repeat. It is not full-resolution
terrain, height blending, client lighting, buildings, doodads or water.

Keep unsupported/incomplete chunks in the existing height-shaded mode, with
explicit counts/reasons. Do not silently keep only available layers or
renormalize a missing layer into a different appearance. Geometry, holes, source
axes, reversed depth, near clipping, whole-original-triangle far rejection and
max-original-eye-depth fog remain unchanged. The texture path never substitutes
minimap/mapTexture imagery for a terrain material.

## Actual selected-build facts

Read-only source audit: Forever70755 enUS. The original library and the prior
area-proof acquisition store were opened read-only. The following exact MAID
sources and13 diffuse images were acquired only under
`/tmp/vimi-terrain-texture-proof/acquired`. No settings, running JARs or original
library objects were written; game bytes remain outside Git.

WDT775971 has CKey`e4fb77fc12097f3b41adf2aac2d945e7`, version18, MPHDflags0x3ca.
Its recorded MAID tuples include:

| Filename tile | root | obj0 | obj1 | tex0 | tex0 CKey |
| --- | --- | --- | --- | --- | --- |
| 32,32 | 777947 | 777948 | 777949 | 777950 | f107fa3f11eb29293b358d3e7143f10f |
| 33,32 | 777952 | 777953 | 777954 | 777955 | 451d7db660f7f855737325770181a114 |
| 32,33 | 778147 | 778148 | 778149 | 778150 | 5f79d04a5c16e48852be8b1a5830f260 |

Each root has256 header-bearing MCNKs, zero recorded root texture layers and no
MCLY/MCAL/MTEX. Each paired tex0 has MVER18, MAMP0, MDID, MHID and256 headerless
MCNK texture records containing MCLY/MCAL. No MTEX or MTXP is present. All MHID
entries are0. Do not infer tex0 as rootID+3: use MAID+12. The future placement
checkpoint`3b5c589` already preserves `Tile.tex0Id()` and the other eight MAID
fields; coordinate against that API rather than creating a competing Tile shape.

| tex0 | bytes | MDID count | source layers/chunk | overlays | mask flags |
| --- | ---: | ---: | --- | ---: | --- |
| 777950 | 1140380 | 12 | 2:15,3:35,4:203,5:2,6:1 | 707 | 706×0x300,1×0x100 |
| 777955 | 980646 | 8 | 2:38,3:55,4:161,5:2 | 639 | 639×0x300 |
| 778150 | 1069109 | 8 | 2:29,3:32,4:195 | 678 | 678×0x300 |

Every base layer flags0. Every decoded overlay has exactly4096 bytes, consumes
its entire declared MCAL interval and has no trailing bytes. For all2074 masks,
per-pixel overlay sums never exceed255. Nonzero effectIDs describe omitted ground
effects, not texture identifiers or permission to render foliage.

The13 actual diffuse FileDataIDs are186776,186779,186783,186788,186790,186798,
186802,186805,187077,187081,188468,188574,188580. All are256²/88580bytes with
nine exact declared mips, ending at EOF88580. Twelve use the existing BC2 color
profile `(encoding2,alphaDepth8,alphaEncoding1)` but mipFlags2;188574 uses BC3
`(2,8,7)` with mipFlags1. Every file declares a4² mip at offset88532/length16.
The strict default decoder correctly refuses mipFlags2 today; a production
thirteen-image decode has not yet passed and is an explicit prerequisite.

Root777947 header coordinates demonstrate another necessary distinction:
MCNK(1,0) anchors(nativeX0,nativeY-33.333984375), while MCNK(0,1) anchors
(-33.333984375,0). This differs from filename-tile axes. Root geometry row17 runs
along decreasing nativeX; its columns run along decreasing nativeY. Pair texture
records by validated source ordinal/root chunk provenance, not screen coordinates
or a guessed filename convention. Recorded MCCV contains145 BGRA values per chunk,
580bytes; sampled alpha is255 and RGB ranges39..183.

Private evidence: `native-facts2.log`, `acquired-texture-facts2.log`,
`alpha-summary.log`, `diffuse-header-facts.log`, audited Java/Python sources and
private root/tex0/BLP files under `/tmp/vimi-terrain-texture-proof`.

## Decoder API checkpoint: explicit handmade mips

Propose nested `Blp2Types.MipProfile { STRICT, DECLARED_HANDMADE_V1 }` and overloads
of the existing methods, with the profile as a final argument:

```java
readMips(AssetSource source, Limits limits, Cancellation token, MipProfile profile)
decode(AssetSource source, int mip, Limits limits, Cancellation token, MipProfile profile)
decodeLargestWithin(AssetSource source, int maxSide, Limits limits,
                    Cancellation token, MipProfile profile)
```

All old overloads delegate to STRICT. Existing `isMipFlags(int)`,
`allowsMipLevels(int)`, strict image payload guards, default outputs and C#
compatibility remain unchanged. The new profile additionally accepts **exactly2**,
not0x12/arbitrary nonzero flags, for the observed already-supported BC2 tuple
`content1/encoding2/alphaDepth8/alphaEncoding1`. Require a complete power-of-two
chain through1x1. Retain existing selected-mip payload checks and additionally
validate every handmade mip's exact size, metadata/source bounds, monotonic
nonoverlap and terminal EOF before either metadata return or decoding. The
current strict metadata reader does not already prove all those whole-chain
conditions; add them only for this opt-in flag2 path. No synthesized mip, header
mutation or relaxed color/BC decoder profile.
Other currently strict-admitted flags/tuples still work normally through either
profile. The returned Image retains raw mipFlags2, never rewrites it to1.

The terrain consumer selects the declared4² mip, with limits: source≤1MiB,
base dimension≤512, base pixels≤512², mip bytes≤1MiB and decodedRGBA≤1MiB. Source
profiles without that declared level are omitted rather than resampled from a
large image or invented. The actual 4² source mips avoid sampling a256² texture
at four isolated columns per repeated tile. That is a quality policy, not an
inference that hand-authored mips equal a generated box filter.

Do not feed raw-profile2 Image objects into the old strict persisted-image
payload. Persist only the new composed atlas recipe/output under its own policy;
bind decoder profile+revision, WDT/root/tex0/diffuse keys, source ordinals,
MCCV policy, chosen mips, masks, resolution and composition math in its identity.

## Exact first source admission and composition

A new optional texture metadata reader reuses the checked WDT/ADT chunk walk.
Keep existing geometry APIs/admission unchanged. It must obtain exact unique
MPHD32/MAID and preserve unsigned WDT flags; initial positive evidence is version18
andflags0x3ca. Do not add generic permission for unknown alpha layouts. Texture
service reads only positive recorded `tex0Id`, with a complete current source
receipt; no synthesized paths/IDs or sibling-file guesses.

Initial tex0: ≤8MiB, MVER18, one MAMP4 with value0, unique MDID/MHID arrays of
matching size≤64, MHID all0, no MTXP/MTEX, exactly256 texture MCNKs matching a
validated complete root16×16 grid. Each MCLY is1..8 records×16bytes. Base flags0;
overlay flags exactly0x100(raw4096) or0x300(RLE4096); texture index in the ordered
MDID table, all transforms/animation/height profiles outside this shape declined.
MCAL offsets are checked monotonic disjoint intervals, with exact4096 decoded
samples, nonzero RLE run lengths and exact consumed EOF per map. Reject overruns,
underruns, overlapping slices and unexpected tails before retaining a derivative.

Use direct eight-bit coverage: `wi=maski/255` for overlays and
`w0=(255-sum(maski))/255`. Any sum>255 declines the affected chunk, never silently
normalizes it. This differs from sequential source-over: red base, green mask128
and blue mask64 must yield byteRGB(63,128,64), not approximately(95,96,64).
Small/nibble alpha, edge-fix variants, compression outside this exact RLE, MTEX
names, positive MHID and MTXP height material parameters are separate profiles.

Atlas pixel centers define sampling. At chunk pixel(px,py) in0..31, local
normalized coordinates are `((px+.5)/32,(py+.5)/32)`; diffuse repeats eight times,
using nearest periodic sampling of the selected declared4² mip. Mask nearest
sampling selects raw64² coordinates(2px+1,2py+1). This explicitly defines the
coarse first preview rather than copying an unrelated exporter raster convention.

Proposed first lighting policy: **unlit diffuse with baked source vertex tint**,
plus the existing camera-dependent fog. MCCV, when present, must be exact145×4
BGRA with the reviewed opaque alpha shape; evaluate its RGB barycentrically on
the original source triangle at the atlas point, multiply by2/255, then clamp/
round the final atlas RGB once toRGBA8. Absent MCCV uses neutral multiplier1.
Do not apply the current green altitude tint to diffuse colors or imply client
lighting. Root/independent review must approve this explicit policy before code;
a directional-light extension is separate. Unsupported chunks use alpha0/RGB0;
admitted chunks alpha255. No partial-layer appearance is painted as complete.

## Presentation, wire and resources

Use an immutable `TerrainTileAtlas` with readonly pixel views and256 coverage bits,
keyed by exact root/tex0/recipe identities and validated native tile anchors.
At most18 unique atlas payloads are retained across the parent cache/current/latest
pending snapshots together, not18 per owner. Snapshot lists share immutable
payloads; replacing a pending scene releases its leases before allocating another.
Textures prepare on a separate bounded worker, never block geometry or FX, and
report files/chunks loading, limited detail, failures and capped coverage locally.
Selection/source/revision/close guards apply at each async handoff. Keep readable
height geometry while textures load. Coalesce replacements and allow first-frame
acceptance/terminal state before switching the latest pending scene; never spawn a
child per texture or restart a child for camera-only motion.

Keep the old16MiB geometry file/model protocol unchanged. Proposed optional
checksummed texture annex: header128bytes, at most9 descriptors×128bytes and
at most9×512²×4 pixels: **9438464bytes**, cap10MiB. Header binds exact geometry-file
SHA256 and atlas recipe. Each descriptor binds tile coordinates, source keys,
native origin/extent, coverage bits and checked payload range. Exact EOF, counts,
alpha/coverage consistency and aggregate products are validated before allocation.
The child receives only owned composed data, never filenames to arbitrary assets.
Old child calls without an annex retain the existing height-only behavior.

Existing persistent48-byte instance layout and at-most-nine per-tile draws can
remain. Bind one atlas and native-to-atlas uniform per tile; derive UV from the
selected source position relative to that tile's validated anchor, not a global
per-triangle texture-ID buffer. Add flat original-triangle fog as a varying;
fragment atlas alpha255 chooses atlasRGB plus fog, alpha0 chooses the untouched
existing height color. Use point/clamp atlas sampling, opaque output and existing
reversed depth/write rules. This needs no texture arrays, eight-layer GPU sampler
set or per-source-chunk native texture handles. Software uses the identical atlas,
UV, coverage, fog and fallback rules. Validate UV precision after origin rebasing.

Conservative child logical delta, in addition to the existing91353380-byte ledger:

| Texture term | Max bytes |
| --- | ---: |
| Nine immutable decoded atlases | 9437184 |
| One incoming atlas decode/copy scratch | 1048576 |
| Conservatively queued bgfx atlas copies | 9437184 |
| GPU atlas storage | 9437184 |
| Annex input buffer | 65536 |
| Source/binding/descriptor envelope | 65536 |

Combined logical ceiling is120844580bytes (~115.25MiB), leaving13373148bytes within
128MiB. This is not a measured JVM/driver bound. No simultaneous old/new child
contexts are admitted. In-process mutable replacement would need a new ledger.
The parent texture-pipeline proposal is48MiB: global18MiB atlas pool plus one8MiB
tex0 and bounded caller-copy peak, one1MiB BLP/copy, composition scratch, masks and
metadata. Existing geometry, file/network stores, JVM and Prism are independently
bounded; do not describe48MiB as the entire app. Measure every actual clone,
scene serialization and queued-upload lifetime before accepting this ledger.

## Stages and acceptance before delivery

1. Review this source/profile/API checkpoint. Implement opt-in handmade decoding
   only after approval, with authored BC2 exact chain and truncated/overlap/flag/
   wrong-tuple negatives. Strict old goldens/C# exchange remain unchanged. Decode
   all13 original images without byte rewriting and independently inspect output.
2. Texture/root grid facts and compositor: authored RLE/raw masks, wrong MDID,
   sum>255, missing/protected files, duplicate/mismatched source, MCCV channel/order,
   known red/green/blue oracle, asymmetric repeats/orientation and border cases.
   Actual three-tile atlases must report exact admitted/omitted chunk counts.
3. Independent CPU/native frames with analytic UV/fog/depth/coverage expectations,
   not only shared CPU/GPU agreement. Test nine-atlas resource peak, child errors,
   cancellation/source retirement, max-file corruption and old height-only calls.
   Regenerate/hash-verify all four shader profiles; actual Vulkan proof is required,
   Metal/D3D remain explicitly unexecuted until tested.
4. Measure first texture latency, camera latency, atlas quality, source/download
   bytes, cold/fresh restore and replacement churn; run real FX with delayed/error
   texture providers. Preserve geometry when optional texture work fails. No FPS
   or full-client appearance claim follows from this design.

## Pinned primary evidence and limits

- [wow.export ADTLoader4474fa4](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/ADTLoader.js): split/monolithic walks, ordered MDID/MHID, sixteen-byte MCLY and raw/RLE alpha selection.
- [Its WDTLoader](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/WDTLoader.js): all eight MAID fields.
- [Its old terrain fragment](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/shaders/adt.fragment.old.shader): direct weights/opaqueRGB; its CPU exporter instead uses sequential interpolation, so do not copy that conflicting math.
- [Noggit59e58add terrain fragment](https://github.com/wowdev/noggit3/blob/59e58add868608339fe292970196985d4ce050ff/src/glsl/terrain_frag.glsl) and [texture conversion](https://github.com/wowdev/noggit3/blob/59e58add868608339fe292970196985d4ce050ff/src/noggit/texture_set.cpp): big-alpha direct weights versus explicit old-alpha conversion. Its four-layer editor is not evidence for unbounded layer counts.
- [Whiteout35d068de BLP specification](https://github.com/FernandoS27/WhiteoutLib/blob/35d068dedde5cedff1c6bd2216de27dfe0af6bb6/docs/BLP_FILE_FORMAT_SPECIFICATION.md) and [parser](https://github.com/FernandoS27/WhiteoutLib/blob/35d068dedde5cedff1c6bd2216de27dfe0af6bb6/src/whiteout/textures/blp/parser.cpp): handmade mip flag2 and declared mip chains. This does not authorize its permissive fallback behavior in Vimi.

## Stage2 API checkpoint (after approved decoder dbcb658)

No implementation is authorized by this section alone. Target integration baseline
is the future world-appearance branch: all eight `Tile` companion fields already
exist there, along with `WorldPlacementSource`; do not alter their wire/constructors.
Root and domain approved the Stage1 decoder. Its full assets/C# checks and actual
thirteen-original public decodes pass, with independent BC2/BC3 block-byte parity;
that does not yet prove an atlas or renderer.

### Shared root grid facts

Add `WorldFormats.adtGrid(byte[], BooleanSupplier)` backed by the existing private
ADT walker. Existing `adt`/`adtAreas` calls keep their admission, output arrays,
source float arithmetic and allocation behavior. The new method returns immutable
`TerrainGrid(List<GridChunk>)`; it never reconstructs a grid from generic mesh
positions or invents an association with a different file.

`GridChunk` exposes source ordinal, recorded header x/y, raw flags, native float
originX/Y/Z,145 raw height offsets, normalized64-bit fine-cell holes, and optional
bounded MCCV facts. The source sample order remains17 staggered rows,9/8 columns;
`position(row,col)` uses exactly the current float position/height arithmetic.
Heights/colors expose readonly buffers backed by private defensive copies, not
mutable arrays or repeated whole-array clones. Grid metadata, including optional
colors and issues, is capped1MiB per tile. Source files are capped2MiB for this
first texture profile; the geometry reader's existing larger cap is unchanged.

Optional color state is `ABSENT`, `BGRA8` or `UNSUPPORTED`; duplicate/wrong-size
MCCV does not convert a valid height grid into different geometry. It declines
that chunk's texture appearance. Geometry truncation/invalid heights/duplicate
coordinate failures keep the existing walker behavior. An absent color has neutral
factor1; a present first-profile color requires145 BGRA tuples/alpha255.

First texture admission requires all256 unique root chunks in canonical source
order `x=ordinal%16,y=ordinal/16`; the texture stream's headerless ordinal is paired
only with that validated source order. Header x increments nativeY and header y
increments nativeX. Native anchors/heights are retained without welding; explicit
source-to-atlas mapping/large-coordinate precision tests remain required before
rendering. This API also supplies the LOD experiment's source-grid provenance,
without approving its reduction/error thresholds.

### WDT/tex0 facts and mask owner

Add a separate `WorldTextureFormats` class, keeping `WorldFormats.World` unchanged:

```java
Inventory inventory(byte[] wdt, BooleanSupplier canceled)
TextureData read(byte[] tex0, BooleanSupplier canceled)
// Inventory: existing immutable World plus exact unsigned MPHD flags/provenance.
// TextureData: AutoCloseable, private source bytes and immutable checked metadata.
```

The inventory method uses the checked WDT walk and all eight recorded MAID fields,
with extra profile facts for unique MPHD32. It does not infer a texture path.
`TextureData` exposes ordered MDID/MHID, MAMP and at most256 `ChunkTexture` metadata
records, each holding at most8 layer declarations and exact MCAL intervals.
This is optional appearance evidence; malformed outer chunk framing makes the
texture input unavailable while old geometry remains readable. Independently
bounded per-chunk unsupported flags/references yield typed declined-chunk reasons.

The owner defensively copies at most8MiB once; no raw slice/backing-array escapes.
`decodeAlphas(ordinal, ByteBuffer target, BooleanSupplier canceled)` writes into
caller-owned scratch for that chunk only, at most7×4096=28672bytes. It verifies
RLE/raw exact consumption and sum≤255 and returns the used mask count or typed
chunk decline. Closing releases its source reference and future decode calls fail.
Do not materialize all256×7 masks: that would add7340032bytes before clone costs.
A full-eight-layer authored tile must prove the streaming scratch bound. Layer
metadata and encoded source are retained only while composing one tile.

### Pure atlas composition

Add `TerrainAtlasComposer` and immutable `TerrainTileAtlas` in rendering-world;
this module consumes format facts/decoded RGBA data, not `BuildFiles` or a BLP
reader. Define a bounded `DiffuseMip` value with FileDataID, content key, original
mip profile/level/dimensions and readonly4² RGBA pixels. A map holds at most64
recorded diffuse IDs; missing/unavailable/unsupported entries remain explicit.
The service obtains these values through the approved decoder and authenticated
source receipts, not by trusting arbitrary metadata constructors as file proof.

```java
Composition compose(WorldFormats.Tile tile, TerrainGrid grid,
                    WorldTextureFormats.TextureData textures,
                    Map<Long, DiffuseMip> diffuse, BooleanSupplier canceled)
// Composition: atlas, supported/total chunks and immutable bounded reasons.
```

Composition performs no IO, acquisition, threads or persistence. It evaluates the
previously specified pixel-center/direct-weight/MCCV contract, produces exactly
512² RGBA bytes, and clears unsupported chunk rectangles to alpha0/RGB0. It does
not resize a missing mip or partially renormalize unavailable layers. `TileAtlas`
retains private pixels, validated native mapping and256 coverage bits; readonly
views are cheap and never expose its backing arrays. Constructor/caller-copy peaks
are counted explicitly. Snapshot lists share atlas objects and never clone pixels.

### Source service and ownership

`WorldTerrainTextureSource` in concepts-world borrows `BuildFiles` and optional
`GeneratedStore`; it owns its worker/cancellation/lifetime and leases an explicitly
shared atlas pool. Proposed entry:

```java
CompletableFuture<Result> read(MapInfo map, Tile tile, Cancellation token,
                               Consumer<Progress> progress)
CompletableFuture<Void> closeAsync()
// Result: state, bound Receipt, optional atlas, texture coverage and reasons.
```

Re-read/recheck WDT receipt first; the tile coordinates and every root/tex0 ID must
match its recorded tuple. Read/recheck the root and tex0 keys and each used diffuse
key, binding build/locale/map/tile/source ordinals, profile revisions and chosen
mips. Compare before/after stat identity; complete streams only, no protected holes.
A bounded read overload may take a stricter source-byte limit while all existing
`WorldFileBytes` callers retain their previous limit. Root bytes are released
before tex0 bytes are acquired; retain only compact grid facts. Read BLP through
bounded asset ranges so a4² decode does not retain its entire source image.

One source request and one replaceable pending request; cancellation drains the
actual active file before another begins or close completes. Progress is bounded
and emitted off FX. Geometry never waits for this worker. No provider refresh,
whole-map texture prefetch or filename guessing. The shared pool enforces eighteen
unique payloads across caches/current/pending owners; it is not a per-service18
allowance. If both snapshots pin every lease, discard obsolete pending work before
allocating more. Persistence stores only a recipe-bound atlas/coverage/reasons,
not raw-profile2 images in the old strict payload. Unknown/protected/missing source
receipts cannot become a persisted successful composition; readable partial
appearance remains available without claiming complete dependencies.

Parent48MiB is conditional on this ownership:18MiB unique atlas pool + at most
16MiB tex0 caller/owner copy peak +1MiB grid/metadata + bounded per-chunk masks,
4² image data, atlas construction/serialization scratch and cancellation overlap.
Any full-mask DTO clone, full-image accessor copy, extra independently retained
cache, or second active source invalidates that ledger and must be corrected or
reviewed explicitly before UI/child wiring. The source helper alone does not add
a new `BuildWorld` method or alter the GUI until its lifecycle/receipt tests pass.
