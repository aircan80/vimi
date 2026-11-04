# Persistent World parent transport checkpoint

Design/API proposal only, after the reviewed child actor `2d83f6f`, fixed native owner
`186c01f`, tile packet `f59fd26` and frame-v2 codecs. The strict package-private status decoder
`8cb24ed` + brace-only `2e79f70` is the wire parser; do not create a second parser here.
No GUI, source qualification, process-arbiter replacement, packet-format change, new shader or
resource-cap increase is proposed by this checkpoint.

## Responsibility and authority

Add one public `WorldStreamTransport` facade in rendering-bgfx. Keep `WorldStreamState`,
`WorldStreamControl`, `WorldStreamStatus`, envelopes, tile headers and frame mapping types
package-private. The facade is a bounded source-neutral process/IPC owner. A scope digest,
packet header, successful checksum, Resident or COMMITTED receipt is never native game-source
evidence. No BuildFiles/BuildTables/source-inspector/network/preparation dependencies enter it.

Only the trusted future application adapter may connect the private recorded-tile source
factory to this facade. It must write inside the source admission, wait for final validation
and actual operation drain, and return a finalized **unadvertised** packet owner. Custom/public
providers cannot become positively qualified hot providers by supplying a structural DTO.
The facade accepts descriptive packet metadata only; it does not acquire assets or reconstruct
source authority from filenames, IDs, hashes or `TerrainScene.Tile`.

## Small public surface

Concrete proposed names (nested public facade types; internal wire records remain hidden):

```java
public final class WorldStreamTransport {
    public enum Backend { DEFAULT, VULKAN, METAL, D3D11 }
    public record Spec(String scopeSha256, long initialPolicy, Backend backend) {}

    // Off-FX filesystem work. Creates fresh private directory/nonce/bootstrap/frame mapping.
    public static PreparedSession prepare(Path scratchParent, Spec spec) throws IOException;

    public static final class PreparedSession implements AutoCloseable {
        public List<String> launchArguments(); // flags for the existing GpuViewerMain, not shell text
        public Session attach(Process applicationOwnedChild) throws IOException;
        public void close(); // only unattached ownership; called off FX
    }

    public record Camera(long revision, long policy, int width, int height, TerrainCamera view) {}
    public enum Appearance { TEXTURED, HEIGHT_FALLBACK }

    public static final class Session {
        public CompletableFuture<Void> initialized();
        public Optional<TileReservation> tryReserveTile(int x, int y);
        public void camera(Camera camera); // metadata-only coalescing
        public CompletableFuture<CommitReceipt> commit(long policy, List<Resident> ordered);
        public Optional<Frame> takeLatestFrame(); // metadata/ownership transfer; no mapping IO
        public CompletableFuture<DrainReceipt> closeAsync();
        public CompletableFuture<DrainReceipt> terminatedAndDrained();
    }

    public static final class TileReservation {
        public CompletableFuture<Resident> produce(PacketProducer producer);
        public CompletableFuture<Void> cancel(); // idempotent, waits actual producer/input drain
    }
    @FunctionalInterface public interface PacketProducer {
        OwnedPacket prepare(PacketDestination destination, BooleanSupplier stopping) throws Exception;
    }
    public static final class PacketDestination {
        public Path path(); // exact private tile-<tx>.wst destination
        // Opaque writer context; no public protocol counter setters or sourceVerified flag.
    }
    public static final class OwnedPacket implements AutoCloseable {
        // Opaque source-neutral file owner, minted by the separately reviewed packet-writing adapter.
        // Stores destination identity, length and whole SHA only; never geometry/atlas/source handles.
        public void close(); // off-FX, idempotent cleanup before transfer
    }
    public static final class Resident {
        public int tileX(); public int tileY();
        public Appearance appearance(); public int coveredRectangles();
    }
    public static final class CommitReceipt {
        public long contentRevision(); public long policy();
        public List<Resident> ordered();
    }
    public static final class Frame {
        public long sequence(); public long cameraRevision(); public long policy();
        public int width(); public int height();
        public CommitReceipt content();
        public IntBuffer pixels(); // detached read-only ARGB_PRE, no native/mapping alias
    }
    public static final class DrainReceipt {
        public long childPid(); public int exitCode();
    }
}
```

All opaque handles have private constructors, session-owner identity and fixed bounded fields.
They cannot be mixed across sessions or reused after their generation retires. `Resident` is
created only for an exact matching STAGED result; it says the child owns the staged presentation
payload, not that a game source was authenticated. `CommitReceipt` is created only after matching
COMMITTED. `DrainReceipt` is minted only after actual process exit and all local owners drain.
No public constructors taking booleans such as verified/drained, arbitrary cleanup callbacks,
unbounded maps of transaction futures, or raw protocol string send API.

`PacketDestination`/`OwnedPacket` deliberately do not expose the large internal tile header as
a public API. A subsequent **narrow writer-adapter checkpoint** must define how the trusted
composition bridge writes the existing exact structural packet and obtains OwnedPacket. First
transport implementation uses the existing package-private writer in authored tests. This is
not permission to invent or bypass the source factory while adding the transport. The writer
adapter will remain structurally neutral: its successful return never authenticates caller data.

## Process arbiter and preparation

The facade does not start an independent world-only child manager. The existing application
`GpuPreviewService.application()` owns the one-child application policy today. Its future
adapter holds that SAME arbiter reservation while creating PreparedSession, starting the matched
Java child from `launchArguments()`, attaching, stopping and receiving DrainReceipt. Models,
cold terrain and hot terrain must use the same arbiter. No separate static singleton/semaphore
inside this facade is presented as proof of application-wide exclusivity.

Preparation is synchronous/off FX and creates a new private temp directory, random nonzero
128-bit nonce, fixed256-byte session.wss and exactly4,800,256-byte fresh frames mapping.
Arguments use the current PID, exact backend choice and bootstrap checksum. The existing
launcher remains responsible for matched bundled/runtime Java, classpath and native-access
flags; arguments are a bounded list, never a shell command. DEFAULT uses the existing OS backend
selection and does not authorize OpenGL or software fallback.

Unattached PreparedSession owns/deletes its own newly created files only. After successful
attach ownership transfers once to Session; closing the old preparation handle cannot delete
an attached session. Attach failure after a process exists requires the application arbiter to
retain that process/permit and stop it; it must not treat a failed method return as proof that
no child was started. Production attach/start adapter tests must cover that boundary.

The facade validates the actual Process handle, fresh mapping/session identity and single
attach state before starting readers. Child errors before SESSION are process failure, not
initialization success. A startup deadline must stop an owned child which never produces valid
SESSION/CREDIT; propose10seconds, independently tested and configurable only at the test seam.
Parent death/control-close/status-stall watchdogs in the child remain additional protection.

## Producer and file ownership

`tryReserveTile` is cheap and returns empty if no currently available child credit, cleanup is
pending, another producer owns that credit, or close has begun. It never waits or triggers source
IO. It validates0..63 coordinates and uses the one child credit exactly once. Transaction IDs
are assigned internally with the reviewed non-reuse/high-water limits; no wrap or counter reset
inside a live session.

`produce` invokes its callback on ONE dedicated off-FX preparation lane. It owns the actual
callback invocation until its `finally`, not a cancelable future's completed flag. The callback
is the parent-owned operation: it may call the existing private source engine and wait on that
engine's actual-drained result on this worker. Its final return must occur only after source
admission, packet write, final read-set validation and unadvertised-result transfer succeed.
No output file may still be written after return. Any unreturned output is the callback's local
try/finally responsibility; once returned, ownership transfers to the transport. The callback
must not retain geometry/atlas leases, BuildFiles or source owners inside OwnedPacket.

A callback exception does not imply that an arbitrary unregistered partial path is safe to
ignore. Its exact destination is registered before invocation and is cleaned only after the
callback actually finishes. Cancellation publishes stopping promptly but does not use
`CompletableFuture.cancel` as evidence of drain. If it returns an output late, that output is
closed off FX. Only after callback/actual writer drain may the unused credit send CANCEL_UNUSED;
an advertised OFFER uses established CANCEL and waits INPUT_CONSUMED plus the child's exact
retirement credit. No next producer/reused credit while an old writer can still touch its file.

This preserves the instruction that the **transport receives only finalized unpublished
packet metadata**. It does not accept live source/geometry/atlas payloads or infer provenance.
The producer is source-owned application orchestration; transport supplies a bounded worker
and lifetime envelope, not a game-source capability.

OFFER publishes only the exact ticket basename/length/full SHA and current nonce/lease. The
packet-writer adapter must bind actual XY/header/session/lease to that destination. Source and
material receipt verification stays with the source engine. Reader header/full-SHA validation
in the child remains mandatory even if the parent writer minted OwnedPacket.

Tile/commit files remain owned until exact matching INPUT_CONSUMED. Deletion runs on the file
lane outside locks/readers. A failed deletion blocks additional preparation/publication and
closes the session rather than accumulating an unbounded graveyard. At most one tile file and
one256-byte commit file are unconsumed; cleanup is scheduled before another tile writer can
start. Disk allowance is one max2,284,544-byte tile, one256-byte commit, bootstrap256 and fixed
mapping4,800,256, plus bounded filesystem overhead. No second full-packet heap byte array.

## Typed status machine and commands

Stdout has exactly one decoder using WorldStreamStatus.read, fixed256-byte ASCII/LF bounds.
Nonce mismatch has no state/file/frame effect. Invalid/truncated syntax, unknown matching
transaction/lease, duplicate or out-of-order reliable acknowledgements fail closed. Stderr has
its own bounded draining reader (retain at most4KiB last diagnostics); it is never parsed as
status or allowed to block because a diagnostic UI consumer is slow.

SESSION must be unique and precede CREDIT. Credit serial increments exactly once; slot and
generation must be inactive/current as appropriate. Validate every status against the currently
bounded pending operation, not merely its numeric domain. Retain at most nine active Residents,
one staged/incoming handle, one producer, one commit, and fixed transaction metadata. Terminal
operation futures are removed immediately; no per-transaction history map. Caller-retained
metadata handles do not retain pixels, files, source objects or native resources.

One writer thread owns child stdin. Parent command capacity is four **unacknowledged reliable
operations including in-flight write**, not merely four queued byte arrays. Duplicate cancel
calls share the same pending operation. This prevents a fast pipe writer from overflowing the
child's four-descriptor queue while native work is slower. Camera is one latest replacement,
not counted as reliable completion, and CLOSE is a sticky independent flag. Commands are
encoded by WorldStreamControl, never handwritten text. There is no write/flush/stream-close,
user callback or process wait under the state monitor or on the caller/FX thread.

A successfully staged handle is not yet visible content. `commit` validates at most nine unique
current-generation handles/XY in the caller's order, writes one bounded256-byte commit off FX,
and reserves one pending commit. Expected content comes from the last acknowledged commit,
not an optimistic local increment. A rejected optional commit leaves the last accepted table
readable; its transaction remains consumed. Unexpected renderer/transport inconsistency is
fatal and must not manufacture COMMITTED. Initial and explicit empty tables remain valid.

An excluded staged handle retains its claim until explicit cancellation/drain. COMMITTED does
not itself assert new pixels and must not retire the application's last presented CPU/source
snapshot. That presentation ownership belongs to the later controller; it changes only after
an accepted frame from the matching receipt. This preserves the existing atomic CPU/GPU
resource barrier rather than repeating the cold A/B/C lifetime bug.

## Frames and pre-allocation ACK barrier

ONE poll worker reads the v2 mapping at approximately30Hz, with no mapping IO/readback wait on
FX. Before calling `readLatest`, build an immutable barrier from the actual session plus current
acknowledged CommitReceipt, policy/dimensions and requested camera interval. Content must equal
that receipt's WorldContentRevision; a stationary-camera old readback cannot satisfy a new
COMMITTED receipt. Policy/dimension changes advance camera floor; camera-only drag accepts
monotonic lagging completed revisions up to the latest requested revision to avoid starvation.

WorldFrameFileV2 checks required header/ACK identity before allocating detached pixels. Recheck
the barrier after read and at `takeLatestFrame` handoff so queued old-policy/content/session
frames cannot overwrite a newer accepted state. A new desired but unacknowledged world keeps
the old displayed image; neither status arrival nor request dispatch clears the viewport.
Frame.content is the exact private receipt object matching that frame, not the newest mutable
world variable at delivery time.

The mailbox retains only the newest detached Frame and one worker read; notifications are
coalesced metadata-only. `takeLatestFrame` transfers that detached owner, never waits or touches
the mapping. The later GUI consumes at most one in-flight frame for bulk image upload and drops
it afterwards; it must not retain an unbounded frame history. Require the existing no-lost-final-
frame handoff regression and terminal-failure-before-queued-delivery regression in integration.
No callback can enqueue one FX task per produced frame.

Named parent bridge payloads at800x500: mapping4,800,256; reused CRC/read scratch1,600,000;
two facade detached frames3,200,000 plus at most one GUI upload-in-flight1,600,000 =11,200,256B.
The GUI WritableImage/native graphics storage and JVM/driver baseline are separate owners.
Packet transport adds at most65,536B streaming/hash scratch, two256B control/status staging
buffers and a proposed256KiB fixed metadata/future/diagnostic allowance. Validate concrete
implementation counts before approval; this is not a48MiB whole-application claim. Existing
48MiB source-helper, geometry cache, prepared metadata and child logical ledgers remain separate
and unchanged. No extra live atlas or geometry clones enter transport.

## Close, failure and arbiter release

`closeAsync` atomically rejects new work, marks producer cancellation and requests CLOSE without
waiting on a pipe monitor. Graceful request, process wait/destroy/force and stream cleanup run
off caller. Preserve the existing tested bounded escalation pattern: configured short graceful
wait,250ms after destroy, then2000ms after destroyForcibly. Do not synchronously close a stream
another thread may hold. A blocked custom close runs on a bounded daemon cleanup task; repeatedly
closing must not create more threads/tasks.

`closeAsync` may fail within its bounded deadline if process or parent producer/IO cannot drain.
That failure is NOT DrainReceipt. Keep files/mapping and the application arbiter permit while
any child is alive or any parent producer/file writer can still access them. No successor may
launch after an unknown kill outcome. `terminatedAndDrained` completes only after confirmed
Process.onExit, actual parent preparation finally, stdin/stdout/stderr workers relinquish their
owners, mapping close and directory cleanup. A late process exit can complete that same terminal
future; no new close attempt is needed. Only its private DrainReceipt authorizes arbiter release.
A cleanup failure remains explicit and cannot be relabeled successful release/deletion.

CLOSED is a useful protocol status, not proof of process death. Exit0 without valid CLOSED,
CLOSED followed by nonzero exit, malformed status, unexpected EOF, startup timeout and writer
failure are failures. Failed sessions discard pending frames and cannot present GPU success
later. Parent must not delete/reuse session files on a CANCELED future alone or rely on a future's
cancellation as proof of actual IO completion.

## Implementation and acceptance boundaries

1. Review this facade/lifetime contract and the strict parser separately. Implement metadata
   reducer/reservations/ACK barrier and owned prepared files using fake owned processes/streams.
   Tests hold producer finally, stdout read, stdin write and stream close independently; caller
   cancellation/close stays prompt, no new reservation/child, old files/permit remain until actual
   drain. Hostile statuses/ABA/session/tx/order/nonce and counter exhaustion are authored cases.
2. Implement process/command/frame loops with the existing internal codecs. Prove fresh bootstrap
   and actual mapping through the real v2 child, same PID across several tile changes, camera-only
   changes/resize/stationary content, rejected optional packet/commit preserving old content,
   cancellation while parent producer and child upload are held, EOF/parent loss, bounded stderr,
   failed kill retaining arbiter and eventual real exit permitting cleanup. Use authored geometry.
3. Review a narrow public packet-writing adapter and actual private-source composition before
   wiring the application singleton. Then independently review shared model/world arbiter and
   WorldView's presented/source/CPU-fallback ownership. Actual authenticated source traversal,
   representative continuous-camera cadence/latency and resource peaks are separate acceptance.

No GUI or release promotion follows from this proposal alone. No FPS, cross-backend hardware,
full map/source coverage, speculative prefetch, far-distance LOD or WMO hot-pass compatibility
is claimed by this parent transport milestone.
