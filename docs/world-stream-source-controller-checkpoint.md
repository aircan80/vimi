# Concrete off-FX World source controller

This next implementation is package-private GUI code beside WorldStreamSourceBridge. It does not
edit WorldView or GpuPreviewService or launch a second GPU owner. Root supplies the already-launched
Session from the existing application GPU arbiter and an actual opaque Source/Scope pair. Current
source APIs include the final post-cancellation-drain lifetime gate and explicit-origin refusals.

## Exact boundaries

`WorldStreamSourceController(Source, Scope, Session, Events)` owns one off-FX orchestration worker.
`demand(Demand(viewGeneration, centerX, centerY, List<Tile> ordered))`, `camera(Camera)`, `pulse()`
and `closeAsync()` are metadata-only entry points. Demand accepts at most nine unique recorded
comparison tiles, in deterministic closest-first order; no MapInfo/Tile DTO creates source authority.
The actual bridge always requalifies the exact comparison through its Source/Scope.

A small `PresentationPort` adapter holds the not-yet-frozen Commit/Frame boundary. Its intended
implementation is exactly Session.commit(policy, Resident...) -> CF<CommitReceipt> and
Session.takeLatestFrame() -> Optional<Frame>. No success inferred from STAGED, produce completion,
file INPUT_CONSUMED or COMMITTED is a presentation event. The adapter can be replaced by the
concrete facade only after its independent review; no source-neutral fake receipt creates a holder.

## Bounded actor state

The controller retains one latest demand descriptor (nine comparisons), one latest camera, one
active tile reservation/producer and one coalesced completion token. Future callbacks never perform
source/file/pixel IO and never enqueue an unbounded event per callback. A single scheduled flag
coalesces worker kicks. Parent capacityRevision plus the existing view pulse wakes genuine pressure
waits; there is no immediate retry loop or pressure-to-height conversion.

Each successful actual STAGED pair is `(opaque Resident, unique bridge Prepared)`. The private holder
is taken only from that exact successful Producer. Current, candidate and last-presented membership
tables contain aliases to the same pair. Their union, including one construction admission, must be
<=12 before a producer starts; each table itself is <=9. No extra base retain, geometry/atlas copy,
source table index or discarded-neighborhood cache is added. One incoming child slot replaces one
current member; first useful tile is committed without waiting for all nine neighbors. Source/child
capacity limits remain unchanged. Held presentation applies backpressure rather than growing union.

Commit-in-flight membership stays immutable until its actual outcome. A new demand replaces only
the latest descriptor. The worker finishes a useful staged tile if it remains in latest demand;
otherwise it first calls TileReservation.cancel(), establishing the parent's stop bit, THEN changes
the producer's actor-owned latest predicate to false. The raw FX demand generation never directly
turns that predicate false: an unsolicited CancellationException is intentionally fatal at the
parent boundary. This ordering must be tested with held actual source preparation. Failed cancel
(ALREADY_COMMITTED/COMMIT_PENDING/etc.) is not cancellation success and cannot free source aliases.
No successor starts until the prior producer/file/cancellation owner has actually drained.

## Commit, frame and acceptance

A commit submits exact opaque STAGED/current Residents, deterministic unique XY, under the latest
camera policy. COMMITTED updates current metadata but retains outgoing last-presented holders.
Frames carry exact CommitReceipt membership and current frame policy/dimensions; historical content
policy need not equal a later resize policy. Parent frame gating enforces actual content ACK and
camera/policy causality before allocating detached pixels. The controller keeps at most one pending
latest frame mailbox; an FX runnable captures only a small wake token and pulls that mailbox.

`acceptPresentation(viewGeneration, sequence, receiptIdentity, policy, width, height)` is a final
metadata-only acceptance gate. It checks latest view generation, terminal state, monotonic frame
order, current viewport policy/dimensions, exact frame receipt identity and every represented
Prepared.isOpen. No source stat/parse, payload borrow or IO occurs on FX. Only successful actual pixel
upload calls this acceptance path and moves presented aliases. Dropped/superseded frames do not
retire outgoing holders. An already visible old map image can remain explicitly last-view while
new generation prepares; it is never relabeled as rendered from the new map.

Camera is independent of source progress: latest monotonic Camera is forwarded without awaiting
source/commit. Bounds, camera seed, current area/subzone facts and CPU fallback pixels are computed
inside Prepared.withPayload off FX. Only scalar facts/pixels escape; no Scene of nine cloned meshes
or escaped live Material/AreaMetadata is constructed. GPU-only fallback can borrow current valid
holders; workspace invalidation requires fresh source qualification. No parallel second acquisition
pipeline is started for CPU fallback.

## Retirement and feedback

Clean typed preparation refusal preserves current/presented tables. RETRY_LATER waits for a genuine
capacity/presentation revision; CAPABILITY_UNAVAILABLE is explicit unsupported state; SOURCE_INVALIDATED
requires a new qualified scope/session policy. Genuine terminal material height is an actual packet.
Unknown providers/program errors/dirty cleanup fail the route and request Session close.

Close first requests reservation cancellation and Session.closeAsync outside locks, then retires
untaken producers/holders off FX when they no longer have users. Session's actual terminatedAndDrained
receipt is the sole permission to release the application GPU arbiter. A failed/canceled future is
never proof of drain. Controller close also waits its CPU/payload callbacks and independent source
holder retirement. It never closes borrowed BuildFiles/BuildTables. Activity includes scope/source,
material, packet, upload/commit and accepted pixels, with bounded counts and honest partial/reason
metadata; it ends only at accepted presentation or explicit terminal outcome.

## Implementation/test stages

1. Implement bounded source demand/reservation/holder actor using actual closed bridge. Use a narrow
   parent adapter so authored scheduling tests can hold real source IO and completion independently.
2. After reviewed public Commit/Frame facade, add exact commit membership and single frame mailbox;
   root wires the existing view/application arbiter separately.
3. Causal tests hold source writer/lower cancellation, STAGED, COMMITTED, actual frame and FX upload
   independently. Prove superseded cancel-before-stale ordering, continuous camera progress, 12-owner
   union pressure, no height flash, untaken/MOVED cleanup, stale frames and hide/close drain.
4. Root integration then traverses real Map0 in the same child, measuring source/upload/presentation
   latency. No source-only test claims FPS or completed game-like smoothness.

## Frozen view-facing API shape

The concrete class is `WorldStreamSourceController` (package-private GUI). Root may wire this shape
while implementation remains isolated:

```java
record Request(long viewGeneration, BuildWorld world, WorldService.MapInfo map,
    WorldService.Inventory inventory, WorldFormats.Tile origin,
    WorldFormats.AreaPoint areaPoint, WorldCurrentArea.Index areaIndex) {}
interface GpuStarter { GpuOwner start(String qualifiedScopeSha256); }
interface GpuOwner {
    CompletableFuture<WorldStreamTransport.Session> session();
    CompletableFuture<Void> closeAsync();
}
interface Events { void changed(); }
// Controller owns worker and source acquisition; fxPost is Platform::runLater in production.
WorldStreamSourceController(Request request, GpuStarter gpu, Events events,
    Consumer<Runnable> fxPost);
void camera(WorldStreamTransport.Camera camera);
void neighborhood(List<WorldFormats.Tile> ordered); // unique, <=9
void pulse();
void software(boolean enabled);
Snapshot snapshot();
Optional<Presentation> takePresentation();
boolean mayPresent(Presentation frame);
boolean presented(Presentation frame); // only after actual successful pixel upload
void discard(Presentation frame);
CompletableFuture<Void> closeAsync();
```

`Snapshot` contains phase/detail, prepared/desired counts, nullable `Seed(camera,originX,originY,bounds)`
and `WorldCurrentArea.Result`. `Presentation` is opaque/private-constructed and exposes sequence,
cameraRevision, policy, width/height and readonly detached pixels, never source handles. Frames are
pulled from a coalesced mailbox on FX; no per-frame queued runnable captures payload owners. At most
one presentation is borrowed by FX; actor does not take another parent frame until ack/discard.

`Request.world` and Index are borrowed references; source creation/Scope qualification run only on
the controller worker. Initial selected tile fully qualifies before any seed is derived. Original
Inventory is bounded to 4096 comparisons and reused; the controller does not acquire a second
inventory or copy its tile/Map graph. A controller's small membership/descriptors/control ledger
is separate from the existing source receipt reserve. New view requests close the old controller;
no legacy LoadedTile acquisition pipeline runs in parallel.

GpuOwner.closeAsync returns the actual application request-owner drain (`CF<Void>`), including a
request canceled while queued before a Session exists. Session's actual transport drain remains
separate; no fake no-child DrainReceipt is constructed. An await-session future must fail when a
request ends without opening. Request completion/failure alone never releases that owner.

The pure area adapter is `WorldCurrentArea.resolve(mapId,recordedAreaId,Index)` from a512700. Controller
scans checked AreaChunk coverage inside off-FX payload borrows and passes only the actual recorded
scalar area ID to this helper; root minimap continues using Inventory, seeded origin and camera.
