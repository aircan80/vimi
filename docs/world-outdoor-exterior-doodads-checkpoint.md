# BASIC exterior doodads, material classes and omission status (issue #172)

This slice builds on `world-outdoor-exterior-source-checkpoint.md` and
`world-outdoor-exterior-packet-checkpoint.md`. The World Explorer's late-delivered exterior
objects now include static M2 doodads and alpha-key and alpha-blended materials. The ready line
also says what was not drawn. Everything is still BASIC: a static bind pose, texture1 only, the
fixed world-wmo light and fog. It is not client lighting, animation or material parity.

## Materials

The material class is read from MOMT blend mode (buildings) or M2 material blend mode (doodads):

| Blend mode | Class | Drawn as |
| --- | --- | --- |
| 0 | opaque | depth-writing, alpha forced to 255 |
| 1 (alpha key) | cutout | depth-writing, alpha test at 0.5 (a BASIC reference, not the client's) |
| 2 (alpha) | blended | after water, no depth write, source-alpha over, farthest placement first |
| other | not drawn | counted as an unsupported batch |

- **Opaque parity.** Buildings keep the opaque-only profile's outcome exactly. Root caps (vertices,
  indices, ranges) are decided by opaque batches alone, and opaque ranges are resolved first,
  in recorded order, against the original 64-image and 1 Mi-pixel budget.
- **Material budget.** Cutout and blended building surfaces are then added from their own budget:
  32 images, 256 Ki pixels, 32,768 vertices (for groups with no kept opaque range), 98,304
  indices and 256 ranges. A surface that is missing, over that budget (`BATCH_CAPPED`) or
  unreadable (`BATCH_UNREADABLE`) omits only itself.
  - *Unreadable* means its image read was refused at `SOURCE_INPUT`: a partial, network or store
    state, or a short read.
  - Reads the opaque-only profile already made keep failing the whole set, as before.
- Opaque ranges force alpha, so an image used both opaque and with alpha is decoded twice, once
  from each budget.
- Blended placements are sorted by the distance of their origin to the camera, not per triangle.
- The `world-wmo` fragment shader gains `u_wmoMaterial` (x: the alpha-test reference, y: whether
  to output the sampled alpha). The cold BASIC_WMO pass sets it to zero, so its pixels are
  unchanged (`nativeGpuWorldWmoProof`).

## Doodads

**MDDF records** are taken in obj0 order. Each one needs:

- the direct FileDataID flag `0x40`, and no other flags besides `0x2`, `0x20`, `0x200` and
  `0x1000` (see [Pinned placement conventions](#pinned-placement-conventions));
- a positive scale/1024 of at most 64;
- a finite rotation. Pitch (rotation x) and roll (rotation z) are applied with the heading, as
  pinned below.

Identical duplicate unique IDs share one placement; conflicting ones are all omitted.

**WMO doodad sets**. For each drawn building, set 0 is drawn, plus its MODF doodad set when that
is nonzero. Entries are taken in MODD order.

- `WmoFormats` now retains MODS, MODI and MODD as raw declarations.
- Doodad declarations never decline a root. Any of these is simply not retained, and the root
  still parses exactly as it did before doodads were read:
  - a duplicate MODS, MODN, MODI or MODD;
  - a partial record;
  - an oversized declaration (more than 256 sets or 32,768 definitions);
  - a declaration over the fact budget.
- A MODD reference is an index into MODI. Name-only roots (MODN without MODI) are counted, not
  resolved.
- MODD flags are allow-listed like MDDF flags: only `0x1` (accepts projected textures, no
  transform) is admitted. Any other flag is counted as an unsupported set doodad.
- The full MODD quaternion is applied, as pinned below.
- Each entry's position goes through the building transform. Its rotation is the building heading
  times the quaternion's rotation, normalized in double; the quaternion must be within 1e-3 of
  unit length. Its scale is the building scale times the MODD scale, at most 64.
- Group membership (MODR) is not evaluated, so the doodads of interior groups are drawn too.

### Pinned placement conventions

These facts were pinned from sources, not guessed. The reference tree (the `-PreferenceRoot`
checkout) has no ADT or WMO placement code; its
`.local/references/m2-static` covers M2 and SKIN only. The facts therefore come from the primary
references this repository already pins in `world-placements-proposal.md`, at the same revisions,
plus the wowdev format pages at fixed revisions:

- TrinityCore `ec72c1914da121c07620dd5fe8ad084458dad9d1` (**TC**);
- wow.export `4474fa460eb9b1b6130994a42a232ecd6af64681` (**WE**);
- wowdev wiki [ADT/v18 rev 37119](https://wowdev.wiki/index.php?title=ADT/v18&oldid=37119) and
  [WMO rev 37115](https://wowdev.wiki/index.php?title=WMO&oldid=37115) (**wiki**).

Native axes are `x = 51200/3 - pos.z`, `y = 51200/3 - pos.x`, `z = pos.y` (the reviewed rule in
`WorldBasicWmo.Transform`), and model vertices stay in raw M2/WMO-local axes (z up).

| Fact | Convention | Sources |
| --- | --- | --- |
| MDDF `0x40` | `nameId` is a FileDataID, not an MMID index | wiki ADT/v18 `MDDFFlags` (`mddf_entry_is_filedata_id`); TC `adtfile.cpp:149` |
| MDDF `0x200` | Meaning **not pinned** (wiki: `mddf_unk_200`, "could be for large vegetation", unverified). No source uses it to choose the reference or the transform: TC selects the reference with `0x40` alone and extracts every record with the same transform (`adtfile.cpp:149-155`, `model.cpp:160-178`); WE draws every record with one matrix and reads no flag (`M2Renderer.js:984-1008`); the wiki matrix has no flag term. It is admitted on that ground, like `0x2` and `0x1000` before it. | as listed |
| Other MDDF bits | `0x1`, `0x4`, `0x8`, `0x10`, `0x80`, `0x100` stay refused (wiki: biodome or unknown) | wiki ADT/v18 `MDDFFlags` |
| MDDF scale | `scale / 1024`, uniform | wiki `SMDoodadDef.scale`; TC `model.cpp:163`; WE `M2Renderer.js:164` |
| MDDF rotation | Degrees; `R = Rz(180 + y) * Ry(x) * Rx(z)` in native axes, applied to raw model coordinates, so roll (z) is applied to the model first, then pitch (x), then the heading (y). `Rz(180 + y)` is exactly the reviewed heading rule. | TC: `Matrix3::fromEulerAnglesZYX(y, x, z)` (`ModelInstance.cpp:29`, `Matrix3.cpp:1664-1681`) in the frame `(pos.z, pos.x, pos.y)` (`wmo.h:68`), which is the native frame turned 180 degrees about up; TC keeps M2 vertices raw (`model.cpp:70`, `model.cpp:140-145`). wiki ADT/v18 "How to compute a matrix to map M2 to world coordinates" (`rotateY(y - 270)`, `rotateZ(-x)`, `rotateX(z - 90)`) gives the same matrix, and its axis table says rotation x turns about West/East and rotation z about North/South. |
| MODD quaternion | Components `(x, y, z, w)`, w real, in raw WMO-local axes; `building * T(position) * fromQuat(q) * S(scale)` | wiki WMO `SMODoodadDef` and "How to compute a matrix to map WMO's M2 to world coordinates"; WE `WMOLoader.js:274`, `WMORenderer.js:400-426` (it swizzles position and quaternion together into its viewer axes, which is the same rotation) and `WMORenderer.js:467`; TC `vec3d.h:75`, `model.cpp:250` (raw local position under the building rotation) |
| MODD scale | A float factor, multiplied with the building scale | wiki `SMODoodadDef.scale`; TC `wmo.h:62`; WE `WMOLoader.js:275` |

**Disagreement.** WE's map viewer builds `Ry(y - 90) * Rx(x) * Rz(z)` on its swizzled
`(x, z, -y)` vertices (`M2Renderer.js:156-195`, `M2Loader.js:641-643`). That agrees with the
heading rule, but it swaps the pitch and roll axes relative to TC and the wiki (about 0.17 apart
in a matrix element at 10 degrees). Two independent sources agree, and the wiki's axis table
agrees with them, so Vimi follows TC and the wiki. WE is not used for MDDF pitch or roll.
TC's set extraction composes `Quat * wmoRotation` and re-encodes it as Euler angles
(`model.cpp:252-259`); that collision path is not used as evidence for the composition order.

**Models**. A model is the M2 (chunked 272/274 or unchunked 264), its first SKIN, and texture1 of
each admitted batch. A batch is admitted when it has:

- material layer 0;
- at least one texture;
- texture1 sampled with UV0, as chosen by the batch's vertex shader (see below);
- a type-0 texture with a positive TXID;
- blend mode 0, 1 or 2;
- a section not already used by an earlier batch.

**Texture1 coordinates.** The vertex shader picked from the batch's shader ID and texture count
selects the coordinates, as in wow.export's `ShaderMapper.getVertexShader`. Texture1 uses UV0 when
that shader is a `Diffuse_T1*` one:

- not `0x8000`, not `0x80` (`Diffuse_Env*`), and not `0x4000` with one texture (`Diffuse_T2`); or
- a `0x8000` combiner whose `SHADER_ARRAY` vertex shader is `Diffuse_T1*`. `Diffuse_EdgeFade_*`,
  `Color_*` and IDs past the table are refused.

The names come from `M2Combiners.name` in `formats-assets`, the existing reviewed transcription of
that mapper, so there is one table. A shader ID with bits outside the named selection (`0x40f0`
with one texture, `0x40ff` with more) is refused, as `M2Combiners.admit` refuses it
(`M2Combiners.namedBitsOnly`). The vertex shader never depends on those bits. On the probed real
models only `0x0000`, `0x0010`, `0x8000` and `0x8001` appeared, and all of them pass.

Sources, all in the reference tree's `.local/references/m2-static`: `export-shadermapper.js:9-46`
(the table) and `:52-90` (the selection), `export-renderer.js:606` (how it is used), and
`formatlib-m2.cs:282` ("tex_unit_lookup_table unused as of cata"). #229 read that lookup table
instead. Real models declare it empty, so no real doodad batch was admitted; the private probe
below found 39 to 76 unsupported batches per tile and no drawn doodad on the base.

Further rules:

- Models with billboard bones are unsupported.
- Colour, transparency and UV animation tracks are not evaluated.
- A referenced zero normal makes the model unsupported.
- One mesh is built over the SKIN vertex lookup.

## Per-tile caps

Doodads have their own caps, so they never displace buildings:

| Doodads | Cap |
| --- | ---: |
| Placements (MDDF and set entries together) | 512 |
| Distinct models read (each with one SKIN) | 48 |
| Images read | 64, sides at most 128 (falling to 64) |
| Vertices / indices / ranges | 65,536 / 196,608 / 512 |
| Decoded pixels | 524,288 |
| Model / SKIN / image source | 2 MiB / 1 MiB / 1 MiB |

- The 4,096-draw budget now covers every placement and is applied in the source, in the order
  buildings, then MDDF doodads, then set doodads. The bridge repeats the check defensively.
- A building is admitted on its opaque-only cost, as the opaque-only profile did. Only a building
  that profile would not have drawn is charged all its ranges.
- If the drawn buildings' full cost then exceeds the budget, the cutout and blended ranges of
  buildings that also have opaque ranges are dropped, last root first, until it fits. They are
  counted as `BATCH_CAPPED`. A building the opaque-only profile drew is never capped.
- `WorldStreamScopes.UseProfile.BASIC_EXTERIOR` gains the M2_MODEL, M2_SKIN and M2_IMAGE derived
  kinds, and its binding limit grows to 506.
- Caps never split a model.

## Source refusals

Every read the opaque-only profile made keeps the slice 1 policy: anything but a missing,
protected or oversized source fails the whole set. A cutout or blended building image refused at
`SOURCE_INPUT` omits only its surfaces (see Materials). A doodad source refused at
`SOURCE_INPUT` (a partial, network or store state, or a short read) omits only its doodads
instead. It is counted as `DOODAD_SOURCE_INPUT`, in
addition to that read's unavailable bucket, so decoration never costs a tile its buildings.

Any other runtime failure while reading or copying a doodad M2 or SKIN is counted as
`MODEL_FAILED` and omits only that model's doodads; this tile's own cancellation still
propagates. A source that changes during or after a read (`SOURCE_BINDING`) still fails the whole
set.

## Objects file version 2

`tile-<txn>.wso` keeps its magic and moves to version 2.

- **Ranges** carry a fourth word, the material class, validated to 0..2.
- **Placements** carry x, y, z, a scale and a row-major 3x3 rotation, 112 bytes each. The rotation
  must be orthonormal within 1e-6 with determinant +1.
- **Caps** are the opaque building, material and doodad caps: 72 models, 304 meshes, 1,792
  ranges, 576 placements, 160 images, 196,608 vertices, 589,824 indices and 1,835,008 pixels.
- **Ceiling**: 16 MiB (the maximum profile is about 14.9 MB).

## GPU and heap memory budget

The hot child holds `SLOT_COUNT` = 10 slots (9 active and 1 staged). Each slot carries at most one
validated objects file.

| Per slot, worst case | Bytes |
| --- | ---: |
| Vertices (196,608 x 32) | 6.3 MB |
| Indices (589,824 x 2) | 1.2 MB |
| RGBA8 textures (1,835,008 x 4) | 7.3 MB |
| Total (maximum profile) | about 14.9 MB |

- The object GPU budget is therefore about **150 MB** over 10 slots, up from about 79 MB before
  this slice.
- The decoder worker and the upload hold at most one more file's worth of heap memory at a time.
- The bound is enforced by the file caps and the reader's count checks, and the renderer charges
  it in `Stats.objectBytes`. It is not checked against the device's memory.
- It is acceptable for the desktop GPUs this targets. Real tiles stay far below it: 3 to 10
  buildings and 3,478 to 18,582 vertices per tile were measured in slice 2.
- **Frame stack.** The renderer allocates the object uniform buffers once per frame
  (`BgfxWorldStreamRenderer.ObjectFrame`). Per-placement work allocates nothing on the LWJGL frame
  stack, whatever the number of placements: up to 10 x 576 placements, each drawn in two passes.

## Creatures (#226)

Opt-in NPC creature models (`-Dvimi.world.creatures=true`) are appended after buildings and
doodads, into the same version 2 file.

- They share whatever room the file profile still has: the builder's `fits`, `placementRoom` and
  `drawRoom` now report the version 2 caps. They draw as opaque ranges with the identity heading.
- They keep #226's isolation: any creature problem rebuilds the buildings and doodads alone, and
  reports only the creatures failed.
- The ready line keeps #226's creature detail after the building and doodad counts.

## Status

`ExteriorSummary` carries each attached tile's drawn and omitted counts, and the ready line reads,
for example:

    5 BASIC exterior buildings and 512 doodads on 1 (not drawn: 12 buildings, 876 doodads (847 capped), 54 surfaces)

Omitted buildings and doodads are records that were not drawn for any reason. *Capped* doodads
are the omitted ones that qualified but did not fit the per-tile doodad, model or draw caps
(`DOODAD_CAPPED`, `SET_DOODAD_CAPPED` and the bridge's own draw-budget check). Nothing is counted
as tilted any more: `DOODAD_TILTED` and `SET_DOODAD_TILTED` are gone. Omitted surfaces are
unsupported, image-less, capped or unreadable batches. The GPU status label's tooltip carries
the BASIC disclosure.

## Validation

- **Unit tests.** `WorldExteriorObjectsTest` adds 10 authored tests (25 in total). They use authored
  chunk streams and an authored chunked 274 M2 and SKIN, with no game assets. They cover:
  - MDDF flags, scale and duplicates, against the heading-rule oracle;
  - model blend classes and alpha keeping;
  - WMO cutout alpha;
  - model failures (unsupported version, truncation, missing SKIN);
  - the doodad and model caps;
  - doodad-set composition (set selection, MODI references, quaternion composition);
  - doodad input refusals versus changed sources;
  - an unreadable cutout or blended image omitting only its surface;
  - cutout geometry never capping the opaque budget;
  - cutout ranges dropped, never a building, at the 4,096-draw boundary;
  - MODD flag rules.
- The tilt slice adds 3 tests (28 in total) and updates 2. The oracles are written from the cited
  conventions, never from `Rotation`:
  - `mddfPitchRollAndHeadingFollowThePinnedPlacementMatrix` transcribes the wowdev ADT/v18
    placement matrix call by call (gl-matrix style) and maps five local points of each placement.
    It covers `(17.5, -121.25, 33)` and `(-8, 405, -71)` with flags `0x240` and `0x40`, pure
    pitch and roll against the wiki's axis table, and bit-identical heading-only matrices. `0x100`
    and name references stay unsupported.
  - `moddQuaternionsWithPitchRollAndYawComposeLikeThePinnedPlacementMatrix` transcribes the wowdev
    WMO MODD matrix (`building * T * fromQuat * S`) for a 50-degree turn about `(1, 2, -0.5)` and
    a yaw-30, pitch-20, roll-10 product, under a heading-37.5, scale-2 building.
  - `texture1CoordinatesComeFromTheVertexShaderNotTheUnusedLookupTable` checks the shader rule
    and that the authored model with an empty lookup table still draws.
  - Negative control: applying roll before pitch in `Rotation.mddf` fails the MDDF oracle test.
- `WorldStreamObjectsFileTest` draws a full frame of 10 x 576 placements twice through the
  per-frame uniform buffers, checking that the frame stack never grows. A negative control shows
  the old per-placement allocation running out of stack.
- `WmoFormatsTest` covers duplicate and partial doodad chunks on an otherwise valid root.
- `WmoFormatsTest` covers the raw doodad declarations, partial records and budget fallback.
- `WorldStreamObjectsFileTest` covers the version 2 maximum profile, range classes and the
  rotation checks.
- **Native proof.** `nativeGpuWorldStreamObjectsProof` launches the real hot child on Vulkan with
  an authored terrain tile and late objects: three camera-facing quads, plus enough placements
  behind the camera to fill the tile's 576-placement cap.
  - The cutout quad, whose texels are all transparent, leaves the background pixel unchanged.
  - The opaque quad gives its exact texel colour.
  - The blended quad matches source-alpha over within 1 unit.
  - With the alpha test disabled, the proof fails ("cutout discards: 10 != 195").

### Real data (tilt slice)

A private probe ran `WorldTerrainStreaming.prepareExteriorObjects` and the real hot child over a
reflink copy of the local library and a copy of the #229 warm cache. Nothing from it is committed.
The repository owner allowed CDN downloads for this check. The missing doodad M2, SKIN and BLP
sources for Azeroth 32..34 x 31..33 and the Elwynn tile 47,30 were fetched through the app's
normal `CachingObjectSource` into that private cache, never into the live library. The counts
below are a local-only rerun over the fetched cache, on the branch base (eac813fe) and on this
branch.

| Tile | Base: unsupported (all `0x200`) / tilted MDDF, tilted MODD | Base drawn | Branch drawn MDDF + set | Of which tilted (MDDF and set) / `0x200` | Branch capped MDDF / set |
| --- | --- | ---: | ---: | --- | --- |
| 32,31 | 231 / 124, 57 | 0 | 291 + 0 | 40 / 225 | 278 / 293 |
| 32,32 | 307 / 70, 63 | 0 | 512 + 0 | 20 / 294 | 271 / 277 |
| 32,33 | 363 / 293, 56 | 0 | 421 + 0 | 41 / 363 | 394 / 290 |
| 33,31 | 178 / 84, 258 | 0 | 428 + 5 | 23 / 177 | 90 / 382 |
| 33,32 | 241 / 132, 295 | 0 | 512 + 0 | 12 / 232 | 342 / 591 |
| 33,33 | 220 / 201, 19 | 0 | 512 + 0 | 56 / 218 | 381 / 466 |
| 34,31 | 27 / 110, 0 | 0 | 160 + 0 | 67 / 27 | 68 / 0 |
| 34,32 | 3 / 23, 0 | 0 | 70 + 0 | 23 / 2 | 0 / 0 |
| 34,33 | 43 / 13, 0 | 0 | 169 + 0 | 10 / 42 | 0 / 0 |
| 47,30 | 56 / 90, 8 | 0 | 288 + 18 | 99 / 56 | 0 / 80 |

- **No MDDF record is unsupported or tilted any more.** Every unsupported record carried only
  `0x200` beside admitted flags.
- **Buildings are unchanged.** Drawn and omitted placements, batches and images are identical
  between base and branch on every tile.
- **Base drew no doodad**, because of the texture-coordinate rule above.
- **Caps now bind.** The 512-placement doodad cap is reached on 3 tiles. MDDF records come first,
  so set doodads are mostly capped. Up to 43 of the 48 model reads and all 64 model-image reads
  are used.
- **Memory.** The file caps are unchanged, so the worst case stays about 14.9 MB per slot and
  about 150 MB over 10 slots. The real tiles above reach at most 21,019 vertices, 44,232 indices
  and 1,605,632 pixels per file, about 7.2 MB (about 72 MB over 10 slots).
- **Slope check.** On the 7 tiles with MDDF records that are tilted on terrain steeper than
  8 degrees (360 records), the doodad up axis was compared with the terrain normal under each
  candidate convention. The pinned one is closest: the median angle is 10.2 to 22.1 degrees per
  tile. WE's swapped axes give 16.1 to 31.5 degrees, and the pinned one wins on 262 of the 360
  records. Negating pitch, roll or both is worse on every tile. The other Euler order is within
  0.1 degree at these small angles, so the data cannot separate it. This corroborates the
  sources; it is not used as one.
- **GPU frames.** The real hot child rendered 800 x 500 frames of tiles 33,33 and 47,30 on Linux
  Vulkan, through `vimi-heavy --gpu`. Trees stand upright on slopes and rocks sit on the ground.
  Fallen logs lie along the ground as authored, and nothing floats or lies on its side. Status
  line: `5 BASIC exterior buildings and 512 doodads on 1 (not drawn: 12 buildings, 876 doodads
  (847 capped), 54 surfaces)`.

### Real data (#229)

A private run used `WorldStreamSourceController`'s preparer on Azeroth 32..34 x 31..33. The source
was local-only, over a copy of the existing warm cache; there was no network and no CDN.

- **Buildings draw again on every tile with object sources** (8, 4, 7, 3, 6 and 5 on the 6 tiles).
  - The same probe against an opaque-only build of the branch base (33bbc563) gives identical
    drawn and omitted building counts.
  - It also gives identical opaque batches (42, 27, 44, 31, 37, 31), missing-image batches, drawn
    groups and decoded images.
  - The 1 to 3 cutout or blended batches per tile whose textures are not in that cache are now
    counted as unreadable instead of failing the tile.
- The 3 tiles at x = 34 fail as they do on main: their opaque object sources are not cached.
- 178 to 363 MDDF records per tile are unsupported. All 1,540 of them carry flag `0x200`, whose
  meaning is not pinned, so they stay counted, not drawn.
- 70 to 293 per tile are tilted.
- No doodad M2 is in that cache, so every doodad model was unavailable. Each counted against the
  48-model cap, and the remaining doodads were capped.

  Doodad pixels on real data have therefore not been seen.

## Not done

- **Cold-cache preemption timing (follow-up).**
  - Doodads add up to about 150 derived reads per tile. That lengthens the time a preempted
    preparation needs to drain before terrain can start.
  - The time is still bounded, and terrain never waits for a completed objects result.
  - On a cold cache with a moving camera, though, objects may be preempted repeatedly.
  - This has not been measured.

- **The meaning of MDDF `0x200`** is not pinned (the wiki guesses large vegetation). It is
  admitted only because no source lets it change the reference or the transform. If it turns
  out to be, for example, a draw-distance or LOD hint, nothing here models that.
- **Other MDDF bits** (`0x1`, `0x4`, `0x8`, `0x10`, `0x80`, `0x100`) and MODD flags other than
  `0x1` stay refused. None of these MDDF bits appeared on the probed tiles.
- **Set doodads are starved by the shared caps.** MDDF records take the 512 placements and most of
  the 48 models first. A fairer split, or larger caps with a new file profile, is a follow-up.
- **Interiors.** MODR group membership, portals and interior lighting.
- **Client parity.** Client alpha references and per-layer M2 shaders.
- **Animation.** Colour and transparency tracks and animation.
- **Other blend modes** (additive, modulate) are not drawn.
- **Backends.** DX11 and Metal were not run. The DX11 `world-wmo` binaries come from the Windows
  shader job.
- **A committed real-data visual proof of doodads.** The private frames above are not committed;
  a repeatable one needs the doodad sources, and fetching them needs
  network access.
