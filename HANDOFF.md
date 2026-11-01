# vimi development handoff

## Agreed direction

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

- Gradle compiled the component with selected lint warnings as errors and built JARs.
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
- Rebuild/compaction crash points and injected I/O failures remain stories #11, #12, #13.
- Append crash-safety story #6 is implemented and validated with 99 passing tests
  across the component suites. Rebuild/compaction and injected I/O failure work follows.

## Remaining parity work

This is not yet full reference parity. See README's Current limits. In particular:
- Group commits, read-day accounting, pack-handle/session SHA caches and caller-side
  parallel verification remain. Current writes commit individually.
- Queue overflow currently fails with a limit error rather than asynchronously waiting.
- Cancellation does not interrupt running SQLite statements.
- Replay holds a file handle per pack; compaction holds moved-row metadata per source.
- Expand fault-injection/process-kill coverage and read-only media tests.
- Validate native Windows/macOS locking and SQLite packaging in platform CI.
- Native Windows/macOS validation remains; the Gradle wrapper includes Windows launchers.

## Workspace

The user renamed `~/Development/vj` to `~/Development/vimi` and initialized Git on `main`.
`.gitignore` exists. The user will create/connect the GitHub repository themselves.
Do not modify the existing `AGENTS.md` without a separate request.
