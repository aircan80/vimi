# Source cancellation failure and final handoff

Separate lifecycle correction atop c6297f5, before typed-origin refusal changes.
Actual lower cancellation callbacks previously ran in a virtual thread whose
finally always completed the lane successfully. Callback failure was only logged;
a failed final lane join could also skip working release or abort a return after
its generic R had already been detached from engine ownership.

Cancellation dispatch now records callback/start failure explicitly. The private
lane seals under a short monitor, completes idle futures outside it, and joins the
actual callback finally. No callback runs under a metadata monitor or on FX.
withRecordedTile retains returned R locally through admission expiry, lane sealing,
actual cancellation drain and its final cancellation gate. A known failed/canceled
operation closes its R before awaiting lower callbacks that may depend on that
close. A late end/drain failure also closes the still-unreturned aggregate inside
the source-active guard. Only complete success transfers R to the caller.

Working/cancel-child references always clear in finally, after returned aggregate
cleanup. Unknown callback/cleanup failure is an ordinary fatal wrapper preserving
original and cleanup diagnostics; it cannot masquerade as clean cancellation or a
future expected refusal. Sticky cleanup failure retires known proofs and rejects
new work as fatal rather than retry pressure. A previously entered payload callback
still has its normal independent immutable pin; new borrows check backend health.
Actual close reports failure only after its tracked work/owners retire; an exception
is not permission to reuse native/app GPU ownership.

Two causal authored fixtures use actual native Map/WDT/root admission. One real
cancel callback waits for returned R close, then remains held and throws: R closes
first, active credit remains occupied through callback finally, no next source IO
starts, failure is visible and working eventually clears. The other injects only a
negative private end-future failure after the actual full source callback has made
its AdmissionResult; it holds and throws from returned R cleanup. It proves no
unreturned R/base escape, source-active credit held until real close, both errors
preserved, zero atlas claims and no clean cancellation. Reflection in that fixture
only forces failure; it creates no positive source proof or receipt. Full concepts
checks pass109 tests. No GUI, packet producer or renderer route changes here.
