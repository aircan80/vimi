# Qualified idle animation for model previews: first slice

Tracks #183. This slice adds an optional looping **Stand** (animation ID 0, variation 0)
idle to the Mounts model preview when the selected display's source passes a narrow,
reviewed profile. The static rest preview, its cache and its material admission are
unchanged and remain the default. World previews are not changed: World does not render
M2 doodads or creatures yet, so there is nothing to animate there until that lane lands.

## What is evaluated

`M2IdleAnimation` (formats-assets) reads, for an already parsed chunked 272/274 model:

- the global-sequence clocks, the sequence table and the bone table, cross-checked
  against the parsed header and bone metadata;
- the first sequence with ID 0 / variation 0. It must not be an alias (flag 0x40) and
  must store its keys in the model (flag 0x20). Durations must be 1–600,000 ms;
- each bone's translation, rotation and scale track for that sequence (or slice 0 for a
  global-sequence track). Only step (0) and linear (1) keys are admitted; Bezier (2) and
  Hermite (3) are refused. Key times must be ordered and within their clock. Compressed
  quaternions use the client format `(v < 0 ? v + 32768 : v - 32767) / 32767`.

Budgets: at most 256 bones (vertex influences are 8-bit direct bone indices), 4,096
sequences, 1,024 global clocks, 4,096 keys per track and 262,144 keys in total. The Stand
itself must last at most 600,000 ms. A global clock loops on its own length and is bounded
only by the `int` key-time range (`PreviewAnimation.MAX_GLOBAL_MILLIS`), because character
faces loop on longer clocks (Undead male: 1,079,000 ms).

Refused as `MODEL_UNSUPPORTED`, with the message shown in the preview: external skeleton
(SKID) or bone files (BFID), missing Stand, alias Stand, Stand keys in an `.anim` file,
curve interpolation, out-of-range duration. Structural disagreement is `MODEL_INVALID`.

`DisplayIdleAnimations` (concepts-models) then admits the sequence for the compact preview
surface. Only bones that move a preview vertex, plus their ancestors, are inspected:

- parent-inheritance flags 0x1/0x2/0x4 and cylindrical billboard flags 0x10/0x20/0x40 on
  that chain are refused, because their client semantics are not implemented;
- a spherical billboard bone (exactly 0x8 among those bits) is admitted only as a
  childless leaf without Stand keys whose vertices are rigidly bound to it (weight 255).
  Its members keep their rest offsets around the bone's evaluated pivot, so the existing
  camera-facing rigid-card renderer keeps orientation. Card size is not rescaled by
  ancestor scale;
- flags on bones that move no vertex (attachments, emitters) do not matter;
- a Stand sequence that keys no bone is reported unsupported and the rest pose stays.

`PreviewAnimation` (rendering-models) is the immutable evaluator: local transform
`T(pivot + translation) · R(rotation) · S(scale) · T(−pivot)`, composed under the parent in
dependency order (storage order need not be topological; cycles are refused), linear
blend skinning of up to four influences summing to 255, shortest-path slerp, keys held
before the first and after the last key, sequence time looping on the Stand duration and
global tracks on their own clock. Normals are rotated and keep their source length.
Framing bounds are the union of the rest bounds and 96 samples across the loop, so the
camera does not breathe with the pose. `matches` requires the exact rest positions (and
normals for an appearance) the influences were prepared for; a restored cached preview
built from the same source matches, a different surface is refused.

Not evaluated: blending between sequences, IK, attachments, particles/ribbons, texture
or colour tracks, events, ancestor-scale on cards, external skeletons and `.anim` files.

## Service and UI

`BuildDisplayModels.idle(displayId, token)` and `BuildMounts.idle(mountId, variant, token)`
are new default methods (UNAVAILABLE by default), so existing implementations and callers,
including the NPC lane, keep compiling unchanged. `DisplayModelService` runs the idle
request on the same bounded single worker as previews, re-reading the model and LOD0 SKIN
so influences map through the exact same vertex lookup. Results are `AVAILABLE`,
`UNSUPPORTED` (readable source outside the profile, with reason) or `UNAVAILABLE`
(relationship/source unreadable). Idle results are not persisted in this slice.

`MountsView` requests idle only after the static appearance is shown, with the same
selection generation and cancellation token as the preview. `ModelPreviewView` shows an
**Idle animation** check box only when the delivered animation matches the surface on
screen. Unsupported sources show a one-line reason instead of an inert control; an
unavailable result shows nothing. Playback evaluates the pose off the FX thread and uses
the existing software renderer at most every 40 ms, only while the viewport is visible
and no frame is rendering. The GPU path is closed while playing and resumes when playback
stops. Races previews offer Stand for models that store their own bones, and explain why
for the others (see "Races" below).

## Actual source evidence

Read-only Forever 1.60.1.70755 enUS, original library plus earlier private acquired
stores, `CachingObjectSource.localOnly`, no network and a fresh private generated store.
Private probes and images: `HOME/.cache/vimi-tmp/models-idle-proof/` (`audit-all.log`,
`render.log`, `rest-*.png`, `idle-*.png`, `all-sheet-*.png`). No game asset is committed.

All 141 readable Mount rows were walked through MountXDisplay → CreatureDisplayInfo →
CreatureModelData. Of 36 distinct mount models, 24 are not stored locally and 3 are
encrypted (IV length unsupported). The other 9 all store Stand keys in the model with only
step/linear keys; one of them has a SKIN surface the existing static reader rejects:

| Display | Model FDID | Stand index / ms | Keys | Chain flags | Idle |
| --- | --- | --- | --- | --- | --- |
| 1166 Arctic Wolf | 123418 | 0 / 3333 | 1841 | none | AVAILABLE |
| 2784 Black Ram | 125546 | 1 / 2667 | 1094 | none | AVAILABLE |
| 145022 Black Skeletal Horse | 126304 | 1 / 4000 | 697 | none | AVAILABLE |
| 145671 Spectral Bear | 7704362 | 3 / 2000 | 1540 | none | AVAILABLE |
| 146777 Brown Prideclaw | 7749921 | 0 / 2000 | 2066 | 0x8 leaves 44, 45 | AVAILABLE |
| 14348 Black War Kodo | 124696 | 3 / 2667 | 1790 | 0x8 leaves 17, 18 | AVAILABLE |
| 14388 Black War Raptor | 125649 | 4 / 2000 | 2133 | 0x8 leaves 36, 37 | AVAILABLE |
| 143799 Empyrean Galestrider | 7704354 | 18 / 2000 | 1301 | 0x8 leaves 32, 33 | AVAILABLE |
| 14337 Black War Steed | 125620 | 1 / 4000 | 1558 | 0x8 leaves 24, 25 | static surface rejected |

Several of these models also carry 0x4 bones that move no vertex; they are correctly
ignored. Idle qualification took 57–669 ms cold (dominated by model/SKIN reads), pose
evaluation 0.2–4 ms for 758–3,824 vertices, and every restored cached preview matched its
animation exactly. Rest and posed CPU frames were inspected for the wolf, raptor, bear and
Prideclaw: limbs, heads and tails stay attached and move plausibly; nothing explodes,
which also confirms the quaternion decoding and composition order.

All ten sampled playable-race body models (Human, Orc, Dwarf, Tauren, Night Elf) declare
BFID bone files (10–30 each) and are refused. Character idle needs a reviewed meaning of
those files before Races previews can animate.

## Races: Stand for models with their own bones

Tracks #104. A race model that names no external skeleton (SKID) and no bone files (BFID),
and stores its Stand keys itself, now offers the same looping Stand as Mounts. Every link is
recorded data: ChrRaceXChrModel → ChrModel → CreatureDisplayInfo → CreatureModelData → M2,
the M2's own bones and Stand tracks, and LOD0 SKIN for the vertex lookup. Nothing is
inferred from the race or model name.

- `BuildModels.readIdle` re-reads the model through `M2IdleAnimation.read`, which still refuses
  SKID and BFID. Its result also supplies the Stand facts, so the model is read once.
- `RaceRestSurface` builds the rest surface (positions, normals, UVs) from a SKIN. The static
  preview, the textured sample and the idle all use it, so the idle matches the surface on
  screen. The static preview reads `readRestSkin` and the textured sample `readRestDrawSkin`;
  on the Undead male source both give the same vertex lookup, triangles and sections.
- `DisplayIdleAnimations` (concepts-models) is now public, and `concepts-races` depends on
  `concepts-models`, so race and mount models pass the same bone-flag admission.
- `RaceMediaTypes.Idle` gains `READY` with the prepared `PreviewAnimation`. A bone-flag or
  evaluator refusal is `UNSUPPORTED` with its reason. A SKIN that cannot be read is
  `UNAVAILABLE` (it names the SKIN FileDataID) and is assessed again on the next request.
- Cached results are now also checked against the SKIN's content key. At most four prepared
  animations stay in the 32-entry cache; refusals are small and stay longer.
- `RaceModelPanel` hands a READY result to `ModelPreviewView.setAnimation`, which shows the
  **Idle animation** toggle only when the animation matches the static mesh or the textured
  sample (positions and normals). The Stand facts appear under "Model source details".

Actual Forever 1.60.1.70755 enUS, read-only and local only (no network, nothing downloaded).
The library plus the private stores from earlier probes were read; the probe and its images
are in `HOME/.cache/vimi-tmp/race-stand-proof/` (`run2.log`, `run3.log`, `sheet-9.png`). No
game asset is committed. 116 ChrRaceXChrModel variants were assessed:

- Undead male (ChrModel 9, M2 959310, SKIN 959362) is READY: Stand is sequence 2, 2,667 ms,
  17,111 keys on 170 bones, bound to 44,939 rest vertices with 226 bones evaluated. Its face
  bones use a 1,079,000 ms global clock, which the old 600,000 ms global bound refused. The
  animation matched both the freshly derived and the restored static mesh. A cold assessment
  took 1.0 s, a repeated one 39 ms, and a pose 1.1–1.3 ms. CPU frames from the front and side
  show the bind A-pose changing into the hunched Undead stance. Limbs, hands and head stay
  attached and nothing explodes. The textured sample was not available locally, so its match
  was checked only through the identical SKIN lookup.
- Worgen (ChrModel 43/44) and 36 other ChrModels (Goblin female, Pandaren, Vulpera, Fel Orc,
  Vrykul, Naga, the drake forms and others; 52 rows counting races that share a model) pass
  every model check, but their LOD0 SKIN files are not stored locally. They report
  `UNAVAILABLE`: "The model's rest surface (SKIN FileDataID …) could not be read: Object
  is not stored". Their static preview is unavailable for the same reason. They were not
  downloaded, so their binding and bone flags are unproven on actual data.
- Algarian Stormrider (ChrModel 186) has 260 bones and stays `UNSUPPORTED` (at most 256
  directly indexed bones).
- BFID and SKID variants keep the refusals below, unchanged.

## Races: bone files and refusal reporting

Races previews say exactly why Stand is not offered for a model that is not offered.

`M2BoneFile` (formats-assets) reads one BFID-declared bone file exactly: `uint32` version 1,
then `BIDA` (`uint16` model bone indices) and `BOMT` (one 64-byte 4x4 float matrix per index).
Each chunk must appear exactly once, in either order, and the chunks must fill the file
exactly. Indices must be unique and inside the model's bones, values must be finite, and every
matrix must have `(0, 0, 0, 1)` at file positions 3/7/11/15, with translation at 12–14. Other
versions, unknown chunks and other matrix forms are `MODEL_UNSUPPORTED`. Structural
disagreement is `MODEL_INVALID`. Limits are 1 MiB and 4,096 entries per file. The reader
records facts only; it does not say which file applies or how.

`BuildModels.readBoneFile` reads only IDs that the read model declares in BFID, and checks the
size before it opens anything. `M2IdleAnimation.assessStand` runs every Stand check of the
mount reader except the external-file gate and returns facts without tracks, so a result
can never be played back by mistake. `BuildModels.readStandFacts` wraps that check.

`BuildRaces.idle(raceId, chrModelId, token)` is a new default method (UNAVAILABLE). It runs
on the race worker. It returns `UNSUPPORTED` with every blocking reason, or `UNAVAILABLE` if
the relationship or the model could not be read. The reasons are:

- an external skeleton (SKID). When the model has no bones of its own, Stand is not read;
- BFID bone files (at most 64), each read and validated, with counts of the files validated,
  refused and unreadable. Even when every file validates, Stand is refused: which file the
  client applies for an appearance, and how its matrices combine with Stand keys, have not
  been reviewed;
- a Stand that the mount reader would refuse (alias, `.anim`-resident, curve keys, and so on);
- otherwise the Stand is bound as described above, and a bone-flag or evaluator refusal is
  reported.

Results whose sources were all read are cached per model content key (32 entries) and checked
against each bone file's content key on reuse. A model that changes during the assessment,
or a bone file that ends early or changes length (`MODEL_SOURCE_CHANGED`), is reported as
unreadable and not cached. An UNAVAILABLE result shows its reason in place of the toggle.
`RaceModelPanel` asks for the assessment after the static surface is shown. The preview then
shows "Idle animation unavailable: …" in place of the toggle, and the Stand and bone-file
facts appear under "Model source details".

Actual Forever 1.60.1.70755 enUS, read-only. Missing bone files and models were fetched
into a private store under `HOME/.cache/vimi-tmp/race-idle-proof/` (`race-idle-net.log`,
`race-idle-net-v2.log`, `bonefile-net.log`). No game asset is committed. The probe assessed 75
ChrRaceXChrModel variants before its time limit, and then every playable race again with the
final code. Every variant was UNSUPPORTED; none was UNAVAILABLE.

- 26 variants declare BFID, 8–30 files each. This includes all playable bodies except those
  below. Every bone file validated: each held 3–129 affine matrices, and all bone indices were
  inside the model. The bones are face bones for Human and Night Elf, but Dwarf, Gnome and
  Tauren bone files cover 115–129 bones. Their Stand is in the model with only step/linear
  keys, for example Human male sequence 0, 2,667 ms, 6,680 keys on 170 bones.
- 19 variants name a SKID and store no bones of their own: Blood Elf, Draenei, Dracthyr,
  Goblin male and others.
- 30 variants have neither and pass the Stand reader. Most are non-playable rows (Fel Orc,
  Forest Troll, Vrykul and others), plus Undead male and Worgen. At the time only the missing
  race playback refused them; see the section above for their current state.

A cold assessment took 0.1–1.0 s from local files and up to 4.7 s when files had to be
fetched. A repeated assessment took 33–56 ms (variant lookup and stat) and did not re-read
the model.

## Tests

- `M2IdleAnimationTest`: authored Walk+Stand annex on the 272/274 goldens; keyed and
  identity tracks, global clocks, every refusal above, mismatched source and SKID.
- `PreviewAnimationTest`: hand-computed parent/child composition with pivots, blended
  weights, non-topological storage order, step/linear/held/looping keys, shortest-path
  slerp and normal length, global clocks, billboard pivots, fixed framing bounds and
  invalid skeletons/weights/tracks.
- `DisplayIdleAnimationsTest`: native fixture with an ordinary root and a 0x8 card leaf;
  card members follow the parent, irrelevant flags pass, chain flags and keyed or parent
  billboards are refused, keyless Stand keeps the rest pose.
- `M2BoneFileTest`: valid files in either chunk order and across several bounded reads;
  refusal of versions, unknown chunks and non-affine matrices; missing, repeated, partial,
  mismatched, out-of-range, duplicate, non-finite, truncated, trailing and overrunning data;
  size and entry limits; short or resized sources (`MODEL_SOURCE_CHANGED`); cancellation.
- `M2IdleAnimationTest`: BFID refuses playback while `assessStand` still reports the Stand,
  carries curve and `.anim` refusals, and keeps structural errors fatal.
- `BuildModelsTest`: declared bone files read, refused and missing through files; an
  undeclared ID is rejected before any access; oversized files are refused before opening.
- `RacesServiceTest`: through files, an unreadable bone file is assessed again, a complete
  result is cached without opening files, and new bone-file or model content invalidates it.
- `RaceIdleAnimationsTest`: every blocker is reported together, a SKID-only model skips Stand,
  a model-resident Stand has no blocker, unreadable sources are not cached, the bone-file
  budget is enforced, cancellation stops the assessment, only READY carries an animation, and
  evaluable-read refusals map to Stand facts as `assessStand` reports them.
- `RacesServiceTest`: an authored 274 model with one Stand bone and the authored SKIN is READY;
  its animation matches `RaceRestSurface` and moves vertices by the keyed amount; the result is
  cached and a new SKIN content key reassesses it; a 0x1 bone is refused with its reason; a
  SKIN that is not stored is UNAVAILABLE and reassessed.
- `BuildModelsTest`: `readIdle` returns tracks for model-resident bones and refuses BFID.
- `PreviewAnimationTest`: a global clock longer than 600,000 ms is sampled modulo its length.
- `RacesUiTest` (display required): the refusal replaces the toggle and its facts reach the
  details; a READY result shows the toggle, its status and its facts.
- `MountsUiTest` (display required, not part of hosted CI): the control appears only for
  a matching animation, playback produces changing frames with an idle status, and an
  unsupported variant hides the control and shows its reason.

## Next slices

1. GPU playback: stream posed positions to the inline GPU child, or upload bone palettes,
   with bounded per-frame cost; required before many-model World use.
2. World: animate placed M2 doodads/creatures once they render, sharing one evaluator per
   model and bounding per-frame evaluated vertices.
3. Characters: Stand is offered for race models without SKID/BFID. Confirm Worgen and the NPC
   rows on actual data once their SKIN files are stored by normal use. Then review how the
   client selects and applies BFID files (customization choice to bone file, and matrix
   composition with Stand) before offering Stand for playable bodies. SKID skeleton reading
   comes after that.
4. Persist qualified idle data beside the static derivative if cold qualification time
   matters in practice.
