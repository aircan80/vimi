# Scoped source admission for terrain and BASIC_WMO

Status: exact API design checkpoint only. No callback/factory/cache implementation
is authorized by this document. Reuses independently approved WorldStreamScopes
and Map-stamp/strict-EOF validation; the public recorded selector remains
comparison-only. Terrain and WMO outputs keep separate private source factories.

## Package-internal API

```java
enum UseProfile { TERRAIN_BASIC, BASIC_WMO } // fixed reviewed bounds, no numeric override
@FunctionalInterface
interface TileSourceUse<R extends AutoCloseable> {
    R prepare(TileAdmission admission);
}
<R extends AutoCloseable> R withRecordedTile(
    Scope scope, WorldFormats.Tile selector, UseProfile profile,
    Cancellation external, TileSourceUse<R> use);
```

TileAdmission is a static final package-internal type with a PRIVATE constructor
inside WorldStreamScopes. It is created only after resolving the private current/
replacement registry, fresh native Map qualification+canonical stamp+bounded EOF,
checked WDT identity and exact comparison of all eight recorded MAID references.
Its recorded tuple is copied FROM that registry, not adopted from the selector.
It binds owner/generation, scope digest, build/locale/map and actual WDT numeric
FileDataID/CKey/consumed length. Scope proof must retain the actual WDT read length
as a bounded scalar; optional stat.size never invents that value.

Package-internal accessors provide recorded metadata, the scoped cancellation
and a ScopedReads facade only while admission is active. Metadata exported into
an output descriptor is descriptive, not a replayable admission. No public
constructor, public LoadedTile/TerrainScene, placement receipt or parser DTO can
mint one. Existing recorded() can remain a metadata-only convenience; neither
factory may authorize work by calling it and later using its returned DTO outside
the guarded source operation.

The one-active CAS stays held across PREVALIDATE → SOURCE CALLBACK → FINAL CHECKS
→ TRANSFER/REJECT → EXPIRE/DRAIN. A new request cannot enter between geometry,
placements, material reads and final validation. Callbacks/source IO execute on
an owned source worker, outside every engine/cache/lifecycle monitor. The future
async adapter queues one latest scope/tile/profile/cancel descriptor only; it
cannot queue TileAdmission, prepared arrays, scopes' heavy MAID records or leases.

## Actual reads and descendant evidence

ScopedReads performs bounded complete/no-hole actual reads through the borrowed
build/locale BuildFiles, with requested/resolved numeric ID, CKey and actual
consumed length checks. Storage/path/format/unknown-length enrichment is allowed;
identity/known consumed length mismatch is SOURCE_CHANGED. Returned input owners
must close/clear bytes and streams before callback completion. The facade tracks
a bounded detached binding set, not input storage. It cannot silently acquire or
read arbitrary paths guessed from map/display strings.

An actual read receipt proves consumed bytes, not a parent→child asset relation.
The source factory must establish that relation by parsing the actual parent
inside this operation and constructing its own PRIVATE checked edge/placement
value. No helper promotes a public WorldObject/Receipt/Reference DTO into evidence.
Reading an unrelated caller-specified numeric ID does not qualify a renderable.

- Terrain factory selects root/tex0 only from the admitted tuple, uses actual
  checked geometry and its actual owned WorldTerrainTextureSource BatchResult.
  Positive receipt construction remains private; terminal material fallback still
  requires genuine checked geometry and an actual terminal source outcome.
- BASIC_WMO first reads the admitted obj0 reference, parses its actual bytes,
  and selects a caller ordinal plus expected unsigned uniqueID from those recorded
  MODF entries. Actual parsing verifies ordinal/UID agreement and rejects duplicate
  or conflicting UIDs; reordering yields STALE_SELECTION. Neither selector supplies
  a positive root ID or transform. Its private
  CheckedPlacement binds that obj0 receipt/parser revision/ordinal, exact recorded
  reference, native transform/bounds/flags/doodad/name sets. Unknown name-reference
  profiles reject; no hardcoded positive FileDataIDs or imported global transform.
  Root WMO→GFID/group→images are derived only from those actual parsed parents and
  checked edges, then read/qualified by the WMO source factory.

UseProfile pins different budgets: TERRAIN_BASIC admits the existing≤67 consumed
WDT/root/tex0/diffuse bindings; BASIC_WMO admits≤11 total bindings (WDT, root ADT,
obj0, WMO root, one group and six images). BASIC_WMO's reviewed initial derived
limits are root1KiB/group32KiB/image64KiB, with obj0≤8MiB and root-ADT construction
separately named. Both profiles enforce maxOpenInputs=1 and maxLiveInputBytes=8,388,608.
A raw-input reservation is acquired BEFORE opening a source or allocating its byte
storage, checked against the smaller per-asset limit, and held until the owner
closes and clears its byte/stream references. Another read while that owner is live
fails with backpressure; close racing a read retains the reservation until actual
read/callback drain. Tracked detached binding metadata consumes no input credit.
WMO reads are therefore serial, including root ADT then obj0 then derived assets.
Terrain's separately owned material helper must run after facade input drain and
uses its separately reviewed source/atlas allowance; it cannot overlap a live
facade input. The 8MiB raw phase is charged once within the reviewed 16MiB terrain
construction or WMO source phase, not once per binding. Decoded geometry/images
and parser scratch remain explicit charges in their factory ledgers. No claim
that a binding-count cap alone bounds live bytes is permitted.
A profile cannot borrow another's binding/input allowance.
Overflow/missing profile is unsupported/backpressure, never a fabricated positive
or height fallback. Exact child format/material profiles remain owned by the WMO
agent's separately reviewed parser/source proposal.

## Scene-qualified composition

Cold same-child terrain+WMO annex must preserve the existing3×3/nine-tile scene.
Inside the same admission, read/check the selected root ADT (or later reuse an
approved private cache proof), compute its exact native geometry fingerprint,
and create PRIVATE CheckedRootFrame metadata: scope owner/generation+digest,
current admitted tuple, root ID/CKey/actual consumed length and geometry SHA.
Then retire the temporary parse/extra TerrainScene buffers before WMO group/image
preparation. The token retains fixed metadata only; it is not an owned full-nine-
tile frame or a reason to shrink the viewer to one tile.

The WMO asset qualifies independently through its actual obj0→MODF→root/group/image
chain. Its private constructor requires the checked placement/root-frame evidence.
An adapter may then compare this ALREADY-qualified asset with exactly one matching
filename/fingerprint tile of the existing public≤9-tile TerrainScene. Comparison
cannot mint asset positivity from that public scene. The other eight tiles retain
their existing terrain contract; the source factory makes no new provenance claim
about them. Wrong/missing/duplicate selected match rejects binding.

The annex binds actual serialized whole-scene file SHA plus selected tile ordinal/
fingerprint and private map scope; the codec checks that file identity against the
actual bytes/scene used to write it. Public caller TerrainScene, caller file hash or
metadata alone cannot mint CheckedRootFrame or a positive WMO asset. The source-
qualified asset may be composed/written to UNADVERTISED scratch within the guarded
callback, allowing the engine's final all-binding checks to follow any composition
or write delay. Only after successful admission transfer may the parent advertise
the annex/open a native child. On failure the owned result removes scratch and
closes geometry/image owners.

No retained full scene is added behind the source token, no second geometry cache
is introduced and no view-distance reduction is part of this contract. Cache
reuse remains separately reviewed. Output retains only its own asset owners and
small checked root/scope stamps, never live admission/heavy scope proof.

ADT MODF placement and global WDT placement are distinct typed origins. Global
WMO needs a FUTURE withGlobalDeclaration operation from a checked WDT scene-facts
variant, not withRecordedTile(Scope, Tile(0,0),...). This document does not invent
missing tiles or generalize terrain packets/factories for global scenes.

## Final checks, expiry and close

The callback owns every locally constructed output and scratch resource until
it successfully returns a nonnull AutoCloseable result. It MUST use try/finally to
close unreturned outputs, including an output constructed immediately before an
exception: the engine cannot discover a result which was never returned. A local
handoff flag may relinquish this duty only on successful return. The engine then
owns the returned result through final validation and transfer. It may not
advertise a packet, publish a native child or transfer source ownership externally
before withRecordedTile succeeds. On every canceled/stale/final-validation failure
after return, the engine closes that returned result. Callback finally closes
unreturned results; the engine closes all outstanding input/lease owners on every
exit, exactly once outside locks.

Before transfer, while still one-active:

1. Check cancellation and close/generation state; finish/drain input callbacks.
2. Revalidate ALL actual consumed tracked bindings, including tex0/diffuse or WMO
   group/images, against the same numeric identity/CKey and any known length.
   The actual helper's earlier pre-delivery validation is insufficient if a source
   changes while qualification or composition is held.
3. Open a fresh qualified native Map capture, compare the complete stamp and pass
   its bounded EOF capability probe; fresh-check WDT CKey/consumed length.
4. Resolve the same registry owner/generation and exact tuple under a short
   metadata lock, then linearize result transfer. A known changed binding reports
   SOURCE_CHANGED, never terminal height fallback. There is no automatic retry
   loop that silently repairs authority during this operation.

Finally expire admission and clear its live IO facade/controller/cancellation/
registry links before releasing the active guard. An escaped admission then
contains only bounded detached metadata and rejects further scoped operations;
it cannot retain BuildFiles/services/heavy MAID or authorize another callback.
Read tracker/mailboxes/futures retain no retired input arrays or live leases.

Output factories retain a separate small lifecycle-validity gate (no IO/service
closure) and register owners in their bounded output registry. Source close
invalidates new admissions/output access immediately, dispatches cancellation
once outside monitors and completes asynchronously only after active callback/
input/result owners drain. A result racing close may already be closed; its
private payload/advertisement access must reject that state. Existing payload
callback pins survive close until finally; no early free/CANCEL_UNUSED/credit.
Scope close and factory output-registry drain are joined by the owning source
service. The engine never calls user callbacks, output.close or lower cancellation
while holding its lifecycle monitor.

## Required authored gates

Use genuine native Map capture and actual authored WDT/root/obj0/WMO/BLP reads.
Prove wrong/expired/foreign scope and all-eight tuple mismatch reject before child
reads; public DTOs cannot create admissions/frames/placements. Hold the callback,
reject a second admission, then cancel/close and confirm the guard/claims persist
until real source work drains. Attempt a second read with the first input held:
reject before source open/allocation; release it and prove the credit is reusable.
Allocate a real owned output/scratch file then throw BEFORE returning it: callback
finally must remove it and release all claims; the next admission succeeds.
An escaped admission cannot access IO after expiry.

Hold after successful material/group/image batch delivery and mutate each consumed
binding before transfer: SOURCE_CHANGED, no stale positive or height fallback.
Mutate Map/WDT/generation during callback; final fresh stamp/EOF must reject.
Default Unsupported/unknown/LIMIT EOF cannot reuse authority. Root/area metadata
and frame identity remain tied to actual checked parse, including equal-geometry
but changed AreaID cases. All result/input/atlas/cache pins drain under callback
failure, rejected delivery and close. No renderer/packet/GUI wiring is approved
merely by passing these private source tests.
