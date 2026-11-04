# Maps explorer proposal

Status: design for review; no Maps browser implementation or release is claimed.
The requested feature is a dedicated **Maps** workspace for browsing selected-build
2D artwork, separate from the 3D World Explorer. First delivery should support
Azeroth → continent → zone, clicking a map region, going back/up, and switching
directly to any readable map. Later releases add quest/NPC layers.

## Proposed experience

A large map occupies the center. Above it are Back, Forward, Up, clickable
breadcrumbs and a searchable map picker. A compact child-map list sits beside it;
this remains usable when a region cannot be placed on the artwork. Include map
name, type and ID in search results so duplicate names are distinguishable.

- **Back/Forward** follow the user's visit history, including direct switches.
  **Up** follows the selected map's recorded parent; it does not guess the map
  the user visited previously. Breadcrumbs follow the same validated parent chain.
- Clicking a single qualified child region opens it. Hover identifies the target
  and outlines its recorded rectangle. Overlapping candidates open a small named
  chooser; no nearest/smallest map is silently preferred. The child list is an
  equivalent keyboard-accessible route.
- Wheel zoom and drag pan explore the current artwork. They do not unexpectedly
  change maps. A Fit button restores the full map. Up remains explicit; optional
  right-click Up can be added with a visible shortcut hint.
- The picker changes maps without requiring a trip through Azeroth. Default
  filters expose World-system maps; cities, battlegrounds and other readable
  types remain available. Taxi/Adventure systems are separate advanced filters,
  not alternative duplicate continents in the default list.
- On first opening, use a saved map valid for this build/locale, otherwise a unique
  recorded World-system world root. The inspected Forever build supplies Azeroth.
  If several roots qualify, show a root chooser. Do not identify a root by an
  English name or assume ID947 for every build.
- Remember a bounded history entry's map, selected floor/art variant, pan center
  and zoom. Build/locale or preparation revision changes invalidate old handles;
  a restored ID must be revalidated before use.

The hierarchy is source data, not a fixed three-level tree. Cities may be direct
children of a continent even when geographically inside a zone. An island can be
a direct child of Azeroth. Missing parents and cycles produce a usable standalone
map with a diagnostic, not invented breadcrumb levels.

## What already exists

`concepts-locations` provides `BuildLocationMaps` / `LocationMapsService` and
`MapTypes`, documented in [map-artwork.md](map-artwork.md). It already:

- distinguishes UiMap, client Map and Area IDs;
- resolves declared provider coordinates through qualified assignments, retaining
  ambiguity and source evidence;
- reads phase-zero artwork through
  `UiMap → UiMapXMapArt → UiMapArt → UiMapArtStyleLayer + UiMapArtTile`;
- supports cropped edge tiles, partial artwork, exact recorded FileDataIDs and
  bounded BLP decoding with the existing generated-image cache.

`LocationMapView` currently embeds this in NPC/quest sidebars. Reuse its backend
and rendering conventions, but do not stretch that entity-selection view into
another owner of full-catalog navigation. The new Maps view should use a reusable
map canvas/controller; existing sidebar callers retain their current behavior.
`WorldService.MapInfo` and WDT terrain tiles describe 3D maps, not this hierarchy.

## Selected-build evidence and dungeon coverage

A local-only probe of Forever1.60.1.70755 used original library objects read-only
and a separate generated scratch store. No artwork downloads, network client,
account/settings writes or game images were involved. Private proof and raw rows
remain outside Git in `/tmp/vimi-maps-design-catalog-proof.log`.

| Table | Physical layout | Readable/declared rows | Content key |
| --- | --- | ---: | --- |
| UiMap | DB51D55F | 60/60 | 521ec5030f69b979d9cc21ed2ba23132 |
| UiMapAssignment | C9CC8DFB | 61/61 | 47db4b120a379ad5e94743e43e0b7933 |
| UiMapLink | 712A46E6 | 0/0 | 07433a6e69ba26aa1b1c1c82e4337e81 |
| UiMapGroup | 6D5EB3BD | 0/0 | 9c6812de2c643fcd7fa0b6ffaeb03fb7 |
| UiMapGroupMember | 0FF75B01 | 0/0 | 34abf63bbf2c327d623ed67ddeca8273 |

Recorded parent chains include Azeroth947 → Eastern Kingdoms1415 → Elwynn
Forest1429, and Azeroth947 → Kalimdor1414 → Durotar1411. Metadata-only artwork
checks return READY for Azeroth947/art2141, Kalimdor1414/art2142, Eastern
Kingdoms1415/art2140, Elwynn1429/art2153 and Stormwind1453/art2146. Each declares
1002×668 pixels and twelve256px tiles. These checks establish manifests, not that
every encoded image is locally present or drawable.

The60-row UiMap catalogue has **no Type4 dungeon or Type5 micro maps**. Both
floor-group tables are readable and empty. There is therefore no declared dungeon
or floor UiMap to expose through this first supported route in the inspected
build. This does not establish that no dungeon-related image exists anywhere in
its assets. Existing Dungeons UI shows instance metadata; it is not a dungeon-map
viewer. Do not substitute entrance coordinates, terrain minimaps or another
build's map as a dungeon floor.

The design should nevertheless support dungeon/raid UiMaps when another qualified
selected build declares them. Expose a floor selector only through qualified
`UiMapGroupMember` relationships, preserving `FloorIndex`, `RelativeHeightIndex`,
label and group/map IDs. Groups are a separate relationship from world parents.
Do not select a floor from approximate height or fabricate floor1. Empty,
unsupported or conflicting group data gets an explicit no-floor-data state. The
three newly inspected layouts above still need production admission/tests; an
empty source is not positive evidence for every field's runtime semantics.

## Navigation metadata and hit regions

Extend the existing build-scoped map facade with optional asynchronous methods
(names are provisional):

```java
CompletableFuture<MapCatalog> catalog(Cancellation token);
CompletableFuture<MapPage> page(long uiMapId, Cancellation token);
// page: selected node, validated parent path, child choices, qualified regions,
//       optional floor choices, diagnostics and source identities.
// Existing artwork(uiMapId) and tile(uiMapId, tileRowId) remain reusable.
```

Default implementations on custom providers return explicit unsupported results.
Metadata results are detached and immutable. Their generation combines build,
locale, prepared table identity and the reviewed navigation profile. Query metadata
on the existing bounded service worker; never page tables or parse schemas on FX.
Validate declared foreign keys and exact physical layouts before interpreting
fields. `UiMap.ParentUiMapID` establishes hierarchy; `System` keeps coordinate
systems separate. Preserve a missing parent separately from a readable zero.
Read optional type/name/visibility facts without hiding an otherwise readable ID.

Click regions need additional qualification:

1. A readable, nonconflicting direct `UiMapLink` rectangle can describe its exact
   declared parent/child relationship. Preserve conditions/flags; unevaluated
   character eligibility must not become an availability assertion. No such rows
   exist in the inspected Forever build.
2. For a recorded child without a usable link, a bounded profile can derive an
   **assignment rectangle**, not a geographic polygon: pair child/parent
   `UiMapAssignment` rows only when they share the same client MapID and reviewed
   unscoped coordinate frame. Intersect their world domains, map the intersection
   through the parent's signed Region/UiMin/UiMax transform and clip it to that
   parent's assignment domain and the image extent. Retain both row IDs and keys.
   Child full-extent coordinates, finite nondegenerate bounds and same-system
   parentage are required initially. A parent declaring no world positions, or
   unsupported frame-affecting flags, does not qualify for this derived route.
   A partial child/WMO frame needs a separately reviewed rule. Distinct valid pairings remain distinct candidates.
3. Missing, degenerate, contradictory or nonintersecting frames leave a child
   available in the list/picker, without an invented hotspot. Never infer a map
   link from a similar name, filename or numeric equality between ID domains.

Azeroth947 has two declared inset assignments: row46784/Map0 with
UiMin(.5505,.0994), UiMax(.8966,.8691), and row46785/Map1 with
UiMin(.0399,.0855), UiMax(.4083,.9234). Its child continent world extents differ
from those parent assignment domains, making domain intersection important. Elwynn
uses assignment46739 and Eastern Kingdoms46725 on Map0. Independent asymmetric
corner/interior tests and a private actual-art alignment check gate these rules.

Rectangles can overlap and include ocean or adjacent territory. They must be
presented as map regions, not exact zone borders. Parent-child relationships and
a rectangle do not prove pixel-perfect client hit testing. The exact70755 UI
calls native `C_Map.GetMapInfoAtPosition` and `GetMapRectOnMap`; the Lua mirror does
not expose that engine implementation. Source-derived rectangular navigation with
an overlap chooser is the first honest delivery. A future outline/highlight mask
reader needs its own source-backed format/semantics review.

For reference, TrinityCore separately derives parent bounds from shared-map
assignments and overrides them with explicit links; our profile must additionally
preserve ambiguity rather than copying its first-match behavior. See the
[primary source](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/DataStores/DB2Stores.cpp).
Exact local definitions are bundled at WoWDBDefs revision
b9a63096cdd99b26967f17f9515377ae62cadfd7; client UI evidence is Gethe's Forever mirror
17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c
([map canvas source](https://github.com/Gethe/wow-ui-source/blob/17ed2c3e8471cdc3806ab0d26a082be7c67d3d7c/Interface/AddOns/Blizzard_MapCanvas/Blizzard_MapCanvas.lua)).

## Loading, rendering and lifetime

Use JavaFX 2D image tiles and a small overlay canvas. Pan/zoom changes transforms
and visible tile selection; it does not decode/recompose a whole8192px image on
every mouse movement. Native bgfx is unnecessary for this 2D feature.

Maintain one active loading plan and one latest pending plan per Maps view, with
one image decode/upload lane. Prioritize visible tiles, then a one-tile border;
do not fetch all256manifest cells or every map while opening the catalogue.
Preflight count/byte budgets before starting a tile read. Reuse already loaded
matching tiles on pan and restore persistent decoded images through `BlpImages`.
Do not call acquisition merely to build the picker.

Show distinct activity for catalogue, artwork manifest and visible images. Image
progress may say “8 of12visible tiles loaded”; no guessed byte/global percentage.
Keep the old image labelled while switching, then publish target metadata and its
first accepted image together. Old hit regions must not remain active over new
artwork. Loading continues until every required visible tile is accepted or has a
terminal missing/protected/unsupported result. A partial map keeps readable tiles,
a local retry action and an explanation. Artwork conditions/character phase remain
unevaluated. Local-only or disabled download policy should say artwork is not
available locally and preserve navigation controls.

All source calls, byte conversion and decoding run off FX. FX performs bounded
image publication and drawing. Each delivery verifies owner, table/preparation
revision, selected UiMap/art/floor, viewport generation and content identity.
Cancellation and cache/source close occur off FX where callbacks may block. A
latest-slot replacement must explicitly retire resource-owning pending work;
clearing an executor queue must not orphan image buffers. Hide/close stops new
loads, cancels/drains owned work and releases pending/current pixels before
borrowed workspace services retire.

Proposed first budgets, to be measured before implementation approval:

- Preserve existing table caps:8192UiMaps,16384assignments,256art cells,
  8192per layer side/16,777,216logical pixels,512px per tile/2MiB encoded source/
  1MiB decoded pixels. Check caller counts before copies.
- New detached navigation index:16MiB logical budget, with explicit per-record
  charges (node≤1KiB with≤256UTF-16name units, assignment≤256B, derived region≤128B).
  Fixed source references replace repeated per-field evidence-string copies.
  Reject/mark partial before exceeding the budget; do not silently truncate roots.
- Bound artwork manifests to a16-entry LRU instead of the existing unbounded
  `artwork` map. Preserve old API results; active UI frames own detached snapshots.
  Existing cached raw table rows are a separate lower-layer budget and must be
  included in the real heap/copy audit, not hidden inside the new16MiB figure.
- At most32resident tile images for each current/pending target and16MiB of
  decoded pixels per target, within a shared32MiB physical image-residency cap.
  Charge a second copy for estimated graphics residency, plus bounded2MiB input,
  decode/conversion/upload scratch and a1600×1000overlay backing surface. Target a
  96MiB logical image envelope with a precise copy/lifetime ledger; this is not a
  cross-platform process-RSS/VRAM guarantee. Under pressure preserve the current
  map, reduce border prefetch and report the limit instead of raising caps.
- Back/Forward history64entries holds IDs and view parameters, not pixel caches.
  No independent cache per breadcrumb, floor, provider or entity.

## Future quest and NPC layers

Keep base artwork, navigation regions and entity overlays separate. Each overlay
record needs an entity/quest ID and role, original provider/snapshot/build/locale,
original coordinate kind/units, resolved UiMap/assignment/frame and optional phase
or floor context. Retain multiple source observations; do not merge disagreement
into one apparently authoritative point.

Reuse `LocationTypes` and `BuildLocationMaps.resolve`. AreaID-keyed Questie spawns
are not UiMap coordinates. Existing full-area-percent admission, signed world
projection and ambiguity checks remain; parent-map markers require an explicit
qualified transform. Unknown or multiply resolved locations stay counted as
unplotted. A coordinate without reliable dungeon-floor context is not placed on
an arbitrarily selected floor.

Layer controls can later offer quest starts, quest ends, NPCs and objects, with
provider filters and a legend. Marker selection opens a bounded detail panel with
quest/entity links and evidence. Declare them recorded starter/finisher locations,
not live character quest availability. An observed player interaction remains
visually distinct from a declared spawn. Zoomed-out maps use viewport queries and
clusters with counts rather than thousands of JavaFX nodes.

Current `BuildLocations` supports per-entity locations and quest pickups only.
Questie imports `finishedBy`, but there is no reviewed `turnIns` facade yet.
Map-wide overlays therefore require separately reviewed starter/finisher adapters
and a build/provider-revision-bound spatial projection/index, not an FX loop over
all NPCs or a new download of every provider. Proposed first overlay query returns
≤2000visible markers plus explicit truncated/unplotted counts. It must read only
enabled, already prepared source snapshots and retain existing product/flavor
compatibility policy. Icons, clustering and character eligibility are later work.

## Delivery and verification

1. **Maps navigation and artwork:** qualified catalogue/hierarchy/assignment
   regions; Maps workspace; breadcrumbs, history, picker, clickable unique regions
   and overlap chooser; async tiled artwork, progress and partial/offline states.
   Include dungeon/floor choices only where qualified source facts exist; this
   Forever snapshot has none. No new entity-overlay capability is claimed.
2. **Overlay foundations:** bounded viewport/spatial queries, provider/coordinate
   provenance, cluster/selection model and layer filters. Keep NPC/quest sidebars
   compatible; qualify turn-in relationships before presenting their icons.
3. **Quest/NPC layers:** recorded pickup/turn-in/NPC/object icons and linked details,
   with ambiguity, floor and incomplete-source behavior visible.

These are feature milestones that may share prereleases when independently ready;
there is no reserved release number or requirement to split near-ready work.

Required authored checks cover duplicate names, World/Taxi separation, missing and
cyclic parents, direct-switch Back versus Up, cities/orphans, duplicate/conditional
links, asymmetric signed projections, partial parent frames, domain clipping,
overlap choice, border hit testing after pan/zoom/letterboxing, cropped tiles,
missing art and source revision changes. Include adversarial counts before copies,
cache-pressure refusal, cancellation and resource release through every stale or
rejected delivery. FX tests must show activity through accepted tile presentation
and preserve UI responsiveness while sources are held. A private selected-build
Azeroth→continent→zone cold/warm proof gates actual alignment and artwork claims;
all checked-in screenshots/fixtures must be authored, not game artwork.
