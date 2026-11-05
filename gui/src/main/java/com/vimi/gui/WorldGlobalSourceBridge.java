package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldGlobalScenes;
import com.vibeminer.rendering.bgfx.WorldGlobalHashes;
import com.vibeminer.rendering.bgfx.WorldGlobalLayout;
import com.vibeminer.rendering.bgfx.WorldGlobalSceneWriter;
import com.vibeminer.rendering.world.GlobalInteriorLimits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed actual Source-to-file path. Every method with IO or resource retirement runs off FX. */
final class WorldGlobalSourceBridge {
    private WorldGlobalSourceBridge() {}

    static final class Prepared implements AutoCloseable {
        private final WorldGlobalScenes.WrittenScene<WorldGlobalSceneWriter.UnadvertisedFile>
                source;
        private final WorldGlobalSceneWriter.Destination destination;
        private final Path directory;
        private final Cancellation cancellation;
        private final float[] bounds;
        private boolean moved;

        private Prepared(
                WorldGlobalScenes.WrittenScene<WorldGlobalSceneWriter.UnadvertisedFile> source,
                WorldGlobalSceneWriter.Destination destination,
                Path directory,
                Cancellation cancellation,
                float[] bounds) {
            this.source = source;
            this.destination = destination;
            this.directory = directory;
            this.cancellation = cancellation;
            this.bounds = bounds.clone();
        }

        /** Descriptive native XYZ AABB captured under the actual writer borrow. */
        float[] bounds() {
            return bounds.clone();
        }

        WorldGlobalScenes.Facts facts() {
            return source.facts();
        }

        boolean isOpen() {
            return source.isOpen();
        }

        WorldGlobalSceneWriter.ExpectedFile expected() {
            var result = new WorldGlobalSceneWriter.ExpectedFile[1];
            source.withWrittenResource(file -> result[0] = file.expected());
            return result[0];
        }

        OwnedInput move() {
            cancellation.check();
            synchronized (this) {
                if (moved) {
                    throw new IllegalStateException("Global input already moved");
                }
            }
            var result = new WorldGlobalSceneWriter.MovedInput[1];
            Throwable failure = null;
            boolean returned = false;
            try {
                source.withWrittenResource(
                        file -> {
                            cancellation.check();
                            result[0] = file.move();
                            synchronized (this) {
                                moved = true;
                            }
                        });
                cancellation.check();
                if (result[0] == null || !result[0].isFor(destination)) {
                    throw new IllegalStateException("Global file ownership mismatch");
                }
                var owned = new OwnedInput(result[0], directory);
                returned = true;
                return owned;
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                if (!returned && result[0] != null) {
                    Throwable cleanup = null;
                    try {
                        result[0].close();
                    } catch (RuntimeException | Error error) {
                        cleanup = error;
                    }
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException error) {
                        cleanup = append(cleanup, new UncheckedIOException(error));
                    }
                    if (cleanup != null) {
                        if (failure != null) {
                            append(failure, cleanup);
                        } else {
                            rethrow(cleanup);
                        }
                    }
                }
            }
        }

        @Override
        public void close() {
            Throwable failure = null;
            try {
                source.closeAsync().join();
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            boolean transferred;
            synchronized (this) {
                transferred = moved;
            }
            if (!transferred) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException cleanup) {
                    failure = append(failure, new UncheckedIOException(cleanup));
                }
            }
            if (failure != null) {
                rethrow(failure);
            }
        }
    }

    /**
     * File-only capability; Source owns no deletion right after MOVE. Close after actual child
     * drain.
     */
    static final class OwnedInput implements AutoCloseable {
        private final WorldGlobalSceneWriter.MovedInput input;
        private final Path directory;

        private OwnedInput(WorldGlobalSceneWriter.MovedInput input, Path directory) {
            this.input = input;
            this.directory = directory;
        }

        WorldGlobalSceneWriter.MovedInput input() {
            return input;
        }

        @Override
        public void close() {
            Throwable failure = null;
            try {
                input.close();
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                if (failure == null) {
                    failure = new UncheckedIOException(cleanup);
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }

    static Prepared prepare(
            WorldGlobalScenes source,
            WorldGlobalScenes.Scope scope,
            Path parent,
            Cancellation cancel)
            throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(cancel);
        cancel.check();
        var directory =
                Files.createTempDirectory(parent, "global-source-").toAbsolutePath().normalize();
        WorldGlobalScenes.WrittenScene<WorldGlobalSceneWriter.UnadvertisedFile> written = null;
        float[] bounds = new float[6];
        boolean returned = false;
        Throwable failure = null;
        try {
            var destination =
                    WorldGlobalSceneWriter.Destination.create(
                            directory, new Object(), scope.scopeSha256());
            written =
                    source.prepareAndWrite(
                            scope,
                            cancel,
                            (borrow, actualToken) -> {
                                captureBounds(borrow, bounds);
                                try {
                                    return WorldGlobalSceneWriter.writeUnadvertised(
                                            destination,
                                            new Adapter(borrow),
                                            actualToken::isCanceled);
                                } catch (IOException error) {
                                    throw new UncheckedIOException(error);
                                }
                            });
            var result = new Prepared(written, destination, directory, cancel, bounds);
            returned = true;
            return result;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (!returned) {
                Throwable cleanup = null;
                try {
                    if (written != null) {
                        written.closeAsync().join();
                    }
                } catch (RuntimeException | Error error) {
                    cleanup = error;
                }
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException error) {
                    cleanup = append(cleanup, new UncheckedIOException(error));
                }
                if (cleanup != null) {
                    if (failure != null) {
                        append(failure, cleanup);
                    } else {
                        rethrow(cleanup);
                    }
                }
            }
        }
    }

    /** Bounded scalar scan of the profile's vertices inside the live borrow; no arrays or IO. */
    static void captureBounds(WorldGlobalScenes.CheckedBorrow borrow, float[] bounds) {
        int vertices = borrow.vertices();
        if (vertices < 1 || vertices > GlobalInteriorLimits.VERTICES) {
            throw new IllegalStateException("Global vertex bound");
        }
        for (int axis = 0; axis < 3; axis++) {
            bounds[axis] = Float.POSITIVE_INFINITY;
            bounds[axis + 3] = Float.NEGATIVE_INFINITY;
        }
        for (int vertex = 0; vertex < vertices; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                float value = borrow.vertex(vertex, axis);
                bounds[axis] = Math.min(bounds[axis], value);
                bounds[axis + 3] = Math.max(bounds[axis + 3], value);
            }
        }
    }

    private static Throwable append(Throwable primary, Throwable cleanup) {
        if (primary == null) {
            return cleanup;
        }
        if (primary != cleanup) {
            primary.addSuppressed(cleanup);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("Global bridge cleanup failed", failure);
    }

    private static final class Adapter implements WorldGlobalSceneWriter.StructuralRead {
        private final WorldGlobalScenes.CheckedBorrow borrow;
        private final WorldGlobalSceneWriter.Description description;
        private final WorldGlobalLayout.Counts counts;
        private final List<WorldGlobalHashes.Group> groups;
        private final List<WorldGlobalHashes.Batch> batches;

        private Adapter(WorldGlobalScenes.CheckedBorrow borrow) {
            this.borrow = borrow;
            var facts = borrow.facts();
            var materials =
                    facts.materials().stream()
                            .map(
                                    m ->
                                            new WorldGlobalHashes.Material(
                                                    m.rawWords().stream()
                                                            .mapToLong(Long::longValue)
                                                            .toArray()))
                            .toList();
            var images =
                    facts.images().stream()
                            .map(
                                    i ->
                                            new WorldGlobalHashes.Image(
                                                    i.id(),
                                                    i.width(),
                                                    i.height(),
                                                    switch (i.encoding()) {
                                                        case BC1 -> 1;
                                                        case BC3 -> 2;
                                                        case BC2 -> 3;
                                                        default ->
                                                                throw new IllegalStateException(
                                                                        "Unqualified global image"
                                                                                + " encoding");
                                                    },
                                                    i.alphaDepth(),
                                                    i.alphaEncoding(),
                                                    i.mipCount(),
                                                    i.alphaMin(),
                                                    i.alphaMax(),
                                                    i.nonopaque(),
                                                    i.sourceRgbaSha256(),
                                                    i.opaqueRgbaSha256()))
                            .toList();
            var sources = new ArrayList<WorldGlobalHashes.Source>(
                            GlobalInteriorLimits.SOURCES);
            int groupOrdinal = 0, imageOrdinal = 0;
            for (var s : facts.sources()) {
                var role = WorldGlobalHashes.Role.valueOf(s.kind());
                int ordinal =
                        switch (role) {
                            case GROUP -> groupOrdinal++;
                            case IMAGE -> imageOrdinal++;
                            default -> 0;
                        };
                sources.add(
                        new WorldGlobalHashes.Source(
                                s.id(),
                                role,
                                ordinal,
                                s.length(),
                                s.declaredLength(),
                                s.key(),
                                s.sha256()));
            }
            byte[] modf = new byte[64];
            for (int i = 0; i < 64; i++) {
                modf[i] = (byte) facts.modfByte(i);
            }
            var build = facts.build();
            description =
                    new WorldGlobalSceneWriter.Description(
                            facts.mapId(),
                            facts.scopeSha256(),
                            WorldGlobalHashes.buildLocale(
                                    build.product(),
                                    build.buildConfig(),
                                    build.cdnConfig(),
                                    build.region(),
                                    facts.locale()),
                            WorldGlobalHashes.RawModf.copyOf(
                                    modf,
                                    facts.declaration().reference(),
                                    facts.declaration().uniqueId()),
                            sources,
                            materials,
                            images);
            var spans = borrow.spans();
            batches =
                    spans.stream()
                            .map(
                                    s ->
                                            new WorldGlobalHashes.Batch(
                                                    s.group(),
                                                    s.material(),
                                                    s.firstIndex(),
                                                    s.indexCount()))
                            .toList();
            var groupList = new ArrayList<WorldGlobalHashes.Group>(facts.groups().size());
            int vertex = 0, index = 0, batch = 0, drawn = 0;
            for (var g : facts.groups()) {
                int size = g.rawTriangles() * 3;
                groupList.add(
                        new WorldGlobalHashes.Group(
                                vertex, g.vertices(), index, size, batch, g.batches(), g.flags()));
                vertex += g.vertices();
                index += size;
                batch += g.batches();
                drawn += g.drawnTriangles();
            }
            groups = List.copyOf(groupList);
            int pixels = images.stream().mapToInt(i -> i.width() * i.height()).sum();
            counts =
                    new WorldGlobalLayout.Counts(
                            materials.size(),
                            groups.size(),
                            batches.size(),
                            images.size(),
                            borrow.vertices(),
                            borrow.indices(),
                            drawn,
                            pixels);
        }

        @Override
        public WorldGlobalSceneWriter.Description description() {
            return description;
        }

        @Override
        public WorldGlobalLayout.Counts counts() {
            return counts;
        }

        @Override
        public List<WorldGlobalHashes.Group> groups() {
            return groups;
        }

        @Override
        public List<WorldGlobalHashes.Batch> batches() {
            return batches;
        }

        @Override
        public float vertex(int vertex, int component) {
            return borrow.vertex(vertex, component);
        }

        @Override
        public int index(int ordinal) {
            return borrow.index(ordinal);
        }

        @Override
        public int imageBytes(int image) {
            return borrow.imageBytes(image);
        }

        @Override
        public int imageByte(int image, int offset) {
            return borrow.imageByte(image, offset);
        }
    }
}
