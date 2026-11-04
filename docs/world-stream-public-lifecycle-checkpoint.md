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

Three bounded IO lanes currently drain commands, typed statuses and stderr. The frame poll,
producer/file and cancellation lanes remain the other three of the eventual six IO lanes.
One separately tracked lifecycle-management deadline covers initialization, including a held
Starter, without occupying an IO lane. Cameras
occupy one replaceable slot, CLOSE is sticky, and pipe writes/reads never execute on the caller.
SESSION/CREDIT/CAMERA_APPLIED/CLOSED use the reviewed strict reducer and camera gate. Any
matching operation status without an actual published operation fails closed. No frame is
accepted by this checkpoint. Six IO lanes remain the eventual ceiling.

The opt-in launch-owner startup deadline defaults to disabled for existing helper callers. The
public facade enables ten seconds. Its single management task only samples metadata and requests
existing close, with no pipe IO, user callbacks or joins on the timer. SESSION acknowledgement or
stop disarms it. Actual drain additionally waits for that task's finally, the real Starter return,
child exit and all existing workers. Timeout cannot delete files or release the application GPU
permit while Starter remains held. A focused causal fixture uses a 50ms deadline with held Starter,
asserts autonomous stop/bounded-close failure/retained mapping/no drain, then releases the Starter
and verifies eventual no-child cleanup. A separate acknowledgement fixture remains live beyond its
100ms deadline and closes normally afterward.

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

## Confirmed exit is independent of drain success

A failed drain future is never an exit certificate. The launch owner publishes a separate
private childExited event only after its actual Process.onExit observation and a non-live check.
This event can precede held worker finally blocks, so input retirement after confirmed exit
cannot deadlock waiting for the later aggregate drain. The public session immediately stops new operations and closes the frame gate on that event,
but invokes state.processExited only after BOTH confirmed exit and the status reader actual
finally. Aggregate success/failure alone only closes state. This preserves validation of buffered
CLOSED and other acknowledgements without authorizing new positive handoffs after exit. No-child start and unavailable exit
observation never publish childExited.

An authored live, unkillable Process with throwing onExit proves aggregate failure cannot enable
retirement of an advertised input; the mapping remains retained. A held-worker fixture proves
actual confirmed exit is observable before worker release while aggregate drain and file cleanup
remain pending. Genuine subprocess lifecycle tests remain separate from that adversarial fake.

A deterministic buffered-status regression holds the reader after SESSION/CREDIT, completes actual
exit, waits for the session exit handler under its metadata lock, then releases a previously
buffered CLOSED. It requires successful protocol completion and actual cleanup. A negative
control making processExited run before status-finally fails with `Status after terminal`;
the restored gate passes. A separate negative control interpreting failed drain as processExited
fails the live-child advertised-input-retirement guard. Neither control requires native hardware.
