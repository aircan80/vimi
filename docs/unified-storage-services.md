# Unified SQLite storage services

The library services are available separately from the legacy pack engine. The
current desktop application still opens its existing stores; this change does not
activate a migration or discard caches. The prototype measurements are in
[the experiment notes](sqlite-blob-prototype.md), and remaining application adoption
work is in [the rollout plan](unified-storage-rollout.md).

## Component boundaries

`SqliteObjectStore` implements `ObjectReader`, `ObjectWriter` and `HistoryLog`.
`SqliteGeneratedStore` implements `GeneratedStore` as a non-owning view over the
same library, preserving generated record-file serialization. `LibraryCatalog`
owns canonical build/global membership and inventory generations in that database.
The application owns their lifecycle and closes the catalog before the shared
object store after consumers have drained. Closing a generated facade must never
close the library for other consumers; this facade has no independent close method.

```java
Path database = directory.resolve("library.sqlite");
SqliteObjectStore objects = SqliteObjectStore.open(database).join();
try (LibraryCatalog catalog = LibraryCatalog.open(database).join()) {
    GeneratedStore generated = new SqliteGeneratedStore(objects);
    // Supply objects through ObjectReader/ObjectWriter/HistoryLog interfaces.
    // Supply generated through GeneratedStore. Do not expose SQL sessions to UI controls.
} finally {
    objects.closeAsync().join();
}
```

This is a lifecycle example for a worker thread, not permission to block the GUI
thread with `join`. Open, payload operations and catalog operations return futures.

## Table ownership

SQLite has no named namespaces within one database file. Prefixes document table
ownership and component interfaces enforce it:

| Prefix | Owner | Contents |
|---|---|---|
| `storage_*` | Object backend | Physical presence, payloads, integrity, staging and observation history |
| `catalog_*` | Ownership catalog | Canonical owners, expected sources and active/incomplete/retired inventory generations |
| Future domain prefixes, e.g. `items_*` | Corresponding domain component | Queryable projections, if a later change deliberately colocates them |

`storage_payloads` contains only `id INTEGER PRIMARY KEY` and `data BLOB`. Chunks,
identity, lengths, classification and ownership remain metadata. Identity is the
full 18-byte typed/namespaced object key. CDN and generated objects coexist; their
validation follows the typed identity. Original/derived classification is descriptive
accounting metadata, never a retention or deletion rule. Relational table sizes
are excluded from object/build payload totals.

Existing build and item query databases remain separate. Their owners continue
exposing domain methods such as item paging/search; sharing a database later would
not authorize arbitrary SQL access from the GUI or across component tables.

## Durability and staging

The database has an application identity and versioned storage metadata. Unrelated
formats are rejected before schema initialization. A writer lock excludes a second
object backend for the same database; the catalog has its own owner lock and
connection. SQLite serializes their short write transactions. Reads use independent
read-only snapshot connections. Original pack formats and C# compatibility remain
available through the existing backend and its tests.

Writes use WAL and FULL synchronization. Admission and worker queues are bounded.
The backend publishes immutable chunks and their proofs atomically, and checks
intersecting block CRCs before returning range bytes. History documents/records
remain available for build discovery and history records are protected from deletion.

Verified ready objects up to 1 MiB publish in one FULL transaction. Encoded-object
staging permits appends of at most 1 MiB. Arbitrarily small input fragments fill
fixed-size rows rather than creating a row per network read. Acquire input outside a
transaction, append/commit a bounded chunk, then allow another request to run.
Complete stages are verified outside the writer before publication; large objects
remain chunked, avoiding a whole-file publication copy. Closing preserves committed
progress for resume. Explicit abandonment/deletion marks chunks for bounded cleanup.
No implicit full-database VACUUM is performed. SQLite can reuse freed pages without
shrinking the file. The generated facade's all-zero compaction report means no
pack work occurred; it does not claim the database is empty or space was reclaimed.

## Accounting

Declare retained canonical owners and every expected inventory source using
`retainOwner`/`expect`. `replace` consumes pages of at most 1024 references outside
write transactions and atomically switches the completed generation. Failed,
canceled or stalled replacements do not expose partial references. Old generations
are reclaimed in bounded pages without deleting object payloads.

`figures` joins active references directly to `storage_objects`: a committed download
or deletion immediately affects the next query, without copying presence into a
second accounting database. Source references can exist before their objects are
stored. Cross-source references are deduplicated by full object key, and sharing
counts canonical BUILD owners. Global/provider overlap is reported separately;
it overlaps, rather than adds to, the unique/shared-build partition. Unknown or
incomplete inventories make sharing provisional. Retaining/forgetting an owner
changes membership, not payload retention policy.

## Explicit imports

`LibraryImporter` accepts up to 4096 explicitly selected keys and reports copied,
already present, absent, unreadable and refused entries. It does not enumerate or
delete the old store, overwrite existing destination values, import owner intent,
or activate a new library. Large encoded objects stream in bounded ranges through
resumable staging. Cancellation is exceptional and preserves previously committed
work; retry the keys to continue. Source/destination lifetimes belong to the caller.

Use a read-only source to avoid legacy read-day bookkeeping. SQLite may still
create reader WAL/SHM coordination sidecars on a writable source filesystem; the
importer does not modify source payload/index contents. Preserve preparation receipts
and canonical retained owner membership before any application cutover. Existing
source stores remain intact for rollback. No automatic cache reset is introduced.

## Checks

Use a full Java 25 JDK and SQLite 3.35 or newer with the repository's normal
native library configuration:

```sh
./gradlew :object-store:integrationTest --tests '*SqliteObjectStoreTest' --tests '*LibraryCatalogTest' --tests '*LibraryImporterTest' --tests '*UnifiedLibraryCompositionTest'
./gradlew :generated-store:integrationTest --tests '*SqliteGeneratedStoreTest'
./gradlew :object-access:integrationTest --tests '*SqliteObjectSourceTest'
./gradlew :object-store:productionStorageComparison
./gradlew :object-store:libraryAccountingBenchmark
```

These cover payload integrity and lifecycle, staged resume, ownership generations,
source preservation, shared presence joins, existing generated/source consumer
contracts and record-file reopen. They use synthetic data, not live CDN credentials
or user library contents. Full-inventory scale, production acquisition composition,
application preservation/activation and platform rollout remain explicit gates.
The accounting benchmark defaults to 2,209,282 references per synthetic build;
`-PaccountingBenchmarkObjects=10000` selects a smaller smoke fixture. It seeds only
presence/reference metadata, not payload chunks. Its size-query results must not
be presented as content-integrity or ingest-throughput evidence. Performance must
be measured on the actual service implementation, not inferred from the earlier
prototype. See [measured results and limitations](unified-storage-performance.md)
and the rollout's scale requirements before changing application defaults.
