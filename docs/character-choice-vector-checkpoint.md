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
