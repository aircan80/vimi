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
adapter, GUI integration, nine-tile performance, and optional malformed-packet rejection racing an
already-queued established CANCEL. That cancellation/rejection ordering must get a causal fixture
before the broader protocol facade is approved; the valid STAGED cancellation path and unused
refusal path are exercised against the actual child here.
