# World terrain explorer

Open a prepared build and choose **World**. The sidebar starts with continent
records (`Map.InstanceType=0`); include other maps to inspect instances/internal
records too. Search uses recorded map names and IDs. Select a map, inspect its
readable areas, choose a tile in its 64×64 terrain inventory, then Open explorer.
Selecting an area alone does not acquire terrain. **Find terrain** explicitly
checks the map's terrain files for that exact recorded area ID. Missing files may
be downloaded by the existing source service. Checked/total, matches, unavailable
files and Cancel remain visible. Child areas are not implicitly included.

Matching tiles receive blue outlines and appear in a result list while the full
continent remains visible. Choose a match, then **Open explorer**. Its source
content key and chunk evidence are revalidated before the initial camera uses an
actual retained terrain sample plus the normal altitude offset. A tile may contain
multiple areas; a fully holed match has no rendered entry. Changed sources require
finding terrain again. Canceled/failed scans retain explicit partial coverage;
no observed match does not prove that an entire zone has no terrain.

The screen grid uses filename-coordinate Y horizontally and X vertically. Drawing,
highlighting and hit testing share that mapping. WDT tuple/file identities and
3D terrain coordinates are retained. A correction fixes the transposed continent
outline: a read-only Eastern Kingdoms inventory spans42rows by23columns.
An authored asymmetric-grid test verifies orientation and exact clicked fileID. Zone-map artwork and spawn/pickup markers are tracked separately in #116.

Click the viewport to focus it. WASD moves horizontally, Space or E ascends and Q descends,
mouse drag or arrow keys changes view direction, Shift increases movement speed,
and the wheel changes speed. Back to map returns to tile selection. Leaving the
World area cancels background requests and pauses movement/rendering; returning
resumes the explorer. Closing/replacing a build drains its world service before
borrowed tables/files are retired.

## First-stage scope

This is a free-fly **terrain preview with height shading**. It renders source
terrain heights and terrain holes. Buildings, doodads, texture-layer painting,
water, collision and live NPCs are not rendered yet. Global-WMO-only maps have no
terrain tiles and remain visible with that limitation. The default application viewport now uses inline bgfx GPU rendering on a
capable backend, with the bounded CPU renderer as fallback. No JavaFX3D pipeline
is required. **GPU terrain** selects the renderer; **Retry GPU** explicitly retries
a failed scene. Backend/activity feedback stays beside the controls. Unsupported
or failed native rendering retains the current camera/data and uses software.
Actual hardware qualification covers Linux Vulkan; Metal/D3D remain pending.

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

## Verified area discovery

The metadata path shares ADT18 chunk/header/MCVT validation with geometry, checks
all source-derived coordinates, and reads unsigned MCNK areaID at offset52.
It retains source-order chunk identities and chooses the first nonholed cell
center as an entry reference. Raw MCNK origin Z is a height base; the entry Z
includes the recorded MCVT sample. Fully holed chunks remain area evidence but
cannot supply an automatic terrain entry.

Discovery has one active worker and one latest pending request. It reads one
bounded32MiB source at a time, checks at most4096 unique inventory tiles, retains
at most4096 tile matches and32 diagnostic samples, and publishes counts at most
10Hz. It creates no persistent area index. Close drains reads without closing
borrowed file/table handles; stale area/map/workspace results cannot overwrite
newer activity or matching tiles.

Read-only/scratch Forever1.60.1.70755 acceptance resolved Eastern Kingdoms'736
inventory tiles and root ADT777947 at32,32. Its256 chunks recorded seven distinct
area IDs. An exact-area279 lookup matched one chunk, revalidated its source,
and used an entry point equal to an actual native mesh vertex. The original
application library stayed read-only; one missing tile was acquired into a
private scratch store. This is a one-tile source proof, not a completed continent
or zone-coverage scan. No game assets or screenshots enter Git.

## Inline GPU terrain

Terrain and model previews share one application-owned offscreen GPU child.
Height geometry is uploaded once per immutable tile snapshot as bounded48-byte
triangle instances. Camera updates carry free-flight coordinates without geometry
reupload or process restart. Tile-set replacement drains the old child serially,
keeps the previous readable image and shows activity until a current frame is
accepted by JavaFX. Hidden/iconified/inactive views retire their native owner.
An empty loaded neighborhood retires stale GPU output and renders software sky.

The GPU uses native coordinates, existing flat height/slope colors, original
triangle-depth fog, holes and near/far behavior. Qualified reversed D32F depth
preserves the tested close-surface ordering; homogeneous-depth backends fall back
to CPU. GPU camera controls may update faster while nearby-file checks stay near
10Hz. Requested frames preserve aspect ratio within800×500. Readback, parent copy
and final FX upload remain costs; this is not zero-copy or a universal speedup.

Independent acceptance includes27 feasibility and30 production Vulkan frames,
maximum nine-tile upload, source index37119, close/far overlap, opaque sky, typed
child IPC, camera/resize persistence and actual inline FX lifecycle/fallback.
All24 packaged shader blobs are hash-verified across four offline profiles.
See [terrain review](gpu-terrain-review.md) for exact scope and platform limits.

```sh
./gradlew :rendering-bgfx:nativeGpuTerrainProductionAcceptance
# Linux Vulkan and a working JavaFX display required:
./gradlew :gui:worldGpuUiProof
```

![Authored inline GPU terrain](images/world-inline-gpu-authored.png)

Streaming follows filename X along native world X and filename Y along native
world Y. Camera lookup uses the loaded origin tile’s native maximum on each
matching axis. This differs from the continent grid’s screen layout (filename Y
horizontally, X vertically). Keeping these mappings separate prevents walking
toward a tile from loading its transposed neighbor and evicting visible terrain.

Maps with no recorded terrain show an explanation directly over the tile panel.
A recorded global-world-model map explains the current model-preview limitation;
unavailable inventory is reported separately. These states disable actions that
require terrain tiles. This screenshot uses authored map records only.

![Authored empty-map explanation](images/world-empty-inventory-authored.png)

The navigation regression uses an asymmetric origin and physically positioned
fixture tiles. Thirteen moves cover both directions on both native axes, returns
and all four diagonals, asserting both actual requested tiles and the retained
3×3 neighborhood. Actual viewport key events and deterministic timer steps verify
Space/E ascent, no doubled Space+E speed, Q descent and key release. Independent
negative controls fail for the previous swapped lookup and previous E-only input.
Run it with `./gradlew :gui:worldNavigationUiTest` on a JavaFX-capable display.

## Explorer minimap

The top-right minimap stays north-up while recorded artwork scrolls beneath the
player arrow. Its regional coverage uses at most nine recorded MAID minimap IDs;
there are no guessed paths or invented markers. A local activity indicator covers
artwork loading, and missing/protected/unsupported sources remain visibly partial
or unavailable. The arrow tracks facing even when artwork is missing.

File acquisition and image decoding run off the JavaFX thread. Bounded detached
pixels are uploaded on FX; hidden views, changed sources and closed builds retire
requests and ignore late results. A resize cannot revive retired source artwork.
The dedicated authored proof is `./gradlew :gui:worldMinimapUiTest` on a desktop
display. These quadrant colors are authored orientation fixtures, not game art.

![Authored north-up minimap](images/world-minimap-authored.png)

The service/component and final lifecycle hook received independent review.
Integrated World navigation/inventory/minimap and comparison FX checks pass,
along with the affected source/service/GUI checks. Actual game artwork and private
source proofs stay outside Git.

A read-only Forever70755 proof decoded recorded minimap IDs204477,204478,204517,
204476 and204437 for the five neighboring source tiles used in the native-axis
proof. All produced detached256×256 pixels with identical cold/warm output.
The first artwork was visually inspected locally. These sources have single
larger mip levels: the minimap consumer explicitly downsamples a declared mip
within512 pixels, while the generic decoder never invents or resizes mip levels.
Larger-only unsupported sources retain the visible fallback. Actual game pixels
remain private under `/tmp/vimi-live-mount79-proof`; none enter this repository.
