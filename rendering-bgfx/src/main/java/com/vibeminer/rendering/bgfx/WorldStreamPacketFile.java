package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** A finalized unadvertised file, with one irreversible ownership move and no source references. */
final class WorldStreamPacketFile implements AutoCloseable {
    /** objects is the optional sibling {@code tile-<txn>.wso}, deleted together with path. */
    private record Data(
            WorldStreamTileFile.Expected expected,
            Path path,
            Object destinationIdentity,
            Path objects) {}

    private record Cleanup(Data data, CompletableFuture<Void> completion) {}

    private static final class Owner {
        private Data data;
        private CompletableFuture<Void> completion;

        Owner(Data data) {
            this.data = data;
        }

        synchronized Moved move() {
            if (data == null || completion != null) {
                throw new IllegalStateException("Packet file already moved or closing");
            }
            var moved = new Moved(data);
            data = null;
            completion = CompletableFuture.completedFuture(null);
            return moved;
        }

        synchronized Cleanup closePlan() {
            if (completion != null) {
                return new Cleanup(null, completion);
            }
            completion = new CompletableFuture<>();
            var result = new Cleanup(data, completion);
            data = null;
            return result;
        }
    }

    /** Transport alone owns this value after MOVE, independently of the source's inert shell. */
    static final class Moved implements AutoCloseable {
        private final Data metadata;
        private final Owner owner;

        private Moved(Data metadata) {
            this.metadata = metadata;
            owner = new Owner(metadata);
        }

        WorldStreamTileFile.Expected expected() {
            return metadata.expected();
        }

        Path path() {
            return metadata.path();
        }

        Object destinationIdentity() {
            return metadata.destinationIdentity();
        }

        /** The owned sibling objects file, or null. */
        Path objectsPath() {
            return metadata.objects();
        }

        CompletableFuture<Void> closeAsync(Executor existingFileLane) {
            Objects.requireNonNull(existingFileLane);
            return schedule(owner.closePlan(), existingFileLane);
        }

        /** Only off-FX source/controller/file lanes may synchronously close a file owner. */
        @Override
        public void close() {
            closeNow(owner.closePlan());
        }
    }

    private final Owner owner;

    private WorldStreamPacketFile(Data data) {
        owner = new Owner(data);
    }

    /**
     * Caller must already own this exact newly published file through its own successful CREATE_NEW
     * writer. A matching preexisting file or these descriptive checks never grant that ownership.
     * On failure the caller still owns cleanup. This method does filesystem work off FX.
     */
    static WorldStreamPacketFile finalized(
            WorldStreamTileFile.Expected expected,
            Path exactDestination,
            Object destinationIdentity)
            throws IOException {
        return finalized(expected, exactDestination, destinationIdentity, null);
    }

    /**
     * As above, also taking ownership of the caller's already published sibling objects file. On
     * failure the caller still owns both files.
     */
    static WorldStreamPacketFile finalized(
            WorldStreamTileFile.Expected expected,
            Path exactDestination,
            Object destinationIdentity,
            Path objects)
            throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(exactDestination);
        Objects.requireNonNull(destinationIdentity);
        if (destinationIdentity.getClass() != Object.class
                || exactDestination.getFileSystem() != FileSystems.getDefault()
                || !exactDestination.isAbsolute()
                || !exactDestination.equals(exactDestination.normalize())
                || exactDestination.getParent() == null
                || !exactDestination
                        .getFileName()
                        .toString()
                        .equals("tile-" + expected.transaction() + ".wst")) {
            throw new IllegalArgumentException("Exact packet destination required");
        }
        var attributes =
                Files.readAttributes(
                        exactDestination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expected.length()) {
            throw new IOException("Finalized packet metadata differs");
        }
        if (objects != null) {
            if (!objects.equals(
                    exactDestination
                            .getParent()
                            .resolve(WorldStreamObjectsFile.basename(expected.transaction())))) {
                throw new IllegalArgumentException("Exact objects destination required");
            }
            if (!Files.readAttributes(objects, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile()) {
                throw new IOException("Finalized objects metadata differs");
            }
        }
        return new WorldStreamPacketFile(
                new Data(expected, exactDestination, destinationIdentity, objects));
    }

    Moved move() {
        return owner.move();
    }

    /**
     * MOVE after the owner itself rewrote its still-unadvertised file in place; the replacement
     * expectation describes the same exact path, which this owner continues to hold until MOVE.
     */
    Moved moveReplacing(WorldStreamTileFile.Expected replacement) throws IOException {
        Objects.requireNonNull(replacement);
        Data current;
        synchronized (owner) {
            current = owner.data;
            if (current == null || owner.completion != null) {
                throw new IllegalStateException("Packet file already moved or closing");
            }
            if (replacement.transaction() != current.expected().transaction()
                    || !replacement.nonce().equals(current.expected().nonce())
                    || !replacement.lease().equals(current.expected().lease())
                    || !replacement.scope().equals(current.expected().scope())) {
                throw new IllegalArgumentException("Replacement packet identity differs");
            }
            var attributes =
                    Files.readAttributes(
                            current.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != replacement.length()) {
                throw new IOException("Replacement packet metadata differs");
            }
            owner.data =
                    new Data(
                            replacement,
                            current.path(),
                            current.destinationIdentity(),
                            current.objects());
        }
        return owner.move();
    }

    Path path() {
        synchronized (owner) {
            if (owner.data == null) {
                throw new IllegalStateException("Packet file already moved or closing");
            }
            return owner.data.path();
        }
    }

    WorldStreamTileFile.Expected expected() {
        synchronized (owner) {
            if (owner.data == null) {
                throw new IllegalStateException("Packet file already moved or closing");
            }
            return owner.data.expected();
        }
    }

    /** Queues deletion on the existing bounded file lane; creates no thread or watcher. */
    CompletableFuture<Void> closeAsync(Executor existingFileLane) {
        Objects.requireNonNull(existingFileLane);
        return schedule(owner.closePlan(), existingFileLane);
    }

    /** Only off-FX source/controller/file lanes may synchronously close a file owner. */
    @Override
    public void close() {
        closeNow(owner.closePlan());
    }

    private static CompletableFuture<Void> schedule(Cleanup plan, Executor existingFileLane) {
        Objects.requireNonNull(existingFileLane);
        if (plan.data() != null) {
            try {
                // Executor invocation, deletion and completion callbacks are outside all monitors.
                existingFileLane.execute(() -> delete(plan));
            } catch (RuntimeException refused) {
                plan.completion().completeExceptionally(failure(refused));
            } catch (Error fatal) {
                plan.completion().completeExceptionally(failure(fatal));
                throw fatal;
            }
        }
        return plan.completion().copy();
    }

    private static void closeNow(Cleanup plan) {
        if (plan.data() != null) {
            delete(plan);
        }
        try {
            plan.completion().join();
        } catch (java.util.concurrent.CompletionException failed) {
            throw new UncheckedIOException((IOException) failed.getCause());
        }
    }

    private static void delete(Cleanup plan) {
        try {
            try {
                if (plan.data().objects() != null) {
                    Files.deleteIfExists(plan.data().objects());
                }
            } finally {
                Files.deleteIfExists(plan.data().path());
            }
            plan.completion().complete(null);
        } catch (IOException | RuntimeException failed) {
            plan.completion().completeExceptionally(failure(failed));
        } catch (Error fatal) {
            plan.completion().completeExceptionally(failure(fatal));
            throw fatal;
        }
    }

    private static IOException failure(Throwable failed) {
        // No arbitrary Throwable.getMessage/getCause callback or retained failure graph.
        String message = "Packet file cleanup failed: " + failed.getClass().getName();
        var detached = new IOException(message.substring(0, Math.min(256, message.length())));
        detached.setStackTrace(new StackTraceElement[0]);
        return detached;
    }
}
