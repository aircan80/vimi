# object-store

The pack store: object bytes in packfiles with one SQLite index,
ownership catalog and build-status registry. The authoritative disk format is
[docs/object-store-v1.md](../docs/object-store-v1.md).

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
contracts without SQLite or disk stores. Gradle skips
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
| Documentation only | No tests unless a documented executable example changes |

The disk suite runs the shared contracts against the actual store, plus temporary
store tests for reads/ranges, history, cancellation, concurrency, corruption,
staging, recovery, rebuild and compaction. Functional-parity coverage includes
generated-store isolation, chunked BLTE resume, strict history UTF-8, index
validation, the 64 MiB generated limit, and encoded staging above 64 MiB. Tests never use profile/user data.

```sh
./gradlew :object-store:integrationTest
./gradlew check                 # all existing components' unit + integration tests
./gradlew check :object-store:crashTest  # all current suites
```

`test`, `check` and normal builds never start crash-test child processes.
The crash suite is opt in.
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
`object-store/build/libs/`. The Bash
scripts in `scripts/` delegate to these Gradle tasks; `scripts/check.sh` runs only
fast tests.

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
[verification measurements](../docs/verification-performance.md) for the opt-in benchmark.

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
spools moved-row metadata. See [maintenance bounds and benchmarks](../docs/maintenance-resources.md). Cancellation checks
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
integration, crash and (since removed, #274) Java/C# interoperability cases with zero skips, including
all read-only permission checks. Native Windows hardware validation is still open;
hosted Windows checks run. Clean-machine SQLite startup and final release-readiness review remain
open. Recovery tests simulate durable unindexed tails and torn bytes;
append, rebuild and compaction process-kill boundaries and deterministic I/O
failures are tested. Real power-cut testing has not been performed.

