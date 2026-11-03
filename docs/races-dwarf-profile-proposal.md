# Dwarf static body and hair profile proposal

Status: approved design, implemented in `f2690da` with typed-context and cutout
regression follow-up `119a1f8`. Root and independent domain review approved the
bounded profile; deliver this increment as its own prerelease. Human1/2,
Orc3/4 and Tauren11/12 already exist, so this is an additional race pair.

## Selected scope and evidence ownership

Add exact Dwarf male/female static body/hair composition and existing recorded
starting clothing. Keep lowest-known-eligible customization, recorded foreign-key
joins, existing geometry/clothing rules, static normals and all current bounds.
Do not add animation, full character shaders, attachments, an editor, account
eligibility, guessed defaults, fallback traversal or other races.

Actual source audit used selected Forever `1.60.1.70755`, enUS, with bundled
WoWDBDefs revision `b9a63096cdd99b26967f17f9515377ae62cadfd7`. The original library
was opened through `ObjectStore.openReadOnly`; missing assets and all derivatives
were acquired/written only under `/tmp/vimi-race-profile-research`. No app settings,
running JARs or user-library objects were modified. Actual images/assets stay
outside Git. The private prototype changes only a copied research class and its
private cache identity, not production `RaceAppearance` or its persisted policy.

## Exact model and source facts

| Fact | Male | Female |
| --- | --- | --- |
| Race / recorded sex | 3 / 0 | 3 / 1 |
| ChrModel | 5 | 6 |
| Component texture layout | 107 | 108 |
| CreatureDisplayInfo | 49242 | 53768 |
| CreatureModelData | 5408 | 7203 |
| M2 FileDataID | 878772 | 950080 |
| M2 content key | da1aea059a6d2c6f46f63c35c44c3c9f | f8fc49df5a263e1566102e497abdc584 |
| LOD0 SKIN FileDataID | 878823 | 950113 |
| SKIN content key | 70277f3dcc101f5aec6b143339e8cfec | 9aa323f2bd1db21052d09b4522178efd |
| M2 version / native vertices | 272 / 251718 | 272 / 247346 |
| Render lookup vertices / indices / sections | 42067 / 153759 / 138 | 41656 / 149163 / 103 |

These come from the existing build-local chain `ChrRaceXChrModel → ChrModel →
CreatureDisplayInfo → CreatureModelData → FileDataID`. CreatureDisplayInfo and
several clothing dependencies are PARTIAL; retain readable rows without upgrading
absence or membership. No filename/name matching selected either model.

ChrModel is FileDataID3384313, layout03FAB755,
CKey`5b0536a731396bdf8eeb07d2bab6ff95`, the same content reviewed for the existing
[Tauren compatibility-zero adapter](races-tauren-profile-proposal.md#context-only-compatibility-zero-gate).
Actual rows5/6 record Sex0/1 and both flattened compatibility slots12/13 as literal
zero, with integer actual columns, no interpretation and no narrowing. Admission
must check the exact model/sex/layout tuple and that row's recorded Sex. Reuse the
exact-layout signed32/scalar/non-ID/non-relation literal-zero predicate for these
Dwarf tuples only. Its unverified fallback field names do not enter the generic
allowlist, and no nonzero association is traversed. Different layout, ordinal,
logical width/signedness, interpretation, narrowing or value is unsupported.

The tested Dwarf classes are Warrior1 and Priest5; both are recorded in CharBaseInfo.
The source also records Hunter3, Paladin2, Rogue4 and Shaman7. These are selected-
build facts, not server/account availability. `PlayableRaceBit=2` is read from
ChrRaces; do not calculate it from RaceID.

## Unchanged customization and clothing

Production `RaceCustomization.select` ran unchanged. Its sorted male sample IDs
are `524,551,571,590,604,5532,15441,15451`; female IDs are
`615,634,654,675,5560,78950`. Both tested classes choose these same sets. Every
selected option requirement is0; selected choice requirements are141/146 and
visibility requirements are0. Requirement141 retains copy-row provenance from35.
The existing player/class/race rules admit these samples without account, region,
archive or prerequisite guesses. Prerequisite source F925BC6F is READY with450
readable rows; its CKey is`360443ffda07a06b336d1491689f2852`.

Both layouts record body type1 at2048x1024, hair type6 at512x256, eyes type19
at256x128: 2,260,992 composed pixels total. Selected layers use the existing
copy/source-over operations1/15, flags0, second target0 and second mask-1. Keep all
existing atlas, requirement, qualifier and ambiguity guards. No blend operation,
fixed atlas rectangle or new fallback source is introduced.

A concrete male hair join is customization material19275 (layoutBE9767E9) →
texture target10/resource669600 → TextureFileData878537 with UsageType0
(layoutBD7C74C2). ComponentTextureFileData878537 is explicitly ABSENT in the same
READY source B32B030A; only this source-qualified absence permits an untagged
candidate. The image decodes at512x256, with2385 pixels below alpha128 and126715
fully opaque pixels out of131072. Female sample hair resolves recorded material19289
and FDID3483526. Keep current sex/class/race specificity ranking; unknown,
protected, changed or tied qualifier outcomes remain omitted.

Purpose9 loadouts1568/1571 have recorded class1/5 and race mask low4/high0.
Warrior clothing38/39/40 resolves displays6048/6050/6051. Priest clothing6098/53/52/51
resolves displays9943/8370/5848/7081. The robe records geosets`[1,0,1,0,0,0]`;
existing logic selects skirt1302 and sleeves802, while Warrior retains pants1301.
Both keep base body0 and recorded hair/beard selection. Sex-qualified clothing
FDIDs differ where the existing unique-best qualifier rule selects them. The
unverified raw CharacterLoadout ModID is not interpreted by this proposal.

## Why the male beard needs one additional binding

Using only the current race draw policy produces textured body/clothing and a
neutral grey male beard. It is not acceptable to describe that as complete Dwarf
body/hair support. The actual selected unbound beard/hair surface ordinals32/48/127
have native geoset IDs102/302/3401 and the same recorded draw profile:

| Property | Observed value |
| --- | --- |
| M2 version | 272 |
| Texture count / shader | 1 / 16 |
| Batch flags / second field | 0x10 / 0 |
| Priority / material layer / color index | 0 / 0 / -1 |
| Material flags / blend | 4 / 1 (two-sided alpha-key) |
| Texture combo | slot1 → texture ordinal0 → dynamic type6, flags3 |
| Coordinate lookup | absent; selector65535; reviewed implicit UV0 |
| Texture transform | combo0 →65535 (identity) |
| Texture weight | combo0 →0; same-model opacity fact0 is statically full |

`M2Appearance.readModel` proves `staticWeights=[true]` against that exact parsed
model object. Neither a foreign fact object nor an unknown/dynamic weight is proof.
The recorded shader16/count1 is the existing `M2Combiners` MOD/UV0 profile, already
represented by the renderer's lit DIFFUSE plus ALPHA_KEY material. This requires
no new shader, combiner enum, coordinate convention or codec format. Current
`RaceAppearance` excludes it because it accepts only shader0.

Propose a **Dwarf-only** typed draw predicate, preserving old race behavior. The
reviewed matrix is body shader0/type1/textureFlags0/materialFlags0/opaque0, female
hair shader0/type6/textureFlags3/materialFlags4/opaque0, and male hair
shader16/type6/textureFlags3/materialFlags4/alpha-key1. Every case additionally
requires version272, one texture, flags0x10, second field0, priority0, layer0,
color-1, absent coordinate lookup with selector65535, consumed identity transform,
and proven full opacity. Actual sections have one batch each; require that
uniqueness before assigning a material, rather than choosing the first of multiple
passes. Female hair is recorded opaque: do not infer alpha-key merely because
it is hair or shares the male atlas dimensions. Resolve texture lookups from validated draw references;
do not select surfaces by native geoset ID or hardcode the sample ordinals. Other
profiles retain neutral shading and a diagnostic. Do not globally admit every
shader16, arbitrary MOD blend, projected UV, two-texture or unknown flag profile.

A private prototype with only this male hair predicate adds43 reviewed bindings:
male body65 + hair43 =108; female remains body47 + hair26 =73. The beard is visibly
textured and cut out correctly. All four Warrior/Priest sex combinations compose,
then restore warm and through a new component instance with the same binding
counts and limitations. This prototype is source/design evidence, not production
implementation approval.

## Remaining limits, normal data and budgets

This remains a partial static body/hair profile. Visible native IDs1601/3301/5101
(male) and3301/5101 (female) still lack admitted bindings. Their fixed-resource,
two-texture or other blend/shader paths are not guessed. The eye atlas can be
composed without claiming its unsupported draw is rendered. Preserve explicit
neutral-surface/attachment warnings both cold and restored; do not label the whole
character's materials complete.

Both actual render lookups contain zero zero-length normals and zero nonfinite
normals. Carry native normals and UVs unchanged; no rig/animation evaluation is
introduced. Existing M2/SKIN topology and diffuse renderer validation stays intact.
Native M2 vertex counts are larger than render lookups; only the validated LOD0
lookup is uploaded. Keep65536 render vertices,1,500,000 indices,256 sections,
16 textures,2048 image dimension,8,388,608 aggregate pixels and40MiB appearance
codec limits. No asset/parser allocation cap or frame/raster budget is raised.

Prepare immutable metadata on the existing table worker, decode/compose on its
separate bounded worker, and keep cancellation/source-generation/close behavior.
The existing GPU/CPU model presentation needs no new runtime process or transport.
Bind the composition cache to an updated explicit Dwarf profile/policy revision,
source M2/SKIN/layer keys, locale, recipe and chosen visibility. Preserve old
payload decoding; incomplete source fetches must not become a cached success.
No existing cache object needs deletion or rewriting.

## Concrete implementation and acceptance boundary

1. Independently review this exact tuple/draw matrix and private actual-data facts.
   No production change before approval.
2. Add an internal profile identifier from exact race/model/sex/layout admission;
   carry it in the prepared recipe so render admission cannot accidentally apply
   the new predicate to Human/Orc/Tauren or other models. Factor/reuse the narrow
   compatibility-zero helper without granting generic fallback-field authority.
3. Apply the Dwarf-only binding matrix through existing typed materials. Leave
   all other race draw policy, texture joins, requirement rules and clothing
   geometry unchanged. Version the content-bound composition policy.
4. Add meaningful authored tests for both exact tuples; wrong race/model/sex/
   recorded Sex/layout and every changed compatibility shape/value; unchanged old
   profiles and strict format APIs; body preservation, pants versus robe/sleeves,
   exact qualifier absence versus UNKNOWN/PROTECTED/source change, and lowest-ID
   known-eligible choices without UiOrder inference.
5. Exercise every draw matrix guard negatively: shader/count/version, unknown
   flags/second field, priority/layer/color, texture type/wrap flags, wrong blend,
   nonidentity transform, coordinate lookup and missing/foreign/dynamic opacity.
   Assert unadmitted surfaces stay neutral with a persistent warning. Include a
   meaningful alpha127/128 beard cutout/depth oracle using authored data.
6. Re-run all four actual source combinations against the implementation using
   read-only originals and private acquisition/generated stores. Verify body,
   beard, female hair, pants/robe and preserved normal/UV/topology data; prove warm
   and fresh-component persistent restores plus identical warnings. Inspect
   authored CPU/GPU presentation and actual private frames without committing
   assets. Run races/formats/rendering/GUI checks and existing Human/Orc/Tauren
   regressions. Independently review before the separate prerelease.

Private reproducible evidence: `/tmp/vimi-race-profile-research/metadata-first.log`,
`dwarf-final-facts.log`, `dwarf-mod6-proof.log`, `dwarf-draw-matrix.log` and the research Java sources in that
directory. Original-versus-prototype male images are `dwarf-5-class-1.png` and
`dwarf-mod6-5-class-1.png`; the former demonstrates the excluded grey beard, the
latter the exact proposed binding. These local paths are reviewer evidence, not
repository assets or proof of client-default/account/runtime behavior.

## Production implementation validation

The public `RacesService.appearance` pipeline was exercised on the same private
Forever70755 source for both Warrior1 and Priest5, male/female. All four cold
results remain honestly PARTIAL. The male binds108 reviewed sections and the
female73; native UV/normal/topology arrays and the existing clothing rules are
preserved. Both male beard and female hair are textured; unreviewed eye/glow and
separate attachments remain explicit limitations.

All four warm requests and four newly constructed-service requests restore the
persistent composition with identical full appearance codec bytes and warning
lists. Warrior keeps pants1301, Priest skirt1302 and sleeves802. Source normals
contain zero nonfinite or zero-length vectors. The original library was read-only;
acquisition and generated outputs remained in private scratch stores. Evidence is
`/tmp/vimi-race-profile-research/dwarf-production-asserted-f2690da.log` and
`dwarf-production-{5,6}-class-{1,5}.png`; no game assets are committed.

Authored native-byte tests cover exact male/female draw matrices, foreign/missing/
partial opacity evidence, every reviewed batch guard, and model/material/coordinate/
transform negatives. The admitted male material is passed through the existing
renderer at alpha127/128 to verify discard without depth versus acceptance with
depth before the body. Table fixtures cover both tuples, wrong race/layout/sex/
recorded Sex, literal-zero compatibility values and changed logical/raw shapes.
The existing Human/Orc/Tauren and strict format regressions remain enabled.

`:concepts-races:check`, `:formats-assets:check`, `:rendering-models:check` and
`:gui:check` pass. Hardware/runtime claims remain scoped to the separately
recorded GPU proof; no new backend or animation qualification is implied.

The no-window Linux x64 Intel Vulkan follow-up rendered all four production
payloads at960x720, two camera views and three modes each (default raw, qualified
material diagnostic and textures off):24 frames. Against the same-input CPU,
silhouette intersection-over-union was0.99996858–1.0 and foreground RGB mean
absolute error0.439567–0.555896. Every output pixel was opaque; all four children
closed normally with no camera/mode restart. The male default GPU frame was
visually inspected for the textured beard. This is Vulkan execution evidence,
not Metal/D3D qualification or a client-rendering equivalence claim.
Local artifacts are `dwarf-production-vulkan.log` and `gpu-production/` beneath
the private research directory above.
