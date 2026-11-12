# World walk and fly movement checkpoint

Issue #179 and the walk/fly body work. The World explorer has three motion
modes:

- **Free camera** (default) keeps the established controls: WASD, Space/E up,
  Q/X down, arrows/drag to look, Shift ×2, wheel dolly along the view
  direction, speed presets 1 to 5. No collision.
- **Walk with collision** (checkbox or F; F again returns to the free camera)
  moves a body through the terrain with an upright capsule. The camera follows
  it.
- **Fly with collision** (checkbox or G; G again walks) moves the same body
  without gravity.

No body is drawn yet; the camera follows an invisible capsule.

## Walking

`rendering-world` `WalkController` integrates fixed 1/60 s steps (at most 12 per
frame; longer gaps are dropped, not caught up):

- Run 7 yd/s forward and sideways, backpedal 4.5 yd/s, no acceleration ramp
  (the client has none), and no smoothing.
- Gravity 19.29 yd/s² down to a terminal speed of 60.15 yd/s. Space jumps at
  7.96 yd/s (apex about 1.64 yd). A jump keeps its take-off velocity; there is no
  air steering yet.
- Ground steeper than the slope limit is a wall to walk into and a slope to
  slide down. Ledges up to the step height are walked up; taller ones block.
- Walls stop the body one radius away; moving at an angle slides along them.
- Terrain holes are open: the body falls through. A body that falls 500 yd below
  where it last stood (a hole into nothing) is put back there.
- Where collision is not loaded yet, a walking body waits: it does not move into
  the unknown area and does not fall while its own footprint is unknown. The
  status line says so (amber mode icon).

Collision is swept, not sampled: the capsule is a vertical stack of spheres (the
bottom and top are exact hemispheres), each swept against the triangles in the
box around the move with Fauerby's plane/edge/vertex tests, followed by
collide-and-slide. Steps go up by the step height, slide, then down. A contact
supports the body when its triangle is walkable and the touched point is at most
a step above the feet, so the rim of a tall ledge cannot be climbed with the
capsule's rounded side.

## Flying

The body goes where the view points (pitch included), strafes sideways, and
Space/E and Q/X climb and descend. It slides along terrain and never goes
under it. The speed is the 32 yd/s taxi speed, or the speed preset when that is
faster (labelled "beyond game speed"), doubled with Shift. Classic Era has no
player flight, so these controls are an approximation. Flight never waits for
collision: it collides with what is loaded and the status line says when that is
not all.

## Camera

`FollowCamera` orbits the eye (90% of the capsule height) at a zoom distance
behind the body, along the view direction. Mouse look and the arrow keys turn
the body; pitch tilts the camera. A small sphere swept from the eye pulls the
camera in front of terrain instead of through it, and it eases back out over a
quarter second. The wheel zooms by 1.5 yd per notch, from first person out to
39 yd (the client's 15 yd base times its largest zoom factor of 2.6). C
switches between first person and the last follow distance. A camera moved by
someone else (a bookmark or a typed jump) places the body under it, standing on
the ground below.

## Values

`MovementParity` holds every value with its source:

| Value | Explorer | Source |
| --- | --- | --- |
| Run, walk, backpedal | 7.0, 2.5, 4.5 yd/s | Emulator constants; client check owed |
| Swim forward, back | 4.722, 2.5 yd/s | Emulator constants (swimming not yet used) |
| Turn rate | π rad/s | Emulator constant |
| Gravity, terminal speed | 19.291105 yd/s², 60.148003 yd/s | Emulator constants |
| Jump velocity | 7.95554 yd/s | Emulator constant |
| Taxi speed | 32 yd/s | Client flight paths |
| Slope limit | 50° | Flagged: unknown, to be measured |
| Step height | 1.0 yd | Flagged: unknown, to be measured on stairs |
| Capsule | radius 0.611, height 2.031 yd | CreatureModelData default size |
| Camera | 15 yd base, ×2.6, start 6 yd | Client base and zoom factor; start flagged |

## Collision source

`CollisionWorld` answers boxes with blocking triangles and whether the box is
fully known. Today it is the retained terrain (`TerrainGround`): every rendered
cell's four fan triangles, holes emit nothing, and a box is still loading while
part of it lies outside every retained chunk. `TerrainGround.coverage` tells a
hole (a loaded chunk without a rendered cell) from unloaded ground. The terrain
is retained for every resident tile in full, so collision does not depend on
what the renderer caps. Buildings (WMO) and doodads (M2) follow.

## Source of heights

`TerrainGround` samples the same triangles the viewer draws. `Tile.of`
qualifies the parser's ADT18 source-order layout: 145 vertices per chunk on the
documented 17-row grid (position tolerance 0.01 yd) and exactly one 12-index fan
per rendered cell in the parser's order. Anything else returns null and
contributes no ground. It retains only 145 heights, a 64-bit rendered-cell mask
and the origin per chunk (about 150 KiB per tile). Float-rounded chunk seams
accept a 0.01 yd overlap.

- CPU scenes build heights on the existing preparation worker with the
  neighborhood and publish them with that scene.
- Persistent GPU streaming builds heights on the source controller worker,
  cached per current resident and dropped with it. FX reads one immutable
  aggregate.

## Verification

```sh
./gradlew :rendering-world:check
./gradlew :gui:worldExplorerInputTest
# JavaFX display required:
./gradlew :gui:worldNavigationUiTest :gui:worldHudUiTest
```

- `WalkControllerTest`: the value table; run, backpedal, strafe and diagonal
  speeds; fall time with gravity and terminal speed; jump apex and air time; no
  air steering; walls and sliding; a 30° ramp walked, a 65° slope blocked and
  slid down; a 0.6 yd step climbed and a 1.4 yd ledge blocked; ledges and holes
  fall; falling out of the world puts the body back; unknown collision waits;
  flight without gravity, along the pitch, skimming the ground; plane, edge and
  corner sweeps.
- `BodyMotionTest`: placement on the ground below the camera, own and foreign
  cameras, turning, the camera pulled in by a wall and the ground and easing
  back, first person and the zoom limit, flight along the pitch.
- `TerrainGroundTest`: coverage tells holes from unloaded ground; box triangles
  match the heights.
- `WorldExplorerInputTest` and `WorldNavigationUiTest`: the mode keys and
  checkboxes, body placement, waiting without collision, walking on an authored
  ADT18 surface, the follow camera, and flight speed from the presets.

No game data or screenshots are included. Real-client movement parity is not
claimed for the flagged values.
