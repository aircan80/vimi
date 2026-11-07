package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.TerrainCamera;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Source-neutral World process lifetime. The application's existing GPU arbiter supplies Starter
 * and retains its permit until terminatedAndDrained completes successfully.
 */
public final class WorldStreamTransport {
    /** Hard ceiling on residents one commit may name: the far 5 x 5 neighborhood. */
    public static final int MAX_RESIDENTS = WorldStreamState.MAX_ACTIVE;

    /** One tile beside the active residents is staged while the next commit is prepared. */
    public static final int MAX_PREPARED = MAX_RESIDENTS + 1;

    /**
     * Tile neighborhood a session keeps resident (issue #173). Every slot is the same fixed size,
     * so NEAR needs 10 slots and FAR 26; FAR costs about 2.6 times the terrain GPU memory.
     */
    public enum ViewDistance {
        /** The 3 x 3 neighborhood: 9 residents, 10 slots. */
        NEAR(1, WorldStreamEnvelope.Session.NEAR_ACTIVE),
        /** The 5 x 5 neighborhood: 25 residents, 26 slots. */
        FAR(2, WorldStreamState.MAX_ACTIVE);
        private final int radius, residents;

        ViewDistance(int radius, int residents) {
            this.radius = radius;
            this.residents = residents;
        }

        /** Tiles on each side of the camera tile. */
        public int radius() {
            return radius;
        }

        /** Most residents one commit may name. */
        public int residents() {
            return residents;
        }

        /** Renderer slots: every resident plus one staged. */
        public int slots() {
            return residents + 1;
        }

        static ViewDistance of(int residents) {
            for (var value : values()) {
                if (value.residents == residents) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown view distance");
        }
    }

    /**
     * Exterior object texture tier (#267 wires its image detail here). It selects the session's
     * shared objects GPU budget: STANDARD 192 MiB, HIGH 256 MiB.
     */
    public enum ObjectTextures {
        STANDARD(WorldStreamEnvelope.Session.STANDARD_TEXTURES),
        HIGH(WorldStreamEnvelope.Session.HIGH_TEXTURES);

        private final int code;

        ObjectTextures(int code) {
            this.code = code;
        }

        /** GPU bytes all exterior object sets of a session may reserve together. */
        public long budgetBytes() {
            return code == WorldStreamEnvelope.Session.HIGH_TEXTURES
                    ? WorldStreamEnvelope.Session.HIGH_OBJECT_BUDGET
                    : WorldStreamEnvelope.Session.STANDARD_OBJECT_BUDGET;
        }

        static ObjectTextures of(int code) {
            for (var value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown object texture tier");
        }
    }

    /** Fallback frame poll when no FRAME hint arrives; hints normally wake the reader first. */
    static final long FRAME_POLL_MILLIS = 33;

    public enum Backend {
        DEFAULT,
        VULKAN,
        METAL,
        D3D11
    }

    /**
     * {@code exteriorObjects} selects the BASIC_EXTERIOR_V1 session profile, under which each tile
     * packet may carry one immutable objects file drawn after terrain.
     */
    public record Spec(
            String scopeSha256,
            long initialPolicy,
            Backend backend,
            boolean exteriorObjects,
            ViewDistance viewDistance,
            ObjectTextures objectTextures) {
        public Spec(String scopeSha256, long initialPolicy, Backend backend) {
            this(scopeSha256, initialPolicy, backend, false);
        }

        public Spec(
                String scopeSha256, long initialPolicy, Backend backend, boolean exteriorObjects) {
            this(scopeSha256, initialPolicy, backend, exteriorObjects, ViewDistance.NEAR);
        }

        public Spec(
                String scopeSha256,
                long initialPolicy,
                Backend backend,
                boolean exteriorObjects,
                ViewDistance viewDistance) {
            this(
                    scopeSha256,
                    initialPolicy,
                    backend,
                    exteriorObjects,
                    viewDistance,
                    ObjectTextures.STANDARD);
        }

        public Spec {
            Objects.requireNonNull(backend);
            Objects.requireNonNull(viewDistance);
            Objects.requireNonNull(objectTextures);
            new WorldStreamEnvelope.Session(
                    new WorldStreamState.Nonce(1, 1), scopeSha256, initialPolicy);
        }
    }

    public record Camera(long revision, long policy, int width, int height, TerrainCamera view) {
        public Camera {
            new WorldStreamControl.Camera(
                    new WorldStreamState.Nonce(1, 1), revision, policy, width, height, view);
        }
    }

    /** #381: a frame the helper presented into its child window. */
    @FunctionalInterface
    public interface PresentedListener {
        void presented(long camera, long content);
    }

    /** Must return every spawned child, or throw only when no child was spawned. */
    @FunctionalInterface
    public interface Starter {
        Process start(List<String> arguments) throws Exception;
    }

    public enum Appearance {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    /**
     * Child-reported outcome of a tile's BASIC_EXTERIOR objects file; terrain never depends on it.
     */
    public enum ExteriorObjects {
        /** The session is not in the exterior profile. */
        NOT_IN_PROFILE,
        /** The tile packet named no objects file. */
        NONE,
        /** Decoded and uploaded; drawn whenever the tile is committed. */
        DRAWN,
        /** Decode or upload failed; the tile's terrain stays staged and committable. */
        FAILED
    }

    /** Terminal outcome of {@link Session#attachObjects}; terrain never depends on it. */
    public enum ObjectsOutcome {
        /** Attached to the resident's still active slot generation; drawn with its terrain. */
        ATTACHED,
        /** Writing, reading or uploading the objects failed; the terrain tile is untouched. */
        FAILED,
        /** The resident was no longer active (retired or its slot reused); nothing attached. */
        DROPPED,
        /**
         * The session's objects GPU budget was exhausted; nothing attached, terrain untouched. The
         * same resident may be offered again, e.g. once it moves inward or residents retire.
         */
        BUDGET
    }

    public enum Refusal {
        RETRY_LATER,
        CAPABILITY_UNAVAILABLE,
        SOURCE_INVALIDATED,
        /**
         * The helper rejected this tile's packet (for example a header that does not match its
         * slot); only that tile is affected and the session keeps streaming (issue #365).
         */
        PACKET_REJECTED
    }

    public static final class PreparationRefusal extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient Refusal reason;
        private final transient Object identity;

        private PreparationRefusal(Refusal reason, String detail, Object identity) {
            super(detail, null, false, false);
            this.reason = reason;
            this.identity = identity;
        }

        public Refusal reason() {
            return reason;
        }
    }

    public enum CancellationReason {
        ALREADY_COMMITTED,
        COMMIT_PENDING,
        PACKET_REJECTED,
        ALREADY_RETIRED
    }

    public static final class CancellationRefusal extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient CancellationReason reason;

        private CancellationRefusal(CancellationReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public CancellationReason reason() {
            return reason;
        }
    }

    @FunctionalInterface
    public interface StopRegistration extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    public interface PacketProducer {
        OwnedPacket prepare(
                PacketDestination destination, java.util.function.BooleanSupplier stopping)
                throws Exception;
    }

    public static final class Resident {
        private final WorldStreamParentState.Resident metadata;

        private Resident(WorldStreamParentState.Resident metadata) {
            this.metadata = metadata;
        }

        public int tileX() {
            return metadata.x;
        }

        public int tileY() {
            return metadata.y;
        }

        public Appearance appearance() {
            return metadata.outcome == 1 ? Appearance.TEXTURED : Appearance.HEIGHT_FALLBACK;
        }

        public int coveredRectangles() {
            return metadata.coverage;
        }

        /** Inline objects from STAGED, or late objects once {@link Session#attachObjects} ends. */
        public ExteriorObjects exteriorObjects() {
            return switch (metadata.objects) {
                case 0 -> {
                    var late = metadata.late;
                    yield late == WorldStreamStatus.LateOutcome.ATTACHED
                            ? ExteriorObjects.DRAWN
                            : late == WorldStreamStatus.LateOutcome.FAILED
                                    ? ExteriorObjects.FAILED
                                    : ExteriorObjects.NONE;
                }
                case 1 -> ExteriorObjects.DRAWN;
                case 2 -> ExteriorObjects.FAILED;
                default -> ExteriorObjects.NOT_IN_PROFILE;
            };
        }
    }

    /** Only producer/fixed-writer lanes receive this unadvertised owner; close is off JavaFX. */
    public static final class OwnedPacket implements AutoCloseable {
        private final Object ownershipLock = new Object();
        private WorldStreamPacketFile.Moved moved;
        private final int outcome, coverage;
        private final boolean objects;

        private OwnedPacket(
                WorldStreamPacketFile.Moved moved, int outcome, int coverage, boolean objects) {
            this.moved = Objects.requireNonNull(moved);
            this.objects = objects;
            if (!(outcome == 1 && coverage >= 1 && coverage <= 256
                    || outcome == 2 && coverage == 0)) {
                throw new IllegalArgumentException("Packet appearance");
            }
            this.outcome = outcome;
            this.coverage = coverage;
        }

        private WorldStreamTileFile.Expected expected() {
            synchronized (ownershipLock) {
                if (moved == null) {
                    throw new IllegalStateException("Packet already transferred or closed");
                }
                return moved.expected();
            }
        }

        private WorldStreamPacketFile.Moved claim(
                Object identity, WorldStreamTileFile.Expected expected) {
            synchronized (ownershipLock) {
                if (moved == null
                        || moved.destinationIdentity() != identity
                        || !moved.expected().equals(expected)) {
                    throw new IllegalStateException("Foreign or already transferred packet");
                }
                var value = moved;
                moved = null;
                return value;
            }
        }

        @Override
        public void close() {
            WorldStreamPacketFile.Moved value;
            synchronized (ownershipLock) {
                value = moved;
                moved = null;
            }
            if (value != null) {
                value.close();
            }
        }
    }

    public static final class PacketDestination {
        record Context(
                WorldStreamState.Nonce nonce,
                String scope,
                long transaction,
                WorldStreamState.Lease lease,
                int x,
                int y,
                Path path,
                Object identity) {}

        private final Context context;
        private final Object identity = new Object();
        private final Path path;
        private final WorldStreamProducerCancellation.Producer cancellation;
        private volatile boolean active = true;
        private final boolean exteriorObjects;
        private final TileReservation reservation;

        // Without a reservation (structural tests) there is no shared array: splat keeps the atlas.
        private PacketDestination(
                WorldStreamEnvelope.Session descriptor,
                WorldStreamParentState.Ticket ticket,
                Path path,
                WorldStreamProducerCancellation.Producer cancellation) {
            this(descriptor, null, ticket, path, cancellation);
        }

        private PacketDestination(
                WorldStreamEnvelope.Session descriptor,
                TileReservation reservation,
                WorldStreamParentState.Ticket ticket,
                Path path,
                WorldStreamProducerCancellation.Producer cancellation) {
            exteriorObjects = descriptor.exteriorObjects();
            this.reservation = reservation;
            this.context =
                    new Context(
                            descriptor.nonce(),
                            descriptor.scope(),
                            ticket.transaction,
                            ticket.lease,
                            ticket.x,
                            ticket.y,
                            path,
                            identity);
            this.path = path;
            this.cancellation = cancellation;
        }

        public Path path() {
            requireActive();
            return path;
        }

        public StopRegistration onStopping(Runnable requestCancellation) {
            requireActive();
            var registration = cancellation.onStopping(requestCancellation);
            return registration::close;
        }

        public PreparationRefusal refusal(Refusal reason, String detail) {
            requireActive();
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
            if (detail.length() > 256) {
                throw new IllegalArgumentException("Refusal detail bound");
            }
            for (int i = 0; i < detail.length(); i++) {
                if (detail.charAt(i) < 32 || detail.charAt(i) > 126) {
                    throw new IllegalArgumentException("Refusal detail ASCII");
                }
            }
            return new PreparationRefusal(reason, detail, identity);
        }

        Context context() {
            requireActive();
            return context;
        }

        /** Whether this session's profile admits a per-tile exterior objects file. */
        public boolean exteriorObjects() {
            requireActive();
            return exteriorObjects;
        }

        /**
         * Binds this tile's slot to shared splat layers for its diffuse keys (issue #174 step 2).
         * At most {@link WorldStreamTileFile#MAX_SPLAT_LAYERS} keys get a new layer, which this
         * packet must upload; further new keys, and keys finding no free layer, resolve to -1 so
         * their chunks keep the atlas. New layers stay pending until the tile is staged; any other
         * end of this reservation forgets them, so no later tile reuses a layer never uploaded.
         */
        SplatBinding bindSplat(List<WorldStreamDiffuseArray.Key> keys) {
            requireActive();
            if (reservation == null) {
                int[] none = new int[keys.size()];
                java.util.Arrays.fill(none, -1);
                return new SplatBinding(none, List.of());
            }
            return reservation.session.bindSplat(reservation, keys);
        }

        void dropSplatUpload(int layer) {
            requireActive();
            if (reservation != null) {
                reservation.session.dropSplatUpload(reservation, layer);
            }
        }

        // The separately reviewed fixed writer performs MOVE inside the source's pinned resource
        // callback.
        OwnedPacket adopt(
                WorldStreamPacketFile.Moved moved, WorldStreamTileFile.Header writtenHeader) {
            requireActive();
            Objects.requireNonNull(writtenHeader);
            var expected = moved.expected();
            if (moved.destinationIdentity() != identity
                    || !moved.path().equals(path)
                    || !expected.nonce().equals(context.nonce())
                    || !expected.scope().equals(context.scope())
                    || expected.transaction() != context.transaction()
                    || !expected.lease().equals(context.lease())
                    || !writtenHeader.nonce().equals(context.nonce())
                    || !writtenHeader.scope().equals(context.scope())
                    || writtenHeader.transaction() != context.transaction()
                    || !writtenHeader.lease().equals(context.lease())
                    || writtenHeader.x() != context.x()
                    || writtenHeader.y() != context.y()) {
                throw new IllegalArgumentException("Foreign packet destination/header");
            }
            int coverage = 0;
            for (long word : writtenHeader.coverage()) {
                coverage += Long.bitCount(word);
            }
            if ((writtenHeader.objects() != null) != (moved.objectsPath() != null)
                    || writtenHeader.objects() != null && !exteriorObjects) {
                throw new IllegalArgumentException("Packet objects differ from owner or profile");
            }
            return new OwnedPacket(
                    moved,
                    writtenHeader.outcome() == WorldStreamTileFile.Outcome.TEXTURED ? 1 : 2,
                    coverage,
                    writtenHeader.objects() != null);
        }

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException("Expired packet destination");
            }
        }
    }

    /** Shared layer per requested key (-1 keeps the atlas) and the new layers to upload. */
    record SplatBinding(int[] layers, List<Integer> uploads) {}

    public static final class TileReservation {
        private final Session session;
        private final WorldStreamParentState.Ticket ticket;
        private final CompletableFuture<Resident> result = new CompletableFuture<>();

        // The result as callers see it: it completes only after the splat bookkeeping for the
        // outcome has run, so no caller reacting to a failure can bind a layer this tile is still
        // about to forget (#435).
        private final CompletableFuture<Resident> settled;
        private final CompletableFuture<Void> canceled = new CompletableFuture<>();
        private volatile boolean stop;
        private volatile WorldStreamProducerCancellation.Producer cancellation;
        private boolean produced, offerOutstanding, cancelOutstanding;
        // Opt-in hand-off stamps (-Dvimi.world.stageTimings); 0 while timings are off.
        private volatile long reservedAt, producedAt, producerStartedAt, offeredAt, consumedAt;
        // Guarded by the session lock: the capacity revision when this reservation was issued and
        // the capacity changes it made itself before its result completed (#283).
        private long issuedCapacity;
        private int ownCapacityChanges;

        // Splat layers this reservation's packet uploads; forgotten unless the tile is staged.
        private int[] splatPending = new int[0];

        // Whether this tile bound splat layers at all; a staged tile that did not releases the
        // slot's previous binding, so an untextured successor never pins old layers forever.
        private boolean splatBound;

        private TileReservation(Session session, WorldStreamParentState.Ticket ticket) {
            this.session = session;
            this.ticket = ticket;
            settled =
                    result.whenComplete(
                            (resident, failure) -> {
                                if (failure != null) {
                                    session.forgetSplat(this);
                                } else {
                                    session.keepSplat(this);
                                }
                            });
        }

        /**
         * Whether capacity changed since this reservation was issued for a reason other than the
         * reservation itself: its own drain and retirement and the CREDIT that returns its lease
         * (which may land after its result) do not count. Read live, atomically with the session
         * revision. A caller blocked by its refusal retries when this is true.
         */
        public boolean capacityChangedElsewhere() {
            return session.changedElsewhere(this);
        }

        public CompletableFuture<Resident> produce(PacketProducer producer) {
            return session.produce(this, Objects.requireNonNull(producer));
        }

        public CompletableFuture<Void> cancel() {
            return session.cancel(this);
        }
    }

    public static final class CommitReceipt {
        private final long contentRevision, policy;
        private final List<Resident> ordered;

        private CommitReceipt(long contentRevision, long policy, List<Resident> ordered) {
            this.contentRevision = contentRevision;
            this.policy = policy;
            this.ordered = List.copyOf(ordered);
        }

        public long contentRevision() {
            return contentRevision;
        }

        public long policy() {
            return policy;
        }

        public List<Resident> ordered() {
            return ordered;
        }
    }

    public static final class Frame {
        private final WorldFrameFileV2.Frame frame;
        private final CommitReceipt content;

        private Frame(WorldFrameFileV2.Frame frame, CommitReceipt content) {
            this.frame = frame;
            this.content = content;
        }

        public long sequence() {
            return frame.metadata().sequence();
        }

        public long cameraRevision() {
            return frame.metadata().camera();
        }

        public long policy() {
            return frame.metadata().policy();
        }

        public int width() {
            return frame.metadata().width();
        }

        public int height() {
            return frame.metadata().height();
        }

        public CommitReceipt content() {
            return content;
        }

        /** Read-only pixels; throws once {@link #release()} returned them for reuse. */
        public java.nio.IntBuffer pixels() {
            return frame.pixels();
        }

        /**
         * The one owner calls this when it no longer reads {@link #pixels()}, so a later frame can
         * reuse the array. Idempotent; an unreleased frame is ordinary garbage.
         */
        public void release() {
            frame.release();
        }
    }

    public static final class DrainReceipt {
        private final OptionalLong pid;
        private final OptionalInt exit;

        private DrainReceipt(WorldStreamLaunchOwner.Drain drain) {
            pid = drain.childPid();
            exit = drain.exitCode();
        }

        public OptionalLong childPid() {
            return pid;
        }

        public OptionalInt exitCode() {
            return exit;
        }
    }

    public static final class PreparedSession implements AutoCloseable {
        static final long CLOSE_GRACE_MILLIS = 250;

        private final WorldStreamEnvelope.Session descriptor;
        private final WorldStreamPreparedFiles files;

        private PreparedSession(
                WorldStreamEnvelope.Session descriptor, WorldStreamPreparedFiles files) {
            this.descriptor = descriptor;
            this.files = files;
        }

        /** Transfers file ownership before asynchronous external process creation. */
        public Session launch(Starter starter) {
            return launch(starter, 10000, 5000);
        }

        Session launch(Starter starter, long startupMillis, long closeMillis) {
            return launch(starter, startupMillis, closeMillis, CLOSE_GRACE_MILLIS);
        }

        /**
         * {@code graceMillis} is how long close waits for the child to exit on its own after CLOSE
         * before it is signalled; tests with JVM children on loaded hosts need more than a helper.
         */
        Session launch(Starter starter, long startupMillis, long closeMillis, long graceMillis) {
            Objects.requireNonNull(starter);
            var session = new Session(descriptor);
            var owner =
                    WorldStreamLaunchOwner.launch(
                            files,
                            starter::start,
                            session::attach,
                            graceMillis,
                            closeMillis,
                            startupMillis);
            session.launched(owner);
            return session;
        }

        /**
         * Never waits or closes files on the caller. Inert once launch has transferred ownership.
         */
        @Override
        public void close() {
            files.closePreparationAsync();
        }

        public CompletableFuture<Void> closeAsync() {
            return files.closePreparationAsync();
        }
    }

    /** Filesystem preparation; call on a bounded application worker, never on JavaFX. */
    public static PreparedSession prepare(Path scratchParent, Spec spec) throws IOException {
        Objects.requireNonNull(spec);
        var random = new SecureRandom();
        long high, low;
        do {
            high = random.nextLong();
            low = random.nextLong();
        } while (high == 0 && low == 0);
        var descriptor =
                new WorldStreamEnvelope.Session(
                        new WorldStreamState.Nonce(high, low),
                        spec.scopeSha256(),
                        spec.initialPolicy(),
                        spec.exteriorObjects(),
                        spec.viewDistance().residents(),
                        spec.objectTextures().code);
        String backend =
                spec.backend() == Backend.DEFAULT
                        ? null
                        : spec.backend().name().toLowerCase(java.util.Locale.ROOT);
        return new PreparedSession(
                descriptor, WorldStreamPreparedFiles.create(scratchParent, descriptor, backend));
    }

    public static final class Session {
        private final Object lock = new Object();
        private final WorldStreamEnvelope.Session descriptor;
        private final WorldStreamParentState state;
        // Parent-owned shared splat layer array (issue #174 step 2): the only place that decides
        // which diffuse occupies which GPU layer, so parent and child never disagree.
        private final WorldStreamDiffuseArray splatLayers = new WorldStreamDiffuseArray();
        private final WorldStreamParentFrameGate frames;
        private final CompletableFuture<Void> initialized = new CompletableFuture<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile WorldStreamLaunchOwner.Handle owner;
        private volatile boolean closing, closedStatus;
        private volatile IOException failure;
        private WorldStreamControl.Camera pendingCamera;
        private boolean closeWritten, actualExit, statusesDrained;
        private final java.util.ArrayDeque<WorldStreamControl.Command> reliableCommands =
                new java.util.ArrayDeque<>(4);
        private final java.util.ArrayDeque<Runnable> fileTasks = new java.util.ArrayDeque<>(4);
        private final WorldStreamProducerCancellation cancellation =
                new WorldStreamProducerCancellation();
        private int outstandingReliable;
        private volatile long capacityRevision;
        private TileReservation tile;
        // The reservation tile replaced. The parent state admits a successor only once this one's
        // replies are all accounted for, but a reply it has already accepted may still be on its
        // way through this reader when the successor is reserved, so replies are matched by
        // transaction, never by whichever reservation is current.
        private TileReservation previousTile;
        // Test seam: runs on the status reader after the parent state accepted a message and
        // before this session handles it.
        volatile java.util.function.Consumer<WorldStreamParentState.Change> statusSeam;
        private WorldStreamPacketFile.Moved input;
        private boolean commitRequested;
        private CommitOperation commitOperation;
        private CommitReceipt currentReceipt;
        private Frame latestFrame;
        // Child FRAME hints wake the frame reader at once. The bounded wait remains the fallback,
        // so a lost or absent hint costs at most the old poll interval.
        private final WorldFrameSignal frameSignal = new WorldFrameSignal();
        private volatile Runnable frameListener;
        private volatile PresentedListener presentedListener;
        private volatile Runnable capacityListener;
        // Test seams: the reader's fallback wait, and completion when the reader has returned.
        volatile long framePollMillis = FRAME_POLL_MILLIS;
        final CompletableFuture<Void> framesReaderDone = new CompletableFuture<>();
        // Diagnostics only: bounded helper stderr, logged on abnormal end and with renderer
        // objects failures; never protocol.
        private final HelperStderrTail stderrTail = new HelperStderrTail();
        private final java.util.concurrent.atomic.AtomicBoolean endReported =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean drainReported =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final CompletableFuture<String> diagnosticReport = new CompletableFuture<>();
        private volatile long childPid;
        private volatile boolean stderrAttached;
        private final java.util.concurrent.atomic.AtomicLong objectsWriteFailures =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rendererObjectsBudget =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rendererObjectsFailures =
                new java.util.concurrent.atomic.AtomicLong();
        // Per-transaction refusals that leave the session streaming (issue #365).
        private final java.util.concurrent.atomic.AtomicLong rejectedCameras =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rejectedTiles =
                new java.util.concurrent.atomic.AtomicLong();
        private final CompletableFuture<String> objectsDiagnostic = new CompletableFuture<>();
        // Protocol records from helper stdout; unrelated lines are counted and sampled, never
        // decoded. Their sample joins the abnormal-end record.
        private final WorldStreamStdout stdout;
        private final CompletableFuture<String> stdoutDiagnostic = new CompletableFuture<>();

        private ObjectsOperation objectsOperation;

        /**
         * Test probe only: runs on the objects worker, under the session lock, after the stop check
         * and just before the OBJECTS command is enqueued. It must never block or take another
         * lock.
         */
        volatile java.util.function.Consumer<WorldStreamLaunchOwner.Handle> beforeObjectsEnqueue;

        /** The one late objects delivery in flight: file write, OBJECTS, reply, file delete. */
        private static final class ObjectsOperation {
            final Resident resident;
            final WorldStreamObjectsFile.Data data;
            final CompletableFuture<ObjectsOutcome> result = new CompletableFuture<>();
            boolean advertised;
            WorldStreamStatus.LateOutcome reply;

            ObjectsOperation(Resident resident, WorldStreamObjectsFile.Data data) {
                this.resident = resident;
                this.data = data;
            }
        }

        private static final class CommitOperation {
            final CompletableFuture<CommitReceipt> result;
            final List<Resident> ordered;
            final WorldStreamParentState.Commit metadata;
            final Path path;
            boolean created, advertised, deleted, outstanding;

            CommitOperation(
                    CompletableFuture<CommitReceipt> result,
                    List<Resident> ordered,
                    WorldStreamParentState.Commit metadata,
                    Path path) {
                this.result = result;
                this.ordered = List.copyOf(ordered);
                this.metadata = metadata;
                this.path = path;
            }
        }

        SplatBinding bindSplat(
                TileReservation reservation, List<WorldStreamDiffuseArray.Key> keys) {
            synchronized (splatLayers) {
                var resolution = splatLayers.bind(reservation.ticket.lease.slot(), keys);
                int[] layers = resolution.layers().clone();
                var uploads = new java.util.ArrayList<Integer>();
                for (int layer : resolution.uploads()) {
                    if (uploads.size() < WorldStreamTileFile.MAX_SPLAT_LAYERS) {
                        uploads.add(layer);
                    } else {
                        // Beyond the per-packet cap: never uploaded here, so never resolved.
                        splatLayers.discard(layer);
                        for (int i = 0; i < layers.length; i++) {
                            if (layers[i] == layer) {
                                layers[i] = -1;
                            }
                        }
                    }
                }
                reservation.splatPending = uploads.stream().mapToInt(Integer::intValue).toArray();
                reservation.splatBound = true;
                return new SplatBinding(layers, List.copyOf(uploads));
            }
        }

        /** A packet that will not upload one of its new layers releases it before writing. */
        void dropSplatUpload(TileReservation reservation, int layer) {
            synchronized (splatLayers) {
                splatLayers.discard(layer);
                reservation.splatPending =
                        java.util.Arrays.stream(reservation.splatPending)
                                .filter(pending -> pending != layer)
                                .toArray();
            }
        }

        /**
         * After a commit: slots the new table no longer draws, other than the tile still in flight,
         * stop holding splat layers (issue #174). Credit only goes to inactive slots, so a slot is
         * never rebound while its old generation is drawn; this releases the retired ones that are
         * never rebound. Called under the session lock.
         */
        private void releaseRetiredSplat() {
            var live = new java.util.HashSet<Integer>();
            for (var resident : state.active()) {
                live.add(resident.lease.slot());
            }
            if (tile != null && !tile.result.isDone()) {
                live.add(tile.ticket.lease.slot());
            }
            synchronized (splatLayers) {
                splatLayers.retain(live);
            }
        }

        private void keepSplat(TileReservation reservation) {
            synchronized (splatLayers) {
                reservation.splatPending = new int[0];
                if (!reservation.splatBound) {
                    // This slot's new tile uses no splat layers: its old ones stay cached but
                    // become evictable (draw-lifetime pinning is #174 step 2b-2's design).
                    splatLayers.release(reservation.ticket.lease.slot());
                }
            }
        }

        private void forgetSplat(TileReservation reservation) {
            synchronized (splatLayers) {
                for (int layer : reservation.splatPending) {
                    splatLayers.discard(layer);
                }
                reservation.splatPending = new int[0];
            }
        }

        /** Test observation: layers some slot still references. */
        int splatReferenced() {
            synchronized (splatLayers) {
                return splatLayers.referenced();
            }
        }

        /** Test observation of the parent-owned splat array. */
        int splatResident() {
            synchronized (splatLayers) {
                return splatLayers.resident();
            }
        }

        private Session(WorldStreamEnvelope.Session descriptor) {
            this.descriptor = descriptor;
            stdout =
                    new WorldStreamStdout(
                            WorldStreamStdout.hex(descriptor.nonce()), this::unrelatedStdout);
            state = new WorldStreamParentState(descriptor);
            frames = new WorldStreamParentFrameGate(descriptor);
        }

        private void launched(WorldStreamLaunchOwner.Handle handle) {
            owner = handle;
            // Every stop (close, child exit, worker failure) wakes a parked frame reader.
            handle.onStopping(this::frameSignaled);
            handle.childExited().thenRun(this::childExited);
            handle.boundedClose()
                    .whenComplete(
                            (drain, error) -> {
                                if (error != null) {
                                    fail(handle.failure() == null ? error : handle.failure());
                                    if (!handle.terminatedAndDrained().isDone()
                                            && drainReported.compareAndSet(false, true)) {
                                        report(
                                                "World helper did not drain before the close"
                                                        + " deadline",
                                                null,
                                                failure == null ? error : failure,
                                                false);
                                    }
                                }
                            });
            handle.terminatedAndDrained()
                    .whenComplete(
                            (drain, error) -> {
                                state.close();
                                frames.close();
                                failPending(
                                        new java.util.concurrent.CancellationException(
                                                "World session drained"));
                                if (!initialized.isDone()) {
                                    initialized.completeExceptionally(
                                            problem("World child did not initialize"));
                                }
                                if (error != null) {
                                    fail(error);
                                } else if (handle.failure() != null) {
                                    fail(handle.failure());
                                } else if (failure != null) {
                                    completion.completeExceptionally(failure);
                                } else if (!closedStatus
                                        || drain.exitCode().isEmpty()
                                        || drain.exitCode().getAsInt() != 0) {
                                    fail(
                                            new IOException(
                                                    "World child exited without successful CLOSED"
                                                            + exitSuffix(drain)));
                                } else {
                                    completion.complete(null);
                                }
                                if (completion.isCompletedExceptionally()
                                        && endReported.compareAndSet(false, true)) {
                                    report(
                                            "World helper ended abnormally",
                                            drain,
                                            failure == null ? error : failure,
                                            true);
                                } else {
                                    diagnosticReport.complete("");
                                    cleanNoiseCounts();
                                }
                            });
        }

        private void attach(Process process, WorldStreamLaunchOwner.Handle handle) {
            owner = handle;
            try {
                childPid = process.pid();
            } catch (UnsupportedOperationException unavailable) {
                childPid = 0;
            }
            handle.runWorker("commands", () -> writeCommands(process, handle));
            handle.runWorker(
                    "statuses",
                    () -> {
                        try {
                            readStatuses(process, handle);
                        } finally {
                            statusesDrained();
                        }
                    });
            handle.runWorker("stderr", () -> drainErrors(process));
            stderrAttached = true;
            handle.runWorker("producer-files", () -> files(handle));
            handle.runWorker("frames", () -> pollFrames(handle));
            handle.runWorker("objects", () -> objects(handle));
            handle.runWorker(
                    "cancellation",
                    () -> {
                        cancellation.work(handle::stopping);
                        if (cancellation.failure() != null) {
                            fail(cancellation.failure());
                        }
                    });
        }

        public CompletableFuture<Void> initialized() {
            return initialized.copy();
        }

        /** Whether this session's profile admits BASIC_EXTERIOR objects. */
        public boolean exteriorObjects() {
            return descriptor.exteriorObjects();
        }

        /** This session's resident neighborhood; commits name at most its residents. */
        public ViewDistance viewDistance() {
            return ViewDistance.of(descriptor.maxActive());
        }

        /**
         * Revision of objects budget releases: it moves when a resident whose objects were drawn is
         * replaced in its slot. A set refused with {@link ObjectsOutcome#BUDGET} is worth offering
         * again only after it moves.
         */
        public long objectsReleaseRevision() {
            return state.objectsReleases();
        }

        /** This session's object texture tier, which sets its shared objects GPU budget. */
        public ObjectTextures objectTextures() {
            return ObjectTextures.of(descriptor.objectTextures());
        }

        /** Protocol success is separate from process/resource drain. */
        public CompletableFuture<Void> completion() {
            return completion.copy();
        }

        /** Bounded array snapshot on caller; validation/publication and all IO run off caller. */
        public CompletableFuture<CommitReceipt> commit(long policy, Resident... ordered) {
            Objects.requireNonNull(ordered);
            if (ordered.length > descriptor.maxActive() || policy < descriptor.policy()) {
                throw new IllegalArgumentException("World commit bounds");
            }
            var snapshot = ordered.clone();
            var result = new CompletableFuture<CommitReceipt>();
            synchronized (lock) {
                if (closing || owner.stopping()) {
                    return CompletableFuture.failedFuture(
                            new java.util.concurrent.CancellationException("World stopped"));
                }
                if (commitRequested) {
                    return CompletableFuture.failedFuture(
                            new PreparationRefusal(
                                    Refusal.RETRY_LATER,
                                    "World commit still owns input",
                                    new Object()));
                }
                commitRequested = true;
                enqueueFile(() -> prepareCommit(policy, snapshot, result));
            }
            return result.copy();
        }

        private void prepareCommit(
                long policy, Resident[] ordered, CompletableFuture<CommitReceipt> result) {
            CommitOperation operation = null;
            try {
                if (closing || owner.stopping()) {
                    throw new java.util.concurrent.CancellationException("World stopped");
                }
                var members =
                        new java.util.ArrayList<WorldStreamParentState.Resident>(ordered.length);
                for (var member : ordered) {
                    members.add(Objects.requireNonNull(member).metadata);
                }
                WorldStreamParentState.Commit metadata;
                synchronized (lock) {
                    if (closing || owner.stopping()) {
                        throw new java.util.concurrent.CancellationException("World stopped");
                    }
                    metadata = state.prepareCommit(policy, members);
                }
                if (metadata == null) {
                    synchronized (lock) {
                        commitRequested = false;
                    }
                    result.completeExceptionally(
                            new PreparationRefusal(
                                    Refusal.RETRY_LATER,
                                    "World commit awaits genuine capacity",
                                    new Object()));
                    return;
                }
                operation =
                        new CommitOperation(
                                result,
                                java.util.Arrays.asList(ordered),
                                metadata,
                                owner.files()
                                        .directory()
                                        .resolve("commit-" + metadata.transaction + ".wsc"));
                synchronized (lock) {
                    commitOperation = operation;
                }
                var leases =
                        members.stream()
                                .map(
                                        m ->
                                                new WorldStreamEnvelope.Resident(
                                                        m.lease.slot(), m.lease.generation()))
                                .toList();
                byte[] bytes =
                        WorldStreamEnvelope.encode(
                                new WorldStreamEnvelope.Commit(
                                        descriptor.nonce(),
                                        metadata.transaction,
                                        metadata.expected,
                                        metadata.next,
                                        policy,
                                        leases));
                try (var channel =
                        java.nio.channels.FileChannel.open(
                                operation.path,
                                java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    operation.created = true;
                    var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                String sha =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(bytes));
                synchronized (lock) {
                    if (closing || owner.stopping()) {
                        throw new java.util.concurrent.CancellationException(
                                "World stopped before commit");
                    }
                    frames.expectCommit(
                            metadata.transaction, metadata.expected, metadata.next, policy);
                    operation.advertised = true;
                    enqueueCommand(
                            new WorldStreamControl.Commit(
                                    descriptor.nonce(), metadata.transaction, sha));
                }
            } catch (Throwable failed) {
                failedCommit(operation, result, failed);
            }
        }

        private void failedCommit(
                CommitOperation operation,
                CompletableFuture<CommitReceipt> result,
                Throwable failed) {
            Throwable cleanupFailure = null;
            if (operation != null && operation.created && !operation.advertised) {
                try {
                    java.nio.file.Files.deleteIfExists(operation.path);
                    operation.deleted = true;
                } catch (Throwable cleanup) {
                    cleanupFailure = cleanup;
                }
            }
            result.completeExceptionally(
                    WorldStreamLaunchOwner.Handle.detached(
                            cleanupFailure == null ? failed : cleanupFailure));
            if (cleanupFailure != null) {
                fail(cleanupFailure);
                return;
            }
            if ((closing || owner.stopping())
                    && (operation == null || !operation.advertised)
                    && WorldStreamProducerCancellation.cleanCancellation(failed, true)) {
                // Our own stop checks may run after close queues this file task. All owned,
                // unadvertised bytes have retired before treating that exact clean stop as normal.
                return;
            }
            fail(failed);
        }

        /**
         * Delivers BASIC_EXTERIOR objects for a resident that is already staged and committed,
         * after its terrain. The file write, the OBJECTS command, the child's reply and the file
         * delete all run on this session's own objects worker, independent of tile and commit
         * credits, so terrain never waits for it. At most one delivery is in flight (a second one
         * is refused RETRY_LATER). The child attaches the objects only while that exact slot
         * generation is still active; otherwise the outcome is DROPPED. A resident that is already
         * retired is DROPPED at once, before any file is written. Each resident is admitted once: a
         * later request, whatever the first outcome (including a FAILED objects file write), is
         * misuse. Misuse (a foreign resident, one with inline objects, or one already requested)
         * fails only the returned future with an IllegalArgumentException; it never ends the
         * session. Failures of the objects themselves stay isolated to them, and a FAILED outcome
         * is recorded on the resident before the future completes. A clean close completes the
         * future with a CancellationException after the file has been retired; if the session
         * fails, the future fails at once and the file is retired by the objects worker as the
         * session drains.
         */
        public CompletableFuture<ObjectsOutcome> attachObjects(
                Resident resident, WorldStreamTileWriter.Exterior exterior) {
            Objects.requireNonNull(resident);
            Objects.requireNonNull(exterior);
            if (!descriptor.exteriorObjects()) {
                throw new IllegalStateException("Session profile has no exterior objects");
            }
            synchronized (lock) {
                if (closing || owner.stopping()) {
                    return CompletableFuture.failedFuture(
                            new java.util.concurrent.CancellationException("World stopped"));
                }
                if (objectsOperation != null) {
                    return CompletableFuture.failedFuture(
                            new PreparationRefusal(
                                    Refusal.RETRY_LATER,
                                    "World objects delivery in flight",
                                    new Object()));
                }
                boolean live;
                try {
                    live = state.admitLateObjects(resident.metadata);
                } catch (IllegalArgumentException misuse) {
                    return CompletableFuture.failedFuture(misuse);
                }
                if (!live) {
                    return CompletableFuture.completedFuture(ObjectsOutcome.DROPPED);
                }
                var operation = new ObjectsOperation(resident, exterior.data());
                objectsOperation = operation;
                lock.notifyAll();
                return operation.result.copy();
            }
        }

        private void objects(WorldStreamLaunchOwner.Handle handle) throws Exception {
            while (true) {
                ObjectsOperation operation;
                synchronized (lock) {
                    operation = objectsOperation;
                    if (operation == null) {
                        if (handle.stopping()) {
                            return;
                        }
                        lock.wait(20);
                        continue;
                    }
                }
                deliverObjects(operation, handle);
            }
        }

        private void deliverObjects(
                ObjectsOperation operation, WorldStreamLaunchOwner.Handle handle) throws Exception {
            var metadata = operation.resident.metadata;
            var directory = handle.files().directory();
            java.util.function.BooleanSupplier stop = () -> closing || handle.stopping();
            ObjectsOutcome outcome = null;
            boolean written = false;
            Throwable fatal = null;
            try {
                WorldStreamObjectsFile.Reference reference = null;
                if (!stop.getAsBoolean()) {
                    try {
                        reference =
                                WorldStreamObjectsFile.write(
                                        directory,
                                        new WorldStreamObjectsFile.Identity(
                                                descriptor.nonce(),
                                                descriptor.scope(),
                                                metadata.transaction,
                                                metadata.lease,
                                                metadata.x,
                                                metadata.y),
                                        operation.data,
                                        stop);
                        written = true;
                    } catch (IOException | RuntimeException failed) {
                        // The writer removes its own partial file. Only these objects fail, and
                        // the resident records it like a child FAILED, or DROPPED once retired.
                        if (!stop.getAsBoolean()) {
                            objectsWriteFailed(failed);
                        }
                        synchronized (lock) {
                            if (!stop.getAsBoolean()) {
                                outcome =
                                        ObjectsOutcome.valueOf(
                                                state.lateObjectsFailed(metadata).name());
                            }
                        }
                    }
                }
                if (reference != null) {
                    synchronized (lock) {
                        if (!stop.getAsBoolean()) {
                            // Admitted synchronously; only retirement can have happened since.
                            var command =
                                    state.lateObjects(
                                            metadata, reference.length(), reference.sha256());
                            if (command == null) {
                                outcome = ObjectsOutcome.DROPPED;
                            } else {
                                var probe = beforeObjectsEnqueue;
                                if (probe != null) {
                                    probe.accept(handle);
                                }
                                // The owner may begin stopping outside this lock after the check
                                // above. A refused command was never sent: withdraw it, so the
                                // file is deleted at once and the stop cancels the future, the
                                // same as a stop seen before publishing.
                                operation.advertised = enqueueCommand(command);
                                if (!operation.advertised) {
                                    state.lateObjectsUnsent(metadata);
                                }
                            }
                        }
                    }
                }
                if (operation.advertised) {
                    // The child may read the file until its reply, or until it has actually
                    // exited and its statuses drained.
                    synchronized (lock) {
                        while (operation.reply == null && !(actualExit && statusesDrained)) {
                            lock.wait(20);
                        }
                        if (operation.reply != null) {
                            outcome = ObjectsOutcome.valueOf(operation.reply.name());
                        }
                    }
                }
            } catch (Throwable failed) {
                fatal = failed;
            } finally {
                if (written) {
                    try {
                        java.nio.file.Files.deleteIfExists(
                                directory.resolve(
                                        WorldStreamObjectsFile.basename(metadata.transaction)));
                    } catch (Throwable cleanup) {
                        if (fatal == null) {
                            fatal = cleanup;
                        } else {
                            fatal.addSuppressed(cleanup);
                        }
                    }
                }
                synchronized (lock) {
                    objectsOperation = null;
                    lock.notifyAll();
                }
            }
            if (fatal != null) {
                operation.result.completeExceptionally(
                        WorldStreamLaunchOwner.Handle.detached(fatal));
                fail(fatal);
                return;
            }
            if (outcome != null && !stop.getAsBoolean()) {
                operation.result.complete(outcome);
            } else {
                operation.result.completeExceptionally(
                        new java.util.concurrent.CancellationException("World stopped"));
            }
        }

        /** Detached pixels only; no mapping IO or array allocation occurs on the caller. */
        public java.util.Optional<Frame> takeLatestFrame() {
            synchronized (lock) {
                var result = latestFrame;
                latestFrame = null;
                if (result == null) {
                    return java.util.Optional.empty();
                }
                if (closing || owner.stopping() || !frames.presented(result.frame.metadata())) {
                    result.release();
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(result);
            }
        }

        /**
         * Runs on the transport's frame reader, without any lock, each time a new frame becomes
         * available to {@link #takeLatestFrame}. It must not block; replaced by a later call.
         */
        public void onFrame(Runnable listener) {
            frameListener = listener;
        }

        /**
         * #381, native presentation: runs on the status reader with the camera and content
         * revisions of each frame the helper presented into its child window. It must not block; a
         * throw is logged and does not end the session.
         */
        public void onPresented(PresentedListener listener) {
            presentedListener = listener;
        }

        private void pollFrames(WorldStreamLaunchOwner.Handle handle) throws Exception {
            try {
                readFrames(handle);
            } finally {
                framesReaderDone.complete(null);
            }
        }

        private void readFrames(WorldStreamLaunchOwner.Handle handle) throws Exception {
            long after = 0;
            while (!handle.stopping() && !closing) {
                var candidate = frames.readLatest(handle.files().frames(), after);
                boolean offered = false;
                if (candidate != null) {
                    after = candidate.metadata().sequence();
                    synchronized (lock) {
                        if (!closing
                                && !handle.stopping()
                                && currentReceipt != null
                                && currentReceipt.contentRevision()
                                        == candidate.metadata().content()
                                && frames.current(candidate.metadata())) {
                            // An untaken older frame is superseded: its array returns to the pool.
                            if (latestFrame != null) {
                                latestFrame.release();
                            }
                            latestFrame = new Frame(candidate, currentReceipt);
                            offered = true;
                        }
                    }
                    if (!offered) {
                        candidate.release();
                    }
                }
                var listener = frameListener;
                if (offered && listener != null) {
                    try {
                        listener.run();
                    } catch (RuntimeException failure) {
                        // A consumer's fault must not end the frame reader or the session.
                        System.getLogger(WorldStreamTransport.class.getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "World frame listener failed",
                                        failure);
                    }
                }
                frameSignal.await(framePollMillis);
            }
        }

        private void frameSignaled() {
            frameSignal.signal();
        }

        private void deleteCommit(CommitOperation operation) {
            try {
                java.nio.file.Files.deleteIfExists(operation.path);
                if (operation.advertised) {
                    state.retireCommitFile(operation.metadata);
                }
                synchronized (lock) {
                    operation.deleted = true;
                    if (operation.metadata.terminal) {
                        commitRequested = false;
                    }
                }
                capacityChanged();
            } catch (Throwable failed) {
                fail(failed);
                // Retire this actual worker exceptionally; never requeue a sticky failed delete.
                if (failed instanceof Error fatal) {
                    throw fatal;
                }
                throw new java.io.UncheckedIOException(
                        WorldStreamLaunchOwner.Handle.detached(failed));
            }
        }

        /** Poll with the controller's existing cadence; a change requests one bounded retry. */
        public long capacityRevision() {
            return capacityRevision;
        }

        private void capacityChanged() {
            synchronized (lock) {
                if (capacityRevision == Long.MAX_VALUE) {
                    throw new IllegalStateException("World capacity revision exhausted");
                }
                capacityRevision++;
            }
            var listener = capacityListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (RuntimeException failure) {
                    // A consumer's fault must not end the status reader or the session.
                    System.getLogger(WorldStreamTransport.class.getName())
                            .log(
                                    System.Logger.Level.WARNING,
                                    "World capacity listener failed",
                                    failure);
                }
            }
        }

        /**
         * Runs after every {@link #capacityRevision} change, possibly under the transport's own
         * lock, so it must only signal (never block or call back in). Replaced by a later call.
         * Lets a consumer wait on capacity without polling (#366).
         */
        public void onCapacity(Runnable listener) {
            capacityListener = listener;
        }

        /** A capacity change caused by {@code reservation}'s own drain or retirement. */
        private void capacityChanged(TileReservation reservation) {
            synchronized (lock) {
                capacityChanged();
                reservation.ownCapacityChanges++;
            }
        }

        private boolean changedElsewhere(TileReservation reservation) {
            synchronized (lock) {
                return capacityRevision
                        != reservation.issuedCapacity + reservation.ownCapacityChanges;
            }
        }

        /** Cheap metadata reservation; empty means genuine child/file credit is not available. */
        public java.util.Optional<TileReservation> tryReserveTile(int x, int y) {
            synchronized (lock) {
                if (closing || owner.stopping() || commitRequested) {
                    return java.util.Optional.empty();
                }
                var reserved = state.reserve(x, y);
                if (reserved == null) {
                    return java.util.Optional.empty();
                }
                previousTile = tile;
                tile = new TileReservation(this, reserved);
                tile.issuedCapacity = capacityRevision;
                tile.reservedAt = WorldStageTimings.start();
                return java.util.Optional.of(tile);
            }
        }

        private CompletableFuture<Resident> produce(
                TileReservation reservation, PacketProducer producer) {
            synchronized (lock) {
                if (closing || owner.stopping() || tile != reservation || reservation.produced) {
                    throw new IllegalStateException("Expired tile producer");
                }
                state.beginProducer(reservation.ticket);
                reservation.produced = true;
                reservation.producedAt =
                        WorldStageTimings.lap(
                                "handoff.parent.reserveToProduce", reservation.reservedAt);
                enqueueFile(() -> produceFile(reservation, producer));
            }
            return reservation.settled.copy();
        }

        private CompletableFuture<Void> cancel(TileReservation reservation) {
            synchronized (lock) {
                var terminal = state.cancellation(reservation.ticket);
                if (terminal == WorldStreamParentState.Cancellation.PENDING
                        && commitRequested
                        && reservation.ticket.resident != null) {
                    return CompletableFuture.failedFuture(
                            new CancellationRefusal(CancellationReason.COMMIT_PENDING));
                }
                if (terminal == WorldStreamParentState.Cancellation.CANCELED) {
                    return CompletableFuture.completedFuture(null);
                }
                if (terminal != WorldStreamParentState.Cancellation.PENDING) {
                    return CompletableFuture.failedFuture(cancelRefusal(terminal));
                }
                reservation.stop = true;
                var lower = reservation.cancellation;
                if (lower != null) {
                    lower.stop();
                }
                var command = state.cancel(reservation.ticket);
                if (command != null) {
                    enqueueCommand(command);
                }
            }
            return reservation.canceled.copy();
        }

        private static CancellationRefusal cancelRefusal(
                WorldStreamParentState.Cancellation terminal) {
            return new CancellationRefusal(
                    switch (terminal) {
                        case COMMITTED -> CancellationReason.ALREADY_COMMITTED;
                        case COMMIT_PENDING -> CancellationReason.COMMIT_PENDING;
                        case REJECTED -> CancellationReason.PACKET_REJECTED;
                        case RETIRED -> CancellationReason.ALREADY_RETIRED;
                        default -> throw new IllegalArgumentException("Nonterminal cancellation");
                    });
        }

        /**
         * The reservation a tile reply names, by transaction: the current one or its predecessor.
         */
        private TileReservation reservationFor(long transaction) {
            synchronized (lock) {
                if (tile != null && tile.ticket.transaction == transaction) {
                    return tile;
                }
                if (previousTile != null && previousTile.ticket.transaction == transaction) {
                    return previousTile;
                }
                throw new IllegalStateException("Uncorrelated tile status");
            }
        }

        private static long transaction(WorldStreamStatus.Message message) {
            return switch (message) {
                case WorldStreamStatus.InputConsumed value -> value.transaction();
                case WorldStreamStatus.Staged value -> value.transaction();
                case WorldStreamStatus.Rejected value -> value.transaction();
                case WorldStreamStatus.Canceled value -> value.transaction();
                default -> throw new IllegalStateException("Status names no tile transaction");
            };
        }

        /**
         * Called only on existing workers; never completes exposed futures from caller metadata
         * APIs.
         */
        private void publishCancellation(TileReservation reservation) {
            var terminal = state.cancellation(reservation.ticket);
            if (terminal == WorldStreamParentState.Cancellation.CANCELED) {
                reservation.canceled.complete(null);
            } else if (terminal != WorldStreamParentState.Cancellation.PENDING) {
                reservation.canceled.completeExceptionally(cancelRefusal(terminal));
            }
        }

        /** Returns false, sending nothing, once the session is closing or its owner stopping. */
        private boolean enqueueCommand(WorldStreamControl.Command command) {
            synchronized (lock) {
                if (closing || owner.stopping()) {
                    return false;
                }
                if (outstandingReliable == 4) {
                    throw new IllegalStateException("World reliable command capacity");
                }
                if (command instanceof WorldStreamControl.Offer) {
                    if (tile.offerOutstanding) {
                        throw new IllegalStateException("Duplicate offer credit");
                    }
                    tile.offerOutstanding = true;
                } else if (command instanceof WorldStreamControl.Cancel
                        || command instanceof WorldStreamControl.CancelUnused) {
                    if (tile.cancelOutstanding) {
                        throw new IllegalStateException("Duplicate cancel credit");
                    }
                    tile.cancelOutstanding = true;
                }
                if (command instanceof WorldStreamControl.Commit) {
                    if (commitOperation == null || commitOperation.outstanding) {
                        throw new IllegalStateException("Commit command credit");
                    }
                    commitOperation.outstanding = true;
                }
                outstandingReliable++;
                reliableCommands.addLast(command);
                lock.notifyAll();
                return true;
            }
        }

        private void retireReliable() {
            synchronized (lock) {
                if (outstandingReliable < 1 || outstandingReliable > 4) {
                    throw new IllegalStateException("World reliable command credit underflow");
                }
                outstandingReliable--;
            }
        }

        private void enqueueFile(Runnable task) {
            synchronized (lock) {
                if (fileTasks.size() == 4) {
                    throw new IllegalStateException("World file task capacity");
                }
                fileTasks.addLast(task);
                lock.notifyAll();
            }
        }

        private void produceFile(TileReservation reservation, PacketProducer producer) {
            reservation.producerStartedAt =
                    WorldStageTimings.lap(
                            "handoff.parent.produceToProducerStart", reservation.producedAt);
            WorldStreamProducerCancellation.Producer lower;
            try {
                lower = cancellation.begin();
            } catch (IllegalStateException unavailable) {
                if (!closing && !owner.stopping()) {
                    throw unavailable;
                }
                state.producerDrained(reservation.ticket);
                capacityChanged(reservation);
                state.retireTileFile(reservation.ticket);
                capacityChanged(reservation);
                reservation.result.completeExceptionally(
                        new java.util.concurrent.CancellationException(
                                "World stopped before producer"));
                return;
            }
            reservation.cancellation = lower;
            if (reservation.stop || closing || owner.stopping()) {
                lower.stop();
            }
            var destination =
                    new PacketDestination(
                            descriptor,
                            reservation,
                            reservation.ticket,
                            owner.files()
                                    .directory()
                                    .resolve("tile-" + reservation.ticket.transaction + ".wst"),
                            lower);
            OwnedPacket packet = null;
            Throwable error = null;
            try {
                if (!reservation.stop && !closing && !owner.stopping()) {
                    packet =
                            Objects.requireNonNull(
                                    producer.prepare(
                                            destination,
                                            () -> reservation.stop || closing || owner.stopping()));
                }
            } catch (Throwable failed) {
                error = failed;
            } finally {
                destination.active = false;
                lower.finished();
            }
            while (!lower.retired()) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
            }
            reservation.cancellation = null;
            if (cancellation.failure() != null) {
                error = cancellation.failure();
            }
            state.producerDrained(reservation.ticket);
            capacityChanged(reservation);
            try {
                if (error != null
                        && !(error instanceof PreparationRefusal refusal
                                && refusal.identity == destination.identity)
                        && !WorldStreamProducerCancellation.cleanCancellation(
                                error, reservation.stop || closing || owner.stopping())) {
                    throw WorldStreamLaunchOwner.Handle.detached(error);
                }
                if (error != null
                        || packet == null
                        || reservation.stop
                        || closing
                        || owner.stopping()) {
                    if (packet != null) {
                        packet.close();
                        packet = null;
                    }
                    state.retireTileFile(reservation.ticket);
                    capacityChanged(reservation);
                    synchronized (lock) {
                        var command = state.cancel(reservation.ticket);
                        if (command != null) {
                            enqueueCommand(command);
                        }
                    }
                    if (error instanceof PreparationRefusal refusal) {
                        reservation.result.completeExceptionally(refusal);
                    } else {
                        reservation.result.completeExceptionally(
                                new java.util.concurrent.CancellationException("Tile stopped"));
                    }
                    return;
                }
                boolean published = false;
                synchronized (lock) {
                    if (!reservation.stop && !closing && !owner.stopping()) {
                        var expected = packet.expected();
                        if (!expected.nonce().equals(descriptor.nonce())
                                || !expected.scope().equals(descriptor.scope())
                                || expected.transaction() != reservation.ticket.transaction
                                || !expected.lease().equals(reservation.ticket.lease)) {
                            throw new IOException("Foreign finalized packet identity");
                        }
                        input = packet.claim(destination.identity, expected);
                        var command =
                                state.offer(
                                        reservation.ticket,
                                        expected.length(),
                                        expected.sha256(),
                                        packet.outcome,
                                        packet.coverage,
                                        packet.objects);
                        enqueueCommand(command);
                        reservation.offeredAt =
                                WorldStageTimings.lap(
                                        "handoff.parent.producerRunToOffer",
                                        reservation.producerStartedAt);
                        packet = null;
                        published = true;
                    }
                }
                if (!published) {
                    packet.close();
                    packet = null;
                    state.retireTileFile(reservation.ticket);
                    capacityChanged(reservation);
                    synchronized (lock) {
                        var command = state.cancel(reservation.ticket);
                        if (command != null) {
                            enqueueCommand(command);
                        }
                    }
                    reservation.result.completeExceptionally(
                            new java.util.concurrent.CancellationException(
                                    "Tile stopped before offer"));
                }

            } catch (Throwable failed) {
                if (packet != null) {
                    packet.close();
                }
                fail(failed);
            }
        }

        private void files(WorldStreamLaunchOwner.Handle handle) throws Exception {
            while (true) {
                Runnable task;
                synchronized (lock) {
                    task = fileTasks.pollFirst();
                    if (task == null) {
                        if (handle.stopping()
                                && input == null
                                && (commitOperation == null
                                        || !commitOperation.created
                                        || commitOperation.deleted)) {
                            return;
                        }
                        if (handle.stopping()
                                && commitOperation != null
                                && commitOperation.created
                                && !commitOperation.deleted
                                && (!commitOperation.advertised || actualExit && statusesDrained)) {
                            var operation = commitOperation;
                            task = () -> deleteCommit(operation);
                        } else if (handle.stopping()
                                && actualExit
                                && statusesDrained
                                && input != null) {
                            var owned = input;
                            var current = tile;
                            task =
                                    () -> {
                                        owned.close();
                                        state.retireTileFile(current.ticket);
                                        capacityChanged();
                                        synchronized (lock) {
                                            input = null;
                                        }
                                    };
                        } else {
                            lock.wait(20);
                            continue;
                        }
                    }
                }
                task.run();
            }
        }

        /**
         * Replaces one pending camera; no pipe I/O or external callback occurs on the caller. A
         * camera that is not a valid transition from the last accepted one (an older revision, a
         * lower policy, or a new size without a policy change) is refused and logged, rate limited,
         * and the session keeps streaming the last accepted camera: false. Only a stopping session
         * throws.
         */
        public boolean camera(Camera camera) {
            Objects.requireNonNull(camera);
            var command =
                    new WorldStreamControl.Camera(
                            descriptor.nonce(),
                            camera.revision(),
                            camera.policy(),
                            camera.width(),
                            camera.height(),
                            camera.view());
            synchronized (lock) {
                if (closing || owner != null && owner.stopping()) {
                    throw new IllegalStateException("World session is stopping");
                }
                if (!frames.tryDesire(command)) {
                    cameraRejected(camera);
                    return false;
                }
                pendingCamera = command;
                lock.notifyAll();
                return true;
            }
        }

        /** A refused camera transition: logged first, then every 64th; never session-fatal. */
        private void cameraRejected(Camera camera) {
            long count = rejectedCameras.incrementAndGet();
            if (!cadenceLogged(count)) {
                return;
            }
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "World camera refused: revision "
                                        + camera.revision()
                                        + ", policy "
                                        + camera.policy()
                                        + ", "
                                        + camera.width()
                                        + "x"
                                        + camera.height()
                                        + " is not a valid transition ("
                                        + count
                                        + (count == 1 ? " camera" : " cameras")
                                        + " this session); the last accepted camera stays");
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt camera publication.
            }
        }

        /**
         * The helper refused one tile transaction (an invalid packet); the session keeps streaming
         * and the controller retries or skips that tile. Logged first, then every 64th, with the
         * helper's redacted stderr tail, which names the helper's reason.
         */
        private void tileRejected() {
            long count = rejectedTiles.incrementAndGet();
            if (!cadenceLogged(count)) {
                return;
            }
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "World helper rejected a tile ("
                                        + count
                                        + (count == 1 ? " tile" : " tiles")
                                        + " this session); streaming continues"
                                        + stderrSuffix());
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt the status reader.
            }
        }

        /** Tiles the helper rejected this session (diagnostics and tests). */
        public long rejectedTiles() {
            return rejectedTiles.get();
        }

        long rejectedCameras() {
            return rejectedCameras.get();
        }

        public CompletableFuture<DrainReceipt> closeAsync() {
            requestClose();
            return owner.closeAsync().thenApply(DrainReceipt::new);
        }

        public CompletableFuture<DrainReceipt> terminatedAndDrained() {
            return owner.terminatedAndDrained().thenApply(DrainReceipt::new);
        }

        private void requestClose() {
            synchronized (lock) {
                closing = true;
                pendingCamera = null;
                if (latestFrame != null) {
                    latestFrame.release();
                }
                latestFrame = null;
                lock.notifyAll();
            }
            frameSignaled();
            frames.close();
            cancellation.stopSession();
        }

        private void childExited() {
            requestClose();
            synchronized (lock) {
                actualExit = true;
                if (statusesDrained) {
                    state.processExited();
                }
            }
        }

        private void statusesDrained() {
            synchronized (lock) {
                statusesDrained = true;
                if (actualExit) {
                    state.processExited();
                }
            }
        }

        private void writeCommands(Process process, WorldStreamLaunchOwner.Handle handle)
                throws Exception {
            var output = process.getOutputStream();
            while (true) {
                WorldStreamControl.Command command;
                synchronized (lock) {
                    if ((closing || handle.stopping()) && !closeWritten) {
                        closeWritten = true;
                        command = new WorldStreamControl.Close(descriptor.nonce());
                    } else if (closeWritten) {
                        return;
                    } else if (!reliableCommands.isEmpty()) {
                        command = reliableCommands.removeFirst();
                    } else if (pendingCamera != null) {
                        command = pendingCamera;
                        pendingCamera = null;
                    } else {
                        lock.wait(20);
                        continue;
                    }
                }
                output.write(WorldStreamControl.encode(command));
                output.flush();
            }
        }

        private void readStatuses(Process process, WorldStreamLaunchOwner.Handle handle)
                throws Exception {
            try {
                while (true) {
                    var message = stdout.read(process.getInputStream());
                    if (message == null) {
                        if (!closedStatus) {
                            throw new IOException("World status EOF before CLOSED");
                        }
                        return;
                    }
                    var change = state.status(message);
                    var seam = statusSeam;
                    if (seam != null) {
                        seam.accept(change);
                    }
                    switch (change) {
                        case SESSION -> {
                            handle.initialized();
                            if (!closing && !handle.stopping()) {
                                initialized.complete(null);
                            } else {
                                initialized.completeExceptionally(
                                        problem("World session stopped during initialization"));
                            }
                        }
                        case CAMERA -> frames.applied((WorldStreamStatus.CameraApplied) message);
                        case FRAME -> frameSignaled();
                        case PRESENTED -> {
                            var listener = presentedListener;
                            if (listener != null) {
                                var presented = (WorldStreamStatus.Presented) message;
                                try {
                                    listener.presented(presented.camera(), presented.content());
                                } catch (RuntimeException failure) {
                                    // A consumer's fault must not end the status reader.
                                    System.getLogger(WorldStreamTransport.class.getName())
                                            .log(
                                                    System.Logger.Level.WARNING,
                                                    "World presented listener failed",
                                                    failure);
                                }
                            }
                        }
                        case INPUT_COMMIT -> {
                            CommitOperation operation;
                            synchronized (lock) {
                                operation = commitOperation;
                            }
                            enqueueFile(() -> deleteCommit(operation));
                        }
                        case COMMITTED -> {
                            CommitOperation operation;
                            CommitReceipt receipt;
                            synchronized (lock) {
                                operation = commitOperation;
                                if (operation == null
                                        || !operation.outstanding
                                        || !frames.committed(
                                                (WorldStreamStatus.Committed) message)) {
                                    throw new IllegalStateException(
                                            "Commit acknowledgement correlation");
                                }
                                operation.outstanding = false;
                                retireReliable();
                                releaseRetiredSplat();
                                receipt =
                                        new CommitReceipt(
                                                operation.metadata.next,
                                                operation.metadata.policy,
                                                operation.ordered);
                                currentReceipt = receipt;
                                if (operation.deleted) {
                                    commitRequested = false;
                                }
                            }
                            capacityChanged();
                            if (!closing && !handle.stopping()) {
                                operation.result.complete(receipt);
                            } else {
                                operation.result.completeExceptionally(
                                        new java.util.concurrent.CancellationException(
                                                "World stopped"));
                            }
                            TileReservation candidate;
                            synchronized (lock) {
                                candidate = tile;
                            }
                            if (candidate != null) {
                                publishCancellation(candidate);
                            }
                        }
                        case INPUT_TILE -> {
                            var current = reservationFor(transaction(message));
                            current.consumedAt =
                                    WorldStageTimings.lap(
                                            "handoff.parent.offerToInputConsumed",
                                            current.offeredAt);
                            enqueueFile(
                                    () -> {
                                        WorldStreamPacketFile.Moved owned;
                                        synchronized (lock) {
                                            owned = input;
                                        }
                                        if (owned == null) {
                                            throw new IllegalStateException(
                                                    "Missing advertised input owner");
                                        }
                                        owned.close();
                                        state.retireTileFile(current.ticket);
                                        publishCancellation(current);
                                        capacityChanged();
                                        synchronized (lock) {
                                            input = null;
                                            lock.notifyAll();
                                        }
                                    });
                        }
                        case STAGED -> {
                            TileReservation current = reservationFor(transaction(message));
                            WorldStageTimings.lap(
                                    "handoff.parent.inputConsumedToStaged", current.consumedAt);
                            synchronized (lock) {
                                if (!current.offerOutstanding) {
                                    throw new IllegalStateException("Missing offer command credit");
                                }
                                current.offerOutstanding = false;
                                retireReliable();
                            }
                            if (!closing && !handle.stopping() && !current.stop) {
                                current.result.complete(new Resident(current.ticket.resident));
                            } else {
                                current.result.completeExceptionally(
                                        new java.util.concurrent.CancellationException(
                                                "World stopped"));
                            }
                            // Diagnostics after delivery, so they never delay the tile.
                            if (current.ticket.resident.objects == 2) {
                                rendererObjectsFailed();
                            }
                        }
                        case CANCELED -> {
                            TileReservation current = reservationFor(transaction(message));
                            synchronized (lock) {
                                if (!current.cancelOutstanding) {
                                    throw new IllegalStateException(
                                            "Missing cancel command credit");
                                }
                                if (current.offerOutstanding) {
                                    current.offerOutstanding = false;
                                    retireReliable();
                                }
                                current.cancelOutstanding = false;
                                retireReliable();
                            }
                            publishCancellation(current);
                            current.result.completeExceptionally(
                                    new java.util.concurrent.CancellationException(
                                            "Tile canceled"));
                        }
                        case REJECTED -> {
                            CommitOperation operation;
                            synchronized (lock) {
                                operation = commitOperation;
                            }
                            if (operation != null
                                    && operation.metadata.transaction
                                            == ((WorldStreamStatus.Rejected) message)
                                                    .transaction()) {
                                synchronized (lock) {
                                    if (!operation.outstanding
                                            || !frames.rejectCommit(
                                                    (WorldStreamStatus.Rejected) message)) {
                                        throw new IllegalStateException(
                                                "Rejected commit correlation");
                                    }
                                    operation.outstanding = false;
                                    retireReliable();
                                    if (operation.deleted) {
                                        commitRequested = false;
                                    }
                                }
                                capacityChanged();
                                operation.result.completeExceptionally(
                                        new java.io.IOException("World commit rejected"));
                                break;
                            }
                            TileReservation current = reservationFor(transaction(message));
                            boolean cancelPending;
                            synchronized (lock) {
                                if (!current.offerOutstanding) {
                                    throw new IllegalStateException(
                                            "Missing rejected offer credit");
                                }
                                current.offerOutstanding = false;
                                retireReliable();
                                cancelPending = current.cancelOutstanding;
                            }
                            var error =
                                    new PreparationRefusal(
                                            Refusal.PACKET_REJECTED,
                                            "World helper rejected the tile packet",
                                            null);
                            if (!cancelPending) {
                                publishCancellation(current);
                            }
                            current.result.completeExceptionally(error);
                            tileRejected();
                        }
                        case CANCEL_REJECTED -> {
                            TileReservation current = reservationFor(transaction(message));
                            synchronized (lock) {
                                if (!current.cancelOutstanding) {
                                    throw new IllegalStateException("Missing stale cancel credit");
                                }
                                current.cancelOutstanding = false;
                                retireReliable();
                            }
                            capacityChanged();
                            publishCancellation(current);
                        }
                        case CLOSED -> {
                            closedStatus = true;
                            handle.closedAcknowledged();
                            requestClose();
                            handle.closeAsync();
                        }
                        case OBJECTS -> {
                            var outcome = ((WorldStreamStatus.ObjectsDone) message).outcome();
                            synchronized (lock) {
                                var operation = objectsOperation;
                                if (operation == null
                                        || !operation.advertised
                                        || operation.reply != null) {
                                    throw new IllegalStateException(
                                            "Late objects outcome correlation");
                                }
                                operation.reply = outcome;
                                retireReliable();
                                lock.notifyAll();
                            }
                            if (outcome == WorldStreamStatus.LateOutcome.FAILED) {
                                rendererObjectsFailed();
                            } else if (outcome == WorldStreamStatus.LateOutcome.BUDGET) {
                                rendererObjectsBudget();
                            }
                        }
                        case CREDIT -> {
                            // The returned lease belongs to a reservation: count the change as its
                            // own, so a refused reservation's unused credit is in its baseline
                            // and cannot wake the retry it refused (#366 review).
                            var credited = state.creditedTicket();
                            TileReservation owner = null;
                            synchronized (lock) {
                                if (tile != null && tile.ticket == credited) {
                                    owner = tile;
                                } else if (previousTile != null
                                        && previousTile.ticket == credited) {
                                    owner = previousTile;
                                }
                            }
                            if (owner == null) {
                                capacityChanged();
                            } else {
                                capacityChanged(owner);
                            }
                        }
                        case IGNORED -> {}
                        default ->
                                throw new IOException(
                                        "Unexpected World status without published operation");
                    }
                }
            } catch (Throwable error) {
                fail(error);
                throw error;
            }
        }

        private void drainErrors(Process process) throws IOException {
            // Keep draining so the helper never blocks on a full pipe; retain only a bounded,
            // sanitized tail for the abnormal-exit and renderer objects failure log records.
            byte[] scratch = new byte[4096];
            var errors = process.getErrorStream();
            int read;
            while ((read = errors.read(scratch)) != -1) {
                stderrTail.accept(scratch, 0, read);
            }
        }

        /**
         * The first late objects write failure of a session, and then every 64th with a count, is
         * logged: terrain continues, so a failing writer would otherwise leave no evidence.
         */
        private void objectsWriteFailed(Throwable failed) {
            long count = objectsWriteFailures.incrementAndGet();
            if (!cadenceLogged(count)) {
                return;
            }
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "World exterior objects file could not be written ("
                                        + count
                                        + (count == 1 ? " failure" : " failures")
                                        + " this session); tile kept as terrain only: "
                                        + HelperStderrTail.redact(
                                                WorldStreamLaunchOwner.Handle.detached(failed)
                                                        .getMessage()));
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt objects delivery.
            }
        }

        /** A repeated diagnostic is logged for its first occurrence, then every 64th. */
        static boolean cadenceLogged(long count) {
            return count == 1 || count % 64 == 0;
        }

        /**
         * The renderer reported a tile's objects as failed (inline or late); terrain is kept. The
         * first such failure of a session, then every 64th, is logged with the helper's redacted
         * stderr tail, which holds the renderer's reason (for example an object pass that could not
         * load its shaders). Otherwise the tail is only logged when the helper ends abnormally.
         */
        private void rendererObjectsFailed() {
            long count = rendererObjectsFailures.incrementAndGet();
            if (!cadenceLogged(count)) {
                return;
            }
            // Runs on the status reader: nothing here, redaction included, may end the session.
            try {
                String record =
                        "World renderer could not draw exterior objects ("
                                + count
                                + (count == 1 ? " tile" : " tiles")
                                + " this session); tiles kept as terrain only"
                                + stderrSuffix();
                objectsDiagnostic.complete(record);
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(System.Logger.Level.WARNING, record);
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt objects delivery.
            }
        }

        private void rendererObjectsBudget() {
            long count = rendererObjectsBudget.incrementAndGet();
            if (!cadenceLogged(count)) {
                return;
            }
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(
                                System.Logger.Level.INFO,
                                "World exterior objects budget exhausted ("
                                        + count
                                        + (count == 1 ? " tile" : " tiles")
                                        + " this session); tiles kept as terrain only, retried"
                                        + " as the camera moves");
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt objects delivery.
            }
        }

        /** Test observation of the first renderer objects failure record of the session. */
        CompletableFuture<String> objectsDiagnostic() {
            return objectsDiagnostic.copy();
        }

        /**
         * Bounded, redacted evidence for a stalled-frame report, without waiting on the helper:
         * pending upload, commit and late objects work, queued reliable commands, whether a frame
         * awaits the viewer, the ignored stdout noise count and the helper stderr tail. Never ends
         * or changes the session.
         *
         * <p>Runs on the FX thread during a stall, when a session thread may hold {@code lock}, so
         * it never takes it: the fields are read racily as a best-effort snapshot. A stale or mixed
         * view is acceptable for a diagnostic, and none of these reads can throw.
         */
        public String stallSummary() {
            boolean upload = tile != null;
            boolean commit = commitOperation != null;
            boolean objects = objectsOperation != null;
            boolean frame = latestFrame != null;
            int commands = reliableCommands.size();
            int reliable = outstandingReliable;
            long noiseLines = stdout.noiseLines(), noiseBytes = stdout.noiseBytes();
            long pid = childPid;
            return (pid == 0 ? "no child process" : "pid " + pid)
                    + (closing ? "; closing" : "")
                    + "; tile upload "
                    + (upload ? "pending" : "idle")
                    + "; commit "
                    + (commit ? "pending" : "idle")
                    + "; late objects "
                    + (objects ? "pending" : "idle")
                    + "; queued commands "
                    + commands
                    + "; outstanding reliable "
                    + reliable
                    + "; frame awaiting viewer "
                    + (frame ? "yes" : "no")
                    + "; unrelated stdout ignored "
                    + noiseLines
                    + (noiseLines == 1 ? " line, " : " lines, ")
                    + noiseBytes
                    + (noiseBytes == 1 ? " byte" : " bytes")
                    + stderrSuffix();
        }

        /**
         * Once per session, on the status reader: the helper wrote a line that is not protocol (for
         * example JVM or JFR startup text). It is ignored; this warning names it, redacted.
         */
        private void unrelatedStdout(String firstLine) {
            try {
                String record =
                        "World helper wrote output unrelated to its protocol on stdout; ignoring"
                                + " it (first line: "
                                + firstLine
                                + ")";
                stdoutDiagnostic.complete(record);
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(System.Logger.Level.WARNING, record);
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt status reading.
            }
        }

        /** After a clean close: how much unrelated stdout was ignored, at debug level, no tail. */
        private void cleanNoiseCounts() {
            long lines = stdout.noiseLines(), bytes = stdout.noiseBytes();
            if (lines == 0 && bytes == 0) {
                return;
            }
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(
                                System.Logger.Level.DEBUG,
                                "World helper closed cleanly; unrelated stdout ignored: "
                                        + lines
                                        + (lines == 1 ? " line, " : " lines, ")
                                        + bytes
                                        + (bytes == 1 ? " byte" : " bytes"));
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt lifetime ownership.
            }
        }

        /** Test observation of the session's one unrelated stdout warning. */
        CompletableFuture<String> stdoutDiagnostic() {
            return stdoutDiagnostic.copy();
        }

        private String stderrSuffix() {
            return !stderrAttached
                    ? "; helper stderr not captured"
                    : stderrTail.isEmpty()
                            ? "; helper stderr empty"
                            : "; helper stderr tail:\n" + stderrTail.snapshot();
        }

        private static String exitSuffix(WorldStreamLaunchOwner.Drain drain) {
            return drain.exitCode().isEmpty() ? "" : " (exit " + drain.exitCode().getAsInt() + ")";
        }

        /** Test observation of the bounded helper stderr tail so far. */
        String stderrSnapshot() {
            return stderrTail.snapshot();
        }

        /** Test observation of the single abnormal-end record; empty after a clean end. */
        CompletableFuture<String> diagnosticReport() {
            return diagnosticReport.copy();
        }

        private void report(
                String summary,
                WorldStreamLaunchOwner.Drain drain,
                Throwable cause,
                boolean terminal) {
            var text = new StringBuilder(summary);
            long pid = childPid;
            if (drain != null && drain.childPid().isPresent()) {
                pid = drain.childPid().getAsLong();
            }
            text.append(pid == 0 ? "; no child process" : "; pid " + pid);
            if (drain != null && drain.exitCode().isPresent()) {
                text.append("; exit ").append(drain.exitCode().getAsInt());
            }
            text.append("; closed status ").append(closedStatus ? "received" : "not received");
            if (cause != null) {
                // Only the already detached, bounded transport message; never a nested chain.
                text.append("; failure: ")
                        .append(
                                HelperStderrTail.redact(
                                        WorldStreamLaunchOwner.Handle.detached(cause)
                                                .getMessage()));
            }
            text.append(stdout.suffix());
            text.append(stderrSuffix());
            String record = text.toString();
            try {
                System.getLogger(WorldStreamTransport.class.getName())
                        .log(System.Logger.Level.WARNING, record);
            } catch (RuntimeException | Error ignored) {
                // Diagnostic delivery cannot interrupt lifetime ownership.
            } finally {
                if (terminal) {
                    diagnosticReport.complete(record);
                }
            }
        }

        private IOException problem(String message) {
            return failure == null ? new IOException(message) : failure;
        }

        private void failPending(Throwable failure) {
            TileReservation current;
            synchronized (lock) {
                current = tile;
            }
            CommitOperation pending;
            synchronized (lock) {
                pending = commitOperation;
            }
            if (pending != null) {
                pending.result.completeExceptionally(failure);
            }
            ObjectsOperation objects;
            synchronized (lock) {
                objects = objectsOperation;
            }
            if (objects != null) {
                objects.result.completeExceptionally(failure);
            }
            if (current != null) {
                current.result.completeExceptionally(failure);
                current.canceled.completeExceptionally(failure);
            }
        }

        private void fail(Throwable error) {
            // Secure the lifetime before diagnostic encoding or any future/user callback.
            requestClose();
            var handle = owner;
            if (handle != null) {
                handle.closeAsync();
            }
            var detached = WorldStreamLaunchOwner.Handle.detached(error);
            if (failure == null) {
                failure = detached;
            }
            initialized.completeExceptionally(failure);
            completion.completeExceptionally(failure);
            failPending(failure);
        }
    }

    private WorldStreamTransport() {}
}
