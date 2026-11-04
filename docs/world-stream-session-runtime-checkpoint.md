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
