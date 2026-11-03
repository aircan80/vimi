# Inline GPU terrain implementation review

## Independent approvals

- Scene/camera/checksummed file APIa46c7f6: root and acceptance approved immutable
  ownership, bounds, native projection and exact file identity before allocation.
- Persistent native renderer e6c3ed1: acceptance independently passed30 actual
  Vulkan frames and maximum nine-tile upload; root reviewed capability checks,
  source shading, resource ownership and cancellation cleanup.
- Child WORLD transport55c0b4a: root reviewed typed mode/XOR validation, bounded
  pre-map opening, failure protocol and shared ring. Legacy model native/FX proofs
  pass. Root additionally reviewed/executed nine-case child proofad90fa9 (integrated
  7d1c4d2): actual color/sky, camera/resize/burst, CLOSE/EOF, wrong-kind/oversized
  control failure, bridge caps and mixed-scene CLI rejection. Independent actual
  World FX proof exercises the real default child CLI.
- Shared GUI service8a6c8e9: acceptance independently reviewed and executed typed
  controls, wrong-kind rejection, one-child replacement and old-owner isolation.
- Controller9843a7b/c30075f: acceptance's separate manual-FX/fake-process tests
  prove first accepted upload, resize barriers, stale/lagging delivery, hidden
  owner retirement, explicit retry and fallback without respawn loops.
- World wiring bdc35e7/0eae668: architecture approved after fixing empty-neighborhood
  stale rendering and separating nearby discovery100ms from faster GPU controls.
  Initial camera/snapshot preparation stays off FX, and backend activity ends
  only after an accepted current frame. Acceptance independently authored actual
  World FX proofc9bc59d (integrated5679b07).

## Executed evidence and limits

All16 terrain/sky offline blobs came from pinned matching tools, with Windows
DX11 artifact41714852002 metadata and twelve non-DX binaries matched before
integration. Production sources/resources preserve that qualified shader content.
All24 packaged model/terrain/sky hashes verify.

27 authored feasibility and30 production Vulkan frames pass. Production cases
cover native axes, original-altitude shading, unclamped intermediate color,
original-triangle fog, near/far rules, holes, close/far overlaps, first-wins depth,
source index37119 and full nine-tile589,824-triangle/27MiB upload. Canceled native
construction can be followed by a successful successor. Logical child payload
ledger91,353,380 bytes stays under128MiB; JVM/driver/RSS overhead is not claimed.

Root GUI checks and actual authored World FX proof pass: camera+resize preserve
one child PID, hidden views drain/resume, software toggling drains GPU ownership,
and empty neighborhoods retain CPU sky after actor drain/FX flush. Existing World,
model activity and actual inline model proofs also pass. Authored screenshot is
source-free; no game assets/screenshots enter Git.

Actual native hardware evidence is Linux Vulkan only. GL homogeneous depth is
explicitly declined for terrain. Offline Metal/DX11 compilation and packaged
native load checks do not qualify those hardware paths. No universal performance
speedup is claimed. Hosted native CI and all five packaged checks gate delivery.

## Private live-source proof

Independent Forever1.60.1.70755 proof read cached root ADT777947 at32,32 from
composite read-only original/scratch stores with localOnly and no network. Its
37120 vertices/65488 triangles/256 chunks rendered through CPU and actual Vulkan
at four cameras. Minimum silhouette IoU was0.99995716; intersected RGB mean error
was at most0.000648 channel units. A strict interior mask excludes RGB transitions
in either image with a one-pixel neighborhood and two-pixel frame border; its
24268–91876 retained pixels per view matched exactly. Boundary exclusions are
explicit; this does not claim whole-frame identity.

One WORLD child handled28 requests, including800×500/640×400/720×450 resizes,
then CLOSED/exit0. On this Intel adapter, launch-to-copied frame was367.403ms and
27 warm request-to-copied samples had median34.0924ms/p9535.0244ms. Extra detached
pixel copying sampled0.2162ms median. CPU reference frames separately took20.2–35.1ms
without budget truncation. FX upload was not measured here, and these observations
are not a cross-platform or universal speedup benchmark. Frozen JAR hashes,
private images and logs remain under `/tmp/vimi-live-mount79-proof/terrain-live-final/`.
No private source assets or images enter Git.
