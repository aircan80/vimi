# Unified pack library and build loading

The desktop now uses packfile payloads with one SQLite index/catalog under the
application data directory's `library/` folder. The all-SQLite BLOB candidate was
not selected: its measured payload performance was mixed. Existing `objects/` and
`generated/` stores remain intact as migration/rollback sources.

## Storage boundary and tables

`ObjectStore.openLibrary(folder)` opens the shared original/generated pack engine.
Its `databasePath()` is `folder/objects.sqlite3`. The database contains the pack
index, live ownership, inventory generations and persisted build-list status.
The proven index tables retain their existing names; `storage_objects` is a live
view over the physical object index, not a copied presence projection. New storage
metadata uses `storage_*`, ownership uses `catalog_*`, build status uses `builds_*`.
These prefixes are conventions, not SQLite namespaces.

Open `LibraryCatalog` on that same database, then create immutable owner capabilities:

```java
ObjectStore library = ObjectStore.openLibrary(folder).join();
LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join();
PackLibraryOwner owner = library.forOwner(canonicalBuildIdentity,
        LibraryCatalog.Scope.BUILD, "metadata", LibraryCatalog.Role.ORIGINAL);
```

This example belongs on a worker, not the FX thread. Supply the owner capability
through `ObjectReader`, `ObjectWriter` and `HistoryLog`; do not change a global
current-owner variable. Concurrent build operations keep their captured identity.
Raw library reads support diagnostics; publishing without an owner is rejected.
`PackGeneratedStore` borrows the library and an owner capability through the
`GeneratedStore` interface. It never closes the shared library or changes record
serialization. Its compact operation compacts shared packs without deleting live
objects of either classification.

Pack bytes are safely written before the FULL SQLite index transaction publishes
location/integrity and owner membership. An existing object gains a second owner
before the owned operation completes. Runtime publication sources are separate
from the immutable expected ENCODING inventory. Expected membership can precede a
download; querying it against the physical index immediately reflects committed
presence. Original/derived is descriptive accounting metadata, not a deletion or
retention rule. Global/provider owners cover data without a WoW build.

## Durable metadata and recovery

The new mixed library has pack kind/application identity **2** and versioned
metadata. Legacy CDN/generated kinds **0/1** and their C# compatibility path remain
unchanged. Original C# tools do not open the new mixed-kind library; the old stores
are preserved for rollback. SQLite 3.35+ is required for catalog queries.

Unlike a disposable legacy index, the unified database contains authoritative owner
and status intent. A damaged or missing established unified index is refused rather
than silently deleted and rebuilt. An uncommitted tail after payload fsync is
truncated to the durable indexed boundary; it is not replayed as an ownerless
object. Process-termination tests cover both sides of publication. This does not
simulate physical power loss.

## Build loading and full preparation

The native composition publishes authenticated build/CDN configurations, ROOT and
ENCODING first, then prepares archive indexes, Files, Tables and the existing
Items/Creatures/Effects research services. `BuildOpener.openMetadataBuild` remains
the metadata-only boundary. Full preparation retains its immutable storage context
until workspaces and inventory work drain. Preparation readiness is versioned in
`builds_preparation_*` tables, separately from metadata load status.

`BuildRegistry` owns `builds_registry*` tables. Canonical statuses are DISCOVERED,
LOADING, LOADED, FAILED and INTERRUPTED. LOADED means successful metadata opening,
not full build download or table readiness. Restart recovers abandoned LOADING
attempts as INTERRUPTED; stale completions cannot overwrite a newer attempt.
The build list reads these persisted facts. Previous preparation receipts import
as retained/discovered facts, never fabricated fresh readiness.

`BuildObjectInventory` publishes the ENCODING reference inventory in bounded pages,
without reading object payloads or resolving file tables. Publication switches the
completed generation atomically. Unknown/incomplete inventories make sharing
provisional. The UI keeps status and accounting separate: successful metadata loading
does not wait for the entire inventory import. Until accounting completes, sizes
are explicitly unmeasured rather than zero. Selected build size cards refresh
asynchronously; cached list figures update when available. Undiscovered/unselected
rows do not each trigger an expensive full inventory calculation.

Original and derived payload bytes, unique/shared build bytes and global overlap
come from the catalog. Relational table/database bytes are excluded from per-build
object totals. Whole-library physical overhead can be measured separately.

Every derived object written or read through `PackGeneratedStore` is associated
with its build/provider before the operation completes. This includes manifests,
segments and cache hits, so the native accounting adapter skips legacy artifact
graph enumeration entirely. Image cache sharing across builds/locales survives
restart without another payload write. Providers have GLOBAL owners; their
standalone objects are not charged to a build. Global overlap is a subset of a
build's unique/shared partition, never an additional bucket.

Coverage describes the shared library, not old standalone caches or external
provider archives. Explicit unresolved inventory expectations remain incomplete,
including after a failed/corrupt import. Physical presence alone proves neither
semantic validity nor unknown legacy ownership. Measurement does not decode data,
fetch media, remove unregistered artifacts or promise reclaimable disk space.


## Preservation and lifecycle

On first activation `PackLibraryMigration` borrows old stores read-only, imports
observation history, saved preparation receipts and canonical retained owner intent,
then writes a completed migration receipt. Failure leaves that receipt incomplete
and the source data intact. Existing destination values are retained on retries.
A legacy writer lease prevents simultaneously running the old application against
borrowed source stores. SQLite can create reader WAL/SHM coordination sidecars;
original index and pack byte hashes remain unchanged in tests.

Old CDN payloads can be reused lazily through the read-only shared source rather
than copying the complete cache at startup. Resources close in dependency order:
stop opening, cancel/drain inventory work, release metadata contexts, close discovery
services, registry and legacy readers/leases, then ownership and the pack library.
No default-data directory is opened by tests or benchmarks.

## Phase two

Selected derived relational projections may later use indexed component-owned
SQLite tables, such as `items_*` or `questie_*`. The GUI calls domain query methods,
not arbitrary SQL. Existing query databases stay separate in this milestone.
Providers may retain their own downloaded source archives; derived binary artifacts
can use pack objects owned by a provider/version. Relational bytes remain outside
object attribution totals.

## Checks

Use a full Java 25 JDK and the repository's native SQLite setup:

```sh
./gradlew :object-store:check :generated-store:check :builds:check :accounting:check
./gradlew :gui:buildLoadingTest :gui:migrationTest :gui:loadStateTest
```

The first command checks affected storage/build components. GUI tasks use synthetic,
offline native fixtures, including metadata-only load, status/size persistence,
legacy preservation and restart. A local JavaFX override is needed only on Linux ARM64
machines where the pinned classifier is unavailable.

Historical BLOB measurements are in [the performance report](unified-storage-performance.md).
Do not interpret those as a speed claim for the selected pack library. Actual app
performance remains a measured criterion; full inventory accounting is a background
refresh and must not block navigation or interactive work.
