# Public World session lifetime checkpoint

This checkpoint exposes real `WorldStreamTransport.prepare`, `PreparedSession.launch`,
`Session.initialized/camera/closeAsync/terminatedAndDrained/completion`, and private
process-ownership composition. It does not yet expose tile reservations, packet producers,
commits, or accepted frames. Those methods will be added when their ownership is implemented;
there are no placeholder methods returning fake readiness or pixels.

Preparation does filesystem work off JavaFX. Launch transfers the prepared files before invoking
an asynchronous application-supplied Starter. The application GPU arbiter remains the sole
process permit owner. Its Starter must return every spawned handle or throw without a child.
Old preparation close cannot delete transferred files. DrainReceipt construction requires the
reviewed actual process exit, worker-finally, pipe cleanup, mapping cleanup and file cleanup
receipt. Protocol completion remains separate: no-child startup failure and exit without
successful CLOSED fail completion even if resource drain succeeds.

Four bounded IO lanes currently drain commands, typed statuses, stderr and the future frame
poll lane. The latter currently enforces the initialization deadline after attachment. Cameras
occupy one replaceable slot, CLOSE is sticky, and pipe writes/reads never execute on the caller.
SESSION/CREDIT/CAMERA_APPLIED/CLOSED use the reviewed strict reducer and camera gate. Any
matching operation status without an actual published operation fails closed. No frame is
accepted by this checkpoint. Six IO lanes remain the eventual ceiling (producer/file and
cancellation occupy the remaining two).

A known remaining launch integration task is an autonomous initialization timeout while the
external Starter itself is held. Explicit close already remains bounded and retains all files
until that Starter really finishes; this checkpoint does not claim the autonomous timeout for
that interval. A tracked lifecycle management deadline must cover it before promotion.

Two focused tests execute an actual Java-only authored protocol subprocess and a held external
Starter. They prove typed SESSION/camera exchange, coalesced caller camera publication, graceful
CLOSE/exit-zero, exact PID receipt, private directory removal after exit, rejection after close,
and old preparation-close safety across a held Starter followed by no-child failure. They use
no game assets, network, GPU, GUI, source authority or application arbiter. No native performance
or public packet/source integration claim follows.

The next public commit API is `commit(long policy, Resident... ordered)`: length at most nine
must be checked before its single clone on the caller, with exact owned-snapshot validation on
the off-caller reducer. Immutable commit receipts may expose a bounded List. File ownership is
being implemented separately as a one-shot Unadvertised-to-Moved capability; source-side close
must become inert after MOVE, rather than sharing a deleting close flag with the transport.
