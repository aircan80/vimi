# Build Comparison: WoW domain design

Status: proposed design for #117. This document contains domain recommendations,
not implementation approval. Independent review and recorded approval are required
before comparison code begins.

## Product question and comparison boundaries

The useful question is: **what changed in the recorded client data, and what can
we reliably say that means?** The first comparison should help a player recognize
an item, talent or recipe change and help a data miner trace it to exact client
fields. It must not present a changed file hash as a gameplay change or claim that
client records describe every live server rule.

A comparison has two explicit endpoints, A and B. Each endpoint fixes product,
full client build identifier, build/root configuration, requested locale, actual
source identities and projection/parser versions. A and B are directional labels;
the version number alone does not prove chronology or ancestry. Comparisons must
remain reproducible after a parser, definition or provider update.

Recommended comparison modes:

| Endpoints | Useful conclusion | Required treatment |
| --- | --- | --- |
| Same product, branch and locale; different builds | Recorded client changes | Normal starting workflow; retain schema/coverage differences |
| Same product family, beta versus live or another branch | Possible branch divergence | Explicit branch comparison; numeric ID alignment is provisional where continuity is not established |
| Different products/expansions | Exploratory differences and possible shared records | Separate namespaces by default; enable only reviewed cross-product alignments, never infer continuity from names or equal integers |
| Different locales of the same content | Localization differences | Label as localization; compare independently supported numeric facts separately |
| Different builds and locales | Mixed client/localization differences | Warn and separate text changes; do not attribute translated wording to the build update |
| Same build with different parser/definition revisions | Interpretation/readability changes | Diagnostic comparison, not a patch/gameplay-change report |
| Same client endpoints with different external snapshots | Provider observation changes | Separate provider section; never blend into Blizzard client changes |

A family classification must come from a reviewed product policy, not string
prefixes or an expansion number. In particular, Forever, Anniversary, Retail and
their beta products must not be automatically treated as one interchangeable ID
space. The user may explore other products, but unsupported semantic alignment
must remain visible rather than being silently guessed.

## Identity and evidence rules

Use a typed identity: domain, owning table/entity namespace and recorded ID. The
same integer in Item, Spell, CreatureDisplayInfo, NPC, AreaTable and UiMap is not
the same entity. Names are display labels, never fallback identity matches.

Within a reviewed compatible namespace, the stable record ID is the primary
match. For associations, preserve the relation's own ID and foreign keys. Also
show a grouped semantic view when useful, such as one dungeon's difficulty rows,
without collapsing distinct source records. If an association has no independent
ID, use an explicitly reviewed compound key; keep multiplicity and ordering where
they have meaning. Do not deduplicate relationships merely because their rendered
labels match. DB2 copy records retain their logical ID; a changed copy-source link
is a structural fact even when effective values remain equal.

Every displayed change needs its field/reference identity, endpoint values,
source table and content identity, schema/layout admission and readability. A
normalized semantic value may be compared only when both endpoint adapters are
reviewed for the same meaning and units. Preserve raw source values beside it.
Raw physical integers, unverified candidate field names or unrelated layouts must
not gain semantic admission because comparison needs a value.

A file has several different identities. A root FileDataID or recorded named-file
reference identifies an asset entry in a build; its content key identifies decoded
content; an encoding key identifies an encoded representation. An EKey change can
be encoding-only. A changed CKey establishes changed decoded bytes, not what the
player sees. Different M2/BLP bytes can leave the reviewed visible surface unchanged;
the same client bytes can behave differently under live server rules. File names
from a community listfile are provider labels and may improve without a client
asset change.

## Membership, partial data and unknowns

The comparison vocabulary must distinguish these cases:

| Evidence | Domain result |
| --- | --- |
| Readable entity in both endpoints, comparable field values differ | Confirmed recorded field change |
| Readable entity on one side; authoritative absence on the other | Confirmed recorded addition/removal within the declared scope |
| Readable entity on one side; encrypted, unsupported, missing preparation or unknown lookup on the other | Presence unresolved; newly readable/lost readability, not addition/removal |
| Entity in both endpoints; optional field unavailable on one side | Field comparison unknown; other readable fields remain comparable |
| Equal admitted values | Unchanged for those assessed fields, not proof the complete entity or gameplay is unchanged |
| Incompatible adapters or ID namespaces | Unsupported semantic comparison; retain independent endpoint inspection |

An authoritative absence must come from the table/file service's coverage-aware
boundary, not a missing item on a paginated screen, a search result, a truncated
scan or a failed projection. A PARTIAL table can still have a proven ABSENT lookup
where the existing service establishes coverage; do not replace that contract
with a blanket partial-table refusal. Conversely, no visible rows in a partial
relationship table does not establish that an entity has no relations.

An unprepared table is not an empty table. A file declared absent in the selected
build is different from a present but protected file. Optional-table absence may
explain an unsupported projection without proving that the player concept was
removed from the game. Group counts must say which members were assessed and
which remain unknown; an incomplete group must not be summarized as a definitive
size reduction. Show relationship changes and source coverage alongside the
parent entity rather than deleting a useful parent view.

The summary needs separate confirmed-change, readability/interpretation and
unresolved counts. Unknown records must not be counted as unchanged. A filtered
comparison must disclose its scope and coverage; it cannot imply an exhaustive
patch report.

## Useful changes in each current area

| Area | Player-facing questions | Data-miner detail and limits |
| --- | --- | --- |
| Builds / Overview | Which products/build endpoints, branch and locale are compared? What preparation is available? | Compare recorded build/configuration metadata. Download status, ownership, locally stored bytes and accounting are local observations, not client patch content. Shared/unique sizes depend on the library's current owners and measurement time. |
| Files | Which recorded assets appeared, disappeared or changed? | Match reviewed FileDataID/named-root identity; show decoded-content versus encoding changes separately. Distinguish provider listfile-label improvements, protected bytes and unavailable entries. Names do not prove rename continuity. |
| Tables | Which tables, layouts, rows and fields differ? | Separate source bytes, schemas/adapters, logical rows, copy links and readable coverage. Added readable rows after a key/definition update are interpretation/readability events unless opposite-side absence is proven. Unknown fields remain raw evidence. |
| Items | Did recorded name, quality, slot, level, stat, use/equip text or appearance change? | Match Item IDs; show admitted source values and derived calculations separately. ItemSparse/ItemModifiedAppearance/effect changes require exact joins. Scaling, level context, spell-description expansion and locale must be fixed or reported as differing contexts; do not call a rendered tooltip change a numerical buff without admitted values. |
| Creatures | Did a recorded creature display/model, dimensions, sounds or texture references change? | CreatureDisplayInfo/CreatureModelData identities are not NPC IDs. One display can serve multiple NPCs and one NPC can have multiple displays. Display changes are client asset facts, not a complete creature spawn/stat change. |
| NPCs | Did observed name, title, service roles, level observations or locations change? | Current NPC knowledge combines prepared external providers and any reviewed client relationships. Keep provider-specific facts, observation origin, build/flavor and snapshot changes separate. No static client table should be implied to enumerate every server NPC or spawn. |
| Quests | Did recorded title, text, starter/finisher, objectives or rewards change? | Preserve each reviewed source's quest namespace and provenance. Starter NPC/object/item, finisher NPC/object and objective targets are different relations. Item objectives need a separate acquisition relationship; a source item supplied by a starter is not an item that starts the quest. Order changes and counts matter. Observational incompleteness is not removal. |
| Talents | Did a recorded node, rank, position, prerequisite or spell effect change? | Match talent/rank/spell IDs within the reviewed tree system. Keep geometry/ordering, prerequisites and rank spell facts distinct. Classic three-tree and Retail systems require separate adapters; matching a translated name does not align them. A description-resolver improvement is not a spell change. |
| Races | Did lore, recorded class associations, racial cards, voices or supported model appearances change? | Use ChrRaces IDs and explicit class/model links. PlayableRaceBit is not raceID−1. A recorded class association does not prove account/server playability. Racial display-card records without SpellID must not be name-joined to spells. Sample-choice changes can reflect eligibility/selection policy rather than changed game defaults. |
| Dungeons / Raids | Did names, recorded encounters, literal entry messages or per-difficulty capacity change? | Match Map IDs and explicit MapDifficulty/DungeonEncounter relationships. Keep difficulty-specific records and capacities separate, even when a label such as “20 Player” conflicts with a numeric capacity. Queue facts require exact joins and are not universal entry rules. Associated area/interior is not an exterior entrance. Encounter records are not a complete boss/NPC/loot roster. |
| Mounts | Did name, description, spell reference, recorded variants or supported display appearance change? | Keep Mount, spell and display identities separate. Preserve multiple display variants and their conditions; an available preview does not prove ownership, learnability or live selection probability. Asset-only changes and metadata changes need independent results. |
| Professions / Recipes | Did an ability, skill requirement, admitted reagent/result association or recorded result count change? | SkillLine/SkillLineAbility, spell and recipe item IDs are different namespaces. Show exact relationships, not name matches. Learned ranks, server availability, conditional results and actual success/skill-up probabilities must not be inferred from incomplete static facts. Classify generated text versus admitted numeric/source changes separately. |
| World / Locations | Did map/area relationships, zone artwork, recorded terrain tiles or a supported terrain surface change? | Map, AreaTable, UiMap and UiMapArt IDs have different meanings. Use reviewed world/UI frame transformations with their evidence, never equal-ID guesses. WDT inventory, ADT height/hole geometry and texture/art changes are separate channels. Current explorer does not evaluate buildings, water, collision or server movement, so a terrain delta must not claim a complete zone change. |
| Data Sources / Settings | Which prepared snapshots, mappings or interpretation options affected this report? | Treat settings and source selection as comparison context. Report provider versions, source priority and policy changes; do not mix local enabled flags or refreshed source downloads with Blizzard client additions. Product-list visibility does not define whether a build exists. |

There is no standalone general Spells area in the current browser. Spell-backed
facts reached through items, talents, racial associations or recipes should retain
their spell provenance without implying complete spell coverage. A future Spells
adapter can add a dedicated channel after its identity/schema contract is reviewed.

## Text, numeric values and presentation

A changed localized string is useful on its own, but the label should say
“recorded text changed” when no admitted gameplay value backs it. Preserve
actual locale and fallback provenance per field. Equal requested locales do not
prove both values came from the same locale.

Raw spell descriptions can contain tokens, conditional branches and referenced
spells. Store the source string change independently from changes caused by a
resolver revision, chosen rank, player-level context or improved lookups. Retain
unresolved fragments and partial states. Formatting/whitespace differences may
have a separate display-only filter; never discard the original text or conceal
numbers solely because a normalized presentation is equal.

Numbers require admitted units and context: time units, percent versus fraction,
world yards versus normalized map coordinates, signed sentinels, scalar versus
bit mask, array ordering and race-mask word width must be explicit. A different
raw bit mask is not automatically a list of added playable races. Names may be
localized while an ID and its other facts remain comparable.

## Appearance and audio comparisons

Separate three questions:

1. Did an asset reference or source content change?
2. Did the same reviewed static preview policy produce a different output?
3. Is the gameplay or animated appearance known to differ?

The first two can be answered within the current architecture. The third generally
cannot: static native rest surfaces do not evaluate a full rig, animation,
attachments, client lighting, shader effects, live customization defaults or account
unlocks. An identical static image does not prove identical animations or voices.
A changed image does not prove the underlying client changed if the renderer,
geoset rules, texture decoder, sample choice or camera changed.

For a useful static appearance comparison, fix model/sex/class, explicit recorded
customization choice IDs where both endpoints support them, clothing selection
policy, pose/rest mode, camera and rendering policy. If an endpoint lacks an
eligible choice, report the difference in sample selection and avoid presenting
the pair as the same character. Current reviewed Human/Orc profiles can establish
narrow static comparisons; unsupported races retain geometry/source evidence and
an explicit texture limitation. Neutral-shaded or unbound surfaces stay flagged
in cached and fresh previews.

Voice clip changes need the exact action/race/sex/class/locale relationship, sound
kit membership and file identities. A kit membership change is not necessarily a
new spoken line; multiple encoded assets may contain the same audio. Waveform or
playback inspection can be separate from semantic/transcript claims. No automatic
voice playback is needed for comparison.

## External NPC/quest/location evidence

Freeze provider snapshots independently at both endpoints. The default meaningful
client comparison should hold the external snapshots fixed where compatible, or
clearly mark provider changes as a separate axis. “Newest source available for
each build” is convenient browsing policy but not a reproducible control for
attributing a quest or NPC change to a client build.

Questie flavor mappings may be user-selected. Forever observations from Polestar
or EG Link need their own product/build compatibility evidence; do not substitute
a nearby snapshot and silently claim exact-build facts. Different source priorities
can change the chosen display value without either provider changing. Preserve
disagreement and both evidence trails.

Location evidence must retain coordinate system and origin. Questie area-keyed
percentages are not UiMap IDs and their optional third coordinate is phase, not
altitude. Polestar spawn points are world yards on a client Map ID; its UiMap frame
bounds use worldY for left/right and worldX for top/bottom. EG interaction points
are often where the player stood, not exact NPC spawn positions; combat-log world
points use a different schema. Location projection/artwork revisions are another
interpretation axis. Display an approximate interaction move differently from a
confirmed recorded spawn-coordinate change.

## Recommended first reviewed slice

This role recommendation concerns the first semantic adapters. The consolidated
[delivery plan](README.md) precedes them with the approved Files/table inventory
foundation; it controls implementation order and the initial same-product/locale
capabilities. No durable resume or full-area completion is implied.

Start with two compatible same-product/same-locale endpoints and an explicit
field set per existing concept. Provide added/removed/changed/unchanged-assessed/
unknown states with record drill-down, source coverage and separate asset/provider
channels. Items, Classic talents and dungeon metadata offer understandable first
examples, but the design must preserve the same uncertainty contract for every
current area rather than displaying unsupported areas as zero changes.

Before approval, reviewers should decide the supported product-family alignment
matrix, per-area admitted fields, whether cross-locale mode is initially exposed,
the default external-snapshot control, and which fixed static preview policies can
be compared. Runtime/storage/job/UI designs belong to the corresponding role
proposals; this document does not authorize comparison implementation.

## Primary references grounding these rules

- [Pinned WoWDBDefs definitions](https://github.com/wowdev/WoWDBDefs/tree/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions): explicit namespaces, layouts and foreign keys; field availability differs by admitted layout.
- [QuestieDB typed records](https://github.com/Questie/QuestieDB/blob/master/src/types/General.t.lua) and [quest field meanings](https://github.com/Questie/QuestieDB/blob/master/src/types/Quest.t.lua): coordinate phase, starters/finishers/objectives and source-item distinctions.
- [Polestar spawn schema](https://github.com/fitzforge/polestar/blob/next/forever-db/schema/spawn.schema.json) and [UiMap frame schema](https://github.com/fitzforge/polestar/blob/next/forever-db/schema/ui_map.schema.json): world units, typed map IDs and axis orientation.
- [EG Link dataset descriptions](https://github.com/JIVESCORP/eg-link-output-wowf/blob/main/DATASETS.md): interaction versus combat-log coordinates and observational provenance.
- [WoW Model Viewer customization rules](https://github.com/wowmodelviewer/wowmodelviewer/blob/develop/Source/games/wow/CharDetails.cpp): player requirements, active geoset groups and body/scalp preservation. Client-default or animation equivalence is not established by these sample rules.

External reference branches above can move; the implemented comparison must record
the exact provider/definition revisions it used rather than depending on a mutable
reference URL. Public documentation should contain authored fixtures or summaries,
not downloaded game payloads, user libraries, credentials or private paths.
