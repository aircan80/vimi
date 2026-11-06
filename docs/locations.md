# Provider location contracts

`:concepts-locations` supplies NPC/object locations and explicit quest pickup
relationships from the same already prepared provider snapshots used by the
catalogues. It owns no database, repository or download client. Construction is
cold; `LocationsService` runs bounded local queries asynchronously. Closing it
cancels/drains owned work without closing borrowed repositories.

Provider contracts, adapters and source-frame projection are joined by the
[selected-build map/artwork service](map-artwork.md). GUI marker wiring remains
separate integration work for #116. No new network location service is used.

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

## Maps role overlays

The Maps browser can draw quest giver, vendor, trainer and flight master markers.
Each layer has its own footer toggle and all are off by default. `MapRoles` reads
only the build's mapped, already prepared QuestieDB version. It never downloads,
prepares or substitutes another flavor.

- Quest givers come from explicit NPC `questStarts` entries. The `QUEST_GIVER`
  NPC flag alone does not make one.
- Vendors, trainers and flight masters come from `npcFlags`. The bit layout
  differs by flavor. It follows QuestieDB v1.0.10
  `src/corrections/enum/expansions.lua` and the flavor `rules` map in
  `src/config.lua`. Vanilla and Forever use vendor 4, flight master 8 and
  trainer 16. TBC, Wrath, Cata and Mists use vendor 128, trainer 16 and flight
  master 8192. Any other release tag keeps this layout and reports the catalog
  as partial.
- Positions stay Questie AreaID percentages. The selected build resolves them in
  one batch through `UiMapAssignment` (`BuildLocationMaps.resolveAll`), and the
  existing area/world projection rules apply. A location is drawn on a UiMap
  only when every drawable candidate for that map agrees. Conflicting
  assignments and locations with no drawable candidate are counted, not drawn.
  Candidates outside the map's 0..1 frame (for example Questie's `-1,-1`
  placeholders) are reported and not drawn. As in the location map, a
  non-drawable candidate (WMO-scoped or partial rectangle) does not block a
  drawable one on the same UiMap. Placement resolves in chunks of 512, so
  interactive map requests can run in between.
- Assignment rows are indexed once by AreaID and MapID. Each coordinate checks
  only related rows, so unreadable UiMap references on unrelated rows no longer
  mark every resolution partial. AreaID or MapID read failures are still
  reported for coordinates of that kind.

The status line names the Questie release and flavor and counts each enabled
layer on the presented map (positions, not distinct NPCs). It also reports role
NPCs without recorded positions, unplaced and conflicting positions, the first
source or resolution issue, and the 2,048-marker cap. Phase, quest
availability and service inventories are not evaluated.

A faction choice (All factions, Alliance, Horde) filters the placed markers by
Questie `friendlyToFaction` without reloading: Alliance shows `A` and `AH` NPCs,
Horde shows `H` and `AH` (any case). NPCs with a missing or unrecognised faction
show only under All. Under a faction, the per-map counts say which faction they
are for, the status line counts the positions hidden as the other faction or as
unrecognised, and the catalog totals are marked as covering all factions. The
faction is Questie's record, not an evaluation of the player's reputation.

Hovering a marker lists the drawn markers within 6 px (nearest first, one per
NPC, at most five): name and subname, NPC ID, roles, recorded faction, the
recorded Questie position (AreaID and percent) and the QuestieDB release, and
says when more NPCs are in reach than are listed. Drawn markers are bucketed in a
16 px grid, so a hover checks only nearby cells. The tooltip is re-checked after
every repaint (zoom, filter, layer or placement change) and hides when nothing
drawn is under the pointer any more.

## World explorer minimap role markers

The World explorer's minimap can draw the same quest giver, vendor, trainer and
flight master markers, with its own layer toggles and faction choice in the
explorer footer. Each placed Maps marker is carried to world yards through the
selected build's recorded unscoped `UiMapAssignment` frames for the explorer's
current map (`MapRoles.world`, `MapTypes.WorldFrame.toWorld`). The inverse is the
exact algebraic inverse of the projection the resolver already applies
(`WorldFrame.toUi`); both use the same frame record. A marker gets a world point
only when every invertible frame of its UiMap that contains it gives the same
point (within 0.01 yards). Markers outside every frame, with conflicting frames
or with only degenerate frames are counted in the status line and not drawn.
Markers of UiMaps with no frame on the current map belong to other maps and are
not counted.

Questie records no height, so these markers are drawn on the 2D minimap only and
never in the 3D viewport; no terrain height is looked up or guessed. Hover
details and the faction filter work as on Maps, and the tooltip is re-checked
after every minimap repaint (movement, rotation, filter or layer change).

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
