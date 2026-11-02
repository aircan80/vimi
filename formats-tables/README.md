# Client tables and DB2 formats (layer 5)

This module ports the table side of the pinned C# `VibeMiner.Formats` design at
`4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`. Assets live in the separate
`formats-assets` module. Table reads consume layer 4 `Files`; fetched definitions
consume layer 2 observations/history. Derived rows use the layer 1 generated-store
contract and its portable record files, as in the reference.

## Components

- `db2`: bounded WDC3/WDC4/WDC5 fixed and sparse structural indexes, on-demand
  row/ordinal/ID reads, copied and protected IDs, native relationships, typed
  text issues, integer storage and schema interpretation. NONE, immediate,
  signed immediate, common, pallet and pallet arrays retain their distinct rules.
  Verified raw-integer profiles, constant-upper-bit narrowing and low-bit
  collision evidence are selected before rows are emitted. `Db2Reader.open`
  accepts either a range byte source or a Files handle; the latter returns a typed
  `Db2Open` that owns its stream. `Db2FileSource` serializes seek/read access and
  keeps four 256 KiB blocks; failed speculative reads retry only the requested
  range. Holes remain named unavailable ranges. Closing clears cached plaintext.
- `definitions`: strict bounded DBD parsing, exact build and same-major layout
  selection, typed schemas, all 15 locales, the complete pinned WoWDBDefs provider,
  local/fetched precedence and structured failure provenance. Fetched definitions
  remain in observation history, retain the first date/revision of unchanged
  bytes and use 24-hour refresh/15-minute minimum-gap defaults. A table handle
  serializes asking and shares one fetch budget across its operations.
- `generated`: resolved-field/options identities, key-fingerprint dependency
  graphs, shared independently canceled builds and encrypted encoding scopes.
  Rows stream through portable record files in bounded pages; raw text evidence
  is charged across the whole generated decode. Damaged derived descriptors,
  nodes, manifests and damaged segments are discarded and rebuilt. Key changes
  invalidate dependent tables; clear content shares across builds/locales when
  its selected fields have no locstrings.
- `tables`: `Tables`/`BuildTables` services, shallow listing, checked states,
  schemas, exact row/filtered-page queries, declared foreign keys/referenced-by,
  TACT bootstrap and background preloads. IDs and integer filters remain exact
  unsigned32/int64/uint64 values. Typed text issues and raw evidence never become
  semantic keys. Interactive direct reads start bounded background preparation;
  at most eight sources run/wait, and the last 64 failures force the generated
  path on retry. Separate four-interactive/two-background lanes have 256 queued
  callers each and give queued interactive work precedence.

`BuildTactKeys` joins only the build's own TactKey and TactKeyLookup rows. Zero
placeholders, malformed bytes and conflicting keys never become usable keys.
`TablePreloads` bounds four jobs by default, retains 64 terminal results, cancels
and drains on close, and delivers serial/coalesced progress with stage/final
snapshots retained. Subscriber failures do not stop work. An exposed job future
cannot forge its internal completion or bypass resource cleanup.

The published test fixtures include independent `MemoryTables`, shared consumer
contracts and an authored tiny DB2 writer. They need no game data or credentials.

## Commands

Use the repository's full Java 25 JDK and consumer-supplied SQLite library setup.

```sh
./gradlew :formats-tables:test
./gradlew :formats-tables:check :formats-tables:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
```

`check` includes compiler warnings as errors, formatting, Checkstyle and native
integration. The native pipeline wires real ObjectStore, CachingObjectSource,
FileSystem, DB2 and generated storage; after reopening both stores, it deletes
encoded DB2 input and verifies the durable generated table still serves identical
rows/schema/identity.

The optional compatibility suite builds a friend-name harness against unchanged
pinned C# assemblies. Build the reference Formats project in Release first:

```sh
../vibeminer/.local/toolchains/dotnet/dotnet build ../vibeminer/src/backend/VibeMiner.Formats/VibeMiner.Formats.csproj --configuration Release -p:RestoreLockedMode=true -p:NuGetAudit=false
./gradlew :formats-tables:interopTest -Pdotnet=/absolute/path/to/dotnet -PreferenceRoot=/absolute/path/to/vibeminer -PsqliteLibrary=/absolute/path/to/sqlite-library
```

This checks exact default/null-versus-empty options hashes, selected-field hashes,
node identities, rows-v1 bytes and optional metadata fields, then exchanges native
generated table caches in both directions. The finite single formatter also has
2,048 deterministic bit patterns plus boundary cases generated with the pinned
.NET SDK's invariant `Single.ToString("R")`.

## Provider packaging and qualifications

The original provider's manifest, source listing, license and notices are bundled
unchanged. All 1,346 definition byte slices retain their original SHA-256. Only
compression is repacked from Brotli to deterministic gzip, so the JDK can read it
without another codec or native library. Runtime verifies the pinned resource
hashes, decoded stream hash/length and each requested definition hash. See the
resource [repack notes](src/main/resources/com/vibeminer/formats/definitions/REPACK.md)
and [upstream notice](src/main/resources/com/vibeminer/formats/definitions/NOTICE.md).
The parsed provider cache retains at most 64 entries/2 MiB of source bytes, keeping
its newest entry even if that one alone exceeds the byte target.

Java uses immutable collection snapshots and future/cursor APIs in place of C#
records, Tasks and async enumerables. Prefer `Db2Table.rows`/`GeneratedTable.rows`
or page reads for large tables; `Db2Table.readAll` is an explicit materializing
convenience. Java object/index overhead and this implementation's 256-row generated
write batches differ from C#'s compact collections/one-row enumeration. Reader
index reservations express the same policy estimates, not equal measured Java
heap usage. User-facing failure prose is shorter; stable state/code/time and
structured table/fetch provenance are retained.

Real-client corpus/performance work, application wiring, native Windows machine
certification and release packaging remain separate. The implemented reader
supports the reference's bounded subset rather than every historical DB2 format;
other magics, unsupported layouts, malformed framing and exceeded limits remain
explicit refusals.

## Preparation of the known inventory

`BuildTables.prepareKnown(progress, token)` is an additive preparation boundary for
application readiness. The existing `preload` contract and its pinned C# behavior
remain unchanged.

The new operation freezes the handle's catalog of known, nonzero FileDataIDs;
community filenames do not expand its scope. Its result contains a complete,
FileDataID-ordered outcome ledger on successful completion, including absent and
not-in-locale entries. Absent/not-in-locale entries are excluded from the actual
table-work denominator. Present known files with missing/unusable definitions are
still acquired and explicitly assessed. Unknown files outside the manifest remain
outside this preparation pass.

`COMPLETED` means every in-scope table was assessed, not that every table is
readable. Partial, encrypted, missing, unsupported, corrupt and unavailable data
remain explicit per-table outcomes. Inspect that ledger to describe availability;
`tablesFailed` is a diagnostic count, not a readiness predicate. Cancellation,
store/system failures and still-unchecked data do not establish completed
preparation. Generated readable tables can be reused without reacquiring a missing
original encoded file.

Use `TablesOptions.withLocalDefinitionsOnly()` for a prepared session or key
bootstrap that must not implicitly refresh/fetch community definitions. Existing
constructors preserve the original behavior. The application should bind an
immutable key-status snapshot and stable definition provider to each preparation
context; later enrichment creates a replacement generation/handle. Generated
table identities already include definition/source and relevant key fingerprints.
Catalog entries without a known nonzero FileDataID are not addressable by this
API and cannot be claimed as downloaded.

## Completed inventory persistence

`PreparedTablesCache` saves the full completed known-table ledger, retaining
protected/partial/absent outcomes and reasons. Its caller supplies the stable
build/locale, catalog/definition, reader policy and key-fingerprint identity.
Restore uses a **local-only** table handle to validate readable generated graphs;
incompatible/missing artifacts produce a cache miss. It never calls prepareKnown,
ensure or online acquisition during restore. Failed/canceled/unchecked passes
cannot overwrite a completed inventory. Explicit enrichment may replace it.

The small inventory pointer is replaceable derived data over immutable decoded
row graphs. A crash/store failure during pointer replacement yields a cache miss;
it cannot publish a false ready state or remove the underlying table rows.

Prepared inventory checks emit `PreloadStage.RESTORING`, separately from downloading
and preparing. Restoration still validates readable local table artifacts and can
fall back to ordinary preparation if an input is missing, changed or corrupt.
