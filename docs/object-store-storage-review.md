# Object store storage review (#26)

Review date 2026-11-05, main `b0a65be`. It covers #26's last criterion: review the
documented gap list and attach platform, crash and resource-limit evidence before
the epic closes.

**Compatibility with the C# store is not a goal.** The owner decided on 2026-11-05
that the Java store may diverge from the C# reference. This review uses the pinned
reference (`4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`, `src/backend/VibeMiner.Objects`)
only as a well-tested point of comparison. A difference from it is not a defect in
itself. Each finding below is judged on its effect on vimi.

The documented list is README "Current limits" and HANDOFF "Remaining parity
work". This review checks each item and records further storage issues found
along the way. File references are to the Java `object-store` sources and, where
a comparison helps, to the C# reference.

## Evidence summary

| Evidence | Linux | macOS ARM64 (native) | Windows x64 (native) |
| --- | --- | --- | --- |
| Unit + disk integration | 361 local; hosted CI | 361 local, 0 skips (`66d06c2`) | 453 local, 2 Unix-only skips (PR #258) |
| Process-kill crash (`crashTest`) | 59 | 59 | 59 |
| Read-only media (`ReadOnlyMediaTest`) | POSIX modes | POSIX modes | Native ACLs (PR #258) |
| Native SQLite probes | 5 | 5 | 6, default name via `PATH` with a control case (PR #258) |
| Resource bounds | regression suite + benchmark baseline | regression suite | regression suite + benchmark below |
| Java/C# interop (`interopTest`, record only) | 41 | 41 | 41 |

The case counts grew between runs as tests were added (361 → 453). Each run was
green at its own commit. Hosted CI runs unit and integration on all three
platforms, and Linux crash and interop on every PR. Interop results are kept as a
record of where the formats still agree. They are not an acceptance requirement.

Windows resource benchmark (`:object-store:benchmark -PbenchmarkObjects=64
-PbenchmarkBytes=65536`), JDK 25.0.5.0.2, 24 CPUs, three JVM runs, MiB/s:

| Phase | Run 1 | Run 2 | Run 3 |
| --- | ---: | ---: | ---: |
| Write | 26.9 | 26.6 | 23.2 |
| Read | 25.0 | 30.1 | 22.1 |
| Rebuild | 59.1 | 75.2 | 58.7 |
| Compact | 110.2 | 127.8 | 120.4 |

Every run verified all payloads and observed a peak of 12 verification workers.
The machine differs from the Linux baseline in `maintenance-resources.md`, so the
two tables don't compare directly. The low Windows read figure is explained by
issue G1 below.

## Items on the documented list

| Item | Status | Evidence |
| --- | --- | --- |
| Native Windows/macOS validation of locking, sharing and SQLite | **Resolved** | Native macOS (HANDOFF, 2026-11-01) and native Windows (PR #258): writer-lock exclusion, leased pack handles surviving concurrent append and unlink (`PackReaderCacheTest`) |
| Read-only media validation (#25) | **Resolved** | All five `ReadOnlyMediaTest` cases run on Linux, macOS and Windows |
| Bounded maintenance (#19) and other resource stories (#7, #8, #18, #23) | **Resolved** | Closed stories; regression suite in `maintenance-resources.md`; benchmark above |
| SQLite lock waits keep a 5 s busy timeout | **Fine as is** | `NativeSqlite.BUSY_TIMEOUT_MILLIS = 5000`; the reference uses the same value |
| Rollback, commit and durability cleanup are not cancelable | **Fine, by design** | Durable outcomes must not depend on cancellation. Active SQLite work is interruptible through the progress callback |
| Group commit at 128 entries / 16 MiB / 250 ms | **Fine as is** | `PendingWrites.java:11-13` |
| Replay channel cache, two compaction channels, 2 MiB temp cache target | **Fine as is** | Documented bounds in `maintenance-resources.md` |
| Writer admission returns `OBJECT_STORE_LIMIT` past 64 waiters or 512 MiB | **Fine as is** | Bounds memory and fails fast. The reference instead lets producers wait without limit (`ObjectStore.Writer.cs:150, 413`); vimi doesn't need that |
| Reader and opener queues reject overflow | **Product issue (G2)** | See below |
| Real power-cut testing | **Not done; needs the owner** | Needs physical hardware or a VM with hard power-off. Process-kill and injected-I/O suites cover the software boundaries |
| Clean-machine SQLite startup | **Not done; needs a pristine machine** | Every native run used an existing development machine. Needs a fresh Windows VM, and ideally macOS and Linux too |

## Product issues found

The PM is filing G1 and G2 as issues.

**G1. Every read opens a new SQLite connection (performance).** Each Java read
opens a connection, runs one probe query, does its work and closes the connection
(`ObjectStore.reading` at `ObjectStore.java:805-820`, `openReader` at `:675-703`).
SQLite's page cache is left at its default of about 2 MiB, and it is lost on every
close. A probe on the Windows machine above compared opening a read-only
connection to an existing index, querying and closing it with querying on one
kept-open connection:

| Pattern | µs per operation (two rounds of 2,000) |
| --- | ---: |
| Open, query, close (today) | 927 / 1,039 |
| Query on a kept-open connection | 5.4 / 7.0 |

At about 1 ms per open, 64 reads spend about 64 ms on opens alone. That is
roughly 40 % of the 0.13–0.18 s read phase above. Correctness is unaffected: a
fresh connection always sees the latest commit, and the per-open probe keeps the
#25 read-only fix. Suggested fix: a bounded idle-connection pool with a larger
`cache_size`. For comparison, the reference pools 8 connections with a 64 MiB cache
(`ObjectIndex.cs:90, 477-520`). The pool must keep the probe-on-open guard and close
pooled connections on store close, and on retirement after rebuild or scan.

**G2. A burst of reads fails instead of queueing (behavior).** Reads run on an
8-thread pool with a 64-slot queue and `AbortPolicy` (`Async.java:14-27`). Request
73 and later in a burst therefore fail with `OBJECT_STORE_LIMIT "Worker queue is
full or closed"` (`Async.java:45-47`). The opener pool behaves the same way. A GUI
that fans out many reads, such as model or texture loading, can hit this as a
spurious error. Options: let callers wait asynchronously within a bound, as writer
admission does, or keep failing fast and document that callers must limit their
concurrency.

## Other observations (no action needed now)

- **G3. No progress reporting for Scan, Verify and Scrub.** `StoreMaintenance` has
  no progress parameter. This becomes relevant only if the GUI runs long
  maintenance and wants a progress bar.
- **G4. Fixed commit delay and read-thread count.** Both are hard-coded
  (`PendingWrites.MAX_NANOS`, `ObjectStore.java:57`). Make them options only if a
  caller needs different values.
- **G5. No layered reader.** Nothing in `object-store` reads a primary store and
  then a read-only shared store. Add it in layer 2 if vimi needs it.

**Checked and behaving correctly on Windows:**
- Pack readers share read, write and delete access, so the writer can append and
  compaction can delete while a read is in progress. Native Windows tests pass.
- Directory `fsync` is skipped on Windows, which journals file names itself
  (`Format.java:208-210`).
- The read-only immutable fallback requires a clean-close marker and an absent or
  empty WAL, and checks folder writability on every platform
  (`ObjectStore.java:693-700`).

## Conclusion for #26

- Platform, crash and resource-limit evidence is attached above. It all passes on
  Linux, macOS ARM64 and Windows x64.
- No correctness problems remain on the documented list that this machine can
  test.
- G1 and G2 are product issues for the PM to track. G3–G5 need nothing now.
- Two items need the owner: clean-machine SQLite startup and real power-cut testing.
