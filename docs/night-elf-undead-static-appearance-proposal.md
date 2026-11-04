# Night Elf static appearance checkpoint and Undead source blocker

This extends the existing static character preview only where actual selected-build
facts permit it. It does not add HD/SD pairing, animation, account/server unlock claims,
new client shader formulas, or customization controls beyond the separately reviewed
Dwarf Skin Color feature.

## Recorded source chain

Read-only original library and previous acquired stores; all new acquisitions and
outputs are private scratch. Build wowf1.60.1.70755/enUS uses config
9d9888f2d89a2dcc9eacc68a2d7cb3dc. Actual variants were resolved through the existing
race/model/display/model-data chain, not inferred by arithmetic.

| Race | ChrModel / sex / texture layout | Display / model-data | M2 FDID / content key | LOD0 SKIN | Compact preview vertices |
| --- | --- | --- | --- | --- | --- |
| Night Elf4 | 7 /0 /109 | 54918 /7369 | 974343 /8696fd515113d423f8a006ab7c4fedca | 974391 | 41543 |
| Night Elf4 | 8 /1 /110 | 54439 /7300 | 921844 /0645480db6a3903c1c6763add3b0d943 | 921897 | 39349 |
| Undead5 | 9 /0 /111 | 54041 /7233 | 959310 /bf8ec02811f587739b1d3344dd841f49 | 959362 | 44939 |
| Undead5 | 10 /1 /112 | 56327 /7578 | 997378 /9ed069924dee45914079bfd93db6502c | 997389 | 35933 |

The source log is authoritative for the observed model-data IDs/counts; the production
contract does not hardcode asset IDs. ChrModel FDID3384313/layout03FAB755/
CK5b0536a731396bdf8eeb07d2bab6ff95 records all four exact sexes/layouts and literal
zero model/texture fallback cells at physical fields12/13. Any compatibility-zero
handling stays limited to the admitted race/model/layout/sex tuple and exact layout.

All four M2s are version272. Raw model vertex counts exceed the compact renderer mesh
counts; the existing SKIN-driven compact extraction stays unchanged. No input/output
cap is raised. Existing preview bounds remain65536 vertices,1500000 indices,256 parts;
composition remains at most16 canvases/8388608 pixels and existing decoder limits.

## Positively observed opaque body/hair shape

All four actual inventories contain:

- Body: shader0, one texture, replaceable type1/textureflags0, materialflags0/blend0.
- Hair: shader0, one texture, replaceable type6/textureflags3, materialflags4/blend0.
- Both: batchflags16, rawsecond0, priority0, materialLayer0, color−1, empty texture
  coordinate lookup with selector65535 (implicit UV0), identity transform65535.
- Actual opacity reader returns `facts.model()==model` and `staticWeights=[true]`.
  Every visible body/hair section has exactly one batch. Native normals/UV topology
  stay unchanged; opaque material uses existing lit diffuse rendering.

Night Elf material canvases are type1(2048×1024),type6(256²),type19(256×128),
type10(128×256). Undead omits the type10 canvas. Actual first prototype compositions
use existing source-over/clothing recipes and existing decode profiles; no format
extension was required.

Auxiliary shader16/type2 or10, explicit effect textures, shader16xxx/32xxx multi-pass
and conditional surfaces are outside this first draw admission. They retain explicit
neutral/partial diagnostics. This is basic static body/hair support, not full client
appearance fidelity.

## First production slice: Night Elf male/female

Add two explicit contextual profile values. Require race4/model7 or8 with exact recorded
sex/layout109 or110 and contextual literal-zero fallback checks. Admit only the exact
opaque body/hair matrix above with existing strict common guards (same-model full
opacity, one batch per section, valid lookups, identity transform/implicit UV0).
Dwarf male alpha-key and all existing legacy predicates remain unchanged.

Require a selected full-canvas type1 base layer before admitting the new profile;
missing eligible base skin must refuse preparation instead of producing a black body.
Actual Night Elf male base material3177→FDID1030035 and female3653→997259 satisfy it.
Version the appearance cache recipe. Preserve one shared choice vector, existing
requirements and related-choice filtering, actual source file keys and wardrobe rules.

Private scratch-only research compositions visually show coherent purple body skin,
green hair and recorded Warrior clothing for both Night Elf sexes. Research outputs
are not production qualification; repeat through production API cold/warm/fresh owners
with identical codec bytes/warnings and authored tuple/guard regressions after the
implementation checkpoint. Actual native rendering remains its own execution gate.

## Undead blocker: explicit archive/prerequisite semantics

The raw geometry/material shape is suitable, but the current safe default selector
produces **no base skin layer**. The resulting black exposed skin is rejected as a
support proof. Assets are present; this is not a missing-file or HD/SD problem.

Actual male Skin Type option567 has Bony6527 requiring Req55 (`OverrideArchive=0`)
and Fresh6529 requiring Req4975 (`OverrideArchive=1`); female option568 has Bony6524
and Fresh6526 with those same requirements. All other Req55/4975 account fields are
zero/unrestricted, but archive0/1 is deliberately unknown to the present policy.
Skin Color options58/63 depend through ReqChoice on those Skin Type choices:
Req61→6527, Req66→6524, with archive0. Fresh equivalents4976/4977 depend on6529/6526
with archive1. Req10 is type4 and remains outside the known-player policy.

The existing rule admits archive−1 only and refuses all unresolved prerequisites.
Do not turn archive0 into a guessed default or ignore prerequisite rows to make the
image look populated. A separately reviewed explicit archive/known-choice dependency
preview context is required before Undead appearance admission. No Undead positive
profile is proposed in the first production slice.

## Executed private evidence and acceptance

Private source, logs and images are under
`HOME/.cache/vimi-tmp/night-elf-undead-proof/`:
`source-audit.log`, `composition-research.log`, `skin-dependencies.log`,
`research-7.png` through `research-10.png`. Both original and earlier acquired stores
were opened read-only; new sources are confined to `acquired/` scratch. No game asset
or screenshot is committed. Research classes are scratch-only and cannot widen
production admission.

Required authored checks: exact model/sex/layout/fallback negatives, wrong race/profile,
nonzero fallback, nonidentity transform, foreign-model opacity facts, nonstatic weight,
multiple section batches, wrong shader/material/texture flags, and missing base skin.
Actual production proof must retain ordinary partial-source diagnostics, show nonblack
skin, preserve compact mesh identity and cold/warm/fresh parity for both sexes.


## Production execution checkpoint

Implementation8a68d24 keeps two explicit Night Elf profile values. The Dwarf-only
wrapper still refuses other families; the shared strict draw shape now has an explicit
Night Elf opaque case. Human/Orc/Tauren legacy admission and all input/output caps stay
unchanged. Both the recipe and actual decoded-image stages require a full body base.
The generated appearance policy is versioned6.

The actual public service completed these cases through ordinary appearance preparation:

| Class / model | Compact vertices / indices | Bound surfaces | Codec SHA256 |
| --- | --- | --- | --- |
| Warrior1 /7 | 41543 /150468 | 93 | 669a24cb4eb4680f153d87ba07834feb93f7cd7b3af58b0cdb06fae11a741523 |
| Warrior1 /8 | 39349 /145512 | 76 | 94da125dfa6edf801eb15b499195843ae4c215034d3e286fe2685de8058e9fdd |
| Priest5 /7 | 41543 /150468 | 93 | 4ef9714ea4da19c5c1d67cf630d154440ae35e2dcbd47edfbaf27fee640320a6 |
| Priest5 /8 | 39349 /145512 | 76 | 5c9fa746015686e01254b3b6a6543be9ededf9c0422175eebfa79d3866c8b6ff |

Each case completed cold, warm and fresh component restores with identical codec bytes
and diagnostics. Final source runs reopen every asset store read-only and use localOnly
with no network, writing only a new private generated-store scratch directory. No
consumed texture may be absent in this proof. Images
`production-{7,8}-class-{1,5}.png` were visually inspected: body skin is populated, hair
and clothing are present, Priest robes change geoset1301→1302, and native male/female
rest poses are preserved. Unsupported auxiliary materials remain honest partial notes.

Private executable `NightElfProductionProof.java`, logs
`production-8a68d24-final-readonly.log` and `production-exact-current.log`, and runtime
classpath provenance live beside those images. The latter log uses only this checkout's
Gradle-resolved module outputs/dependencies, not older proof JARs. Earlier source/body
acquisition and research logs remain separate from this final execution record.

Authored regressions exercise the exact family/sex/layout/fallback matrix and body/hair
shape negatives, opacity identity, nonstatic weights, transforms, selectors, multipass,
wrong flags/blends/shader and missing base recipes. A real missing diffuse through
MemoryFiles/BlpImages must produce no appearance and no generated payload. An actual
opaque hair-over-body pixel fixture proves alpha0 is ignored and depth is retained.
Removing only the full-base recipe guard makes its authored regression fail (expected
Projection absent); restoring exact production source passes the checks. Logs are
`missing-base-negative-control.log`, `restored-full-check.log`, `final-source-check.log`.
These tests contain only authored assets. This checkpoint grants no Undead archive or
prerequisite policy, new controls, animation, or full client shader parity.
