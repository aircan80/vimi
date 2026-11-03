# Narrow Tauren static appearance proposal (#104)

This is an isolated prototype awaiting independent domain review. It does not admit
other races, model fallbacks, character animation or held equipment. No client assets
or actual-data screenshots are included in the repository.

## Exact selected-build facts

Read-only source validation used Forever 1.60.1.70707, enUS. Required table bytes were
decoded from the selected root with the bundled WoWDBDefs revision
`b9a63096cdd99b26967f17f9515377ae62cadfd7`; PARTIAL tables retained readable records.
The primary library was opened read-only and closed before the application reopened.
Optional Blizzard acquisition and generated derivatives went only to isolated temp
libraries. Later proofs used those isolated libraries without opening the app library.

| Fact | Male | Female |
| --- | --- | --- |
| Race / sex | 6 / 0 | 6 / 1 |
| ChrModel | 11 | 12 |
| Component texture layout | 113 | 114 |
| CreatureDisplayInfo | 55077 | 56316 |
| CreatureModelData | 7399 | 7576 |
| M2 FileDataID | 968705 | 986648 |
| LOD0 rest SKIN FileDataID | 968755 | 986679 |
| Native M2 vertices | 187473 | 195611 |
| Rest vertex lookup / triangle indices | 33925 / 138537 | 33542 / 135228 |

The exact joins use `ChrRaceXChrModel → ChrModel → CreatureDisplayInfo →
CreatureModelData`; no filename/name matching or guessed model choice is involved.
The recorded classes are Warrior1, Hunter3, Shaman7 and Druid11. Their unique
Purpose9 loadouts are 1581–1584, with race mask low word32/high word0 and
`PlayableRaceBit=5`. This is static build metadata, not account/realm availability.

## Selection and material evidence

Production `RaceCustomization.select` is invoked unchanged. It chooses the lowest
recorded eligible choice ID, not UiOrderIndex and not a claimed client default.
For both Warrior and Druid, the resulting male choice set is
`1045,1074,1084,1100,1107,7274,7343,7408,7425,9017`; the female set is
`1114,1135,1143,1161,1169,7282,7429,9019`. The selected option/choice requirements
are the existing reviewed 141/146 rules, with matching class/race masks and no
prerequisites, account, region or archive unlock assumptions. No curated choice
replacement was supplied by the probe.

Both layouts record body textureType1 at2048×1024, hair6 and fur8 at512×512,
eyes19 at256×128 and accessory20 at256×256. Existing canvas/pixel limits suffice.
Selected layer rows retain flags0, second target0, second mask−1 and blend1/15;
no blend or atlas rule is generalized. Texture candidates use the unchanged
sex/class/race qualifier ranking. In particular, resource680669's male FDID3592199
and female4293161 are distinguished by actual ComponentTextureFileData qualifiers;
the male profile never chooses the first ambiguous diffuse row.

| Composition | Selected diffuse FileDataIDs |
| --- | --- |
| Male sample | 974717,976670,974673,974469,3614550,987223,3592199 |
| Female sample | 996031,996030,996002,996016,995905,3614550,987223,3592137 |
| Male Warrior clothing | 151561,155342,157934,160720,163388 |
| Female Warrior clothing | 151561,155342,157934,160719,163387 |
| Male Druid clothing | 152477,154756,157013,157370,159692,162293,164990 |
| Female Druid clothing | 152477,154755,157013,157369,159692,162292,164989 |

All selected BLPs decoded and all four compositions completed. The exact production
M2/SKIN diffuse-binding restrictions stay unchanged. Male accepted bindings cover
body1 (31 surfaces), hair6 (20), fur8 (26). Male accessory20 is composited but its
fixed-type0 M2 draws are outside the admitted binding profile. Visible neutral IDs
are3301,3601,3701,3901,5101. Female accepted bindings cover body1 (31), hair6 (23),
fur8 (16), accessory20 (26); visible neutral IDs are3301,5101. Eye19 atlases have no
accepted draw binding under the current strict shader profile. Counts include
alternate sections; selected visibility is applied separately. Neutral-shading
warnings are present both cold and restored. These are useful partial static
previews, not complete material/shader rendering.

## Clothing and geometry limits

Warrior clothing6125/139 resolves to displays9182/8003. Druid robe6139 and pants6124
resolve to displays10119/5236. Druid robe geoset groups are `[0,0,1,0,0,0]`: the
existing slot rules select skirt1302 over pants1301. This robe is sleeveless; it
does not select802 merely because that alternative exists in the model. Body0,
recorded hairstyle and horns2401 remain present. Both sexes were visually inspected
with Warrior pants/vest and Druid robe.

These four starting loadouts contain no boots. Neither Tauren SKIN has group20
feet. This proposal adds no boot/hoof policy, no human foot geometry, no attached
weapons/shields and no animation. Later gear support requires separate evidence.

## Context-only compatibility-zero gate

The model/layout/sex admission is exactly `(race6,model11,layout113,sex0)` or
`(race6,model12,layout114,sex1)`. The prototype also requires the two recorded
fallback compatibility slots to be literal zero. Their unverified names are never
added to the generic field allowlist or trusted for another profile.

The selected Classic ChrModel table is FileDataID3384313,
CKey`5b0536a731396bdf8eeb07d2bab6ff95`, layout`03FAB755`: WDC5 schema5,
17 physical fields, flags0, inline ID index2, one section. Flattened slot12
(`ModelFallbackChrModelID` in the pinned DBD) maps to physical field8: signed-packed
kind5, bit offset248, width6, parameters56/6/0. Flattened slot13
(`TextureFallbackChrModelID`) maps to physical field9: COMMON kind2, bit offset254,
width0, default0,112 auxiliary bytes. Both models decode to literal0 in both slots.
The logical DBD types are signed32; the physical storage is not claimed to be32bit.
The contextual gate requires the exact layout, ordinal, scalar signed32 declaration,
non-ID/non-relation status, no raw interpretation/narrowing and exact zero text.
Nonzero, differently shaped or unreadable slots are unsupported; no fallback is
followed or interpreted.

Primary naming corroboration is
[TrinityCore ChrModelEntry at34fd0ebd83247ef0f5f72ffe537789c9e4c78928](https://github.com/TrinityCore/TrinityCore/blob/34fd0ebd83247ef0f5f72ffe537789c9e4c78928/src/server/game/DataStores/DB2Structure.h).
That is modern-source scope and cannot establish the Classic layout by field name
alone. The independent Classic physical audit above is required. The exact pinned
[WoWDBDefs ChrModel definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ChrModel.dbd)
and actual decoded row tuples establish this narrow compatibility gate, subject to
independent domain review.

## Cache and validation

The isolated policy revision is `human-orc-tauren-clothing-geosets-v4`. Existing
source, skin, recipe, locale and renderer identity bindings remain intact.
Real PackGeneratedStore derivatives were restored on repeat (35–60ms in this probe),
then restored again after closing and constructing a new RacesService on the same
pack store, for all four race/class/sex combinations. These timings do not describe
full app startup. Restored previews retain all partial/neutral/attachment warnings.

Deterministic authored regressions cover exact pair admission, fur/accessory canvas
sizes and sex qualification, wrong sex/layout/nonzero compatibility and altered logical/physical interpretation rejection,
no global admission of fallback names, and robe/body/horn preservation without
inventing feet or sleeves. Component check currently passes29 tests.

Local research-only artifacts (not committed assets) are
`/tmp/tauren-profile-outfit-proof.txt`, `/tmp/tauren-physical-profile-proof.txt`,
`/tmp/tauren-static-model-proof.txt`, and `/tmp/tauren-production-pack-proof.txt`.
Prototype runners are `/tmp/TaurenAppearanceProbe.java` and
`/tmp/DirectTaurenTables.java`; their authored table interface wraps actual same-build
Db2Reader rows, not fixture game data. Review approval is required before this
prototype becomes public profile support.
