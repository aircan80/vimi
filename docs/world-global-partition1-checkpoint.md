# Global pure protocol partition1 — ready for review

Completed the already approved pure partition while hotfix integration continues separately. Pure code only: global argv/control parsing, Closed status, mapping nonce/dimensions and fixed-scalar pre-pixel-allocation selection. No child dispatch/process/GUI/runtime enablement. Based on6a371ad6 plus reviewed frame-owner dependencies88686ff2/dc017621. Root3d1d402 design approval applies to direction, not implementation approval.

Focused GlobalWmoProtocolTest (seven cases), existing GpuFrameFileTest and WorldGlobalProtocolTest compile/pass. Log `/home/aircan/.cache/vimi-tmp/global-protocol-focused.log` (35tasks24executed8cache3uptodate). Formatter applied. Full rendering-bgfx:check/style pass40tasks13executed (`global-partition1-full-check.log`). Moving ONLY the selection check after pixel copying reproduces a causal failure: five rejected800×500 frames allocate8003280B versus the<1MB bound (`global-preallocation-filter-negative.log`). Restored source then forced all selected protocol/legacy frame tests pass33/33tasks (`global-protocol-restored.log`). Implementation remains unreviewed and must not be promoted as enabled. Canonical input length is narrowed to existing InputExpectation1024..5577600 inside6MiB stream envelope. Initial-bounds Source bridge metadata is not implemented. This checkpoint contains no child/runtime wiring. Root implementation approval is required before connecting a process; hotfix remains the primary integration task. Future PR must reference#170 and remain unmerged until explicit user approval.

## Root implementation review (2026-11-04)

Root review plus a separate independent reviewer that read only the code and the proposal found no blockers. Fixes made before runtime wiring:

- Argv paths must match their exact `Path.of` spelling. JDK `Path.of` silently collapses `//` and trailing `/`, so before this fix a normalize-equality check alone accepted noncanonical argv.
- The scene and frames directories must be disjoint and not nested in either direction. Previously only equal parents were rejected.
- `GlobalWmoLaunch.arguments()` emits the exact argv that `parse` accepts, so the parent cannot drift from the child grammar. Round-trip is tested with and without a backend.
- `readLine` documents that the caller must pass a buffered stream.
- `GlobalWmoProtocolEdgeTest` (8 cases) covers:
  - odd/duplicate/extra argv and invalid backends;
  - uppercase or wrong-length identity hex, and length bounds `0/01/1023/5577601`;
  - CAM/CLOSE field counts and revision `0`/overflow;
  - dimension and camera bounds, and ten noncanonical float spellings plus empty-field spacing;
  - explicit `Long.MAX_VALUE` revision exhaustion;
  - empty/CR/partial/non-ASCII reader lines;
  - selection with sequence, nonce `0`, closed mapping and non-800×500 mapping.

Accepted as-is:

- Input length stays 1024..5577600, the `InputExpectation` bound inside the 6 MiB envelope.
- Exhaustion is implicit: `Long.MAX_VALUE` is the final accepted revision and every successor is rejected.
- Identity is compared before the prefix; this only affects diagnostics.
- Package-private visibility requires the parent transport to live in `rendering-bgfx`.

`:rendering-bgfx:check` passes: 40 tasks, with 7+8 protocol tests executed and none skipped. Log: `/home/aircan/.cache/vimi-tmp/claude-codecs-fix-check.log`.
