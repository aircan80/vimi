# Public World commit and frame checkpoint

Concrete API (no GUI/source promotion):

- `Session.commit(long policy, Resident... ordered)` returns `CompletableFuture<CommitReceipt>`.
  The caller array count is checked before one bounded clone; validation and 256-byte private
  COMMIT file publication run on the existing producer/file lane. Receipt exposes immutable
  `contentRevision()`, historical `policy()` and `ordered()` residents. Receipt means matching
  COMMITTED, not presentation or parent file-drain completion.
- `Session.takeLatestFrame()` returns `Optional<Frame>` with sequence, cameraRevision, current
  policy, width/height, content receipt and readonly detached pixels. Pixels are opaque
  `0xffRRGGBB` (valid ARGB_PRE, identical straight ARGB because alpha255). The caller performs no
  mapping IO or pixel allocation. Caller/UI retains at most its bounded in-flight frame.

The sixth and final IO lane polls the existing v2 mapping at 33ms. Matching CAMERA_APPLIED and
COMMITTED barriers are required before pixel allocation. Current receipt/gate publication is
one metadata transaction. Resize policy and historical receipt policy remain separate. Taking
pixels rechecks terminal/current camera/content metadata; the UI must additionally enforce its
source/request generation before upload and release presented-source aliases only on real pixels.
Two facade detached arrays plus one UI in-flight array are the intended ownership budget; mapping
and CRC scratch remain the existing reviewed sizes. No geometry or atlas is copied by this lane.

Private COMMIT ownership is one pending request/file. INPUT_CONSUMED queues actual deletion on
its existing file lane; next request credit waits for terminal response AND deletion. Optional
commit rejection clears only its matching expectation/command credit and preserves current
receipt/table. Unexpected failure remains fatal. Actor exit permits advertised-file cleanup only
after actual exit plus status-finally; unadvertised owned files are safe to remove locally. Unknown
exit or cleanup failure keeps the application's arbiter permit/files quarantined.

Queued COMMIT owns its metadata phase before the file worker runs. Canceling a staged candidate
in that interval returns typed COMMIT_PENDING without stop/cancel flag mutation. The actual
State.prepareCommit transition is serialized with cancel on the same short metadata lock. No
source IO, external callback, caller collection method or file operation occurs under it. New
construction also waits for this phase. A controlled real Java child/file-lane fixture proves
caller-array mutation cannot alter publication, deferred COMMIT/cancel is nonfatal, old active
cancel returns ALREADY_COMMITTED, immutable receipts and no frame before camera/pixel proof.

The opt-in actual Vulkan proof runs one child through refusal/unused-credit recovery, two staged
packets, two commits, three accepted mapped frames, resize and 100 camera submissions. Independent
pixel expectations include the authored textured center RGB17/34/51 (one channel tolerance),
opaque readonly pixels, preserved historical receipt policy after resize, and a stationary empty
commit replacing texture pixels with sky without another camera request. It also checks retired
commit cancellation is not fake CANCELED, real uncommitted tile cancel, CLOSED/EOF/exit0 and actual
cleanup. This is authored native parent-bridge evidence, not game-source/GUI/FPS/cross-platform
runtime approval. Existing offline shader verification remains unchanged.
