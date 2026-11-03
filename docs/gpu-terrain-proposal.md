# Inline GPU terrain proposal

Status: design for review; no terrain implementation or backend performance claim.

Review record: root and domain reviewers agree with the bounded scene/instance
direction and corrected arithmetic; acceptance review agrees with the semantic
and lifecycle scope. Acceptance identified depth precision as a feasibility gate.
The proposal is **not fully approved for production implementation** until the
reversed-depth spike and independent overlap oracles below qualify that gate.

## First implementation slice

Render the World Explorer's existing height-only terrain inline through the bgfx
child process and frame bridge. Preserve its nine-tile neighborhood, native
coordinates, initial camera, WASD/Q/E movement, drag/arrow look, speed controls,
holes, sky, flat height/slope coloring, fog and depth behavior. Buildings, terrain
textures, water, collision, animation and new acquisition behavior remain outside
this slice. Keep the CPU renderer usable on unsupported or failed backends.

Relevant current boundaries:

- `formats-world/.../WorldFormats.java`: bounded ADT geometry and holes; MCNK
  positions are already native world coordinates. Do not reinterpret tile indices
  as vertex coordinates.
- `concepts-world/.../WorldService.java`: sequential acquisition worker, request
  cancellation and twelve-entry tile cache; retain this source owner.
- `rendering-world/.../TerrainRenderer.java`: current geometry, camera and visual
  reference; independent of JavaFX and native resources.
- `gui/.../WorldView.java`: active nine-tile neighborhood, sequential nearby loads,
  off-FX immutable scene preparation, initial/nearby activity and latest CPU frame.
- `gui/.../GpuPreviewService.java` and `rendering-bgfx/.../GpuViewerMain.java`:
  application-wide child ownership, watchdog, bounded startup/drain and frame IPC.
  Their present model scene/orbit controls must become typed consumers rather
  than accepting terrain through model validation.

## Preserve the existing visual contract

For native relative position `(dx,dy,dz)` and camera yaw `a`, pitch `b`, define
`forward=cos(a)*dx+sin(a)*dy`, eye right `-sin(a)*dx+cos(a)*dy`, eye up
`cos(b)*dz-sin(b)*forward`, and positive eye depth
`sin(b)*dz+cos(b)*forward`. Standard GPU eye Z is negative depth. Projection is
centered at `(width/2,height/2)` with focal length `0.8*width` in both axes. Retain
initial camera selection and pitch clamp `[-1.5,1.5]`. Preserve absolute native
camera coordinates. Select one immutable shared scene origin in native coordinates
and subtract it in double precision during native upload and camera-uniform
preparation, before float GPU math. Do not subtract two million-scale float
positions inside the vertex shader. Compute native altitude/lighting before
rebasing; rebasing must not alter map coordinates or tile lookup. Test a local
patch translated near the allowed coordinate extremes.

The CPU reference rejects an original triangle only when its maximum eye depth
is below `0.5` or its minimum eye depth exceeds `2200`. It clips surviving
triangles at the near plane `0.5`, but does **not** clip their portions beyond
`2200`. A conventional finite far plane would change visible geometry.

Prefer a **reversed-depth infinite-far projection** with a renderable D32F depth
attachment, clear depth 0 and strict GREATER. After backend viewport mapping,
window depth is `0.5 / positiveEyeDepth`; the nearest distinct surface wins, and
exactly equal depth retains the first submitted surface. For zero-to-one clip
depth use clipZ=near, clipW=positiveEyeDepth; for homogeneous-minus-one-to-one
use clipZ=2*near-positiveEyeDepth with the same clipW. This preserves the near
plane while leaving far distance unbounded. It is terrain-specific; do not alter
model framebuffer state. A homogeneous-minus-one-to-one clip backend may lose
precision before its viewport mapping: D32F plus reversed comparison alone does
not qualify that backend. Initially decline `homogeneousDepth` backends unless
a separately reviewed zero-to-one clip-control/depth strategy passes the same
actual overlap probes. Offline GLSL compilation is not OpenGL qualification.
Gate D32F framebuffer support and actual reversed-depth
behavior at runtime; unsupported terrain rendering uses CPU fallback.

This is an explicit implementation feasibility gate, not a completed proof.
Ordinary infinite-far depth near 1 loses precision: near 2000, standard D32F
depth has approximately 0.48-world-unit spacing, and a surviving far portion
near 100000 can lose approximately 1192 units. CPU depth stores eye distance, so
those ties could visibly select the wrong triangle. Do not ship standard depth
as a silent substitute. Reversed floating depth greatly improves the distribution
but still needs the independent close-surface/first-wins tests below; a generic
claim of arbitrary million-unit depth equivalence is not supported. If these
qualification tests fail, revise the representation/depth contract with reviewers
or retain CPU terrain rather than merely widening image-difference tolerances.

Each original triangle retains one color after near clipping. Compute its native
flat normal, mean native altitude, and the CPU reference's slope light/rock base
RGB during off-FX/native scene preparation. Keep that base RGB untruncated in
floating point; quantize only after fog, never to an intermediate byte texture or
integer attribute. At each camera update, transform all
three original corners; fog uses their **maximum original eye depth**, not clipped
corners or per-fragment depth. Apply exactly the existing fog equation and final
channel truncation. Supply the resulting identical color at all three corners so
rasterizer clipping/interpolation cannot introduce a gradient. Include degenerate
normal fallback, winding-independent absolute lighting, and original altitude
formula. GPU float arithmetic may differ at integer rounding boundaries: permit
at most one channel unit for analytic triangle-interior color oracles where
independently justified, not broad visual drift. GPU rasterization has its own
shared-edge ownership/subpixel rules; separate boundary coverage differences
from interior shading errors instead of promising byte-exact CPU frame parity.

Retain opaque alpha 255, no culling, depth writes and logical strict-nearest
comparison (CPU LESS; proposed reversed GPU GREATER). Submit in
snapshot tile order, then original triangle order; preserve equal-depth first
wins. Use a sequential bgfx view so sorting cannot reorder tile submissions.
Sky must reproduce the current row-dependent RGB gradient rather than a constant
clear color. Draw the background first with depth testing/writes disabled. A
bounded background pass may use fragment row coordinates, with
backend origin/readback orientation tested. Use the integer output row
`floor(fragmentCoordY)` after orientation normalization, not pixel-center
`y+0.5`, in the reference sky equation. Do not change the fallback's
30,000,000-work CPU budget; GPU output need not reproduce CPU early termination.

## Geometry representation and exact bounds

`WorldFormats.Terrain` allows at most 256 chunks, 145 vertices/chunk and
`8*8*12` indices/chunk. All coordinates must remain finite within +/-1,000,000.

| Quantity | One tile | Nine tiles |
| --- | ---: | ---: |
| Source vertices | 37,120 | 334,080 |
| Source indices | 196,608 | 1,769,472 |
| Original triangles | 65,536 | 589,824 |
| Expanded nonindexed corners | 196,608 | 1,769,472 |
| XYZ source bytes (12/vertex) | 445,440 | 4,008,960 |
| uint32 source index bytes | 786,432 | 7,077,888 |
| Source geometry bytes | 1,231,872 | 11,086,848 |
| Expanded 36-byte corner upload | 7,077,888 | 63,700,992 (60.75 MiB) |
| Expanded 48-byte corner upload | 9,437,184 | 84,934,656 (81 MiB) |
| 48-byte triangle instances | 3,145,728 | 28,311,552 (27 MiB) |

The earlier estimate of approximately 5.3 million expanded vertices triple-counts
indices: 1,769,472 indices already designate the expanded corners. Model
`PreviewMesh`/`GpuSceneFile` bounds must not be raised to accommodate terrain.

Recommend **persistent triangle instancing**. One static template triangle has
three corner-selector vertices. Each original terrain triangle is one 48-byte
instance, exposed as three vec4 attributes:

```
i_data0 = (Ax, Ay, Az, baseR)
i_data1 = (Bx, By, Bz, baseG)
i_data2 = (Cx, Cy, Cz, baseB)
```

All three vertex invocations can compute the maximum/minimum original corner
depths and identical fogged color. Select A/B/C by the template vertex. Rejected
triangles emit identical out-of-frustum positions, producing no fragments; do not
try to discard only one corner. The template preserves source triangle winding.
No geometry shader, primitive ID, vertex texture fetch, compute or 32-bit GPU
indices are required. Upload per-tile immutable instance buffers once per scene;
never allocate transient instance data or rebuild instances on camera motion.
A fully holed tile may have zero triangles: skip its instance buffer and still
produce a valid sky-only frame if the whole scene is empty.

bgfx exposes persistent vertex-buffer-backed instance binding and reports an
instancing capability; its documented instance stride is aligned to 16 bytes.
This layout uses three vec4s and a 48-byte stride. Treat support as a runtime gate,
not a promise inferred from OS alone. See the official [instance binding API](https://bkaradzic.github.io/bgfx/bgfx.html#_CPPv4N4bgfx21setInstanceDataBufferE18VertexBufferHandle8uint32_t8uint32_t),
[capabilities](https://bkaradzic.github.io/bgfx/bgfx.html#c.BGFX_CAPS_INSTANCING), and
[instance allocation rules](https://bkaradzic.github.io/bgfx/bgfx.html#instance-buffer).

A vertex/primitive-ID plus texture or storage-buffer fetch design could retain
shared vertices and smaller uploads, but adds resource-format, fetch/ID capability
and shader-profile constraints. It must not become an implicit requirement of
this first slice. Expanded 36-byte corners (own position plus the other two
corners, deriving native base in the shader) are a concrete 60.75-MiB alternative,
but cost over twice the recommended resident geometry and repeat flat math.
Expanded 48-byte corners retain precomputed base at 81 MiB. If persistent
instancing fails a target runtime probe, use CPU fallback first; an expanded GPU
fallback requires a separately reviewed memory budget and proof, not silent
allocation escalation.

Before the native representation implementation is committed, a tiny authored
instance spike must compile every offline profile using the project's pinned
bgfx/LWJGL versions (GLSL, SPIR-V, Metal and DX11) and actually render on the
available Vulkan adapter. Runtime Metal/DX11 probes qualify those backends when
hardware is available; their absence is an explicit platform acceptance
limitation, not a blocker to committing a Vulkan-proven implementation. Retain
capability checks and CPU fallback on every platform. Do not rely on newer
online API documentation alone.

## Scene file, allocation and resource budget

Propose `GpuTerrainSceneFile` version 1, little endian, with a 64-byte header and
one 32-byte descriptor per tile. Header: magic8, version4, tileCount4,
fileBytes8, flags4, reserved4, SHA25632. Descriptor: tileX4, tileY4, vertexCount4,
indexCount4, chunks4, reserved4, payloadBytes8. Each descriptor's payload is native
XYZ float32 followed by uint32 indices, in snapshot order. Digest covers header
bytes0..31, all descriptors and all payload bytes. Require exact size/EOF,
zero flags/reserved fields, unique `(tileX,tileY)` in `[0,63]`, 1..9 tiles,
per-tile source bounds above, index count divisible by three, valid indices,
1..256 chunks, and long-arithmetic aggregate checks **before allocation**.

Worst valid file is `64 + 9*32 + 11,086,848 = 11,087,200` bytes. Set a separate
hard **16 MiB** file cap; reject oversized files before mapping or decoding.
This file cap is independent of resident CPU/GPU resource budgets below.
Checksum covers metadata as well as geometry. Parent writes an immutable private
file before launch; a child does not read changing tile files or application
stores. No native handles or game source paths cross IPC.

Resident instance geometry is at most 27 MiB, with at most nine static buffers and
one 12-byte selector VBO. Stage one tile at a time (3 MiB maximum); do not create
an expanded scene-sized Java array. Account for source payload (~10.57 MiB),
queued bgfx copies (conservatively up to 27 MiB), resident GPU instances (27 MiB),
and current staging (3 MiB): less than 68 MiB before renderer frame resources.
The queue term is a conservative bound, not a claim that completed copies remain
resident; measure actual release behavior. Include any additional file bytes,
decoded-array clones and immutable-scene construction copies in the peak ledger;
they must not hide behind the 16-MiB file cap. Prefer streaming bounded tile
decoding to retaining both a full file byte array and cloned geometry. These are
logical payload bounds, not an RSS/driver allocation promise. Child cleanup must
release partial buffers on every upload failure.

Keep World's initial requested viewport cap at 800x500; preserve aspect ratio if
scaled. Existing frame IPC at that size occupies exactly
`64 + 3*(64 + 800*500*4) = 4,800,256` bytes. Separately budget render color/depth,
three readback targets, three CPU readback buffers, detached latest frames and FX
image storage. Require a documented implementation allocation ledger with a
128-MiB logical child-owned resource ceiling, including any upload-copy peak;
backend allocation failure always falls back safely. No old/new child overlap.
Driver/JVM/Prism overhead is measured and reported separately.

## Shared API and ownership

- `rendering-world`: immutable `TerrainScene` snapshot and `TerrainCamera`
  projection/math; keep `TerrainRenderer` as reference/fallback. A prepared
  triangle-base helper should be shared by CPU/native preparation where possible,
  with independent analytic tests protecting the existing equations. Scene
  construction clones/validates on bounded workers, never FX.
- `rendering-bgfx`: `GpuTerrainSceneFile`, typed terrain child mode,
  `BgfxTerrainRenderer`, terrain shaders and native proofs. Reuse the existing
  offscreen frame/readback pipeline, header validation, watchdog and backend
  guards. Keep model materials and model cache codecs independent.
- GUI service: extract reusable child lifecycle/frame transport from model-only
  payload and controls. Explicit typed model/terrain requests share the one
  application-owned child; an owner/generation cannot close another owner's
  session. No unbounded generic command API.
- `WorldView`: source requests, nine-tile neighborhood and controls remain here.
  It selects GPU/fallback, presents activity/status and submits scene snapshots.
  `WorldService` remains unchanged unless a concrete source-lifetime issue is
  discovered. Do not run source acquisition or native initialization on FX.

## Control and replacement protocol

Terrain child CLI uses `--terrain-scene <absolutePath> --frames <absolutePath>
--parent-pid <pid>`; reject simultaneous model and terrain payload modes. Reuse
HELLO/READY/ERROR/CLOSED semantics, with READY after the first published frame.
New bounded ASCII control: `WORLD revision width height x y z yaw pitch` followed
by newline, at most 256 bytes. Validate finite coordinates/yaw within current
camera limits, pitch within +/-1.5, dimensions within the frame-file allocation,
and increasing positive revision. CLOSE/EOF/parent-loss behavior remains shared.
Never interpret a model VIEW as WORLD, or vice versa.

Coalesce camera updates in one worker-owned latest slot; keep at most one FX
frame delivery pending. Accept monotonic lagging camera frames for the current
scene to avoid starvation during continuous movement. Dimension changes advance
a presentation barrier. Scene/workspace identity changes invalidate the entire
session/generation. Preserve time-based movement integration and its existing
0.1-second delta clamp. Keep CPU movement rendering and nearby-load checks at
their existing approximately 10 Hz cap; GPU camera submissions may coalesce at
most 30 Hz independently, without raising source-acquisition cadence. This is a
scheduling ceiling, not a promised achieved frame rate. No native calls, process
waits, mapping reads or scene-file
writes on FX. The bridge copies GPU frames; it is not zero-copy.

For the first slice, a tile-set change prepares a replacement immutable snapshot,
then serially drains the old child and launches its replacement. Keep the last
readable image and current camera while loading. At most one preparation running
and one pending latest replacement; discard intermediate snapshots before launch.
Camera updates during replacement apply to the new child before its first frame.
The temporary interruption of GPU frames is an explicit first-slice tradeoff;
measure it before designing live native tile streaming. No camera-only restart.

Retain WorldView's first-tile/first-frame activity and nearby-terrain activity.
Do not clear loading merely because a native scene was uploaded; first-frame
activity ends on a frame accepted by the current FX generation, or on a terminal
fallback/error. Source revision/handle changes, selected map changes, Back,
deactivation, ancestor visibility loss and close retire ownership and stale
callbacks. Preserve current loaded data/camera when merely hidden; resume from a
fresh session without a failure retry loop. On unsupported backend or renderer
failure, release the GPU child and request CPU output for the same snapshot and
camera; provide an explicit retry choice or retry only on a new scene/source.

## Acceptance and implementation checkpoints

1. Review this contract and exact limits. Authored CPU-independent projected
   coordinate, flat-color/fog and near/far cases must pin reference semantics.
2. Add bounded scene/camera/file APIs and malformed/truncated/checksum tests;
   verify maximum-count arithmetic without constructing unnecessary giant test
   arrays. Verify source/native tile-axis orientation using asymmetric geometry.
3. Prove persistent triangle instancing, selector winding, original-corner fog,
   reversed infinite-far projection, sky orientation, no culling and logical
   nearest/equal-depth first-wins with
   tiny authored native frames. Include a near-crossing triangle whose original
   depth controls fog and a surviving triangle extending beyond 2200. Include
   all-beyond 2200 rejection and hidden holes. Verify infinite-depth equality and
   first-wins at near, ordinary and far-straddling ranges; document depth-buffer
   precision. Independently verify overlapping triangles separated by 0.01 and 0.1
   units near 2000, in both submission orders, and unequal-depth portions near
   100000 of triangles that survive the original all-beyond 2200 test. Include
   exactly coplanar first-wins, D32F capability rejection, and large-coordinate
   translated/rebased fixtures. Do not promise separation of arbitrarily close
   surfaces millions of units away with a float infinite-far projection.
4. Integrate GUI transport with fake/delayed lifecycle tests: replacement while
   moving, old frame after scene/source retirement, dimension barriers, hidden
   view, terminal GPU failure, blocked shutdown and no successor on unkillable
   child. Preserve local activity and CPU fallback behavior.
5. Run an actual authored FX inline proof, actual native terrain frames and
   source-free nine-tile upload bound/progress tests. Measure preparation,
   upload/replacement gap, request-to-frame latency, readback, copy, FX upload,
   steady throughput and memory on the available adapter. Report measured
   resolution/backend and cold versus warm results; set no unmeasured FPS claim.
6. Regenerate and verify every pinned shader profile, execute normal checks and
   independently review implementation. Linux x64/ARM64, macOS Intel/ARM and
   Windows x64 remain capability/fallback targets; compile success does not prove
   native execution on untested hardware/platforms. Keep such limitations visible.

## Authored feasibility checkpoint

The test-only `GpuTerrainSpike` harness uploads persistent 48-byte triangle
instances, uses a separate non-instanced sky program and renders into a reversed
D32F offscreen target. It does not load ADTs, start an application session, change
WorldView or replace a running application. Its initial native check covers sky
rows, opaque output, one authored triangle and camera reuse without re-upload.
Independent depth/color/geometry acceptance remains separately owned.

Explicit commands:

```sh
python scripts/rendering/compile_bgfx_terrain_spike.py
# Generate DX11 on the pinned Windows host, then import its authored-spike artifact.
python scripts/rendering/compile_bgfx_terrain_spike.py --verify
./gradlew :rendering-bgfx:nativeGpuTerrainSpike
```

Terrain and sky each have four offline shader profiles and their own manifest;
production model resources remain independent. The shader workflow retains the
separate `bgfx-terrain-spike-shaders` artifact. The native harness currently
qualifies only actual headless Vulkan with zero-to-one clip depth and renderable
D32F. Metal, DX11 and homogeneous-depth/OpenGL execution are not inferred from
successful offline compilation. No production GUI integration is authorized by
this test-only checkpoint without independent feasibility review.
