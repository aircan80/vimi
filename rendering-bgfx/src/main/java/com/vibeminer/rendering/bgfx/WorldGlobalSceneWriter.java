package com.vibeminer.rendering.bgfx;

import static com.vibeminer.rendering.bgfx.WorldGlobalLayout.require;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Structural standalone writer boundary. Only the closed application bridge qualifies Source. */
public final class WorldGlobalSceneWriter {
    private WorldGlobalSceneWriter() {}

    public static final class Description {
        private final long mapId;
        private final String scope, buildLocale, recipe;
        private final WorldGlobalHashes.RawModf modf;
        private final List<WorldGlobalHashes.Source> sources;
        private final List<WorldGlobalHashes.Material> materials;
        private final List<WorldGlobalHashes.Image> images;

        public Description(
                long mapId,
                String scope,
                String buildLocale,
                WorldGlobalHashes.RawModf modf,
                List<WorldGlobalHashes.Source> sources,
                List<WorldGlobalHashes.Material> materials,
                List<WorldGlobalHashes.Image> images) {
            this.sources = bounded(sources, 28);
            this.materials = bounded(materials, 24);
            this.images = bounded(images, 18);
            recipe =
                    WorldGlobalHashes.recipe(
                            mapId,
                            scope,
                            buildLocale,
                            modf,
                            this.sources,
                            this.materials,
                            this.images);
            this.mapId = mapId;
            this.scope = scope;
            this.buildLocale = buildLocale;
            this.modf = modf;
        }

        public long mapId() {
            return mapId;
        }

        public String scope() {
            return scope;
        }

        public String buildLocale() {
            return buildLocale;
        }

        public String recipe() {
            return recipe;
        }

        public WorldGlobalHashes.RawModf modf() {
            return modf;
        }

        public List<WorldGlobalHashes.Source> sources() {
            return sources;
        }

        public List<WorldGlobalHashes.Material> materials() {
            return materials;
        }

        public List<WorldGlobalHashes.Image> images() {
            return images;
        }
    }

    /**
     * Frozen synchronous scalar borrow; no Source, array, NIO or image ownership is transferred.
     */
    public interface StructuralRead extends WorldGlobalHashes.Mesh {
        Description description();

        int imageBytes(int image);

        int imageByte(int image, int offset);
    }

    /** Compilation boundary only; production application wiring must use the fixed writer. */
    @FunctionalInterface
    public interface Writer {
        UnadvertisedFile write(
                Destination destination, StructuralRead borrowed, BooleanSupplier stopping)
                throws IOException;
    }

    /**
     * Pure request metadata. The application owns the private directory; this does not create it.
     */
    public static final class Destination {
        private final Path path;
        private final Object requestIdentity;
        private final String scope, nonce;

        private Destination(Path directory, Object requestIdentity, String scope) {
            Objects.requireNonNull(directory);
            Objects.requireNonNull(requestIdentity);
            require(
                    requestIdentity.getClass() == Object.class
                            && directory.getFileSystem() == FileSystems.getDefault()
                            && directory.isAbsolute()
                            && directory.equals(directory.normalize())
                            && directory.getParent() != null);
            WorldGlobalLayout.hex(scope, 64);
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            nonce = HexFormat.of().formatHex(random);
            this.scope = scope;
            this.requestIdentity = requestIdentity;
            path = directory.resolve("global.wgs");
        }

        public static Destination create(
                Path privateDirectory, Object literalRequestIdentity, String scope) {
            return new Destination(privateDirectory, literalRequestIdentity, scope);
        }

        public Path path() {
            return path;
        }

        public String scope() {
            return scope;
        }

        public String nonce() {
            return nonce;
        }
    }

    /** Descriptive expectations; construction never authenticates a game source or owns a path. */
    public record ExpectedFile(
            WorldGlobalStatus.Identity identity,
            int length,
            WorldGlobalLayout.Counts counts,
            String recipeSha256,
            String nativeWireSha256) {
        public ExpectedFile {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(counts);
            require(length == counts.offsets().actualBytes());
            WorldGlobalLayout.hex(recipeSha256, 64);
            WorldGlobalLayout.hex(nativeWireSha256, 64);
        }

        public InputExpectation input() {
            return new InputExpectation(identity, length);
        }
    }

    /**
     * Explicit child subset. Its reader must still compute/check whole-file SHA on actual bytes.
     */
    public record InputExpectation(WorldGlobalStatus.Identity identity, int length) {
        public InputExpectation {
            Objects.requireNonNull(identity);
            require(length >= 1024 && length <= WorldGlobalLayout.MAX_ENCODED_BYTES);
        }
    }

    private record Data(Path path, Object identity, ExpectedFile expected) {}

    private record Cleanup(Data data, CompletableFuture<Void> done) {}

    private static final class Owner {
        private final Object lock = new Object();
        private Data data;
        private CompletableFuture<Void> completion;

        private Owner(Data data) {
            this.data = data;
        }

        MovedInput move() {
            synchronized (lock) {
                if (data == null || completion != null) {
                    throw new IllegalStateException("Global file moved or closing");
                }
                var moved = new MovedInput(data);
                var inert = CompletableFuture.<Void>completedFuture(null);
                data = null;
                completion = inert;
                return moved;
            }
        }

        Cleanup closePlan() {
            synchronized (lock) {
                if (completion != null) {
                    return new Cleanup(null, completion);
                }
                var done = new CompletableFuture<Void>();
                var plan = new Cleanup(data, done);
                completion = done;
                data = null;
                return plan;
            }
        }
    }

    /** Source R owns this until one accepted resource pin calls move after Source final checks. */
    public static final class UnadvertisedFile implements AutoCloseable {
        private final Data metadata;
        private final Owner owner;

        private UnadvertisedFile(Data data) {
            metadata = data;
            owner = new Owner(data);
        }

        public ExpectedFile expected() {
            return metadata.expected();
        }

        public MovedInput move() {
            return owner.move();
        }

        public CompletableFuture<Void> closeAsync(Executor existingFileLane) {
            Objects.requireNonNull(existingFileLane);
            return schedule(owner.closePlan(), existingFileLane);
        }

        @Override
        public void close() {
            closeNow(owner.closePlan());
        }
    }

    /**
     * After MOVE this independent transport owner alone deletes after read/process actual drain.
     */
    public static final class MovedInput implements AutoCloseable {
        private final Data metadata;
        private final Owner owner;

        private MovedInput(Data data) {
            metadata = data;
            owner = new Owner(data);
        }

        public ExpectedFile expected() {
            return metadata.expected();
        }

        public Path path() {
            return metadata.path();
        }

        public boolean isFor(Destination destination) {
            return destination != null
                    && metadata.identity() == destination.requestIdentity
                    && metadata.path().equals(destination.path())
                    && metadata.expected().identity().nonce().equals(destination.nonce())
                    && metadata.expected().identity().scope().equals(destination.scope());
        }

        public CompletableFuture<Void> closeAsync(Executor existingFileLane) {
            Objects.requireNonNull(existingFileLane);
            return schedule(owner.closePlan(), existingFileLane);
        }

        @Override
        public void close() {
            closeNow(owner.closePlan());
        }
    }

    /**
     * Package-internal writer handoff, not public path adoption. The caller MUST already own this
     * exact file by successful CREATE_NEW; if validation fails the caller still owns its cleanup.
     */
    static UnadvertisedFile finalizedCreated(Destination destination, ExpectedFile expected)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(expected);
        require(
                expected.identity().nonce().equals(destination.nonce())
                        && expected.identity().scope().equals(destination.scope()));
        var attributes =
                Files.readAttributes(
                        destination.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expected.length()) {
            throw new IOException("Finalized global file metadata differs");
        }
        return new UnadvertisedFile(
                new Data(destination.path(), destination.requestIdentity, expected));
    }

    private static CompletableFuture<Void> schedule(Cleanup plan, Executor lane) {
        if (plan.data() != null) {
            try {
                lane.execute(() -> delete(plan));
            } catch (RuntimeException failure) {
                plan.done().completeExceptionally(detached(failure));
            } catch (Error fatal) {
                plan.done().completeExceptionally(detached(fatal));
                throw fatal;
            }
        }
        return plan.done().copy();
    }

    private static void closeNow(Cleanup plan) {
        if (plan.data() != null) {
            delete(plan);
        }
        try {
            plan.done().join();
        } catch (java.util.concurrent.CompletionException failure) {
            throw new UncheckedIOException((IOException) failure.getCause());
        }
    }

    private static void delete(Cleanup plan) {
        try {
            Files.deleteIfExists(plan.data().path());
            plan.done().complete(null);
        } catch (IOException | RuntimeException failure) {
            plan.done().completeExceptionally(detached(failure));
        } catch (Error fatal) {
            plan.done().completeExceptionally(detached(fatal));
            throw fatal;
        }
    }

    private static IOException detached(Throwable failure) {
        String name = failure.getClass().getName();
        if (name.length() > 180) {
            name = name.substring(0, 180);
        }
        var result = new IOException("Global file cleanup failed: " + name);
        result.setStackTrace(new StackTraceElement[0]);
        return result;
    }

    private static <T> List<T> bounded(List<T> values, int max) {
        Objects.requireNonNull(values);
        int count = values.size();
        require(count >= 1 && count <= max);
        var copy = new ArrayList<T>(count);
        for (int n = 0; n < count; n++) {
            copy.add(Objects.requireNonNull(values.get(n)));
        }
        return List.copyOf(copy);
    }
}
