# Object store storage review (#26)

Review date 2026-11-05, main `963fcfb`. It covers #26's last criterion: review the
documented gap list and attach platform, crash and resource-limit evidence before
the epic closes.

**Compatibility with the C# store is not a goal.** The owner decided on 2026-11-05
that the Java store may diverge from the C# reference. This review uses the pinned
reference (`4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`, `src/backend/VibeMiner.Objects`)
only as a well-tested point of comparison. A difference from it is not a defect in
itself. Each finding below is judged on its effect on vimi.

The documented list is the README "Current limits" and the former "Remaining
parity work" notes. This review checks each item and records further storage issues found
along the way. File references are to the Java `object-store` sources and, where
a comparison helps, to the C# reference.

## Evidence summary

| Evidence | Linux | macOS ARM64 (native) | Windows x64 (native) |
| --- | --- | --- | --- |
| Full object-store suite (unit + integration + crash + interop) | 361 local; unit and integration also in hosted CI | 361, 0 skips (`66d06c2`) | 453, 2 Unix-only skips (PR #258 head) |
| of which unit + integration | 261 | 261 | 353 |
| Process-kill crash (`crashTest`) | 59 | 59 | 59 |
| Read-only media (`ReadOnlyMediaTest`) | POSIX modes | POSIX modes | Native ACLs (PR #258, not yet merged) |
| Native SQLite tests (`SqliteLibraryTest`) | 5 | 5 | 6, default name via `PATH` with a control case (PR #258) |
| Resource bounds | regression suite + benchmark baseline | regression suite | regression suite + benchmark below |
| Java/C# interop (`interopTest`, record only) | 41 | 41 | 41 |

The crash and interop rows are part of the full-suite totals, not additions to them.
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
| Native Windows/macOS validation of locking, sharing and SQLite | **macOS resolved; Windows resolved once #258 merges** | Native macOS (2026-11-01) and native Windows (PR #258): writer-lock exclusion, leased pack handles surviving concurrent append and unlink (`PackReaderCacheTest`) |
| Read-only media validation (#25) | **Resolved once #258 merges** | All five `ReadOnlyMediaTest` cases run on Linux and macOS today, and on Windows with #258's ACL enforcement |
| Bounded maintenance (#19) and other resource stories (#7, #8, #18, #23) | **Resolved** | Closed stories; regression suite in `maintenance-resources.md`; benchmark above |
| SQLite lock waits keep a 5 s busy timeout | **Fine as is** | `NativeSqlite.BUSY_TIMEOUT_MILLIS = 5000`; the reference uses the same value |
| Rollback, commit and durability cleanup can't be canceled | **Fine, by design** | Durable outcomes must not depend on cancellation. Active SQLite work is interruptible through the progress callback |
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
close. A throwaway probe on native Windows measured the difference. Setup:
- the index of a freshly created store, with its writer still open (WAL mode, the
  `store` row, no object rows);
- connections opened through `NativeSqlite` with `sqlite3_open_v2` flags
  `SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_URI`, then a 5 s busy
  timeout, as `openReader` does;
- the query `PRAGMA user_version`;
- 2,000 iterations of each pattern, two rounds in one JVM.

| Pattern | µs per operation (two rounds of 2,000) |
| --- | ---: |
| Open, query, close (today) | 927 / 1,039 |
| Query on a kept-open connection | 5.4 / 7.0 |

An independent reproduction during review found the same ratio of about 100×.

At about 1 ms per open, 64 reads spend about 64 ms on opens alone. That is
roughly 40 % of the 0.13–0.18 s read phase above. Correctness is unaffected: a
fresh connection always sees the latest commit, and the per-open probe keeps the
#25 read-only fix. Suggested fix: a bounded idle-connection pool with a larger
`cache_size`. For comparison, the reference pools 8 connections with a 64 MiB cache
(`ObjectIndex.cs:90, 477-520`). The pool must keep the probe-on-open guard and close
pooled connections on store close, and on retirement after rebuild or scan.

**G2. Overflowing a read or open queue fails instead of waiting (behavior).**
Each store runs reads on an 8-thread pool with a 64-slot queue and `AbortPolicy`
(`Async.java:14-27`, `ObjectStore.java:57`). Request 73 and later in a burst
therefore fail with `OBJECT_STORE_LIMIT "Worker queue is full or closed"`
(`Async.java:45-47`). Opens use the static `OPENERS` pool (`ObjectStore.java:49`):
2 threads and a 64-slot queue, shared by the whole JVM, so the 67th concurrent open
fails.

No current GUI caller exceeds 72 concurrent reads on one store by itself.
`CachingObjectSource` is bounded at 36 by `StreamScheduler` (32 background plus 4
interactive, `SourceTypes.java:255-258`). The risks are:
- several consumers sharing one store's 8 + 64 pool, while long reads such as
  verify and scrub hold its threads;
- `PackGeneratedStore` passing reads through without its own bound;
- concurrent opens across the JVM hitting the shared `OPENERS` limit;
- `CachingObjectSource.copyShared` treating a shared-store `OBJECT_STORE_LIMIT` from
  `read` as a miss (`CachingObjectSource.java:1289-1295`). A full queue there
  silently turns into a CDN download;
- in the same method, `shared.has(...).join()` (`:1222`) not catching the limit at
  all, so the error surfaces from the copy path instead.

Options: let callers wait asynchronously within a bound, as writer admission does,
or keep failing fast but have callers tell a full queue apart from other limits.

## Other observations (no action needed now)

- **G3. No progress reporting for Scan, Verify and Scrub.** `StoreMaintenance` has
  no progress parameter. This becomes relevant only if the GUI runs long
  maintenance and wants a progress bar.
- **G4. Fixed commit delay and read-thread count.** Both are hard-coded
  (`PendingWrites.MAX_NANOS`, `ObjectStore.java:57`). Make them options only if a
  caller needs different values.
- **G5. No layered reader in `object-store`.** Layer 2 already covers it:
  `CachingObjectSource` reads the local store, then the shared store, then the CDN
  (`CachingObjectSource.java:47`, `:1221-1295`). Nothing is needed in layer 1.

**Checked and behaving correctly on Windows:**
- Pack readers share read, write and delete access, so the writer can append and
  compaction can delete while a read is in progress. Native Windows tests pass.
- Directory `fsync` is skipped on Windows, which journals file names itself
  (`Format.java:208-210`).
- The read-only immutable fallback requires an absent or empty WAL and checks folder
  writability on every platform (`ObjectStore.java:693-700`). An immutable index
  without the clean-close marker is rejected (`ObjectStore.java:515-517`,
  `immutable && candidate.clean != 1`).

## Conclusion for #26

- Platform, crash and resource-limit evidence is attached above. It all passes on
  Linux, macOS ARM64 and Windows x64. The Windows read-only and default-name SQLite
  evidence counts once #258 merges.
- No correctness problems remain on the documented list that this machine can
  test.
- G1 and G2 are product issues for the PM to track. G3–G5 need nothing now.
- Two items need the owner: clean-machine SQLite startup and real power-cut testing.
