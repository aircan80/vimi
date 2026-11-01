# Continuous integration

The `Unit tests` workflow runs `:object-store:test` on GitHub-hosted
`ubuntu-24.04`, `windows-2025` and `macos-15` runners. Each job installs a full
Temurin Java 25 JDK matching the runner architecture. The fast task needs neither
native SQLite nor .NET nor the original C# checkout. Gradle compiler lint remains
enabled; formatting and native integration are separate checks.

Run the same task locally with:

```sh
./gradlew :object-store:test --no-daemon --console=plain
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

Currently only `object-store` exists. Any code, public-interface, test, fixture,
Gradle, dependency, wrapper, configuration, workflow or unknown-path change selects
its unit tests on all three platforms. Markdown/reStructuredText, root license
notices and documentation images skip them; executable/build/workflow directories
always select tests regardless of file extension. Mixed documentation and code
changes select tests. Manual dispatch always selects tests; unavailable comparison
history conservatively selects tests. Renames/deletions are included in comparison.
The workflow itself has no path filter, so required checks never remain pending
because GitHub skipped the entire workflow.

When another component is introduced, add its root and consumer relationships to
the selector before making selection narrower. Public-interface and shared-fixture
changes must select affected consumers too; shared build/wrapper/workflow changes
must continue to select all components. There are no other component consumers yet.

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
Native integration, crash recovery and C# compatibility are tracked independently
in #14 and #20. Three-platform unit jobs and affected-component selection implement
#9 and #15 respectively.
GitHub-hosted jobs are permitted on all three platforms. Before hands-on native
Windows/macOS development or debugging, notify the maintainer so development can
move to the appropriate machine.

## Native integration jobs

The independent `Integration tests` workflow uses the same conservative component
selection and an always-running `Integration tests` aggregate check. Its platform
jobs run `:object-store:integrationTest` separately from the fast unit task and
retain independent `integration-reports-*` XML/HTML artifacts for seven days.
Every disk test uses temporary synthetic stores.

Ubuntu 24.04 uses an x64 JDK and the distribution's `libsqlite3-0` package. Windows
2025 uses an x64 JDK and the official SQLite 3.54.1 x64 DLL archive, verified against
SQLite's published SHA3-256 before extracting only `sqlite3.dll`. macOS 15 uses a
JDK matching its hosted runner architecture and Homebrew's SQLite library matching
that architecture. The native setup helper prints the actual platform, pointer
width, SQLite version and selected library path, then passes an absolute path via
`-PsqliteLibrary`. Missing libraries, unsupported architectures and checksum
failures stop the job instead of skipping its tests.

The CI library locations are dependencies of hosted test jobs, not an application
SQLite packaging policy. Native packaging/read-only-media and hands-on platform
validation remain separate storage stories. These workflows have not yet been
validated on hosted runners while workflow publication authorization is pending.
