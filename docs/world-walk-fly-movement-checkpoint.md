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

The body is drawn as a murloc (creature display 441) that stands, walks and
runs with its movement, and is hidden in first person. It collides with the
terrain, buildings and doodads. See [Drawn body](#drawn-body-murloc).

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
- In deep terrain water the body swims (see Swimming below).
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

## Swimming

A walking body swims once its feet are 0.6 of its height below the terrain's
liquid surface (1.22 yd for the default capsule). The fraction is flagged: the
client's threshold is unknown, to be measured by wading into a lake until the
swim animation starts. While swimming:

- No gravity. Swim 4.722 yd/s forward and sideways, 2.5 yd/s backwards
  (emulator constants).
- Forward follows the view's pitch, so looking down dives and looking up rises;
  Space rises straight up and the descend key sinks. The body never rises above
  the swim depth: at the surface it floats, following a sloping surface, and it
  stays at its depth when no key is held.
- The bed and banks collide. Only walkable ground lifts the body toward the
  shore; a steep bank is a wall. It walks again once walkable ground lifts its
  feet 0.05 of its height above the swim depth (flagged, so the body does not
  switch at the edge every step), and falls where the liquid ends.
- The murloc plays SwimIdle at rest and Swim when moving; the status line says
  "Swimming".

The surface comes from `LiquidSurface` (`rendering-world`): every MH2O cell of
the resident tiles, drawn or left out of the packet, with its corner heights,
split on the NE--SW diagonal like the drawn quad; where layers overlap the
highest surface answers. Each cell keeps its kind: water or ocean as drawn, and
magma (LiquidType 3) or slime (LiquidType 4) for coloured layers; any other
coloured liquid is "other". Swimming is the same in every kind.

A tile's liquid arrives with its terrain: the stream copies the water while it
prepares the tile, before the tile's heights reach the body, so there is no
moment with ground but no water. Where a tile's water is refused or its copy
fails, that tile has no liquid surface: a body walks or falls to the bed there
and walks on it (the tile's water note says "no swimming here"). If the water
appears later (the tile is read again), a body on a bed deeper than its swim
depth starts swimming where it stands, on the bed, and Space takes it up to the
surface; it is not lifted by itself. The surface is built apart from the drawn
water: a drawn copy that fails keeps it, and a tile with more liquid cells than
`LiquidSurface.MAX_CELLS` keeps its highest layers and notes the rest.

The exit is decided where the body would stand: the walk-out check snaps the
feet down to the ground it found (up to 0.1 yd) and walks out only when they
are still above the exit depth there, so a short body (the murloc, 1.29 yd
tall, whose margin is 0.065 yd) cannot snap back below its swim depth.

Not covered: water inside buildings (WMO liquid, such as the Stormwind canals)
and the older MCLQ chunk liquid are not read, so a body walks through them;
flying ignores water.

Real data (build 70862, `-Pprobe.mode=swim`, Elwynn 49,31, `-Pprobe.swimArea=18`,
Crystal Lake): 2,258 water cells, surface 57.6 to 59.1. Walking in from eight
shores, the body starts swimming 3.7 to 5.3 yd past the shore line where the
ground lies 1.24 to 1.35 yd under the surface, swims at 4.71 to 4.73 yd/s,
floats 1.22 to 1.27 yd under, and walks out with the ground 1.12 to 1.19 yd
under, switching once each way (three paths cross the lake and walk out on the
far side over shallows, switching there too). A step costs under 0.05 ms.

## Flying

The body goes where the view points (pitch included), strafes sideways, and
Space/E and Q/X climb and descend. It slides along terrain and never goes
under it. The speed is the 32 yd/s taxi speed, or the speed preset when that is
faster (labelled "beyond game speed"), doubled with Shift. Classic Era has no
player flight, so these controls are an approximation. Flight never waits for
collision: it collides with what is loaded and the status line says when that is
not all.

Flying down onto ground stops there; flying never lands by itself (as since
#704). A flying body that is still on walkable ground rests there: the status
line says "resting on the ground (the walk/fly key lands)", the gryphon plays
Stand, and it stays in fly mode until the walk/fly key switches to walking.

The flying body is a gryphon (creature display 1149, model 792, the taxi
gryphon); see "Drawn body" below.

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
| Swim forward, back | 4.722, 2.5 yd/s | Emulator constants |
| Swim depth | 0.6 of the body height | Flagged: unknown, to be measured |
| Swim exit margin | 0.05 of the body height | Flagged: explorer default |
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
what the renderer caps.

Buildings and doodads collide too (`CollisionWorld.combined`). For each
recorded tile within one tile of the camera (whatever the view distance),
`WorldTileCollision` reads every obj0 placement, never the capped draw set:

- each MODF building places all of its root's LOD0 groups. A group keeps its
  solid triangles (MOPY or MPY2: COLLISION, or RENDER without DETAIL) and its
  BSP, which narrows each query to the leaves a box touches;
- each building's doodad sets (set 0 and its own set, or its MWDR range) and
  each MDDF doodad place the model's M2 collision mesh;
- transforms are the drawn objects' own (ADT origin and heading, pitch and
  roll; set doodads composed with their building).

Parsed roots, groups and models are shared in a 96 MiB cache. The whole 3×3
around Stormwind measured 35 MiB on 70862 (Ironforge and Orgrimmar 19 MiB).
Tiles are read one at a time on their own worker. Each tile's placements get
their world transforms and boxes once, when read; merging the nine parts into
one `ObjectCollision` (a 32-yard grid, de-duplicated when two tiles list the
same building) only builds the grid. Parts farther than one tile are dropped.
A tile whose read failed, or that found a source it could not read yet, is read
again after 15 seconds, doubling with each such read in a row up to four
minutes. Sources the build will not give (not in the build, encrypted without a
key, protected) are remembered like malformed ones and never read again;
sources that cannot be read just now are not remembered.
Tiles the map does not record (open sea, the map edge) have nothing to wait
for. Until a tile's buildings are
read, the body still walks on its terrain and the status line says the
buildings there are still loading: loading warns, it does not stop the body.

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
- `WalkControllerSwimTest`: the swim values; floating at the surface without
  drift; swim, swim-back and strafe speeds; diving along the pitch, Space rising
  to the surface and no higher, sinking to the bed; walking into a lake and
  back out with one switch each way; a fall into deep water stopping; falling
  where the liquid ends; a steep bank stopping a swimmer without flicker;
  following a sloping surface; flight and dry ground never swimming; the
  murloc's capsule over beds around its exit and swim depths, and walking into
  the lake and out with one switch each way.
- `worldStreamSourceBridgeTest`: every source cell reaches the surface (also a
  layer capped out of the packet); past the cell bound the highest layers are
  kept with a note; a layer with a NaN corner is skipped; a drawn copy that
  fails keeps the surface.
- `LiquidSurfaceTest`: cell lookup and edges, the NE--SW split, the highest
  of overlapping layers, coordinates far from the origin.
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

## Body sequences (murloc, gryphon)

The drawn body's data:

- `M2IdleAnimation.readSequence` reads any sequence stored in the model (Stand
  0, Walk 4, Run 5, WalkBackwards 13, SwimIdle 41, Swim 42, Fly 135) under the
  same checks as Stand, with its recorded move speed (M2Sequence movespeed);
  `BuildDisplayModels.worldSequences` admits several under the World profile
  from one parse. Display 441 (murloc, model 31) has Stand, Walk, Run,
  SwimIdle and Swim, all in the model; it has no jump or fall sequence.
  Display 1149 (gryphon, model 792) stores Stand 0, Death 1, Walk 4, Run 5,
  StandWound 8, CombatWound 9, CombatCritical 10, AttackUnarmed 16,
  Whirlwind 126, Birth 127 and Fly 135 (`-Pprobe.mode=body -Pprobe.display=1149
  -Pprobe.allSequences=true` lists every stored ID up to 999): no idle-in-air
  sequence (no FlyStand 229, Hover 193, FlyHover 422 or InFlight 144; names
  from the public AnimationData list).
- `PreviewAnimation.write`/`read` carry an animation to another process (a
  versioned stream; another version, a truncated stream or more keys than the
  bounds is refused), and a `Skinner` poses it into caller arrays every frame
  without allocating.
- `BodyAnimation.choose` picks the sequence from the body's mode, status and
  direction: backpedalling plays WalkBackwards (else Walk, else Run), moving
  forward runs (walks at walk speed), deep water swims; flying moving plays
  Fly, flying still in the air FlyStand, else Hover, else Fly (the gryphon:
  Fly), and a flying body resting on the ground Stand; it falls back to what
  the model has and never makes up a pose. `BodyAnimation.rate` runs a sequence's clock at the body's
  speed over the sequence's move speed, so the feet keep pace with the ground.
- `BodyAnimation.capsule` sizes the body from the model's GeoBox times
  CreatureDisplayInfo.CreatureModelScale times CreatureModelData.ModelScale
  (`BuildDisplayModels.bodySize`; murloc: radius 0.51, height 1.29 yd;
  gryphon: radius 2.30, height 5.35 yd), because
  CreatureModelData's collision size is the same default for every model probed.
- Walking collision forgets the sources it refused for a missing or wrong key
  (or protected sections) when the build's key set changes
  (`BuildFiles.keyRevision`), and reads them again.
- The measurement probe's `body` mode renders each sequence of a display at four
  times on the CPU, and writes the World's body file (`body-441.wsb`).

## Drawn body (murloc walking, gryphon flying)

- Walking draws the murloc (display 441), flying the gryphon (display 1149).
  `WorldBodyModel` builds each body on a worker the first time the view enters
  its mode (never when only the free camera is used), once per World view: the
  display's World binding converted exactly as the World converts creatures
  (`WorldTileCreatures.convert`, which also reports the rest vertex behind each
  model vertex), its sequences and its size, packed as one body file
  (`WorldStreamBodyFile`: model, vertex map, scale, and each animation's
  `PreviewAnimation.write` record). The capsule takes the recorded size.
- From then on the stream controller hands the current mode's file to the World
  child (again after a restart), after the first visible tile, as file
  `body-1.wsb` and command `BODY_MODEL`. Switching walk and fly sends the other
  body, which replaces the drawn one; each body is built once and the same body
  is not sent twice in a row. Body commands go one at a time: the next only
  after the child answered the previous. Until the swapped body is drawn (and
  after a failed one) no body is drawn, never the other mode's;
  the child checks length, digest and every count and reference, builds the
  index buffer, textures and a dynamic vertex buffer in the objects' layout,
  and answers one `BODY_DONE` (`DRAWN` or `FAILED`; a failure only means no
  body). The body is charged to the objects' accounting like an object set: its
  texture handles count against the object handle limit (#658) and its buffer
  and texture bytes against the objects budget (#660), from attach until it is
  replaced or the session closes; a body that does not fit is refused
  (`FAILED`) and the previous one stays attached.
- Each camera line in a body mode carries a `BODY` tail: the feet in
  thousandths of a yard, the yaw in ten-thousandths of a radian, the sequence,
  its clock in milliseconds and a hidden flag (first person). The longest
  camera line is now 392 bytes; the line limit is 400.
- Each frame with a new pose, the child skins the rest surface with the
  sequence's `Skinner`, gathers positions and normals through the vertex map
  into the dynamic vertex buffer and draws the body's ranges with the objects'
  program, materials and states under one instance row (yaw about +Z, scale,
  feet relative to the camera). Opaque and cutout ranges draw with the objects.
  Its blended and additive ranges draw after all object blended ranges, in
  file order, not sorted with them by distance (the body is near the camera,
  so this is usually right; a blended object between the camera and the body
  would draw under it). Nothing is allocated on
  the Java heap per frame; a repeated pose is not uploaded again.
- `BodyAnimator` keeps the clock: the sequence from `BodyAnimation.choose`, its
  clock at `BodyAnimation.rate`, restarted on a change of sequence; without
  WalkBackwards (the murloc has none) backpedalling plays the walk in reverse.
  A standing body keeps requesting frames at about 12 a second so its idle
  plays; none in first person (the body is hidden) or while the view is not
  focused.
- Proofs: `nativeGpuWorldStreamBodyProof` draws a body (an authored one, or
  `-PbodyFile=body-441.wsb` or `body-1149.wsb` from the probe) standing,
  walking, running and, when it has Fly, flying above the ground, from the
  follow camera and from the side at four times each, and checks that it draws,
  that a hidden body draws nothing, that the legs (or wings) move and that a
  repeated pose is not uploaded; `-PswapBodyFile=` then swaps in a second body
  and checks it draws holding only its own textures.
  `nativeGpuWorldStreamBodySessionProof` sends a body through the real World
  child and checks `BODY_DONE 1 DRAWN` and its frames, then swaps in a second,
  larger body and checks its own `BODY_DONE` and that the frame shows it.
- Not yet: jump and fall sequences, and the body turning toward its movement
  while strafing.

