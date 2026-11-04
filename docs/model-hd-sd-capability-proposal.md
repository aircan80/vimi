# Recorded, SD and HD model selection: capability investigation

Status: exploration and proposed capability contract, not implementation approval. The Forever client has a real HD/SD control. Vimi has not yet established the local asset-selection mapping that implements it. No production code, account settings or asset acquisition changed during this investigation.

## Implemented first slice: source-stated definition label (#185)

The Races model preview now states a definition only from an explicit ChrRaces field pair for the selected sex: `MaleDisplayID`/`HighResMaleDisplayID` or `FemaleDisplayID`/`HighResFemaleDisplayID`. Both fields must be verified-name integer references to `CreatureDisplayInfo.ID`. Such layouts exist in the pinned definitions (for example ChrRaces `8BE33A75`, 2.5.1); current Forever ChrModel-era layouts do not have them. `RaceModelDefinitions` compares the ChrModel-selected display with that pair. The selector and detail view show `SD` or `HD` together with the exact source field, for example `HD · ChrRaces #1 HighResMaleDisplayID`.

Every other case is a refusal with its reason. The selected display does not change:

| State | Meaning |
| --- | --- |
| `UNSUPPORTED_PROFILE` | The layout has no pair, has only one side, or a side is unverified or unlinked. Current 70755 always ends here. |
| `NO_RECORDED_PAIR` | Both fields are verified, but one side records zero. |
| `AMBIGUOUS` | Both fields name the same display. |
| `UNMATCHED` | The selected display is neither recorded side. |
| `UNREADABLE` | ChrRaces, the race row or a cell is unavailable. Retry can reassess it. |

Fallback slots, file names, LODs, mips and polygon counts are never evidence. Definition failures never discard or degrade the recorded variants. This slice adds no toggle. A selector that switches to the counterpart display is a later slice and needs the full composition chain described below.

## What the matching client UI proves

The extracted Blizzard UI source mirror has an exact Forever **1.60.1 (70755)** revision, `17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c`. This is client-authored UI published by a third-party mirror, not a claim that Gethe is a Blizzard-operated repository.

[Preset.lua](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_SettingsDefinitions_Shared/Preset.lua#L12-L53) defines `CreateHDSDToggleOptions`. It gates the control using `C_GameRules.IsSDHDToggleEnabled()`, obtains the value from `C_GameRules.AccountHasSDEnabled()`, and calls `C_GameRules.SetSDHDToggleValue(value, Enum.SdToggleUsageLocationUI.OptionsMenu)`. True selects Standard Definition, false selects High Definition. The default follows the Classic versus Enhanced Forever experience preset. It is a proxy setting, not evidence that the old `hdPlayerModels` CVar controls this client.

The [Camelot character creation control](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_CharacterCreate/Camelot/Blizzard_CharacterCreate.lua#L3271-L3303) uses the same API. Its checked state is the inverse of SD-enabled; after changing it, the UI updates race/class state and resets customization while retaining camera position. The [barbershop control](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_BarbershopUI/Mainline/Blizzard_BarberShopUI.lua#L258-L274) also delegates selection to `C_GameRules`.

The [generated API declaration](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_APIDocumentationGenerated/GameRulesDocumentation.lua#L332-L342) takes a boolean `setToSD` and a UI usage-location enum. Neither this declaration nor the inspected UI implementation returns an alternate display, model, texture or file ID. The remapping is behind the native API boundary. This is evidence of a genuine client capability, **not evidence that the required alternate assets or their pairing are absent**. Whether the native implementation selects different table rows, conditional records, content views, another recorded resource or a combination remains unresolved.

## What the prepared local tables prove

The audit used only original-library and existing private-scratch read-only object readers, bundled definitions and existing prepared tables. No new game source acquisition occurred. Private diagnostics remain outside Git at `/tmp/vimi-hd-sd-audit/70755-audit.log`.

Complete bounded pages for Forever 70755 enUS:

| Table | Layout | Readable/declared | Content key |
| --- | --- | --- | --- |
| ChrModel | 03FAB755 | 127/127 | 5b0536a731396bdf8eeb07d2bab6ff95 |
| ChrRaces | 4F44C796 | 58/58 | 46c25b870338ad6fb9ccabcecece036d |
| ChrRaceXChrModel | A203BC29 | 116/116 | cb32fc20e8156551892c3a6b0b4d4b44 |
| CreatureModelData | 0F5449F8 | 1194/1194 | 10ba817c8d93833716d9ee436c7db912 |

CreatureDisplayInfo layout7275F5F6 is partial: 14,207/14,232 readable rows, content key572d54115bdef5d733fb20d7e74bf910. Unreadable rows cannot establish absence of a specific alternate.

Current ChrRaces logical fields include model/texture fallback race and sex, but do not expose the legacy MaleDisplayID/FemaleDisplayID/HighResMaleDisplayID/HighResFemaleDisplayID fields. Current ChrModel does expose named model/texture/helm fallback slots. Human models1/2 select displays57899/56658; Dwarf5/6 select49242/53768. All four have model and texture fallback values0. These selected pairs are male/female, not SD/HD.

Positive fallback values also exist and demonstrate why fallback cannot mean SD:

- ChrModel29, joined to Skeleton race15, falls back to model9.
- ChrModel65, joined to Human race33, has **model fallback10 but texture fallback9**.
- Dark Iron Dwarf race34/model67 falls back to model5; Mag'har Orc race36/model71 to model3.
- Visage races75/76 use models127/128 with fallbacks to Human1/2.

These are recorded candidate fallback values, not a newly qualified composition profile or HD relationship. Existing `RaceAppearancePlan` deliberately admits selected fallback slots only as literal-zero compatibility facts in exact layouts; it does not implement positive fallback composition. Following them indiscriminately could change race, sex, texture layout or customization ownership.

The pinned [ChrModel definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ChrModel.dbd) declares separate model and texture fallback relationships. Historical [ChrRaces definitions](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ChrRaces.dbd) contain explicit high-resolution display fields for other layouts; their existence in a union of historical columns does not make them readable in this build.

Neither the inspected current CreatureDisplayInfo nor CreatureModelData logical profile exposes a generic SD/HD alternate link. PortraitCreatureDisplayInfoID denotes a portrait reference; CreatureModelMinLod is not SD/HD. The current ConditionalCreatureModelID slot is marked raw/unverified by the table provider. It cannot establish a semantic replacement relation until its exact profile and native use are independently qualified. Multiple MountXDisplay rows likewise establish variants, not their quality meaning.

## Same-FileDataID ROOT-content hypothesis

An alternate hypothesis is that native SD/HD selection chooses different content for the same FileDataID instead of following a DB2 pair. This was checked without payload I/O using the existing immutable `Build.resolve` API, which preserves all `RootTable.find` memberships and their locale/content flags rather than applying the Files handle's selection policy.

For the eight selected race models, each FileDataID has **exactly one ROOT membership and one content key**: Human1011653/1000764, Orc917116/949470, Dwarf878772/950080 and Tauren968705/986648. Each has locale flags0x173f6 and content flags0x120c0000. The five sampled mount models123410/125522/125649/124696/126304 likewise each have one membership with those flags. Therefore an alternate ROOT membership for the same ID is not available to implement the toggle for these sampled assets in this recorded 70755 build. This does not rule out another FileDataID, another content view or a client-native remapping, and does not establish the quality meaning of these content flags.

ChrModel FileDataID3384313 has one membership with flags0x12200000. ChrRaces1305311 has ten memberships with flags0x10200000 and distinct locale masks; only one intersects enUS (mask0x202). Those locale variants do not establish an SD/HD pair. Private diagnostics: `/tmp/vimi-hd-sd-audit/root-variants-70755.log`.

Current `BuildFilesHandle.pick` filters by selected locale and ranks an encoded variant ahead of missing encoded content, preferring entries without content bit128 and then without bit1, keeping the first equal-rank entry. It has no SD/HD preference, account input or quality-qualified content-flag contract. Any future variant selector would need an independently verified flag/profile meaning, explicit cache/source identity and backward-compatible default behavior; changing these rankings based on an HD hypothesis is not approved.

## Capability by viewer

| Viewer | Verified | Unresolved |
| --- | --- | --- |
| Races/character composition | Matching client offers SD/HD and may reset customization. Current recorded selection chain is ChrRaceXChrModel → ChrModel → CreatureDisplayInfo → CreatureModelData. | Native SD/HD mapping, eligible races/sexes, alternate texture/layout/customization chain and availability in the selected content view. |
| Mounts | Recorded display variants and per-display model/appearance. Wolf105 and Ram94 each have one recorded variant in the inspected overview. | No generic HD/SD pair or proof that the player-model setting affects mounts. |
| NPCs | Current NPC concept provides catalog data, without a model-preview/display resolver. | Future display-chain selection and any quality relationship. Do not promise a generic toggle. |
| Raw model/file previews | Recorded geometry, SKIN and native texture mips. | No semantic pairing. A lower SKIN LOD or BLP mip is not an SD replacement model. |

## Proposed local UI contract

Keep **Recorded** as the default: render the selected build's existing positively qualified source chain without asserting its quality. A future selector can contain Recorded, SD and HD only where the corresponding capability has been established. A requested unavailable mode should explain whether its mapping is unqualified, source unreadable, required asset absent or appearance unsupported. Do not silently fall back and label the result HD or SD.

A proposed detached capability result separates `AVAILABLE`, `NO_RECORDED_PAIR`, `UNSUPPORTED_PROFILE` and `UNREADABLE`, with mode-specific qualified source evidence, display/model IDs and reason. `NO_RECORDED_PAIR` is reserved for a positively qualified pair profile whose actual link is zero or whose qualified linked record is absent. Current 70755 is `UNSUPPORTED_PROFILE` / unproven link: there is no verified pairing through the current qualified sources, and raw conditional fields remain unresolved. Complete known columns and sampled ROOT memberships do not prove that no pairing exists elsewhere. The matching client-UI capability is independent evidence and must remain visible in the design even while local pairing is unresolved.

Do not call client account-setting APIs from Vimi or pretend to read the player's account mode. Do not expose an inert selector that changes texture resolution while claiming an HD model. “All recorded surfaces” continues to control mesh-section diagnostics; it is unrelated to SD/HD.

## Proposed future controlled client observation

The exact same UI revision declares useful read-only introspection methods:

- [ModelScene actor GetModelFileID](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_APIDocumentationGenerated/FrameAPIModelSceneFrameActorBaseDocumentation.lua#L111-L124) returns a `fileID` from the actor base.
- [SimpleModel GetModelFileID](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_APIDocumentationGenerated/SimpleModelAPIDocumentation.lua#L219-L232) returns a `fileID` from the model widget API.
- [CharacterModel GetDisplayInfo](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_APIDocumentationGenerated/FrameAPICharacterModelBaseDocumentation.lua#L42-L55) returns its numeric display ID. This is a different object API; do not assume ModelScene actors expose it.

Actual matching-client callers provide additional evidence: [EncounterJournal](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_EncounterJournal/Mainline/Blizzard_EncounterJournal.lua#L1484) calls `actor:GetModelFileID()`, while [TalkingHeadUI](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_FrameXML/TalkingHeadUI.lua#L142) calls the character model's `GetDisplayInfo()`. API availability does not prove that a particular character-creation native presentation exposes a suitable widget or that returned IDs reflect every internal replacement stage.

A future **explicitly authorized manual experiment** can select a supported player-model widget/actor for the same character/race/sex, wait for loading to complete, and record available model FileDataID, display ID, selected SD/HD state, build, customization and source context before and after the user changes the real client setting. Keep presentation/camera fixed and account for the UI's documented customization reset. Require a stable loaded model and repeated observations on each side, not a transient zero/stale ID. If IDs change, validate both recorded assets and full composition chains; if they remain equal, investigate textures/content selection rather than declaring the setting ineffective. Repeat separately for a mount before inferring any mount effect. This experiment has **not** been executed; no addon, client script, setting change or account operation is authorized by this proposal.

## Next investigation gate

1. Inspect the matching prepared UI paths and compare the pinned mirrored UI when available; establish source identity, without scanning or acquiring guessed paths.
2. Audit declared tables related to client rules, conditional models and customization only where an actual qualified relation exists. Preserve raw/unverified cells as unknown; a suggestive field name is insufficient. Determine whether the client uses a table mapping, a native display-replacement rule or different content selection.
3. Establish at least one independently verified SD↔HD asset pair for one race/sex in one build, with both full display/model/texture/layout/customization chains. If the necessary mapping resides only inside native client behavior, document that limitation and seek a primary observable mapping rather than filename or polygon-count heuristics.
4. Review an exact admission profile and authored wrong-layout, wrong-sex, self/cycle, unknown/partial/missing-target and unrelated-fallback tests before production.
5. Prepare both choices with bounded one-active/one-latest work, cancellation and selection generations. Reconcile customization explicitly; do not reuse option IDs, UV layouts or skin composites across incompatible models.
6. Cache each fully selected chain by build/locale, mode/pairing policy, table/source receipts, model content, texture layout and composition policy. Toggle-back reuse must validate these identities. No old cache deletion or global remapping.
7. Prove cold/warm/fresh restore and authored stale-toggle behavior, then inspect actual CPU/native GPU images of independently verified pairs. Hardware parity alone cannot certify semantic pairing.

The first deliverable remains a reviewed capability/mapping contract, not a generic all-viewer toggle. The exact client UI makes this a viable investigation rather than an unsupported assumption; local asset selection is the remaining technical gate.
