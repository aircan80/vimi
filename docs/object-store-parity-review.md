# Object store parity-gap review (#26)

Review date 2026-11-05, main `b0a65be`, against the pinned C# reference
`4fd92d2a36add50c9b82e1c92bf72706e8d0df2b` (`src/backend/VibeMiner.Objects`).
It covers #26's last criterion: "Review the documented parity-gap list and attach
passing platform, compatibility, crash and resource-limit evidence before closing
the epic."

The documented gap list is README "Current limits" and HANDOFF "Remaining parity
work". This review checks each item against the reference source. It also records
gaps that the comparison found and the list did not name. File references are to
the Java `object-store` sources and to the C# reference.

## Evidence summary

| Evidence | Linux | macOS ARM64 (native) | Windows x64 (native) |
| --- | --- | --- | --- |
| Unit + disk integration | 361 local; hosted CI | 361 local, 0 skips (`66d06c2`) | 452 local, 2 Unix-only skips (PR #258) |
| Java/C# compatibility (`interopTest`) | 41 | 41 | 41 |
| Process-kill crash (`crashTest`) | 59 | 59 | 59 |
| Read-only media (`ReadOnlyMediaTest`) | POSIX modes | POSIX modes | Native ACLs (PR #258) |
| Native SQLite probes | 5 | 5 | 5, default name via `PATH` (PR #258) |
| Resource bounds | regression suite + benchmark baseline | regression suite | regression suite + benchmark below |

The case counts grew between runs as tests were added (361 → 452). Each run was
green at its own commit. Hosted CI runs unit and integration on all three
platforms, and Linux crash and compatibility on every PR.

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
gap G1 below.

## Items on the documented list

| Item | Status | Evidence |
| --- | --- | --- |
| Native Windows/macOS validation of locking, sharing and SQLite | **Resolved** | Native macOS (HANDOFF, 2026-11-01) and native Windows (PR #258): bidirectional Java/C# writer exclusion in `InteropTest`, leased pack handles surviving concurrent append and unlink in `PackReaderCacheTest` |
| Read-only media validation (#25) | **Resolved** | All five `ReadOnlyMediaTest` cases run on Linux, macOS and Windows |
| Bounded maintenance (#19) and other resource stories (#7, #8, #18, #23) | **Resolved** | Closed stories; regression suite in `maintenance-resources.md`; benchmark above |
| SQLite lock waits keep a 5 s busy timeout | **Parity** | Java `NativeSqlite.BUSY_TIMEOUT_MILLIS = 5000`; C# `busy_timeout=5000` (`ObjectIndex.cs:90-91`) |
| Rollback, commit and durability cleanup are not cancelable | **Parity, by design** | Durable outcomes must not depend on cancellation. Java's progress-callback interruption of active SQLite work goes beyond the reference |
| Group commit at 128 entries / 16 MiB / 250 ms | **Parity** | `PendingWrites.java:11-13`; C# `ObjectStore.Writer.cs:431`, `Results.cs:80` |
| Replay channel cache, two compaction channels, 2 MiB temp cache target | **Accepted Java bound** | Documented in `maintenance-resources.md`; format and crash compatibility unaffected |
| Writer admission returns `OBJECT_STORE_LIMIT` past 64 waiters or 512 MiB | **Deliberate difference** | C# uses a bounded channel of 64, and further producers wait without limit (`ObjectStore.Writer.cs:150, 413`). Java bounds memory and fails fast instead. Proposed: accept as agreed scope |
| Reader and opener queues reject overflow | **Open difference (G2)** | See below |
| Real power-cut testing | **Not done; needs the owner** | Needs physical hardware or a VM with hard power-off. Process-kill and injected-I/O suites cover the software boundaries |
| Clean-machine SQLite startup | **Not done; needs a pristine machine** | Every native run used an existing development machine. Needs a fresh Windows VM, and ideally macOS and Linux too |

## Gaps found in this review

**G1. No reader connection pool (performance).** Every Java read opens a new
SQLite connection, runs one probe query, does its work and closes the connection
(`ObjectStore.reading` at `ObjectStore.java:805-820`, `openReader` at `:675-703`).
The reference keeps a pool of up to `ReadConnections` (default 8) idle connections
with a prepared lookup, and reads inline (`IndexReaders`, `ObjectIndex.cs:477-520`).
It also sets `cache_size=-65536` (64 MiB) on every connection (`ObjectIndex.cs:90`).
Java leaves SQLite's default (about 2 MiB). A probe on the Windows machine above
compared opening a read-only connection to an existing index, querying and closing
it with querying on one kept-open connection:

| Pattern | µs per operation (two rounds of 2,000) |
| --- | ---: |
| Open, query, close (Java today) | 927 / 1,039 |
| Query on a pooled connection | 5.4 / 7.0 |

At about 1 ms per open, 64 reads spend about 64 ms on opens alone. That is
roughly 40 % of the 0.13–0.18 s read phase above. Behavior is unaffected: a fresh
connection always sees the latest commit, and the per-open probe keeps the #25
read-only fix. Suggested follow-up: a bounded idle-connection pool, with
`cache_size` matched to the reference. It must keep the probe-on-open guard and
close pooled connections on store close, and on retirement after rebuild or scan.

**G2. Reader and opener overflow fails instead of waiting (behavior).**
Java runs reads on an 8-thread pool with a 64-slot queue and `AbortPolicy`
(`Async.java:14-27`). Request 73 and later in a burst therefore fail with
`OBJECT_STORE_LIMIT "Worker queue is full or closed"` (`Async.java:45-47`). The
reference waits on a semaphore (`ObjectIndex.cs:501`), so a burst only queues. A
GUI that fans out many reads, such as model or texture loading, can hit this. It
is already documented, but not as an agreed difference. Options: let callers wait
asynchronously within a bound, like writer admission does, or keep failing fast
and record that callers must limit their concurrency. Needs a decision from the
lead or the owner.

**G3. No progress reporting for Scan, Verify and Scrub (API).** The C# reference
takes an `IProgress<ScanProgress>` with entry and byte counts on `ScanAsync`,
`VerifyAsync` and `ScrubAsync` (`ObjectStore.Maintenance.cs:16, 215, 251`). Its
CLI uses it for scheduled scrubs. Java's `StoreMaintenance` has no progress
parameter. This matters once the GUI runs long maintenance. Suggested follow-up
when a caller needs it.

**G4. Fixed options (API, minor).** The reference makes `CommitDelay` (250 ms) and
`ReadConnections` (8) configurable (`Results.cs:80-81`). Java hard-codes both
(`PendingWrites.MAX_NANOS`, `ObjectStore.java:57`). The defaults match. Low
priority.

**G5. `LayeredObjectReader` (API, minor).** The reference has a public helper that
reads a profile store first and then a read-only shared store
(`ObjectStore.cs:399-420`). Java has no equivalent in `object-store`. It is
trivial to add when layer 2 needs it.

**Checked and at parity:**
- Pack readers share read, write and delete access. On Windows the reference passes
  `FileShare.ReadWrite | FileShare.Delete` (`Files.cs:45-47`); Java NIO shares all
  three by default. Native Windows tests pass.
- Directory `fsync` is skipped on Windows in both (`Files.cs:18`, `Format.java:208-210`).
- Read-only immutable fallback requires a clean-close marker and an absent or empty
  WAL in both (`ObjectIndex.cs:66-71`, `ObjectStore.cs:100-102`;
  `ObjectStore.java:693-700`). Java also checks folder writability on Windows, where
  the reference skips it. That is stricter and safe.

## Conclusion for #26

- Platform, compatibility, crash and resource-limit evidence is attached above. It
  is all passing on Linux, macOS ARM64 and Windows x64.
- The documented list has no correctness gaps left that this machine can test.
- To close the epic, the lead or the owner needs to decide:
  - whether to accept G2 and the writer-admission difference as agreed scope, or
    track G2 as a follow-up;
  - whether G1, G3, G4 and G5 become follow-up issues (G1 has a measured cost);
  - how to handle the two items that need the owner: clean-machine SQLite startup
    and real power-cut testing.
