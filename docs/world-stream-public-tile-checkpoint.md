# Public tile production checkpoint

This adds real `tryReserveTile`, `TileReservation.produce/cancel`, scoped
`PacketDestination`, Moved-only `OwnedPacket`, and descriptive `Resident` to the reviewed
public lifecycle. Commit and accepted-frame methods remain absent until implemented.
No public Header/Expected/nonce/lease constructor or arbitrary positive source claim is exposed.

The existing arbiter still supplies Starter. Five IO lanes currently exist: commands, statuses,
stderr, producer/files and cancellation. The future frame poll lane is the sixth. One producer
runs at a time; the same bounded file lane handles actual input deletion. Cameras remain one
replaceable slot while producer work runs, CLOSE remains independent, and reliable commands
are capped at four outstanding rather than merely four queued writes. Callback code never runs
under session metadata monitors. Public close/failure ultimately retires pending result futures
without equating logical future failure with source callback or process/resource drain.

Destination is active only inside the producer callback. It registers at most one cancellation
request on the existing reviewed cancellation lane. Tile cancel and session stop both trigger it;
already-stopping registration is asynchronous. Producer return is followed by actual cancellation
callback-finally retirement before `producerDrained`, OFFER or CANCEL_UNUSED. Only the private
same-destination PreparationRefusal codes preserve the session without becoming a height packet;
unknown exceptions are fatal. Unreturned resources remain callback-local cleanup responsibility.

The separate fixed writer must perform Unadvertised-to-Moved transfer inside the source's pinned
written-resource callback after source final checks. Destination's package-private adoption checks
token, exact path, nonce/scope/transaction/lease and requested XY against the actual header used
by that writer, and derives outcome/coverage from it. The writer must pair that header with its
actual file publication; adoption does not independently re-read the whole packet or authenticate
source data. The old source wrapper is inert after MOVE. OwnedPacket contains only the moved file
owner and descriptive appearance counts, with no source/geometry/atlas owners. Its close is for
explicit off-FX producer/fixed-writer lanes; successful transport claim makes the old wrapper inert.

INPUT_CONSUMED queues real deletion on the producer/file lane. Only successful deletion marks
State fileRetired. A child CREDIT received earlier cannot start another construction until that
retirement. At shutdown an advertised input can instead retire after confirmed OS exit AND the
status reader actual finally, never a failed aggregate drain. Unknown exit/failed cleanup retains
files and the application's permit. No arbitrary directory entries are recursively deleted.

`capacityRevision()` is cheap monotonic metadata. Real CREDIT, actual producer/cancel-finally
retirement and input deletion bump it; overflow fails closed on the owning worker. Controllers
poll this with their existing cadence and retry source work only on a genuine changed capacity,
presentation or source-generation event. SESSION alone and a cancellation future alone are not
new-credit proofs. There is no extra capacity watcher/waiter lane.

Validation includes a held actual access.Cancellation lower listener: the producer's Java finally
returns before the cancellation callback finally, yet CANCEL_UNUSED/new construction and actual
mapping/process drain remain blocked. Logical bounded failure can finish while those owners are
still held; releasing the lower callback allows actual drain and file cleanup. The fake process
in that deterministic fixture is an IO/lifetime oracle, not native rendering evidence.

The opt-in actual Vulkan public proof exercises one typed RETRY_LATER refusal, real unused-credit
recovery, one canonical authored geometry/atlas file with finalize/MOVE/source-wrapper-close,
OFFER/INPUT_CONSUMED/STAGED, explicit tile cancellation, 100 camera submissions in the same child,
CLOSED/EOF/exit-zero and successful resource cleanup. It does not commit terrain or accept mapped
content frames, and makes no FPS, GPU image, source qualification or GUI integration claim.

Fixed-writer package hooks are `PacketDestination.context()` and
`PacketDestination.adopt(Moved, Header writtenHeader)`. Context is an immutable package-private
record of nonce/scope/transaction/lease/x/y/path/literal-Object identity, with no mutable Ticket,
Session or source reference. Adoption requires the still-active destination and the exact header
used by the fixed writer, not an arbitrary second description. Source scope/geometry/material
qualification and final read-set checks remain in the separate closed source bridge.

Not yet promoted by this checkpoint: COMMIT/accepted-frame publication, source-backed producer
adapter, GUI integration, nine-tile performance, and full native malformed-packet rejection/cancellation interleaving qualification. The narrower
parent state/pipe correction below is independently reviewable; the valid STAGED cancellation
path and unused refusal path are exercised against the actual child here.

## Optional rejection versus established cancellation

The child can reject INVALID_PACKET and grant new credit before reading an already-issued CANCEL;
its now-retired incoming descriptor then causes STALE_TRANSACTION for that exact old cancellation.
The parent retains its single old ticket while cancelSent&&!cancelResolved, even after CREDIT and
file deletion, rather than allocating tombstones or losing correlation. Only exact old tx plus
prior INVALID_PACKET plus an outstanding cancellation can resolve one STALE_TRANSACTION as
CANCEL_REJECTED. That fails the cancellation future rather than manufacturing CANCELED. Wrong
nonce stays ignored without resolution; wrong tx, missing prerequisites and duplicates stay fatal.
Only actual input deletion plus producer/callback drain and resolved cancellation allow reuse.

OFFER and CANCEL reliable credits are tracked separately; INVALID_PACKET retires only OFFER,
CANCELED retires whichever actual commands remain, and CANCEL_REJECTED retires only CANCEL.
Release underflow is fatal. Resolving this last cancellation bumps the capacity revision because
it can unblock an already-received credit. Current committed table/content remain unchanged.

Three pure-state cases preserve a previously committed table, test prerequisite/identity/replay
negatives and retain the actual-file-retirement barrier. The old implementation fails the first
causal case by admitting a new construction before its cancellation response is correlated.
An actual Java subprocess with authored deterministic status timing holds OFFER processing until
CANCEL is queued, then emits INPUT/INVALID_PACKET/CREDIT before STALE_TRANSACTION. It proves
both futures fail exceptionally while the session accepts a successor reservation/unused cancel,
then CLOSED/EOF/exit-zero drains. This is real pipe/parent ownership evidence, not a GPU or source
qualification oracle; existing native parser fixtures establish malformed-packet rejection.
The pipe fixture also fails causally when INVALID_PACKET is changed back to clearing all
reliable credits: the later cancel retirement underflows and no genuine successor credit can be
used. Restoring independent OFFER/CANCEL accounting makes the unchanged fixture pass.

## Cancellation success includes parent file retirement

CANCELED is an acknowledgement, not parent deletion completion. The metadata cancellation
query reports success only with authentic canceled ack, producer/callback-finally drain and
actual input-file retirement. The status reader and file lane both retry publication, using the
original reservation even if a newer ticket is later admitted. No exposed future is completed
from a caller metadata method or under a session monitor.

An already-canceled reservation returns bounded completed success for that old reservation.
Rejected, active committed and otherwise retired tickets return a fresh already-failed
CancellationRefusal (PACKET_REJECTED/ALREADY_COMMITTED/ALREADY_RETIRED), without another child
command, fake CANCELED or an indefinitely pending future. Replacing an active committed tile
requires a subsequent COMMIT; failure does not certify physical resource drain.

An actual authored Java child emits INPUT/STAGED, then CANCELED/CREDIT while a test-only task
holds the parent's sole file lane ahead of deletion. Cancellation stays pending, the input file
remains and no successor construction is admitted. Releasing the lane deletes the real file and
only then completes cancellation; repeating old cancellation is bounded. The old immediate-ack
publication fails this unchanged fixture causally. The rejection/queued-cancel pipe fixture also
checks a repeated old ticket cancellation returns typed PACKET_REJECTED after successor credit.
