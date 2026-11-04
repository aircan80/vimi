# Private prepared tile factory reusing the World geometry cache

Status: targeted design checkpoint only. No WorldService cache/factory behavior
has been changed. Depends on independent approval of the corrected scope engine.
Models, public LoadedTile semantics, renderer and packet advertisement remain out
of scope. This specifies concrete ownership before introducing source authority.

## Files and exact private changes

1. `WorldFileBytes`: add a private-constructed checked root-read receipt alongside
   owned bytes: requested/resolved numeric FileDataID, CKey, actual consumed length,
   complete/no-hole read result and read-policy revision. Only the actual bounded
   read/identity recheck can create it. It contains no bytes, stream or service.
   Hot roots use≤8MiB input; legacy readable roots retain their existing32MiB
   ceiling/behavior. A legacy read may save optional evidence only when it really
   satisfies the hot checked-read profile; otherwise its receipt remains absent.
   Post-read comparison permits storage/path/format/unknown-size enrichment and
   binds only numeric identity, CKey and consistent known consumed length.
2. `WorldService`: replace the values in its EXISTING twelve-entry LRU with
   private `CachedGeometry` entries. Do not add another geometry cache. Each entry
   owns the existing LoadedTile/raw Terrain, optional checked read receipt,
   optional lazily built immutable TerrainScene.Tile/fingerprint and pin metadata.
   Public LoadedTile remains descriptive and cannot populate/publish an entry.
   Only actual service read/parse code constructs private entries/evidence.
3. Add private/package-internal `PreparedGeometry` and `WorldPreparedTile` owned
   types in concepts-world, with private constructors and no positive DTO factory.
   They hold cache pins and optional atlas claims; they do not clone tile arrays
   or retain BuildFiles/WorldService/executor closures. Output metadata is detached.
4. Add a package-internal factory entry on WorldService for authored tests, before
   adding any public BuildWorld optional capability or GUI caller. Its preparation
   runs on the existing single World worker, within the scope engine's one-active
   operation. All source/callback work remains outside lifecycle/cache monitors.

`WorldStreamScopes` needs a private validated-admission callback: resolve current
registry token, validate fresh Map/WDT evidence and all eight MAID references,
then invoke the factory with a private admission value. That value can only be
created inside the engine; it binds owner/generation, checked scope digest and the
exact authorized tuple. Check lifetime/generation again before transferring the
owned result; cancellation/staleness closes it. Scope tokens and latest descriptors
still retain no heavy MAID proof or source handles. No arbitrary caller supplies a
RootReadReceipt, PreparedGeometry, BatchResult or positive material enum to this
entry point.

## Cache keys, evidence and reuse

Cache family is `(mapId, filenameX, filenameY, numericRootFDID)` for hot roots;
legacy path-only families retain their separate canonical path component. Ignore
caller display directory for numeric references. A checked version also binds its
root CKey. Changed root versions may coexist only as separate entries within the
same twelve physical slots while the old version is pinned; no invisible displaced
entry lives in a second map. Legacy lookup can scan at most twelve family entries,
so no extra unbounded version index is required.

A warm checked cache hit first compares fresh stat identity/known length with its
private actual-read receipt. Its immutable native tile is constructed at most once
from that entry's raw immutable Terrain and its fingerprint uses the existing
native-terrain-geometry-v1 algorithm. Scope admission supplies recorded filename
coordinates and all companion references; root receipt+fingerprint supplies actual
consumed geometry evidence. A private PreparedGeometry combines both. A public
LoadedTile, a current stat alone or matching coordinates cannot upgrade an unknown
old payload into proof.

For an unproven legacy cache entry, do one strict actual root read/parse. If the
checked geometry fingerprint equals that cached immutable geometry, keep its
arrays and attach the actual read evidence; discard the temporary comparison
payload. Otherwise install the checked version in a reserved free slot. Never
bless old geometry using only a fresh CKey without proving byte-derived geometry
agreement. An unpinned entry may be replaced; a pinned old version survives until
its consumers retire. Optional legacy evidence from an earlier actual checked
read avoids this upgrade reread on the common path.

The pinning applies to the shared cache, not only hot lookups. Evict only unpinned
entries; constructing/pending entries count against twelve. If all twelve slots
are pinned, return RETRYABLE_NO_CREDIT before another root input/geometry payload
is allocated. Do not bypass the cache for a legacy request or turn pressure into
missing/height terrain. Legacy callers can receive an explicit busy issue while
hot owners retire. Existing consumer-held legacy LoadedTile references remain
separate view ownership; cache eviction is not a claim that those arrays vanish.
The hot coordinator must use private leases rather than retaining another raw
LoadedTile neighborhood cache.

## Preparation transaction and material ownership

The private service factory consumes this sequence as one admitted operation:

1. Validate scope/admission and reserve/pin one of the twelve cache slots.
2. Reuse or construct checked geometry; close/clear root input/parser owners.
3. Call the service-owned WorldTerrainTextureSource for exactly the admitted tile;
   consume its actual BatchResult with try/finally on every outcome. No caller
   injects BatchResult.unsupported or a descriptive TileResult.
4. READY/PARTIAL requires real private receipt plus retained atlas lease. Compare
   build/locale/map/WDT/root/tex0, ALL recorded references, root CKey/length,
   WDT CKey/actual consumed length, fingerprint, recipe/cache key and coverage.
   Qualify the actual immutable native tile using TerrainTextureBinding.qualify.
5. Revalidate current source identity and scope generation before publishing one
   owned complete pair. Mismatch closes both claims and reports SOURCE_CHANGED,
   not height fallback. Pending, cancellation, close and unexpected programming
   failures cannot mint terminal height authority.

WDT proof must retain its ACTUAL consumed length as one bounded scalar in the
private scope registry; an optional stat size is not that evidence. Root receipt
likewise records actual complete read length. These are later packet source tuple
lengths, including height fallback, without fabricated source facts.

Typed results are PREPARED(owner), RETRYABLE_NO_CREDIT, UNSUPPORTED_CAPABILITY,
SOURCE_CHANGED and FAILED(bounded reason). A PREPARED height-only owner requires
genuine checked root geometry plus an actual terminal UNAVAILABLE/unsupported
source result from the owned material job (or explicit qualified-binding profile
failure), with current identities still matching. POOL_BUSY returns retryable;
no atlas pool/configured capability returns unsupported; neither means absent
material. Cancellation stays exceptional and explicitly closes owners.

Source execution remains one active plus one latest metadata-only descriptor.
The private factory initially stays synchronous on the existing World worker;
a future optional asynchronous adapter must enqueue only bounded scope/tile/token
metadata, never prepared arrays/leases, and remove replaced queued descriptors.
Scope close cancellation is dispatched outside lifecycle monitors; adding this
path must not attach blocking source cancellation to a WorldService monitor-held
listener chain. Source close invalidates before joining worker/capture/material
completion; no source handles are closed while accepted IO is still active.

## Pins and scoped payload callbacks

At most twelve factory output owner credits (including retained aliases) can live;
claim pressure refuses another output before allocation. Current display up to9
and prepared speculation up to3 fit that bound. Callback pins are separate, at
most one active callback per output owner. A cache pin identifies a static private
entry/credit ledger with no outer WorldService/BuildFiles/executor reference.

WorldPreparedTile.withPayload synchronously captures its geometry pin/reference
and an independent retained atlas claim before calling consumer code. Consume
TerrainTextureBinding.borrow only inside the atlas's scoped borrow. No geometry
arrays/RGBA copies are created; pixel/binding views must not escape. Capture uses
short metadata locks; callback executes outside every prepared/cache/pool monitor.
Concurrent close detaches base owners, then releases outside locks. Existing
callback pins keep immutable geometry and pixels valid through callback finally;
new callbacks fail once closed. Error, cancellation and rejected offer close all
captured pins. Cached geometry/atlas destruction is never inferred from GC.

Service close detaches its bounded output owner registry under a metadata lock,
closes those base owners outside locks, and waits asynchronously for worker plus
callback pins to drain. Avoid cache→prepared lock inversion: no cache/lifecycle
monitor calls user callback, prepared.close or source cancellation. A writer's
credit remains occupied until its callback and channel both drain; source close
cannot permit early CANCEL_UNUSED or release referenced pixels. Queued delivery
contains cleared owner mailboxes/metadata only, not retired full tile aliases.

## Named bounds requiring implementation verification

The one source LRU retains≤12 raw plus≤12 lazy immutable native payloads:
`2 × 12 × 1,231,872 = 29,564,928` bytes, as approved separately from material
helper memory. Aliased cache pins do not duplicate these payloads. No thirteenth
cache construction slot. Existing legacy view/CPU scene owners remain separately
named; this is not a global application heap bound.

Propose≤16MiB geometry construction transient allowance, separately charged from
the material helper:≤8MiB actual root input, up to four bounded per-tile geometry
representations/copies during parse/comparison (4×1,231,872), and≤2MiB area/parser/
control allowance. The named sum15,413,248 fits16MiB. Prove actual parser lifetimes
before implementation acceptance; no retained raw file or temporary comparison
tile survives into material loading. This narrows hot input relative to legacy
32MiB and does not widen any scene/atlas/work cap.

Reserve a NEW64KiB source-factory metadata allowance (twelve cache/evidence entries,
twelve output owners, bounded callback pin records and active/latest descriptors),
instead of silently assigning them to existing receipt metadata. If combined with
the corrected helper47,790,400-byte named total, this is47,855,936 bytes, still below
48MiB by2,475,712 bytes. Geometry payload/construction and prepared-table open
manifest/schema/certificate owners remain separate components. These are proposed
logical owner limits, not process/JVM/RSS or measured allocation peaks.

## Authored acceptance gates before exposing callers

Use real numeric Map capture→checked WDT/root→actual material source fixtures.
Assert legacy cache hit behavior, no second cache, checked warm reuse without root
reopen, unproven legacy upgrade requiring actual checked read, equal fingerprint
reuse, changed CK/geometry version retention while pinned and eviction only after
last pin. Hold all twelve slots and verify no thirteenth root allocation/read;
close/drop speculative pins and verify metadata wake/retry. Never accept a forged
LoadedTile/receipt or wrong scope/reference as authority.

READY, partial, genuine terminal unavailable, protected/changed root, POOL_BUSY,
unsupported source configuration and cancellation must remain distinct. Compare
actual WDT/root lengths and exact texture receipt binding. Race close against
held scoped payload callbacks, queued/failed writer and source IO; existing pins
remain valid, new work is rejected, final cache/atlas claims and callbacks drain.
No factory implementation or WorldService modification starts before this targeted
plan is reviewed; no packet/renderer/GUI integration is authorized by its tests.
