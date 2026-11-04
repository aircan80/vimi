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
sequences, 1,024 global clocks, 4,096 keys per track and 262,144 keys in total.

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
stops. Races previews are unchanged (character models use external bone files; see below).

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
- `MountsUiTest` (display required, not part of hosted CI): the control appears only for
  a matching animation, playback produces changing frames with an idle status, and an
  unsupported variant hides the control and shows its reason.

## Next slices

1. GPU playback: stream posed positions to the inline GPU child, or upload bone palettes,
   with bounded per-frame cost; required before many-model World use.
2. World: animate placed M2 doodads/creatures once they render, sharing one evaluator per
   model and bounding per-frame evaluated vertices.
3. Characters: qualify BFID bone-file semantics and external skeleton/`.anim` reads, then
   offer Stand in Races previews.
4. Persist qualified idle data beside the static derivative if cold qualification time
   matters in practice.
