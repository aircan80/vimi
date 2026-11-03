# Provider location contracts

`:concepts-locations` supplies NPC/object locations and explicit quest pickup
relationships from the same already prepared provider snapshots used by the
catalogues. It owns no database, repository or download client. Construction is
cold; `LocationsService` runs bounded local queries asynchronously. Closing it
cancels/drains owned work without closing borrowed repositories.

The first stage provides contracts, adapters, source-frame projection and tests.
The selected-build map/area resolver, Blizzard map artwork and GUI marker wiring
are separate integration work for #116. No new network location service is used.

## Native coordinates remain distinct

| Source | Map identifier | Coordinates | Important qualification |
|---|---|---|---|
| Questie NPC/object `spawns` | AreaID | x/y in 0–100 percent | Optional third tuple value is phase, never height |
| Polestar `spawn` | client Map ID | world yards: x north, y west; optional z | Rounded source points, not guaranteed live placements |
| EG `npcs` interaction `positions` | UiMapID and/or client Map ID in `instance` | normalized mapX/mapY and worldX/worldY | Where the player stood during interaction/looting, not an exact NPC spawn |

One `Location` can contain multiple coordinate alternatives for the **same**
observation, as EG supplies both map and world coordinates. Do not render these
alternatives as duplicate markers. `Origin` distinguishes declared spawn data
from observed interaction locations. Phase remains source evidence; current
character phase, quest availability, patrol behavior and account state are not
inferred. Missing optional phase/height retains readable x/y with a diagnostic.

Questie data types explicitly distinguish AreaID-keyed spawns from UiMap IDs in
[General.t.lua](https://github.com/Questie/QuestieDB/blob/master/src/types/General.t.lua).
Questie's [map consumer](https://github.com/Questie/Questie/blob/master/Modules/Map/QuestieMap.lua)
resolves `GetUiMapIdByAreaId` and then divides percent coordinates by 100. Our
adapter preserves AreaID until a reviewed selected-build crosswalk is supplied.
Neither `zoneID` nor quest `zoneOrSort` supplies that crosswalk; negative
`zoneOrSort` values are quest-sort identifiers.

Polestar publishes the coordinate meanings in its
[spawn schema](https://github.com/fitzforge/polestar/blob/next/forever-db/schema/spawn.schema.json)
and [map-frame schema](https://github.com/fitzforge/polestar/blob/next/forever-db/schema/ui_map.schema.json).
EG documents its interaction positions and the different combat-log coordinate
shape in [DATASETS.md](https://github.com/JIVESCORP/eg-link-output-wowf/blob/main/DATASETS.md).
The combat-log `{map,uiMap,x,y}` shape is explicitly unsupported by the interaction
adapter; its x/y must never be mistaken for normalized mapX/mapY.

## Map projection

`LocationProjection.directUiMap` admits a unique recorded normalized UiMap
coordinate. `throughFrame` requires a world point and frame from the same provider
snapshot and matching client Map ID. It uses the recorded signed edges:

```text
mapX = (worldY - left) / (right - left)
mapY = (worldX - top) / (bottom - top)
```

Do not sort/reverse those edges or swap the world axes. Outside-frame points are
not clamped onto a map border. Cross-snapshot compatibility is not assumed.
Multiple source frames remain available; no arbitrary smallest map or floor is
chosen. An AreaID is never treated numerically as a UiMapID. Blizzard's
UiMapAssignment/artwork integration must explicitly establish the remaining
selected-build relationships before plotting these native coordinates.

`READY` means source facts were read/parsed; it does **not** promise a drawable
map, complete server coverage or build compatibility. Projection has its own
outcome. In particular, a perfectly readable Questie location can still need
AreaID-to-UiMap conversion.

## Quest pickup relationships

Only explicit starter evidence creates a `Starter`:

- Questie `Quest.startedBy[1]` NPCs, `[2]` objects, `[3]` items.
- Polestar `quest_start` rows with independent `quest`, typed `kind` and `id`.
- EG NPC/object `offers` quest-ID keyed objects.

Questie `finishedBy`, objectives, kill-credit targets, required/provided items,
`sourceItemId`, and EG `takes` never become pickup relationships. Questie's
[source item field](https://github.com/Questie/QuestieDB/blob/master/src/types/Quest.t.lua)
is an item supplied by a starter, not an item that starts a quest.
Polestar documents starter kinds in
[quest_start.schema.json](https://github.com/fitzforge/polestar/blob/next/forever-db/schema/quest_start.schema.json).

An item starter remains an explicit known relationship with unsupported intrinsic
map position. Its drops/vendors are separate acquisition relationships, never
silently promoted to quest pickup markers. Reverse Questie NPC/object
`questStarts` and item `startQuest` are possible future enrichment; the first
adapter uses the quest's explicit `startedBy` groups.

## Provider boundaries and selection

`LocationSources` accepts the already selected prepared version/snapshot; it never
refreshes, downloads, prepares, picks another flavor or substitutes another build.
The Questie flavor and schema profile are checked against the opened preparation.
The coordinating data-source selection still decides which snapshots apply.
Provenance retains provider, snapshot, source product/build/locale, license,
dataset, record identity and evidence field. Do not overwrite source build with
the target build, or infer that matching IDs establish cross-version validity.

Candidates are ordered Questie → Polestar → EG and unioned without discarding
lower-priority additional locations. Repeated starter entities merge their
evidence; NPC and object IDs occupy different namespaces. All valid coordinate
alternatives remain inspectable. A missing provider fails independently and does
not hide other readable facts. Empty recorded lists differ from missing fields;
neither proves absence from the game.

Polestar's new `lookupPage` reads existing prepared indexed keys with totals,
avoiding the old 500-record cap. Spawn records are filtered by entity kind after
ID lookup. Its `quest_start` record key is the starter ID, **not quest ID**;
therefore a bounded local quest-start index is built once from prepared rows.
EG likewise indexes only NPC/object `offers` from its selected `npcs` dataset.
Absent `npcs` stays unknown, with no fallback to a different EG build.

Limits cover source inputs, coordinate/starter counts, source paging, JSON
size/depth/value count, index evidence, cache entries/point budget, worker queue
and diagnostics. Over-limit/incomplete reads retain a bounded readable prefix
with explicit partial coverage. Partial phases, heights and coordinate alternatives
do not block neighboring valid facts. Prepared snapshots are immutable; recreate
the handle when source selection/preparation changes.

## Verification

```sh
./gradlew :concepts-locations:check :polestar:check
```

The 27 focused authored tests cover area/UiMap/world separation, phase vs height,
signed-frame projection, multiple maps/locations, optional unknown fields,
partial/bounded records, explicit starter kinds, objective/finisher exclusion,
item-starter limits, observed-vs-spawn locations, malformed JSON/shapes, source
priority/provenance, cancellation and lifetime. A provider integration test reads
501 keyed spawn rows in two local pages while its network client is offline.

A separate offline proof used scratch copies of existing prepared public-source
snapshots: QuestieDB v1.0.10 Forever, Polestar 2026.11.1 (1.60.1.70532), and an
explicit EG Classic-beta 1.60.1.70532 snapshot. Quest 788 identifies NPC 3143 as a
starter in all three. Questie records Area14 (42.06%,68.33%); Polestar records
Map1 world(-600.5,-4186.5,z41.3), which its own UiMap1411 frame projects to about
(0.420615,0.683357). EG independently records an observed interaction at normalized
UiMap1411 (0.4214,0.6841) plus world coordinates. These differences remain facts;
they are not averaged or asserted identical. All 59 Polestar frames were read,
and network-client invocation count was zero. Original provider databases were
read only; copies and probe work stayed outside the application data directory.
The mixed proof snapshots demonstrate adapters, not a new application selection
policy or cross-product compatibility guarantee. No source payloads, local paths
or private user records are committed.
