# Shared character choice vector checkpoint

This internal foundation follows `character-creator-audit.md`. It adds no appearance
controls, public selection API, race profile, HD/SD mapping, or eligibility rule.

`RaceAppearance.prepare` now resolves one OptionID→ChoiceID vector and supplies the
same selection to both `RaceMedia` geoset filtering and `RaceAppearancePlan` texture
elements. Selection belongs to the exact media instance, race, class and model;
consumers reject a different context before reading geometry/material inputs. The
ordered immutable vector is included in the generated appearance identity, including
choices that affect only geometry. Existing standalone preview/plan entry points still
resolve their own default sample. Defaults remain the lowest known eligible ChoiceID;
unknown unlock requirements and related-choice filtering retain existing behavior.

The vector is operation-local and is not a native source certificate. It does not make
the existing unfiltered `allChoices` candidate set a selectable catalog. Public choices
must be revalidated against option membership, eligibility and current source inputs;
no caller-created map will be accepted as an authoritative selection.

## Read-only selected-build inspection

An executed private Java inspection used the original library read-only, local-only
source access, and a separate generated-store scratch overlay. No assets were acquired
or committed. Build was wowf 1.60.1.70755/enUS, config
`9d9888f2d89a2dcc9eacc68a2d7cb3dc`.

The following is an actual same-option alternate pair, unlike a list of defaults from
different options:

| Fact | Choice 524 | Choice 525 |
| --- | --- | --- |
| Model / option | model 5 / option 30, `Skin Color` | same |
| Requirement / visibility requirement | 141 / 0 | 141 / 0 |
| Material elements | 2134 and 2135 | 2136 and 2135 |
| Differing material target | 1 | 1 |
| Differing MaterialResourcesID | 103678 | 103679 |
| TextureFileData diffuse (`UsageType=0`) | 878515 | 878516 |

Option table: FDID3384247/layoutDCC2A86E/CK5fb930dab2ed9e513d6b005f84de97bc.
Choice table: FDID3450554/layout9559C358/CK1d9487e3f30f3fca049337da0c26c2f6.
Elements: FDID3512765/layout6483C37E/CKb99b6c6171f7d102f2fed89b205bd0f2.
Materials: FDID3459652/layoutBE9767E9/CK255f0119529932dca0ef2b107b49affa.
TextureFileData: FDID982459/layoutBD7C74C2/CK16fc52c507b53b3e59e558cd6d932608.

Requirement 141 has ReqType3, unrestricted class/race masks, zero region,
achievement, quest and modified-appearance requirements, and archive -1. The complete
450-row ReqChoice table has no entry for requirement141. Choice `UiOrderIndex` is
still an unverified raw physical integer and is not used as a slider or semantic order.

Private executed logs are under `HOME/.cache/vimi-tmp/character-choice-proof/`
(`inspection.log`, `materials.log`, `textures.log`). They establish the observed source
chain, not a visible two-choice rendering proof or a stronger native certificate than
the existing character pipeline provides.

## Next small visible slice

Expose one validated requested override for option30 after the shared foundation is
reviewed. Validate the exact option/model membership and existing requirement policy,
then resolve all remaining options deterministically. Both geometry and atlas receive
that same complete vector. Return actual option/choice IDs; unnamed choices may be
labelled with their IDs. Do not expose unrelated unlock/account choices.

Before UI promotion, qualify FDIDs878515/878516 through existing component/race/sex
selection, decode actual bodies, render both full appearances, and show independently
observed atlas and final-image pixel differences. Prove geometry remains coherent and
cache identities differ, switching back restores the first output, invalid overrides
leave the current model untouched, and stale preparation cannot replace a newer choice.
No additional race support or inert controls are part of this checkpoint.

## Internal override and executed visible proof

A package-internal requested map is now admitted before preparation. It is limited to
4096 entries before copying (the existing option-table ceiling), unsigned positive
32-bit option/choice IDs, exact option membership in the selected model, and the same
known eligibility and visibility checks as default selection. An unavailable, unknown,
wrong-option or ineligible requested choice fails preparation rather than substituting
a default. Unspecified options retain existing deterministic defaults. This remains an
internal proof seam, not a public arbitrary-map API or selectable catalog.

The authored fixture checks explicit alternate membership, missing requirement,
nonzero visibility requirement, foreign option, nonexistent choice, immutable/context
binding, and a 4097-entry hostile map that must be refused without iteration/copy.
The existing full races check passes with this seam; public default entry points keep
empty overrides and prior behavior.

The actual selected-build proof initially found that the alternate diffuse bodies were
not stored locally. That run's image difference was **rejected** because missing-layer
fallback is not an appearance proof. Only the recorded missing bodies were acquired
into a new private scratch store. A subsequent proof reopened original, prior acquired,
and new acquired stores **read-only** and used `CachingObjectSource.localOnly` with no
network. Every result must have a nonnull appearance and no missing-texture warning.

Actual bodies observed:

| FDID | Decoded source length | Content key |
| --- | --- | --- |
| 878515 | 350716 | 26b36649cc0a333321467469ab88f21b |
| 878516 | 350716 | 58a70c20b4917666271fe457405bb784 |
| 878296 | 175948 | 9eb2c02a304b117dbad3cad2313af551 |
| 878297 | 175948 | 0a36746b82261696d998ea15e91f7418 |

The skin choice also selects the related face layer 878296 versus878297; the proof
retains existing related-choice filtering rather than replacing just the base image.
Both appearances have108 admitted surfaces. The first composed atlas differs at
1,041,416 pixels; the actual static model rendered at800×600 differs at14,826 pixels.
Mesh positions, indices and part descriptors are identical. Sequence524→525→524 has
different first/second cache keys, equal first/third keys, and byte-identical first/third
encoded appearances and output pixels. Private screenshots were visually inspected:
skin changes while beard, clothing and geometry remain stable.

Executable private source and final log:
`HOME/.cache/vimi-tmp/character-choice-proof/DwarfChoiceVisibleProof.java` and
`visible-final.log`; images `choice-524-0.png`, `choice-525-1.png`, `choice-524-2.png`.
The proof uses the current checkout's compiled concepts-races classes before dependency
JARs. No game bodies, images or generated derivatives are committed. Existing partial
character-table/neutral-surface warnings remain honest; this does not establish full
client shader parity, animations, other race admission or a GUI control implementation.

## Public bounded catalog and preparation API

`BuildRaces.customizations(race,class,model,token)` and
`appearance(RaceCustomizationTypes.Request,token)` are optional; custom providers
return unavailable unless they implement them. An unavailable provider never receives
an override through the old default-sample method. Requests contain at most16 unique
OptionID→ChoiceID picks, while catalogs contain at most16 options,64 choices per option
and256 choices total. Labels/messages are capped256 characters and diagnostics32.
All list snapshots check both reported size and actual iteration before copying.

The native service currently exposes only race3/model5/option30. Its actual source row
must bind to model5 and supply a verified nonempty name. Choices are derived from the
actual option foreign key and existing known requirement/visibility policy;524/525 are
proof samples, not a hardcoded membership list. More than64 eligible members refuses
the catalog. Default is the same selected choice as the full shared vector. Unknown
requirements, nonzero visibility requirements and another option's rows are excluded.
Public nonempty requests must belong to that catalog and are revalidated by the same
selection path during appearance preparation. Empty requests retain default behavior.
This does not make the catalog a native-membership certificate or promise full client
customization support. Other race/model/control contexts remain explicitly unsupported.
