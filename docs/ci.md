# Continuous integration

The `Unit tests` workflow runs `:object-store:test :generated-store:test :network-clients:test :object-access:test :builds:test :files:test :formats-assets:test :formats-tables:test :concepts-core:test :concepts-items:test :concepts-creatures:test :concepts-effects:test :gui:prototypeTest :gui:backendTest :gui:openingTest :gui:preparationTest :gui:localMetadataTest :gui:preparationStatusTest :gui:nativeWorkspaceTest :gui:overviewPresentationTest :gui:researchBrowserDataTest :gui:iconRequestsTest` on GitHub-hosted
`ubuntu-24.04`, `windows-2025` and `macos-15` runners. Each job installs a full
Temurin Java 25 JDK matching the runner architecture. The fast tasks don't need
native SQLite. Generated record tests use
the architecture-matched zstd library bundled in the locked zstd-jni dependency.
Gradle compiler lint remains
enabled; formatting and native integration are separate checks.

Run the same task locally with:

```sh
./gradlew :object-store:test :generated-store:test :network-clients:test :object-access:test :builds:test :files:test :formats-assets:test :formats-tables:test :concepts-core:test :concepts-items:test :concepts-creatures:test :concepts-effects:test :gui:prototypeTest :gui:backendTest :gui:openingTest :gui:preparationTest :gui:localMetadataTest :gui:preparationStatusTest :gui:nativeWorkspaceTest :gui:overviewPresentationTest :gui:researchBrowserDataTest :gui:iconRequestsTest --no-daemon --console=plain
```

Use `gradlew.bat` in a Windows terminal. Hosted jobs use the checked-in Unix
launcher through the runner's Bash shell, including Git Bash on Windows.

The workflow runs on branch pushes and pull requests targeting `main`. It also
supports manual dispatch once present on the default branch. Each platform
continues independently if another fails. The aggregate `Unit tests` check always
runs: it fails if change selection fails or a selected platform fails, is canceled
or is unexpectedly skipped. Documentation-only changes pass this aggregate with
the platform jobs deliberately skipped. Require only the stable `Unit tests`
check in branch protection, rather than the conditional platform job names.
Required-check settings are recommendations until configured separately.

Selection rules live in `scripts/ci/select_components.py`; verify them with:

```sh
python3 -m unittest discover -s scripts/ci -p '*_test.py'
```

The validation group contains `object-store`, its consumer `generated-store`,
and the independent `network-clients` component, plus layer 2 `object-access` and
its layer 3 consumer `builds`, layer 4 `files`, and layer 5 `formats-assets`/`formats-tables`, and layer 6 `concepts-core`, `concepts-items`, `concepts-creatures` and `concepts-effects`. The historical `object_store` selector output conservatively
selects all thirteen components. Any code, public-interface, test, fixture,
Gradle, dependency, wrapper, configuration, workflow or unknown-path change selects
all components on all three platforms. Markdown/reStructuredText, root license
notices and documentation images skip them; executable/build/workflow directories
always select tests regardless of file extension. Mixed documentation and code
changes select tests. Manual dispatch always selects tests; unavailable comparison
history conservatively selects tests. Renames/deletions are included in comparison.
The workflow itself has no path filter, so required checks never remain pending
because GitHub skipped the entire workflow.

When another component is introduced, add its root and consumer relationships to
the selector before making selection narrower. Public-interface and shared-fixture
changes must select affected consumers too; shared build/wrapper/workflow changes
must continue to select all components. The generated-store consumes object-store; object-access consumes object-store
and network-clients; builds consumes object-access; files consumes builds, object-access and generated-store;
formats-assets consumes Files and generated-store; formats-tables consumes Files and generated-store; concepts-core consumes formats-tables; items and creatures consume concepts-core and formats-assets; effects consumes concepts-core. Changes to any component currently run all thirteen.

Each platform retains JUnit XML and HTML test reports for seven days, including
failing test reports. Open the run's artifacts named `unit-reports-Linux`,
`unit-reports-Windows` or `unit-reports-macOS`. Build logs and reports are public;
test fixtures must contain synthetic data only. Workflow artifacts do not include
Gradle homes, environment dumps, credentials or local stores.

Actions are pinned to reviewed release commit hashes. Gradle wrapper validation
runs before executing the wrapper; its distribution is also SHA-256 pinned.
The workflow has only `contents: read`, checks out without persisting credentials,
uses ordinary `pull_request` events, and requests no repository secrets. Pull
requests restore but never write dependency/JDK caches. Trusted branch pushes
may write caches scoped by GitHub to their branch. Setup Java's Gradle cache keys
include runner OS/architecture and hashes of Gradle build scripts, dependency
lockfiles, wrapper configuration and Gradle properties.

The workflow does not publish artifacts as releases or modify source/branches.
Native integration and crash recovery are tracked independently
in #14 and #20. Three-platform unit jobs and affected-component selection implement
#9 and #15 respectively.
GitHub-hosted jobs are permitted on all three platforms. Before hands-on native
Windows/macOS development or debugging, notify the maintainer so development can
move to the appropriate machine.

## Native integration jobs

The independent `Integration tests` workflow uses the same conservative component
selection and an always-running `Integration tests` aggregate check. Its platform
jobs run `:object-store:integrationTest :generated-store:check :network-clients:check :object-access:check :builds:check :files:check :formats-assets:check :formats-tables:check :concepts-core:check :concepts-items:check :concepts-creatures:check :concepts-effects:check :gui:check :gui:installDist` separately from the
fast unit workflow and
retain independent `integration-reports-*` XML/HTML artifacts for seven days.
Every disk test uses temporary synthetic stores.

Ubuntu 24.04 uses an x64 JDK and the distribution's `libsqlite3-0` package. Windows
2025 uses an x64 JDK and the official SQLite 3.54.1 x64 DLL archive, verified against
SQLite's published SHA3-256 before extracting only `sqlite3.dll`. Hosted macOS 15
validation runs on ARM64, with an architecture-matched JDK and Homebrew SQLite. The native setup helper prints the actual platform, pointer
width, SQLite version and selected library path, then passes an absolute path via
`-PsqliteLibrary`. Missing libraries, unsupported architectures and checksum
failures stop the job instead of skipping its tests.

The CI library locations are dependencies of hosted test jobs, not an application
SQLite packaging policy. Native packaging/read-only-media and hands-on platform
validation remain separate storage stories. The initial macOS integration failure
was traced by a native ARM64 investigation to the variadic `open()` binding in
WriterLock (#24). The binding now marks the third argument as variadic, with
regressions for permissions, reopening and exclusion. Hosted Java 25
unit and integration validation now passes on all three platforms (see below).

If the faulty binding already created a lock file without owner read/write access,
correcting the call does not change that existing file's permissions. Stop all
writers, then restore owner read/write permission on that store's `writer.lock`
with `chmod u+rw /path/to/store/writer.lock`. Do not delete or replace the lock file:
locks belong to its inode. The library preserves existing file permissions and
never changes them automatically.

## Crash recovery job

`Storage crash recovery` (`.github/workflows/storage-crash.yml`) runs the
process-kill crash recovery suite, `:object-store:crashTest`, on Linux x64. Relevant
storage, build, workflow and unknown changes run it on pushes and pull requests;
documentation-only changes skip it while the aggregate gate completes. Manual
dispatch and a Monday 05:00 UTC schedule force it. It keeps a seven-day HTML/JUnit
artifact named `crash-reports-Linux`. JUnit failure output includes the existing
child-process failure diagnostics; test data remains temporary/synthetic.

C# compatibility is not a goal (owner decision, 2026-11-05), so there is no Java/C#
interoperation job. Until #274 it ran here with a sparse checkout of the C# reference
and the .NET SDK; the history below records those runs.

For storage changes, the recommended required aggregates are `Unit tests`,
`Integration tests` and `Storage crash recovery`. Each always runs and rejects
failed/canceled selection or selected tests; platform-job names are conditional
and should not be required individually. Branch protection itself is unchanged.
The workflows publish test reports only, never releases.

## Hosted validation evidence

Workflow publication authorization is resolved. Draft [PR #28](https://github.com/aircan80/vimi/pull/28)
contains the completed storage baseline and CI foundation, without merging main.

- [Unit push run](https://github.com/aircan80/vimi/actions/runs/41341340081) and
  [unit PR run](https://github.com/aircan80/vimi/actions/runs/41341346777) pass on all
  three platforms (40 unit cases per platform).
- [Corrected native integration run](https://github.com/aircan80/vimi/actions/runs/41343070437)
  passes on Linux, Windows and macOS. Linux/macOS run 135 cases; Windows explicitly
  skips the two POSIX-only lock-metadata checks and runs the remaining cases.
  The initial macOS writer-lock ABI defect is fixed in b673fb9 with four regressions.
- [Corrected storage compatibility run](https://github.com/aircan80/vimi/actions/runs/41343070401)
  passes 59 crash and 40 C# interoperation cases on Linux. Both push and pull-request
  runs pass. #14 and #20 are complete; the CI foundation milestone is closed.
- A temporary documentation-only branch produced successful
  [unit](https://github.com/aircan80/vimi/actions/runs/41341228034),
  [integration](https://github.com/aircan80/vimi/actions/runs/41341227994) and
  [compatibility](https://github.com/aircan80/vimi/actions/runs/41341228020) aggregate
  checks while expensive jobs were skipped.
- A separate synthetic unit failure on that temporary branch made all platform
  jobs and the [unit aggregate](https://github.com/aircan80/vimi/actions/runs/41341376398)
  fail. Its retained report has 41 cases and exactly one intentional failure.
  That experiment is not merged; its temporary branch is deleted after validation.

The unit positive/failure and compatibility artifacts were downloaded and inspected;
the expected XML/HTML reports are present, with synthetic data and no private local
paths or credential patterns found by the report privacy scan. CI helper tests also
cover 288 aggregate-status combinations. Branch protection and release publication
remain unchanged.

## Generated-store coverage

The generated-store module participates in the existing stable aggregate checks.
Unit tests run shared memory contracts and record-file behavior; integration jobs
run shared disk contracts, persistence, compaction, error mapping and the 64 MiB
object boundary, plus generated-store formatting/Checkstyle.
Native Windows validation remains deferred; hosted Windows jobs continue.

## Layer 1 network coverage

The network-clients fast suite runs pure routes/keys, table grammar, range planning,
admission/lifecycle tests and shared fake contracts. Integration runs the same
contracts against isolated loopback HTTP servers, plus TLS/HTTP2, exact range and
length checks, source bounds, signed-URL redaction, redirect policies, truncated
transfers, header/idle/total deadlines, cancellation, sink backpressure and source
concurrency. Tests never contact Blizzard or GitHub or require live credentials.
The existing stable aggregates and conservative selector include these checks;
network changes also retain the storage crash recovery gate.

## Layer 2 and layer 3 coverage

Unit jobs include object-access contracts/scheduler/fan-out/cancellation tests and
builds discovery/parsing/resolution/tracking tests. Integration jobs run both
modules' check tasks, including source/disk contracts and a composition-root
pipeline through actual storage, source coordination, catalog projection, build
resolution and offline reopen/reconstruction. Builds uses the same externally
supplied SQLite library; no second bundled native dependency is introduced.

Tests use temporary synthetic fixtures; they do not perform live service requests
or use user data.

## Layer 4 coverage

Files unit suites exercise independent fake/shared contracts, decoded ranges,
Salsa20 vectors, BLTE integrity/protected/wrong-key cases, lifecycle, bounded
admission, Ensure cancellation/progress, names/parser/index/delta behavior and
offline reconstruction. Integration includes the disk-backed Files pipeline with
real object/generated stores and names reconstruction after generated-index deletion.

## Layer 5 asset coverage

The assets module adds 80 unit cases and one disk pipeline to the existing gates.
The disk case follows real Files into BLP and model/SKIN parsing, generated image
persistence and offline reopen. Checked-in BLP/model/appearance/VMG1 goldens are
verified by the unit tests. All reports join the existing seven-day unit and
integration artifacts.

## Table format coverage

Table checks cover DB2 layouts/values, strict definitions and selection, generated
rows/metadata/identities, real/fake table contracts, queries and references, TACT
bootstrap, preload and lifecycle. The disk pipeline proves generated rows reopen
without their encoded DB2 source.

## Layer 6 concept coverage

All four concept modules participate in the thirteen-module unit and quality groups.
Core exercises bounded shared relation indexes, cancellation/retry, stale sources,
Unicode literal matching and exact query cursor framing. Items covers left joins,
facts/classification labels, bounded appearance/default selection, verified armor
material joins, model/icon provenance and accepted-operation cleanup. Creatures
covers its sixteen-field profile, display/model chains, actual model/SKIN/BLP
appearance paths, typed dependency visibility and resource lifetime. Effects covers
reviewed Retail/Classic links, physical numeric evidence, names and partial/protected
provenance. Core and Items also supply disk integration checks through real lower
stores; Creatures and Effects run their deterministic service and lifecycle suites
with quality checks in the integration group.

Hands-on Windows validation is recorded in `HANDOFF.md`; hosted Linux, Windows and
macOS unit/integration jobs continue.

The layer 7 GUI also runs its headless fixture/adapter checks in the unit group.
Integration checks build its platform-specific application distribution without
launching JavaFX windows. JavaFX dependencies remain isolated in `gui/`.

Preparation additionally runs a native authored end-to-end pipeline in
`:gui:preparationIntegrationTest` via `:gui:check`. It verifies metadata publication
before index acquisition, payload-free Files, protected-table accounting, retained
lookups and offline reopen without community/network dependencies.

GUI history coverage includes `:gui:preparationHistoryTest` in hosted unit checks.
Questie backend unit coverage runs `:questie:test`; native integration/quality
coverage runs `:questie:check :questie:installDist`. Its unit fixtures make no live
GitHub requests, and SQLite-dependent tests belong to `integrationTest`.
