# Continue development on macOS

Use this guide for native validation of #24, #25 and #26. GitHub Actions already
runs unit/integration checks on macOS; local runs add native crash, interoperability,
read-only-media and debugging evidence. The baseline before these guides is main
commit `724a957`. Always use the latest main and read `HANDOFF.md` first.

## Open the project

For a fresh workspace, clone into sibling directories:

```sh
mkdir -p ~/Development
cd ~/Development
git clone https://github.com/aircan80/vimi.git
git clone https://github.com/aircan80/vibeminer.git
cd vibeminer
git checkout --detach 4fd92d2a36add50c9b82e1c92bf72706e8d0df2b
cd ../vimi
```

The reference commit matches CI. If these repositories already exist, keep local
changes safe: update a clean `vimi` main with `git pull --ff-only`, and have the
agent inspect the sibling reference before changing its checkout.

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

From `vimi`, install the reference's pinned SDK using Microsoft's
[official .NET installation script](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-install-script):

```sh
curl -fsSL https://dot.net/v1/dotnet-install.sh -o /tmp/vimi-dotnet-install.sh
bash /tmp/vimi-dotnet-install.sh --version 10.0.402 \
  --install-dir "$PWD/../vibeminer/.local/toolchains/dotnet"
cd ../vibeminer
.local/toolchains/dotnet/dotnet build \
  src/backend/VibeMiner.Objects/VibeMiner.Objects.csproj \
  --configuration Release -p:RestoreLockedMode=true -p:NuGetAudit=false
cd ../vimi
```

## Run native checks

First run focused cases, then the full component checks. The first build downloads
Gradle and dependencies; do not add `--offline` until they are cached.

```sh
./gradlew :object-store:integrationTest \
  --tests '*WriterLockTest' --tests '*PackReaderCacheTest' \
  --tests '*ReadOnlyMediaTest' --tests '*SqliteLibraryTest' \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib"

./gradlew :object-store:check :object-store:crashTest \
  :object-store:interopTest :object-store:assemble \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib" \
  "-Pdotnet=$PWD/../vibeminer/.local/toolchains/dotnet/dotnet"
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
