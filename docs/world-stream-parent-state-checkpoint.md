# Parent operation metadata checkpoint

WorldStreamParentState is package-private source-neutral protocol bookkeeping only. It owns
one current credit/reservation, one commit, at most nine active immutable resident descriptors,
ten scalar slot generations and monotonic counters. Handles carry an identity token, not a
source/process/pixel/file owner. No public facade, control queue, file producer/cleanup lane,
child launch, observer callback or GUI is wired by this checkpoint.

SESSION/credit order, slot generation/free-slot selection, exact INPUT_CONSUMED/STAGED/COMMITTED/
CANCELED matching and duplicate/future/foreign status rejection are checked before state changes.
The child credit cannot make another producer available until prior producer/cancellation
ownership and actual input-file retirement are complete. ProducerDrained is explicitly the
combined real producer finally and scheduled cancellation-callback finally, to be supplied by
the reviewed cancellation/lifecycle driver. It is not a canceled future. Unadvertised cancellation
waits that retirement and local file cleanup; OFFER/COMMIT publication cannot overtake an older
reserved unpublished transaction. Established cancellation does not allocate a new transaction.

Commit snapshots copy at most nine small descriptors before validation and retain that SAME
validated snapshot. They check owner/generation, active-or-staged membership, unique slot/XY,
source order and cancellation. No geometry/atlas/source retains occur per commit. Expected
content comes from acknowledged state. Optional INVALID_COMMIT/INVALID_TABLE/REVISION_MISMATCH
rejection preserves the old table; unknown/fatal rejection cannot be treated as that retry case.

File-retirement events are distinct from INPUT_CONSUMED. CLOSED/EOF alone cannot allow removal
of a still-readable child input. A separate processExited event is permitted only from actual
owned Process.onExit and allows terminal input cleanup without a missing consumption status.
Cleanup/producer-finally metadata can still retire after terminal state; no new command can.
The lifecycle/file driver must validate before doing IO and retain ownership until actual
cleanup; this pure state cannot prove a caller actually deleted or exited anything. The frame
ACK gate remains separate and must be wired to matched expectations/receipts by the full Session.

Seven authored tests cover a full stage/commit/next-credit trace, file deletion versus ack,
unadvertised writer cancellation/counter order, advertised cancellation/old-input ordering,
wrong nonce/duplicate/premature/mismatched statuses, stale/foreign/duplicate-XY/canceled handles,
optional rejection and terminal-versus-process-exit cleanup. An adversarial changing list proves
commit validation and retention use one bounded captured snapshot, rather than validating one
list then copying a different foreign descriptor. Full rendering-bgfx check passes.

No authenticated-source, actual parent pipe/process protocol, frame-presentation or performance
qualification is claimed. The complete public transport still needs to connect these reviewed
helpers, enforce the reliable operation/window bounds, and execute authored real-child proofs.
