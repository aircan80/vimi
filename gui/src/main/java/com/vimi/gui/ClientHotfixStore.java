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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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
    private final Path directory;
    private final Probe probe;

    interface Probe {
        default void beforeRead() {}

        default void beforeCopy(long copied, Cancellation token) {}

        default void afterSnapshotClose() {}
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

    CompletableFuture<Summary> importCache(Scope scope, Path original, Cancellation token) {
        Objects.requireNonNull(original);
        return submit(token, cancel -> unchecked(() -> importActual(scope, original, cancel)));
    }

    CompletableFuture<Snapshot> open(Scope scope, Cancellation token) {
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    var summary = readManifest(scope);
                                    return summary == null
                                            ? null
                                            : openSnapshot(
                                                    object(summary.sha256()),
                                                    scope,
                                                    summary,
                                                    cancel);
                                }));
    }

    /** Clears association only. Immutable imported bytes are deliberately retained. */
    CompletableFuture<Void> disable(Scope scope, Cancellation token) {
        return submit(
                token,
                cancel ->
                        unchecked(
                                () -> {
                                    ensureStorage();
                                    Path binding = binding(scope);
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
                        } finally {
                            try {
                                registration.close();
                            } catch (RuntimeException | Error error) {
                                failure = append(failure, error);
                            }
                        }
                        try {
                            if (failure == null) result.complete(value);
                            else result.completeExceptionally(failure);
                        } finally {
                            operationFinished(token);
                        }
                    });
        } catch (RuntimeException | Error failure) {
            registration.close();
            operationFinished(token);
            result.completeExceptionally(failure);
        }
        return result.copy();
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

    private Summary importActual(Scope scope, Path original, Cancellation token)
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
                            FileChannel.open(
                                    input, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
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
            } finally {
                staged.closeActual();
            }
            var after = regular(input);
            if (after.size() != before.size()
                    || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(after.fileKey(), before.fileKey()))
                throw new IOException("Client cache changed during import");
            token.check();
            Path destination = object(digest);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                var existing = openSnapshot(destination, scope, summary, token);
                existing.closeActual();
            } else Files.move(temporary, destination);
            writeManifest(scope, summary, token);
            return summary;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException | Error error) {
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
            if (error == null) finished.complete(null);
            else finished.completeExceptionally(error);
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
        if (lock != null) return;
        privateDirectory(directory);
        privateDirectory(directory.resolve("objects"));
        privateDirectory(directory.resolve("bindings"));
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
        } catch (RuntimeException | IOException failure) {
            lockChannel.close();
            lockChannel = null;
            throw failure;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            throw new IOException("Client-cache store is already open");
        }
    }

    private Path object(String hash) {
        return directory.resolve("objects").resolve(hash + ".bin");
    }

    private Path binding(Scope scope) {
        return directory.resolve("bindings").resolve(scope.key() + ".binding");
    }

    private Summary readManifest(Scope scope) throws IOException {
        Path path = binding(scope);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        var attributes = regular(path);
        if (attributes.size() > MAX_MANIFEST_BYTES)
            throw new IOException("Client-cache binding exceeds bounds");
        byte[] bytes;
        try (var input =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() != attributes.size())
                throw new IOException("Client-cache binding changed");
            var data = ByteBuffer.allocate((int) attributes.size());
            while (data.hasRemaining())
                if (input.read(data) < 0) throw new IOException("Client-cache binding truncated");
            bytes = data.array();
        }
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
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES)
            throw new IOException("Client-cache binding exceeds bounds");
        Path path = binding(scope),
                temporary = path.resolveSibling("binding-" + UUID.randomUUID() + ".tmp");
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
                if (failure != null) append(failure, cleanup);
                else throw cleanup;
            }
        }
    }

    private static BasicFileAttributes regular(Path path) throws IOException {
        noSymlinkComponents(path);
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile())
            throw new IOException("Client-cache input must be a regular file");
        return attributes;
    }

    private static void privateDirectory(Path path) throws IOException {
        noSymlinkComponents(path);
        Files.createDirectories(path, attributes(path, true));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Client-cache directory must not be a symbolic link");
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, DIRECTORY_MODE);
    }

    private static void noSymlinkComponents(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current))
                throw new IOException("Client-cache paths must not contain symbolic links");
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

    private static FileChannel create(Path path) throws IOException {
        return FileChannel.open(
                path,
                Set.of(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                attributes(path, false));
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
