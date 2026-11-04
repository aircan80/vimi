# World v2 session runtime wire/API checkpoint

Exact remaining status arities for review before live control wiring. Every ASCII line begins `WS2 <nonce32> ` and ends LF, at most256bytes. Fixed lowercase nonce/canonical counters use the reviewed command conventions; no arbitrary text/path is emitted.

| Kind | Fields after kind | Reliability |
| --- | --- | --- |
| SESSION | `TERRAIN_BASIC_V1` | reliable |
| CREDIT | `serial slot generation` | reliable |
| CAMERA_APPLIED | `policy width height` | coalesced latest |
| INPUT_CONSUMED | `tx TILE` or `tx COMMIT` | reliable |
| STAGED | `offerTx serial slot generation outcome coveredRectangles` | reliable |
| COMMITTED | `tx expected next` | reliable |
| REJECTED | `tx code` | reliable |
| CANCELED | `offerOrUnusedTx serial slot generation` | reliable |
| CLOSED | none | reliable |

STAGED outcome1/2 is the approved packet TEXTURED/HEIGHT_FALLBACK mapping; coverage is0..256 and0forheightfallback. REJECTED codes are fixed enum STALE_TRANSACTION, STALE_LEASE, BAD_LEASE, BUSY, INVALID_TABLE, REVISION_MISMATCH, INVALID_PACKET, INVALID_COMMIT, PROTOCOL_ERROR, GPU_UNAVAILABLE, SESSION_CLOSED, STATUS_OVERFLOW, COUNTER_EXHAUSTED. tx0is reserved ONLY for session/control errors without a decoded transaction. Wrong-nonce commands have no action/status. SESSION confirms the protocol profile, not displayed pixels; no CREDIT is emitted until the native owner is ready. No STAGED/COMMITTED/ACK relabels pixels or ends parent presentation activity.

The optional launch profile is `--world-session-v2 <absolutePrivateDirectory> --session-sha256 <64lowerhex> --frames <absoluteMappingPath> --parent-pid <positivePID> [--backend vulkan|metal|d3d11]`. Only this mode accepts these keys; mixed legacy scene/terrain args and duplicate/unknown flags refuse. Bootstrap is exactlysession.wss/256bytes, regularNOFOLLOW, SHAverifiedfromsamehandle; frames must be in the same owned private directory and match fresh nonce/scope. Tile/commit paths are child-derived predictable basenames. Existing model/immutable-terrain CLI and GUI remain unchanged until independent actor review.

One control reader handles bounded command parsing/mailboxes only. One decoder worker owns at mostonefile/read/pack operation and no hidden queuedpayloads. The native owner reconciles newlyissued native references BEFORE released deltas and before cancellation/staging/commit/nextcredit. INPUT_CONSUMED uses the old tx and precedes next CREDIT reliably. A canceled queued/unstarted input is still opened/drained only if advertised/accepted ownership requires it; no unused cancellation invents a file acknowledgement. Returned futures contain cleared raw Payload/packed wrappers after transfer.

One status writer owns the potentially blocking OutputStream. Reliable16includesinflight; CAMERA_APPLIED is one coalesced metadata slot. No native-owner IO/join on that writer. The independent live-clock watchdog requests close after5swithreliableoutstanding/no completedwrite, and terminates after5sfurther without full drain. EOF/CLOSE/parentdeath initiate the existing separate5sgrace. Full drain includes decoder/native owners AND status writer; a blocked writer cannot disarm termination merely because GPUcleanup completed. Completed writes only advance status progress; enqueueing/partialoutput do not.

Authored process acceptance will use actual production packets/nativeowner/v2mapping acrosssamechild CAM/replacement/resize/stationarycontent/cancel/replay/close. A separate ownedchild fills its real stdoutpipe while its parent stops reading; an independent stderr/testmetadata progress channel may help stop production before queueoverflow, but no protocol/runtimebounds are relaxed. Assert independent status-stall termination and confirm process exit before deleting its scratch. No user app/process/library is touched.

## Bounded writer checkpoint

`WorldStreamStatusWriter` owns the potentially blocking output exclusively on a named virtual
thread. `reliable` reserves the existing deadline's capacity before enqueueing, including the
one line in flight; camera acknowledgements retain at most one pending replacement independently
of that reliable capacity. Native-owner calls only validate/copy at most 256 ASCII bytes and
briefly update metadata. No write, flush, close, join or callback occurs under its monitor.
`finish()` rejects new statuses, drops the optional camera acknowledgement, and returns a detached
future for the remaining reliable writes. It never closes the borrowed output stream. Its finished
state includes exceptional pipe completion; the session must inspect `failure()` and cannot emit
successful CLOSED after that failure. The external watchdog remains responsible for a writer
that cannot drain, even after native resources finish closing. This checkpoint is not a running
session or watchdog qualification.

## Actor and opt-in child checkpoint

`WorldStreamSession.run` is a child-only one-session entrypoint, not an in-process GUI service.
It owns one packet/commit decoder worker, the existing four-descriptor mailbox, one active
possibly-deferred descriptor, and the bounded status writer. A deferred COMMIT cannot prevent
an established old OFFER cancellation from being taken from that fixed mailbox; CANCEL_UNUSED
and other new transactions retain FIFO order. Camera and CLOSE remain independent of it.
A reserved COMMIT consumes transaction high-water before file IO and retires its reservation
on a bad file without changing the displayed table. It never rolls back replay protection.
Worker completion is actual file/decode/packing completion; no `Future.cancel` is used as a
resource-drain signal. The worker closes raw Payload through packing and transfers at most one
4 MiB packed input. Native issued references are reconciled before released references and
before stage, cancellation or advertising the next credit. INPUT_CONSUMED for the old tx is
enqueued before any newly released CREDIT. Final native shutdown releases are mirrored into
the metadata model before successful CLOSED.

The owner applies bounded upload slices before drawing, never after queuing a draw in the same
bgfx frame. It advances the native frame to retire uploads/readbacks independently of camera
arrival. Draw requests are paced at a nominal 30 Hz upper scheduling target, not a guaranteed
rendered/accepted FPS measurement. The fixed targets and three readback tickets still capture
camera/content/policy at draw time. The existing absolute-native 48-byte layout and promoted
eight shader blobs are unchanged here.

`GpuViewerMain` dispatches only explicit `--world-session-v2` requests into `WorldStreamMain`.
Legacy model, immutable-terrain and headless-smoke option/status paths remain separate. The
new parser rejects mixed/duplicate/unknown flags and requires canonical absolute directory and
frame paths, a lowercase bootstrap SHA and a positive parent PID. The 256-byte `session.wss`
is read and validated from one NOFOLLOW handle, with exact length/EOF checks; frame nonce/scope
and fresh mapping are independently checked before native allocation. Bootstrap and frames
must be regular files within the private directory. POSIX privacy/owner checks are enforced
where that attribute view is available; Windows relies on the caller creating its private
owned directory/ACL and has not received native runtime qualification. Publication remains
single-parent, immutable-file publication inside that directory, not a hostile multiwriter
filesystem contract.

An independent parent-death watchdog starts before bootstrap IO; the actor separately watches
parent/control loss and live reliable-status progress. A blocked reliable write requests close
after five seconds without a completed write, then forces owned-child termination after five
more seconds without full drain. Normal close/fatal errors arm a five-second drain deadline
before potentially blocking native cleanup. Native cleanup alone cannot disarm a blocked
writer. No successful CLOSED is emitted on failure/forced termination. Borrowed stdin/stdout
are never synchronously closed by another thread: the blocked virtual stdin reader is released
by process termination. The caller must exit this owned child after the one session returns;
this API is not reusable as successive sessions within a long-lived application JVM. Parent
scratch removal must still wait for confirmed child death.

### Executed authored evidence

`:rendering-bgfx:check :rendering-bgfx:nativeGpuWorldStreamSessionProof` passed together
(40 tasks) on Linux x64 Intel Vulkan. The proof uses only generated test geometry and textures,
no application data or network. The normal session and EOF sessions launch the real opt-in
`GpuViewerMain` path and validate typed statuses and mapped pixels. The test covers three
commits, two distinct texture colors, unchanged-camera content changes, 15 camera revisions,
actual upward-camera sky/return pixels, 800x500 resize, replay rejection, a malformed COMMIT
whose reservation remains consumed, established cancellation alongside a queued COMMIT,
CANCEL_UNUSED, and clean CLOSE/EOF exit. It is not a held slow-filesystem timing qualification.

A separate owned child uses the same actor with a test-only completion counter on stderr;
its stdout is never read. The parent sends one CANCEL_UNUSED at a time only after the previous
two reliable writes complete. Actual pipe capacity stopped progress after 573 commands and
1,147 completed writes. The independent watchdog halted that child with the authored exit73
hook at about 11.9 seconds including startup, demonstrating real blocked-pipe shutdown rather
than a fake clock/stream. Production forced exit uses code2. No universal FPS, nine-tile
movement performance, Metal/D3D hardware, GUI coordinator or authenticated game-source
integration is claimed by this checkpoint.
