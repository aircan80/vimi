# World v2 bounded control codec

`WorldStreamControl` is a package-private command codec, with no control threads,
mailboxes, packet IO or renderer integration. It covers CAM, OFFER, COMMIT,
CANCEL, CANCEL_UNUSED and CLOSE from the reviewed World v2 contract.

The stream reader consumes at most256 bytes including LF. It returns null only
for clean EOF before a command, refuses truncated/oversized/control/non-ASCII
input and does not scan arbitrary input for a later delimiter. The caller must
terminate the session after protocol failure; resynchronization is unsupported.
Only predictable transaction basenames, lowercase fixed hex and exact arities
are accepted. Counters use unsigned-looking canonical nonnegative decimal within
signed-long bounds; transactions exclude the exhausted maximum. Float formatting
is precisely Java Double.toString with round-trip equality, retaining negative
zero and rejecting alternate spellings, NaN and infinities. Existing TerrainCamera
coordinate/pitch bounds and fixed800×500 dimension caps apply. The writer checks
the complete encoded line bound before returning it.

Five authored tests cover every command, subsequent-line ownership, malformed
paths/nonce/version/counters/digests/camera fields, truncation, an endless input
that asserts no257th read, and long camera values with maximum counters. Full
rendering-bgfx check passes20tasks. This is structural metadata validation, not
source authority, mailbox overflow handling, session freshness or native proof.
Those belong to the subsequent controller/runtime implementation.

The follow-up `WorldControlMailbox` holds exactly four FIFO transaction
commands, one replaceable camera and a separate sticky close marker. Its short
synchronized sections execute no callbacks/IO. Camera revisions/policies remain
monotonic after taking a camera; dimension changes require a newer policy.
Overflow closes admission and clears pending descriptors rather than blocking or
growing. CLOSE remains admissible with a full queue and is idempotent. Wrong
session commands cannot close this mailbox. The owner must observe closing before
executing any command previously taken; this metadata handoff cannot interrupt an
already running operation or replace the runtime's native cleanup.

Four authored tests cover full-queue camera/close independence, overflow,
resize/revision regressions and FIFO amid10,000 coalesced concurrent camera
publications. Full module check passes20tasks. Independent codec approval92fd43f
is recorded separately; this mailbox follow-up still needs its own review.
