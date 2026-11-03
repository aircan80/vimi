# Terrain atlas pool and geometry binding checkpoint

This is the first production sub-checkpoint of the approved source/pool design
`6c95750` plus ownership clarification `ae49e6b`. It adds only the memory pool,
streaming geometry fingerprint and authored tests. Source acquisition, batch
scheduler, authenticated receipt/cache admission, public source API, GUI,
renderer, child transport and persistence are not implemented here.

## Pool behavior

`TerrainAtlasPool` is an explicitly application-owned instance. Its package-only
reservation API must be used before composition/output allocation. Exactly 18
live/reserved slots are available; each charges the conservative complete atlas
estimate of 1,065,248 bytes, including its mapping metadata. Publishing transfers
one reservation to a lease without allocating/copying pixel storage.

A private bounded SHA-256-keyed LRU cache holds complete-atlas claims, never
BuildFiles/source instances or owner sets. The future source helper remains
responsible for qualifying current receipts before lookup/publication. Partial
atlases can be leased without cache admission; zero-coverage atlas publication
is refused. The key itself is not a positive source capability. New source
receipt metadata, if needed, must remain bounded primitive/BuildRef data.

Cache/external/borrow claims share each payload slot. LRU eviction drops the cache
claim; it cannot reclaim a retained or borrowed entry. If all slots stay pinned,
a new reservation returns empty before allocating output. Pool stats expose
entries, reservations, cache entries, lease claims, borrow pins and charged bytes.

`Lease.withAtlas` independently pins its entry under the lock, executes the
synchronous callback outside that lock and releases in finally. Concurrent
handle/result closure cannot free/reuse the entry before callback completion.
The callback's atlas/pixel views must not escape its synchronous borrow; immutable
heap buffers are not revocable, and no independently owned view is promised.
`retain` supplies explicit ownership for asynchronous consumers. Closing a lease
clears its entry reference, is idempotent and never waits for a callback.

Pool close rejects new reservations/retains/borrows, drops cache claims and
asynchronously waits for reservations, leases and already-started borrows to
drain. It does not forcibly invalidate or reuse their slots. Its one completion
notification runs off-lock on a dedicated virtual thread so blocked future
consumers cannot execute on an FX/lease-closing caller. The later source stage
must integrate global active/latest work cancellation/drain before pool close;
this checkpoint has no worker or source streams to cancel.

## Geometry binding

`TerrainGeometryFingerprint.sha256(tileX,tileY,grid)` requires all 256 canonical
source chunks. It streams original native float positions and the existing
hole-dependent fan indices, without allocating a Terrain/mesh, boxed vertex
list or duplicate geometry arrays. `sha256(TerrainScene.Tile)` streams its
read-only buffers. Both hash the exact domain-separated little-endian format
pinned in the proposal, preserving raw signed-zero bits. Camera/rebase state is
excluded. This identifies the geometry arrays only; root receipt/source
qualification and later whole-scene annex binding remain separate responsibilities.

Authored tests pin an independently calculated 93-byte SHA-256 vector, signed
zero distinction, filename X/Y, altered vertices/indices/holes and initial
profile rejection of partial/noncanonical grids. A full authored ADT exercises
both legacy low-resolution and high-resolution holes and agrees with the
unchanged existing reader's actual Scene.Tile.

Private original-byte proof (`NativeGeometryFingerprintProof.java`,
`native-geometry-fingerprint-proof.log` under `/tmp/vimi-terrain-texture-proof`)
also matches source-grid and existing Scene.Tile fingerprints:

| Root / filename tile | Vertices / indices | Geometry SHA-256 |
| --- | --- | --- |
| 777947 / 32,32 | 37120 / 196464 | `c032876b1c24752cb8f2bedf95e710510402addf9ee98e89b6bff675601ae6df` |
| 777952 / 33,32 | 37120 / 196608 | `78f425129a060c44fcdf532cef1dab3a98471d35e2be79224bc147dfca889805` |
| 778147 / 32,33 | 37120 / 196464 | `9faff052ecb0f2a70f710e650a96fc4f3f8b579a0c862f57293b2854c39db836` |

## Validation and scope

`./gradlew :rendering-world:spotlessApply :concepts-world:spotlessApply
:rendering-world:check :concepts-world:check` passes (38 tasks). The new seven
tests exercise real separately composed 1 MiB outputs, reservation-before-output,
19th refusal, blocked borrower concurrent closure, replacement after final pin
release, cache/external sharing and eviction, thrown callback cleanup, pending
close and a deliberately blocked close-future consumer.

concepts-world adds an API dependency on rendering-world for the existing pure
atlas type. No dependency cycle or third-party dependency is introduced. The
future complete source-component 48 MiB ledger is not yet a runtime proof;
source buffers, callbacks, receipts and batch/future ownership remain the next
independently reviewed checkpoint. This checkpoint's independent approval is
pending.
