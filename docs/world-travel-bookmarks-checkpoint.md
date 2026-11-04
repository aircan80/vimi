# World travel and camera bookmarks checkpoint

Issue #181. Scope: searchable saved camera bookmarks, typed native-coordinate
travel and safe jumps in the World explorer. Entrances, zone centers and floors
are never inferred from bounds.

## Behavior

- **Bookmark this view** (explorer footer) saves the camera position, yaw and
  pitch. It also records the map ID, the map directory and name, the filename tile,
  and the build/locale label the bookmark was saved from. The default name is the
  recorded terrain area chain, or otherwise the map and tile.
- **Bookmarks & travel** (below the tile inventory) lists bookmarks in save order.
  It searches by name, map name or directory, or exact map ID. Go (or double-click)
  and Delete act on the selection.
- **Go to coordinates** accepts `x y` or `x y z` native coordinates on the selected
  map. Anything else is explained and nothing opens.

## Validation before a jump

1. **Map identity:** the current build must have a map with the saved ID. When both
   records have a directory, the directories must match (case-insensitive), so a
   reused ID never sends you to a different map. A missing or changed map is refused.
   If the map is not the selected one, it is selected (instances are included if
   needed), and the jump continues only after its inventory loads.
2. **Recorded tile:** the filename tile, `floor(32 - native / TILE_SIZE)` on each
   axis, must be present in the map's WDT inventory. An unrecorded tile is refused
   without opening the explorer.
3. **Verified position:** after the tile loads, the point must lie in the half-open
   rectangle below the loaded tile's native maxima. This is the same ownership
   rule the camera tile lookup uses. Otherwise the explorer keeps the tile's normal
   terrain entry and reports **outside the verified terrain tile**.
4. **Safe altitude:** when the rendered surface height there is known
   (`TerrainGround`), the camera never lands below eye height above it. Typed
   coordinates without z arrive 30 yards above the surface, or at the tile's normal
   entry altitude if no height is loaded. Persistent GPU streaming may seed the
   camera before heights are available. A saved z is then used unchanged and the
   status says so. Walk mode then follows the surface once it loads.

## Storage

With a library, bookmarks persist in the unified library database
(`objects.sqlite3`) in `world_bookmarks_metadata(version)` and
`world_bookmarks(id, record)`. The checks match the other preferences: unified
library application ID/user version, a complete versioned schema, refusal of
future versions, FULL commits, and at most 256 rows. Each record is a versioned,
percent-encoded line. Unreadable rows are counted and reported but never deleted
or repaired. All SQL runs on one bounded daemon worker, never on FX. Late results
for a replaced store are ignored. Without a library (tests, design preview), the
view keeps session-only bookmarks and says so. The data inventory lists the new
tables. Bookmarks contain no game assets.

## Verification

```sh
./gradlew :concepts-world:check :gui:worldBookmarkStoreTest
# JavaFX display required:
./gradlew :gui:worldTravelUiTest :gui:worldUiTest :gui:worldNavigationUiTest
```

- `WorldBookmarksTest`: tile-index convention and bounds, half-open ownership, map
  identity (missing, changed directory, unrecorded directory), unrecorded tiles and
  inventories, strict coordinate parsing, encoding round trip with reserved and
  non-ASCII characters, malformed or future records, field bounds, and search.
- `WorldBookmarkStoreTest`: session store; persisted order across reopen; removal;
  unreadable rows counted; the 256 limit; refusal of a future schema; and refusal
  of a non-library database, which is left unmodified.
- `WorldTravelUiTest` uses authored ADT18 tiles at absolute positions. It covers
  malformed and unrecorded input (the explorer does not open), and arriving at
  typed coordinates 30 yards above the rendered surface. It then saves a bookmark
  below the surface, jumps to it from another map (the map is reselected and the
  camera lifted to eye height), and checks the changed-directory and missing-map
  refusals and the out-of-tile fallback to the tile entry.

Local runs used a private headless `kwin_wayland --virtual --xwayland` session.
The axis convention matches the documented native-axis streaming lookup. Every
jump is also re-verified against the loaded tile, so a convention mismatch on real
data would be refused, never landed. Real-data acceptance is not yet recorded.
