# vimi

Java desktop port of VibeMiner using its seven-layer architecture, with a JavaFX
interface for build discovery, preparation and research. See the
[architecture overview](architecture.md) for a high-level component table and
Mermaid diagram, including the independent Questie backend.
Download, preparation and local query commands are documented in the
[Questie backend guide](questie/README.md).

The [unified pack library](docs/unified-storage-services.md) stores object bytes in
packfiles with one SQLite index, ownership catalog and build-status registry.
The native app currently loads build metadata and reports persisted status/sizes;
table and item preparation are deferred. Existing stores are preserved during the
transition. Historical all-SQLite payload measurements are retained for reference.

## Setup

Switching development machines? Follow the [macOS continuation guide](docs/continue-macos.md)
or [Windows continuation guide](docs/continue-windows.md), including the return handoff steps.

Install a **full Java 25 JDK** for your machine's architecture and set `JAVA_HOME`
to its directory. A runtime alone is insufficient. The checked-in Gradle 9.1.0
wrapper downloads the pinned distribution on first use and verifies its SHA-256.
JUnit 5.13.4 test dependencies download from Maven Central; resolved versions are
recorded in `object-store/gradle.lockfile`. The object-store production code has no external Java dependencies; other modules
lock their HTTP, JSON or compression dependencies. Later builds can run with `--offline` after these downloads.

Disk operations also need native SQLite: `libsqlite3.so.0` on Linux,
`libsqlite3.dylib` on macOS, or `sqlite3.dll` on Windows. Install it through your
platform package manager, or supply `-PsqliteLibrary=/absolute/path/to/library`
when running Gradle tests. Use a library matching your JDK's architecture.
See [native SQLite distribution and diagnostics](docs/native-sqlite.md) for supported
build requirements, startup errors, provenance and platform validation.

On Windows use `gradlew.bat` wherever the examples below use `./gradlew`.
To review the code in IntelliJ IDEA, open this repository root as a Gradle project
and select the Java 25 JDK for both the project and Gradle JVM.

GitHub-hosted unit checks and their reports are described in [CI documentation](docs/ci.md).

## Java code quality

Java source and every test suite use four-space AOSP formatting through Spotless
8.10.4 and google-java-format 1.30.0. Imports are explicit and unused imports are
removed. Control-flow bodies use braces. Checkstyle 10.26.1 enforces braces,
one statement per line, modifier ordering, filenames and absence of empty statements.
Compiler checks use `-Xlint:all -Werror`; intentional FFM restricted calls are
suppressed only in the native adapters, with their native-access requirement documented.

```sh
./gradlew :object-store:spotlessApply          # format production and test Java
./gradlew :object-store:spotlessCheck          # check formatting without editing
./gradlew :object-store:checkstyleMain :object-store:test  # focused style + fast tests
./gradlew :object-store:check                  # all Java style, unit and disk checks
```

Formatting/style checks are incremental and included in `check`. The `test` task
still runs only the fast unit suite. The first build resolves the pinned build
plugins and quality tools automatically; their plugin/Checkstyle dependency
versions are recorded in the component's Gradle lockfiles.

In IntelliJ IDEA, use the Spotless Gradle tasks or the google-java-format plugin
with its AOSP style so editor formatting agrees with the build. Prefer explicit
imports and braces rather than applying every optional IDE inspection mechanically.

## Fast development checks

Run the changed component's fast tests by default:

```sh
./gradlew :object-store:test
# Narrow a check to one class or method:
./gradlew :object-store:test --tests '*FormatTest'
./gradlew :object-store:test --tests '*FormatTest.matchesPublishedFormatExample'
```

This suite checks the binary format and the in-memory reader/writer/history
contracts without SQLite, disk stores, .NET, or the sibling checkout. Gradle skips
unchanged compile and test tasks and reuses cached build configuration. Build and
configuration caches are enabled by default; use `--no-configuration-cache` to
diagnose future incompatible plugins or tasks. Use `--rerun-tasks` to deliberately run them again.
Tests produce HTML reports under `object-store/build/reports/tests/<task>/`.

Choose additional checks based on the change:

| Change | Checks |
| --- | --- |
| Object-store logic | `:object-store:test` |
| Persistence, locking, staging, recovery or maintenance | Also `:object-store:integrationTest` |
| Public interfaces or contracts | Also tests for components that consume those interfaces |
| Disk format or cross-language behavior | Also `:object-store:interopTest` |
| Documentation only | No tests unless a documented executable example changes |

The disk suite runs the shared contracts against the actual store, plus temporary
store tests for reads/ranges, history, cancellation, concurrency, corruption,
staging, recovery, rebuild and compaction. Functional-parity coverage includes
generated-store isolation, chunked BLTE resume, strict history UTF-8, index
validation, the 64 MiB generated limit, and encoded staging above 64 MiB. Tests never use profile/user data.

```sh
./gradlew :object-store:integrationTest
./gradlew check                 # all existing components' unit + integration tests
./gradlew check :object-store:crashTest :object-store:interopTest  # all current suites
```

`test`, `check` and normal builds never start .NET or crash-test child processes.
Crash and interoperability suites are opt in.
Gradle does not automatically select downstream tests from changed interfaces;
that impact selection is part of the development workflow until a CI policy is added.

## Crash recovery checks

```sh
./gradlew :object-store:crashTest
# Limit to one operation when developing a recovery fix:
./gradlew :object-store:crashTest --tests '*AppendCrashTest.putSurvivesOrTruncatesAtEachBoundary*'
```

The crash suite starts a child Java writer on an isolated temporary store, waits
for an explicit write-boundary handshake, and forcibly terminates it without a
clean shutdown. On Unix this is a process kill, not an exception that rolls back
inside the writer. Covered boundaries include partial payloads, completed headers,
pack fsync, uncommitted index updates and completed index commits. Tests exercise
puts, tombstones, history records and staged copies, then check recovery, clean-close
markers, repeated reopening, continued writes and index replay. Each child has
bounded handshake/termination waits and is cleaned up on failure.

Instrumentation is package-private and instance-scoped, with no public option or
environment/property switch. The executable worker and parent harness live only
in test fixtures. Production writes retain their normal unsplit payload-write path.

The interoperability suite additionally kills Java writers and recovers them in
C#, and kills C# writers and recovers them in Java. The C# harness uses the
reference assembly's existing friend-test access under the assembly name
`VibeMiner.Objects.Testing`; no reference source is changed.

The crash suite also terminates temporary-index rebuilds during creation, replay,
checkpoint and atomic replacement, and live scans before/after their commit. Disk
integration tests check cancellation/exception rollback, old-versus-new reader
visibility, repeatable scans and malformed/foreign/newer pack preflight. The old
main index is retained until the replacement is installed atomically. C# recovery
is checked after each Java rebuild termination point.

Compaction tests cover target listing/copy, fsync, the uncommitted/durable switch,
source deletion and final row cleanup. They check tombstone preservation, corrupt
and newer source handling, cancellation on both sides of the switch, and whole/range
read races before and after source-file acquisition. Copied blocks are checked in
one pass, corrupt compaction candidates are skipped for the session, and a reader
whose pack disappeared before open retries once against the moved row. Java and
C# recover each other's interrupted compactions.

Disk integration tests also inject write, sync, truncate, unlink, atomic-replace,
SQLite commit/rollback and close failures. They exercise real SQLite-full behavior
by capping a temporary database's page count in both rollback-journal and WAL modes;
they do not fill the host disk. File and SQL fault hooks are package-private and
per-instance, with no environment or public configuration switches.

Confirmed rollback permits safe tail truncation and retry. Errors after commit or
with an unknown transaction outcome preserve pack/compaction files and stop writes
until reopen. Failed rollback/reload leaves the clean-close marker unset; close
still drains accepted work and releases ownership independently of the opener pool.
When a write fails with an uncertain outcome, reopening may retain its valid entry,
so callers should retry by key. Cross-language checks cover C# recovery of these
failed Java writes.

These tests model injected I/O failures and process termination, not a physical
power cut.

## Build

```sh
./gradlew :object-store:assemble
```

The component's JAR, source JAR and reusable test-fixtures JAR are written under
`object-store/build/libs/`. There is no application entry point yet. The Bash
scripts in `scripts/` delegate to these Gradle tasks; `scripts/check.sh` runs only
fast tests, and `scripts/interop.sh` runs only interoperability checks.

## Java/C# compatibility checks

First build the original object-store project in a sibling `../vibeminer` checkout,
producing `src/backend/VibeMiner.Objects/bin/Release/net10.0/VibeMiner.Objects.dll`.
The C# harness references that assembly without modifying the sibling checkout.
It requires the .NET 10 SDK and packages pinned in its `packages.lock.json`.

```sh
./gradlew :object-store:interopTest
# Override the .NET executable and optionally use a local NuGet package source:
./gradlew :object-store:interopTest -Pdotnet=/absolute/path/to/dotnet
./gradlew :object-store:interopTest -PnugetSource=/absolute/path/to/nuget/packages
```

The default .NET executable is `../vibeminer/.local/toolchains/dotnet/dotnet`.
On Windows set `-Pdotnet` to the installed `dotnet.exe` path.
The harness checks bidirectional data/history and generated-store exchange,
rebuilds, writer exclusion, chunked staging resume in both directions, and
whole/incremental verification refusal vectors. C# build output stays in the ignored `bin/` and `obj/` directories
next to its project. The CLI home stays in ignored `.local/dotnet/`.

## Overview measurements

Live Overview measures community-name coverage and stored encoded payloads in the
background. Sharing is relative to canonical builds retained in this local library;
unindexed builds make the split provisional. Derived figures show the measured
portion (catalog/table graphs and current Items database), with incomplete coverage
for remaining asset/provider inventories. Whole-library file lengths include global,
unattributed and container bytes separately from per-build payload attribution.
See [storage accounting](accounting/README.md) for scopes, refresh behavior and tests.

## Component boundaries and layout

Each substantial component is a Gradle subproject that produces its own library
JAR and owns its tests. Consumers depend on role interfaces. Pack encoding,
SQLite, locking and recovery are internal details of the object-store component.
Future components will declare project dependencies, for example
`implementation project(':object-store')`; fakes and shared contracts can be
consumed with `testImplementation testFixtures(project(':object-store'))`.

- `polestar/`: independent GitHub-only forever-db snapshots, validation, preparation and indexed queries.
- `concepts-items/`: layer 6 item facts, appearances, models, icons and armor material provenance.
- `concepts-creatures/`: layer 6 creatures, display chains and static appearance evidence.
- `concepts-talents/`: layer 6 read-only Classic talent trees and prerequisite facts.
- `concepts-npcs/`: merged NPC identities, names, titles, levels and provider evidence.
- `concepts-quests/`: merged quest identities, titles, levels and provider evidence.
- `concepts-races/`: layer 6 race metadata, recorded class combinations, racial cards and static model projections.
- `concepts-professions/`: layer 6 build-local profession abilities, recipe results, reagent/tool slots and raw skill facts; see [Professions](docs/professions.md).
- `formats-audio/`: bounded isolated Vorbis-to-PCM voice decoding and persisted PCM codec.
- `formats-world/`: bounded WDT18 terrain inventory and split-root ADT18 height/holes parsing.
- `concepts-world/`: lazy build map/area metadata and terrain access through owned files.
- `rendering-world/`: perspective terrain camera, clipping and bounded CPU fallback rendering.
- `rendering-models/`: bounded CPU static-mesh/diffuse rendering, atlas composition and versioned derivatives, independent of JavaFX/GPU support.
- `concepts-effects/`: layer 6 item effects, reviewed rules, spell names and definition provenance.
- `concepts-core/`: shared layer 6 query/cursor rules and bounded reverse relation indexes.
- `formats-tables/`: layer 5 DB2/definitions, generated tables, query/relations and preload.
- `formats-assets/`: layer 5 BLP images, native M2/SKIN models and VMG1 artifacts.
- `files/`: layer 4 build/locale filesystem, decoding, Ensure and community names.
- `builds/`: layer 3 discovery/catalog, metadata opening/resolution and download tracking.
- `object-access/`: layer 2 local/shared/CDN coordination, observations and commit feeds.
- `network-clients/`: layer 1 HTTP clients, routes/range planning, fakes and loopback tests.
- `generated-store/`: generated-data wrapper and compressed record files.
- `object-store/src/main/java`: public store interfaces/types and implementation.
- `object-store/src/test/java`: fast unit and memory-contract tests.
- `object-store/src/testFixtures/java`: reusable memory fake, assertions and contracts.
- `object-store/src/integrationTest/java`: disk integration tests.
- `object-store/src/crashTest/java`: opt-in child-process termination tests.
- `object-store/src/interopTest/java`: cross-language compatibility and recovery tests.
- `object-store/src/interopTest/csharp`: original implementation's C# test harness.
- `docs/object-store-v1.md`: reference copy of the authoritative disk specification.
- `scripts/`: convenience wrappers around component Gradle tasks.

## Library use

```java
var store = ObjectStore.open(Path.of("/absolute/path/to/store")).join();
try {
    var result = store.read(key, Cancellation.NONE).join();
} finally {
    store.closeAsync().join();
}
```

Imports come from `com.vibeminer.store`. Launch consumers with
`--enable-native-access=ALL-UNNAMED` and optionally
`-Dvibeminer.sqlite.library=/absolute/path/to/library`.
The example waits for results for brevity; GUI callers should compose futures
and dispatch rendering to the JavaFX thread. Always close the store to checkpoint
its index and release the cross-process writer lock. Read-only access is exposed
through `ObjectStore.openReadOnly` and a separate interface without write methods.

Successful whole and nonempty range reads on writable stores enqueue best-effort
read-day notifications without waiting for writer admission or SQLite. Duplicate keys
coalesce; at most 65,536 distinct keys wait, with excess notifications dropped. The
writer persists Unix days during group commits, after a 30-second idle wakeup, or
on close after accepted readers finish. Days never decrease if the clock moves back.
Has, empty ranges, verification and read-only stores do not update tracking. Scan
preserves existing days; confirmed metadata-only SQL failures may drop notifications
without failing reads or stopping safe writes.

Pack read channels are cached and leased per read. `Options.packHandles()` defaults
to 64; the existing three-argument Options constructor keeps that default, and the
four-argument constructor accepts a positive capacity. Only idle least-recently-used
entries are evicted. When all slots are pinned, an uncached channel closes when its
lease ends. Compaction retires source entries without closing active leases; close
drains accepted readers before disposing cached channels. Retired active leases are
outside the retained-cache count and close at their last release. Supported-platform
integration tests exercise concurrent write/delete sharing with leased channels.

Whole reads of objects up to 1 MiB reuse successful session SHA proofs. Proofs are
bound to the open-channel incarnation, pack/offset, key, length and expected digest;
the set clears before exceeding 1,048,576 entries. Evicted/replaced channels and
moved entries earn new proofs. Every read still validates its header and block CRCs.
Range reads do not populate proofs, forced verification bypasses them, and corruption
or scan clears them. Generation stamps prevent an old in-flight hash from publishing
after a clear. Concurrent cold reads may duplicate SHA work outside the cache lock.

Put identity verification, SHA-256 and block-table preparation run on per-store CPU
workers, at most half the processors with a minimum of one. Admission remains FIFO
and one 512 MiB retained-input budget spans waiting, preparation and I/O; unaccepted
waiters do no CPU work. The serial writer yields until its head request is prepared
and flushes an existing group rather than waiting for another batch's CPU work.
Accepted preparations drain on close. History key generation also runs on CPU workers;
lightweight argument/shape checks stay on the caller. Refusals retain precedence over
cancellation, and per-object result order is preserved. See
[verification measurements](docs/verification-performance.md) for the opt-in benchmark.

## Generated data (layer 1)

The [`generated-store`](generated-store/README.md) module reuses the pack engine in
an independent `generated-v1/` store, exposing a separate interface for derived
bytes. It also ports C# VMRF record files: compressed segments, ordered records,
paging, ID lookup and sequential reads. It includes a memory fake, shared memory/disk
contracts and opt-in bidirectional C# compatibility tests. Root `check`/`assemble`
include both modules; `scripts/check.sh` runs both fast suites.

```sh
./gradlew :generated-store:test
./gradlew :generated-store:check :generated-store:assemble
```

See the module README for usage, bounds, compression provenance and the reference
build needed for `:generated-store:interopTest`.

## Layer 1 networking

[`network-clients/`](network-clients/README.md) ports the C# CDN, version-service,
WoWDBDefs and wow-listfile boundaries, plus route/table parsing and range planning.
It has bounded asynchronous queues, explicit cancellation, streaming backpressure,
strict response checks, an independent fake and shared local-HTTP contracts.
It has no storage dependency: cache/network selection and history recording belong
to the layer 2 object-access component.

```sh
./gradlew :network-clients:test
./gradlew :network-clients:check :network-clients:assemble
```

Root check/assemble and hosted unit/integration workflows include all layer 1
components. Network tests use isolated servers and never call public services.

## Object access and builds

[`object-access/`](object-access/README.md) is the layer 2 boundary for local,
shared-store and CDN acquisition, observation recording and commit/history access.
[`builds/`](builds/README.md) provides the layer 3 history-derived catalog, build
opening/resolution, downloaded figures and background download-all jobs. Production
layer 3 code uses layer 2 contracts; lower-layer values flow through that boundary.

```sh
./gradlew :object-access:test :builds:test
./gradlew :object-access:check :builds:check :builds:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
```

Their fakes/shared contracts are published as test fixtures. A real pipeline test
covers persisted observations, catalog projection, opening/resolution, commit
visibility and repeated offline reopen/rebuild without network requests. The
optional `:builds:interopTest` exchanges catalog caches with the pinned C# Builds
reference; see the module README for its Release-build prerequisite.

Layers 1–6 are implemented within the documented supported-format bounds. Layer 7 application wiring and JavaFX integration follow. Native Windows machine certification stays deferred.

## Current limits

This is a functioning initial port, not full performance/behavior parity. Writes
share group commits: 128 entries, 16 MiB of entry bytes, 250 ms checked after each
request, queue idle, or `commitNow` closes the group. Pack rolls, deletes, staging
and maintenance establish commit boundaries. The pack is synced before the index
transaction; stored-object futures complete only after commit, and readers cannot
see pending rows. Cancellation skips unappended objects; appended objects still
commit. A failed group rolls back its entire pending tail and fails its pending
owners. Pack metadata transitions retain FULL durability.
The serial writer supports 64 ready requests plus 64 asynchronous admission waiters.
Its 512 MiB retained-input budget includes active, ready and waiting payloads.
Exceeding either the waiter or payload budget returns `OBJECT_STORE_LIMIT`.
Admission is FIFO; cancellation removes an unaccepted waiter and cancels its future.
Once admitted, each operation applies its existing cancellation semantics (including
per-object `CANCELED` results). Close rejects unaccepted waiters and drains accepted
writes. Result continuations run outside the disk writer and admission locks.
Reader and opener queues still reject overflow rather than waiting for room.
 Replay uses a bounded channel cache and disk-backed merge heads; compaction
spools moved-row metadata. See [maintenance bounds and benchmarks](docs/maintenance-resources.md). Cancellation checks
occur between entries/blocks/pages. Active SQLite execution also checks the request's
cancellation token through a progress callback every approximately 1,000 VM instructions;
interruption completes with `CancellationException`. The callback is scoped to the
statement and removed before its native resources are released. Rollback, commit and
post-commit cleanup complete independently of cancellation, preserving durable outcomes.
SQLite lock waits retain the existing five-second busy timeout; the progress callback
does not run while SQLite is waiting on a lock.
The memory fake supports CDN/generated reader/writer/history and resumable staging
contracts without native or file dependencies.

Native integration passes in hosted CI on Linux, Windows and macOS; unit tests run
on all three platforms. Linux ARM64/x64 and cross-language writer locks have been
exercised. Linux permission tests cover clean read-only media (including absent/empty WAL),
rejection of unclean immutable indexes and inaccessible WAL commits, and live secondary
reader visibility. POSIX permission cases skip when the filesystem lacks POSIX permissions
or the process can bypass them. Native macOS ARM64 validation passed all 361 unit,
integration, crash and Java/C# interoperability cases with zero skips, including
all read-only permission checks. Native Windows validation is deferred by the
maintainer and does not block further component development; hosted Windows checks
continue. Clean-machine SQLite startup and final release-readiness review remain
open. Recovery tests simulate durable unindexed tails and torn bytes;
append, rebuild and compaction process-kill boundaries and deterministic I/O
failures are tested. Real power-cut testing has not been performed. The generated-store wrapper and compressed record files are implemented in
[`generated-store/`](generated-store/README.md). Layer 1 network primitives are
implemented in [`network-clients/`](network-clients/README.md). Layers 2 and 3
are implemented separately; Files and asset formats are implemented below. Table formats, layers 6–7 and application packaging remain active/future work.

## Files (layer 4)

[`files/`](files/README.md) binds file handles to a build and locale, providing
listing, stat, whole/range/seekable reads, BLTE/Salsa20 decoding with explicit
protected holes, format detection and cancellable Ensure jobs. Community names
use consent-controlled observations through layer 2 and a rebuildable generated
index; see [names, staging and portable deltas](files/NAMES.md).

```sh
./gradlew :files:test
./gradlew :files:check :files:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
./gradlew :files:interopTest -Pdotnet=/absolute/path/to/dotnet -PsqliteLibrary=/absolute/path/to/sqlite-library
```

The opt-in interop suite needs the pinned C# `VibeMiner.Files` project built in
Release and the reference checkout as a sibling (or `-PreferenceRoot=/path/to/vibeminer`).
It exchanges VMLD deltas and names record-file indexes in both directions.

## Image and model formats (layer 5)

[`formats-assets/`](formats-assets/README.md) provides bounded BLP2 RGBA decoding,
Files-backed image inspection and shared/scoped generated caches, native M2/SKIN
parsing, explicit dependency resolution, static geometry/material/appearance proofs,
and source-bound VMG1 artifacts. See the [pinned source inventory](formats-assets/PARITY.md)
for supported profiles and resource qualifications. Table formats live in their
separate `formats-tables/` module.

```sh
./gradlew :formats-assets:check :formats-assets:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
./gradlew :formats-assets:interopTest -PreferenceRoot=/path/to/vibeminer -Pdotnet=/absolute/path/to/dotnet
```

A disk pipeline validates real Files image decoding, generated caching and offline
reopen. Shared real/fake Files contracts also cover model/skin dependency resolution. The opt-in C# harness regenerates all
checked-in asset goldens using original sources from the pinned reference, including
its two model fixture sources; it does not build the full reference Formats project.
Root lifecycle tasks and hosted unit/integration/compatibility checks include assets.

## Table formats (layer 5)

[`formats-tables/`](formats-tables/README.md) provides the pinned WDC3/4/5 DB2 reader,
DBD/schema selection, bundled and optional fetched definitions, durable generated
tables, direct/query/filter/reference operations, TACT-key bootstrap and preload jobs.
Inputs come through Files; fetched definitions come through layer 2 observations.

```sh
./gradlew :formats-tables:check :formats-tables:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
./gradlew :formats-tables:interopTest -Pdotnet=/absolute/path/to/dotnet -PsqliteLibrary=/absolute/path/to/sqlite-library
```

C# exchange needs the pinned reference Formats project built in Release. The native
pipeline reopens stores, removes the encoded DB2 source, and verifies that schema,
rows, copies and identity still come from the durable generated table. Unsupported
formats remain explicit, matching the reference. Hands-on Windows certification
and large real-data throughput remain deferred.


## Game concepts (layer 6)

[`concepts-items/`](concepts-items/README.md),
[`concepts-creatures/`](concepts-creatures/README.md) and
[`concepts-effects/`](concepts-effects/README.md) expose separate asynchronous
build/locale handles over layer 5. Each publishes an independent in-memory fake
and shared consumer contracts. [`concepts-core/`](concepts-core/README.md) shares
bounded reverse relations, literal name matching and canonical bound cursors.

The modules cover the pinned C# Concepts production scope and complete verified
backend gaps: armor material joins, creature dependency visibility and read
coalescing, effect definition-match provenance, source-change retries and handle
cancellation/draining. Unknown appearance choices and unsupported rendering
bindings remain explicit. The services describe game data and static appearance
evidence; the future application supplies JavaFX controls and rendering.

```sh
./gradlew check assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
./gradlew :concepts-core:interopTest :concepts-items:interopTest :concepts-creatures:interopTest :concepts-effects:interopTest -Pdotnet=/absolute/path/to/dotnet -PreferenceRoot=/absolute/path/to/vibeminer
```

The opt-in compatibility checks regenerate synthetic goldens from the pinned
original C# sources. See each module README for reference build prerequisites,
admission and relation budgets, supported profiles and remaining application work.


## JavaFX application: Builds screen

[`gui/`](gui/README.md) is layer 7. Its first connected screen lists saved build
history, refreshes published metadata explicitly and displays regional provenance.
Real opening publishes Overview after metadata, then prepares Files and known Tables in the background. Tables, Items and Creatures share an initial readiness gate and use local prepared data; media is on demand. Run the approved fixture-based
design with `--args='--mock'`.

```sh
./gradlew :gui:check :gui:installDist
./gradlew :gui:run -PsqliteLibrary=/absolute/path/to/sqlite-library
```

Default startup is offline. Refresh persists observations in the application data
directory, documented in the GUI README; it does not download game content.

NPC and quest source precedence, snapshot policy and checks: [catalogue guide](docs/npcs-quests.md).
Mount and profession screen proposals: [reviewed design](docs/mounts-professions-design.md).

The [World explorer](docs/world.md) supports terrain selection, free flight and nearby tile streaming.

Installable preview builds and release validation are documented in [Prereleases](docs/prereleases.md).
