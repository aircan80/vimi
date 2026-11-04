# Persistent World GPU streaming

Status: pre-code architecture proposal at `4c21343`. No production implementation
or achieved frame-rate claim. Immediate atomic cold-bundle work is complementary:
it removes the geometry-then-texture transition, while this proposal removes
normal movement's renderer restart and repeated upload of unchanged tiles.
Models retain their existing renderer, protocol and limits.

## Observed costs and goals

The current path replaces immutable whole scenes. `WorldView.prepare` constructs
both a CPU Scene and native Tile snapshots for the neighborhood; overlapping
tiles are copied again. `TerrainGpuPreview.request` opens a new native request
when scene or texture-snapshot identity changes. `GpuPreviewService` drains its
old child before starting another, then serializes/reuploads the complete scene.
This protects ownership but necessarily interrupts new camera frames during a
neighborhood handoff. At this baseline geometry and appearance can also arrive
as two successive requests; the atomic cold-bundle fix is intended to stop that.

Evidence in repository source:

- `gui/.../WorldView.java` lines 1191–1204, 1246 and 1307–1338: scene cloning,
  replacement, texture retirement and post-wave material preparation.
- `gui/.../TerrainGpuPreview.java` lines 83–114: new scene/appearance request.
- `rendering-bgfx/.../BgfxTerrainRenderer.java` lines 82, 187–250 and 270–297:
  retained CPU scene, complete-tile packing/copy and common scene origin.
- `concepts-world/.../WorldTerrainTextureSource.java` lines 528–576: warm lookup
  currently follows root/grid/tex0 reads and parsing.
- `GpuPreviewService.java` lines 556–593 and `GpuViewerMain.java` main loop:
  15 ms control-writer polling, approximately 33 ms parent frame polling and
  33,333,333 ns child submission cadence.
- `GpuFrameFile.java` lines 272–341: every successful candidate read allocates
  an owned `int[width*height]`, copies pixels and copies them through CRC scratch.

Existing private diagnostic observations, not controlled comparative benchmarks:
three-source preparation about 483 ms cold / 148 ms warm, and a full native child
replacement around 745 ms in the root's profiling. The private actual-game UI
proof at `14c8c92` took about 2.33 seconds from catalog startup to an accepted
three-tile textured frame; that includes UI/source/child startup, not GPU draw
latency. It proved 768 regions, same-child steady camera/resize, a new textured
CPU frame, stationary resume and complete ownership drain. Boundary frame-pacing
QA is running separately; append its actual trace before implementation tuning.

The first delivery must keep the same child/context during ordinary movement,
keep the camera updating while source/decode/upload work runs, reuse unchanged
tile resources, and make each incoming geometry/appearance pair visible together.
Never expose temporary height-only geometry merely because its atlas is still
loading. Missing unsupported material is a distinct explicit fallback outcome.

## Smallest persistent resource model

Use one application-arbitrated World GPU session for the visible selected
build/locale/map, with at most **nine active tile pairs and one inactive staging
pair**. The tenth slot is not drawn. It is an explicit bounded staging allowance,
not permission for ten active atlases or a larger neighborhood. Each transaction
prepares one incoming tile; when ready, atomically replace an outgoing active
member (or add while fewer than nine are active). Keep readable old terrain until
that operation is ready. A diagonal transition may arrive as several completed
pair transactions; it never shows a half-uploaded tile or changes a tile from
temporary height shading to texture on successive frames.

Allocate ten reusable dynamic instance buffers and ten mutable 512² RGBA8
textures once. No handle creation/destruction churn during ordinary streaming.
Do not set ALLOW_RESIZE. A handle has space for the existing 65,536 triangles;
active total work still obeys 589,824 triangles and 48 bytes per triangle.
Bind instance data through the dynamic-buffer instance API. Draw at most nine
active tile partitions in the explicitly committed order using SEQUENTIAL view
ordering and existing reversed D32F/strict nearest-first-wins semantics.

A pinned implementation detail matters: full-tile dynamic allocation is **4 MiB
of bgfx backing**, not 3 MiB. The pinned `createDynamicVertexBuffer` requests
aligned size plus a stride; its ordinary dynamic allocator rounds the resulting
3 MiB + 48 bytes up to a 1 MiB boundary. Charge ten × 4 MiB. Do not evade this
by adding an unrelated COMPUTE_READ flag/capability or dropping the last triangle.

One decode/pack worker validates one incoming packet and computes the existing
native normal/mean-altitude flat color, geometry fingerprint, source mapping,
counts and packed data. The owner thread never performs file IO, source parsing,
large geometry hashing or a complete 3 MiB packing loop. Neither resident slots
nor session state retain a TerrainScene's vertex/index arrays or nine decoded
CPU atlases after upload. They retain handles, origins/mapping, counts, bounds,
coverage, keys and order only. Release all raw/immutable decode aliases explicitly;
GC liveness of a local variable is not the ownership contract.

## Coordinate representation and depth equivalence

A literal fixed global packing origin avoids reupload but eventually makes
near-camera subtraction inaccurate far from that origin. Per-tile origins improve
locality, but independently rounded per-tile eye matrices can give identical
coplanar geometry different eye Z and change strict first-wins behavior.

Recommended first representation: store the **original absolute native float
positions** in the same 48-byte instance layout, with immutable encoding origin
zero. These already are the source's coordinate precision; no new source
quantization is introduced. For every frame use one common float rebase point
`R=(float cameraX,float cameraY,float cameraZ)` and one common eye matrix, computed
from the **double** remainder `camera - (double)R`. The vertex shader computes
`relativePosition = sourceNativePosition - R`, then applies that common eye
matrix/projection. R changes by uniforms only; no resident tile is repacked.
Identical input positions in different tiles now use identical shader position
arithmetic and the same common uniforms, rather than different tile matrices.

Compute each tile's atlas UV **per vertex** from its recorded native atlas anchor
minus original native XY, scaled by 3/1600; perspective-interpolate that bounded
UV and the original-triangle fog. This is affine UV mapping, not ENV generation.
Avoid interpolating large absolute XY and subtracting the anchor in the fragment
shader. Existing source-grid/coverage and at-most-1/32-texel admission remains;
this does not weld native anchors or promise all finite coordinates qualify.

These are proposed arithmetic changes, not proof of equivalence. Before renderer
approval require all four offline shader profiles and actual Vulkan oracles for:
identical/copolanar triangles with distinct tile anchors, both orders, shared edges,
actual float seam gaps/overlaps, ±999k coordinates, camera crossing rebase rounding
boundaries, near clipping and far-surviving depth comparisons. Preserve raw-native
altitude shading, original-corner max-depth fog, whole-triangle far rejection,
opaque alpha, depth clear zero and GREATER. CPU reference and GPU must share the
same explicit committed draw order. Different resident slot indices cannot alter
coplanar priority. If this representation fails those gates, review the arithmetic
again; do not silently fall back to differently rounded per-tile matrices.

## Incremental upload and camera-first owner loop

Start with at most 256 KiB and 2 ms of upload enqueue work per owner tick, checked
between commands. These are proposed budgets for measurement, not a guarantee
that a driver call cannot block. Geometry chunks are 2,048 triangles = 98,304
bytes; atlas strips are 32 rows = 65,536 bytes. At most two geometry chunks plus
one atlas strip fit the byte allowance. One full pair therefore needs at least
16 upload ticks; there is no invented instantaneous full-tile upload claim.

The owner first consumes the latest camera, renders when its frame budget/ring
permit, handles ready readbacks, and then spends a bounded upload slice on the
inactive slot. Consolidate advancement so bgfx frame processing happens whenever
uploads/readbacks need it even if the camera is stationary; do not starve release
callbacks because no WORLD revision changed. Do not call blocking IO or wait for
source work on this thread. Scene changes mark render dirty even when the last
camera revision is unchanged.

Use one immutable 3 MiB packed buffer and one 1 MiB atlas direct buffer for the
incoming pair. Refer to bounded slices through make_ref_release instead of
accumulating nine complete bgfx_copy allocations. Release callbacks can run on
any thread: they only update a bounded credit/mailbox, never call renderer APIs.
The buffers remain valid until all references are consumed; owner cleanup/free
occurs once. Cancellation of a half-uploaded inactive pair cannot free referenced
memory or make that pair visible. The callback object itself lives through bgfx
shutdown. A missing/failed drain eventually uses the existing process watchdog;
it does not justify unsafe early freeing.

A callback's consumption is **not a GPU display fence**. Updates and subsequent
active draws must obey bgfx/backend command ordering, with repeated overwrite,
cancel, commit and resource-reuse native probes. Fixed resource handles avoid
assuming destroy-frame counts prove GPU memory has been reclaimed. Backend
internal allocation/renaming and driver memory require measurement separately.

## Native logical ledger

Keep the 128 MiB logical child ceiling, 800×500 bridge, three readbacks, nine active
atlases and current geometry work cap. Reserve/check the whole hot layout before
creating it; no old immutable renderer is created alongside this renderer.

| Charged term | Maximum bytes |
| --- | ---: |
| Ten dynamic instance backing allocations × 4 MiB | 41,943,040 |
| Ten GPU atlases × 1 MiB (nine active, one inactive) | 10,485,760 |
| Existing targets/readbacks/native pixels/flip/mapping | 19,200,256 |
| One raw decoded tile plus immutable defensive tile | 2,463,744 |
| One immutable packed geometry direct buffer | 3,145,728 |
| One decoded atlas heap owner | 1,048,576 |
| One atlas direct buffer referenced by updates | 1,048,576 |
| Packet input and checksum scratch, 2 × 64 KiB | 131,072 |
| Selector/sky VBO and dummy pixel | 40 |
| Session/packet/command/callback/shader metadata allowance | 2,097,152 |
| **Conservative named total** | **81,563,944** |
| Remaining within 128 MiB | 52,653,784 |

No 11,086,848-byte full CPU scene, 9,437,184-byte CPU atlas set or 27 MiB queued
copy set is retained here. Callback references alias the charged incoming direct
buffers; they do not duplicate them. If an implementation uses copy instead, or
retains boot/old/pending scene arrays, add those owners and reapprove the ledger.
The large remaining margin is not permission for unbounded backend staging.
Validate pinned allocator behavior, stats and repeated native update peaks; this
is not an RSS/JVM/driver-memory guarantee.

Parent texture preparation remains within its accepted 48 MiB component envelope
(43,328,576 named bytes, global 18-entry atlas pool), not 48 MiB for the app. Keep
nine current atlas owners and at most three speculative prepared neighbors;
source work still uses one active/latest descriptor. Drop speculative claims
first under pool pressure; never evict pinned current claims or allocate a 19th
payload. CPU renders may temporarily pin an older set: respect that backpressure.
Geometry/file/frame-bridge owners remain separately named and bounded.

## Packet, state and acknowledgement contract

Add a **new World-session mode**, leaving model and immutable terrain modes
compatible. A private session header binds fresh nonce, build/locale/map/WDT
scope digest, resource profile and coordinate/render revisions. Reuse the single
application GPU-child arbiter, parent-liveness/EOF/CLOSE watchdog and private
scratch directory; ordinary camera/tile updates do not reopen the child.

One credit permits one immutable incoming tile packet. A parent preparation
worker writes it completely, hashes header+body, then atomically publishes a safe
basename in the session directory. No arbitrary command-supplied paths/symlinks.
A proposed packet cap is **2,284,544 bytes**: 4 KiB header plus 1,231,872 bytes
of native vertices/indices and one 1,048,576-byte atlas. It binds source scope,
filename, fresh resident token, full geometry SHA, qualified material/cache key,
recipe, source tuples, coverage and payload checksums. Stream decode, bound all
counts before allocation and never map/read a whole neighborhood packet.

The source factory produces one of READY_BASIC, PARTIAL_BASIC or
HEIGHT_ONLY_SOURCE_UNAVAILABLE. Only an explicit terminal unsupported/unavailable
source result permits the last form; pending or slow texture preparation does
not. Native metadata is descriptive validation, not a new source-authentication
capability. A future material improvement replaces a complete tile pair too;
first v1 need not partially overwrite an active texture.

Proposed state machine (one owner thread changes visible/resource state):

```
FREE/CREDIT -> READING -> VALIDATED -> UPLOADING_INACTIVE -> READY
READY + valid commit -> ACTIVE; chosen outgoing slot -> FREE/CREDIT
READING/UPLOADING/READY + cancel -> CANCEL_DRAIN -> FREE/CREDIT
bad packet/source/version -> REJECT_DRAIN -> FREE/CREDIT
```

The decoder/IO worker has one active input and one latest metadata descriptor;
there is no second decoded incoming tile. The control reader is independent of
it, with one coalesced camera and a small bounded command queue. Camera/CLOSE
cannot wait behind parsing or upload. Commands remain ASCII ≤256 bytes. Use
monotonic transaction IDs and session nonce; stale/duplicate work cannot commit
into a reused slot (slot generation prevents ABA).

A commit carries expected current content revision, next revision and the ordered
active resident-token set (≤9), through a checksummed **256-byte commit file**.
The pipe command sends its basename/hash, not an oversized inline token list.
Each member must already be active or the sole READY incoming pair; no duplicates,
unknown tokens, unready slot, mismatched source scope or silent reorder is accepted.
Preserve the parent's explicit LoadedTile/active-list order for stayed members and
its explicit new-member order, matching CPU fallback. Do not silently sort by GPU
slot or use asynchronous arrival to define coplanar priority.

Commit swaps the whole ordered draw table between frames. Its ACK means state
accepted, not pixels presented. The parent can retire its old displayed metadata
only after an accepted frame for the committed revision. Invalid/stale commit or
optional packet failure leaves active data and camera rendering intact. Parent
can discard/recompute a latest desired neighborhood without killing the session.

Use bounded typed statuses such as SESSION/CREDIT, INPUT_CONSUMED, STAGED,
COMMITTED, REJECTED and CANCELED. INPUT_CONSUMED permits deletion of the closed
input file; it does not grant another upload credit. CREDIT only returns after
cancel/reuse ownership is safe. At most one tile packet and one commit file are
live; deletion follows consume/terminal ACK or confirmed child termination.
EOF/CLOSE/map/scope replacement drains decoder, references, uploads and readbacks
before destroying resources. Parent death uses the early watchdog. Device failure
terminates this session, retains the old UI image and offers the existing CPU
fallback without an automatic restart loop.

## Frame provenance and presentation

World hot frames need both **camera/control revision and committed content
revision**, plus frame sequence/session nonce. Introduce a versioned World frame
header/mode; do not reinterpret reserved fields of a model frame without a
version change. Reuse or factor the validated ring implementation only after its
compatibility/lifetime tests; model v1 remains unchanged.

A content commit forces a new render at the latest camera even without new input.
Do not use only cameraRevision>publishedRevision to decide whether to publish.
Readback slots capture both revisions. Parent presentation accepts monotonic
camera/content revisions for this session and its dimension/policy barriers;
it must not require equality with the newest desired camera or desired pending
neighborhood, which would starve frames while moving. Old committed content may
keep moving until new ready content is accepted; old policy/session frames may
not cross an explicit barrier. Test all ACK/frame reorderings and in-flight
readbacks from the previous active table.

Only an accepted FX upload ends the new-content activity indicator and publishes
that displayed set's counts. Camera-derived area/minimap metadata must reference
the same intended active/displayed set, not speculative prefetch entries. Hide
or leaving World still retires the child; persistence applies to movement within
the visible session, not background GPU ownership.

## Parent caches, prediction and bridge tuning

Keep geometry/material preparation off FX and remove whole-neighborhood cloning
from the normal hot path. Reuse immutable per-tile geometry with a bounded
12-entry current-plus-prefetch cache; current/descriptor lists share references.
Do not add an independent unbounded cache alongside WorldService's 12 entries.
If both existing raw Terrain and immutable native Tile caches are retained, charge
their worst distinct payloads explicitly: 2×12×1,231,872 = 29,564,928 bytes, outside
the texture component. Lazy CPU fallback can build its additional ≤11,086,848-byte
scene only when needed, after speculative work is canceled. A later borrowed
immutable CPU surface adapter can remove that clone, with its own tests.

Use at most three fully prepared neighbors ahead of velocity, a bounded desired
metadata set and one active preparation. Predict declared MAID tiles from the
already proven native filename axes, not transposed overview coordinates. Choose
a measured preparation/upload lead time with hysteresis, and reprioritize at
most about 10 Hz rather than per camera frame. Speculation yields to current
coverage and source/pool credit. At default 80 yards/s one tile takes ~6.67 seconds;
at maximum shifted speed 3,200 it takes ~0.167 seconds. A fixed small prefetch
budget cannot promise instant coverage at all speeds or cold network latency.
The required behavior is continuous camera/old terrain, bounded work and honest
pending coverage, not a false completeness claim.

An optional warm fast path may scan the bounded 18 private READY receipts by
scope/tile and fresh WDT/root/tex0 CK, then revalidate every previously consumed
BLP binding before reuse. Only prior privately constructed canonical/profile
proof can authorize skipping identical-CK root/tex0 parsing; no raw DTO or missing
unused palette entry can qualify a hit. Measure this separately from the protocol.

At 800×500 the current fresh parent array is 1,600,000 bytes; at a hypothetical
30 accepted frames/s that alone is 48,000,000 bytes/s of allocation. This is rate
arithmetic, not measured FPS or proof that GC causes every stutter. Keep the
existing bounded latest FX delivery. Consider a World-only three-slot detached
frame lease pool (4,800,000 bytes) with release on replacement/drop/FX setPixels
completion; never reuse while a consumer owns it. Image reuse already exists at
unchanged dimensions. Independently test that bulk upload consumes its pixels
before recycling. Model immutable-frame behavior remains unchanged.

An active parent poll near 8 ms can reduce the current independent 33 ms polling
phase delay; stamp-only checks should not copy pixels. Header/barrier rejection
before detached-array allocation can avoid copying known obsolete frames. Keep
idle polling/backpressure bounded. The first hot feasibility run can preserve
30 Hz; a 60 Hz admission trial must measure readback/copy/FX costs with the same
three-slot and 800×500 caps. Do not label a requested rate as achieved frame rate.

## Concrete staged implementation and acceptance

1. Review this scope/API/coordinate/resource contract and the pending baseline
   trace. Pin `WorldSessionSpec`, owned `PreparedWorldTile`, `WorldTileKey`,
   `WorldSessionHandle.updateCamera/offer/commit/cancel/closeAsync`, typed ACKs,
   packet/commit formats and frame provenance before production code. `offer`
   consumes its candidate even on rejection; metadata-only latest descriptors do
   not silently retain additional source buffers/atlas copies.
2. Add pure bounded packet/commit codecs and host/child state-machine fixtures:
   quota, truncation/checksums/source mismatch, replay/ABA, stale expected revision,
   supersession, closed delivery and file/lease cleanup. No native upload yet.
3. Authored feasibility spike on available Vulkan: ten fixed dynamic buffers,
   one inactive pair, chunk references/releases, cancellation and >100 slot reuse
   cycles; camera frames during upload; constant PID; no partial visible pair.
   Compile all four shader profiles. Actual Metal/D3D execution remains a clear
   qualification limitation, not an impossible prerequisite to Linux progress.
4. Implement owner-thread renderer only after resource/precision proof review.
   Independent oracles cover per-frame rebasing, source-order coplanarity, shared
   depth/seams/near/far/fog/UV, commit-without-camera, delayed old readbacks and
   current-data retention after every optional failure. Keep all existing model
   and legacy terrain tests passing.
5. Wire one persistent GUI owner/coordinator and bounded prediction; actual
   private-data boundary traces must show geometry+texture together, one PID,
   no source preparation waits on FX/native draw owner, and complete hide/close
   drain. Exercise rapid reversal, diagonal travel, stationary material retry,
   missing/partial sources, parent death and GPU failure/CPU fallback.
6. Compare accepted-frame interval/readback/FX-upload distributions against the
   fixed baseline trace, including boundary p50/p95/max and resource high-water
   marks. Proposed performance gate: no restart gaps, no temporary height phase,
   and boundary p95 within 25% of steady-motion baseline (or 40 ms, whichever is
   larger), with no preparation/upload-attributable gap above 100 ms on the fixed
   available-hardware trace. These are reviewable targets, not results. Adjust
   slice/poll/prefetch policy from measured evidence; then evaluate 60 Hz/frame
   pooling as a separately measurable improvement without widening resource caps.

## Pinned native references

The linked API/source is the repository's bgfx commit
`780f7bf00a29573473b90dcfab9f92a7d8687702` (LWJGL 3.4.3), not an assumed newer API:

- [C99 API: dynamic buffers, mutable texture updates and release callbacks](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/include/bgfx/c99/bgfx.h).
- [Dynamic allocation and stride padding](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/bgfx_p.h#L5165-L5260),
  [default dynamic backing size](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/config.h#L439-L441).
- [bgfx resource command execution](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/bgfx.cpp#L3560-L3610).

Resource calls are deferred; a release callback is a CPU-source lifetime signal,
not a blanket GPU-completion claim. Exact selected LWJGL binding signatures,
mutable-update behavior and backend allocation peaks are required spike evidence.
