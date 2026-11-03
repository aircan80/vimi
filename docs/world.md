# World terrain explorer

Open a prepared build and choose **World**. The sidebar starts with continent
records (`Map.InstanceType=0`); include other maps to inspect instances/internal
records too. Search uses recorded map names and IDs. Select a map, inspect its
readable areas, choose a tile in its 64×64 terrain inventory, then Open explorer.
The zone list is reference information; selecting an area changes its description
and does not filter or navigate the full-map terrain inventory. The highlighted
tile is a starting location, not a claim about a selected zone's center.

The screen grid uses filename-coordinate Y horizontally and X vertically. Drawing,
highlighting and hit testing share that mapping. WDT tuple/file identities and
3D terrain coordinates are retained. A correction fixes the transposed continent
outline: a read-only Eastern Kingdoms inventory spans42rows by23columns.
An authored asymmetric-grid test verifies orientation and exact clicked fileID. Zone-map artwork and spawn/pickup markers are tracked separately in #116.

Click the viewport to focus it. WASD moves horizontally, Q/E changes altitude,
mouse drag or arrow keys changes view direction, Shift increases movement speed,
and the wheel changes speed. Back to map returns to tile selection. Leaving the
World area cancels background requests and pauses movement/rendering; returning
resumes the explorer. Closing/replacing a build drains its world service before
borrowed tables/files are retired.

## First-stage scope

This is a free-fly **terrain preview with height shading**. It renders source
terrain heights and terrain holes. Buildings, doodads, texture-layer painting,
water, collision and live NPCs are not rendered yet. Global-WMO-only maps have no
terrain tiles and remain visible with that limitation. This renderer is a bounded
CPU fallback that works on Linux ARM machines whose JavaFX pipeline lacks 3D
support. The rendering component is separate from the source component so a GPU
backend can be introduced independently.

Readable PARTIAL Map/AreaTable rows remain usable. Unavailable tables, protected
terrain files and failed tiles report independent outcomes. Existing CDN/file
services authenticate, own, retain and account for original content; World does
not create another cache directory or contact a third-party service. A service
instance retains at most 12 parsed tiles; the viewport retains a 3×3 neighborhood.
Returning to retained files reparses a small terrain payload, avoiding a second
persistent representation of this geometry in the initial stage.

## Data and format boundaries

`:concepts-world` exposes BuildWorld over borrowed BuildTables/BuildFiles. It
reads verified Map/AreaTable fields, then WdtFileDataID or a recorded map directory.
Map D43AFAC3 has a narrowly reviewed WdtFileDataID field-name admission: signed32,
FileData.ID foreign key, no raw physical interpretation. Other unverified fields
remain unavailable. Definitions do not grant blanket interpretation to raw fields.

`:formats-world` admits bounded WDT18 MAIN/MAID inventories and split-root ADT18
MCNK/MCVT heights. MAID supplies actual root ADT IDs; its mapTexture overview ID is
retained separately from terrain texture ADT data. Native world coordinates,
145-sample chunk grids, triangle fans and low/high-resolution hole masks follow
reviewed source layouts. Input is limited to 32 MiB and 256 terrain chunks.
Legacy monolithic ADTs with padding outside declared normal chunks are not an
admitted profile yet. The map inventory can remain useful when a terrain profile
is unsupported.

`:rendering-world` owns perspective camera transforms, near-plane clipping,
depth testing, height/slope shading, fog, cancellation and a per-frame raster
budget. Frames are bounded to 1200×800; the GUI requests at most 800×500. Camera
motion and neighborhood acquisition do not run on the JavaFX thread. Queued
results carry selection/frame generations and cannot overwrite a newer explorer.

Primary format references:
[WDT reader](https://github.com/Kruithne/wow.export/blob/main/src/js/3D/loaders/WDTLoader.js),
[ADT reader](https://github.com/Kruithne/wow.export/blob/main/src/js/3D/loaders/ADTLoader.js),
[terrain coordinate export](https://github.com/Kruithne/wow.export/blob/main/src/js/3D/exporters/ADTExporter.js).
No source/game assets were copied into the repository.

## Verification

```sh
./gradlew :formats-world:check :concepts-world:check :rendering-world:check :gui:check
# Requires a working JavaFX display:
./gradlew :gui:worldUiTest
```

The authored UI fixture exercises selection, stale requests across map and
explorer sessions, background cancellation, rendering and borrowed lifetimes.
It can export an authored screenshot with
`-PworldScreenshot=/absolute/path/world-authored.png`.

A read-only Forever 1.60.1.70707 source proof resolved Map 0 → WDT FileDataID 775971,
736 terrain tiles, and tile32,32 → root ADT777947. The authenticated actual ADT
contained 256 chunks, 37,120 vertices and 65,488 triangles. A single 800×500 frame
rendered in approximately 24–28 ms on the development machine; this is a sample,
not a multi-tile frame-rate guarantee. Actual game payloads/proof images remain
outside the repository. The image below contains authored geometry only.

![Authored World explorer](images/world-authored.png)
