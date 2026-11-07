package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldStageTimings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Child-only World v2 runtime. Not connected to the legacy viewer or GUI. */
final class WorldStreamSession {
    private record Decoded(
            WorldStreamPackedTile tile,
            WorldStreamTileFile.Header header,
            WorldStreamEnvelope.Commit commit,
            Throwable error) {}

    private record LateDecoded(WorldStreamPackedObjects objects, Throwable error) {}

    // Display rate. Three readback slots keep up: each completes within two bgfx frames, and the
    // owner loop advances bgfx every ~1 ms.
    static final long DRAW_INTERVAL = TimeUnit.SECONDS.toNanos(1) / 60;
    private final WorldStreamEnvelope.Session session;
    private final Path directory;
    private final long parentPid;
    private final WorldControlMailbox mailbox;
    private final WorldStreamState state;
    private final WorldStatusDeadline deadline = new WorldStatusDeadline();
    private final WorldStreamStatusWriter status;
    private final AtomicBoolean closing = new AtomicBoolean(), allDrained = new AtomicBoolean();
    private final AtomicBoolean decoderCanceled = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService decoder =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("world-v2-packet-decoder").factory());
    // Late objects: their own decoder and upload, never ahead of a terrain tile's.
    private final AtomicBoolean objectsCanceled = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService objectsDecoder =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("world-v2-objects-decoder").factory());
    private CompletableFuture<LateDecoded> objectsDecoding;
    // The horizon WDL (#372): read and parsed on its own worker, never on the tile decoder.
    private final AtomicBoolean horizonCanceled = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService horizonDecoder =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("world-v2-horizon-decoder").factory());
    private CompletableFuture<HorizonDecoded> horizonDecoding;

    private record HorizonDecoded(
            com.vibeminer.formats.world.WorldLowRes.LowRes lowRes, Throwable error) {}

    private WorldStreamControl.LateObjects objectsCommand;
    private BgfxWorldStreamRenderer.LateUpload lateUpload;
    private volatile boolean failed;
    private volatile long closeStarted;
    private CompletableFuture<Decoded> decoding;
    private WorldStreamControl.Command decodedCommand, deferred;
    private WorldStreamControl.Camera camera;
    private BgfxWorldStreamRenderer.Upload upload;
    // Opt-in measurement only (-Dvimi.world.stageTimings); stamps are 0 while it is off.
    private final WorldStageTimingsDump timingsDump = WorldStageTimingsDump.configured();
    private long uploadStarted, lateStarted;
    // Opt-in hand-off stamps of the one offer in flight; 0 while timings are off.
    private volatile long offerReadAt, decodeDoneAt;
    private WorldStreamTileFile.Header uploadHeader;
    private int issued, released;
    private boolean staged, cancelAcknowledgement;
    private WorldStreamState.Lease canceledLease;
    private long canceledTransaction;
    private long advertisedCredit, lastDraw;

    private WorldStreamSession(
            WorldStreamEnvelope.Session session,
            Path directory,
            OutputStream output,
            long parentPid) {
        this.parentPid = parentPid;
        this.session = Objects.requireNonNull(session);
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        mailbox = new WorldControlMailbox(session.nonce());
        state = new WorldStreamState(session.nonce(), session.maxActive());
        status = new WorldStreamStatusWriter(session.nonce(), output, deadline);
    }

    /** Child-only entry; validates metadata before creating any worker or pipe owner. */
    static boolean run(
            WorldStreamEnvelope.Session session,
            Path directory,
            InputStream input,
            OutputStream output,
            WorldFrameFileV2 frames,
            int backend,
            long parentPid,
            Runnable terminate)
            throws Exception {
        Objects.requireNonNull(session);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
        Objects.requireNonNull(frames);
        Objects.requireNonNull(terminate);
        if (parentPid <= 0) {
            throw new IllegalArgumentException("Invalid parent PID");
        }
        return run(session, directory, input, output, frames, backend, parentPid, terminate, null);
    }

    /**
     * #381 spike: with {@code window}, the native-presentation loop variant (frames presented into
     * the child window at the display rate, uploads paced by {@link WorldPresentBudget}).
     */
    static boolean run(
            WorldStreamEnvelope.Session session,
            Path directory,
            InputStream input,
            OutputStream output,
            WorldFrameFileV2 frames,
            int backend,
            long parentPid,
            Runnable terminate,
            WorldWindowPresentation window)
            throws Exception {
        Objects.requireNonNull(session);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
        Objects.requireNonNull(frames);
        Objects.requireNonNull(terminate);
        if (parentPid <= 0) {
            throw new IllegalArgumentException("Invalid parent PID");
        }
        var runtime = new WorldStreamSession(session, directory, output, parentPid);
        runtime.window = window;
        runtime.runOwner(input, frames, backend, terminate);
        return !runtime.failed;
    }

    // #381 spike: native presentation (null: the readback loop, unchanged).
    private WorldWindowPresentation window;
    private final WorldPresentBudget budget = new WorldPresentBudget();

    private void runOwner(
            InputStream input, WorldFrameFileV2 frames, int backend, Runnable terminate)
            throws Exception {
        Thread.ofVirtual().name("world-v2-control-reader").start(() -> readControls(input));
        Thread.ofVirtual().name("world-v2-independent-watchdog").start(() -> watch(terminate));
        try (var owner = new WorldStreamNativeOwner(session, frames, backend, window)) {
            try {
                send(
                        session.exteriorObjects()
                                ? "SESSION TERRAIN_EXTERIOR_V1"
                                : "SESSION TERRAIN_BASIC_V1");
                advertiseCredit();
                while (!closing.get()) {
                    if (mailbox.closing() || status.failure() != null) {
                        if (mailbox.failed()) {
                            fail("control mailbox failed");
                        } else if (status.failure() != null) {
                            fail("status write failed: " + describe(status.failure()));
                        }
                        requestClose();
                        break;
                    }
                    reconcile();
                    completeDecode(owner);
                    completeObjectsDecode(owner);
                    var update = mailbox.takeCamera();
                    if (update != null) {
                        camera = update;
                    }
                    var cancellation =
                            decoding != null && deferred != null
                                    ? mailbox.takeCancellation()
                                    : null;
                    if (cancellation != null && !mailbox.closing()) {
                        execute(owner, cancellation);
                    }
                    var command = deferred == null ? mailbox.takeTransaction() : deferred;
                    deferred = null;
                    if (command != null && !mailbox.closing()) {
                        execute(owner, command);
                    }
                    takeObjects();
                    takeHorizon();
                    completeHorizon(owner);
                    reconcile();
                    if (window != null) {
                        stepWithinFrameBudget(owner); // #381 spike
                    } else if (upload != null && !upload.finished()) {
                        long stepping = WorldStageTimings.start();
                        owner.step(upload);
                        WorldStageTimings.stop("child.tile.uploadStep", stepping);
                        reconcile();
                    } else if (lateUpload != null && !lateUpload.ready()) {
                        // Terrain keeps priority: late object slices use only idle iterations.
                        long stepping = WorldStageTimings.start();
                        owner.stepLate(lateUpload);
                        WorldStageTimings.stop("child.objects.uploadStep", stepping);
                    } else if (owner.horizonUploading()) {
                        // The horizon uploads after terrain and late objects, in bounded steps.
                        owner.stepHorizon();
                    }
                    // GPU mutations must precede draw submission in the same bgfx frame.
                    long now = System.nanoTime();
                    // Native presentation draws every presented frame; readback keeps its cap.
                    if (camera != null
                            && (window != null || now - lastDraw >= DRAW_INTERVAL)
                            && owner.submit(camera)) {
                        lastDraw = nextDrawBase(lastDraw, now);
                        status.cameraApplied(camera.policy(), camera.width(), camera.height());
                    }
                    if (owner.advance()) {
                        // Wake the parent's frame reader; the mapping write already completed.
                        status.framePublished(owner.publishedSequence());
                    }
                    reconcile();
                    finishUpload();
                    finishLate(owner);
                    advertiseCredit();
                    timingsDump.maybeWrite(now);
                    if (window == null) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    } // Native presentation: advance() paces itself on the display.
                }
                if (Boolean.getBoolean(FRAME_TIMINGS_PROPERTY)) {
                    // Diagnostics for the readback comparison (#370): stderr, never protocol.
                    System.err.println(
                            "WORLD_FRAME_TIMINGS readback="
                                    + (owner.stagingReadback() ? "staging" : "direct")
                                    + " meanPublishMicros="
                                    + owner.meanPublishNanos() / 1000
                                    + " advance "
                                    + owner.advanceSummary());
                    System.err.flush();
                }
                requestClose();
                decoderCanceled.set(true);
                objectsCanceled.set(true);
                horizonCanceled.set(true);
                // Keep the owner advancing while the actual input/packing worker relinquishes
                // ownership.
                while (decoding != null || objectsDecoding != null) {
                    completeDecode(owner);
                    completeObjectsDecode(owner);
                    owner.advance();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                reconcile();
                if (upload != null && !upload.finished()) {
                    owner.cancel(upload);
                }
                state.close();
            } catch (Throwable error) {
                fail("native owner: " + describe(error));
                requestClose(); // Arm the independent deadline BEFORE native close can block.
                throw error;
            }
        } catch (Throwable error) {
            fail("session: " + describe(error));
            requestClose();
            throw error;
        } finally {
            timingsDump.write();
            requestClose();
            decoderCanceled.set(true);
            objectsCanceled.set(true);
            horizonCanceled.set(true);
            decoder.shutdown();
            objectsDecoder.shutdown();
            horizonDecoder.shutdown();
            // This is a child-only drain. The independent watchdog remains armed throughout it.
            while (!decoder.awaitTermination(100, TimeUnit.MILLISECONDS)
                    || !objectsDecoder.awaitTermination(100, TimeUnit.MILLISECONDS)
                    || !horizonDecoder.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                // Do not close a stream under another thread's IO monitor.
            }
            if (decoding != null) {
                var result = decoding.join();
                if (result.tile() != null) {
                    result.tile().close();
                }
                decoding = null;
            }
            if (objectsDecoding != null) {
                var result = objectsDecoding.join();
                if (result.objects() != null) {
                    result.objects().close();
                }
                objectsDecoding = null;
            }
            // Native close has now completed its callback drain; mirror those final releases
            // before claiming a successful session drain to the parent.
            reconcile();
            state.close();
            if (!state.drained()) {
                fail("state not drained at close: " + state.drainSummary());
            }
            status.quiet();
            if (!failed && status.failure() == null) {
                send("CLOSED");
            }
            status.finish().handle((ignored, error) -> null).join();
            if (failed) {
                System.err.println(
                        "World v2 session failed: "
                                + String.valueOf(failure).replaceAll("[\\p{Cntrl}]", " "));
                System.err.flush();
            }
            if (!failed && status.failure() == null) {
                allDrained.set(true);
            }
        }
    }

    /**
     * #381 spike: one presented frame's uploads, several bounded steps (each still at most
     * STEP_BOUND) until the frame's byte or time budget is spent, terrain before late objects.
     */
    private void stepWithinFrameBudget(WorldStreamNativeOwner owner) {
        budget.begin(System.nanoTime());
        while (budget.another(System.nanoTime())) {
            if (upload != null && !upload.finished()) {
                budget.stepped(owner.step(upload));
                reconcile();
            } else if (lateUpload != null && !lateUpload.ready()) {
                budget.stepped(owner.stepLate(lateUpload));
            } else {
                break;
            }
        }
    }

    /**
     * Keeps a steady display-rate phase while drawing continuously, so the ~1 ms loop park does not
     * stretch every interval; restarts the phase after an idle gap.
     */
    // Past this lateness the phase restarts: a stalled loop gets no wasted catch-up draw.
    static final long CATCH_UP_LIMIT = DRAW_INTERVAL * 5 / 4;

    static long nextDrawBase(long previous, long now) {
        return now - previous < CATCH_UP_LIMIT ? previous + DRAW_INTERVAL : now;
    }

    /** {@code -Dvimi.world.frameTimings=true}: print per-size frame timings to stderr at close. */
    static final String FRAME_TIMINGS_PROPERTY = "vimi.world.frameTimings";

    private void readControls(InputStream input) {
        try {
            while (!closing.get()) {
                var command = WorldStreamControl.read(input);
                if (command == null) {
                    requestClose();
                    return;
                }
                if (command instanceof WorldStreamControl.Offer) {
                    offerReadAt = WorldStageTimings.start();
                }
                var result = mailbox.offer(command);
                if (command instanceof WorldStreamControl.Close
                        && result == WorldControlMailbox.Result.ACCEPTED) {
                    requestClose(); // Arm the independent deadline even while native init is
                    // blocked.
                }
                if (result == WorldControlMailbox.Result.OVERFLOW) {
                    send("REJECTED 0 STATUS_OVERFLOW");
                    fail("control overflow");
                    requestClose();
                }
            }
        } catch (IOException | RuntimeException error) {
            send("REJECTED 0 PROTOCOL_ERROR");
            fail("control stream: " + describe(error));
            requestClose();
        }
    }

    private void watch(Runnable terminate) {
        while (!allDrained.get()) {
            if (!ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                requestClose();
            }
            var action = deadline.poll(allDrained.get());
            if (action == WorldStatusDeadline.Action.REQUEST_CLOSE) {
                fail("status deadline expired");
                requestClose();
            }
            if (action == WorldStatusDeadline.Action.TERMINATE
                    || closing.get()
                            && System.nanoTime() - closeStarted
                                    >= WorldStatusDeadline.TIMEOUT_NANOS) {
                terminate.run();
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }

    private synchronized void requestClose() {
        if (!closing.get()) {
            closeStarted = System.nanoTime();
            closing.set(true);
        }
    }

    private void execute(WorldStreamNativeOwner owner, WorldStreamControl.Command command) {
        if (command instanceof WorldStreamControl.Offer offer) {
            if (decoding != null) {
                deferred = command;
                return;
            }
            var result = state.offer(offer.nonce(), offer.transaction(), offer.lease());
            if (result != WorldStreamState.Result.OK) {
                reject(offer.transaction(), result);
                return;
            }
            decoderCanceled.set(false);
            decodedCommand = offer;
            long executed = WorldStageTimings.lap("child.handoff.offerReadToExecute", offerReadAt);
            decoding =
                    CompletableFuture.supplyAsync(
                            () -> {
                                WorldStageTimings.lap(
                                        "child.handoff.executeToDecodeStart", executed);
                                try {
                                    return decodeOffer(offer);
                                } finally {
                                    decodeDoneAt = WorldStageTimings.start();
                                }
                            },
                            decoder);
        } else if (command instanceof WorldStreamControl.Commit commit) {
            if (decoding != null) {
                deferred = command;
                return;
            }
            var reservation = state.reserveCommit(commit.nonce(), commit.transaction());
            if (reservation != WorldStreamState.Result.OK) {
                reject(commit.transaction(), reservation);
                return;
            }
            decodedCommand = commit;
            decoding = CompletableFuture.supplyAsync(() -> decodeCommit(commit), decoder);
        } else if (command instanceof WorldStreamControl.Cancel cancel) {
            var lease = incomingLease(cancel.slot(), cancel.generation());
            if (lease == null) {
                reject(cancel.transaction(), WorldStreamState.Result.STALE);
                return;
            }
            var result = state.cancel(cancel.nonce(), cancel.transaction(), lease);
            if (result == WorldStreamState.Result.OK) {
                decoderCanceled.set(true);
                if (upload != null && !upload.finished()) {
                    owner.cancel(upload);
                } else if (upload != null && staged) {
                    owner.cancel(upload);
                }
                cancelAcknowledgement = true;
                canceledLease = lease;
                canceledTransaction = cancel.transaction();
            } else {
                reject(cancel.transaction(), result);
            }
        } else if (command instanceof WorldStreamControl.CancelUnused unused) {
            var result = state.cancelUnused(unused.nonce(), unused.transaction(), unused.lease());
            if (result == WorldStreamState.Result.OK) {
                send("CANCELED " + unused.transaction() + leaseText(unused.lease()));
            } else {
                reject(unused.transaction(), result);
            }
        }
    }

    private WorldStreamState.Lease incomingLease(int slot, long generation) {
        var lease = uploadHeader == null ? null : uploadHeader.lease();
        if (decodedCommand instanceof WorldStreamControl.Offer offer) {
            lease = offer.lease();
        }
        return lease != null && lease.slot() == slot && lease.generation() == generation
                ? lease
                : null;
    }

    private Decoded decodeOffer(WorldStreamControl.Offer offer) {
        try {
            var tile =
                    decodeTile(session, directory, offer, decoderCanceled::get, objectsRejections);
            return new Decoded(tile, tile.header(), null, null);
        } catch (Throwable error) {
            return new Decoded(null, null, null, error);
        }
    }

    /**
     * Decoder-worker read of one offered tile. A terrain packet failure rejects the tile. A named
     * objects file is optional: any objects failure other than cancellation leaves the terrain
     * decoded with objects state 2, so the tile still stages and commits without buildings.
     */
    static WorldStreamPackedTile decodeTile(
            WorldStreamEnvelope.Session session,
            Path directory,
            WorldStreamControl.Offer offer,
            java.util.function.BooleanSupplier canceled)
            throws IOException {
        return decodeTile(session, directory, offer, canceled, new ObjectsRejections());
    }

    static WorldStreamPackedTile decodeTile(
            WorldStreamEnvelope.Session session,
            Path directory,
            WorldStreamControl.Offer offer,
            java.util.function.BooleanSupplier canceled,
            ObjectsRejections rejections)
            throws IOException {
        var expected =
                new WorldStreamTileFile.Expected(
                        session.nonce(),
                        session.scope(),
                        offer.transaction(),
                        offer.lease(),
                        offer.length(),
                        offer.sha256());
        var payload =
                WorldStreamTileFile.read(
                        directory.resolve("tile-" + offer.transaction() + ".wst"),
                        expected,
                        canceled);
        var header = payload.header();
        if (header.objects() != null && !session.exteriorObjects()) {
            payload.close();
            throw new IOException("Tile names objects outside the session profile");
        }
        WorldStreamPackedObjects objects = null;
        int state = session.exteriorObjects() ? 0 : -1;
        if (header.objects() != null) {
            try {
                var data =
                        WorldStreamObjectsFile.read(
                                directory.resolve(
                                        WorldStreamObjectsFile.basename(offer.transaction())),
                                WorldStreamObjectsFile.Identity.of(header),
                                header.objects(),
                                canceled);
                long packing = WorldStageTimings.start();
                objects = WorldStreamPackedObjects.pack(data, canceled);
                WorldStageTimings.stop("child.objects.pack", packing);
                state = 1;
            } catch (java.util.concurrent.CancellationException stopped) {
                payload.close();
                throw stopped;
            } catch (IOException | RuntimeException | OutOfMemoryError failed) {
                // Optional objects (including their native packing allocation) never cost terrain.
                if (canceled.getAsBoolean()) {
                    payload.close();
                    throw new java.util.concurrent.CancellationException();
                }
                rejections.reject(offer.transaction(), failed);
                state = 2;
            }
        }
        long packing = WorldStageTimings.start();
        var packed = WorldStreamPackedTile.prepare(payload, objects, state, canceled);
        WorldStageTimings.stop("child.tile.pack", packing);
        return packed;
    }

    /**
     * Objects sets refused by validation, packing or upload in one session: the tile keeps its
     * terrain and the reason goes to stderr, the first of the session and then every 64th. The
     * parent logs the redacted stderr tail with its objects-failure record. Thread-safe: the tile
     * decoder and the owner thread both report.
     */
    static final class ObjectsRejections {
        private final java.util.concurrent.atomic.AtomicLong count =
                new java.util.concurrent.atomic.AtomicLong();

        /** The record of this rejection; written to stderr when its count is sampled. */
        String reject(long transaction, Throwable failed) {
            return objectsRejected(count.incrementAndGet(), transaction, failed);
        }
    }

    private final ObjectsRejections objectsRejections = new ObjectsRejections();

    private static String objectsRejected(long count, long transaction, Throwable failed) {
        String record =
                "World exterior objects of tile transaction "
                        + transaction
                        + " rejected, terrain kept: "
                        + reason(failed)
                        + " ("
                        + count
                        + (count == 1 ? " objects set" : " objects sets")
                        + " this session)";
        if (count == 1 || count % 64 == 0) {
            System.err.println(record);
            System.err.flush();
        }
        return record;
    }

    /**
     * Admits one late objects command. A generation that is no longer active here (or already holds
     * objects) is answered DROPPED at once without opening the file.
     */
    private void takeObjects() {
        if (objectsCommand != null || closing.get() || mailbox.closing()) {
            return;
        }
        var command = mailbox.takeObjects();
        if (command == null) {
            return;
        }
        if (!session.exteriorObjects()) {
            throw new IllegalStateException("Late objects outside the session profile");
        }
        var admitted = state.objectsOffer(command.nonce(), command.transaction(), command.lease());
        if (admitted == WorldStreamState.Result.CLOSED) {
            return;
        }
        if (admitted == WorldStreamState.Result.BUSY) {
            throw new IllegalStateException("Second late objects command in flight");
        }
        if (admitted != WorldStreamState.Result.OK) {
            sendObjects(command, WorldStreamStatus.LateOutcome.DROPPED);
            return;
        }
        int coordinate = state.coordinate(command.lease());
        if (coordinate < 0) {
            throw new IllegalStateException("Admitted late objects without tile coordinates");
        }
        objectsCommand = command;
        objectsCanceled.set(false);
        objectsDecoding =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new LateDecoded(
                                        decodeLateObjects(
                                                session,
                                                directory,
                                                command,
                                                coordinate % 64,
                                                coordinate / 64,
                                                objectsCanceled::get),
                                        null);
                            } catch (Throwable error) {
                                return new LateDecoded(null, error);
                            }
                        },
                        objectsDecoder);
    }

    private void takeHorizon() {
        if (closing.get() || horizonDecoding != null) {
            return;
        }
        var command = mailbox.takeHorizon();
        if (command == null) {
            return;
        }
        horizonDecoding =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new HorizonDecoded(
                                        decodeHorizon(directory, command, horizonCanceled::get),
                                        null);
                            } catch (Throwable error) {
                                return new HorizonDecoded(null, error);
                            }
                        },
                        horizonDecoder);
    }

    /**
     * Horizon-decoder read of the session's one WDL file: a private regular file of exactly the
     * announced length and digest, parsed with the WDL reader's own bounds.
     */
    static com.vibeminer.formats.world.WorldLowRes.LowRes decodeHorizon(
            Path directory,
            WorldStreamControl.Horizon command,
            java.util.function.BooleanSupplier canceled)
            throws IOException {
        Path path = directory.resolve(WorldStreamControl.Horizon.BASENAME);
        if (!java.nio.file.Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Horizon is not a regular file (links are not followed)");
        }
        byte[] bytes;
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != command.length()) {
                throw new IOException("Horizon length differs from its command");
            }
            var buffer = java.nio.ByteBuffer.allocate(command.length());
            while (buffer.hasRemaining()) {
                if (canceled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException();
                }
                if (channel.read(buffer) < 0) {
                    throw new IOException("Truncated horizon file");
                }
            }
            bytes = buffer.array();
        }
        try {
            String digest =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
            if (!digest.equals(command.sha256())) {
                throw new IOException("Horizon digest differs from its command");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try {
            return com.vibeminer.formats.world.WorldLowRes.read(bytes, canceled);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed horizon WDL", malformed);
        }
    }

    /** Attaches a decoded horizon on the owner thread and answers its one HORIZON_DONE. */
    private void completeHorizon(WorldStreamNativeOwner owner) {
        if (horizonDecoding == null || !horizonDecoding.isDone()) {
            return;
        }
        var result = horizonDecoding.join();
        horizonDecoding = null;
        if (closing.get()) {
            return;
        }
        WorldStreamStatus.HorizonOutcome outcome;
        if (result.error() != null) {
            outcome = WorldStreamStatus.HorizonOutcome.FAILED;
            System.err.println("World horizon unavailable: " + result.error());
        } else if (result.lowRes().tiles().isEmpty()) {
            outcome = WorldStreamStatus.HorizonOutcome.NONE;
        } else {
            try {
                owner.attachHorizon(result.lowRes());
                outcome = WorldStreamStatus.HorizonOutcome.DRAWN;
            } catch (IOException | RuntimeException failed) {
                outcome = WorldStreamStatus.HorizonOutcome.FAILED;
                System.err.println("World horizon unavailable: " + failed);
            }
        }
        send("HORIZON_DONE 1 " + outcome.name());
    }

    /**
     * Objects-decoder read of one late objects file bound to the resident tile's identity. Any
     * failure is isolated to these objects: the tile's terrain is already staged and untouched.
     */
    static WorldStreamPackedObjects decodeLateObjects(
            WorldStreamEnvelope.Session session,
            Path directory,
            WorldStreamControl.LateObjects command,
            int tileX,
            int tileY,
            java.util.function.BooleanSupplier canceled)
            throws IOException {
        if (!session.exteriorObjects()) {
            throw new IOException("Late objects outside the session profile");
        }
        var data =
                WorldStreamObjectsFile.read(
                        directory.resolve(WorldStreamObjectsFile.basename(command.transaction())),
                        new WorldStreamObjectsFile.Identity(
                                session.nonce(),
                                session.scope(),
                                command.transaction(),
                                command.lease(),
                                tileX,
                                tileY),
                        new WorldStreamObjectsFile.Reference(command.length(), command.sha256()),
                        canceled);
        long packing = WorldStageTimings.start();
        var packed = WorldStreamPackedObjects.pack(data, canceled);
        WorldStageTimings.stop("child.objects.pack", packing);
        return packed;
    }

    private void completeObjectsDecode(WorldStreamNativeOwner owner) {
        if (objectsDecoding == null || !objectsDecoding.isDone()) {
            return;
        }
        var result = objectsDecoding.join();
        var command = objectsCommand;
        objectsDecoding = null;
        if (closing.get() || result.error() != null) {
            if (result.objects() != null) {
                result.objects().close();
            }
            if (!closing.get()
                    && !(result.error() instanceof java.util.concurrent.CancellationException)) {
                objectsRejections.reject(command.transaction(), result.error());
            }
            boolean live = state.objectsLive(command.lease());
            retireObjects(command, false);
            if (!closing.get()) {
                sendObjects(
                        command,
                        live
                                ? WorldStreamStatus.LateOutcome.FAILED
                                : WorldStreamStatus.LateOutcome.DROPPED);
            }
            return;
        }
        // The renderer takes the packed input; a generation it no longer holds is born dropped.
        lateUpload = owner.beginLate(command.lease(), result.objects());
        lateStarted = WorldStageTimings.start();
    }

    private void finishLate(WorldStreamNativeOwner owner) {
        if (lateUpload == null || !lateUpload.ready()) {
            return;
        }
        var command = objectsCommand;
        var outcome = owner.finishLate(lateUpload, state.objectsLive(command.lease()));
        WorldStageTimings.stop("child.objects.uploadWall", lateStarted);
        lateUpload = null;
        retireObjects(command, outcome == WorldStreamStatus.LateOutcome.ATTACHED);
        sendObjects(command, outcome);
    }

    private void retireObjects(WorldStreamControl.LateObjects command, boolean attached) {
        if (state.objectsDone(command.lease(), attached) != WorldStreamState.Result.OK) {
            throw new IllegalStateException("Late objects retired outside their generation");
        }
        objectsCommand = null;
    }

    private void sendObjects(
            WorldStreamControl.LateObjects command, WorldStreamStatus.LateOutcome outcome) {
        send(
                "OBJECTS_DONE "
                        + command.transaction()
                        + leaseText(command.lease())
                        + " "
                        + outcome.name());
    }

    private Decoded decodeCommit(WorldStreamControl.Commit command) {
        try {
            Path path = directory.resolve("commit-" + command.transaction() + ".wsc");
            if (!java.nio.file.Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Commit is not a regular private file");
            }
            byte[] bytes = new byte[WorldStreamEnvelope.BYTES];
            try (var channel =
                    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() != bytes.length) {
                    throw new IOException("Invalid commit length");
                }
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new IOException("Truncated commit");
                    }
                }
                if (channel.size() != bytes.length || channel.read(ByteBuffer.allocate(1)) != -1) {
                    throw new IOException("Changed commit length");
                }
            }
            var commit = WorldStreamEnvelope.decodeCommit(bytes, command.sha256(), session.nonce());
            if (!commit.nonce().equals(session.nonce())
                    || commit.transaction() != command.transaction()) {
                throw new IOException("Commit identity mismatch");
            }
            return new Decoded(null, null, commit, null);
        } catch (Throwable error) {
            return new Decoded(null, null, null, error);
        }
    }

    private void completeDecode(WorldStreamNativeOwner owner) {
        if (decoding == null || !decoding.isDone()) {
            return;
        }
        var result = decoding.join();
        var command = decodedCommand;
        decoding = null;
        decodedCommand = null;
        if (command instanceof WorldStreamControl.Offer offer) {
            WorldStageTimings.lap("child.handoff.decodeDoneToNoticed", decodeDoneAt);
            send("INPUT_CONSUMED " + offer.transaction() + " TILE");
            if (result.error() != null || closing.get() || decoderCanceled.get()) {
                if (result.tile() != null) {
                    result.tile().close();
                }
                // An Error (out of memory, a broken JVM) is not a packet problem: session-fatal.
                if (result.error() instanceof Error fatal) {
                    throw fatal;
                }
                state.decoded(offer.lease(), false);
                if (!decoderCanceled.get() && !closing.get()) {
                    rejections.reject(
                            offer.transaction(), "invalid packet: " + reason(result.error()));
                    send("REJECTED " + offer.transaction() + " INVALID_PACKET");
                }
            } else {
                try {
                    var admitted =
                            admit(
                                    state,
                                    offer.lease(),
                                    result.header().x(),
                                    result.header().y(),
                                    owner::begin,
                                    result.tile());
                    if (admitted.upload() == null) {
                        rejections.reject(offer.transaction(), admitted.reason());
                        send("REJECTED " + offer.transaction() + " INVALID_PACKET");
                    } else {
                        uploadHeader = result.header();
                        upload = admitted.upload();
                        uploadStarted = WorldStageTimings.start();
                        issued = released = 0;
                        staged = false;
                    }
                } finally {
                    result.tile().close();
                }
            }
        } else if (command instanceof WorldStreamControl.Commit commit) {
            send("INPUT_CONSUMED " + commit.transaction() + " COMMIT");
            if (closing.get()) {
                state.rejectReserved(commit.nonce(), commit.transaction());
                return;
            }
            if (result.error() != null) {
                state.rejectReserved(commit.nonce(), commit.transaction());
                send("REJECTED " + commit.transaction() + " INVALID_COMMIT");
                return;
            }
            var decoded = result.commit();
            List<WorldStreamState.Lease> table = new ArrayList<>(session.maxActive());
            for (var member : decoded.members()) {
                var lease =
                        state.active().stream()
                                .filter(
                                        candidate ->
                                                candidate.slot() == member.slot()
                                                        && candidate.generation()
                                                                == member.generation())
                                .findFirst()
                                .orElse(null);
                if (lease == null
                        && uploadHeader != null
                        && uploadHeader.lease().slot() == member.slot()
                        && uploadHeader.lease().generation() == member.generation()) {
                    lease = uploadHeader.lease();
                }
                if (lease == null) {
                    state.rejectReserved(commit.nonce(), commit.transaction());
                    send("REJECTED " + commit.transaction() + " INVALID_TABLE");
                    return;
                }
                table.add(lease);
            }
            if (decoded.policy() != (camera == null ? session.policy() : camera.policy())) {
                state.rejectReserved(commit.nonce(), commit.transaction());
                send("REJECTED " + commit.transaction() + " REVISION_MISMATCH");
                return;
            }
            var accepted =
                    state.commitReserved(
                            decoded.nonce(),
                            decoded.transaction(),
                            decoded.expected(),
                            decoded.next(),
                            table);
            if (accepted != WorldStreamState.Result.OK) {
                reject(commit.transaction(), accepted);
                return;
            }
            owner.commit(decoded.expected(), decoded.next(), table);
            send(
                    "COMMITTED "
                            + commit.transaction()
                            + " "
                            + decoded.expected()
                            + " "
                            + decoded.next());
        }
    }

    /**
     * A bounded, path-free, single-line reason for a stderr diagnostic: the exception class and
     * message with control characters blanked, absolute or relative paths replaced by {@code
     * <path>}, cut to 220 characters (as WorldStreamMain's failure line).
     */
    static String reason(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String text =
                error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
        text = text.replaceAll("[\\p{Cntrl}]", " ");
        text = text.replaceAll("(?:[A-Za-z]:)?[\\\\/][^\\s:;,'\"]*", "<path>");
        return text.substring(0, Math.min(220, text.length()));
    }

    /** The outcome of admitting one decoded tile: its upload, or the reason it was refused. */
    record Admission<U>(U upload, String reason) {}

    /**
     * Admits one decoded, valid tile (issue #365). Its header's coordinates are checked against the
     * slot before the renderer sees it; a mismatch is a per-transaction rejection: the state
     * retires the incoming lease as an invalid packet and the session keeps streaming. Otherwise
     * the renderer begins the upload, and any renderer refusal stays session-fatal.
     */
    static <T, U> Admission<U> admit(
            WorldStreamState state,
            WorldStreamState.Lease lease,
            int tileX,
            int tileY,
            java.util.function.Function<T, U> begin,
            T tile) {
        var accepted = state.decoded(lease, true, tileX, tileY);
        if (accepted == WorldStreamState.Result.INVALID_TABLE) {
            if (state.decoded(lease, false) != WorldStreamState.Result.OK) {
                throw new IllegalStateException("Invalid tile could not retire its lease");
            }
            return new Admission<>(null, "tile coordinates do not match its slot");
        }
        if (accepted != WorldStreamState.Result.OK) {
            throw new IllegalStateException("Decoded tile outside its lease");
        }
        // The decoder validated the header's session and lease and the state just accepted it,
        // so a renderer refusal here is a broken state/renderer invariant: fatal, as before.
        return new Admission<>(begin.apply(tile), null);
    }

    /**
     * Per-transaction rejections on stderr, rate limited: the first of the session and then every
     * 64th. The parent logs the redacted tail with its own rejection record.
     */
    static final class Rejections {
        private long count;

        /** The session's rejection count when this one was written, else 0. */
        long reject(long transaction, String reason) {
            count++;
            if (count != 1 && count % 64 != 0) {
                return 0;
            }
            System.err.println(
                    "World tile transaction "
                            + transaction
                            + " rejected: "
                            + reason
                            + " ("
                            + count
                            + (count == 1 ? " rejection" : " rejections")
                            + " this session)");
            System.err.flush();
            return count;
        }
    }

    private final Rejections rejections = new Rejections();

    private void reconcile() {
        if (upload == null) {
            return;
        }
        while (issued < upload.issuedReferences()) {
            if (state.submittedReference(upload.lease()) != WorldStreamState.Result.OK) {
                throw new IllegalStateException("Untracked native reference issuance");
            }
            issued++;
        }
        while (released < upload.releasedReferences()) {
            if (state.consumedReference(upload.lease()) != WorldStreamState.Result.OK) {
                throw new IllegalStateException("Untracked native reference release");
            }
            released++;
        }
    }

    private void finishUpload() {
        if (upload != null && upload.finished() && upload.successful() && !staged) {
            if (state.staged(upload.lease()) != WorldStreamState.Result.OK) {
                throw new IllegalStateException("Native upload staged outside its credit");
            }
            staged = true;
            WorldStageTimings.stop("child.tile.uploadWall", uploadStarted);
            int covered = 0;
            for (long word : uploadHeader.coverage()) {
                covered += Long.bitCount(word);
            }
            send(
                    "STAGED "
                            + uploadHeader.transaction()
                            + leaseText(upload.lease())
                            + " "
                            + (uploadHeader.outcome() == WorldStreamTileFile.Outcome.TEXTURED
                                    ? 1
                                    : 2)
                            + " "
                            + covered
                            + (upload.objectsState() < 0 ? "" : " " + upload.objectsState()));
        }
    }

    private void advertiseCredit() {
        var credit = state.credit();
        if (credit == null || credit.serial() == advertisedCredit) {
            return;
        }
        if (cancelAcknowledgement) {
            send("CANCELED " + canceledTransaction + leaseText(canceledLease));
        }
        cancelAcknowledgement = false;
        canceledLease = null;
        upload = null;
        uploadHeader = null;
        issued = released = 0;
        staged = false;
        advertisedCredit = credit.serial();
        send("CREDIT" + leaseText(credit));
    }

    private static String leaseText(WorldStreamState.Lease lease) {
        return " " + lease.serial() + " " + lease.slot() + " " + lease.generation();
    }

    private void reject(long transaction, WorldStreamState.Result result) {
        String code =
                switch (result) {
                    case STALE -> "STALE_TRANSACTION";
                    case BAD_LEASE -> "BAD_LEASE";
                    case BUSY -> "BUSY";
                    case INVALID_TABLE -> "INVALID_TABLE";
                    case REVISION_MISMATCH -> "REVISION_MISMATCH";
                    case CLOSED -> "SESSION_CLOSED";
                    default -> "PROTOCOL_ERROR";
                };
        send("REJECTED " + transaction + " " + code);
    }

    private volatile String failure;

    /** Class name and a capped, single-line message, like WorldStreamMain's failure line. */
    static String describe(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        message = message.replaceAll("[\\p{Cntrl}]", " ");
        message = message.substring(0, Math.min(160, message.length()));
        return error.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    /** Marks the session failed; the first reason goes to stderr when the session ends. */
    private void fail(String reason) {
        if (failure == null) {
            failure = reason;
        }
        failed = true;
    }

    private void send(String line) {
        if (!status.reliable(line)) {
            fail("status not accepted: " + line);
            requestClose();
        }
    }
}
