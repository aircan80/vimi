# Continue development on macOS

Use this guide for native validation of #24, #25 and #26. GitHub Actions already
runs unit/integration checks on macOS; local runs add native crash,
read-only-media and debugging evidence. The baseline before these guides is main
commit `724a957`. Always use the latest main and read `HANDOFF.md` first.

## Open the project

For a fresh workspace:

```sh
mkdir -p ~/Development
cd ~/Development
git clone https://github.com/aircan80/vimi.git
cd vimi
```

If the repository already exists, keep local changes safe: update a clean `vimi`
main with `git pull --ff-only`. No .NET SDK or C# reference checkout is needed:
C# compatibility is not a goal (owner decision, 2026-11-05).

## Install the toolchain

Use a native terminal and architecture-matched tools: ARM64 on Apple Silicon or
x64 on Intel. Install Homebrew first if needed, following [its official site](https://brew.sh).
Install [Java 25](https://formulae.brew.sh/formula/openjdk%4025) and SQLite:

```sh
brew install openjdk@25 sqlite
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
uname -m
```

### Select Java 25 for Gradle

`openjdk@25` is keg-only, so Homebrew does not link it into `PATH`. The wrapper runs
`$JAVA_HOME/bin/java` when `JAVA_HOME` is set and `java` from `PATH` otherwise; check
with `./gradlew --version` (the `Launcher JVM` line). If that is a newer JDK (for
example the unversioned `openjdk` formula at Java 26), Gradle 9.1 fails while
compiling build scripts:

```text
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 70
```

Gradle 9.1 runs on Java 25 at most. Choose one of these per-shell or per-command
options; none of them changes the system Java or Homebrew links:

```sh
# Option 1: set JAVA_HOME in each terminal (or in a shell profile you edit yourself).
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"

# Option 2: keep the current JAVA_HOME and run only the Gradle daemon on Java 25.
./gradlew help \
  "-Dorg.gradle.java.home=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

A `-Porg.gradle.java.home=...` project property does not work; use `-D`. You may
instead put `org.gradle.java.home=<path>` in your own `~/.gradle/gradle.properties`,
which applies to every Gradle build for your account, so only do that deliberately.
Do not add a machine-specific path to the repository `gradle.properties`.
Compilation itself already targets Java 25 through Gradle toolchains.

### Python for shader verification

`processResources` and `check` in `rendering-bgfx` verify committed shader binaries
with Python. The Gradle tasks run `python3` on macOS/Linux and `python` on Windows.
macOS Command Line Tools provide `/usr/bin/python3`; `brew install python` also
works. To use a specific interpreter, pass `-Ppython=<path-or-name>`:

```sh
python3 --version
./gradlew :rendering-bgfx:check -Ppython="$(command -v python3)"
```

## Run native checks

First run focused cases, then the full component checks. The first build downloads
Gradle and dependencies; do not add `--offline` until they are cached.

```sh
./gradlew :object-store:integrationTest \
  --tests '*WriterLockTest' --tests '*PackReaderCacheTest' \
  --tests '*ReadOnlyMediaTest' --tests '*SqliteLibraryTest' \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib"

./gradlew :object-store:check :object-store:crashTest :object-store:assemble \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib"
```

Record actual test counts and skips. Use an unprivileged account for permission
checks; a skip is not evidence that read-only media works. Tests use isolated
temporary stores. Native-library strategy is in [native-sqlite.md](native-sqlite.md).

## Start the agent

Open the `vimi` repository root in the agent application and paste:

> Read AGENTS.md, HANDOFF.md and docs/continue-macos.md. We have switched to native
> macOS to continue #24, #25 and #26. Inspect the current checkout and toolchain,
> then run focused and full native validation from this guide. Investigate failures,
> update issues, and commit/push fixes after relevant checks. Merge using fast-forward
> or squash only. Keep both Git author and committer set to aircan80 with email
> 329744803+aircan80@users.noreply.github.com. Review published changes and logs for
> private information. Before finishing, follow this guide's return handoff steps.

## Return to Linux

Before leaving the Mac, have the agent:

1. Update `HANDOFF.md` with the tested commit, OS/CPU architecture, tool versions,
   commands, results/counts/skips, fixes, unresolved failures and the precise next step.
2. Update #24/#25/#26 with reviewed evidence. Keep incomplete acceptance criteria open.
3. Commit and push the handoff and fixes; integrate green work by fast-forward or
   squash. If work is still on a branch or PR, record its name/number in the handoff.
4. Confirm the working tree is clean and all intended commits are pushed. Do not
   publish raw logs containing private paths, account names, hostnames or credentials.

Back on Linux, open the original workspace and tell the agent:

> I'm back from macOS. Fetch the remote, inspect local changes before updating,
> fast-forward main if clean, then read HANDOFF.md and the latest #24/#25/#26 updates.
> Reconcile any recorded branch/PR and continue from the documented next step.

Git and issues carry the state between sessions; no shared chat memory is required.
Do not force-reset local changes if a fast-forward is not possible.
