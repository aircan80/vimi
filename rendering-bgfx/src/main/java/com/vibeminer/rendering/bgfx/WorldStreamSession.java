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

    private static final long DRAW_INTERVAL = TimeUnit.SECONDS.toNanos(1) / 30;
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
    private volatile boolean failed;
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
        state = new WorldStreamState(session.nonce());
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
                send(
                        session.exteriorObjects()
                                ? "SESSION TERRAIN_EXTERIOR_V1"
                                : "SESSION TERRAIN_BASIC_V1");
                advertiseCredit();
                while (!closing.get()) {
                    if (mailbox.closing() || status.failure() != null) {
                        if (mailbox.failed() || status.failure() != null) {
                            failed = true;
                        }
                        requestClose();
                        break;
                    }
                    reconcile();
                    completeDecode(owner);
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
                    reconcile();
                    if (upload != null && !upload.finished()) {
                        owner.step(upload);
                        reconcile();
                    }
                    // GPU mutations must precede draw submission in the same bgfx frame.
                    long now = System.nanoTime();
                    if (camera != null && now - lastDraw >= DRAW_INTERVAL && owner.submit(camera)) {
                        lastDraw = now;
                        status.cameraApplied(camera.policy(), camera.width(), camera.height());
                    }
                    owner.advance();
                    reconcile();
                    finishUpload();
                    advertiseCredit();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                requestClose();
                decoderCanceled.set(true);
                // Keep the owner advancing while the actual input/packing worker relinquishes
                // ownership.
                while (decoding != null) {
                    completeDecode(owner);
                    owner.advance();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                reconcile();
                if (upload != null && !upload.finished()) {
                    owner.cancel(upload);
                }
                state.close();
            } catch (Throwable error) {
                failed = true;
                requestClose(); // Arm the independent deadline BEFORE native close can block.
                throw error;
            }
        } catch (Throwable error) {
            failed = true;
            requestClose();
            throw error;
        } finally {
            requestClose();
            decoderCanceled.set(true);
            decoder.shutdown();
            // This is a child-only drain. The independent watchdog remains armed throughout it.
            while (!decoder.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                // Do not close a stream under another thread's IO monitor.
            }
            if (decoding != null) {
                var result = decoding.join();
                if (result.tile() != null) {
                    result.tile().close();
                }
                decoding = null;
            }
            // Native close has now completed its callback drain; mirror those final releases
            // before claiming a successful session drain to the parent.
            reconcile();
            state.close();
            if (!state.drained()) {
                failed = true;
            }
            if (!failed && status.failure() == null) {
                send("CLOSED");
            }
            status.finish().handle((ignored, error) -> null).join();
            if (!failed && status.failure() == null) {
                allDrained.set(true);
            }
        }
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
                    failed = true;
                    requestClose();
                }
            }
        } catch (IOException | RuntimeException error) {
            send("REJECTED 0 PROTOCOL_ERROR");
            failed = true;
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
                failed = true;
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
            var tile = decodeTile(session, directory, offer, decoderCanceled::get);
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
                objects = WorldStreamPackedObjects.pack(data, canceled);
                state = 1;
            } catch (java.util.concurrent.CancellationException stopped) {
                payload.close();
                throw stopped;
            } catch (IOException | RuntimeException failed) {
                if (canceled.getAsBoolean()) {
                    payload.close();
                    throw new java.util.concurrent.CancellationException();
                }
                state = 2;
            }
        }
        return WorldStreamPackedTile.prepare(payload, objects, state, canceled);
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
            List<WorldStreamState.Lease> table = new ArrayList<>(9);
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

    private void send(String line) {
        if (!status.reliable(line)) {
            failed = true;
            requestClose();
        }
    }
}
