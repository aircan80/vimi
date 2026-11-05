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
- Declarations that are oversized (more than 256 sets or 32,768 definitions) or over the fact
  budget are not retained, and the root still parses.
- A MODD reference is an index into MODI. Name-only roots (MODN without MODI) are counted, not
  resolved.
- Each entry's position goes through the building transform. Its rotation is the building heading
  times the MODD quaternion, normalized in double; the quaternion must be within 1e-3 of unit
  length. Its scale is the building scale times the MODD scale, at most 64.
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
- `WorldStreamScopes.UseProfile.BASIC_EXTERIOR` gains the M2_MODEL, M2_SKIN and M2_IMAGE derived
  kinds, and its binding limit grows to 506.
- Caps never split a model.

## Source refusals

Every read the opaque-only profile made keeps the slice 1 policy: anything but a missing,
protected or oversized source fails the whole set. A cutout or blended building image refused at
`SOURCE_INPUT` omits only its surfaces (see Materials). A doodad source refused at `SOURCE_INPUT` (a partial, network or store state,
or a short read) omits only its doodads instead. It is counted as `DOODAD_SOURCE_INPUT`, in
addition to that read's unavailable bucket, so decoration never costs a tile its buildings. A
source that changes during or after a read (`SOURCE_BINDING`) still fails the whole set.

## Objects file version 2

`tile-<txn>.wso` keeps its magic and moves to version 2.

- **Ranges** carry a fourth word, the material class, validated to 0..2.
- **Placements** carry x, y, z, a scale and a row-major 3x3 rotation, 112 bytes each. The rotation
  must be orthonormal within 1e-6 with determinant +1.
- **Caps** are the opaque building, material and doodad caps: 72 models, 304 meshes, 1,792
  ranges, 576 placements, 160 images, 196,608 vertices, 589,824 indices and 1,835,008 pixels.
- **Ceiling**: 16 MiB (the maximum profile is about 14.9 MB). Nine resident tiles plus one staged
  tile could therefore hold about 150 MB of object GPU memory in the worst case. Real tiles stay
  far below this.

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

- **Unit tests.** `WorldExteriorObjectsTest` adds 9 authored tests (24 in total). They use authored
  chunk streams and an authored chunked 274 M2 and SKIN, with no game assets. They cover:
  - MDDF flags, tilt, scale and duplicates, against the heading-rule oracle;
  - model blend classes and alpha keeping;
  - WMO cutout alpha;
  - model failures (unsupported version, truncation, missing SKIN);
  - the doodad and model caps;
  - doodad-set composition (set selection, MODI references, quaternion composition);
  - doodad input refusals versus changed sources;
  - an unreadable cutout or blended image omitting only its surface;
  - cutout geometry never capping the opaque budget.
- `WmoFormatsTest` covers the raw doodad declarations, partial records and budget fallback.
- `WorldStreamObjectsFileTest` covers the version 2 maximum profile, range classes and the
  rotation checks.
- **Native proof.** `nativeGpuWorldStreamObjectsProof` launches the real hot child on Vulkan with
  an authored terrain tile and late objects: three camera-facing quads.
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

- **Tilted MDDF doodads.** The pitch and roll convention is not pinned against a reviewed oracle.
- **The MDDF `0x200` flag** is not interpreted.
- **Interiors.** MODR group membership, portals and interior lighting.
- **Client parity.** Client alpha references and per-layer M2 shaders.
- **Animation.** Colour and transparency tracks and animation.
- **Other blend modes** (additive, modulate) are not drawn.
- **Backends.** DX11 and Metal were not run. The DX11 `world-wmo` binaries come from the Windows
  shader job.
- **A real-data visual proof of doodads.** It needs the doodad sources, and fetching them needs
  network access.
