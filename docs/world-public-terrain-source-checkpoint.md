# Opaque public terrain source checkpoint

Concepts-only implementation of the reviewed public-source portion of30dca4b/
2f931d6, atop the independently reviewed internal factory/close/scope fixes through
c62e520. No bgfx writer, transport packet or GUI route is wired here.

BuildWorld.terrainStreaming defaults to Optional.empty with no fallback calls.
WorldService returns a stable private-constructed WorldTerrainStreaming only when
its configured files and application atlas pool exist. Facade lookup does no asset
stat/open/capture. Actual createScope/prepareAndWrite and resource cleanup are
synchronous off-FX operations. The service owns/invalidate-closes the backend;
closing the service also clears the facade's backend reference.

Scope has a private constructor and only a detached engine token; it owns no source
or MAID arrays. WrittenTile, CheckedBorrow, Material and Facts also have private
constructors. Public tuples/mip/bounds are descriptive values and cannot mint one
of those handles. A generic Output/R success is not packet-source qualification.
The future fixed GUI bridge must still provide its own closed writer callback and
require successful final source transfer before minting its private holder.

Facts is detached scalar metadata: bounded build/locale/scope/root identity, map
row authority, native geometry SHA, six scalar bounds, outcome/material state and
support counts. It retains no area graph, geometry, pixels, diffuse list or complete
Receipt. Bounds are computed within the actual checked geometry callback; geometry
SHA comes from a snapshot of the private cache proof. Existing entered callbacks
use these snapshots, without midcallback cache/source lookups. New borrows and
resource callbacks use the known-current-or-replacement scope gate. Historical
Facts alone do not authorize a current frame or handoff.

Material is an expiring view that can supply at most64 detached descriptive mip
records; it never returns a complete Receipt or backing receipt list. Every method
requires the same live checked callback. Expiration clears its parent and the
CheckedBorrow backend/material references. Its compositionCacheSha256 is genuinely
nullable for partial material: no successful cache entry or surrogate cache key is
invented. The later packet materialDigest is a separately namespaced structural
identity, as specified by the separate wire amendment, not this nullable cache key.
Geometry/pixels/area graphs remain synchronous borrowed views and must not escape.

withWrittenResource independently pins the singular owner, invokes its consumer
outside metadata monitors and waits its actual finally before source-owned R
cleanup. No raw R getter is public. The callback only performs a one-shot ownership
move, off FX; R must not escape, while a separately owned moved resource may.
An accepted callback may finish through source close; source close before callback
acceptance rejects. The future fixed bridge must handle late stopping and clean a
MOVED-but-unreturned transport packet independently of the now-inert source R.

## Concrete unchanged receipt allowance

The existing helper reserves3MiB of metadata. Its previously named subtotal is
2,611,200 bytes, including18 receipts at626,688 bytes. Each immutable receipt has
at most64 mip records, with a conservative charge2,048+512×count, at most34,816
bytes. Actual build strings are already source-profiled to32 chars (locale16),
CKs are exactly32 hex chars, geometry SHA is64, optional cache SHA is null or64,
and mip encoding is one of five fixed names bounded to16. File/mip numeric fields
have their existing constructor/source limits. The combined factory now explicitly
profiles these actual receipt shapes before attaching one to an output.

Conservatively charge all12 output receipts as distinct, including a binding-
rejected height receipt whose pool atlas may later evict, even when actual output
owners share a pool receipt:

| Named metadata | Bytes |
| --- | ---: |
| Existing named subtotal including18 pool receipts | 2,611,200 |
| Twelve extra output receipts:12×34,816 | 417,792 |
| Revised named subtotal under existing3MiB | 3,028,992 |
| Remaining unchanged metadata headroom | 116,736 |

There is no metadata cap increase. Output/construction count12 and per-receipt
shape enforce the bound; a metadata-only diagnostic sums the conservative output
charges under owner locks and asserts at most417,792. It continues charging held
callbacks until real owner retirement. Facts/borrow/resource controls remain inside
the separately reviewed64KiB factory-control allowance; no new payload arrays or
RGBA copy exist. Prepared tables/definitions, geometry/cache/areas, source input
phases, native rendering and caller-created arbitrary histories retain their
separate ledgers. This is a logical component allowance, not measured JVM RSS.

Authored full checks exercise actual native Map→WDT/root→tex0/64diffuse factory
flows, twelve issued owners/max receipt charge, scalar-only Facts/private handles,
expired material/payload views, unsupported custom/no-pool capability without IO,
malformed coordinates, partial nullable cache state and a real temporary file MOVE
inside a held resource callback. Source close waits that callback; the moved file
survives source R closure until its independent owner closes. No GPU/child process
or user assets are used by these authored tests.

Known internal source exceptions still need a separate precise concepts-origin
refusal mapping checkpoint before fixed bridge integration: actual output/atlas
pressure is retryable; unsupported configured capability and known source/proof
change are distinct; cancellation remains separate. Never parse messages or infer a
refusal solely from isKnown after an unknown error. Unknown provider/program/cleanup
errors remain fatal even if they invalidate the gate. The approved transport uses
WorldStreamTransport.StopRegistration for reservation cancellation AND Session stop;
its concrete Destination/OwnedPacket writer boundary remains another checkpoint.
