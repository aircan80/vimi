# Recorded terrain texture source checkpoint

This implements the reviewed memory-only source/batch part of `6c95750` and
`ae49e6b`, after pool corrections `a6d6e62`/`59d7a8a`. Source production approval
is pending. No GUI rendering, native scene, annex or persistent cache changes
are included.

## API and lifetime

`WorldTerrainTextureSource(BuildFiles,TerrainAtlasPool)` borrows both arguments.
`read(map,tiles,token,progress)` accepts 1..9 unique recorded tiles and returns
one owned `BatchResult`. The caller closes the result; `retainAtlas(x,y)` returns
an optional explicitly owned pool lease. A closed result clears its lease array,
and a closed lease clears its entry reference. Read-only atlas/pixel borrows must
remain inside `Lease.withAtlas`; scheduled work retains a lease first. Delivered
leases survive source closure and share storage with the global READY cache.

The pool now owns one lazy global preparation worker and cancellation dispatcher
across all source instances. There is one active batch and one latest pending
descriptor. Supersession/caller cancellation only marks requests and signals the
dispatcher; lower token/stream cancellation callbacks run off the caller thread.
The active worker drains its callbacks/streams/unpublished leases before another
source becomes active. An already canceled incoming request cannot retire an
uncanceled active batch. A failed delivery closes the undelivered result.
Canceled work is never converted to an unavailable success.

Source close cancels/drains only its own active/pending requests. The pool-owned
READY cache contains only bounded receipts/BuildRef data and atlas entries, with
no BuildFiles, source-instance or growing owner-set reference. A new helper for
the same scope can share complete cached entries after current revalidation.
Partial and zero-coverage derivatives are not cached. Pool close additionally
cancels/drains its global worker, drops cache claims and asynchronously waits for
external leases/borrow pins. Cancellation callback failures are reported after
owned work drains. Future/progress consumers must remain short as specified in
the proposal; the helper performs no FX operations.

## Positive admission and dependency binding

The helper reads/checks the complete WDT at most 1 MiB, exact flags `0x3ca` and
recorded full MAID tuple, then source root at most 2 MiB and tex0 at most 8 MiB.
All source stats require exact numeric ID/nonempty canonical CK/current scope;
opened streams require COMPLETE/no holes, bounded length and advancing reads.
Re-stat checks bind CK/declared size before derivative publication. The optional
root profile requires all 256 source ordinals in canonical header order; existing
geometry admission and limits are unchanged.

Only positive MDID IDs referenced by structurally admissible chunk layers are
fetched. The full palette remains bound by tex0 CK; unused entries do not incur
IO or poison coverage. Each consumed BLP is at most 1 MiB and uses the approved
public decoder's explicit DECLARED_HANDMADE_V1 opt-in and declared square 4²
mip, with selected-pixel/source/chain bounds unchanged. Retain only 64-byte mip
pixels and bounded descriptive facts. There is no synthesized mip or source
header mutation. Missing/protected/unsupported diffuse yields honest local
partial coverage; missing layers are never silently renormalized.

Private-constructed positive receipts bind build/locale/map, full tile tuple,
WDT/root/tex0 CKs and lengths, consumed mip facts, geometry fingerprint, cache
key and pinned source/reader/decoder/recipe versions. Public raw metadata DTOs
are not source capabilities. Cache keys bind the full source scope and consumed
CKs; same-CK prior declared-mip facts are reused only from positively qualified
complete entries. Early tiles are revalidated again after the entire batch.
Changed bindings release their result claims and become typed unavailable.
Local diagnostics retain composer reason/mask issue and source ordinal.

The source geometry fingerprint streams original native floats/hole indices and
matches the active Scene.Tile contract. Root CK alone is not treated as proof
of arbitrary active mesh arrays. UI/native attachment must independently match
the per-tile geometry hash and later whole-geometry annex hash.

## Metadata and copy ledger clarification

The approved 48 MiB component ceiling remains unchanged. Its conservative
metadata allowance is **3 MiB**, replacing the proposal's provisional 2 MiB:
the global cache can retain up to 18 positive receipt objects, in addition to
atlas mapping metadata already charged in each pool slot. Root accepted this
clarification; it does not raise source, pool-entry or pixel limits.

The original conservative total 42,280,000 bytes therefore becomes
**43,328,576 bytes**, below 50,331,648 (48 MiB). Existing rows still charge all
18 slots × 1,065,248 bytes, 16 MiB tex0 input/owner overlap, the 28,672-byte
single-chunk mask scratch, source input allowances, range/decode working set and
fixed selected-pixel construction copies. Full BLP files are not copied by the
helper; the checked stream adapter reads decoder metadata/selected payload.
`Image.rgba()` and the immutable mip constructor each copy only 64 bytes.

Logical bounded metadata accounting includes:

- At most 18 receipts, each estimated as 2,048 + 512×consumed-mip-count bytes;
  maximum 626,688 bytes. Pool stats expose this separately from atlas bytes.
- At most 18 current/pending positive outcomes with 320 bounded issue records
  each; 64 bytes/issue is a conservative 368,640-byte allowance. Complete cache
  entries have no duplicated diagnostic history. Result/cache receipt references
  share the same object; they are not cloned per view.
- Up to 4,096 WDT tile/inventory/map entries at 256 bytes each (1 MiB), one
  color-bearing source grid 331,776 bytes, maximum eight-layer texture metadata
  169,984 bytes, and 65,536 bytes of bounded temporary lists/stats/descriptors.

That conservative named subtotal is below 3 MiB; the remaining allowance covers
small parser/counter/decoder construction metadata. These are logical component
bounds, not a portable JVM/driver RSS claim. Caller-created unbounded future
histories, source/file-store caches and renderer/geometry ownership remain
separately accounted. The helper itself retains no completed request history or
whole-tile decoded alpha-mask cache.

## Optional integration

`BuildWorld.terrainTextures(map,tiles,token,progress)` defaults to an explicit
owned unsupported result for old/custom providers. WorldService's existing
constructor delegates to the new `(BuildTables,BuildFiles,TerrainAtlasPool)`
overload with a null optional pool. The helper is lazy and only created with
both files and a supplied application pool. WorldService close includes its
texture helper in the existing all-helper drain; it never closes the borrowed
pool/files/tables. No implicit pool-per-service is introduced.

## Verification

`./gradlew :concepts-world:check :rendering-world:check` passes (36 tasks), with
12 new source tests covering:

- Public cold/warm cache identity, cross-helper same-key reuse, exact receipts,
  explicit leases and no unused palette fetch.
- Missing diffuse partial/uncached result, closed-result retained-lease use and
  improved complete second read.
- Wrong WDT flags/MAID tuple/root source order, protected/changed/wrong-ID sources,
  final whole-batch revalidation and advancing source/decoder bounds.
- One global worker across helpers, delayed lower cancellation callback, latest
  pending supersession, local source close and already-canceled admission.
- Future cancellation after a result claim exists, undelivered claim cleanup,
  callback-failure reporting after drain, no borrowed source closure.
- Nine current plus nine pending real atlas outputs; nineteenth pool-busy result
  preserves all 18 cached keys. A maximum eight-layer raw tex0 is 7,379,048 bytes;
  it uses one reserved output and seven-map scratch, not all decoded tile masks.
- Caller count-before-copy rejection using a 10-element AbstractList whose
  element access throws; old-provider unsupported behavior and WorldService drain.

Private public-helper proof `/tmp/vimi-terrain-texture-proof/NativeSourceAtlasProof.java`
opens the original library and both scratch stores read-only, uses local-only
object access with no network/writes and reads the three previously verified
source pairs. Cold/warm/fresh-helper batches all return 256/256 coverage per tile,
identical prior RGBA/geometry hashes and the same warmed atlas objects. After each
batch, pool stats show three cached entries, no reservations/lease claims/borrows;
after all closes every count is zero. Evidence:
`native-source-atlas-proof.log`. The source-derived PNGs remain in the pure atlas
checkpoint. Observed timings are diagnostic only.

Native/GUI appearance snapshots, shader backends, UV seam qualification, child
texture acknowledgements and actual inline texture presentation are separately
owned gates; this source checkpoint claims none of them.
