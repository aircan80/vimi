# Linux continuation handoff — 2026-11-04

## Final transfer checkpoint

User requested transfer now. Work is saved; roadmap is not complete. PR #187 is independently approved and unmerged, but GitHub now reports DIRTY after #162 merged: resolve against current main, rerun checks and retain explicit merge hold. Final source commit before this documentation is 378da982. No actual 70707 validation was possible.

Alpha.22 tag packaging run 41902679258 FAILED: four platforms passed; Windows failed Checkstyle while parsing WorldStreamSessionProof.java with java.lang.OutOfMemoryError: Java heap space in ANTLR PredictionContext.mergeArrays. Publication was skipped. Alpha.20 remains latest published. Diagnose build memory before retry; do not move/rewrite alpha.22 tag. Main merge is 666e6b1fe08837b49f5fcc3b97a1f13012b580ff. The earlier queued/publication-pending descriptions below are superseded by this checkpoint.

PR #161 remains open and held. Dungeons/raids and water are partially implemented on preserved branches, not delivered. All other queued work remains unstarted under current scope. No root edits are intentionally left uncommitted after this checkpoint. Read this handoff from fix/client-hotfix-overlay; main HANDOFF is historical.

## Authorization and priority

Finish urgent client hotfix support (#163 / draft PR #187), then hand off. Do not merge #187 or other held PRs without explicit user approval. Release candidate #162 alone is authorized to merge/publish when ready. Existing dungeon/raid and water work may resume in parallel; nothing else may start. Forever TraitTree talents #164 is next urgent but explicitly DO NOT START. Refresh/hotfix discovery #168 is also explicitly not started. Roadmap: GitHub issue #186, individual issues #163–185; character work also #104.

## Urgent hotfix implementation

Worktree `/home/aircan/.cache/vimi-worktrees/vimi-client-hotfix-integration`, branch `fix/client-hotfix-overlay`, draft https://github.com/aircan80/vimi/pull/187. Main remains separate at 74322493. Implementation includes bounded XFTH v9 parsing, exact build/source binding and payload hashes; natural-width schema-driven hotfix rows; bounded effective table overlays; explicit delete/protected/unknown policy; effective TactKey loading; private immutable local import and snapshot retirement; GUI import/disable and lazy talent source details. Cache disable retains imported copies. Data/privacy inventory documents this.

Conservative policy is documented in `hotfix-cache-review.md`: signed push ordering then physical ties; narrow same-record cached invalidation exception only for TactKey/BroadcastText; mixed region IDs unresolved. This is not a claim of complete DBCD/client policy parity. Physical DB2 counts, native evidence and prepared-only comparisons remain physical/base. `hotfixInfo` is retained-view metadata, so absence is uncertainty, not evidence of base origin. Decoder revision participates in configured preparation identity.

Read checkpoints: `hotfix-effective-tables-checkpoint.md`, `hotfix-flat-row-decoder-checkpoint.md`, `client-hotfix-store-checkpoint.md`, `hotfix-key-crypto-proof.md`, `hotfix-gui-integration-checkpoint.md`. Final independent effective-view, GUI and synthetic crypto review is approved by hotfix_cache_review; Java reviewer found no production blocker and supplied seven actual workspace lifecycle tests.

Validation: full local check/assemble/C# interop passed 478 tasks (`/home/aircan/.cache/vimi-tmp/hotfix-combined-full-check.log`). Subsequent actual JavaFX talent/source details plus workspace/store tests passed 99 tasks (`hotfix-workspace-ui-root-check.log`); final provenance/decoder identity changes passed 96 tasks (`hotfix-final-origin-check.log`). Seven workspace scenarios cover loaded/held snapshots, cancellation/drain, retry, adoption failure, sticky cleanup, foreign exception and invalid-version no-I/O. Synthetic actual encrypted BLTE/WDC5 data changes from protected to readable with the hotfix-only key. Specific reported build 70707 cache/spells remain UNVERIFIED: only a local 70450 cache was available for read-only container evidence. Do not claim the Feral examples or TraitTree issue fixed.

Independent review is complete. Final cleanup now preserves an earlier cancellation failure if accounting close also fails; focused lifecycle/source tests passed 96 tasks. Hosted Linux/Windows/macOS unit/integration and compatibility checks passed before this final small cleanup. Remaining: inspect hosted checks for final commit, mark ready when appropriate, keep merge hold. No approval is needed merely to complete review/tests/docs.

## Release candidate #162

Worktree `/home/aircan/.cache/vimi-worktrees/vimi-alpha22-ready`, branch `release/world-character-alpha22`, head 7c0eb41b43487d5f62be71a5519adc97e34819b9. Includes streaming improvements, maps loading fixes, geographic/minimap correction, Dwarf skin controls, Night Elf static profiles and handmade BC3 terrain decoding/packet admission. No experimental water/global rendering.

PR #162 MERGED as 666e6b1fe08837b49f5fcc3b97a1f13012b580ff after the duplicate retry passed and GitHub reported CLEAN. Alpha.22 tag pushed to that merge; five-platform tag packaging/publication is now pending. Current head passed Linux/Windows/macOS unit and integration checks and Java/C# compatibility. Duplicate push run 41889418053 had macOS cancelled without any steps; its failed aggregate leaves PR UNSTABLE. That failed-only retry subsequently passed. Successful same-head integration run is 41889428651. Check live status before acting; do not bypass failing checks. Five-platform package dry run 41882189215 passed at earlier 0bbe081e; 7c0 changes only restores immutable C# v2 golden identity plus explicit Java v3 migration tests. Current source interop independently passed 48 executed tasks.

Merge authorization for #162 has been exercised; continue monitoring normal tag-driven alpha.22 publication. Confirm tag unused; do not alter existing tags. Alpha.21 tag exists but did not publish due Linux ARM64 native SDK path. Alpha.20 is latest published at this checkpoint. `.github/workflows/desktop-prereleases.yml` tags publish only after all five package checks; workflow_dispatch is dry run. #159 is subsumed by #162; #161 shader infrastructure remains separately held.

## Preserved parallel work

- `/home/aircan/.cache/vimi-worktrees/vimi-world-next-integration`, branch `feature/world-next-integration`, checkpoint 360e50c6: reviewed global source/renderer/fixed-frame foundations and liquid geometry/design. Not an enabled dungeon viewer or visible water release.
- `/home/aircan/.cache/vimi-worktrees/vimi-world-water-source-facade`, branch `feature/world-water-source-facade`, abdbe527: source ownership/qualified water facade with actual read-only evidence and tests. Independent implementation review still required before codec/native/GUI integration.
- `/home/aircan/.cache/vimi-worktrees/global-protocol-codecs`, branch `feature/global-protocol-codecs`, 8ca8d58c + cf2b7053: pure GLOBAL CLI/control/closed codecs and frame filter; full checks and allocation negative control passed. Root implementation review pending; no runtime wiring.
- `/home/aircan/.cache/vimi-worktrees/vimi-client-data-backlog`, branch `docs/urgent-client-data-backlog`, 3ef655e4: detailed backlog and merge holds, not merged. GitHub issues #163–185 and epic #186 are already created.

Other issues are queued, including TraitTree, talent text/retries, World diagnostics, macOS setup, refresh discovery, cities/doodads, view distance, fidelity, NPCs, music, sky, ambient, travel, overlays and HD/SD. Do not begin these under current scope. Undead eligibility still needs actual normal archive source evidence; no broadening from locale alone.

## Commands and operational constraints

Use Linux JDK25. Examples from the urgent worktree:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk \
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/aircan/.cache/vimi-tmp \
./gradlew check assemble :formats-tables:interopTest \
  -PsqliteLibrary=/lib64/libsqlite3.so.0 \
  -PreferenceRoot=/home/aircan/Development/vibeminer \
  -Pdotnet=/home/aircan/Development/vibeminer/.local/toolchains/dotnet/dotnet
```

Use HOME cache for temporary files/worktrees, not /tmp (inode pressure). Do not log or commit imported cache bodies, keys or game assets. Preserve original libraries read-only. Check current branch/worktree before edits; many old /tmp registrations exist. Existing root HANDOFF/mac handoff historical sections are stale; this document supersedes their release/task status. No existing GitHub PR has automatic merge authorization except the explicit #162 exception.
