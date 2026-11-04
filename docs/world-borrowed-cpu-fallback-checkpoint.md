# Synchronous fallback over resident terrain borrows

The inline streaming controller needs CPU fallback without constructing a third mesh
array set beside the existing cache's raw/native geometry owners. TerrainRenderer's
legacy Scene constructor deliberately copies WorldFormats.Terrain arrays and remains
available with that ownership contract. The new renderBorrowed operation reads the
existing immutable TerrainScene.Tile buffers synchronously and returns only a fresh
pixel Frame. It creates at most nine small surface/buffer descriptors; it never
constructs WorldFormats.Terrain or copies native mesh arrays. No borrowed Scene is
publicly returned or stored. The caller must hold source/geometry/material callback
pins until the render returns, including cancellation. This renderer is structural
and confers no source authority.

The shared software kernel uses absolute read-only buffer reads; clipping, lighting,
texture sampling, fog, raster order, work/cancellation and frame bounds are unchanged.
The legacy owned constructor still clones its inputs. Borrowed input accepts0..9
unique filename XY tiles; an empty table renders sky. Source/atlas lifetimes and GPU
process failure/drain belong to the later controller, not to this helper.

Three authored nine-surface hashes/triangle counts were captured from the unmodified
array kernel at integration38acbe5 before refactoring. Both legacy and borrowed paths
match exactly across near clipping, distant fog and camera reversal. Existing analytic
ray/plane texture and fog tests now exercise borrowed rendering as well as legacy
parity. Bounds, duplicate XY, cancellation and independent buffer positions are covered.
Full rendering-world checks pass10tasks in `/tmp/vimi-world-borrowed-cpu-check.log`.

Private authored allocation probe `/tmp/vimi-world-borrowed-cpu-goldens/allocation.log`
uses nine maximum-shape meshes prebuilt outside the measured interval. After warmup,
legacy Scene construction PLUS one160x120frame allocates19,261,544current-thread bytes;
borrowing plus the same frame allocates8,174,424bytes. The11,087,120byte difference is
the avoided third mesh set and small descriptor differences. This measures fallback
scene entry, not an11MB saving on every existing legacy frame: legacy Scene is normally
reused between frames. Output/depth/projection scratch still allocates. This is a
local Java25 allocation diagnostic, not retained heap, whole-app memory or GPU/FPS
qualification. No user/game data or native context is used by the probe.

No GUI path is switched here. The future source-owned controller must call this
inside actual payload/material borrows; it cannot retain nine additional geometry
or atlas claims on each commit or treat these authorable tile objects as verified.
