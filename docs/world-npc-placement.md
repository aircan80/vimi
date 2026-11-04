# World NPC spawn placement: static markers checkpoint

Issue #175, first slice. The World explorer can show declared NPC and monster spawn
points as static markers over the presented terrain frame. This is placement evidence,
not creature rendering: no models, animation, AI, patrols or interaction are added.

## Which sources qualify

| Source | Spatial shape | Admitted to World |
|---|---|---|
| Polestar `spawn` (`kind=npc`) | client Map ID, world yards `x` north / `y` west, optional `z` | Yes |
| Questie NPC `spawns` | AreaID plus 0–100 percent, optional phase | No: area percentages are not world positions |
| EG Link `npcs` positions | where a player stood when interacting | No: interaction observations, not spawns |
| Creature/NPC definitions | no position | No: a definition alone does not prove a spawn |

Polestar's spawn schema defines the native axes used by terrain (`TerrainCamera`:
+X north, +Y west, +Z up), so admitted points are not transformed. Points without a
recorded `z` keep planar evidence only: they are counted and disclosed, never snapped
to a guessed terrain floor. Polestar records no phase, so phase stays unknown. Coordinates
are rounded source values (0.5 yd planar, 0.1 yd height).

Source selection is the existing NPC/quest/location policy: Polestar enabled, Forever
product, the latest prepared snapshot at or before the opened build within the same
`major.minor.patch`. The source client build stays visible in the status line; this is
provenance, not a claim that the opened build's server places creatures there. Questie and EG
are not consulted, and no other build or product is substituted.

## Components

- `concepts-locations/WorldSpawns`: reviewed Polestar `spawn`/`npc` wire shapes, a bounded
  immutable index per client Map ID (128 yd cells) and `near(map, x, y, radius, limit)`.
  Results are nearest-first by horizontal distance with an NPC/point tie-break, and report
  height-less points and points beyond the limit. Identical duplicate declarations merge;
  conflicting duplicates for one `npc:id:map:n` are omitted with a diagnostic. Malformed rows,
  unknown kinds and points outside the 64-tile grid never hide neighbouring points. Bounds:
  262,144 spawn rows, 65,536 NPC rows, radius ≤ 2,000 yd, at most 512 results per query.
- `DataSourcesService.worldSpawns(build, token)`: local prepared reads only; no download,
  preparation or network call. It returns an explicit unavailable reason when Polestar is
  disabled, the product has no reviewed source, or no compatible snapshot is prepared.
- `gui/WorldSpawnOverlay`: an **NPC spawns** footer toggle (off by default) and a
  mouse-transparent canvas above the frame. One daemon worker builds at most one index, with a
  one-slot queue; a source/workspace revision change cancels and retires the earlier load,
  and closing the World view prevents any later publication. While the toggle is on, a redraw
  runs only when the view, the index or the viewport size changes. At most 256 markers lie
  within 300 yd; labels are limited to the 16 nearest within 120 yd. Marker colour shows the
  published rank (normal, elite, rare elite, boss, rare, unknown).

Markers use the explorer's current camera and the same eye transform and 0.8-width focal
length as the CPU and GPU terrain paths. The frame is fitted to the viewport exactly as
`ImageView` preserve-ratio does. A short two-yard stem rises from the recorded point.
`WorldView` changes are limited to mounting the overlay, supplying the view, adding the
footer row and closing it.

## Limitations

- Markers are drawn **over** the frame and are not depth-tested, so terrain does not hide
  them. Streaming frames can trail the camera by a frame, so markers may move ahead of the
  terrain slightly during fast motion.
- No creature models yet. Model placement needs a CreatureDisplayInfo choice policy, the
  shared-depth draw pass from the World placement proposal, and the models lane's
  animation work. That is the next slice of #175.
- Game objects (`kind=object`) are not shown. Phase, patrol paths, respawn and faction
  visibility are not evaluated.

## Validation

```sh
./gradlew :concepts-locations:check :gui:worldSpawnsTest :gui:dataSourcesTest
# Requires a working JavaFX display:
./gradlew :gui:worldSpawnsUiTest -PworldSpawnsScreenshot=/absolute/path.png
```

Authored tests cover axis and map separation, height provenance, nearest-first bounded
queries across negative cells, duplicates, malformed and out-of-grid rows, cancellation, the
same-snapshot labels, local-only source selection, analytic screen positions (centre, east is
right when facing north, positive yaw turns left), consistency with `TerrainCamera.screenX/Y`,
culling, off-FX indexing, stale revision retirement and close.

A private, offline proof used a scratch copy of the prepared Polestar 2026.11.1 snapshot
(client 1.60.1.70532). The copy was read only, with an offline client. It indexed 52,312 NPC
points (14,481 without height; Map 0: 24,749, Map 1: 24,230) in about 2.8 s with no issues.
A 300 yd query at Valley of Trials took about 4 ms: 96 placed and 31 height-less, nearest
`npc:3143:1:1` Gornek at (−600.5, −4186.5, 41.3). Comparing source heights with the actual ADT
terrain under the same point is **not yet verified**. No game data or private output is
committed. The image below is authored only.

![Authored spawn markers](images/world-spawns-authored.png)
