# vimi development handoff

## Latest status — 2026-11-02: returning development to Linux

This section supersedes historical status below. The user is switching development
machines only: **do not copy, migrate or synchronize application/cache data**.
Leave the Mac's data untouched; use the Linux machine's own data directory.

### Committed application state

- `main` is pushed through `b40ce84` (PR #62, fast-forward merged). The user tried
  the updated app on macOS and reported that reopening is better. No application
  code remains uncommitted. The preexisting untracked root `md` is unrelated and
  must remain untouched.
- Faster cached-table reopen: `PreparedTablesCache.restore(identity, progress,
  token)` reads the completed inventory once instead of opening/checking every
  readable table. Existing cache format/identity is preserved, so this change
  does not force a new preparation. Identity covers build/configuration, locale,
  pinned definitions/catalog, reader policy and key fingerprints. A stale,
  missing or corrupt inventory falls back to preparation; individual requested
  table graphs retain normal validation/repair. The overload taking local-only
  `BuildTables` remains available for explicit verification. Restore emits only
  initial/final progress and the GUI says “cached tables restored”.
- Generic decoded table rows persist in generated RecordFile pack objects with a
  SQLite object index; they are not individual SQL tables. The Items grid has its
  own derived SQLite projection. File catalogs also persist, avoiding repeat full
  FileDataID enumeration. Key bootstrap and research lookup initialization still
  run on reopen; no promise that total reopening is instantaneous.
- PR #57 (`8373863`) completed the Items SQLite catalog: whole-catalog search,
  filtering and sorting happen before paging; matching totals and localized
  class/subclass labels are returned. Only the current page enters the GUI.
  Details and Layer 6 base stat calculations are separate requests; visible row
  icons load asynchronously without selection. Stats remain a bounded supported
  policy, not certified exact WoW Forever tooltip parity.
- PR #58 (`49008b9`) fixed blank selected dropdown labels, unreadable popup hover
  text and research toolbar alignment. PR #59 (`87be21b`) added durable historical
  preparation receipts and distinguished local table restoration from fresh
  processing. A historical readiness marker is not a live resource handle.
- PR #61 is merged through `7429c4c`: the independent Questie backend is complete,
  with official release checking/downloading, immutable originals, versioned
  derived SQLite data, localized queries, item source evidence, entity comparisons
  and CLI. No Questie GUI wiring or build-opening dependency exists yet. Read
  `questie/README.md` and `questie/UPSTREAM.md` before extending it; preserve upstream
  notices and the documented mixed historic rights caveat. No upstream data is
  bundled in Git.
- Root `architecture.md` now documents the seven layers/components with Mermaid,
  preparation/persistence and the separate Questie application path.

### Latest validation

- macOS 27.2 / ARM64, Homebrew OpenJDK 25.0.5.0.3, Gradle wrapper 9.1.0,
  native SQLite 3.54.1. Latest application validation at `b40ce84`:

  ```sh
  ./gradlew :formats-tables:check :gui:check :gui:installDist \
    -PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib
  ```

  `JAVA_HOME` pointed to the Homebrew full JDK 25; the native library override is
  Mac-specific and must not be reused on Linux. All tasks passed: table unit tests
  71/71 and native integration 1/1, no failures/errors/skips; GUI JavaExec harnesses
  also passed. Tests enforce one inventory read without per-table artifact reads,
  stale/corrupt inventory rejection, cancellation, disk reopen without encoded
  source and on-access repair after deleting a saved table manifest.
- The offline GUI pipeline restored its 1,270-entry fixture ledger in 5 ms. This
  is not a real-build total-opening benchmark.
- Hosted CI for the same application commit passed unit and integration suites
  on Linux, Windows and macOS, plus Linux crash recovery and Java/C# compatibility.
  One redundant PR macOS unit job remained unassigned with no steps; it was
  canceled after the identical commit's push macOS unit suite passed. No failing
  test was waived. PR #62 is merged and remote/local main matched at handoff.

### Resume on Linux

1. Read `AGENTS.md`, this latest status and `architecture.md`. Inspect local changes
   before updating; preserve Linux-local work. Fetch and fast-forward `main` if
   clean, without resetting or overwriting local changes. The handoff documentation
   commit follows `b40ce84` on remote main.
2. Use a full Linux JDK 25 and the Linux native SQLite library (`libsqlite3.so.0`
   by default); Gradle downloads the platform's JavaFX dependencies. Rebuild on
   Linux instead of reusing the Mac's installed distribution. No .NET/reference
   checkout is needed for normal Java checks or GUI execution.
3. In a Linux desktop session:

   ```sh
   ./gradlew :formats-tables:check :gui:check :gui:installDist
   ./gradlew :gui:run
   ```

   On headless Linux, GUI rendering checks need a virtual display, such as Xvfb;
   an interactive app needs a desktop display. Supply `-PsqliteLibrary` only if
   default native library discovery fails, using the actual Linux library path.
4. Use the Linux machine's existing/default data only. There is no data-transfer
   task. Builds not already prepared on Linux will legitimately need an initial
   preparation; cached builds there should use the new fast restore path.
5. Keep author/committer `aircan80 <329744803+aircan80@users.noreply.github.com>`.
   Continue using feature branches and fast-forward/squash integration only;
   user authorization to merge ready changes without another approval persists.

Remaining scope: hands-on Windows certification and packaging remain deferred;
Linux GUI smoke testing is the immediate continuation. Other grids still retain
some page-scoped filter/sort behavior; Creature.db2 is not a complete NPC catalog.
All-NPC enrichment, CDN-only talents, final item tooltip/scaling parity and Questie
GUI integration remain future work. Settings mockups live in a separate gui/design
worktree/session; do not overwrite or assume they have been integrated.

## Previous status — 2026-11-01 (historical)

- Durable preparation cache: Layer 4 PreparedFileCatalog persists locale-scoped
  immutable file records in generated RecordFile storage. Restore validates its
  build/ROOT/ENCODING/locale/version identity and stored segments without file
  enumeration, name lookups or a full presence scan. Presence is live per visible
  page and is not restored as current. Layer 5 PreparedTablesCache persists the
  completed known-table ledger and verifies readable local graphs before reuse.
  Identity includes definitions/catalog/policy and key fingerprints; explicit
  enrichment still reassesses tables. Native cold-store reopen regression forbids
  the full fileDataIds enumeration on round two.
- Synthetic native-disk benchmark, 1,200,000 records: initial catalog+close 13.794s,
  store reopen 0.005s, catalog restore/integrity validation 0.040s; zero repeat
  enumerations/resolutions. This excludes real build parsing and table/concept
  handle initialization and is not a certified live-build timing.
- Research query fix: Items/Creatures now accept up to 500 rows in Java and
  the browser requests 100. Both validators are exercised by GUI query tests.
  Previously a 100-row UI request exceeded the inherited 50-row service limit;
  failures now appear in the grid rather than looking like an empty catalog.
- Live research UI replaces temporary list/text inspectors with compact Files,
  Tables, Items and Creatures grids, real-data inspectors, table-record paging,
  source navigation and asynchronous selected item icons. Filters are explicitly
  page-scoped; file substring scans are bounded with continuation. Direct base
  attributes are projected in Layer 6. Retail allocations/scaling are not yet
  calculated; docs/item-attributes.md records that backend follow-up. Settings
  work remains independent/uncommitted in the other worktree.
- Overview restoration PR #54 was fast-forward merged at `e3f0e11` after all
  hosted checks passed, including Windows integration. Overview now uses the approved metric/explorer/retention layout with real
  readiness, progress and gate bindings. Unknown build byte size and build-local
  filename coverage remain explicitly unmeasured. Download-all is disabled until
  that workflow exists. Mock layout remains available unchanged.
- Closing a build keeps cached data and now shows Closed plus its last assessed
  readiness/locale for this app session. With no session assessment the catalog
  says Not opened / cache not assessed; reopening verifies cached inputs as before.

- Layers 1–6 remain implemented within documented supported-format bounds. Layer 7
  preparation integration PR #53 was fast-forward merged at `e1ab35f` after all
  hosted checks passed (Linux/macOS/Windows unit/integration and Linux crash/C#
  compatibility).
- Agreed pipeline: Metadata (configs/ENCODING/ROOT) publishes real Overview early;
  Files prepares indexes and ID/locale/size/local-presence facts without payload or
  type inspection; key bootstrap then known table processing/lookups runs in the
  background. Community names/types enrich independently and never gate readiness.
- Readiness is Not prepared / Metadata ready / Files ready / Tables ready, separate
  from activity. Tables ready means every known addressable table has an outcome,
  accepting missing/protected/partial/unsupported data explicitly. Unknown tables
  outside the configured catalog are outside scope. Tables/Items/Creatures share
  one initial gate. Media is always on demand.
- Later enrichment preserves the previous published table/concept workspace until
  atomic replacement. Reopen restores/verifies local inputs/generated artifacts;
  it does not trust a permanent prepared flag. Current providers are bundled pinned
  definitions/catalog, build Tact keys and optional community names. New community
  key/catalog sources remain future adapters, not a newly implemented fetch service.
- Preparation uses a payload-free Files listing, complete known-table ledger,
  isolated local-only table reads, retained no-media lookup warmups and data-only
  Item/Creature APIs. Real paged Files/Tables/Items/Creatures browsers are connected
  as a functional inspection path; polished tooltips/model rendering remain GUI work.
- Full root check/assemble/installDist and ten C# compatibility suites pass locally.
  New checks cover metadata-before-indexes, metadata-only Files, generation/cancel/
  enrichment commit, prepared reads not joining CDN flights, readiness/activity
  gates, protected tables, no media access and native offline reopen. Authored
  JavaFX scenes were rendered and inspected; screenshots are in docs/images.
  A live launch exposed a null empty-activity-list bug, fixed with a headless
  regression and verified by relaunch. The user is reviewing the running app.
- Up to four build/locale contexts are retained, with one preparation pipeline at a
  time. Names may continue independently. Close build releases its context; app
  close cancels/drains all jobs and retired resources. Phase progress is measured
  where totals exist and indeterminate otherwise; no invented overall percentage.
- Native hands-on Windows certification, real-corpus throughput and packaging remain
  deferred. The independent Settings design session in vimi-gui/gui/design is not
  modified. The preexisting untracked root md remains untouched.
- Earlier sections below are historical; current agreed behavior supersedes older
  strict all-readable Tables gating and names-as-Files-prerequisite proposals.

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

## Original storage task (completed)

Port the layer 1 object/data store from `../vibeminer/src/backend/VibeMiner.Objects`.
It must remain disk-compatible with the C# implementation. The authoritative specification is
`../vibeminer/docs/formats/object-store-v1.md`; a reference copy is in `docs/object-store-v1.md`.
Scope includes packs, the SQLite index, per-key verification, reads/ranges, puts/deletes,
history, staging, recovery, verification, scan/rebuild, compaction, and read-only access.
The higher-level generated-record store and network clients are separate work.

## Original baseline progress (historical)

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
- Broader platform readiness remains. Bounded maintenance resources/benchmarks (#19)
  use FILE temporary tables, fixed pack-ID bitmaps and 256-row pages; see
  docs/maintenance-resources.md.
  Parallel verification, session SHA proofs, leased channels, group commits and
  read-day tracking are implemented (see above).
- Reader/opener queue overflow still fails with a limit error. Writer admission waits
  asynchronously within explicit request/payload bounds (see above).
- SQLite progress cancellation covers active VM work; lock waits retain the five-second
  busy timeout. Rollback/commit and durability cleanup remain uncancelable.
- Replay retains the configured channel capacity plus one transient cold-open handle;
  compaction copies with two channels. SQLite temp cache is a 2 MiB target.
- Native-platform/read-only-media validation and optional broader fault-boundary
  expansion remain; process-kill and deterministic I/O failure suites now exist.
- Validate native Windows/macOS locking and SQLite packaging in platform CI.
- Native Windows/macOS validation remains; the Gradle wrapper includes Windows launchers.

## Workspace

The user renamed `~/Development/vj` to `~/Development/vimi` and initialized Git on `main`.
`.gitignore` exists. The user will create/connect the GitHub repository themselves.
Do not modify the existing `AGENTS.md` without a separate request.

## Bounded maintenance (#19)

Replay now spools merge heads into SQLite FILE temp tables and leases channels from
its configured cache. Compaction spools candidate ordering/move metadata; open and
validation page pack metadata in consistent read snapshots. Pack identifiers use
fixed bitmaps and directory enumeration streams. Diagnostic text explicitly marks
truncation; verification result key lists remain complete/output-sized.

Combined local validation after #23: 351 cases (67 unit, 184 integration, 59 crash,
41 interoperability), formatting, lint and assembly passed. Opt-in benchmark ran
three 64 × 64 KiB trials; measured environment, results and resource qualifications
are in docs/maintenance-resources.md. Benchmarks do not execute during check/test.

## Platform readiness: read-only media (#25)

- PR #33 was fast-forwarded into main at b87df71; #19 and the performance/resource
  milestone are complete. Its exact commit passed three-platform unit/integration
  CI plus Linux crash and compatibility checks.
- Read-only permission tests exposed delayed SQLite WAL initialization: opening the
  native handle can succeed before the first query fails to create sidecars.
  openReader now probes user_version and closes failed handles before considering
  the existing guarded immutable fallback.
- Five cases cover clean closed indexes with absent/empty WAL, unclean immutable
  rejection, inaccessible nonempty WAL rejection, and secondary reader visibility
  of later writes/deletes without taking writer ownership. Permission changes are
  confined to test directories and restored on every exit; cases skip for POSIX-less
  filesystems or privileged users that bypass permissions.
- #25 remains open for native platform validation under #24. Notify the user before
  hands-on Windows/macOS work so they can switch machines.
- Local validation: 356 cases (67 unit, 189 integration, 59 crash, 41 interoperability),
  formatting, Checkstyle, compiler lint and assembly passed. Hosted checks follow.

## Native SQLite readiness (#26)

- PR #34 passed three-platform CI and was fast-forwarded into main at 51b6e41.
  #25 remains open for native read-only-media validation under #24.
- SqliteLibrary preflights library loading and required C symbols before openCore
  creates store files. Failed loads are retained as ordinary, repeatable
  OBJECT_STORE_NATIVE_LIBRARY errors with actionable architecture/path guidance;
  NativeSqlite initialization is not attempted after a failed dependency preflight.
- Fresh JVM probes cover missing and invalid libraries, explicit override startup,
  and Unix default-name startup; a controlled lookup checks missing C symbols. Windows default lookup is not exercised
  because hosted jobs supply an explicit DLL; override checks require sqliteLibrary.
- docs/native-sqlite.md defines the current consumer-supplied native library strategy,
  upstream provenance, build requirements and remaining clean-machine/native evidence.
  No native binaries, JavaFX dependencies or launchers were added to the component.
- #24/#25/#26 remain open until native-machine and final release-readiness evidence
  is available. Notify the user before hands-on Windows/macOS work.
- Linux validation: 361 cases (67 unit, 194 integration, 59 crash, 41 interoperability),
  formatting, Checkstyle, compiler lint and assembly passed with an explicit native override.

## Moving between machines

- docs/continue-macos.md and docs/continue-windows.md contain setup, native checks,
  agent prompts and return-to-Linux steps. README links both guides.
- Before leaving a machine, update this file and issues with tested commit, platform,
  tool versions, results/skips, outstanding branch/PR and exact next step; commit/push.
- On arrival, inspect local changes, fetch and fast-forward clean main, then read this
  handoff and issue updates. Never reset local work to synchronize machines.

## Native macOS validation (2026-11-01)

- Validated checkout `66d06c20665924599da81b390469bf40631b7f98` on macOS 27.2,
  ARM64, with Homebrew OpenJDK 25.0.5.0.3 and SQLite 3.54.1.
- Java 25 and SQLite were already installed. Installed pinned ARM64 .NET SDK
  10.0.402 under the sibling reference's `.local/toolchains/dotnet`.
  Reference checkout `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b` built in
  Release with locked restore and zero warnings/errors.
- Used `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
  and `GRADLE_USER_HOME=/private/tmp/vimi-gradle-home`. Downloaded Gradle 9.1.0
  and locked dependencies; the temporary Gradle cache can be recreated.
- Focused WriterLockTest, PackReaderCacheTest, ReadOnlyMediaTest and
  SqliteLibraryTest passed. Full `:object-store:check :object-store:crashTest
  :object-store:interopTest :object-store:assemble` passed with
  `-PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib` and
  `-Pdotnet` pointing to the installed reference-local SDK executable.
- Results: 67 unit, 194 integration, 59 crash and 41 interoperability cases
  (361 total), zero skips/failures/errors. Formatting, Checkstyle, compiler lint
  and assembly passed. All five read-only-media cases ran without skips.
- No source fix was needed. Pre-existing untracked `md` was untouched.
  Fetched origin and confirmed main matches the tested commit. Published reviewed
  aggregate evidence to [#24](https://github.com/aircan80/vimi/issues/24#issuecomment-6564991949),
  [#25](https://github.com/aircan80/vimi/issues/25#issuecomment-6564992100) and
  [#26](https://github.com/aircan80/vimi/issues/26#issuecomment-6564992261).
  All remain open for outstanding Windows and final readiness criteria. This Mac
  was an existing development environment, not a pristine-machine startup test.
- The user subsequently deferred native Windows testing to a future session.
  Do not require a Windows machine switch before continuing development. Keep
  hosted Windows checks enabled and #24/#25 open for the deferred evidence.
- Object-store implementation is the completed development baseline; this is not
  a claim of completed platform/release certification. Clean-machine SQLite startup
  and final parity/readiness review remain tracked by #26. The higher-level
  generated-record abstraction and network clients are separate layer 1 work.
- Next: continue the remaining layer 1 components, starting by scoping the
  generated-record abstraction against `VibeMiner.Generated`, then network clients
  and layer 2 object access. Resume Windows checks with `docs/continue-windows.md`
  when the user chooses; no Windows validation is claimed in the meantime.

## Generated store and record files (#36)

- Added `generated-store/`, a separate layer 1 library porting the pinned C#
  `VibeMiner.Generated` component. It uses object-store as an implementation
  dependency; a standalone consumer compile passed with only the generated JAR
  on its compile classpath. No object-store types leak through the public API.
- `GeneratedStore`/`DiskGeneratedStore` provide caller-chosen SHA-256-prefix keys,
  immutable messages, error mapping, batch prevalidation/splitting, stats,
  compaction and asynchronous close. Default generated packs are 256 MiB.
- Ported VMRF format 1: checksummed/content-sized zstd blocks, compressed manifest,
  1024-entry ID chunks, content-addressed segments and manifest-last publication.
  Added pages, unsigned-ID and ceiling lookup, and asynchronous sequential cursors
  with bounded coalesced reads. Writers/cursors reject concurrent calls.
- Record CPU/I/O waits run on a shared bounded worker pool; continuations complete
  off those workers. Explicit cancellation, owned input arrays, queue overflow,
  writer failure/close races and queued-cancellation retry are covered.
- Added memory fake/shared disk contracts and four bidirectional C# exchange/lock
  cases. Local macOS ARM64: 33 unit, 14 integration and 4 interoperability cases
  (51 new cases), zero skips/failures/errors. Root check/assemble, formatting,
  Checkstyle, compiler lint and 13 CI-helper tests passed. Existing object-store
  unit/integration checks remain green; its implementation was unchanged.
- Pinned/locked zstd-jni 1.5.7-6 supplies native zstd binaries; upstream BSD notices
  are included in the generated JAR. SQLite still uses the existing external
  library strategy. See `generated-store/README.md` for exact commands and bounds.
- Hosted workflows now check both storage components with the existing conservative
  selector/aggregate names, including generated-store quality checks and Linux
  C# exchange. The pinned sparse reference includes VibeMiner.Generated.
- No reachability collector is introduced: shared segments must not be deleted
  without checking live manifests, matching the reference scope. Network clients
  and layer 2 remain separate work. Native Windows machine testing stays deferred.
- PR #37 passed push and PR workflows: unit and integration on Linux, Windows
  and macOS, plus Linux crash and both Java/C# compatibility suites. Evidence:
  https://github.com/aircan80/vimi/actions/runs/41359793753 (unit),
  https://github.com/aircan80/vimi/actions/runs/41359793759 (integration),
  https://github.com/aircan80/vimi/actions/runs/41359793826 (compatibility).
  Fast-forwarded main to `a0963173dfd919da63e60520c38a836adc4624f3` and pushed.
  The existing untracked `md` file remains untouched.
- Next component is C# `VibeMiner.Cdn`: routes/keys and pure range planning,
  bounded whole/streaming HTTP fetches and version-service replies, followed by
  WoWDBDefs/listfile clients. These layer 1 clients do not choose local vs network;
  that policy belongs to layer 2 ObjectAccess. Scope against the pinned seven-layer
  reference, with fakes/shared contracts and deterministic local HTTP fixtures.

## Layer 1 network primitives and parallel higher layers

- Added `network-clients/`, independent of storage: CDN keys/routes, range planning,
  version tables, and separate CDN/version/WoWDBDefs/listfile interfaces implemented
  by `NetworkClients`. Bounded per-source queues, explicit cancellation, exact
  ranges/lengths, streaming backpressure, provenance, redirect/encoding restrictions,
  and header/idle/total deadlines are implemented.
- Public `BlteChunkTable` in object-store exposes independent authenticated header
  and chunk proofs, including merged-range slices and lengths above stored-object
  limits. This C# primitive was previously only private verification logic in Java.
  Existing pack encoding and store implementation remain unchanged.
- Local macOS checks pass: 50 network unit, 44 loopback/TLS/HTTP2 integration and
  five standalone BLTE cases (99 new cases); root check/assemble and pinned quality
  checks pass. Fast network tests do not contact external services; test fixtures
  publish an independent memory fake and shared consumer contracts.
- Runtime HTTP/JSON dependencies and transitives are locked, with upstream notices.
  See `network-clients/README.md` for bounds and differences: one explicit attempt
  per host/hop instead of hidden reconnects, and five-minute idle pool eviction
  rather than absolute pooled-connection lifetime. Windows native testing remains
  deferred; hosted jobs still test all three platforms.
- Network PR #39 merged fast-forward at dfe3ea9 after all hosted workflows passed.
- User explicitly authorized parallel layer 2/3 agents and automatic ff-only or
  squash integration after checks. Isolated branches/worktrees:
  `parallel/object-access` (layer 2), `parallel/build-catalog` (layer 3 catalog,
  persistence, tracking/jobs), `parallel/build-resolver` (layer 3 format parsers,
  opening/resolution). Coordinate shared APIs before integration. These development branches are now integrated through PRs #42 and #43. Root owns
  shared Gradle/CI integration and supplied observation/range helpers to layer 2.

## Layer 2 integration

- Network PR #39 merged fast-forward at dfe3ea9 after all hosted checks passed.
- Layer 2 object-access now implements concurrent cached batches, verified ranges,
  observation adapters, history/commit feeds and bounded lifecycle/scheduling.
  Local checks pass: 79 unit + 12 disk cases, formatting, lint and assembly.
- See object-access/README.md for nullable metadata parity and throughput differences
  (sequential presence queries and individual durable delivery puts). Native Windows
  testing remains deferred; hosted Windows checks remain enabled.

## Layer 3 integration

- Parallel catalog and resolver slices are integrated in builds/: durable SQLite
  catalog, offline route reconstruction, v2 identities, build opening/resolution,
  TACT/BLTE/ENCODING/ROOT/archive parsers, shared cache/ledger, downloaded figures
  and bounded download jobs. Layer 3 calls layer 2 interfaces for object I/O.
- Local combined check/assemble passes, including 87 builds unit, 18 integration
  and 2 C# compatibility cases. Layer 2 adds 91 cases; the real pipeline covers
  disk observations through catalog/resolution and offline reconstruction.
- Original storage crash and both storage compatibility suites remain green.
  See builds/README.md and builds/RESOLVER.md for resource bounds and performance
  qualifications. Native Windows certification is deferred. Layers 4–7 remain.

## Completed parallel integration

- PR #42 merged fast-forward at a90ae1e; PR #43 merged fast-forward at fb25a13.
  Issues #40 and #41 are closed. All authors/committers use the requested GitHub
  noreply address. The preexisting untracked root md file remains untouched.
- All 709 local cases pass with no failures/errors/skips across five modules,
  including crash and C# exchange suites. Hosted layer 2 PR checks passed:
  unit41364172473, integration41364172472, compatibility41364172465.
- Hosted layer 3 branch checks passed: unit41364218024, integration41364218140,
  compatibility41364218043. Unit/integration matrices cover Linux, macOS and
  Windows; crash/reference compatibility run on Linux. Exact tested commits were
  fast-forwarded without merge commits.
- Layers 1–3 are implemented within the documented resource/performance bounds.
  Next implementation scope is layers 4–7. Native Windows testing and platform/
  release issues remain deferred; large real-data performance is not certified.

## Layer 4 Files integration (merged PR #46)

- Parallel Files and names implementations integrated on integration/files.
  All Files APIs are implemented, with independent memory fake/shared contracts,
  protected BLTE/Salsa20 reads, ranges/streams, format detection and Ensure jobs.
- Community names: consent, strict CSV, generated record-file indexes, portable
  VMLD delta chains, resumable staging and offline reconstruction. Healthy obsolete
  generated indexes are retained conservatively until reachability collection; see
  files/NAMES.md for this qualification and admission/resource bounds.
- Local root check/assemble passes. New Files evidence: 72 unit + 3 disk pipeline/recovery
  + 4 bidirectional C# exchange cases, no skips/failures. The pipeline reopens real
  object/generated stores and reconstructs names after deleting the index.
- Review fixed Ensure cancellation after a result-valued lower-layer response and
  bounded virtual-thread admission without blocking child operations on permits.
- Layer 5 is active in parallel/formats-tables and parallel/formats-assets; do not
  mark it complete yet. Tables review is correcting shared-generation cancellation
  ownership, .NET float text parity and malformed generated metadata repair.
  Assets review corrected the Files stream monitor deadlock and zero-progress reads;
  regression evidence is now included in the separate assets integration queue. Root owns shared CI/Gradle integration.
- Hands-on Windows testing remains deferred; hosted checks retain all platforms.

- Final Files review additionally corrected canonical-source history filtering,
  equal-timestamp part selection, duplicate release/delta document handling and
  bounded per-part/per-document retention. Regressions cover noncanonical URLs,
  tie cursor order, repeated malformed deltas and restart after multipart cancel
  (staged part reused, no premature history/index publication). All 79 Files cases
  pass locally including 4 C# exchange cases.

## Layer 5 asset integration (merged PR #47)

- `formats-assets/` implementation is complete within its pinned C# supported profiles:
  BLP2 decoding, Files adapters/generated caches, native M2/SKIN and dependencies,
  static geometry/material/appearance proofs and exact source-bound VMG1 artifacts.
- Integrated original asset commits 48e88b1, fa397cf, dd35c75 and 12d52ce, plus the
  disk asset pipeline from 4233820. The Files stream adapter deadlock and zero-progress
  loop have focused regressions. Independent review covered bindings/cache identities.
- Root lifecycle/scripts and all existing CI suites include assets and retain reports.
  The C# golden harness needs original Formats sources and the exact ModelFixtures.cs
  and AppearanceModels.cs fixtures; it does not require a full Formats reference build.
- Assets are queued separately from main so hosted CI overlaps final table regressions.
  Files PR #46 merged fast-forward at 147b71b after hosted unit 41366394997,
  integration 41366394998 and compatibility 41366395028 all passed. Keep issue #45 open: its table half remains active and is expected
  to complete separately after shared-generation cancellation, float-text parity,
  malformed metadata repair, fetch-budget and aggregate-evidence regressions pass.
- Hands-on Windows certification remains deferred; hosted jobs retain all platforms.

- Local asset integration validation passes root `check assemble`, the asset C# golden
  regeneration suite and all 15 CI helper/gate tests. Assets contribute 81 unit,
  one real disk pipeline and two C# golden/transport cases; all have zero failures/skips.

- Hosted assets checks exposed fixture portability only: Windows CRLF checkout of
  an identity text golden and Linux/macOS gzip OS-header differences. Text fixtures
  are pinned LF; expected text is normalized, while production identities remain LF.
  Gzip wrappers compare their verified decompressed original payload, and VMG1/VMIM
  retain byte-exact checks. Local full assets checks/interop pass after regressions.

## Layer 5 tables final integration (merged PR #48)

- Files PR #46 merged fast-forward at 147b71b; assets PR #47 merged at 3e501fb
  after corrected hosted unit 41367153727, integration 41367153827 and compatibility
  41367153790 all passed. Assets add 81 unit + 1 native pipeline + 2 interop cases.
- Tables own commits 52b9664/cabb846 integrated without duplicated Files dependencies.
  All pinned table component families are present: WDC3/4/5 readers, DBD/schema,
  bundled/fetched definitions, generated rows/identities, queries/relationships,
  TACT bootstrap, bounded background generation/protected-ID caches and preload jobs,
  with independent fixtures/shared contracts. See formats-tables/README.md.
- Review regressions cover shared-builder cancellation ownership, .NET float text
  (2,048 C# goldens), malformed/null generated metadata repair, handle fetch budgets,
  aggregate raw evidence, callback/provider cleanup and immutable job completion views.
- Full root check/assemble, crash and ALL interop suites pass on macOS ARM64:
  933 cases total, no failures/errors/skips. Module counts: object-store366,
  generated51, network94, access91, builds107, files79, assets84, tables61.
  Tables contribute 57 unit + 1 real native pipeline + 3 C# exchange/golden cases.
- The table pipeline deletes the encoded DB2 after reopening object/generated stores
  and still verifies identical generated identity/schema/rows/copies. Standalone javac
  public Files/Formats consumer compiles against only the eight project JARs. All
  formatting, Checkstyle, compiler warnings-as-errors and 15 CI helper checks pass.
- Tables PR #48 merged fast-forward at de5af49 after all hosted checks passed;
  issue #45 is closed. Native Windows certification, real-corpus performance
  and release packaging remain deferred. Layers 6–7 are future implementation scope.
  The separate gui/design worktree is the user's independent mock GUI session.

## Final hosted table evidence

- PR #48 unit: https://github.com/aircan80/vimi/actions/runs/41368067575
- PR #48 integration: https://github.com/aircan80/vimi/actions/runs/41368067540
- PR #48 compatibility: https://github.com/aircan80/vimi/actions/runs/41368067508
- Corresponding push runs also pass. All three parallel component PRs are merged;
  no Files/Formats integration work remains. Reference C# checkout is unchanged.

## Layer 6 game-concepts integration

Parallel implementation and independent review covered all Items, Creatures and
ItemEffects production files at C# reference `4fd92d2`; the current reference
checkout's later `1d6632e` has identical Concepts sources/contracts. The original
reference files were not modified. Each module README documents supported
profiles and backend versus renderer scope.

The shared relation index now refreshes schema/column selection when source
identity changes, including changes before the first page. Tests cover both
content-key and layout changes, reservation release, canceled owners/waiters and
partial-table absence. MemoryTables handles now observe reseeded tables and use
the C# fake identity framing without repeatedly hashing every row.

Independent review found and fixed accepted-future cancellation leaks, late-open
cleanup, terminal explicit-token checks and exposed mutable close futures.
Admission remains bounded and fail-fast; close drains accepted operations before
releasing lower resources. Item armor joins use only verified declared foreign
keys and retain raw component sections. Creature model references expose typed
lower-layer evidence. Effect layout-match warnings preserve definition provenance
even when later rows cannot be read.

Integration: PR #50, fast-forward merge `c537beb`; tracking issue #49 complete.
All push/PR aggregate checks were green before main advanced.

Native Windows certification and real-data performance remain
separate; layer 7 owns JavaFX composition, application settings/jobs and rendering.

## Real build opening integration

PR #52 fast-forward merged `2e6f3e2` after all hosted checks passed. This connects Open build to the existing BuildOpener
through app-owned BuildOpening. Exact regional CDN evidence is selected explicitly.
The indeterminate popup offers Hide/Cancel; hidden completion never forces
navigation. Success displays real authenticated metadata, not mock Overview data.
Application shutdown drains/cancels the operation before closing its dependencies.
No lower-layer progress API is added. Full root check/assemble and GUI installDist
pass; 8 opening contracts, 10 catalog adapter contracts, prototype checks and native
composition/reopen checks pass. Hosted Linux/macOS/Windows checks and Linux
crash/C# compatibility pass. US is preferred when observed; hidden activity
retains a spinner, elapsed time, View and cancellation state.

Read-only progress review recommends shared-flight phase snapshots with optional
measured counters (configs/index counts first, parser pages/chunks later), bounded
latest-only delivery and no arbitrary overall percentage. Layer 2 batch progress
exists but is object-completion-only; live byte telemetry would require further
L1/L2 work and retry/shared-transfer accounting. No progress API changes have been
implemented. Initial Overview readiness is separate from full ROOT/index opening.

## Current local browser and item work (2026-11-02)

PR #55 connected the approved Files/Tables/Items/Creatures browsers to live data.
PR #56 added durable prepared file catalogs and known-table outcome ledgers; all
hosted checks passed. PR #56 landed with a merge commit (2c4c783), contrary to the
requested FF/squash policy; use an actual local `merge --ff-only` plus push for the
next integration.

The item follow-up adds a shared bounded asynchronous derived SQLite connection,
versioned local item projection and typed full-catalog search/filter/sort/paging
with matching totals. Only page rows enter the GUI. Details/Layer 6 base attributes
are a separate call; row icons load without selection. Class/subclass labels use
selected-locale tables. Other grids still have their previous page-scoped dropdown
and sort behavior. NPC universe/catalog and CDN-only talent UI remain deferred;
Creature.db2 is not a complete NPC catalog.

Base allocation stats now have a bounded backend policy using RandPropPoints and
numeric GameTables. Missing inputs/scaling context remain explicit. This is not
certified WoW Forever 1.60.1.70707 final-tooltip parity; see docs/item-attributes.md.
Source facts/C# compatibility remain unchanged. Checks, real SQLite grid/reopen
integration, authored JavaFX UI review and items/tables/generated C# interop passed.
Native hands-on Windows certification/packaging remain deferred.


## Independent Questie backend (2026-11-02)

Added `:questie` without changing existing build/file/format/game-concept services.
It checks official QuestieDB releases, preserves immutable per-flavor original
packages plus raw metadata/notices, and prepares separate versioned SQLite data.
The public `QuestieRepository` facade and CLI support offline local version
listings, entity lookup, paged search, lightweight typed item sources and recorded
field comparison. No Questie GUI wiring or build-opening dependency is added.

Preparation is Java-only and represents static corrected baked data; runtime
character/season corrections remain unevaluated. Unknown references remain explicit.
Source integrity, cancellation/deadlines, drain/close, retained versions, locale
fallback and schema validation have deterministic/native coverage. Hosted CI includes
the new module. Root `check assemble` passed locally with the native SQLite override.

Live opt-in smoke downloaded official Forever v1.0.9 and v1.0.10 into a temporary
library, retained both, prepared 46,957 entities each, verified English plus nine
translations and independently decoded source counts. Rechecking/resyncing latest
reused original and prepared data without download/reimport. No upstream archive
is checked into Git. See questie/README.md and questie/UPSTREAM.md.
