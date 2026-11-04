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
  and selects only a caller ordinal from those recorded MODF entries. Its private
  CheckedPlacement binds that obj0 receipt/parser revision/ordinal, exact recorded
  reference, native transform/bounds/flags/doodad/name sets. Unknown name-reference
  profiles reject; no hardcoded positive FileDataIDs or imported global transform.
  Root WMO→GFID/group→images are derived only from those actual parsed parents and
  checked edges, then read/qualified by the WMO source factory.

UseProfile pins different budgets: TERRAIN_BASIC admits the existing≤67 consumed
WDT/root/tex0/diffuse bindings; BASIC_WMO admits≤11 total bindings (WDT, root ADT,
obj0, WMO root, one group and six images). BASIC_WMO's reviewed initial derived
limits are root1KiB/group32KiB/image64KiB, with obj0≤8MiB and root-ADT construction
separately named. A profile cannot borrow another's binding/input allowance.
Overflow/missing profile is unsupported/backpressure, never a fabricated positive
or height fallback. Exact child format/material profiles remain owned by the WMO
agent's separately reviewed parser/source proposal.

## Scene-qualified composition

Cold same-child terrain+WMO annex requires a private WorldSourceFrame owner/token
created INSIDE this same operation from actual root ADT read/cache evidence. It
binds scope owner/generation+digest, current admitted tuple, root ID/CKey/consumed
length and native geometry fingerprint, and owns/pins that actual TerrainScene.
Public caller TerrainScene or detached metadata cannot mint a positive frame.

One callback may construct this qualified frame plus its actual obj0 placement,
WMO/groups/images and return one composite owned output. The WMO factory's private
constructor must require that exact frame identity; it cannot combine an old
terrain frame with a new obj0/root receipt. Cache reuse is a separate approval;
initial bounded actual read/parse may create the frame without a second geometry
cache. Output retains only its own geometry/material owners and small source
stamps, never the live admission or heavy scope proof.

ADT MODF placement and global WDT placement are distinct typed origins. Global
WMO needs a FUTURE withGlobalDeclaration operation from a checked WDT scene-facts
variant, not withRecordedTile(Scope, Tile(0,0),...). This document does not invent
missing tiles or generalize terrain packets/factories for global scenes.

## Final checks, expiry and close

The callback returns an engine-owned, nonnull AutoCloseable result. It may not
advertise a packet, publish a native child or transfer source ownership externally
before withRecordedTile succeeds. On every thrown/canceled/stale path, close that
result plus all outstanding input/lease owners exactly once outside locks.

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
until real source work drains. An escaped admission cannot access IO after expiry.

Hold after successful material/group/image batch delivery and mutate each consumed
binding before transfer: SOURCE_CHANGED, no stale positive or height fallback.
Mutate Map/WDT/generation during callback; final fresh stamp/EOF must reject.
Default Unsupported/unknown/LIMIT EOF cannot reuse authority. Root/area metadata
and frame identity remain tied to actual checked parse, including equal-geometry
but changed AreaID cases. All result/input/atlas/cache pins drain under callback
failure, rejected delivery and close. No renderer/packet/GUI wiring is approved
merely by passing these private source tests.
