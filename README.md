# vimi

Java desktop port of VibeMiner using its seven-layer architecture. The first
component is a layer 1 object-store library; the JavaFX GUI belongs in layer 7.

## Setup

Install a **full Java 25 JDK** for your machine's architecture and set `JAVA_HOME`
to its directory. A runtime alone is insufficient. The checked-in Gradle 9.1.0
wrapper downloads the pinned distribution on first use and verifies its SHA-256.
JUnit 5.13.4 test dependencies download from Maven Central; resolved versions are
recorded in `object-store/gradle.lockfile`. Production Java code has no external
Java dependencies. Later builds can run with `--offline` after these downloads.

Disk operations also need native SQLite: `libsqlite3.so.0` on Linux,
`libsqlite3.dylib` on macOS, or `sqlite3.dll` on Windows. Install it through your
platform package manager, or supply `-PsqliteLibrary=/absolute/path/to/library`
when running Gradle tests. Use a library matching your JDK's architecture.

On Windows use `gradlew.bat` wherever the examples below use `./gradlew`.
To review the code in IntelliJ IDEA, open this repository root as a Gradle project
and select the Java 25 JDK for both the project and Gradle JVM.

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

These tests model process termination, not a physical power cut. Compaction crash
boundaries and injected storage failures remain separate stories.

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

## Component boundaries and layout

Each substantial component is a Gradle subproject that produces its own library
JAR and owns its tests. Consumers depend on role interfaces. Pack encoding,
SQLite, locking and recovery are internal details of the object-store component.
Future components will declare project dependencies, for example
`implementation project(':object-store')`; fakes and shared contracts can be
consumed with `testImplementation testFixtures(project(':object-store'))`.

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

## Current limits

This is a functioning initial port, not full performance/behavior parity. Writes
currently commit each object individually; `commitNow` has no additional effect.
Queues reject overflow with `OBJECT_STORE_LIMIT` rather than waiting for room.
Read-day updates, the pack-handle cache, parallel caller verification and the
session SHA cache are not implemented. Scan's merge holds one open handle per
pack; compaction retains moved-row metadata per source pack. Cancellation checks
occur between entries/blocks/pages and do not interrupt an active SQLite statement.
The memory fake supports CDN/generated reader/writer/history and resumable staging
contracts without native or file dependencies.

Linux ARM64 native SQLite and cross-language writer locks have been exercised.
Windows and macOS need native-platform CI, including read-only media behavior and
SQLite packaging. Recovery tests simulate durable unindexed tails and torn bytes;
append and rebuild process-kill boundaries are tested, but compaction kill boundaries,
injected I/O failures and real power-cut testing remain to be completed. No higher layer, generated-record abstraction
or network client is included.
