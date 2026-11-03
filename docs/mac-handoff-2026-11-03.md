# Mac continuation checkpoint — 2026-11-03

## Historical checkpoint — superseded by Linux continuation

The user stayed on Linux. These recovery instructions preserve the state before
implementation resumed; do not follow the old incomplete-certificate prompt as
current work. Native issue certificates are now merged on main at `6eeaf5a`,
with independent implementation approval of `66700ad`. Current work is automatic
**inline** GPU previews and their remaining verification, as recorded at the top
of [HANDOFF.md](../HANDOFF.md). No Mac/data transfer is active. A future move to
macOS should use [continue-macos.md](continue-macos.md) against the current checkout.

## Transfer state (historical)

Main before this documentation checkpoint: `c6815467a62ef93e8973975844611d5cf1a0b61c`.
The checkout was clean after reboot. Session logs and committed branches were
inspected; no source changes were made during transfer preparation.

Items comparison PR #136 is merged at `2920ff0`. It had independent integration
reviews, 154 comparison tests, authored JavaFX checks, native SQLite/pack proof,
and a live Forever70707 self-comparison of 31,982 observed IDs. These are prior
session results, not tests rerun after reboot. Preserve partial source uncertainty,
uncertified membership, session-only results and effects/assets exclusions.

[Alpha.9](https://github.com/aircan80/vimi/releases/tag/v0.1.0-alpha.9)
was verified published at 2026-11-03 14:03:53 UTC, explicitly a prerelease,
with 18 uploaded assets across Linux x64/ARM64, macOS Intel/Apple Silicon and
Windows x64, including checksums and manifests. No new release is needed for
this documentation transfer.

## Then-unfinished work (historical)

Continue native text-issue certificates, under the existing Build Comparison #117
scope. Design approval is recorded on the integration branch. Implementation is
incomplete and has no final acceptance approval.

Preserved recovery branches and exact tips:

| Branch | Tip | Meaning |
| --- | --- | --- |
| `feature/native-issue-certificates` | `f42ac31` | Integration; proposal, approvals, captured receipt types |
| `feature/native-issue-capture` | `045d90c` | Original capture-side commit, already represented in integration |
| `design/native-issue-certificates` | `09533d5` | Reviewed design revision |
| `feature/native-issue-origin` | `197824c` | Design/approvals only; no saved producer implementation |
| `feature/native-issue-certificate-store` | `197824c` | Design/approvals only; no saved storage implementation |

The integration diff from main contains three files: the concrete proposal,
review approval record, and `CapturedRowsTypes.java` (108 additions, one deletion).
Do not treat branch names or old agent assignments as evidence of completed code.
Temporary `/tmp/vimi-*` worktrees disappeared during reboot. Their registrations
remain locally marked prunable; their committed branches survive. Unsaved edits,
local toolchains, scratch libraries and Gradle caches are not transferred by Git.
Do not rely on old session processes or agents still running.

## Original Mac resume instructions (historical)

For a fresh checkout:

```sh
git clone https://github.com/aircan80/vimi.git
cd vimi
```

For an existing checkout, inspect `git status` first and preserve local changes.
On a clean main:

```sh
git switch main
git pull --ff-only
git fetch origin
```

Read `AGENTS.md`, `HANDOFF.md`, this checkpoint and `docs/continue-macos.md`.
Use the latter for native Java 25, SQLite and pinned C# reference setup; its
older #24–#26 task prompt is optional native certification work, not the active
certificate implementation task. Do not copy Linux JDK or SQLite paths to macOS.

Create a fresh worktree from the remote integration branch:

```sh
git worktree add -b resume/native-issue-certificates ../vimi-native-issue-certificates origin/feature/native-issue-certificates
cd ../vimi-native-issue-certificates
```

If that destination or branch already exists, inspect it rather than overwriting.
Read `docs/comparison/native-issue-certificates-proposal.md` and the latest
`docs/comparison/review.md` here before editing. Audit the partial receipt change
against the design, then complete native producer/factory, generated graph
identity/metadata validation and capture boundary wiring with focused regressions.

Required evidence includes real reader → factory → store → reopen → capture;
actual scoped/unscoped graph identities; old/custom null channels remaining
UNKNOWN; invalid/truncated text issues preserved; unchanged C# goldens; and no
field-admission fingerprint changes from certificates alone. Certificates must
never certify complete membership. Comparison must not acquire, repair or migrate
old graphs. Do not normalize null issue lists globally.

Start with relevant component checks, then native integration:

```sh
./gradlew :formats-tables:check :concepts-comparison:check :gui:check \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib"
./gradlew :gui:nativeRawComparisonTest \
  "-PsqliteLibrary=$(brew --prefix sqlite)/lib/libsqlite3.dylib"
```

Run table C# interoperability with the pinned sibling reference and .NET SDK
as documented in README.md, then root `check assemble` and required hosted CI
before integration. Obtain independent implementation reviews before declaring
the reviewed certificate design complete. Merge using fast-forward or squash.
Keep author/committer `aircan80 <329744803+aircan80@users.noreply.github.com>`.
Continue prereleases after major features; keep them explicit prereleases.

Suggested prompt for the next agent:

> We moved from Linux after a reboot. Read AGENTS.md, HANDOFF.md and
> docs/mac-handoff-2026-11-03.md. Inspect the checkout and toolchain, recover the
> remote native certificate integration branch in a fresh worktree, and continue
> the incomplete implementation under its reviewed proposal. Prior test evidence
> is historical; validate the actual recovered implementation. Keep legacy/custom
> uncertainty and local-only comparison behavior. Preserve unrelated local work.

Windows installer hands-on acceptance #24–#26, additional race profiles/animation,
and remaining Build Comparison areas remain open. Consult the active roadmap for
scope; no blocking product question was recorded before reboot.
