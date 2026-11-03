# Selected-build location maps and artwork

`BuildLocationMaps` resolves provider locations through the selected build's
client tables, exposes phase-zero artwork metadata, and decodes explicitly
requested BLP tiles. `LocationMapsService` borrows `BuildTables`, `BuildFiles` and
`GeneratedStore`; closing it cancels/drains its own work before the workspace
closes those borrowed handles. It contains no JavaFX or provider SQL.

```java
var maps = new LocationMapsService(tables, files, generated);
var resolution = maps.resolve(location, cancel);
var artwork = maps.artwork(uiMapId, cancel);
var image = maps.tile(uiMapId, tileRowId, cancel);
```

The one-argument constructor supports metadata without image services. Calls are
asynchronous and bounded. Metadata calls do not fetch image assets. The GUI should
request only the tiles it needs, using a bounded loading sequence; the component
serializes its worker and limits its queue to 32 requests.

## Coordinate resolution

- A recorded UiMap coordinate first resolves that exact `UiMap` ID. A world
  alternative for the same observation does not create a duplicate marker on
  that UiMap.
- Area-percent coordinates follow **declared** `UiMapAssignment.AreaID → UiMapID`
  references. Direct percent transfer is reviewed only for full `UiMin=(0,0)` /
  `UiMax=(1,1)` rectangles and assignments without WMO context requirements.
  Otherwise the known map association remains available with nullable x/y.
- World-yard coordinates require matching client `MapID`, an unscoped assignment,
  readable nondegenerate x/y region bounds and an inside-region point. World y
  maps to UI x, world x to UI y, with the recorded reversed orientation. Valid
  supplied z is checked against readable height bounds; no nearest floor or
  missing height is invented. Optional unreadable height bounds retain x/y with
  a diagnostic.

Every candidate retains assignment/source cells and the original provider
observation. Multiple matching maps or assignments remain explicit; no arbitrary
nearest, smallest, parent map, or floor is selected. Only system 0 World assignments
are admitted for computed projections. Character phase/visibility is unevaluated.
Map names have ID fallbacks; unreadable optional names/metadata do not hide valid
relationships. Area IDs are never cast to UiMap IDs.

The exact bounds orientation is reviewed against
[TrinityCore's GetUiMapPosition](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/DataStores/DB2Stores.cpp)
and [UiMapAssignment definitions](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/UiMapAssignment.dbd).
Its ordered Region array is minX,minY,minZ,maxX,maxY,maxZ; the component uses the
recorded UiMin/UiMax interpolation rather than sorting or guessing bounds.

## Artwork and tiles

The recorded route is:

`UiMap → UiMapXMapArt → UiMapArt → UiMapArtStyleLayer + UiMapArtTile`

Only one distinct phase 0 art ID and one layer 0 style row are selected by default.
Other recorded phase links remain in the metadata, but there is no fallback to a
nonzero phase. Layer dimensions/tile sizes come from the style; rows/columns and
FileDataIDs come from art tiles. No texture filenames or ID equality are guessed.

The metadata records logical x/y placement and visible edge dimensions. A
1002×668 layer with 256 px tiles has 4 columns and 3 rows; its last column/row are
cropped to the declared map extent. Conflicting duplicate cells are omitted while
other cells remain usable. Missing/protected/partial tiles do not block other
readable tiles. Known map/art/layer facts remain available when an optional tile
table is unsupported or unavailable.

Bounds are 8192 per layer side, 16,777,216 logical pixels, 256 tile cells, 512 per tile
side, 2 MiB source per tile and 1 MiB decoded RGBA per tile. A tile must belong to the
readable selected phase 0/layer 0 manifest. Decoded image dimensions must match its
declared full or cropped edge dimensions; images are not silently resized to fit
an incompatible grid. Existing `BlpImages` content/decoder-bound generated caching
is reused, so reopening the same tile can restore decoded pixels.

Definitions: [UiMapXMapArt](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/UiMapXMapArt.dbd),
[UiMapArt](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/UiMapArt.dbd),
[UiMapArtStyleLayer](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/UiMapArtStyleLayer.dbd)
and [UiMapArtTile](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/UiMapArtTile.dbd).

## Reviewed Forever profiles and proof

| Table | Layout | Readable 70707 snapshot rows |
|---|---|---:|
| UiMap | DB51D55F | 60 |
| UiMapAssignment | C9CC8DFB | 61 |
| UiMapXMapArt | EAE7DA2A | 60 |
| UiMapArt | 3AE7D144 | 144 |
| UiMapArtStyleLayer | 3F597F5A | 6 |
| UiMapArtTile | 2DA5B77B | 1672 |

Counts are verification facts, not required totals. Unsupported layouts fail
explicitly per relationship; readable PARTIAL rows remain usable. Recreate the
handle when the workspace's preparation/table revision changes.

A separate proof decoded rows **directly from the selected build's physical DB2
files**, using original library objects read-only and a separate scratch store
for any probe downloads/derivatives. Forever 1.60.1.70707 resolves Area 14 through
assignment 46721 to UiMap 1411 (Durotar). XMapArt row 2409 chooses phase 0 Art 2169,
Style 1, a 1002×668 layer with 12 tiles. Tile row 101294 references FileDataID 8073638;
it decoded at 256×256 in about 703 ms, then restored from generated cache in 4 ms.
No actual map image or client source payload is committed.

```sh
./gradlew :concepts-locations:check
```

Fourteen authored map tests cover relationship-based area mapping, world axes and
map scope, alternative deduplication, unresolved rectangles/WMO context, ambiguous
assignments/phases, logical tile grids and edge crop, partial/conflicting cells,
optional field failure, bounded image decode/cache and borrowed lifetime. Art IDs
above 127 exercise value equality rather than accidentally relying on Java's
boxed-number cache. The existing 27 provider-location tests run with them.

Marker UI wiring and selected-source policy are owned by the coordinating GUI
integration; see [provider contracts](locations.md).

## NPC and quest sidebar

Selecting an NPC or quest opens a zone-map panel in its existing detail sidebar.
Quest markers describe explicit pickup/starter relationships. They do not mix in
quest objectives or turn-in locations. An item starter remains a known relationship
without an invented intrinsic position.

Choose another map from the compact selector when observations cover several
maps. Blue markers are declared source spawns; amber markers are approximate
observed player interactions. Hover for provider/snapshot, entity ID, normalized
position, recorded phase and assignment basis. Unknown or ambiguous coordinates
remain unplotted; a known map can still show artwork without a marker. Base artwork
does not evaluate a player's phase, unlocks or current visibility.

The GUI reads only already prepared enabled provider snapshots using the same
flavor/product/build policy as the catalogues. Source handles/cache survive row
selection and retire on catalogue/source/workspace invalidation or close. The
selected build owns map-table/file services; closing drains them before borrowed
tables. Original artwork uses normal build-owned Blizzard file acquisition and
its existing persistent generated-image cache.

The sidebar bounds each request to512observations and32sequential artwork tiles.
One failed observation/tile preserves other readable results. Revision, selection
and cancellation guards prevent retired work from changing the visible map.
Resolver/artwork diagnostics and phase limitations stay visible with partial data.

Focused checks: `:concepts-locations:check :gui:check` and the display-dependent
`:gui:locationMapUiTest :gui:providerBrowserUiTest`. The authored image fixture
covers a cropped edge tile, isolated resolution failure, delayed stale response,
resize, starter queries and borrowed ownership; it contains no game assets.
