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

- the direct FileDataID flag `0x40`, and no other flags besides `0x2`, `0x20` and `0x1000`;
- a positive scale/1024 of at most 64;
- zero pitch and roll. Only the reviewed ADT heading rule (`WorldBasicWmo.Transform`) is applied.

Records with a nonzero pitch or roll are counted as *tilted*, not drawn. Identical duplicate
unique IDs share one placement; conflicting ones are all omitted.

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
- MODD rotations follow the MDDF rule. Only a rotation about local z is applied (quaternion x and
  y exactly zero). Entries with pitch or roll are counted as `SET_DOODAD_TILTED`, shown as tilted
  in the status, and not drawn, because no reviewed convention is pinned for them.
- Each entry's position goes through the building transform. Its rotation is the building heading
  times that z rotation, normalized in double; the quaternion must be within 1e-3 of unit length.
  Its scale is the building scale times the MODD scale, at most 64.
- Group membership (MODR) is not evaluated, so the doodads of interior groups are drawn too.

**Models**. A model is the M2 (chunked 272/274 or unchunked 264), its first SKIN, and texture1 of
each admitted batch. A batch is admitted when it has:

- material layer 0;
- at least one texture;
- UV0;
- a type-0 texture with a positive TXID;
- blend mode 0, 1 or 2;
- a section not already used by an earlier batch.

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

    8 BASIC exterior buildings and 120 doodads on 7 (not drawn: 3 buildings, 41 doodads (12 tilted), 9 surfaces (2 unreadable))

Omitted buildings and doodads are records that were not drawn for any reason. Omitted surfaces are
unsupported, image-less, capped or unreadable batches. The GPU status label's tooltip carries
the BASIC disclosure.

## Validation

- **Unit tests.** `WorldExteriorObjectsTest` adds 10 authored tests (25 in total). They use authored
  chunk streams and an authored chunked 274 M2 and SKIN, with no game assets. They cover:
  - MDDF flags, tilt, scale and duplicates, against the heading-rule oracle;
  - model blend classes and alpha keeping;
  - WMO cutout alpha;
  - model failures (unsupported version, truncation, missing SKIN);
  - the doodad and model caps;
  - doodad-set composition (set selection, MODI references, quaternion composition);
  - doodad input refusals versus changed sources;
  - an unreadable cutout or blended image omitting only its surface;
  - cutout geometry never capping the opaque budget;
  - cutout ranges dropped, never a building, at the 4,096-draw boundary;
  - MODD flag and tilt rules.
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

### Real data

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

- **Tilted MDDF and MODD doodads.** The pitch and roll conventions are not pinned against a
  reviewed oracle.
- **The MDDF `0x200` flag** is not interpreted.
- **Interiors.** MODR group membership, portals and interior lighting.
- **Client parity.** Client alpha references and per-layer M2 shaders.
- **Animation.** Colour and transparency tracks and animation.
- **Other blend modes** (additive, modulate) are not drawn.
- **Backends.** DX11 and Metal were not run. The DX11 `world-wmo` binaries come from the Windows
  shader job.
- **A real-data visual proof of doodads.** It needs the doodad sources, and fetching them needs
  network access.
