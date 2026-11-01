# vimi development handoff

## Agreed direction

- Continue approved roadmap work and merge it autonomously after relevant checks
  pass; no repeated merge approval is needed. Use fast-forward or squash only,
  never create merge commits.
  Ordinary merges/pulls are configured fast-forward-only. PR #28 was
  integrated by fast-forwarding main to 7004cd2 and pushing it. Future feature work
  uses branches based on that reviewed main baseline.

- vimi (short for VibeMiner) is a Java desktop port of `../vibeminer`.
- Use its **new seven-layer architecture** only; ignore the legacy C# architecture.
- JavaFX is the selected GUI toolkit. No CLI application initially.
- Target Windows, macOS, and Linux. Keep JavaFX dependencies in layer 7.
- Preserve separate interfaces at every layer boundary, with in-memory fakes and shared contract tests.
- Store I/O must be asynchronous and bounded so the GUI never blocks. Use `CompletableFuture` results, bounded worker executors, explicit cancellation, and JavaFX-thread dispatch only in the future GUI layer.

## Active task

Port the layer 1 object/data store from `../vibeminer/src/backend/VibeMiner.Objects`.
It must remain disk-compatible with the C# implementation. The authoritative specification is
`../vibeminer/docs/formats/object-store-v1.md`; a reference copy is in `docs/object-store-v1.md`.
Scope includes packs, the SQLite index, per-key verification, reads/ranges, puts/deletes,
history, staging, recovery, verification, scan/rebuild, compaction, and read-only access.
The higher-level generated-record store and network clients are separate work.

## Current progress

- A functioning initial Java layer 1 store now compiles and passes Linux tests.
- Role interfaces isolate readers, writers, history, staging, verification and maintenance.
  Pack encoding, SQLite, locking and recovery remain internal implementation details.
- Implemented the missing `Staging` and `Maintenance` orchestration: resume/commit,
  recovery, bounded-buffer integrity checking, merged replay, scan, verify and compaction.
- Fixed full verification to include identity-check failures.
- Added dependency-free Java tests, a memory fake, shared reader/writer/history contracts,
  and a C# interoperability harness referencing the original Release assembly.
- Added build/check scripts, formatting conventions, and README setup/use/limitations.
- Native SQLite uses the stable Java foreign-function API (the build targets Java 25), with optional
  `-Dvibeminer.sqlite.library=/absolute/library/path` and native access enabled.
- Unix writer locking uses `flock`, confirmed against the C# writer in both directions.

## Component build and focused tests

- `object-store/` is a Gradle library subproject with its own source, fixtures and tests.
- A checked-in Gradle 9.1.0 wrapper pins/verifies the distribution; the build requires
  a full Java 25 JDK, not just a runtime. JUnit 5.13.4 dependencies are locked.
- `./gradlew :object-store:test` runs fast format and in-memory contract tests only.
- `./gradlew :object-store:integrationTest` runs shared disk contracts and native/disk checks.
- `./gradlew :object-store:interopTest` explicitly opts into the C# harness.
- `./gradlew check` runs all existing components' unit and integration suites, without .NET.
- Test fixtures publish the memory fake and shared contracts for future component tests.
- Run tests for changed components and affected consumers; interface changes need consumer
  checks, persistence changes need integration checks, format changes need interoperability.
- `README.md` documents exact commands and paths. Existing scripts delegate to Gradle.

## Validation performed

- Gradle compiled the component with compiler lint warnings as errors and built JARs.
- Unit tests cover the published format example and shared in-memory contracts.
- Integration tests cover shared disk contracts, reads/ranges, history, cancellation,
  concurrent reads, corruption, staging, durable unindexed tails, truncation and rebuilds.
- Java/C# interoperability passed after relocating the harness into the component:
  bidirectional data/history exchange, rebuilds and writer exclusion.
- No user data was tested or changed. The sibling C# project was not modified.
- This machine's system Java lacks full JDK files. Validation uses a downloaded ARM64
  JDK at `/tmp/vimi-jdk25-arm64` and `GRADLE_USER_HOME=/tmp/vimi-gradle-home`.
  Set `JAVA_HOME` to a persistent full JDK for ordinary local builds.

## Functional parity milestone implementation

- Added generated-store contracts, chosen-key persistence/rebuild/delete, type isolation,
  limits and bidirectional C# generated-store exchange.
- Added chunked staging/resume, fragmented headers, corruption, budget, complete/incomplete
  commit and stale-handle contracts. Exercise the 64 MiB generated boundary and encoded
  staging above the whole-object limit on temporary stores.
- Fixed incomplete commits deleting resume data, broken-handle lifecycle, stale handles
  deleting replacement files, and missing/changed staged sources causing store-wide errors.
- Aligned invalid staging arguments with the reference. Target pack failures still fail
  at store level; source validation failures refuse only the staged object.
- Added per-key bounds/refusal precedence, index/footer/page checks, BLTE proof, strict
  history grammar/UTF-8, full identity verification and newer-header preflight coverage.
- The reusable memory fake now supports both store kinds, staged proof/resume, budgets,
  close/cancellation and history paging. Shared contracts run against memory and disk.
- Added whole/incremental Java/C# verification vectors and cross-language chunked resume.
- Enabled Gradle configuration cache after testing storage and reuse with all suites.
- This implementation covers GitHub stories #3, #4, #5 and #10. Linux validation
  passes 54 unit, integration and interoperability tests; native Windows/macOS
  validation remains in the platform milestone.

## Append crash-safety story implementation (#6)

- Added package-private, per-store write observation points with no public fault switch.
  Production payload writes are not split unless the test observer is installed.
- Added a child writer and bounded parent handshake/force-kill harness in test fixtures.
- `:object-store:crashTest` is opt in and covers partial payload, table/header completion,
  pack sync, uncommitted index changes and committed index changes for puts, deletes,
  history and staged copies. Recovery checks include dirty/clean markers, preservation,
  truncation/replay, repeated reopen, continued writes and a subsequent scan.
- Added bidirectional process-kill recovery against the original C# implementation.
  The harness uses its existing `VibeMiner.Objects.Testing` friend assembly name.
- Existing fast unit and default check commands do not launch crash workers or .NET.
- Rebuild/scan safety is covered by #11 and compaction safety by #12. Injected
  I/O failures remain story #13.
- Append crash-safety story #6 is implemented and validated with 99 passing tests
  across the component suites. Rebuild/compaction and injected I/O failure work follows.

## Rebuild/scan crash-safety story implementation (#11)

- Preserve the old main index until atomic replacement by the rebuilt index. Previously
  the old main file was deleted immediately before the move, creating an avoidable gap.
- Added per-instance internal maintenance probes, including a same-package open helper
  for observing rebuilds before open completes. There is no public fault switch.
- Added 12 child-process kill cases covering temporary-index rebuild creation, replay,
  checkpoint/replacement and live scan sealing, row replacement, commit and checkpoint.
- Added 11 integration cases for cancellation/exception rollback, retry, reader visibility,
  malformed/unlisted/foreign/newer packs and replacement failure preserving the main file.
- Added six C# recoveries of interrupted Java rebuilds; the existing reference source is
  unchanged. All 128 unit/integration/crash/interoperability cases pass on Linux.
- Stories #6 and #11 are committed locally as `e091a60` and `8311bf2`. These
  local commits have not been pushed. Keep updating GitHub issues with progress and evidence.

## Compaction crash-safety story implementation (#12)

- Added eight kill boundaries spanning target listing, partial copy, sync, uncommitted
  and durable switch, source deletion and final source-row cleanup.
- Copy verified payload blocks and CRCs in a single pass, preserving the original entry
  header/sequence. Track corrupt compaction candidates for the session and bound the
  number attempted by maxPacks, rather than counting only successful compactions.
- Added a bounded row retry when a source disappears between row lookup and file open.
  Deterministic tests cover both whole/range reads, before/after file acquisition.
- Cancellation before the switch deletes the target after closing handles and restores
  index metadata; cancellation after a completed one-pack request does not turn its
  durable success into a cancellation on an unrelated next candidate.
- Added 16 integration cases for cancellation, corruption/newer/torn sources, read races,
  FULL switch durability, byte-identical headers and tombstone/history preservation.
- Added 12 bidirectional Java/C# compaction recovery cases. The component now passes
  164 unit/integration/crash/interoperability cases on Linux.
- Completed implementation covers #12. Local commits remain pending an explicit push.
  Deterministic injected I/O failures are covered next by #13.

## Injected storage-failure story implementation (#13)

- Added a package-private per-store file adapter and per-connection SQL hooks. Tests
  inject write/sync/truncate/delete/replace and statement failures without global state
  or public fault switches. Page-count limits exercise real SQLite-full behavior in
  both rollback-journal and WAL modes without exhausting the host filesystem.
- Track SQLite transaction outcomes and use sqlite3_get_autocommit after failures.
  Confirmed rollbacks can cut tails; committed/unknown outcomes preserve pack bytes
  and compaction targets and stop writes until reopen. A failed write with uncertain
  outcome may be recovered as present, so callers retry by key.
- Failed rollback or index reload stops unsafe writes, fails queued writes predictably,
  and never sets the clean marker. Failed clean-close/checkpoint attempts clear the
  marker again when possible and always release ownership. Close drains on a virtual
  thread independently of the bounded opener pool.
- Preserve primary error codes when secondary cleanup fails. Normalize disk-full
  errors, including SQLite FULL and known filesystem space/quota messages.
- Added 42 integration cases and three C# recoveries of uncertain failed Java writes.
  Component validation: 24 unit, 94 integration, 51 crash and 40 interop cases (209 total)
  pass on Linux. Fast unit tests remain independent of native/disk/process/.NET tests.
- #13 completes the Crash safety milestone. Local commits remain pending explicit push.
  Next milestone: Asynchronous behavior (#16 backpressure, #17 SQLite cancellation,
  #21 group commits), following their dependencies. Native platform work requires
  notifying the user before switching to Windows/macOS; development is primarily Linux.

## Java review and cleanup

- Applied pinned Spotless/google-java-format AOSP formatting to production and tests:
  four spaces, explicit/unused-import cleanup, normal line breaks and control-flow braces.
- Added Checkstyle rules and `-Xlint:all -Werror` across all Java compilations. Only
  intentional FFM restricted calls are suppressed in NativeSqlite and WriterLock.
  Exception types declare serialVersionUID. Plugin/tool versions and dependencies
  are pinned/locked. Formatting/style checks participate in `check`, not fast `test`.
- Fixed malformed object-index row parsing so reads/Verify report corruption rather
  than leaking parsing exceptions; scrub detects and repairs the bad rows. Invalid
  cursor keys report OBJECT_STORE_INDEX_UNREADABLE. Added eight disk regressions.
- Key equality/order/hash avoid temporary index-byte arrays; two new unit cases cover
  unsigned ordering, hash compatibility, equality and ownership across byte boundaries.
- Hoisted the store-kind query out of the per-entry pack-accounting loop.
- Validation: 219 passing cases (26 unit, 102 integration, 51 crash, 40 interop), all
  formatting/style tasks and all compiler lint checks on Linux.
- Review implementation covers #27 and is committed before asynchronous work begins.
  Future asynchronous/performance and native-platform work remains tracked; the review
  does not complete those milestones.

## Asynchronous writer admission

- #16 uses one serial disk worker, 64 ready slots and 64 FIFO admission waiters.
  Retained active/ready/waiting input is capped at 512 MiB; excess waiter/payload
  budgets report OBJECT_STORE_LIMIT. Waiting uses no blocked admission threads.
- Token cancellation removes unaccepted requests and cancels their futures without
  disk work. Admitted requests retain existing per-operation cancellation behavior.
  A staged commit canceled before admission leaves its handle usable.
- Close rejects unaccepted waiters and drains accepted writes. User continuations
  run outside the writer and admission locks, including reentrant write/close calls.
- Added 11 fast unit cases and five disk cases for admission, budgets, cancellation,
  FIFO, close/draining, reentrant continuations and staged-input ownership.
- Validation: 235 cases (37 unit, 107 integration, 51 crash, 40 interop), formatting,
  Checkstyle, compiler lint and assembly on Linux. No Windows/macOS development yet.
- Next: #17, interrupt active SQLite work safely on cancellation. Group commits
  remain separate in #21. Commits remain local until a push is requested.

## Active SQLite cancellation (#17)

- Reader and writer operation scopes pass explicit tokens to NativeSqlite. A per-statement
  SQLite progress handler checks cancellation roughly every 1,000 VM instructions.
  SQLITE_INTERRUPT caused by the token becomes CancellationException.
- Callbacks run only on the connection's owning thread. They capture no native handle,
  unregister before their confined arena closes, and never unwind Java exceptions across
  native frames. Token cancellation never invokes a native API, so late cancellation
  cannot access a closed connection or interrupt another request.
- Rollback, COMMIT, synchronous-mode restoration, in-memory index reload and post-commit
  maintenance cleanup are uncancelable. Existing uncertain-outcome protections remain.
  Prepared statements finalize even when preparation fails or cancellation interrupts.
- Nine new disk/native cases cover long queries, explicit and automatic rollback,
  connection reuse/disposal, callback errors, public put/scan retry, reader isolation,
  close during cancellation and durable writes with late cancellation.
- Validation: 244 passing cases (37 unit, 116 integration, 51 crash, 40 interop),
  formatting, Checkstyle, compiler lint and assembly on Linux. Lock waiting still uses
  the five-second SQLite busy timeout; progress callbacks do not run during lock waits.
- Next story: #21 group commits. Native Windows/macOS work has not begun.

## Group commits (#21)

- PendingWrites owns writer-only metadata and shared durability futures; payloads live
  in the active pack, not a second memory buffer. Puts share groups across queued
  requests; duplicate keys and history documents resolve against pending entries.
- Groups close at 128 entries, 16 MiB of entry bytes, 250 ms after processing a request,
  commitNow, queue idle and required operation/pack-roll boundaries. Timing uses a
  monotonic clock with a deterministic package-private test seam.
- One pack sync precedes one NORMAL index transaction. Pack rolls retain FULL metadata
  transitions. Readers see only committed rows. Stored-object futures wait for all
  their groups and publish away from the writer thread.
- Cancellation skips objects before append; already-appended entries commit even after
  token cancellation. Public pre-append SQL cancellation remains interruptible. Delete
  closes previous puts, batches tombstones and commits at the request end. Staging and
  maintenance flush pending puts before running.
- Group errors fail every pending owner after rollback/truncate/sync; uncertain commits
  preserve bytes and stop writes as before. Close drains accepted requests and groups.
- Added three policy unit cases, fifteen disk cases and eight multi-entry process-kill
  cases. Existing failure, cancellation, crash and C# compatibility suites remain green.
- Validation: 270 cases (40 unit, 131 integration, 59 crash, 40 interop), formatting,
  Checkstyle, compiler lint and assembly on Linux. This completes Asynchronous behavior.
- CI foundation is on branch ci/foundation in a separate worktree; workflow publication
  authorization is resolved and hosted runs have executed. Main has not been pushed
  or merged. See the CI foundation/native handoff below.

## CI foundation and native macOS handoff

- Use branch `ci/foundation` for the next machine's work. Fetch origin and switch to
  that branch (tracking origin/ci/foundation on a fresh clone); main still has the
  older published baseline. Draft PR #28 contains the full reviewed storage/CI scope.
- For BOTH author and committer use name `aircan80` and email
  `329744803+aircan80@users.noreply.github.com` on every commit. Preserve the user's
  private email. All source, issues, workflow logs and artifacts are public.
- #9 unit jobs pass on hosted Linux, Windows and macOS. #15 selection is verified by
  real documentation-only skips and an intentional synthetic failing-unit branch;
  all required aggregates behave correctly. Synthetic branch changes are never merged.
- Initial #14 macOS integration failed opening/reopening WriterLock. The user supplied
  a native macOS ARM64 investigation archive. Its checksums match; the reported
  temporary correction passes 40 unit, 131 integration and 59 crash cases using
  JDK 26 with --release 25, outside Gradle. It did not run C# interoperation.
- Applied the focused fix: mark Unix open's mode argument with firstVariadicArg(2).
  This follows Java 25 FFM's variadic ABI contract. Added four native regressions:
  fresh POSIX owner read/write mode, reopen, held exclusion/reacquisition, and
  preserving an existing lock inode/content/permissions. POSIX-only checks are
  explicitly conditional on filesystem support; generic lock tests run on Windows.
- The raw archive/reports stay outside the repo: they contain private machine
  identifiers. Only a sanitized diagnosis and aggregate results are public.
- Existing malformed lock permissions need manual owner read/write restoration with
  all writers stopped; never delete/replace an existing lock inode. See docs/ci.md.
- Fix b673fb9 passes Linux Java 25 validation: 274 cases (40 unit, 135 integration,
  59 crash, 40 interop), formatting, Checkstyle, compiler lint and assembly. Hosted
  push/PR unit and native integration jobs now pass on all three platforms. Windows
  skips two POSIX-only metadata checks; generic lock checks run on all platforms.
  Keep #24 open for the rest of native platform validation beyond this one defect.
- #20 Linux crash and C# interoperation jobs pass (59/40 cases). CI supplies an absolute
  dotnet executable path; the reference is a pinned public sparse checkout with locked
  Release restore/build. #14 and #20 are closed; CI foundation milestone is complete.
  PR #28 is ready for review; main and release publishing remain unchanged.
- Hosted evidence, temporary validation runs, stable check names and future expansion
  policy are recorded in docs/ci.md. No branch protection or release settings changed.

## Bounded read-day accounting (#7)

- ReadDays strictly caps concurrent admission at 65,536 unique keys, coalesces duplicate
  reads and drops excess notifications. Read paths do no SQLite work or writer admission.
- Successful whole/nonempty range reads count; Has, verification, empty ranges and
  read-only stores do not. Updates use the clock's Unix day at flush and never decrease
  persisted days. Negative epoch days use floor division.
- Updates share pending data commits and flush before maintenance, on a single coalesced
  30-second wakeup, and after accepted readers finish on close. The timer has at most
  one scheduled/in-flight flush per store, is canceled on close, and retries a saturated
  writer queue later. Timer scheduling is injectable for deterministic tests.
- SQL updates use bounded 256-key chunks. Confirmed metadata-only rollback drops that
  best-effort update without failing reads/close or stopping later writes; unresolved
  transaction outcomes retain the existing stop-writing protections.
- Scan preserves days. A fixed-clock C# round trip proves preservation/advancement in
  both runtimes and that an older Java clock cannot overwrite a newer C# day.
- Validation: 293 cases (44 unit, 149 integration, 59 crash, 41 interop), formatting,
  Checkstyle, compiler lint and assembly on Linux. Hosted checks run on the feature PR.
- Next performance/resource story: #18 pack-handle caching, then #8 session SHA caching,
  #23 parallel verification and #19 bounded maintenance resources.

## Leased pack-handle cache (#18)

- PackReaders owns a synchronized LRU of retained channels, default 64 and configurable
  with Options.packHandles(). The old Options constructors preserve the default.
  Acquiring a lease pins the entry before eviction can occur; only idle victims close.
- The retained map never exceeds capacity. If all entries are pinned, a temporary
  uncached lease closes on release. Retirement removes the cache entry immediately
  but active leases keep the old channel alive until their last release.
- Whole/range reads and verification use positional reads through leases. Missing
  files retain the existing one-row-lookup retry. Compaction invalidates a source
  before deletion and again afterward to catch stale-row acquisition during unlink.
- Store close drains accepted readers, then closes cached channels and releases the
  writer lock, preserving primary close errors if another cleanup also fails.
- Added 11 fast fake-handle unit cases and seven disk cases; existing four compaction
  read races now start with a warm cache. Tests cover LRU, all-pinned overflow,
  retirement/close races, failed opens/close cleanup, appends, leased delete-sharing,
  independent read-only caches and shutdown. No Windows/macOS native debugging yet.
- Validation: 311 passing cases (55 unit, 156 integration, 59 crash, 41 interop),
  formatting, Checkstyle, compiler lint and assembly on Linux. Platform CI follows.
- PR #29 was fast-forwarded into main at 3ff3edf. New changes are on
  storage/pack-handle-cache; integrate by fast-forward or squash only.
- Next: #8 session SHA caching, then #23 parallel verification and #19 maintenance
  resource bounds. Broader platform readiness is still tracked separately.

## Session SHA proofs (#8)

- SessionHashes caches successful whole-read SHA checks only for lengths up to 1 MiB.
  Every header and block CRC is still checked; ranges do not populate the set and
  Verify always performs its full integrity pass. Failed SHA checks never publish.
- Proof identity includes an opaque channel-incarnation token (not a channel reference),
  pack/offset, key, length and expected SHA bits. Moves, replacements/reopens and row
  changes cannot inherit a proof. Entries never retain native handles or payloads.
- At most 1,048,576 entries are retained, clearing before a new entry exceeds capacity.
  Corruption and scan also clear proofs. Opaque generation stamps prevent a hash that
  started before a clear from publishing afterward. Cold reads hash outside the short
  metadata lock and can duplicate work concurrently, as in the reference.
- Added five unit and ten integration cases for bounds, concurrent clear/publication,
  cache hits, mandatory CRCs, failed SHA, forced Verify, scan races, moved/replaced
  entries, changed expected digests, eligibility boundaries and reopen sessions.
- Validation: 326 cases (60 unit, 166 integration, 59 crash, 41 interop), formatting,
  Checkstyle, compiler lint and assembly on Linux; hosted checks follow.
- PR #30 was fast-forwarded into main at fa49f7f. Current branch: storage/session-sha-cache.
  Next work is #23 parallel verification and #19 bounded maintenance/benchmarks.

## Parallel verification (#23)

- Writer admission reserves FIFO slots and the existing 512 MiB payload budget before
  preparation starts. Only admitted requests prepare; canceled/unaccepted waiters
  clear their input references without CPU or disk work.
- Per-store verification workers use max(1, processors/2), with a bounded task queue.
  Identity verification, whole-object SHA and block tables move off caller/writer
  threads. History key creation also prepares on CPU workers after cheap shape checks;
  its reservation covers retained observation bytes and the generated owned copy.
- WriteQueue yields when the FIFO head is unprepared, wakes from completion, and drains
  accepted preparation on close before shutting down the CPU pool. A ready group flushes
  when the next request still needs CPU work. Results/refusal/cancellation order remain.
- Added seven unit and seven integration cases for prepared FIFO, cancellation,
  shutdown/failure wakeup, owned bytes, CPU bounds, out-of-order completion, group
  commit while the next preparation is delayed, and asynchronous history generation.
- Validation: 340 cases (67 unit, 173 integration, 59 crash, 41 interop), formatting,
  Checkstyle, compiler lint and assembly. Three-platform CI follows.
- Opt-in benchmark plumbing was delivered early by the #19 agent. 16 concurrent 4 MiB
  writes showed two active verifiers with four reported CPUs; every stored payload
  was reread and compared. Commands and illustrative numbers are in
  docs/verification-performance.md; normal tests never execute benchmarks.
- PR #31 was fast-forwarded to main at 6eaf1b8. Continue merging after green checks
  autonomously by fast-forward or squash. #19 is developing independently in the
  maintenance_bounds agent/worktree and will rebase onto this work before integration.

## Remaining parity work

This is not yet full reference parity. See README's Current limits. In particular:
- Bounded maintenance resources/benchmarks (#19) and broader platform readiness remain.
  Parallel verification, session SHA proofs, leased channels, group commits and
  read-day tracking are implemented (see above).
- Reader/opener queue overflow still fails with a limit error. Writer admission waits
  asynchronously within explicit request/payload bounds (see above).
- SQLite progress cancellation covers active VM work; lock waits retain the five-second
  busy timeout. Rollback/commit and durability cleanup remain uncancelable.
- Replay holds a file handle per pack; compaction holds moved-row metadata per source.
- Native-platform/read-only-media validation and optional broader fault-boundary
  expansion remain; process-kill and deterministic I/O failure suites now exist.
- Validate native Windows/macOS locking and SQLite packaging in platform CI.
- Native Windows/macOS validation remains; the Gradle wrapper includes Windows launchers.

## Workspace

The user renamed `~/Development/vj` to `~/Development/vimi` and initialized Git on `main`.
`.gitignore` exists. The user will create/connect the GitHub repository themselves.
Do not modify the existing `AGENTS.md` without a separate request.
