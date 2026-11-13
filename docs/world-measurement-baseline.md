# World measurement baseline (Phase 0, #353)

What the World explorer actually spends its time on, measured on the real pipeline with the
local build. These numbers set the Phase 2 priorities. They come from one machine and are
single observations, not guarantees.

**Measured 2026-11-06 on main at `0bbe365c` (#352) plus this instrumentation.** That is before
#397, #416 and #418. Rerun on the same scenarios to compare later changes.

## How to reproduce

Everything is opt-in. The instrumentation is a process-wide table,
`formats-world` `WorldStageTimings`, that does nothing until `-Dvimi.world.stageTimings=true`.
While off, each call site costs one volatile read. BLTE inflate has its own totals in `files`
`FileDecodeTimings`, behind the same property. When the property is set, the GPU helper
inherits it and writes its own table (`WorldStageTimingsDump`) to
`-Dvimi.world.stageTimings.file`.

### What the probe touches

**It only runs on a measurement copy of the library.** It refuses to start without
`-PdataDirectory=<copy>`. It also refuses a directory that:
- has no `MEASUREMENT_COPY` file at its root. A real library never has one, so a real library
  at a custom location is refused too;
- is, or resolves to, the application's own data directory, whether through a junction, a
  different case or a short name;
- has a `library` folder that is a link, junction or reparse point, or the same folder as the
  application's own `library`;
- has no `library` folder at all.

To make the copy, close the application and copy its whole data directory (it holds `library`,
`derived`, `questie` and so on) to a scratch folder. Do not link it. Then mark it. On Windows the
data directory is `%LOCALAPPDATA%\vimi`:

```bat
robocopy "%LOCALAPPDATA%\vimi" <scratch>\vimi-copy /E
type nul > <scratch>\vimi-copy\MEASUREMENT_COPY
```

The copy must contain the build already prepared to Tables-ready.

**It never prepares anything.** It opens the library with
`BuildApplication.openWithoutBackgroundWork`, which is the application's open with two
differences:
- persisted downloads are not resumed;
- no startup or daily provider check (Questie, listfile, keys, EG link, Polestar) is scheduled.

It then needs a Tables-ready preparation receipt for the build, which supplies the region, CDN
configuration and locale. It refuses a build without one. It starts no preparation job. It opens
one local-only work, with no network sources, and uses only:
- the build metadata;
- the resolution indexes and file handle, without the file catalog pass;
- the build's cached key graphs;
- a World over its own workspace tables, with no known-table pass or warmup.

That is why it starts within seconds (#422).

**Every write it can make, all inside the copy or the probe's build folder:**
- **Opening the library:** the store's writer lock, the library's idempotent migration check, and
  provider owner retention in the library catalog. These are the same as an application start.
- **Creating the work:** the work registers its expected build owner (`ownership.expect`).
- **Opening the build:** if the build's resolution projection is missing for the receipt's region,
  opening it may store that projection.
- **Reading tables:** a workspace table with no cached generated form is generated and stored on
  first read.
- **Streaming:** the helper's private packet files go under `gui/build/world-measurement/tmp`,
  which is cleared at task start.
- **Reports:** `report.txt`, `gc.log` and `helper-timings.txt` go under
  `gui/build/world-measurement`.

`worldMeasurementLibraryTest` checks this over an authored offline library. It refuses an
unprepared build, and the open adds no stored object and changes no payload file.

**Bounds.** The deadline is at most 400 s. It also bounds the library open: an open that overruns
is canceled, and the probe waits up to 60 s for it to close what it opened. After the deadline
the run closes everything normally, each step with its own timeout:
- the controller, 45 s;
- the helper, 20 s;
- the library, 60 s.

Deadline plus every close timeout, 525 s, stays inside the task's 10-minute timeout.

The main thread holds the watchdog off at all times except while it measures. The watchdog acts
only 150 s after the deadline, so it can end a stuck measurement but never an open or a close.
Even then it exits normally with `System.exit`; nothing is halted.

**Exit codes:** 0 complete, 2 failed (including a refusal), 3 deadline or incomplete.

### Commands

Take the shared real-data lock. Remove it only if your own `mkdir` succeeded:

```sh
mkdir ../.realdata-lock && {    # shared real-data lock, next to the worktrees
  trap 'rmdir ../.realdata-lock' EXIT
  ./gradlew :gui:worldMeasurementProbe --max-workers=2 "-PsqliteLibrary=<sqlite3.dll>" \
      -PdataDirectory=<copy> -Pprobe.scenarios=elwynn:0:49:31,stormwind:0:48:30   # about 4 min
  ./gradlew :gui:worldMeasurementProbe --max-workers=2 "-PsqliteLibrary=<sqlite3.dll>" \
      -PdataDirectory=<copy> -Pprobe.scenarios=elwynn:0:49:31 -Pvimi.world.viewDistance=far  # 5x5
  ./gradlew :gui:worldMeasurementProbe --max-workers=2 "-PsqliteLibrary=<sqlite3.dll>" \
      -PdataDirectory=<copy> -Pprobe.mode=prep                                     # about 3 min
  ./gradlew :gui:worldMeasurementProbe --max-workers=2 "-PsqliteLibrary=<sqlite3.dll>" \
      -PdataDirectory=<copy> -Pprobe.mode=refusals -Pprobe.maps=0,1
}
```

The report goes to stdout and to
`gui/build/world-measurement/report.txt`. The task also writes
`gui/build/world-measurement/gc.log` (`-Xlog:gc*`) and the helper's table.

In stream mode, creatures are drawn as the World view draws them (`-Pvimi.world.creatures=false`
leaves them out), and
`-Pvimi.world.creatureAnimation=false` keeps them in their baked pose (#384). The fill line
then also prints the creature status. `-Pprobe.camera=x,y,z,yaw,pitch` (native) replaces the
tile-centre seed. `-Pprobe.stillSeconds=N` holds the camera still for N seconds after the
fill and reports what the child redrew on its own (the creatures' idle).

**Creatures mode** (#553, `-Pprobe.mode=creatures`) counts creature and humanoid NPC outcomes
for a `probe.size` square (default 3) around each scenario centre, over `probe.passes` passes
(default 2). Each tile goes through the production bridge, chooser, display previews and NPC
appearances, with no helper, GPU or JavaFX. It differs from the other modes in two ways:
- the work uses the copy's already-loaded community TACT keys (no refresh, no network), which
  the Creature table's encrypted sections need;
- it may add derived entries (decoded images, model previews, race appearances) to the copy's
  generated store, as the application does, so a second run starts warm. Nothing is staged,
  published or downloaded.

With `-Pprobe.npcItems=true` it lists each built humanoid NPC once (its extended display ID, slot
items by InventoryType, item records not drawn and its tabard, cape and torso geosets), and
`-Pprobe.npcImages=<extended display IDs>` writes front, back and rear CPU renders of those NPCs
(`npc-<ID>-front.png`, `npc-<ID>-back.png`, `npc-<ID>-rear.png`) to the probe output for close-up
review. The renders show game data: keep them local.

It also reports, per distinct creature display it previewed (#377, #455), the Stand pose outcome
(Stand pose, or rest pose with the reason), the geoset case (the model's
`CreatureGeosetDataID` 0 or above 0, with or without the display's own geoset rows) with the
production geoset rule's outcome, and for displays whose model records no geoset data, how many
of the model's geoset groups have an x00 section, an x01 section, both or neither.

**NPC slots mode** (`-Pprobe.mode=npcSlots`) counts `NPCModelItemSlotDisplayInfo` rows by item
slot over the whole build, independent of spawns, then builds the production appearance of NPCs
wearing a tabard (slot 9) or a cape (slot 10): every `-Pprobe.stride`-th (default 1), up to
`-Pprobe.limit` (default 60) of each. It tallies them by outcome, by the tabard or cape geoset shown
and by the stated reason when the item is left out. `-Pprobe.npcImages` renders listed NPCs as in
creatures mode. Like creatures mode, it uses the copy's loaded community keys and may add derived
entries to the copy's generated store.

**Displays mode** (#377, #455, `-Pprobe.mode=displays`) is the same display census over the whole
build, independent of spawns and of the Creature table:
- from the tables, every creature display (no extended display info) by geoset case;
- then the production preview and Stand sequence of one creature display per creature model
  (its lowest display ID), in a fixed pseudo-random model order, until `-Pprobe.limit` models
  or the deadline, with the reasons models have no static preview (for example, model files the
  copy does not store).

Like creatures mode, it uses the copy's loaded community keys and may add derived entries to the
copy's generated store.

**Prep mode** (#422) times the real source path one tile at a time, for a `probe.size` square
(default 3) around each scenario centre. Per tile it runs:
- `WorldTerrainStreaming.prepareAndWrite`, with a no-op output (`prep.terrain`);
- the production `WorldStreamSourceBridge.prepareExterior` (`prep.objects`).

It runs `probe.passes` passes (default 2): the first in a fresh process, the second a repeat. It
skips the helper, the GPU, FX and the packet files: in stream mode the terrain packet write is
about 25 ms per tile. Use `-Pprobe.objects=false` for terrain alone. It reports the same series
as stream mode, so before and after runs compare directly.

Both modes draw WMO interior groups as the World view does (#383); `-Pprobe.interiors=false`
measures exteriors only, for a before and after on the same build. In prep mode,
`-Pprobe.root=<WMO root FileDataID>` lists that root's drawn LOD0 groups and whether it carries a
portal set on each tile. `objects.groups.interiorReadPerTile` is the interior pass's share of
`objects.groups.readPerTile`.

On the first pass prep mode also prints one `PROBE tile` line per tile: the vertices and indices
of its building models, those of every pool, its building placements, drawn, capped and
drawn-by-group roots (very large placements, see `ExteriorObjectLimits.LARGE_VERTICES`), drawn
groups and groups another tile draws, and with `probe.root` whether that root is drawn.

**Stream mode** builds the production `WorldStreamSourceController` exactly as `WorldView` does:
- the real `WorldStreamGpuOwner` and helper, on the default backend (D3D11 here);
- the real exterior preparer;
- `WorldStreamImagePresentation` on the JavaFX thread, pulsed by an `AnimationTimer`.

After the fill, a `PROBE sky:` line reports the recorded sky at the camera at 00:00 and 12:00
through the build's sky index: its state, coverage, whether it has fog, light and water, and the
index's source issues, or why the light tables were refused.

The camera follows a fixed script:
1. Stationary at the seed until every tile and its late objects are done (budget 150 s).
2. Rotating in place at 1 rad/s for 12 s.
3. Flying one tile along native -x in 10 s.
4. Settling (budget 60 s).

The vimi tile coordinate is (x, y) = (filename second, filename first). Goldshire is tile
`49,31`, and Stormwind City is `48,30`.

**Water mode** (#171, `-Pprobe.mode=water`) prepares every `probe.stride`-th recorded tile (default
every 4th) of each map in `probe.maps` through the production terrain and water preparation, with
no helper, GPU or JavaFX. It counts the water facts: tile states and reasons, declared, admitted
and unresolved MH2O layers, cells, and issue kinds.

**Census mode** (`-Pprobe.mode=census`) prepares every `probe.stride`-th recorded tile
(default every tile) of each map in `probe.maps`. It prepares the terrain, then the objects,
unless `-Pprobe.objects=false`. It counts outcomes by what refused and at which site. For the
first `probe.limit` refused tiles (default 25) it prints each of the tile's files with its read
state and length. That separates a file the local library copy lacks (`NEEDS_NETWORK`, fetched
by the app when it is online) from a real refusal. On build 70862 both continents' terrain
prepared on every tile (Kalimdor 988, Eastern Kingdoms 736, under a minute each). Objects
refusals came only from obj0 and obj1 files the offline copy lacked.

**Refusals mode** calls the real `BuildWorld.terrainTextures` over every recorded tile of each
map, in batches of 9. It counts:
- tile states;
- textured chunks;
- `WorldTerrainTextureSource.Issue` reasons;
- chunk refusals, as `TerrainAtlasComposer.Reason` / `WorldTextureFormats.ChunkIssue`.

### Run environment

| | |
| --- | --- |
| Build | wowf 1.60.1.70790 |
| JVM | 25.0.5, default G1, 16 GiB max heap |
| Machine | 24 hardware threads, Windows 11 |
| Helper backend | D3D11 (default) |
| Objects | STANDARD object textures, creatures off (the app defaults) |
| Neighbourhood | 3x3 |

## Headline numbers

### 1. Fill time (stationary camera, 3x3)

| | Elwynn (Goldshire) | Stormwind |
| --- | ---: | ---: |
| First accepted frame | 2.7 s | 2.2 s |
| All 9 terrain tiles resident | 12.1 s | 9.3 s |
| All 9 tiles' objects attached | 83 s | 76 s |

### 2. Terrain tile: per-stage time (parent and helper, 9 tiles)

| Stage (series) | Elwynn p50 | Stormwind p50 | Notes |
| --- | ---: | ---: | --- |
| `parent.tile.produceToStaged` | 1,179 ms | 881 ms | Reserve, then source, file, helper decode and upload, then STAGED. Strictly one at a time. |
| `terrain.tile.total` (source side) | 742 ms | 522 ms | |
| `terrain.tile.material` | 520 ms | 376 ms | Texture source: reads, diffuse decode, compose |
| `terrain.material.read` (root, tex0) | ~24-30 ms each | | Three per tile |
| `terrain.material.diffuseReadDecode` (4x4 mip) | ~13 ms each | | ~9 per tile |
| `terrain.material.atlasCompose` | ~22 ms | | |
| `terrain.tile.geometry` | 42 ms | 28 ms | |
| `source.read.TERRAIN_BASIC` (root ADT) | 33 ms | 26 ms | |
| `source.read.LIQUID_INSPECTION` (same root ADT, again) | 24 ms | 18 ms | Same bytes as above: the double read is confirmed |
| `terrain.tile.write` | 25 ms | 23 ms | |
| └ `tileFile.write.encodeDigestWrite` | ~8-9 ms | | SHA-256 is interleaved with the write |
| └ `tileFile.write.geometryFingerprint` | ~5 ms | | |
| └ `tileFile.write.fsync` | ~1.3 ms | | |
| └ `tileFile.write.rename` | ~0.7 ms | | |
| `child.tileFile.read` (read and verify SHA) | 21 ms | 21 ms | |
| `child.tile.pack` | 3.4 ms | 2.7 ms | |
| `child.tile.uploadWall` (begin to STAGED) | 33 ms | 31 ms | Steps p50 0.07 ms |
| `parent.commit.roundTrip` | 3.9 ms | 3.5 ms | |
| `parent.commit.toAcceptedFrame` | 37 ms | 31 ms | Receipt to the first accepted frame drawn from that receipt |

About 400 ms of `produceToStaged` (Elwynn p50 1,179 ms) is not attributed to any timed stage:
- source total 742 ms;
- child read, pack and upload about 57 ms.

The rest is the producer and transport hand-offs between them: the reservation and credit, the
offer, and decode scheduling on the single decoder. None of those are timed separately here.

BLTE zlib inflate for the whole Elwynn fill took 0.45 s for 109 MiB out (3,803 chunks).

**Reading:** of each terrain tile's ~1.2 s, about 0.5 s is texture-source work, mostly per-file
read latency. Each file read costs 20-35 ms regardless of size. A further 0.2 s goes on the other
timed source stages, and about 0.4 s is unattributed hand-off time (above). All of it is serial. File write, fsync,
SHA and the IPC round trips together cost under 100 ms per tile. Two conclusions follow:
- Dropping fsync or swapping SHA-256 for CRC32C is worth a few percent at most.
- Parallel source preparation (N tiles in flight), together with cheaper reads, is the lever.

### 3. Exterior objects: per-tile prep (9 tiles each)

| Series | Elwynn | Stormwind |
| --- | ---: | ---: |
| `objects.tile.total` mean / p50 / max | 7.7 / 6.6 / 14.9 s | 7.2 / 4.3 / 17.2 s |
| `objects.phase.buildings` (WMO roots, groups, images) p50 | 1.9 s | 1.0 s |
| `objects.phase.doodads` (MDDF M2) p50 | 3.2 s | 2.4 s |
| `objects.phase.setDoodads` max | 6.5 s | 7.0 s |
| `objects.phase.interiors` (interior groups, then the set doodads of interior-only roots, which the `setDoodads` figure above still includes) | not in this baseline | not in this baseline |
| `objects.phase.mipChains` p50 | 8 ms | 7 ms |
| `objects.phase.imageBudget` p50 | 4 ms | 3 ms |
| `source.read.BASIC_EXTERIOR` count | 1,702 | 1,817 |
| `source.read.BASIC_EXTERIOR` p50 / mean / max | 27 / 34 / 2,093 ms | 20 / 31 / 2,555 ms |
| `source.prefetchWait.BASIC_EXTERIOR` count / p50 (WMO group read-ahead hits; waits only, not in `source.read.*` or `source.readBytes.*`) | not in this baseline | not in this baseline |
| **Sum of exterior reads** | **58.0 s of 69.0 s** | **56.0 s of 64.9 s** |
| All BLP decodes (`objects.blp.*`), total | 0.17 s | |
| M2 model and skin parse, total | 0.12 s | |
| `objects.bridge.copyAndCreatures` p50 | 6 ms | |
| `child.objectsFile.read` p50 | 12 ms | 12 ms |
| `child.objects.uploadWall` p50 | 201 ms | 200 ms |
| `parent.objects.attachRoundTrip` p50 | 239 ms | 235 ms |

With read-ahead on, `source.read.*` no longer covers all exterior source time: a read-ahead
hit is recorded under `source.prefetchWait.*` instead. To compare with the sum above, add
`source.prefetchWait.BASIC_EXTERIOR` to `source.read.BASIC_EXTERIOR`. Even then a hit counts
only the wait, not the overlapped read.

Derived reads (building groups, doodad models and skins, their images) are served from the
session's source cache (`WorldSourceCache`) when it holds the file: such a read takes well under
a millisecond and still counts in `source.read.*` or `source.prefetchWait.*`. The counters
`source.cache.fileHit` and `source.cache.fileMiss` give the split, and `source.cache.heldMiB` the
bytes held.

**Reading:** 84-86% of objects prep is waiting on about 190 source reads per tile, one at a time.
Decode, parse, mips and assembly add up to under 0.5 s for nine tiles. Two consequences:
- Decode-once BLP changes will barely move prep time.
- Parallel reads, a cheaper read path, and a cross-tile (session) asset library are the levers.

### 4. Exterior object counts (Count buckets summed over the 3x3)

| Bucket | Elwynn | Stormwind |
| --- | ---: | ---: |
| PLACEMENT_DRAWN (buildings) | 46 | 47 |
| PLACEMENT_FAILED_ROOT | 6 | 9 |
| PLACEMENT_UNSUPPORTED | 1 | 0 |
| ROOT_DRAWN / ROOT_FAILED | 40 / 6 | 32 / 9 |
| GROUP_DRAWN / GROUP_INTERIOR (skipped) | 43 / 169 | 33 / 52 |
| BATCH_DRAWN / CUTOUT / MISSING_IMAGE | 345 / 22 / 2 | 273 / 16 / 2 |
| **DOODAD_DRAWN / DOODAD_CAPPED** | **3,585 / 4,001** | **2,254 / 780** |
| DOODAD_FAILED_MODEL | 14 | 14 |
| **SET_DOODAD_DRAWN / SET_DOODAD_CAPPED** | **318 / 4,248** | **609 / 1,068** |
| SET_DOODAD_FAILED_MODEL / UNSUPPORTED | 40 / 30 | 81 / 15 |
| SET_UNSUPPORTED | 14 | 25 |
| MODEL_DRAWN | 332 | 436 |
| MODEL_UNSUPPORTED, of which billboard bones | 9, 9 | 19, 18 |
| MODEL_FAILED / MODEL_EMPTY | 0 / 1 | 1 / 9 |
| IMAGE_DECODED / MODEL_IMAGE_DECODED | 257 / 499 | 206 / 559 |
| MODEL_IMAGE_CAPPED | 1 | 14 |

`MODEL_UNSUPPORTED_BILLBOARD` is a measurement-only sub-count of MODEL_UNSUPPORTED: models
whose first SKIN was present but which carry a billboard bone. It counts distinct models per
tile, not placements.

**Reading:** the per-tile doodad caps are the biggest visible loss:
- Elwynn draws 3,903 of 12,152 qualified doodads (32%).
- Stormwind draws 2,863 of 4,711 (61%).
- In Elwynn, 93% of the doodads in WMO doodad sets are capped.

Some building and model counts are small by comparison:
- Failed WMO roots: 6 and 9.
- Billboard models: 9 and 18.
- Interior groups skipped on exterior maps: 169 and 52. These matter for walking into towns, not
  for the outdoor view.

### 5. Frame cost and delivery (rotating in place, all objects resident)

| | Elwynn | Stormwind |
| --- | ---: | ---: |
| `child.frame.draws` per drawn frame | 6,508 | 4,872 |
| `child.frame.triangles` per drawn frame | 0.94 M | 1.01 M |
| `child.frame.bgfxCpuRender` p50 / p95 | 3.7 / 4.2 ms | 2.9 / 3.3 ms |
| `child.frame.submitCpu` (draw recording, blit, readback request) p50 | 1.5 ms | 1.1 ms |
| `child.frame.cropAndPublish` | ~0.5 ms | |
| `child.gpu.textureMemoryMiB` | 82 | 70 |
| GPU time (`bgfx` timer queries) | not reported on this backend | |
| `child.frame.publishInterval` p50 / p95 | 34 / 40 ms | 34 / 38 ms |
| `fx.frame.acceptInterval` p50 / p95 / max | 32 / 49 ms / 3.2 s | 32 / 48 ms / 3.2 s |
| `fx.frame.cameraToAccepted` p50 / p95 / max | 49 / 66 / 83 ms | 49 / 65 / 66 ms |
| `fx.frame.revisionLag` (camera revisions) | ~3 | ~3 |
| `fx.frame.present` (FX setPixels and swap) p50 | 1.0 ms | 0.9 ms |
| `parent.frame.takeLatest` (mmap read, copy, CRC) p50 | 0.01 ms | 0.003 ms |

`child.frame.cropAndPublish` now starts before the staging crop, so on the staging path it
includes the crop. Staging-path numbers measured after that change are not comparable with the
~0.5 ms above.

**These draw counts predate CPU culling.** This baseline drew every resident object placement and
every terrain chunk each frame. Since then, #369 culls object placements against the view frustum
(plus a sub-pixel size cull), and #430 culls terrain per chunk run. Later runs report far fewer
`child.frame.draws` for the same scene. That is the cull working, not objects going missing. To
tell the two apart, the helper records per submitted frame `child.frame.placements`,
`placementsCulled`, `objectSubmits`, `terrainChunks`, `terrainChunksCulled` and `terrainSubmits`.
On 1.60.1.70862 (Linux, same scenarios, rotating in place; single runs), p50:

| | Elwynn, cull on | Elwynn, cull off | Stormwind, cull on | Stormwind, cull off |
| --- | ---: | ---: | ---: | ---: |
| `child.frame.draws` | 911 | 6,552 | 277 | 4,877 |
| `child.frame.triangles` | 0.18 M | 0.97 M | 0.09 M | 0.96 M |
| `child.frame.placements` / `placementsCulled` | 3,949 / 3,324 | 3,949 / 0 | 2,913 / 2,791 | 2,913 / 0 |
| `child.frame.terrainChunks` / `terrainChunksCulled` | 2,299 / 1,951 | 2,299 / 0 | 2,299 / 2,039 | 2,299 / 0 |
| `child.frame.bgfxCpuRender` p50 | 0.67 ms | 4.34 ms | 0.28 ms | 2.80 ms |

With the cull off, the draw count matches this baseline's 6,508 and 4,872 to within about 1%, and
the placement counts match the object counts in section 4. The resident objects are unchanged.

These 1.60.1.70862 numbers come from a different build, machine and backend (Linux, Vulkan) than
this baseline (1.60.1.70790, Windows, D3D11). Compare the draw and placement counts, not the times:
for example, the cull-off `bgfxCpuRender` of 4.34 ms against the baseline's 3.7 ms is not a
regression.

**Reading:**
- The helper's own per-frame CPU work is about 5 ms, at 800x500, for ~5-6.5k draws and ~1 M
  triangles. Delivery is paced at ~30 fps by the 33 ms draw interval and the 33 ms parent poll,
  not by rendering. Camera-to-pixel latency is ~49 ms p50.
- Lifting DRAW_INTERVAL and the poll (Phase 1 frame path) should reach 60 fps before draw count
  matters. At a larger viewport or a longer view distance, the 5-6.5k draws (one submit per
  placement and range, no culling) will become the next cost.
- The 3.2 s maxima are single gaps right after the stationary phase. They coincide with late
  object attaches.

### 6. GUI JVM garbage collection (whole run, `-Xlog:gc`)

Allocation rate:
- ~46 MiB/s while only rotating.
- ~55 MiB/s averaged over a scenario: 5.9 GiB in 109 s for Elwynn, including object prep.

| Pause kind | n | p50 | p95 | max |
| --- | ---: | ---: | ---: | ---: |
| Young (Concurrent Start) (G1 Humongous Allocation) | 13 | 3.6 ms | 24.6 ms | 24.6 ms |
| Young (Normal) | 9 | 3.9 ms | 4.9 ms | 4.9 ms |
| Young (Prepare Mixed) | 1 | | | 39.3 ms |
| Young (Concurrent Start) (Metadata GC Threshold) | 1 | | | 173.3 ms |
| Remark / Cleanup | 1 / 1 | | | 3.1 / 0.1 ms |

An earlier, shorter run on the same machine had one humongous-allocation pause of 171 ms.

**Reading:**
- Humongous allocations trigger concurrent cycles: whole-file byte arrays and multi-MiB tile and
  image buffers. They cause the occasional 25-170 ms pause, which is visible as a dropped frame
  or two.
- The steady young pauses are small.
- Pooling the big buffers, or moving them off-heap, removes most of these. The per-frame
  1.6 MiB `int[]` is a humongous object at the default region size.

### 7. Terrain texture refusals per map

This is the real `terrainTextures` source, the same reader and composer the streaming material
path uses. A tile counts 256 chunks, whether or not it is mostly ocean.

| | Eastern Kingdoms (0) | Kalimdor (1) |
| --- | ---: | ---: |
| Tiles surveyed | 736 of 736 (198 s) | 486 of 988 (stopped at the 240 s budget) |
| READY / PARTIAL / UNAVAILABLE tiles | 184 / 392 / 160 | 124 / 238 / 124 |
| Textured chunks | 116,492 of 188,416 (61.8%) | 71,265 of 124,416 (57.3%) |

Chunks lost, ranked:

| Chunks lost | Eastern Kingdoms | Kalimdor | Rule |
| --- | ---: | ---: | --- |
| TEXTURE_METADATA / LAYER_SHAPE | 65,586 | 48,188 | MCNK tex0 must be exactly MCLY+MCAL (MCSH, MCMT and others refuse the chunk) |
| TEXTURE_METADATA / CHUNK_SHAPE | 0 | 4,192 | |
| TEXTURE_METADATA / LAYER_FLAGS | 194 | 3 | Layer flags other than 0 / 0x100 / 0x300 |
| MASK_SUM, MASK_DATA, COLOR_ALPHA | 0 | 0 | |
| Whole tile TEXTURE_PROFILE (MPHD, unknown chunks, MHID) | 24 tiles | 1 tile | |
| ABSENT | 0 | 2 | |

**Reading:** LAYER_SHAPE accounts for most of the refused chunks:
- **Eastern Kingdoms:** 99.7% of refused chunks, over every tile.
- **Kalimdor:** about 92%, from a partial sample. The survey stopped at the time budget after the
  first 486 of 988 tiles in inventory order, so the sample is not uniform. CHUNK_SHAPE is most of
  the rest.

Accepting the extra MCSH/MCMT sub-chunks raises textured terrain from ~60% to ~96% of chunks.
**That is fixed by #397.** A probe on the #397 branch found only 29 LAYER_SHAPE chunks remaining;
that residual is tracked in #398. The alpha-sum and MCCV-alpha refusals the review suspected do
not occur on these maps.

### 8. View distance: 3x3 against 5x5 (#173)

Measured 2026-11-07 on 1.60.1.70862, Linux, Vulkan, 32 hardware threads; STANDARD object
textures, creatures off; one run per cell. NEAR is the default; FAR is
`-Pvimi.world.viewDistance=far`. GPU memory and render times are p50 over the whole helper
session, rotate and fly figures are from those phases.

| | Elwynn NEAR | Elwynn FAR | Stormwind NEAR | Stormwind FAR |
| --- | ---: | ---: | ---: | ---: |
| Tiles resident at READY | 9/9 | 25/25 | 9/9 | 25/25 |
| First accepted frame | 1.07 s | 1.00 s | 0.94 s | 0.96 s |
| All terrain tiles resident | 3.02 s | 6.52 s | 3.02 s | 6.27 s |
| Objects settled (deliveries) | 14.8 s (18) | 35.4 s (50) | 12.5 s (18) | 27.3 s (45) |
| `child.gpu.memoryUsedMiB` | 182 | 336 | 180 | 303 |
| `child.gpu.textureMemoryMiB` | 53 | 128 | 47 | 102 |
| `child.frame.bgfxCpuRender` p50 / p95 | 0.40 / 0.69 ms | 0.50 / 0.86 ms | 0.23 / 0.51 ms | 0.30 / 0.80 ms |
| `child.frame.draws` p50 | 383 | 456 | 198 | 211 |
| Rotate `fx.frame.acceptInterval` p50 / p95 | 16.8 / 30.4 ms | 17.4 / 30.3 ms | 16.7 / 18.0 ms | 16.7 / 18.0 ms |
| Rotate `fx.frame.cameraToAccepted` p50 / p95 | 31.1 / 47.9 ms | 40.1 / 50.8 ms | 18.0 / 25.4 ms | 19.2 / 26.6 ms |
| Fly `fx.frame.acceptInterval` p50 / p95 / max | 16.5 / 31.2 / 94 ms | 18.3 / 21.3 / 76 ms | 16.8 / 17.9 / 30 ms | 16.8 / 18.4 / 118 ms |
| Fly: one tile, then READY again | 0.25 s | 0.25 s | 0.25 s | 0.25 s |

Every run also has one ~3 s accept gap right after the stationary phase, as in section 5.

**Reading:**
- 5x5 fills: all 25 tiles are resident in about 6.5 s, about 0.22 s per extra tile after the
  3x3's 3.0 s. Objects take 2.2 to 2.4 times as long, in line with 2.5 to 2.8 times the
  deliveries. No objects set was refused for budget.
- Frame cadence holds: accept intervals are unchanged, the helper's render CPU grows by about
  0.1 ms, and camera-to-pixel latency grows by 1 to 9 ms p50.
- GPU memory grows by 123 to 154 MiB. About 76 MiB is the fixed terrain bound
  (`ViewDistance.terrainGpuBytes`, #606: 153.6 MiB FAR against 77.8 MiB NEAR); the rest is the extra
  object sets, inside the 192 MiB STANDARD objects budget.

## What this means for Phase 2

0. **Terrain texture LAYER_SHAPE** brings textured chunks from ~60% to ~96%. Fixed by #397;
   the residual is tracked in #398.
1. **Source read latency and the serial lane** are the main cost of both terrain (~1 s/tile) and
   objects (~7 s/tile, 85% reads). The candidate fixes:
   - parallel reads or N in-flight tiles;
   - a cheaper stat/open/read path;
   - one root read instead of two;
   - a session asset library keyed by FileDataID, so that overlapping city WMOs and shared doodad
     models are read once.

   Decode-side work (BLP once, CRC32C, no fsync) is worth a few percent.
2. **Doodad caps:** 39-68% of qualified doodads are not drawn. Raising the caps grows prep time
   roughly in proportion to the extra reads. Since this baseline, #416 and #418 cut per-read cost
   about 5x on real data (19.3 to 3.6 ms), so #308 goes ahead before #380. #308 must report prep
   time with the raised caps. #380, the session asset library, is what removes the per-tile
   duplication of shared models and city WMOs.
3. **Frame path**: 30 fps and 49 ms latency come from pacing, not from GPU work, so the Phase 1
   frame quick wins apply as planned. Per-placement culling becomes necessary when caps rise or
   the view distance grows.
4. **GC**: pool or avoid humongous arrays (frame `int[]`, whole-file `byte[]`). Consider ZGC
   after that.

## Not measured, and limits

- **GPU time:** the bgfx timer queries report 0 on the headless D3D11 helper. GPU cost is an
  upper bound from frame pacing only. Use PIX or RenderDoc for the real figure.
- **Presentation and refresh:** the FX side is the production image presentation without a
  visible window, so compositor and display scanout are not included. Live-app fps and latency
  with a window are for the owner to confirm visually.
- **5x5 fill:** measured on 1.60.1.70862 in section 8 (Linux, Vulkan). #276's macOS
  WAITING_CAPACITY hypothesis is not re-checked there: both runs reached READY with every tile.
- **Scope:** one build, one machine, one run per scenario. Creatures are off. HIGH object
  textures are not measured.

## Splat terrain memory (#491)

Splat terrain blending (on by default; `-Dvimi.world.splat=false` turns it off) adds splat masks to every terrain
atlas the parent retains (`TerrainAtlasPool`) and to every terrain slot on the
GPU. The pool is bounded by bytes (`TerrainAtlasPool.MAX_BYTES`, 22 atlases
with 1024² masks, about 110.5 MiB; #583) as well as by count; the GPU side is
counted in each view distance's terrain budget (`ViewDistance.slotGpuBytes`,
`terrainGpuBytes`; #173).

The stream probe records both: the pool's charge (`atlasPool.chargedKiB`,
parent, on each reservation and publication) and the slots' splat masks on
the GPU (`child.gpu.splatMaskKiB`, helper, each frame), next to bgfx's texture
and total GPU memory. The probe sets the mask side for its tier and view
distance as the World view does (1024 only for NEAR with HIGH detail).

Build 70862, Elwynn (49,31), Linux Vulkan, HIGH object textures, a fill then a
30 s flight across one tile (`-Pprobe.mode=stream -Pvimi.world.splat=true
-Pvimi.world.objectTextures=high -Pvimi.world.viewDistance=near|far
-Pprobe.flySeconds=30`), peaks over the run:

| | Splat masks | Pool charge | GPU splat masks | GPU textures (bgfx) | GPU memory used |
|---|---|---|---|---|---|
| NEAR, splat off | none | 12.2 MiB | 0 | 60 MiB | 228 MiB |
| NEAR, splat on (worst case) | 1024² | 60.2 MiB | 53.3 MiB | 124 MiB | 301 MiB |
| FAR, splat on | 512² | 60.5 MiB | 34.7 MiB | 137 MiB | 381 MiB |

The pool stayed at about 55 % of its byte bound in the worst case, with no
`POOL_BUSY` refusal. The GPU splat masks match the budget exactly: ten NEAR
slots of a 1024² mask chain (5.33 MiB each) and twenty-six FAR slots of a 512²
chain (1.33 MiB each).

With splat on by default, the same runs give the frame cost (CPU side; the
driver's GPU timer queries read about 0 here, so no GPU time is given) and the
memory at both view distances, splat off against on:

| | Pool charge | GPU memory used | GPU textures | Frame render p50 / p95 | Render submit p50 / p95 |
|---|---|---|---|---|---|
| NEAR, off | 12.2 MiB | 228 MiB | 60 MiB | 0.472 / 0.665 ms | 0.438 / 0.783 ms |
| NEAR, on | 60.2 MiB | 301 MiB | 124 MiB | 0.494 / 0.690 ms | 0.442 / 0.784 ms |
| FAR, off | 30.5 MiB | 330 MiB | 92 MiB | 0.528 / 0.712 ms | 0.480 / 0.871 ms |
| FAR, on | 60.5 MiB | 381 MiB | 137 MiB | 0.546 / 0.756 ms | 0.480 / 0.922 ms |

Frame render is `child.frame.bgfxFrameRender`, render submit
`child.frame.bgfxCpuRender`.
