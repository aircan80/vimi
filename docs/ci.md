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

The initial workflow runs on branch pushes and pull requests targeting `main`.
It also supports manual dispatch once present on the default branch. Each
platform continues independently if another fails. The aggregate `Unit tests`
check fails if any platform fails, is canceled or is unexpectedly skipped; this
is the stable check name to require in branch protection. Required-check settings
are recommendations until configured separately in the repository.

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
in #14 and #20. Affected-component selection and documentation-only skipping are
tracked in #15; the first unit baseline deliberately checks every branch change.
GitHub-hosted jobs are permitted on all three platforms. Before hands-on native
Windows/macOS development or debugging, notify the maintainer so development can
move to the appropriate machine.
