# Inline GPU terrain implementation review

## Independent approvals

- Scene/camera/checksummed file APIa46c7f6: root and acceptance approved immutable
  ownership, bounds, native projection and exact file identity before allocation.
- Persistent native renderer e6c3ed1: acceptance independently passed30 actual
  Vulkan frames and maximum nine-tile upload; root reviewed capability checks,
  source shading, resource ownership and cancellation cleanup.
- Child WORLD transport55c0b4a: root reviewed typed mode/XOR validation, bounded
  pre-map opening, failure protocol and shared ring. Legacy model native/FX proofs
  pass. Independent actual World FX proof exercises the real default child CLI.
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
