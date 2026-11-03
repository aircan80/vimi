# Terrain texture source and shared-pool checkpoint

Status: proposed API/lifetime checkpoint, following approved formats
`ca99757`/`f3e1819` and pure compositor `e1a0fd2`. This document authorizes no UI,
renderer, child protocol, persisted cache or native UV mapping changes. The first
source delivery is a memory-only cache and a bounded batch producer. The existing
BASIC_COVERAGE_PREVIEW recipe and its normalized-local tint approximation remain
unchanged.

## Ownership and exact API direction

Add `TerrainAtlasPool` and `WorldTerrainTextureSource` to concepts-world. The pool
is created explicitly once by the application owner and shared by every
build-scoped texture source. There is no implicit pool-per-source constructor.
concepts-world adds an API dependency on rendering-world; rendering-world remains
independent of concepts-world, files and build services.

```java
public final class TerrainAtlasPool {
    public static final int MAX_ENTRIES = 18;
    public TerrainAtlasPool();
    public Stats stats(); // reserved/live entries, claims, borrows, cached entries
    public CompletableFuture<Void> closeAsync();

    public static final class Lease implements AutoCloseable {
        public Lease retain();
        public void withAtlas(java.util.function.Consumer<TerrainTileAtlas> action);
        public void close();
    }
}

public final class WorldTerrainTextureSource {
    public WorldTerrainTextureSource(BuildFiles files, TerrainAtlasPool pool);
    public CompletableFuture<BatchResult> read(
        WorldService.MapInfo map, List<WorldFormats.Tile> tiles,
        Cancellation token, java.util.function.Consumer<Progress> progress);
    public CompletableFuture<Void> closeAsync();
}

public final class BatchResult implements AutoCloseable {
    public List<TileResult> tiles(); // <=9 immutable metadata-only results
    public java.util.Optional<TerrainAtlasPool.Lease> retainAtlas(int x, int y);
    public void close();
}
```

Nested DTO placement may use `WorldTerrainTextureSource.BatchResult`,
`TileResult`, `Progress` and `Receipt`; these are the concrete names consumers
should target. `TileResult` is metadata only: tile, state, optional receipt,
supported/total counts and bounded typed reasons. States are READY, PARTIAL,
UNAVAILABLE and POOL_BUSY. READY means 256/256 supported chunks; PARTIAL has an
atlas and 1..255 supported chunks; a zero-coverage composition is UNAVAILABLE
and its reserved output is released. A result cannot claim an atlas without a
receipt. Missing/protected/malformed/unsupported sources remain distinct typed
reason codes, never empty successful coverage. No texture failure changes
terrain geometry or its loading result.

A read accepts 1..9 unique filename coordinates in one selected map. Validate
nulls/counts before copying any public list, then snapshot the bounded list.
One result owns at most nine lease claims. The caller must close BatchResult;
consumers needing a longer-lived atlas explicitly retain a lease, then close
the result. A GUI may retain one batch as its current appearance and a second
as pending, but that does not create additional pixel allocations. The source
will not issue nine independently supersedable public futures and require the
UI to reconstruct a coherent batch.

`retainAtlas` throws after result closure. Every closed result clears its owned
lease array; every closed lease clears its entry reference. A completed future
may continue to retain the closed result, but that object graph retains only
bounded metadata and closed handles, never atlas pixels. Sources and scheduler
state do not keep completed request/future histories.

## Pool slots and scoped immutable borrows

The pool owns exactly 18 entry/reservation slots across cache, active work,
current and pending consumers, including all builds. A slot represents one
unique immutable atlas or one reserved future output. Cache and external claims
share that entry; they are not separate atlas allocations. Reserve a slot
**before calling compose or allocating its 1 MiB output**. An uncached output
cannot exist outside a reservation. A canceled/failed/zero-coverage composition
drops its output and releases the reservation in a finally path.

The global LRU cache holds claims on complete READY entries only. Before a new
reservation, evict cache-only entries in LRU order. An entry with an external
lease or active borrow cannot be freed merely by evicting its cache claim. If no
slot can be released, return POOL_BUSY for the affected tile without allocating
an output or waiting on the FX thread. Initial PARTIAL results are deliberately
not cached: later requests recheck missing sources and may improve coverage.
UNAVAILABLE/POOL_BUSY also have no retained pixel/cache entry. Complete cache
hits are still source-revalidated; cache presence is not source authority.

`withAtlas` acquires its own entry/slot pin under the pool lock before calling
user code, then releases that pin in finally. The callback runs outside the lock.
`Lease.close` is idempotent, nonblocking and only drops that handle's claim: it
cannot free/reuse the slot while a callback, writer or another retained lease
still pins the entry. Likewise closing BatchResult during a borrow cannot
release the underlying slot early. A thrown callback releases its borrow pin.
`retain` on a closed handle or closing pool fails without creating a claim.

The callback borrows the immutable atlas and its read-only buffers synchronously.
It must not retain the atlas, mappings containing a payload reference, or pixel
views after callback return. Java heap ByteBuffers cannot be revoked; this is an
explicit caller lifetime rule, not a claim that read-only means independently
owned. Consumers scheduling asynchronous work retain a Lease first, call
`withAtlas` inside that task, and close it after task completion. No public
`Lease.atlas()` returns a detached payload. A future streaming annex writer will
hold a borrow throughout its reads; a future CPU snapshot must keep lease claims
throughout rendering. Copying pixels creates a new separately budgeted owner
and is not permitted as an unaccounted convenience in this component.

Pool close rejects new sources/reservations/retains, cancels and drains its work,
evicts all cache claims and waits asynchronously for existing claims/borrow pins
to drain. It never blocks FX and never forcibly reuses a still-borrowed slot.
Existing in-flight synchronous borrows may finish. An application must first
close/drain its consumers/build sources and then close the shared pool; leaked
caller leases remain visible in stats rather than being silently reclaimed.

## Scheduling, cancellation and completion

The shared pool owns one global preparation worker and one latest pending batch,
not one worker per build. Thus two build-scoped source instances cannot create
two overlapping 8 MiB source preparations outside the shared ledger. A new batch
replaces and cancels the prior pending batch. It requests cancellation of active
work; the active worker drains all owned streams, temporary buffers, unpublished
results and cancellation callbacks before starting the newest pending batch.
Progress is a bounded stage enum plus tile/chunk/diffuse counters; it has no
unbounded history or per-pixel callback. Consumers must keep progress/completion
callbacks short and marshal UI updates; the helper never invokes FX APIs.

Caller token callbacks and returned-future cancellation only mark the request and
signal a dedicated bounded cancellation dispatcher. They do not synchronously
call source token cancellation/stream close on the caller/FX thread. The
cancellation target retains its own request identity. It cannot be replaced by
a newer target until its owned cancellation callbacks have drained. The active
worker checks caller/internal cancellation between source operations, mask scan
blocks and composition rows. No canceled batch is published as UNAVAILABLE.

The returned CompletableFuture is the delivery future; there is no hidden copy
whose completed successful result can retain an unclaimed lease. If cancellation
wins, late completion fails and immediately closes the undelivered BatchResult.
If successful delivery wins, the caller owns that result and cancellation after
completion does not implicitly destroy it. Caller-token registration is removed
at retirement. Every exception/supersession path closes all not-yet-transferred
leases and reservations. The helper does not need to overwrite a delivered
current batch merely because newer work is pending.

`source.closeAsync()` rejects new work and cancels/drains only that source's
active/pending requests and unpublished claims. The global READY cache belongs
to the pool, retains only bounded metadata/BuildRef values and atlas entries, and
never retains BuildFiles, a source instance or a growing source-owner set. It may
remain until LRU eviction/pool closure; closing either of two same-key sources
does not invalidate the other's delivered leases or the shared cache. The source
neither closes borrowed files
nor closes the shared pool or unrelated sources' requests. Already delivered
leases are caller-owned and remain valid until closed. Shared pool close is a
separate application-lifetime operation.

## Positive source admission and receipts

The first source profile requires a positive selected WDT FileDataID and numeric
recorded root/tex0 IDs; no path arithmetic or `root+3` fallback. All source reads
use borrowed BuildFiles and a private per-request token:

1. Stat/open the WDT (at most 1 MiB), require COMPLETE/no holes, exact declared
   stream length/progress and a canonical nonempty content key. Parse the strict
   `WorldTextureFormats.inventory`: exact WDT flags `0x3ca` and full recorded
   MAID. Each requested tile must equal its recorded inventory tuple, including
   filename coordinates and root/tex0. An arbitrary Tile DTO cannot certify this.
2. Stat/open each root (at most 2 MiB **for this optional texture profile only**),
   parse `adtGrid` through the shared checked ADT18 walker. Require all 256 chunks
   and the initial canonical source order `ordinal == headerY*16+headerX`.
   Preserve all original float anchors. This narrower appearance profile does
   not change existing geometry reader limits/admission or invent axis welding.
3. Read checked tex0 (at most 8 MiB) into one input, construct its single owned
   copy, then release the input before subsequent work. Admit only the reviewed
   split MVER18/MAMP0/MDID+zero-MHID/256 MCNK profile. Preserve local typed
   unsupported chunks; do not promote raw source constructors into authority.
4. Fetch only positive MDID entries referenced by structurally admissible chunk
   layers, at most 64 unique entries. Unused palette entries require no IO and
   cannot poison coverage/cache admission. The full ordered palette remains bound
   by the tex0 CK; per-diffuse receipts cover only consumed bindings. For each
   consumed entry, stat/open a COMPLETE,
   unprotected BLP of at most 1 MiB. Use the approved public decoder with explicit
   DECLARED_HANDMADE_V1 opt-in, its unchanged strict/default checks and selected
   working limits for a **declared square 4×4 mip**. No resizing, header mutation
   or synthetic mip. Retain only immutable 64-byte RGBA values and descriptive
   profile/header facts, not streams/full BLP images. Missing/unsupported diffuse
   produces explicit partial chunk coverage through the pure compositor.
5. Before cache/result publication, re-stat WDT, root, tex0 and every consumed
   diffuse source, comparing selected build/locale, numeric ID, normalized CK,
   size and required source state. A changed binding retires that tile's output;
   cancellation propagates. Recheck the batch's WDT/positive source receipts
   after the last tile so earlier tiles cannot silently outlive changed sources.
   Missing/unsupported coverage is not claimed stable merely because no CK was
   available. No partial result is cached in this first stage.

A complete cache entry is indexed by build/locale, map/WDT ID+CK+flags, the full
tile tuple, root/tex0 CKs, ordered MDID IDs+CKs, declared mip facts/profiles and
all reader/decoder/recipe revisions. It retains only its bounded receipt and
atlas, not raw grid/texture/WDT owners or completed futures. On a prospective
cache hit, revalidate its full source bindings. Previously checked immutable CK
metadata may be reused only under that identical receipt; mutable availability
or a merely matching filename cannot qualify a hit.

The receipt constructor is private to the source implementation. Public getters
are descriptive immutable data, not a caller-mintable positive capability.
Keep source metadata bounded to at most nine tiles × 64 diffuse bindings plus
bounded local reasons. Memory-only cache means no new GeneratedStore codec,
namespace, cross-version persistence or corruption-repair semantics in this stage.

## Binding texture to the actual geometry

A root CK alone cannot prove that an arbitrary active TerrainScene contains that
root's geometry. Add a small pure `TerrainGeometryFingerprint` helper in
rendering-world, with these overloads:

```java
static String sha256(int tileX, int tileY, TerrainGrid grid);
static String sha256(TerrainScene.Tile tile);
```

The grid overload requires the full canonical initial profile and streams the
same checked native float positions and hole-dependent indices emitted by the
existing ADT reader; it does not create a Terrain, mesh arrays or boxed vertices.
The Tile overload streams its read-only native coordinate/index buffers without
cloning. Both hash one pinned little-endian representation: ASCII domain
`VIMI_TERRAIN_GEOMETRY_V1\0`, int32 filename X/Y, chunks, vertex count and index
count, followed by float32 raw native position bits in source order, then uint32
indices in the existing chunk/row/column fan order `a,m,b,b,m,e,e,m,d,d,m,a`.
Finite coordinates make raw-bit hashing deterministic; preserve signed zero.
Use a fixed small scratch buffer or direct scalar digest updates.

The private source receipt binds root ID/CK + grid revision + this geometry
SHA-256. Later appearance attachment must recompute the active Scene.Tile digest
and require exact filename coordinates/counts/hash before associating an atlas.
The later annex independently binds the **whole serialized geometry scene**
SHA-256 and its per-tile source/recipe tuples. A correct source atlas therefore
cannot be silently attached to wrong arrays at the same filename coordinates.
LOD/noncanonical geometry intentionally fails this initial fingerprint contract
until a new reviewed mapping is introduced. No LoadedTile/public old source
receipt or legacy geometry wire change is needed in this stage.

## Conservative component memory ledger

Keep the proposed 48 MiB component envelope, now explicitly including output
metadata and conservative phase overlap. The single global preparation worker
is essential. Shared application/file-store caches, driver allocations and the
existing geometry owner remain separately budgeted; no new duplicate cache is
introduced here.

| Simultaneously charged upper allowance | Bytes |
| --- | ---: |
| 18 atlas/reservation slots × 1,065,248 retained bytes | 19,174,464 |
| tex0 caller input + single raw-owner copy, 2 × 8 MiB | 16,777,216 |
| grid/WDT/texture/receipt/result/mip metadata envelope | 2,097,152 |
| one maximum seven-map mask scratch | 28,672 |
| selected diffuse pixel values + fixed pixel construction allowance | 8,192 |
| bounded BLP range/read working allowance | 1,048,576 |
| root input (even conservatively charged after parsing) | 2,097,152 |
| WDT input (even conservatively charged after parsing) | 1,048,576 |
| **Conservative total** | **42,280,000** |
| 48 MiB limit | 50,331,648 |

Actual metadata sizes must be checked in code/tests, not inferred from a raw DTO's
1 MiB maximum alone. The 2 MiB metadata envelope includes up to 4096 WDT tile
records, one 331,776-byte color-bearing grid, the maximum eight-layer texture
metadata (~170 KiB), nine bounded output receipts/reasons and construction lists.
Atlas mapping metadata is already included in the pool-slot estimate; avoid
charging the same backing atlas twice but retain a conservative margin. No full
256×7×4096 mask allocation or clone-per-view is allowed. The source implementation
must account for actual public accessor copies before accepting this ledger.

## Required authored and private acceptance

- Pool reserves before compositor allocation; at most 18 live/reserved payloads
  across two source instances, current/cache/pending claims and eviction. A 19th
  allocation is refused. Shared claims do not consume another payload slot.
- Block inside `withAtlas`, close its handle/result/cache on another thread and
  attempt new reservations. Its slot stays pinned until callback finally; a
  throwing callback also releases correctly. Closed futures/results/leases do
  not retain payload references. No detached pixel view survives its borrow.
- Delayed source open/read/cancel/close fixtures prove one global active/latest
  scheduler, off-caller cancellation, latest supersession and drain before the
  next source allocation; source.close does not cancel another source/pool.
- Positive exact WDT tuple/current receipts, wrong WDT flags or root source order,
  missing/protected/changing IDs/CKs/sizes, no declared 4² mip, decoder profile,
  missing diffuse and local mask/MCCV failures. Partial results are honest and
  retried on the next read, not negative-cached forever.
- Geometry fingerprints agree with unchanged existing reader and Scene.Tile,
  including asymmetric tile coordinates, nonzero floats/signed zero, holes and
  source index order. Altered vertex/index/tile/source order rejects attachment.
- Quota, cancellation before/inside composition/publication, failed future
  delivery and source/pool closure release every unpublished slot/stream/claim.
  Metadata and largest-eight-layer source cases exercise the actual ledger.
- Private three-source public-helper cold/warm reads produce byte-identical
  atlases/receipts with exact 256/256 coverage and close all leases. Observe pool
  stats before/after replacement/close; no original library writes. GUI/native
  streaming snapshots, UV seams and texture annex tests remain later gates.
