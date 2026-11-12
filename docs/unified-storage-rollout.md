# Unified storage rollout record

The selected architecture is now packfile payloads with one SQLite index/catalog.
The builds-only composition, preservation steps and persisted statuses are
implemented; see [the current service guide](unified-storage-services.md).
The all-BLOB plan below is retained as historical research and is superseded where
it describes payload placement or a deferred builds-only activation.

This was the integration and rollout plan for the all-SQLite backend. It records
existing integration constraints; it does not certify a completed application
migration. Keep the legacy pack backend and its compatibility tests while the new
backend is introduced. Never open a live application store as benchmark data.

The production service implementation is described in
[the library service guide](unified-storage-services.md). New storage tables use
`storage_*`; ownership tables use `catalog_*`. These names are conventions, not
SQLite namespaces. Existing relational databases retain their current schemas.

## Target and boundaries

One library database owns payload presence, integrity metadata, canonical build
membership, global/provider membership and object attribution. Payload rows contain
only `id` and `data` in `storage_payloads`. Large payloads remain chunked inside SQLite so publication
changes metadata without copying the entire object. Small/direct and chunked
payloads share the same logical object identity.

Keep `ObjectReader`, `ObjectWriter`, `HistoryReader`/`HistoryLog`, `GeneratedStore`
and `StorageAccounting` as component boundaries. Sharing a database does not move
build parsing, network acquisition, provider rules or UI state into layer 1.
Original/derived classification is descriptive accounting metadata only. It must
not select retention, validation, storage tiers or deletion policy.

Object identities include type, namespace and key bytes. EKeys are CDN encoded
object identities; they must not be reused as generic Questie archive identities.
Canonical build owners deduplicate regional/CDN discovery routes. Global/provider
owners cover resources that have no single build. Unknown or incomplete retained
inventories make unique/shared figures provisional, rather than proving uniqueness.
Relational table bytes are excluded from per-object build totals. Database/WAL/SHM
file lengths remain separate whole-library overhead measurements.

## Exact integration seams

| Location | Existing behavior | Minimal adaptation |
|---|---|---|
| `object-access/.../CachingObjectSource.java` constructors | Accept `ObjectReader`, `ObjectWriter`, `HistoryLog`; `localOnly` needs `HistoryReader` | Inject the new backend through these interfaces. Preserve history acquisition and paging, cancellation, local-only behavior and commit observations. |
| `generated-store/.../DiskGeneratedStore.java` | Concrete `ObjectStore` field/factories; maps generated keys to type 7 | Add a separate `SqliteGeneratedStore` adapter over the shared backend. Leave the legacy class and format-specific tests intact. |
| `gui/.../BuildApplication.java:open` | Opens `objects/`, `generated/`, `catalog.sqlite`, then `OverviewAccounting` | Own one new library backend, generated facade and ownership catalog. Select the backend explicitly until migration passes. Close the shared owner once after all consumers drain. |
| `gui/.../OverviewAccounting.java` | Takes concrete pack stores, polls lengths, mirrors presence into `accounting.sqlite` | Supply an accounting adapter over the unified catalog. Avoid a second authoritative presence index and remove relational database bytes from object attribution. |
| `gui/.../BuildUsageAccounting.java` | Supplies immutable ENCODING and generated producer inventories | Keep the producer adapters and canonical owner identity. Publish bounded catalog generations outside producer I/O; store downloads must become visible to size joins without a full presence rescan. |
| `gui/.../PreparationHistory.java` | Saves preparation receipts as one generated object | Preserve this object or explicitly import its receipt data before moving to a clean generated store. It contains prior build/locale/source selections and historical readiness. |
| `builds/.../Catalog.java` and `CatalogStore.java` | Disposable projection rebuilt from observation history | Keep its existing database initially. Its exact schema/application ID validation rejects a shared database; sharing requires a deliberate namespaced schema/session refactor. |

Paths in the table omit `src/main/java` and package prefixes for readability.

History support is a switch-over gate. `BuildApplication.discoverProducts` pages
history, and `Catalog` reconstructs build listings from observations and documents.
A backend that supports only generic `has/read/put` cannot replace the GUI store.
Preserve observation ordering, identifiers, typed documents and paginated cursors.

The generated adapter must preserve first-committed-value-wins behavior, ordered
batch outcomes, validation before writes, split large batches, generated error
codes, range bounds, cancellation and corruption reporting. Type 7 accepts derived
bytes without applying CDN identity rules. `RecordFile` serialization stays unchanged.

`GeneratedStore.compact` reports packs examined/moved. A documented no-op report
with all fields zero is valid when the backend has no packs, provided cancellation
and argument validation remain effective. It means no packs compacted; it must not
be presented as database size zero or disk space reclaimed. Do not run blocking
`VACUUM` implicitly. Pack-specific reclamation tests stay with the pack backend.

## Production work sequence

1. Deliver a separately named SQLite backend with a distinct, versioned database
   format/application ID. Its open path must reject unrelated or newer databases
   before mutation. Retain writer ownership/exclusion and independent readers.
2. Run original/derived payloads through the same database and generated facade.
   Use one owning service for shared lifecycle. Components must not close a database
   still in use by another facade. Add a minimal offline integration composition
   before changing the default application path.
3. Add canonical build/global ownership and bounded inventory generations. Expected
   refs can precede downloads. Stage at most a bounded page per transaction and
   atomically activate a completed generation. Producer enumeration, network waits
   and payload verification must not occur under a catalog writer transaction.
4. Connect build producer inventories and the accounting interface to this catalog.
   Keep name-coverage caching separate from payload ownership. Reporting must remain
   responsive during ENCODING imports and background ingest. A failed refresh keeps
   prior figures with stale/incomplete status; it never becomes a measured zero.
5. Prove history, saved preparation receipts, local-only reopening and shutdown with
   the new composition. Then implement explicit migration/selection and switch the
   default. Automatic garbage collection and library cleanup are later work until
   ownership and active-operation rules are complete.

The catalog must handle producer-specific generations: replacing a generated file
catalog must not erase ENCODING or another producer's references. Persisted retained
membership must survive closing a GUI workspace. Presence/size comes from the same
committed object table; failed/unpublished staging never contributes stored bytes.

Objects can be acquired before their final build membership is known. Track them
as active-operation/unattributed data until canonical inventory publication; do not
invent a build or treat every unknown key as a permanent global resource. Do not
enable ownership-based deletion while this transitional state is unresolved.

## Preservation and migration

A new backend is a new disk format; choosing SQLite does not silently change the
format-1 specification. Keep `ObjectStore`, `DiskGeneratedStore` and format docs for
legacy data. C# compatibility is not a goal (owner decision, 2026-11-05), and #274
removed the cross-language tests. No dual-format writer should target the same folder.

Migration must retain user intent before optionally discarding downloadable cache:

- Preparation receipts in `PreparationHistory`'s generated object identify retained
  builds, source configuration, region/locale and historical preparation milestones.
- Canonical retained owner and producer state currently lives in `accounting.sqlite`.
  Coverage/attribution can be rebuilt, but retained membership must be preserved.
- Observation history supplies the build catalog. If an import excludes history,
  the UI must not falsely present an empty library or silently forget retained builds.
- Preserve application settings and durable download policies when implemented.
  Current production Java does not expose a completed persisted Download-all policy
  implementation; do not claim one was migrated based on mock GUI labels.

Use a new destination, record resumable migration progress and validate imported
keys/lengths/ownership. Keep source data intact until the destination passes reopen
and offline verification. On failure, keep using the old selection; never mix a
partially imported destination into an authoritative complete inventory. A cache
reset is an explicit migration mode, not an implied consequence of opening the new
version. Re-downloading cache cannot recover saved user intent.

## Explicit selected-object importer

`LibraryImporter` copies an explicit list of at most 4,096 `ObjectKey` values from
an `ObjectReader` to `SqliteObjectStore`. It borrows both stores and accepts one
active import per instance. `importKeys` returns a future; native work runs on a
private virtual worker rather than the UI thread. Existing destination objects
are retained without overwrite. Entries distinguish copied, already present,
source absent, source unreadable and destination refusal. Unexpected failures and
cancellation complete exceptionally, rather than reporting a successful migration.
Previously committed objects remain after a canceled batch; retry the same keys.

Whole reads are bounded at 64 MiB. Larger encoded objects use a zero-byte metadata
probe, ranges no larger than 1 MiB and target staging; a retry starts at committed
staging progress. The destination verifies full encoded identity at publication.
Closing an interrupted handle preserves resumable chunks. Importing never invokes
source writes/deletes or discovers additional keys. Prefer the legacy read-only
opener: a read-write source reader can update access bookkeeping. Even a read-only
SQLite connection may create WAL/SHM reader sidecars on a writable directory;
original source index and payload bytes remain unchanged. Absolute directory
immutability requires read-only media or an explicitly immutable source reader.

The utility copies only explicitly selected payloads; it does not discover history,
import owner registrations or activate a destination. A selected history record
populates the destination history index through the normal backend publication path.
Callers must also select its document and verify the chosen history is complete.
Complete history/owner migration remains explicit rollout work and must pass before
any default GUI cutover. The receipt
identity can be derived as type 7 plus the first 16 bytes of SHA-256 of UTF-8
`vimi-preparation-history-v1`, matching `GeneratedKey.hash`; importing that key is
not a substitute for retained-owner and history-index migration.

## Questie and remaining relational stores

Questie stays independent during initial rollout. `QuestieSourceRepository.acquire`
produces versioned `archive.zip`, manifests and notices; `verify` and `BakedArchive`
use `Path`/`ZipFile` and random ZIP access. Moving only the archive bytes to SQLite
requires a bounded, verified temporary-file export or a seekable archive adapter,
plus explicit provider/version ownership. Preserve manifests, source notices,
acquisition verification and offline importer tests. Do not pass a CDN EKey for a
Questie asset or load a 256 MiB archive as one Java array.

The current build catalog, Items query indexes and Questie derived snapshots are
relational projections with component-specific schema/version checks. Repointing
them to the library DB without a schema/session refactor is unsafe. Keeping these
projections separate initially does not create a second authoritative object store.
Their disk sizes stay outside build payload totals. Consolidating their relational
tables is a separate, explicit integration step if still desired.

## Validation and rollout gates

Use changed-component checks during development. Start with:

```sh
./gradlew :object-store:integrationTest
./gradlew :generated-store:check
./gradlew :object-access:check :builds:check :accounting:check
```

For the generated adapter, extend `GeneratedStoreContract` with a SQLite fixture;
its corruption hook must damage a synthetic SQLite payload rather than search pack
files. Adapt `GeneratedPersistenceTest.generatedDataSurvivesReopenAndRecordPagesReadFromDisk`:
write 1,000 records with `RecordFileWriter`, close/reopen, find the final record and
read a page. Keep `compactionReclaimsGeneratedPacksAndKeepsLiveData` pack-only.
Exercise `RecordLifecycleTest` and `RecordFileTest` behavior with the adapter as
needed, without duplicating codec-only tests.

Before default application cutover, adapt or extend `CachingObjectSourceTest`,
`BuildApplicationIntegrationTest`, `PreparationHistoryTest`,
`NativeWorkspaceCommitTest`, `OverviewAccountingUiTest` and
`UsageIndexTest`-equivalent unified catalog contracts. Use deterministic offline
fixtures, no live download credentials or published user corpora.

Scale and recovery checks must cover:

- Synthetic inventories on the observed order of 2.2 million EKeys per build,
  multiple shared builds and global ownership; record query/import p95/p99, peak
  heap, WAL/temp/disk footprint and first-open/reopen latency. Tiny fixtures do not
  establish full-build accounting performance.
- Foreground reads and ownership writes while bounded downloads and inventory
  imports compete; measure writer wait and chunk/metadata commit occupancy.
- Verified 145/256/512 MiB streaming, interrupted append/resume, cancellation during
  verification/publication, lost process after commit, SQLite-full and actual
  filesystem-space exhaustion. Process kill is not physical power loss.
- Deletion/page reuse, long reader snapshots, checkpoints and maintenance scheduling.
  No claim of reclaimed filesystem bytes without measuring them.
- A migrated retained library reopened offline, with source untouched, including
  generated receipts and observation history. Inject migration interruption and
  verify deterministic resume or fallback.

Existing GitHub integration workflows already run Linux, macOS and Windows with
native SQLite and Java 25. New native suites must be selected by the component
impact rules and included in their report artifacts. Keep legacy Java/C# and crash
recovery CI until their compatibility promise is explicitly retired. Hosted checks
are useful evidence; when hands-on native debugging is needed, request the relevant
platform machine before changing platform-specific behavior.

Until these gates pass, label the new backend as available for controlled
integration, not a completed default migration or universally faster storage.
