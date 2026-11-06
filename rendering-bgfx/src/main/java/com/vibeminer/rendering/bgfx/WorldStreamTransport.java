package com.vibeminer.rendering.bgfx;

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
            String scopeSha256, long initialPolicy, Backend backend, boolean exteriorObjects) {
        public Spec(String scopeSha256, long initialPolicy, Backend backend) {
            this(scopeSha256, initialPolicy, backend, false);
        }

        public Spec {
            Objects.requireNonNull(backend);
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
        DROPPED
    }

    public enum Refusal {
        RETRY_LATER,
        CAPABILITY_UNAVAILABLE,
        SOURCE_INVALIDATED
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

        private PacketDestination(
                WorldStreamEnvelope.Session descriptor,
                WorldStreamParentState.Ticket ticket,
                Path path,
                WorldStreamProducerCancellation.Producer cancellation) {
            exteriorObjects = descriptor.exteriorObjects();
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

    public static final class TileReservation {
        private final Session session;
        private final WorldStreamParentState.Ticket ticket;
        private final CompletableFuture<Resident> result = new CompletableFuture<>();
        private final CompletableFuture<Void> canceled = new CompletableFuture<>();
        private volatile boolean stop;
        private volatile WorldStreamProducerCancellation.Producer cancellation;
        private boolean produced, offerOutstanding, cancelOutstanding;
        // Guarded by the session lock: the capacity revision when this reservation was issued and
        // the capacity changes it made itself before its result completed (#283).
        private long issuedCapacity;
        private int ownCapacityChanges;

        private TileReservation(Session session, WorldStreamParentState.Ticket ticket) {
            this.session = session;
            this.ticket = ticket;
        }

        /**
         * The capacity revision this reservation alone accounts for: the revision when it was
         * issued plus its own retirement and drain changes up to its result. Once the result has
         * completed, a session revision above this means capacity changed for some other reason
         * since the reservation was issued, so a caller blocked by its refusal should retry.
         */
        public long capacityBaseline() {
            return session.baseline(this);
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

        public java.nio.IntBuffer pixels() {
            return frame.pixels();
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
                        spec.exteriorObjects());
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
        private final java.util.concurrent.atomic.AtomicLong rendererObjectsFailures =
                new java.util.concurrent.atomic.AtomicLong();
        private final CompletableFuture<String> objectsDiagnostic = new CompletableFuture<>();
        // Protocol records from helper stdout; unrelated lines are counted and sampled, never
        // decoded. Their sample joins the abnormal-end record.
        private final WorldStreamStdout stdout;
        private final CompletableFuture<String> stdoutDiagnostic = new CompletableFuture<>();

        // Reliable parent commands in flight, as a sum of named parts so a change to one (e.g.
        // more tiles in flight, #371) adds to it without re-deriving the total.
        private static final int RELIABLE_TILE = 2; // the tile's OFFER and its CANCEL
        private static final int RELIABLE_COMMIT = 1;
        private static final int RELIABLE_OBJECTS = 1; // late OBJECTS
        private static final int RELIABLE_HORIZON = 1; // the session's one HORIZON (#372)
        private static final int RELIABLE_COMMANDS =
                RELIABLE_TILE + RELIABLE_COMMIT + RELIABLE_OBJECTS + RELIABLE_HORIZON;

        private ObjectsOperation objectsOperation;
        // The session's one horizon WDL (#372) and whether any commit has completed yet.
        private HorizonOperation horizonOperation;
        private boolean horizonRequested, horizonStarted, committedOnce;

        /** The one horizon delivery: file write, HORIZON, reply, file delete. */
        private static final class HorizonOperation {
            final byte[] wdl;
            final CompletableFuture<HorizonOutcome> result = new CompletableFuture<>();
            boolean advertised;
            WorldStreamStatus.HorizonOutcome reply;

            HorizonOperation(byte[] wdl) {
                this.wdl = wdl;
            }
        }

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

        /**
         * Sends the map's WDL for the child to draw as a horizon (#372); at most once per session.
         * The file is written after the first commit, so it never delays the first visible tile.
         * The future completes with the child's one outcome; any failure only means no horizon. A
         * stopping session cancels it.
         */
        public CompletableFuture<HorizonOutcome> attachHorizon(byte[] wdl) {
            Objects.requireNonNull(wdl);
            if (wdl.length < 12
                    || wdl.length > com.vibeminer.formats.world.WorldLowRes.MAX_SOURCE_BYTES) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Horizon WDL size"));
            }
            synchronized (lock) {
                if (closing || owner.stopping()) {
                    return CompletableFuture.failedFuture(
                            new java.util.concurrent.CancellationException("World stopped"));
                }
                if (horizonRequested) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Horizon already sent for this session"));
                }
                horizonRequested = true;
                var operation = new HorizonOperation(wdl.clone());
                horizonOperation = operation;
                lock.notifyAll();
                return operation.result.copy();
            }
        }

        /** On the objects worker: the one horizon delivery, once the first commit is done. */
        private void deliverHorizon(
                HorizonOperation operation, WorldStreamLaunchOwner.Handle handle) throws Exception {
            Path path = handle.files().directory().resolve(WorldStreamControl.Horizon.BASENAME);
            WorldStreamStatus.HorizonOutcome outcome = null;
            // Set before the write, so a partial file from a failed write is deleted too.
            boolean written = false;
            Throwable fatal = null;
            try {
                // Session scratch: no fsync, the same as tile files (#364).
                written = true;
                java.nio.file.Files.write(
                        path,
                        operation.wdl,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                String sha =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(operation.wdl));
                synchronized (lock) {
                    var command = state.horizon(operation.wdl.length, sha);
                    if (command != null && !closing && !handle.stopping()) {
                        operation.advertised = enqueueCommand(command);
                    }
                    if (command != null && !operation.advertised) {
                        state.horizonUnsent();
                    }
                }
                if (operation.advertised) {
                    synchronized (lock) {
                        while (operation.reply == null && !(actualExit && statusesDrained)) {
                            lock.wait(20);
                        }
                        outcome = operation.reply;
                    }
                }
            } catch (IOException failed) {
                // Only the horizon is lost; the session goes on.
                outcome = WorldStreamStatus.HorizonOutcome.FAILED;
            } catch (Throwable failed) {
                fatal = failed;
            } finally {
                if (written) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
            if (fatal != null) {
                operation.result.completeExceptionally(fatal);
                fail(fatal);
            } else if (outcome != null) {
                operation.result.complete(HorizonOutcome.valueOf(outcome.name()));
            } else {
                operation.result.completeExceptionally(
                        new java.util.concurrent.CancellationException("World stopped"));
            }
        }

        public CompletableFuture<Void> initialized() {
            return initialized.copy();
        }

        /** Whether this session's profile admits BASIC_EXTERIOR objects. */
        public boolean exteriorObjects() {
            return descriptor.exteriorObjects();
        }

        /** Protocol success is separate from process/resource drain. */
        public CompletableFuture<Void> completion() {
            return completion.copy();
        }

        /** Bounded array snapshot on caller; validation/publication and all IO run off caller. */
        public CompletableFuture<CommitReceipt> commit(long policy, Resident... ordered) {
            Objects.requireNonNull(ordered);
            if (ordered.length > 9 || policy < descriptor.policy()) {
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
                HorizonOperation wdl = null;
                synchronized (lock) {
                    operation = objectsOperation;
                    if (operation == null
                            && horizonOperation != null
                            && committedOnce
                            && !horizonStarted
                            && !handle.stopping()) {
                        // The horizon shares this worker so its 8 MiB write and digest never
                        // stall tile production; it goes only after the first visible tile.
                        horizonStarted = true;
                        wdl = horizonOperation;
                    }
                    if (operation == null && wdl == null) {
                        if (handle.stopping()) {
                            if (horizonOperation != null && !horizonStarted) {
                                horizonOperation.result.completeExceptionally(
                                        new java.util.concurrent.CancellationException(
                                                "World stopped"));
                            }
                            return;
                        }
                        lock.wait(20);
                        continue;
                    }
                }
                if (operation != null) {
                    deliverObjects(operation, handle);
                } else {
                    deliverHorizon(wdl, handle);
                }
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
                if (result == null
                        || closing
                        || owner.stopping()
                        || !frames.presented(result.frame.metadata())) {
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
                            latestFrame = new Frame(candidate, currentReceipt);
                            offered = true;
                        }
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
        }

        /** A capacity change caused by {@code reservation}'s own drain or retirement. */
        private void capacityChanged(TileReservation reservation) {
            synchronized (lock) {
                capacityChanged();
                reservation.ownCapacityChanges++;
            }
        }

        private long baseline(TileReservation reservation) {
            synchronized (lock) {
                return reservation.issuedCapacity + reservation.ownCapacityChanges;
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
                enqueueFile(() -> produceFile(reservation, producer));
            }
            return reservation.result.copy();
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
                if (outstandingReliable == RELIABLE_COMMANDS) {
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
                if (outstandingReliable < 1 || outstandingReliable > RELIABLE_COMMANDS) {
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

        /** Replaces one pending camera; no pipe I/O or external callback occurs on the caller. */
        public void camera(Camera camera) {
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
                frames.desire(command);
                pendingCamera = command;
                lock.notifyAll();
            }
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
                                receipt =
                                        new CommitReceipt(
                                                operation.metadata.next,
                                                operation.metadata.policy,
                                                operation.ordered);
                                currentReceipt = receipt;
                                // The horizon waits for the first visible tile (#372).
                                committedOnce = true;
                                lock.notifyAll();
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
                            var error = new IOException("World packet rejected");
                            if (!cancelPending) {
                                publishCancellation(current);
                            }
                            current.result.completeExceptionally(error);
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
                            requestClose();
                            handle.closeAsync();
                        }
                        case HORIZON -> {
                            synchronized (lock) {
                                var operation = horizonOperation;
                                if (operation != null
                                        && operation.advertised
                                        && operation.reply == null) {
                                    operation.reply =
                                            ((WorldStreamStatus.HorizonDone) message).outcome();
                                    retireReliable();
                                    lock.notifyAll();
                                }
                            }
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
                            }
                        }
                        case CREDIT -> capacityChanged();
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
            HorizonOperation wdl;
            synchronized (lock) {
                wdl = horizonOperation;
            }
            if (wdl != null) {
                wdl.result.completeExceptionally(failure);
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

    /** The child's one answer to the session's horizon WDL (#372); never session-fatal. */
    public enum HorizonOutcome {
        /** Read and attached; drawn from the next frame on. */
        DRAWN,
        /** A valid WDL with no readable tile. */
        NONE,
        /** Write, read, validation or allocation failed; the session continues without it. */
        FAILED
    }

    private WorldStreamTransport() {}
}
