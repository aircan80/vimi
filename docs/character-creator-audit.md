# Character creator audit and first bounded slice

Read-only code audit at `4455a5b`. This follows `races.md`, the Dwarf/Tauren source
proposals and the queued character milestone in `world-experience-roadmap.md`.
World streaming and instance work keep priority. No appearance admission or UI code
changes are authorized by this audit alone.

## Existing support is more than a mesh viewer

`RaceAppearancePlan.build` explicitly admits these race/model pairs:

| Recorded race | ChrModel IDs | Existing textured support |
| --- | --- | --- |
| Human 1 | 1/2 | Existing reviewed composition/diffuse profile |
| Orc 2 | 3/4 | Exact model/layout/sex checks; existing diffuse profile |
| Dwarf 3 | 5/6 | Layout107/108, sex and contextual zero-fallback checks; isolated male alpha-key hair/beard policy |
| Tauren 6 | 11/12 | Layout113/114, sex and contextual zero-fallback checks |
| All other recorded models | Resolve actual table chain | Generic rest geometry may be available; character texture composition is explicitly unsupported |

These are model IDs, not an HD/SD switch or a rule for deriving another race's IDs.
The generic model chain is already `ChrRaceXChrModel → ChrModel →
CreatureDisplayInfo → CreatureModelData → FileDataID`; retain recorded variants.
Dwarf support was shipped already: do not propose implementing it again.

Prior authenticated selected-build research (`1.60.1.70755`, enUS) records Night Elf
race4 models7/8 → displays54918/54439 → M2 IDs974343/921844, and Undead race5
models9/10 → displays54041/56327 → M2 IDs959310/997378. The old local-only probe
reported missing stored model objects, **not** a geometry/parser failure. These
facts identify future audit targets; they do not qualify their material, geoset,
sex/layout or animation profiles. Other model pairs require their own recorded
source facts rather than arithmetic extrapolation.

The current renderer handles actual native normals/UVs, recorded geoset masks,
bounded composed atlases and qualified static clothing. Unsupported draw sections
remain neutral with diagnostics. Animation, separate attached equipment meshes,
full client lighting and account/server creation are outside that support.

## The missing feature is an explicit, shared look selection

`BuildRaces.preview` and `appearance` accept race/class/model only.
`RaceCustomization.select` privately picks the lowest eligible ChoiceID for each
eligible model option. `RaceMedia` derives geosets from one invocation and
`RaceAppearancePlan` derives texture layers from another. This is deterministic
for the present sample, but a customizer needs **one validated effective vector**
shared by both paths. A GUI-only selected value would otherwise leave geosets and
textures using different looks, or have no effect at all.

`RaceModelPanel` currently exposes Class and recorded Model comboboxes, async
geometry-then-appearance loading, voice clips and source details. `RacesView`
already supplies race/class metadata and racial cards. No supported appearance
option catalog or selection request exists; there are no skin/face/hair sliders
waiting to be enabled.

Important existing rules to preserve:

- Eligibility checks option/choice requirements, player/class/PlayableRaceBit,
  visibility and unknown unlock/prerequisite state. PARTIAL prerequisite coverage
  does not prove absence. No account unlock prediction.
- `Selection.allChoices` includes candidates that are **not eligibility-filtered**;
  it must not become the selectable UI catalog.
- Element application requires both `ChrCustomizationChoiceID` and a satisfied
  `RelatedChrCustomizationChoiceID`. Keep material and geoset effects paired.
- Layout `9559C358`'s `UiOrderIndex` is unverified raw physical data. Do not narrow,
  sort semantically by it or treat it as a numeric slider position.
- Names/option categories/colors and camera fields require exact physical-profile
  review before new interpretation. A data field's presence alone is insufficient.

## Smallest useful delivery: one real option on an admitted model

First implement a pure bounded customization catalog/resolver in concepts-races,
then expose **one verified texture-changing option** for Dwarf model5 (or Human1
if its actual alternate source coverage is better). Choose the option from actual
selected-build evidence, not a hardcoded assumption that a ChoiceID means skin.
A second qualified choice must visibly change an independently verified atlas or
geoset mask before that control appears. Prefer a texture-only option for the
first delivery, avoiding the need to qualify new draw sections simultaneously.

Existing public source evidence gives real Dwarf male sample ChoiceIDs
`524,551,571,590,604,5532,15441,15451`; female IDs are
`615,634,654,675,5560,78950`. These are the recorded default sample vectors, **not**
a list of mutually substitutable choices or demonstrated UI labels. The private
prior audit also records alternate candidate rows, but eligibility and effective
material coverage must be checked before offering them. Do not select an arbitrary
second ID from that list as a hair/skin alternative.

Proposed API checkpoint before implementation:

- Optional `BuildRaces.customization(raceId,classId,modelId,token)` returning a
  bounded catalog with recorded OptionID/ChoiceID, admitted label or honest ID
  label, known eligibility, supported effect and an explicit default vector.
  Existing/custom providers default to UNSUPPORTED.
- An immutable request selection with at most one ChoiceID per OptionID. The
  resolver validates actual membership, model/race/class requirements and related
  choice dependencies against current table identities. Public integers are
  requests, not positive admission. Invalid/unknown requests return a typed
  refusal; do not silently substitute a different appearance.
- New preview/appearance overloads consuming the same validated effective vector;
  old overloads retain today's sample behavior. Immutable per-request source
  identity prevents mixing catalogs from different builds, locales or models.
- The cache identity includes the effective ordered OptionID→ChoiceID vector,
  geoset policy, draw/composition revision and all current source identities.
  Preserve current resource caps and incomplete-derivative rules.

Initial proposed UI bounds: at most64 options/model,512 choices/option and8192
choices/catalog; at most64 selected pairs;64KiB aggregate UTF-8 labels and bounded
issues. These are proposed output limits, not permission to load more rows than
existing table caps (4096 options,40000 choices,200000 elements). Constructor caps
precede collection copies; the full projection/transient ledger needs its own
checkpoint. Do not retain raw table rows or image pixels in the GUI catalog.

## Creation-style layout after that source checkpoint

Retain the existing race list as a compact left rail, selected model in a large
center stage and a right appearance panel. Keep recorded class/model selection
and racial information available without crowding the stage. Group only positively
identified options; otherwise preserve recorded labels. Use discrete previous/next
or choice lists for discrete IDs, not continuous invented morph sliders.

First controls: the one effective qualified option, Reset to the explicit sample
vector and source/loading status. Add randomize only after a resolver can generate
jointly valid related-choice vectors; random independent indices are unsafe.
Unknown or non-rendered options remain explained coverage limitations rather than
inert controls. Persist camera rotation/zoom while appearance changes, preserve
the last accepted look during preparation, and apply the new geoset+material bundle
atomically. No fake Create Character/server action.

## Acceptance and remaining work

1. Authored resolver tests: foreign model/option/choice, duplicate option IDs,
   missing/unknown requirements, partial prerequisite absence, related-choice
   changes/cycles, raw UiOrder refusal, bounded copies and stale source identities.
2. Two actual eligible choices for the first option: recorded IDs/labels/joins,
   asymmetric texture or geoset difference, expected coverage and exact unsupported
   reasons. All existing static material guards remain unchanged.
3. Cache round-trip separates choice vectors and reproduces both mask and pixels;
   incomplete source fetch is not cached as complete.
4. Actual FX rapid selection/class/model/build replacement: off-FX source and
   cancellation work, last accepted look retained, bounded latest request, spinner
   ends only for accepted current result or explicit failure, close drains before
   borrowed build services retire. Reuse existing race loading/stale tests.
5. Remaining race profiles proceed pair-by-pair from actual native material facts;
   Night Elf7/8 is a positively identified next audit target, not a preapproved
   rendering profile. Keep animation and attachments as separate later milestones.

No production bug fix was made: the audit found no small isolated control change
that would safely supply this missing domain selection contract.
