package com.vibeminer.rendering.bgfx;

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
    private WorldStreamControl.LateObjects objectsCommand;
    private BgfxWorldStreamRenderer.LateUpload lateUpload;
    private volatile boolean failed;

    // Compressed object formats the renderer samples (bit per code), set before any decode.
    private volatile int objectFormats;
    private volatile long closeStarted;
    private CompletableFuture<Decoded> decoding;
    private WorldStreamControl.Command decodedCommand, deferred;
    private WorldStreamControl.Camera camera;
    private BgfxWorldStreamRenderer.Upload upload;
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
        var runtime = new WorldStreamSession(session, directory, output, parentPid);
        runtime.runOwner(input, frames, backend, terminate);
        return !runtime.failed;
    }

    private void runOwner(
            InputStream input, WorldFrameFileV2 frames, int backend, Runnable terminate)
            throws Exception {
        Thread.ofVirtual().name("world-v2-control-reader").start(() -> readControls(input));
        Thread.ofVirtual().name("world-v2-independent-watchdog").start(() -> watch(terminate));
        try (var owner = new WorldStreamNativeOwner(session, frames, backend)) {
            try {
                // The profile, then the compressed object texture formats sampled as blocks: the
                // parent charges those images their compressed bytes (#374).
                objectFormats = owner.objectFormatMask();
                send(
                        (session.exteriorObjects()
                                        ? "SESSION TERRAIN_EXTERIOR_V1 "
                                        : "SESSION TERRAIN_BASIC_V1 ")
                                + objectFormats);
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
                    reconcile();
                    if (upload != null && !upload.finished()) {
                        owner.step(upload);
                        reconcile();
                    } else if (lateUpload != null && !lateUpload.ready()) {
                        // Terrain keeps priority: late object slices use only idle iterations.
                        owner.stepLate(lateUpload);
                    }
                    // GPU mutations must precede draw submission in the same bgfx frame.
                    long now = System.nanoTime();
                    if (camera != null && now - lastDraw >= DRAW_INTERVAL && owner.submit(camera)) {
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
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                requestClose();
                decoderCanceled.set(true);
                objectsCanceled.set(true);
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
            requestClose();
            decoderCanceled.set(true);
            objectsCanceled.set(true);
            decoder.shutdown();
            objectsDecoder.shutdown();
            // This is a child-only drain. The independent watchdog remains armed throughout it.
            while (!decoder.awaitTermination(100, TimeUnit.MILLISECONDS)
                    || !objectsDecoder.awaitTermination(100, TimeUnit.MILLISECONDS)) {
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
     * Keeps a steady display-rate phase while drawing continuously, so the ~1 ms loop park does not
     * stretch every interval; restarts the phase after an idle gap.
     */
    // Past this lateness the phase restarts: a stalled loop gets no wasted catch-up draw.
    static final long CATCH_UP_LIMIT = DRAW_INTERVAL * 5 / 4;

    static long nextDrawBase(long previous, long now) {
        return now - previous < CATCH_UP_LIMIT ? previous + DRAW_INTERVAL : now;
    }

    private void readControls(InputStream input) {
        try {
            while (!closing.get()) {
                var command = WorldStreamControl.read(input);
                if (command == null) {
                    requestClose();
                    return;
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
            decoding = CompletableFuture.supplyAsync(() -> decodeOffer(offer), decoder);
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
            var tile = decodeTile(session, directory, offer, decoderCanceled::get, objectFormats);
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
        return decodeTile(session, directory, offer, canceled, 0xE);
    }

    /** As above, packing objects for a renderer that samples {@code objectFormats} (#374). */
    static WorldStreamPackedTile decodeTile(
            WorldStreamEnvelope.Session session,
            Path directory,
            WorldStreamControl.Offer offer,
            java.util.function.BooleanSupplier canceled,
            int objectFormats)
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
                objects = WorldStreamPackedObjects.pack(data, canceled, objectFormats);
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
                state = 2;
            }
        }
        return WorldStreamPackedTile.prepare(payload, objects, state, canceled);
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
                                                objectsCanceled::get,
                                                objectFormats),
                                        null);
                            } catch (Throwable error) {
                                return new LateDecoded(null, error);
                            }
                        },
                        objectsDecoder);
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
        return decodeLateObjects(session, directory, command, tileX, tileY, canceled, 0xE);
    }

    /** As above, packing for a renderer that samples {@code objectFormats} (#374). */
    static WorldStreamPackedObjects decodeLateObjects(
            WorldStreamEnvelope.Session session,
            Path directory,
            WorldStreamControl.LateObjects command,
            int tileX,
            int tileY,
            java.util.function.BooleanSupplier canceled,
            int objectFormats)
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
        return WorldStreamPackedObjects.pack(data, canceled, objectFormats);
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
    }

    private void finishLate(WorldStreamNativeOwner owner) {
        if (lateUpload == null || !lateUpload.ready()) {
            return;
        }
        var command = objectsCommand;
        var outcome = owner.finishLate(lateUpload, state.objectsLive(command.lease()));
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
            send("INPUT_CONSUMED " + offer.transaction() + " TILE");
            if (result.error() != null || closing.get() || decoderCanceled.get()) {
                if (result.tile() != null) {
                    result.tile().close();
                }
                state.decoded(offer.lease(), false);
                if (!decoderCanceled.get() && !closing.get()) {
                    send("REJECTED " + offer.transaction() + " INVALID_PACKET");
                }
            } else {
                try {
                    uploadHeader = result.header();
                    upload = owner.begin(result.tile());
                    issued = released = 0;
                    staged = false;
                    var accepted =
                            state.decoded(offer.lease(), true, uploadHeader.x(), uploadHeader.y());
                    if (accepted != WorldStreamState.Result.OK) {
                        throw new IllegalStateException(
                                "Decoded state rejected its actual coordinates");
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
