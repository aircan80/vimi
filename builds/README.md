# Builds (layer 3)

Port of the pinned seven-layer C# `VibeMiner.Builds` design. This component depends
on layer 2 `object-access` contracts, not layer 1 I/O implementations. Immutable
key, history, CDN route and range values flow through the layer 2 boundary, as in
the reference. No JavaFX or application launcher dependency is included.

## Components

- `BuildCatalog` / `Catalog`: offline build listings projected from versions history,
  explicit versions/CDN refresh and summary discovery.
- `BuildIdentity`: byte-identical identity v2 with pinned C# golden vectors, sorted
  field framing and UTF-8 byte lengths. Region/CDNConfig are observation attributes;
  missing and empty ProductConfig fields remain distinct identities.
- `Products`, `VersionsTable`, `VersionsReply`, `Summaries`: strict bounded parsing,
  reviewed labels and bounded natural-order discovery of future WoW products.
- `CdnsRoutes` / `BuildRegions`: recent distinct CDN history and region selection,
  with numeric sequence precedence and offline fallback.
- `Builds` / `BuildOpener`: authenticated metadata loading through layer 2, parsers,
  shared successful opens, independently canceled waiters and file resolution.
- `DownloadedFigures`: bounded ledger measurement and commit-driven presence tallies.
- `DownloadAllJobs`: resumable background passes over missing ledger objects with
  free-space reserve, archive-order fetching, cancellation and progress coalescing.

`MemoryCatalog`, `MemoryBuilds` and shared contract suites are published through
the test-fixtures variant. Synthetic history, metadata and ledger fixtures need no
live credentials, network services or user data.

## Build and verification

Use the full Java 25 JDK and system SQLite setup from the repository README.

```sh
./gradlew :builds:test
./gradlew :builds:check :builds:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
```

Fast tests cover the memory catalog, build resolver/parser contracts, downloaded
figures and download-all jobs. Integration tests exercise the same catalog
contracts against native SQLite, cache reopen/rebuild, foreign/newer-file refusal,
active SQLite cancellation, accepted-work draining and uncertain commit recovery.
`check` includes formatting, Checkstyle and compiler lint with warnings as errors.

The optional catalog compatibility suite uses the unchanged pinned C# reference:

```sh
../vibeminer/.local/toolchains/dotnet/dotnet build ../vibeminer/src/backend/VibeMiner.Builds/VibeMiner.Builds.csproj --configuration Release -p:RestoreLockedMode=true -p:NuGetAudit=false
./gradlew :builds:interopTest -Pdotnet=/absolute/path/to/dotnet -PsqliteLibrary=/absolute/path/to/sqlite-library
```

For an isolated Java worktree, also pass `-PreferenceRoot=/absolute/path/to/vibeminer`.
The harness uses the reference's existing test-friend assembly name solely to
exchange catalog cache files in both directions. It does not modify reference
source. .NET is not needed for ordinary unit/integration checks.

## Catalog behavior

`Catalog.open(source, databasePath)` creates a derived cache, not a second source of
truth. Listing reads versions history in pages and resolves document bytes through
layer 2 without a CDN context; it never calls an observation source. Explicit
`refresh` asks versions and cdns independently, then serves the stored list even
when an observation fails. `refreshSummary` reports discovered products, malformed
rows, sequence text and products whose summary lists no versions document.

The first sync in each process scans all history. Later syncs resume at a history
entry at least ten minutes older than the newest read entry. Unreadable documents
are retried by key; over 1,000 failures force a full rescan. Entries arriving more
than ten minutes late with old timestamps can require a reopen/full scan, matching
the reference's resume policy. Projection is idempotent by history record key.

A source host's newest readable versions document is determined by numeric sequence,
then millisecond observation time, then record key. Host regions keep independent
last words. Malformed whole replies cannot erase a previous current build; invalid
individual rows and ambiguous duplicate regions are reported separately. Current
build listings preserve CDN-config republications without changing build identity.

The catalog uses the reference's schema version 1 and application id `VMcs`, with
WAL/NORMAL transactions. Existing foreign, newer and unreadable databases are
probed read-only and refused without deleting or replacing them. Unknown commit
outcomes stop use until reopen; a committed projection remains durable and an
idempotent retry finds it. It is safe for the application to explicitly discard
this cache and rebuild from history, but this component never does that silently.

Native SQLite follows the existing consumer-supplied library strategy, including
`-Dvibeminer.sqlite.library` and native-access enablement. The private catalog FFM
adapter is adapted from this repository's validated object-store adapter; it is
kept local to avoid exposing store internals or introducing a second bundled native
library. A future shared SQLite extraction can be reviewed separately.

Catalog requests run on one serialized bounded worker with 64 queued requests;
open uses two shared bounded workers. Queued token cancellation removes work before
it touches the source or database. `closeAsync` rejects new work and drains accepted
calls. User future continuations execute off those workers. Active native VM work
uses progress cancellation; SQLite lock waits keep the five-second busy timeout.

## Build opening and resolution

The opener reuses successful builds and joins concurrent opens of one build. One
caller's cancellation does not cancel the others; once all waiters leave, the
abandoned open is canceled and forgotten. Options bound cache size, index-read
concurrency, active opens and queued opens (default queue 64). Admission/lifecycle
failures use `BUILD_OPEN_LIMIT` and `BUILD_OPEN_CLOSED`.

Parsers cover config text, archive/file indexes, BLTE metadata decoding, ENCODING,
ROOT and ROOT path hashes. Keys and format digests are checked; unknown formats and
encryption remain explicit states. The BLTE metadata path decodes byte arrays,
matching the reference opener's path; the reference's internal streaming overload
is not an additional public capability of this port. Java ROOT/archive collections
have object overhead beyond C# compact arrays; identical memory/performance parity
is not claimed. Resolver lookups and per-locale file-ID enumeration never fetch.

## Downloaded figures and download-all

A build's encoded-key ledger defines total objects and encoded bytes. Default
measurement bounds are 4 tracked builds, 2 measurements at once and 4,096 keys per
presence call. Build identity and instance both matter: replacing an opened handle
starts a fresh measurement. A bitmap tracks stored rows; commits touching a row
win over stale answers from a measurement already in flight. Concurrent first calls
share a measurement, cancel independently and retry after all waiters abandon it.
Dropped commit notifications trigger remeasurement. Changes from other processes
require explicit `refresh` or a new opened instance.

Download-all defaults to at most 4 jobs, 4,096 scan keys and fetch requests per batch,
a 1 GiB free-space reserve and 100 ms progress interval. Missing rows are ordered
by archive rank and offset using three primitive arrays (16 bytes per missing row),
then fetched at background priority. It never deletes stored objects. Turning a job
off keeps committed batches; running it again scans current presence. Terminal
results distinguish incomplete, canceled, disk-full, store-failed and needs-network
outcomes. The last 64 terminal results are kept for `stop` after retirement.

Progress callbacks run serially outside job/source locks and coalesce intermediate
snapshots. Stage changes and the final snapshot are retained; completion follows
delivery of the final snapshot. Callback failures do not stop work. Intermediate
stored counts include layer 2's in-batch progress; only the final result is exact.
Downloaded change handlers should be quick, particularly on the commit pump.
All I/O futures should be composed asynchronously by the UI; do not block the
JavaFX thread with `join()`.

## Remaining readiness work

Native Windows machine validation remains deferred. These components do not claim
application-level or release certification. Higher layers, JavaFX screens, desktop
packaging and broader large-real-data performance measurements remain separate.

The [resolver qualification](RESOLVER.md) distinguishes its internal stream helper
from public behavior: as in C#, metadata above the default 64 MiB whole-read
boundary is fetched through verified ranges into a bounded encoded array (up to
the configured 512 MiB cap). Supported large metadata is not refused merely for
crossing the whole-read boundary. Full encoded-array retention and Java collection
overhead remain explicit memory characteristics.
