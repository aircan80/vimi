# World walk and fly movement checkpoint

Issue #179. Scope: ground following, sprint and smoothed camera motion in the
World terrain explorer. Collision, slope limits, swimming and WMO/doodad floor
inference are out of scope and need separate source evidence.

## Behavior

- **Fly** (default) keeps the established controls: WASD, Space/E up, Q/X down,
  arrows/drag to look, Shift ×4, wheel dolly along the full view direction.
- **Walk on terrain** (checkbox or F, one toggle per press) moves along the
  heading at 7 yards/s (Shift ×2). Vertical keys are ignored. The eye stays
  `ExplorerMotion.EYE_HEIGHT` (2 yards) above the rendered surface; this is a
  viewing convenience, not a race-specific client value. The wheel moves along
  the heading only.
- Rising terrain lifts the eye immediately, so it never goes below the surface.
  Descending (including switching from flight) eases with a 0.15 s time constant
  when smoothing is on, and snaps when it is off.
- **Smooth motion** (default on) eases velocity with a 0.12 s response, then
  coasts to rest. Off reproduces the previous exact displacement per frame, which
  the existing navigation oracles pin. The position label shows the effective
  speed, and the walk wheel step is scaled to walking speed.
- Where no qualified surface is loaded (holes, unloaded tiles, other mesh
  layouts), walk holds altitude and the footer says **no rendered terrain here;
  altitude held**. No height is guessed.

## Source of heights

`rendering-world` `TerrainGround` samples the same triangles the viewer draws.
`Tile.of` qualifies the parser's ADT18 source-order layout: 145 vertices per chunk
on the documented 17-row grid (position tolerance 0.01 yd) and exactly one
12-index fan per rendered cell in the parser's order. Anything else returns null
and contributes no ground. It retains only 145 heights, a 64-bit rendered-cell
mask and the origin per chunk (about 150 KiB per tile), at most 12 tiles.
Queries choose the fan triangle containing the point and interpolate
barycentrically on the reconstructed grid positions, which equal the parser's
vertices bit for bit (accepted meshes differ by at most the 0.01 yd tolerance).
Float-rounded chunk seams accept a 0.01 yd overlap, so walking across a seam
never reports missing ground for a frame. The final settling or coasting step is
always presented; intermediate settling ticks keep the normal frame cadence.

- CPU scenes build heights on the existing preparation worker with the neighborhood
  and publish them with that scene, so walk follows what is drawn.
- Persistent GPU streaming builds heights on the source controller worker, cached
  per current resident and dropped with it. FX reads one immutable aggregate.
  No payload handles reach FX. A retiring resident simply contributes nothing.

`ExplorerMotion` is pure and deterministic, with no FX or I/O. Steps are capped at
0.1 s, axes are clamped so overlapping keys never double speed, and coordinates
stay within the camera's ±1,000,000 bound.

## Verification

```sh
./gradlew :rendering-world:check
# JavaFX display required:
./gradlew :gui:worldNavigationUiTest :gui:worldUiTest
```

- `TerrainGroundTest`: every rendered triangle centroid matches its own plane
  (independent mean-of-corners oracle) with both hole encodings; fan centers
  return recorded heights; hole cells, out-of-tile and non-finite points are
  unavailable; moved vertices, reordered fans, partial fans and truncated grids
  are not qualified; at most 12 tiles.
- `ExplorerMotionTest`: exact fly displacement and sprint, dt cap, smooth
  ease-in/convergence/terminating coast, walk eye height while moving, vertical
  keys ignored, walk speed independent of flight speed, immediate lift and eased
  settle, held altitude without ground, dolly in both modes, bounds and invalid
  steps.
- `WorldNavigationUiTest` disables smoothing for its exact oracles, then drives
  actual F key events (repeat ignored), held altitude over the fixture's
  unqualified mesh, and surface following over an authored ADT18 root.

Local runs used a private headless `kwin_wayland --virtual --xwayland` session.
No game data or screenshots are included. Real-client movement parity (speeds,
eye height, step-up) is not claimed.
