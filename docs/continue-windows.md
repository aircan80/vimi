# Continue development on Windows

Use this guide for native validation of #24, #25 and #26. Work in native Windows
PowerShell, not WSL, so the tests exercise Windows locking, sharing and native
libraries. The baseline before these guides is main commit `724a957`. Always use
latest main and read `HANDOFF.md`, including any completed macOS work.

## Open the project

Install Git for Windows if needed. For a fresh workspace, clone into sibling folders:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\Development" | Out-Null
Set-Location "$env:USERPROFILE\Development"
git clone https://github.com/aircan80/vimi.git
git clone https://github.com/aircan80/vibeminer.git
Set-Location vibeminer
git checkout --detach 4fd92d2a36add50c9b82e1c92bf72706e8d0df2b
Set-Location ..\vimi
```

The reference commit matches CI. For existing checkouts, update a clean `vimi` main
with `git pull --ff-only`; preserve local changes and inspect the reference before
changing its checkout.

## Install the toolchain

Install a full architecture-matched Java 25 JDK. Microsoft's
[OpenJDK installation instructions](https://learn.microsoft.com/en-us/java/openjdk/install)
include this WinGet command:

```powershell
winget install --id Microsoft.OpenJDK.25 --exact
```

Open a new PowerShell window after installation. Check `JAVA_HOME` points to that
JDK and its `bin` directory is on PATH:

```powershell
$env:JAVA_HOME
java -version
javac -version
```

Download the matching Windows DLL archive from the [official SQLite downloads](https://www.sqlite.org/download.html).
Match the JDK architecture (x64 or ARM64). From `vimi`, create an ignored local folder:

```powershell
New-Item -ItemType Directory -Force .local\sqlite | Out-Null
```

Extract `sqlite3.dll` into `.local\sqlite`. Record the downloaded archive/version
and its published checksum when collecting validation evidence. Do not commit the
DLL or archive. The CI downloader supports x64 only; ARM64 needs its matching DLL.

Install the pinned .NET SDK using Microsoft's
[official installation script](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-install-script):

```powershell
$installScript = Join-Path $env:TEMP 'vimi-dotnet-install.ps1'
Invoke-WebRequest https://dot.net/v1/dotnet-install.ps1 -OutFile $installScript
$dotnetDirectory = [IO.Path]::GetFullPath((Join-Path $PWD '..\vibeminer\.local\toolchains\dotnet'))
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installScript `
  -Version 10.0.402 -InstallDir $dotnetDirectory
$dotnetExecutable = Join-Path $dotnetDirectory 'dotnet.exe'
Set-Location ..\vibeminer
& $dotnetExecutable build src\backend\VibeMiner.Objects\VibeMiner.Objects.csproj `
  --configuration Release -p:RestoreLockedMode=true -p:NuGetAudit=false
Set-Location ..\vimi
```

Keep Java, SQLite and .NET architectures aligned. Inspect installer failures before
continuing. The execution-policy option above applies only to the installer process.

## Run native checks

From `vimi`, resolve explicit paths and run focused cases, then full checks:

```powershell
$sqliteLibrary = (Resolve-Path .local\sqlite\sqlite3.dll).Path
$dotnetExecutable = (Resolve-Path ..\vibeminer\.local\toolchains\dotnet\dotnet.exe).Path

.\gradlew.bat :object-store:integrationTest `
  --tests '*WriterLockTest' --tests '*PackReaderCacheTest' `
  --tests '*ReadOnlyMediaTest' --tests '*SqliteLibraryTest' `
  "-PsqliteLibrary=$sqliteLibrary"

.\gradlew.bat :object-store:check :object-store:crashTest `
  :object-store:interopTest :object-store:assemble `
  "-PsqliteLibrary=$sqliteLibrary" "-Pdotnet=$dotnetExecutable"
```

The C# harness finds the reference at `..\vibeminer` relative to the `vimi`
checkout. A worktree in another folder needs a sibling directory junction to it
(`New-Item -ItemType Junction`); otherwise `buildCsharpInterop` fails with CS0246.

The first build downloads dependencies. Record counts and skips. Only the two
Unix lock-file mode cases in `WriterLockTest` should skip on Windows.
`ReadOnlyMediaTest` enforces read-only media with native ACLs: it prepends a deny
entry for the current user (write, append, attribute, delete) to each file and
directory of the test's own temporary store, checks that creating a file is
refused, and restores the original ACLs on every exit. It never touches other
directories. If a run is killed mid-test, the leftover temporary folder still
belongs to the user, who can remove the deny entry with `icacls`.
`SqliteLibraryTest` checks default-name `sqlite3.dll` startup by putting the
`-PsqliteLibrary` DLL's directory first on a child JVM's `PATH`. That is not a
pristine-machine installation test. See [native-sqlite.md](native-sqlite.md).

## Start the agent

Open the `vimi` root in the agent application and paste:

> Read AGENTS.md, HANDOFF.md and docs/continue-windows.md. We have switched to native
> Windows to continue #24, #25 and #26. Inspect current progress, including macOS
> results, and validate this machine's toolchain. Run focused and full native tests,
> investigate failures, and finish the Windows-specific media/permission evidence.
> Update issues and commit/push fixes after relevant checks. Merge using fast-forward
> or squash only. Keep both Git author and committer set to aircan80 with email
> 329744803+aircan80@users.noreply.github.com. Review published changes and logs for
> private information. Before finishing, follow this guide's return handoff steps.

## Return to Linux

Before leaving Windows, have the agent:

1. Update `HANDOFF.md` with the tested commit, OS/CPU architecture, tool versions,
   commands, results/counts/skips, fixes, unresolved failures and the precise next step.
2. Update #24/#25/#26 with reviewed evidence; keep incomplete criteria open.
3. Commit and push the handoff and fixes; integrate green work by fast-forward or
   squash. Record any remaining branch/PR in the handoff.
4. Confirm the working tree is clean and all intended commits are pushed. Review
   logs before publishing to remove private paths, account names, hostnames and secrets.

Back on Linux, tell the agent:

> I'm back from Windows. Fetch the remote, inspect local changes before updating,
> fast-forward main if clean, then read HANDOFF.md and the latest #24/#25/#26 updates.
> Reconcile any recorded branch/PR and continue from the documented next step.

Git and issues carry the state between sessions; no shared chat memory is required.
Do not force-reset local changes if a fast-forward is not possible.
