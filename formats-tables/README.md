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
Normal restore reads and validates just the saved inventory and its identity; it
opens no individual table graphs and performs no acquisition. Changed inputs or a
missing/corrupt inventory produce a cache miss. Table access still validates the
requested graph and uses the normal repair path for missing/corrupt derived data.
The overload accepting a **local-only** table handle remains available for explicit
verification of every readable graph. Failed/canceled/unchecked passes
cannot overwrite a completed inventory. Explicit enrichment may replace it.

The small inventory pointer is replaceable derived data over immutable decoded
row graphs. A crash/store failure during pointer replacement yields a cache miss;
it cannot publish a false ready state or remove the underlying table rows.

Inventory restoration emits `PreloadStage.RESTORING`, separately from downloading
and preparing. “Tables ready” records completion of the matching preparation pass;
it does not promise that every stored byte has been rechecked during this open.

## Prepared-local captured rows

`BuildTables.capturePreparedLocal(ref, token)` is an optional capability; other
implementations default to UNSUPPORTED. Native capture requires
`TablesOptions.withLocalDefinitionsOnly()` and an already saved generated graph.
It resolves content identity through the exact-ID `listContentInventory` (never
`stat`, which can sniff payloads), local definitions and `savedLayoutOf`, then uses the new
nonrepair `GeneratedTableStore.openPreparedLocal` path. It never calls ordinary
preparation, direct DB2 decoding, getOrBuild, acquisition or cache repair. Missing
inputs return typed unavailable/not-prepared results; they are not empty tables.
Ordinary `GeneratedTables.open` retains its existing repair behavior.

A successful `CapturedRows` holds one exact reader and immutable receipt: build,
locale, table/content/layout, selected schema/columns and document/block identity,
graph/source/reader policy, effective key fingerprints, counts and section/hole
evidence. Key fingerprints can explicitly be unknown; key bytes are not exposed.
Ownership bookkeeping from an existing build-owned store read is allowed. Capture
publishes no new comparison reports or two-build catalog ownership.

Use `readOrdinalPage(firstOrdinal, maxRows, token)` starting at zero and follow
`nextOrdinal`. Requested rows are1–256, but the initial implementation returns at
most **one raw row** per call to avoid materializing256potentially large rows.
Row source order is ordinal, not sorted ID order. A returned row is conservatively
bounded to1MiB with64KiB cells. Oversized cells become empty placeholders with
explicit `Page.cellIssues` LIMITED annotations; callers must inspect those before
interpreting cells. Other cells and copy identity survive. A cell issue with
columnIndex `-1` means the row's original issue channel was limited. Original
`Row.textIssues == null` remains distinct from an empty issue list, including when
new byte limits are present. These limits do not bound allocations inside the
borrowed RecordFile decoder/decompressor, which retains its own source limits;
they are not a32MiB whole-process memory guarantee.

A held reader is **BEST_EFFORT_INVALIDATING**, never a graph/object retention lease.
READY and exhaustion leave membership **NOT_CERTIFIED**. Segment disappearance or
corruption invalidates the handle; subsequent reads report invalidation and never
reopen/reprepare another graph. Closed/failed pages carry no rows or EOF claim.
Consumers must inspect page state, not treat any null continuation as success.

Capture delivery futures are separate from private completion ownership. Canceling
one requests operation cancellation but does not detach cleanup or parent draining;
a handle admitted after delivery is abandoned is closed before private completion.

Close rejects new work, cancels/drains accepted reads, and releases reader/block
references. Parent table close also cancels pending capture opens and closes live
captured handles before closing borrowed files. The shared application library
remains application-owned. There are at most8live captures per native table handle
and2pending reads per capture. Cancellation of an individual read does not certify
absence or discard useful source data. No sorter, semantic comparison adapter,
persistence, resume or report-publication protocol is supplied by this capability.

Focused offline validation is in `CapturedRowsTest`; the normal
`./gradlew :formats-tables:check` also verifies existing repair/read behavior and
Java integration tests.


## Prepared-local build key bootstrap

`BuildTactKeys.loadPreparedLocal(tables, token)` returns the same immutable KeySet
as ordinary `load`, using only `capturePreparedLocal` for the exact TactKey and
TactKeyLookup FileDataIDs. It verifies captured build/locale/table identities,
actual integer byte columns, page identity/ordinals/counts and short continuations.
There is no by-name fallback, check/schema/normal page call, table decode, repair or
acquisition. The ordinary loading path is unchanged; both paths share its byte
validation and ID-join/zero/duplicate/conflict rules.

Missing/unsupported/damaged local bootstrap produces typed table outcomes and an
empty build-key set. The application can retain its captured community key source;
missing bootstrap does not block an unencrypted comparison table. Readable PARTIAL
rows remain useful, and unmatched joins retain the existing unknown accounting.
Returned key bytes are owned copies, and diagnostics expose counts/outcomes and
fingerprints rather than key material. Tests use authored byte patterns only.

Source-specific bootstrap limits are65,536rows per table,32MiB cumulative scanned
cell/issue estimates and8MiB conservative retained source-map admissions across both
tables. Join/output copies remain bounded by these input/row limits; the8MiB figure
is not an exact whole-bootstrap heap cap. Native capture currently reads one raw
row per page. Bootstrap runs before comparison capture/sort; its parser/decompressor
allocations and these source-specific caps must not be presented as the comparison
core's32MiB working-buffer guarantee. Quota failure produces RESOURCE_LIMIT and
no partially conflict-checked usable keys.

Both captured handles close/drain on cancellation, failure or success. Canceling
the returned delivery future requests worker cancellation without detaching private
cleanup. The borrowed BuildTables and shared library remain application-owned.

## Native row-issue evidence

New native preparation uses `GeneratedTableSource.forNativeBlock` and the audited
`GeneratedTableContent.nativeDb2` factory. `Db2Reader` mints the package-private
origin capability from its actual selected block, effective options and flattened
columns. Generic callbacks cannot obtain that seal. The existing eight-argument
content constructor and accessors remain available, with UNKNOWN issue evidence.
`GeneratedTableContent` is now a final value class rather than a Java record;
record reflection consumers must adapt. Its generic equality/hash semantics are
preserved. `GeneratedTableSource` adds an issue-policy record component and keeps
the existing five-argument UNKNOWN constructor.

Native graphs use separate identity/node/metadata formats. Identity tuples encode
strings as a null tag (0), or a present tag (1), unsigned32 big-endian UTF-8 length
and bytes. Fixed order is format, policy, producer, locale, content key, resolved
block digest, options digest, reader revision, row codec and actual encoding scope
(null for unscoped graphs). Column digests include ordered indices/names/kinds,
ID flags and all interpretation/narrowing fields; integer fields are big-endian
and nullable structures have explicit presence tags. The fixed native identity
fixture is independent of the unchanged legacy C# identity/row fixtures.

`GeneratedTable.issueCertificate()` returns verified native evidence, or null for
UNKNOWN legacy/custom graphs. Publication counts actual emitted rows and validates
native row shape/IDs/issue locations. Both prepared opening and artifact inventory
validate certificates against the final graph, actual scoped/unscoped source,
header columns and saved row count. Inventory does not read row segments.

Native capture normalizes a null issue list to an explicit empty list only after
validating the graph-bound receipt. Direct parser reads, ordinary generated reads
and saved row bytes retain null. Explicit invalid/unknown issues and capture limits
remain visible. A limited issue channel never becomes known issue-free; safe
numeric siblings remain independently usable. No membership certificate is added.

Normal preparation and capture prefer native graphs, then exact legacy graphs only
on absence. Legacy graphs stay UNKNOWN and are not rebuilt solely to upgrade this
evidence. Prepared capture never repairs or acquires data; native corruption does
not fall back to a legacy graph. Comparison records compact evidence provenance
without changing raw semantic or Items field-admission fingerprints.

Focused checks are `NativeIssueCertificateTest`, `CapturedRowsTest`, the native
SQLite/pack `TablesPipelineTest`, and raw/Items source adapter regressions. Run:

```sh
./gradlew :formats-tables:check :concepts-comparison:check :gui:check
./gradlew :formats-tables:interopTest -Pdotnet=/absolute/path/to/dotnet
```

Independent implementation acceptance is still required; design approval alone is
not implementation approval. See the [reviewed contract](../docs/comparison/native-issue-certificates-proposal.md).
