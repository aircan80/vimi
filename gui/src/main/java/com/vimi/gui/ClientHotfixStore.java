package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Local immutable client-cache imports. Source paths and key values never enter manifests/logs. */
final class ClientHotfixStore implements AutoCloseable {
    static final long MAX_SOURCE_BYTES = 64L << 20;
    static final int MAX_MANIFEST_BYTES = 16384, MAX_PENDING = 8, MAX_SNAPSHOTS = 8;
    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private final Object gate = new Object();
    private Path directory;
    private boolean canonicalized;
    private final Probe probe;

    interface Probe {
        default void beforeRead() {}

        default void beforeDispatch() {}

        default void afterRegistrationClose() {}

        default void afterOwnedChannelClose() {}

        default void beforeCopy(long copied, Cancellation token) {}

        default void afterSnapshotClose() {}

        /** After a follow bound the new build, before its watch is written. */
        default void beforeFollowWatch() {}

        /** After a follow watched the new build, before the old build's watch is removed. */
        default void beforeFollowUnwatch() {}

        /** After a failed follow watch, before the new build's earlier binding is restored. */
        default void beforeFollowRestore() {}

        /** Before Refresh sources removes a watch marked as superseded by a move. */
        default void beforeDropLeftover() {}

        /** Before a move writes the superseded marker for an old watch it could not remove. */
        default void beforeMarkLeftover() {}
    }

    /**
     * A confirmed follow. {@code bindingChanged} is false when the new build already used these
     * bytes; {@code oldWatchCleared} is false when the old build is still checked on Refresh
     * sources (a later apply of the same pair retries only that removal).
     */
    record FollowResult(
            Summary summary, boolean bindingChanged, boolean oldWatchCleared, boolean replacedOtherWatch) {
        FollowResult(Summary summary, boolean bindingChanged, boolean oldWatchCleared) {
            this(summary, bindingChanged, oldWatchCleared, false);
        }
    }

    /**
     * The new build was bound but its watch could not be written, and restoring its earlier
     * binding failed too. The new build uses {@link #bound} and is not checked on Refresh
     * sources; the old build keeps its import and its check.
     */
    static final class FollowIncomplete extends IOException {
        private static final long serialVersionUID = 1L;
        final transient Summary bound;

        FollowIncomplete(Summary bound, Throwable cause) {
            super("Client cache bound without its watch", cause);
            this.bound = bound;
        }
    }

    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofVirtual().name("client-hotfix-store").factory());
    private final Set<Cancellation> operations = new HashSet<>();
    private final Set<Snapshot> snapshots = new HashSet<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed, finishing;
    private int cleanupJobs;
    private Throwable cleanupFailure;
    private final List<FileChannel> quarantinedChannels = new java.util.ArrayList<>();

    record Scope(BuildRef build, String locale, String version) {
        Scope {
            Objects.requireNonNull(build);
            Objects.requireNonNull(version);
            locale = WowLocale.parse(locale).code();
            if (!version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}\\.[1-9][0-9]{0,8}"))
                throw new IllegalArgumentException("Exact client build version required");
            Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        }

        int buildNumber() {
            return Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        }

        String identity() {
            return String.join(
                    "\n",
                    build.product(),
                    build.buildConfig(),
                    build.cdnConfig(),
                    build.region() == null ? "<none>" : build.region(),
                    locale,
                    version);
        }

        String key() {
            return sha(identity().getBytes(StandardCharsets.UTF_8));
        }
    }

    record Summary(
            int build,
            String locale,
            String version,
            String sha256,
            long bytes,
            int records,
            Instant importedAt) {}

    ClientHotfixStore(Path directory) {
        this(directory, new Probe() {});
    }

    ClientHotfixStore(Path directory, Probe probe) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.probe = Objects.requireNonNull(probe);
    }

    /** Outcome of checking one opted-in watched original during a source refresh. */
    enum RefreshOutcome {
        /** Same size/mtime, or reread bytes equal the bound import. */
        UNCHANGED,
        /** A different verified cache for the same exact build was imported and bound. */
        UPDATED,
        /** The original now declares another client build; the existing binding is kept. */
        CLIENT_BUILD_CHANGED,
        /** The remembered original no longer exists; the existing binding is kept. */
        MISSING,
        /** The original could not be read or verified; the existing binding is kept. */
        FAILED,
        /**
         * A watch a confirmed move could not remove, and marked as superseded by the exact watch
         * it moved to; that target still exists, so this watch was removed now. Its import is
         * kept. A failed removal is reported as {@code FAILED} instead.
         */
        SUPERSEDED
    }

    /** {@code scope} is null only for an unreadable watch record. Never carries the path. */
    record RefreshResult(
            Scope scope, RefreshOutcome outcome, Summary summary, int clientBuild, Throwable failure) {}

    static final int MAX_WATCHES = 64;

    CompletableFuture<Summary> importCache(Scope scope, Path original, Cancellation token) {
        return importCache(scope, original, false, token);
    }

    /**
     * Imports and binds an explicitly selected original. With {@code watch}, the original's
     * location is remembered privately so Refresh sources can detect and import a newer cache
     * for this exact scope; without it any earlier watch for the scope is removed.
     */
    CompletableFuture<Summary> importCache(
            Scope scope, Path original, boolean watch, Cancellation token) {
        Objects.requireNonNull(original);
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    // Drop any earlier watch before binding, so a failure can
                                    // never leave an old original able to override this choice.
                                    deleteWatch(scope, cancel);
                                    var imported = importActual(scope, original, false, cancel);
                                    if (watch) {
                                        try {
                                            writeWatch(
                                                    scope,
                                                    imported.input(),
                                                    imported.source(),
                                                    Cancellation.NONE);
                                        } catch (IOException | RuntimeException ignored) {
                                            // The binding is committed; the reopened workspace
                                            // reports the scope as unwatched and can retry.
                                        }
                                    }
                                    return imported.summary();
                                }));
    }

    /** Stops checking the remembered original. The binding and imported files are unchanged. */
    CompletableFuture<Void> unwatch(Scope scope, Cancellation token) {
        Objects.requireNonNull(scope);
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    deleteWatch(scope, cancel);
                                    return null;
                                }));
    }

    /**
     * Checks every opted-in watched original once. Changed same-build caches are fully verified,
     * imported and atomically bound; open snapshots stay immutable, so open workspaces keep the
     * data they loaded until reopened. Nothing is discovered beyond the remembered files.
     */
    CompletableFuture<List<RefreshResult>> refreshWatched(Cancellation token) {
        return submit(token, cancel -> unchecked(() -> refreshActual(cancel)));
    }

    /**
     * After the user confirms, moves a watched original whose client now runs another build into
     * that build's scope: the original is fully verified against {@code to}, imported, bound and
     * watched there, and the watch leaves {@code from}. The binding of {@code from} never changes.
     *
     * <p>A failed future means neither build changed, except {@link FollowIncomplete}, which names
     * the state left behind. Applying a pair that was already applied changes nothing; if only the
     * old watch was left behind, it is removed then.
     */
    CompletableFuture<FollowResult> follow(Scope from, Scope to, Cancellation token) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to)
                || !from.build().product().equals(to.build().product())
                || !from.locale().equals(to.locale()))
            throw new IllegalArgumentException("A client cache follows only its product and locale");
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    return followActual(from, to, cancel);
                                }));
    }

    private FollowResult followActual(Scope from, Scope to, Cancellation token)
            throws IOException {
        Path file = watch(from);
        boolean toWatched = Files.exists(watch(to), LinkOption.NOFOLLOW_LINKS);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var current = readManifest(to);
            // Already applied: a second apply of the same pair is a no-op.
            if (current != null && toWatched) return new FollowResult(current, false, true);
            throw new IOException("This build has no watched client cache");
        }
        var original = readWatch(file).original();
        byte[] priorBinding = readPrivate(binding(to));
        // Verifies the declared build against the target scope before anything is bound; a
        // mismatch changes nothing. Identical bytes keep the existing binding.
        var imported = importActual(to, original, true, token);
        if (!imported.changed() && toWatched) {
            // The new build already uses these bytes. Make sure it watches the followed file,
            // not another file with identical bytes; then only the old watch may be left.
            Path watchedByTo = readWatch(watch(to)).original();
            boolean replaced = !watchedByTo.equals(imported.input());
            if (replaced) {
                probe.beforeFollowWatch();
                writeWatch(to, imported.input(), imported.source(), token);
            }
            return new FollowResult(imported.summary(), false, clearOldWatch(from, to, token), replaced);
        }
        try {
            probe.beforeFollowWatch();
            writeWatch(to, imported.input(), imported.source(), token);
        } catch (IOException | RuntimeException failure) {
            if (!imported.changed()) throw failure;
            try {
                // Restore the new build's earlier binding so the failure changes neither build.
                probe.beforeFollowRestore();
                Path binding = binding(to);
                if (priorBinding == null) Files.deleteIfExists(binding);
                else writeAtomic(binding, priorBinding, Cancellation.NONE);
            } catch (IOException | RuntimeException restore) {
                failure.addSuppressed(restore);
                throw new FollowIncomplete(imported.summary(), failure);
            }
            throw failure;
        }
        return new FollowResult(imported.summary(), imported.changed(), clearOldWatch(from, to, token));
    }

    /** False when the old watch stays; the binding and new watch are already durable. */
    private boolean clearOldWatch(Scope from, Scope to, Cancellation token) {
        try {
            probe.beforeFollowUnwatch();
            deleteWatch(from, token);
            return true;
        } catch (IOException | RuntimeException failure) {
            // Includes cancellation: the new build is already bound and watched, so the move is
            // reported as done with the old check left behind. Mark exactly that old watch as
            // superseded by exactly the target watch, so Refresh sources removes it and nothing
            // else; if even the marker cannot be written, the old watch stays an ordinary watch.
            try {
                probe.beforeMarkLeftover();
                writeLeftoverMarker(from, to);
            } catch (IOException | RuntimeException unmarked) {
                // Reported as a leftover check either way.
            }
            return false;
        }
    }

    /** True when the build already has an imported cache or a watch that applying replaces. */
    CompletableFuture<Boolean> occupied(Scope scope, Cancellation token) {
        Objects.requireNonNull(scope);
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    return Files.exists(binding(scope), LinkOption.NOFOLLOW_LINKS)
                                            || Files.exists(watch(scope), LinkOption.NOFOLLOW_LINKS);
                                }));
    }

    CompletableFuture<Snapshot> open(Scope scope, Cancellation token) {
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    var summary = readManifest(scope);
                                    if (summary == null) return null;
                                    var snapshot =
                                            openSnapshot(
                                                    object(summary.sha256()),
                                                    scope,
                                                    summary,
                                                    cancel);
                                    snapshot.watched =
                                            Files.exists(watch(scope), LinkOption.NOFOLLOW_LINKS);
                                    return snapshot;
                                }));
    }

    /** Clears association and any watch. Immutable imported bytes are deliberately retained. */
    CompletableFuture<Void> disable(Scope scope, Cancellation token) {
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    Path binding = binding(scope);
                                    deleteWatch(scope, cancel);
                                    if (Files.exists(binding, LinkOption.NOFOLLOW_LINKS)) {
                                        regular(binding);
                                        cancel.check();
                                        Files.delete(binding);
                                    }
                                    return null;
                                }));
    }

    private <T> CompletableFuture<T> submit(
            Cancellation external, Function<Cancellation, T> action) {
        Objects.requireNonNull(external);
        var token = new Cancellation();
        var result = new CompletableFuture<T>();
        synchronized (gate) {
            if (closed)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Hotfix store closed"));
            if (cleanupFailure != null) return CompletableFuture.failedFuture(cleanupFailure);
            if (operations.size() == MAX_PENDING)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Hotfix store busy"));
            operations.add(token);
        }
        Cancellation.Registration registration;
        try {
            registration = external.onCancel(token::cancel);
        } catch (RuntimeException | Error failure) {
            operationFinished(token);
            return CompletableFuture.failedFuture(failure);
        }
        try {
            probe.beforeDispatch();
            worker.execute(
                    () -> {
                        T value = null;
                        Throwable failure = null;
                        try {
                            token.check();
                            requireHealthy();
                            value = action.apply(token);
                        } catch (RuntimeException | Error error) {
                            failure = error;
                        }
                        try {
                            registration.close();
                            probe.afterRegistrationClose();
                        } catch (RuntimeException | Error error) {
                            recordCleanup(error);
                            failure = append(failure, error);
                        }
                        if (failure != null && value instanceof Snapshot snapshot) {
                            try {
                                snapshot.closeActual();
                            } catch (RuntimeException | Error error) {
                                failure = append(failure, error);
                            }
                            value = null;
                        }
                        operationFinished(token);
                        completeOffWorker(result, value, failure);
                    });
        } catch (RuntimeException | Error original) {
            Throwable failure = original;
            try {
                registration.close();
                probe.afterRegistrationClose();
            } catch (RuntimeException | Error error) {
                recordCleanup(error);
                failure = append(failure, error);
            } finally {
                operationFinished(token);
            }
            completeOffWorker(result, null, failure);
        }
        return result.copy();
    }

    private static <T> void completeOffWorker(
            CompletableFuture<T> result, T value, Throwable failure) {
        ForkJoinPool.commonPool()
                .execute(
                        () -> {
                            if (failure == null) result.complete(value);
                            else result.completeExceptionally(failure);
                        });
    }

    private void recordCleanup(Throwable failure) {
        synchronized (gate) {
            cleanupFailure = append(cleanupFailure, failure);
        }
    }

    private void requireHealthy() {
        Throwable failure;
        synchronized (gate) {
            failure = cleanupFailure;
        }
        rethrow(failure);
    }

    private void operationFinished(Cancellation token) {
        synchronized (gate) {
            operations.remove(token);
        }
        finishIfDrained();
    }

    /** Verified import plus the exact original attributes observed before and after copying. */
    private record Imported(
            Summary summary, Path input, BasicFileAttributes source, boolean changed) {}

    private Imported importActual(
            Scope scope, Path original, boolean keepIdentical, Cancellation token)
            throws IOException {
        Objects.requireNonNull(scope);
        ensureStorage();
        Path input = original.toAbsolutePath().normalize();
        var before = regular(input);
        if (before.size() < 44 || before.size() > MAX_SOURCE_BYTES)
            throw new IOException("Client cache size is outside bounds");
        Path temporary =
                directory.resolve("objects").resolve("import-" + UUID.randomUUID() + ".tmp");
        Throwable failure = null;
        try {
            String digest;
            try (var source =
                            owned(
                                    FileChannel.open(
                                            input,
                                            StandardOpenOption.READ,
                                            LinkOption.NOFOLLOW_LINKS));
                    var target = create(temporary)) {
                if (source.size() != before.size())
                    throw new IOException("Client cache changed before import");
                var hash = digest();
                var buffer = ByteBuffer.allocate(65536);
                long copied = 0;
                while (copied < before.size()) {
                    token.check();
                    probe.beforeCopy(copied, token);
                    token.check();
                    buffer.clear().limit((int) Math.min(buffer.capacity(), before.size() - copied));
                    int n = source.read(buffer);
                    if (n < 0) throw new IOException("Client cache was truncated during import");
                    if (n == 0) continue;
                    copied += n;
                    buffer.flip();
                    hash.update(buffer.duplicate());
                    while (buffer.hasRemaining()) {
                        token.check();
                        target.write(buffer);
                    }
                }
                if (source.size() != before.size())
                    throw new IOException("Client cache changed during import");
                target.force(true);
                digest = HexFormat.of().formatHex(hash.digest());
            }
            var staged = openSnapshot(temporary, scope, null, token);
            Summary summary;
            Throwable stagedFailure = null;
            try {
                if (!digest.equals(staged.index().sha256()))
                    throw new IOException("Imported cache bytes changed");
                summary =
                        new Summary(
                                scope.buildNumber(),
                                scope.locale(),
                                scope.version(),
                                digest,
                                staged.index().length(),
                                staged.index().entries().size(),
                                Instant.now());
            } catch (IOException | RuntimeException | Error error) {
                stagedFailure = error;
                throw error;
            } finally {
                try {
                    staged.closeActual();
                } catch (RuntimeException | Error cleanup) {
                    if (stagedFailure != null) append(stagedFailure, cleanup);
                    else throw cleanup;
                }
            }
            var after = regular(input);
            if (after.size() != before.size()
                    || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(after.fileKey(), before.fileKey()))
                throw new IOException("Client cache changed during import");
            token.check();
            if (keepIdentical) {
                var current = readManifest(scope);
                if (current != null && current.sha256().equals(digest))
                    return new Imported(current, input, after, false);
            }
            Path destination = object(digest);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                var existing = openSnapshot(destination, scope, summary, token);
                existing.closeActual();
            } else Files.move(temporary, destination);
            writeManifest(scope, summary, token);
            return new Imported(summary, input, after, true);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException | Error error) {
                recordCleanup(error);
                if (failure != null) append(failure, error);
                else throw error;
            }
        }
    }

    private Snapshot openSnapshot(Path path, Scope scope, Summary expected, Cancellation token)
            throws IOException {
        var snapshot = new Snapshot(path);
        synchronized (gate) {
            if (closed) throw new CancellationException();
            if (snapshots.size() == MAX_SNAPSHOTS)
                throw new IOException("Too many open client-cache snapshots");
            snapshots.add(snapshot);
        }
        try {
            regular(path);
            snapshot.channel =
                    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            snapshot.length = snapshot.channel.size();
            snapshot.index =
                    HotfixCacheReader.inspect(
                                    snapshot.source,
                                    scope.buildNumber(),
                                    new HotfixCacheReader.Limits(),
                                    token)
                            .join();
            if (expected != null
                    && (!expected.sha256().equals(snapshot.index.sha256())
                            || expected.bytes() != snapshot.length
                            || expected.records() != snapshot.index.entries().size()))
                throw new IOException("Stored client-cache snapshot differs from its binding");
            token.check();
            synchronized (snapshot.monitor) {
                if (snapshot.closing) throw new CancellationException();
            }
            return snapshot;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                snapshot.closeActual();
            } catch (RuntimeException | Error cleanup) {
                append(failure, cleanup);
            }
            throw failure;
        }
    }

    final class Snapshot implements AutoCloseable {
        private final Object monitor = new Object();
        private volatile boolean watched;
        private final Path path;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private FileChannel channel;
        private long length;
        private HotfixCacheReader.Index index;
        private int pins;
        private boolean closing, closeQueued, terminal;
        private Throwable failure;
        private final Db2ByteSource source =
                new Db2ByteSource() {
                    @Override
                    public long length() {
                        synchronized (monitor) {
                            return length;
                        }
                    }

                    @Override
                    public List<Db2Types.Hole> holes() {
                        return List.of();
                    }

                    @Override
                    public CompletableFuture<Void> readRange(
                            long at, ByteBuffer out, Cancellation token) {
                        FileChannel actual;
                        try {
                            token.check();
                            requireHealthy();
                        } catch (RuntimeException | Error failure) {
                            return CompletableFuture.failedFuture(failure);
                        }
                        synchronized (monitor) {
                            if (closing || terminal || channel == null)
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("Client-cache snapshot closed"));
                            if (pins == MAX_PENDING)
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("Client-cache reader busy"));
                            if (at < 0
                                    || out.remaining() > 65536
                                    || at > length
                                    || out.remaining() > length - at)
                                return CompletableFuture.failedFuture(
                                        new IllegalArgumentException("Client-cache read bounds"));
                            pins++;
                            actual = channel;
                        }
                        Throwable error = null;
                        try {
                            probe.beforeRead();
                            while (out.hasRemaining()) {
                                token.check();
                                int n = actual.read(out, at);
                                if (n < 0)
                                    throw new IOException("Stored client-cache input truncated");
                                at += n;
                            }
                            token.check();
                        } catch (IOException | RuntimeException | Error caught) {
                            error = caught;
                        } finally {
                            boolean release;
                            synchronized (monitor) {
                                pins--;
                                release = closing && pins == 0;
                            }
                            if (release) queueClose();
                        }
                        return error == null
                                ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(error);
                    }
                };

        private Snapshot(Path path) {
            this.path = path;
        }

        Db2ByteSource source() {
            return source;
        }

        /** Whether Refresh sources was asked to check this scope's original when opened. */
        boolean watched() {
            return watched;
        }

        HotfixCacheReader.Index index() {
            synchronized (monitor) {
                if (index == null || closing || terminal)
                    throw new IllegalStateException("Client-cache snapshot not open");
                return index;
            }
        }

        CompletableFuture<Void> closeAsync() {
            boolean release;
            synchronized (monitor) {
                closing = true;
                release = pins == 0;
            }
            if (release) queueClose();
            return finished.copy();
        }

        private void queueClose() {
            synchronized (monitor) {
                if (closeQueued || terminal) return;
                closeQueued = true;
            }
            synchronized (gate) {
                cleanupJobs++;
            }
            worker.execute(
                    () -> {
                        try {
                            closeActual();
                        } catch (RuntimeException | Error ignored) {
                            /* Failure is retained by the owner. */
                        } finally {
                            synchronized (gate) {
                                cleanupJobs--;
                            }
                            finishIfDrained();
                        }
                    });
        }

        private void closeActual() {
            FileChannel actual;
            synchronized (monitor) {
                closing = true;
                if (terminal) {
                    rethrow(failure);
                    return;
                }
                if (pins != 0) throw new IllegalStateException("Client-cache reads still active");
                actual = channel;
                channel = null;
            }
            Throwable error = null;
            try {
                if (actual != null) actual.close();
            } catch (IOException | RuntimeException | Error caught) {
                error = caught;
                synchronized (gate) {
                    if (actual != null) quarantinedChannels.add(actual);
                }
            }
            try {
                probe.afterSnapshotClose();
            } catch (RuntimeException | Error caught) {
                error = append(error, caught);
            }
            synchronized (monitor) {
                terminal = true;
                failure = error;
                index = null;
            }
            synchronized (gate) {
                snapshots.remove(this);
                cleanupFailure = append(cleanupFailure, error);
            }
            completeOffWorker(finished, null, error);
            finishIfDrained();
            rethrow(error);
        }

        @Override
        public void close() {
            closeAsync().join();
        }
    }

    CompletableFuture<Void> closeAsync() {
        List<Cancellation> active;
        List<Snapshot> owned;
        synchronized (gate) {
            if (closed) return drained.copy();
            closed = true;
            active = List.copyOf(operations);
            owned = List.copyOf(snapshots);
        }
        for (var token : active) {
            try {
                token.cancel();
            } catch (RuntimeException | Error error) {
                synchronized (gate) {
                    cleanupFailure = append(cleanupFailure, error);
                }
            }
        }
        for (var snapshot : owned) snapshot.closeAsync();
        finishIfDrained();
        return drained.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    private void finishIfDrained() {
        synchronized (gate) {
            if (!closed
                    || finishing
                    || !operations.isEmpty()
                    || !snapshots.isEmpty()
                    || cleanupJobs != 0) return;
            finishing = true;
        }
        worker.execute(
                () -> {
                    Throwable failure;
                    synchronized (gate) {
                        failure = cleanupFailure;
                    }
                    try {
                        if (lock != null) lock.release();
                    } catch (IOException | RuntimeException | Error error) {
                        failure = append(failure, error);
                    }
                    try {
                        if (lockChannel != null) lockChannel.close();
                    } catch (IOException | RuntimeException | Error error) {
                        failure = append(failure, error);
                    }
                    worker.shutdown();
                    if (failure == null) drained.complete(null);
                    else drained.completeExceptionally(failure);
                });
    }

    private void ensureStorage() throws IOException {
        if (lock != null) {
            ownedDirectory(directory);
            ownedDirectory(directory.resolve("objects"));
            ownedDirectory(directory.resolve("bindings"));
            ownedDirectory(directory.resolve("watches"));
            return;
        }
        if (!canonicalized) {
            privateDirectory(directory);
            directory = directory.toRealPath();
            canonicalized = true;
        }
        privateDirectory(directory);
        privateDirectory(directory.resolve("objects"));
        privateDirectory(directory.resolve("bindings"));
        privateDirectory(directory.resolve("watches"));
        Path path = directory.resolve("store.lock");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) regular(path);
        lockChannel =
                FileChannel.open(
                        path,
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        attributes(path, false));
        try {
            lock = lockChannel.tryLock();
        } catch (RuntimeException | IOException | Error failure) {
            closeFailedLock(failure);
            throw failure;
        }
        if (lock == null) {
            var failure = new IOException("Client-cache store is already open");
            closeFailedLock(failure);
            throw failure;
        }
    }

    private Path object(String hash) {
        return directory.resolve("objects").resolve(hash + ".bin");
    }

    private Path binding(Scope scope) {
        return directory.resolve("bindings").resolve(scope.key() + ".binding");
    }

    private Summary readManifest(Scope scope) throws IOException {
        byte[] bytes = readPrivate(binding(scope));
        if (bytes == null) return null;
        String text = new String(bytes, StandardCharsets.UTF_8);
        var lines = text.split("\n", -1);
        if (lines.length != 8
                || !lines[0].equals("vimi-client-hotfix-v1")
                || !lines[1].equals(scope.key())
                || !lines[2].matches("[0-9a-f]{64}")
                || !lines[3].matches("[1-9][0-9]{0,8}")
                || !lines[4].matches("0|[1-9][0-9]{0,5}")
                || !lines[5].equals(scope.version())
                || !lines[6].endsWith("Z")
                || !lines[7].isEmpty()) throw new IOException("Client-cache binding is invalid");
        try {
            long size = Long.parseLong(lines[3]);
            int records = Integer.parseInt(lines[4]);
            if (size < 44 || size > MAX_SOURCE_BYTES || records > 65536)
                throw new IllegalArgumentException();
            return new Summary(
                    scope.buildNumber(),
                    scope.locale(),
                    scope.version(),
                    lines[2],
                    size,
                    records,
                    Instant.parse(lines[6]));
        } catch (RuntimeException invalid) {
            throw new IOException("Client-cache binding is invalid", invalid);
        }
    }

    private void writeManifest(Scope scope, Summary summary, Cancellation token)
            throws IOException {
        String text =
                String.join(
                        "\n",
                        "vimi-client-hotfix-v1",
                        scope.key(),
                        summary.sha256(),
                        Long.toString(summary.bytes()),
                        Integer.toString(summary.records()),
                        scope.version(),
                        summary.importedAt().toString(),
                        "");
        writeAtomic(binding(scope), text.getBytes(StandardCharsets.UTF_8), token);
    }

    /** Bounded private record read; null when absent. Symbolic links are refused. */
    private byte[] readPrivate(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        var attributes = regular(path);
        if (attributes.size() > MAX_MANIFEST_BYTES)
            throw new IOException("Client-cache record exceeds bounds");
        try (var input =
                owned(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (input.size() != attributes.size())
                throw new IOException("Client-cache record changed");
            var data = ByteBuffer.allocate((int) attributes.size());
            while (data.hasRemaining())
                if (input.read(data) < 0) throw new IOException("Client-cache record truncated");
            return data.array();
        }
    }

    /** Owner-only temporary write, then atomic replacement of one private record. */
    private void writeAtomic(Path path, byte[] bytes, Cancellation token) throws IOException {
        if (bytes.length > MAX_MANIFEST_BYTES)
            throw new IOException("Client-cache record exceeds bounds");
        Path temporary = path.resolveSibling("record-" + UUID.randomUUID() + ".tmp");
        Throwable failure = null;
        try {
            try (var output = create(temporary)) {
                var data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) {
                    token.check();
                    output.write(data);
                }
                output.force(true);
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) regular(path);
            token.check();
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException | Error cleanup) {
                recordCleanup(cleanup);
                if (failure != null) append(failure, cleanup);
                else throw cleanup;
            }
        }
    }

    private Path watch(Scope scope) {
        return directory.resolve("watches").resolve(scope.key() + ".watch");
    }

    /** Private opt-in record of one original location plus its last verified size/mtime. */
    private record Watch(
            Scope scope, Path original, long size, long modifiedNanos, String fileKey) {}

    /** Filesystem identity (for example device/inode), so atomic replacement is detected. */
    private static String fileIdentity(BasicFileAttributes attributes) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        String.valueOf(attributes.fileKey()).getBytes(StandardCharsets.UTF_8));
    }

    private void writeWatch(
            Scope scope, Path input, BasicFileAttributes source, Cancellation token)
            throws IOException {
        var build = scope.build();
        String text =
                String.join(
                        "\n",
                        "vimi-client-hotfix-watch-v1",
                        scope.key(),
                        build.product(),
                        build.buildConfig(),
                        build.cdnConfig(),
                        build.region() == null ? "<none>" : build.region(),
                        scope.locale(),
                        scope.version(),
                        Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(input.toString().getBytes(StandardCharsets.UTF_8)),
                        Long.toString(source.size()),
                        Long.toString(source.lastModifiedTime().to(TimeUnit.NANOSECONDS)),
                        fileIdentity(source),
                        "");
        writeAtomic(watch(scope), text.getBytes(StandardCharsets.UTF_8), token);
    }

    private void deleteWatch(Scope scope, Cancellation token) throws IOException {
        ensureStorage();
        Path path = watch(scope);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            regular(path);
            token.check();
            Files.delete(path);
        }
        deleteLeftoverMarker(scope);
    }

    /** "Superseded by" marker of one exact watch; names the exact watch it was moved to. */
    private Path leftoverMarker(Scope scope) {
        return directory.resolve("watches").resolve(scope.key() + ".superseded");
    }

    private void writeLeftoverMarker(Scope from, Scope to) throws IOException {
        String text = String.join("\n", "vimi-client-hotfix-superseded-v1", from.key(), to.key(), "");
        writeAtomic(leftoverMarker(from), text.getBytes(StandardCharsets.UTF_8), Cancellation.NONE);
    }

    private void deleteLeftoverMarker(Scope scope) throws IOException {
        Path marker = leftoverMarker(scope);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        regular(marker);
        Files.delete(marker);
    }

    /**
     * Removes one watch that a move marked as superseded, while the exact watch it moved to still
     * exists. Returns null when the watch has no valid marker (it is then checked as usual); a
     * stale or unreadable marker is dropped and the watch is kept.
     */
    private RefreshResult dropMarkedLeftover(Path watchFile, Cancellation token) throws IOException {
        String name = watchFile.getFileName().toString();
        String key = name.substring(0, name.length() - ".watch".length());
        Path marker = watchFile.resolveSibling(key + ".superseded");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return null;
        String target;
        try {
            byte[] bytes = readPrivate(marker);
            var lines = bytes == null ? new String[0] : new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            if (lines.length != 4
                    || !lines[0].equals("vimi-client-hotfix-superseded-v1")
                    || !lines[1].equals(key)
                    || !lines[2].matches("[0-9a-f]{64}")
                    || !lines[3].isEmpty()) throw new IOException("Leftover marker is invalid");
            target = lines[2];
        } catch (IOException | RuntimeException invalid) {
            try {
                regular(marker);
                Files.delete(marker);
            } catch (IOException | RuntimeException ignored) {
                // Retried next refresh; the watch itself is kept and checked as usual.
            }
            return null;
        }
        Watch watch;
        try {
            watch = readWatch(watchFile);
        } catch (IOException | RuntimeException unreadable) {
            return null; // refreshOne reports and removes unreadable records
        }
        if (!Files.exists(watchFile.resolveSibling(target + ".watch"), LinkOption.NOFOLLOW_LINKS)) {
            // The watch it moved to is gone: this watch is no longer a leftover; keep it.
            try {
                deleteLeftoverMarker(watch.scope());
            } catch (IOException | RuntimeException ignored) {
                // Retried next refresh; the watch is checked as usual either way.
            }
            return null;
        }
        Summary summary;
        try {
            summary = readManifest(watch.scope());
        } catch (IOException | RuntimeException unreadable) {
            summary = null; // affects only this report, never the refresh
        }
        try {
            probe.beforeDropLeftover();
            deleteWatch(watch.scope(), token);
        } catch (IOException | RuntimeException failure) {
            if (token.isCanceled() || canceled(failure)) throw failure;
            // Not removed: a failure, not a removal. Not checked either (no prompt); retried.
            return new RefreshResult(watch.scope(), RefreshOutcome.FAILED, summary, 0, failure);
        }
        return new RefreshResult(watch.scope(), RefreshOutcome.SUPERSEDED, summary, 0, null);
    }

    private Watch readWatch(Path file) throws IOException {
        byte[] bytes = readPrivate(file);
        if (bytes == null) throw new IOException("Client-cache watch disappeared");
        var lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length != 13
                || !lines[0].equals("vimi-client-hotfix-watch-v1")
                || !lines[1].matches("[0-9a-f]{64}")
                || !file.getFileName().toString().equals(lines[1] + ".watch")
                || !lines[9].matches("0|[1-9][0-9]{0,8}")
                || !lines[10].matches("-?[0-9]{1,19}")
                || !lines[11].matches("[A-Za-z0-9_-]{0,512}")
                || !lines[12].isEmpty()) throw new IOException("Client-cache watch is invalid");
        try {
            var scope =
                    new Scope(
                            new BuildRef(
                                    lines[2],
                                    lines[3],
                                    lines[4],
                                    lines[5].equals("<none>") ? null : lines[5]),
                            lines[6],
                            lines[7]);
            Path original =
                    Path.of(
                            new String(
                                    Base64.getUrlDecoder().decode(lines[8]),
                                    StandardCharsets.UTF_8));
            if (!scope.key().equals(lines[1]) || !original.isAbsolute())
                throw new IllegalArgumentException();
            return new Watch(
                    scope,
                    original,
                    Long.parseLong(lines[9]),
                    Long.parseLong(lines[10]),
                    lines[11]);
        } catch (RuntimeException invalid) {
            throw new IOException("Client-cache watch is invalid", invalid);
        }
    }

    private List<RefreshResult> refreshActual(Cancellation token) throws IOException {
        ensureStorage();
        var files = new java.util.ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(directory.resolve("watches"), "*.watch")) {
            for (Path file : stream) {
                if (files.size() == MAX_WATCHES)
                    throw new IOException("Too many watched client caches");
                files.add(file);
            }
        }
        files.sort(null);
        var results = new java.util.ArrayList<RefreshResult>();
        // On cancellation, stop and return what was already checked: those bindings are
        // durable, and the caller still reports the run as canceled from its own token.
        try {
            for (Path file : files) {
                token.check();
                var leftover = dropMarkedLeftover(file, token);
                if (leftover != null) {
                    results.add(leftover);
                    continue;
                }
                var result = refreshOne(file, token);
                if (result != null) results.add(result);
            }
        } catch (IOException | RuntimeException failure) {
            if (!(token.isCanceled() || canceled(failure))) throw failure;
        }
        return List.copyOf(results);
    }

    private RefreshResult refreshOne(Path file, Cancellation token) throws IOException {
        Watch watch;
        Summary current;
        try {
            watch = readWatch(file);
            current = readManifest(watch.scope());
        } catch (IOException | RuntimeException invalid) {
            // An unreadable private record cannot be cleared from any workspace; remove it
            // (regular files only) so it fails one refresh rather than every refresh.
            try {
                regular(file);
                Files.delete(file);
            } catch (IOException | RuntimeException ignored) {
                /* Reported below; the next refresh retries the removal. */
            }
            return new RefreshResult(null, RefreshOutcome.FAILED, null, 0, invalid);
        }
        var scope = watch.scope();
        if (current == null) {
            // A watch never outlives its binding through this store; drop an orphan quietly.
            try {
                deleteWatch(scope, token);
            } catch (IOException | RuntimeException failure) {
                if (token.isCanceled()) throw failure;
            }
            return null;
        }
        BasicFileAttributes now;
        try {
            now = regular(watch.original());
        } catch (java.nio.file.NoSuchFileException missing) {
            return new RefreshResult(scope, RefreshOutcome.MISSING, current, 0, null);
        } catch (IOException | RuntimeException unreadable) {
            return new RefreshResult(scope, RefreshOutcome.FAILED, current, 0, unreadable);
        }
        if (now.size() == watch.size()
                && now.lastModifiedTime().to(TimeUnit.NANOSECONDS) == watch.modifiedNanos()
                && fileIdentity(now).equals(watch.fileKey()))
            return new RefreshResult(
                    scope, RefreshOutcome.UNCHANGED, current, scope.buildNumber(), null);
        Imported imported;
        try {
            int declared = declaredBuild(watch.original());
            if (declared > 0 && declared != scope.buildNumber())
                return new RefreshResult(
                        scope, RefreshOutcome.CLIENT_BUILD_CHANGED, current, declared, null);
            imported = importActual(scope, watch.original(), true, token);
        } catch (IOException | RuntimeException failure) {
            if (token.isCanceled() || canceled(failure)) throw failure;
            return new RefreshResult(scope, RefreshOutcome.FAILED, current, 0, failure);
        }
        Throwable statFailure = null;
        try {
            writeWatch(scope, imported.input(), imported.source(), token);
        } catch (IOException | RuntimeException failure) {
            if (token.isCanceled() || canceled(failure)) throw failure;
            // The verified binding is already durable; a stale recorded stat only forces the
            // next refresh to reverify the same bytes, so report the real outcome plus failure.
            statFailure = failure;
        }
        return new RefreshResult(
                scope,
                imported.changed() ? RefreshOutcome.UPDATED : RefreshOutcome.UNCHANGED,
                imported.summary(),
                scope.buildNumber(),
                statFailure);
    }

    /** Header build of a recognizable XFTH v9 file, else -1; full verification follows. */
    private int declaredBuild(Path path) throws IOException {
        try (var input =
                owned(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var header = ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) if (input.read(header) < 0) return -1;
            header.flip();
            if (header.getInt() != 0x48544658 || header.getInt() != 9) return -1;
            return header.getInt();
        }
    }

    private static boolean canceled(Throwable error) {
        for (int i = 0; i < 8 && error != null; i++, error = error.getCause())
            if (error instanceof CancellationException) return true;
        return false;
    }

    private static BasicFileAttributes regular(Path path) throws IOException {
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile())
            throw new IOException("Client-cache input must be a regular file");
        return attributes;
    }

    private static void privateDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path))
            throw new IOException("Client-cache directory must not be a symbolic link");
        Files.createDirectories(path, attributes(path, true));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Client-cache directory must not be a symbolic link");
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, DIRECTORY_MODE);
    }

    private static void ownedDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Private client-cache directory is unavailable");
    }

    private void closeFailedLock(Throwable failure) {
        var actual = lockChannel;
        lockChannel = null;
        try {
            if (actual != null) owned(actual).close();
        } catch (IOException | RuntimeException | Error cleanup) {
            append(failure, cleanup);
        }
    }

    private static FileAttribute<?>[] attributes(Path path, boolean directory) throws IOException {
        Path parent = path.getParent();
        if (parent != null
                && Files.exists(parent)
                && Files.getFileStore(parent).supportsFileAttributeView("posix"))
            return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(directory ? DIRECTORY_MODE : FILE_MODE)
            };
        return new FileAttribute<?>[0];
    }

    private OwnedChannel create(Path path) throws IOException {
        return owned(
                FileChannel.open(
                        path,
                        Set.of(
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        attributes(path, false)));
    }

    private OwnedChannel owned(FileChannel channel) {
        return new OwnedChannel(channel);
    }

    private final class OwnedChannel implements AutoCloseable {
        private final FileChannel channel;

        private OwnedChannel(FileChannel channel) {
            this.channel = channel;
        }

        long size() throws IOException {
            return channel.size();
        }

        int read(ByteBuffer buffer) throws IOException {
            return channel.read(buffer);
        }

        int write(ByteBuffer buffer) throws IOException {
            return channel.write(buffer);
        }

        void force(boolean metadata) throws IOException {
            channel.force(metadata);
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
                probe.afterOwnedChannelClose();
            } catch (IOException | RuntimeException | Error failure) {
                synchronized (gate) {
                    quarantinedChannels.add(channel);
                }
                recordCleanup(failure);
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface Io<T> {
        T run() throws IOException;
    }

    private static <T> T unchecked(Io<T> action) {
        try {
            return action.run();
        } catch (IOException error) {
            throw new UncheckedIOException("Client hotfix storage failed", error);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (next == null) return first;
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error != null) throw new IllegalStateException("Client-cache cleanup failed", error);
    }
}
