package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Canonical structural decode only. No Source qualification, native handles or file ownership. */
public final class GpuGlobalWmoSceneFile {
    private GpuGlobalWmoSceneFile() {}

    /** Private adoption only; one CPU mesh and image set, never public backing-array ownership. */
    public static final class Decoded
            implements WorldGlobalSceneWriter.StructuralRead, AutoCloseable {
        private final Object lock = new Object();
        private final WorldGlobalFileCodec.Header header;
        private final WorldGlobalFileCodec.Meta metadata;
        private float[] vertices;
        private int[] indices;
        private List<byte[]> images;

        private Decoded(
                WorldGlobalFileCodec.Header header,
                WorldGlobalFileCodec.Meta metadata,
                float[] vertices,
                int[] indices,
                List<byte[]> images) {
            this.header = header;
            this.metadata = metadata;
            this.vertices = vertices;
            this.indices = indices;
            this.images = images;
        }

        public WorldGlobalLayout.Counts counts() {
            return header.counts();
        }

        public WorldGlobalSceneWriter.Description description() {
            return metadata.description();
        }

        public List<WorldGlobalHashes.Group> groups() {
            return metadata.groups();
        }

        public List<WorldGlobalHashes.Batch> batches() {
            return metadata.batches();
        }

        public float vertex(int vertex, int component) {
            synchronized (lock) {
                open();
                Objects.checkIndex(vertex, counts().vertices());
                Objects.checkIndex(component, 5);
                return vertices[vertex * 5 + component];
            }
        }

        public int index(int index) {
            synchronized (lock) {
                open();
                return indices[Objects.checkIndex(index, counts().indices())];
            }
        }

        public int imageBytes(int image) {
            synchronized (lock) {
                open();
                return images.get(Objects.checkIndex(image, images.size())).length;
            }
        }

        public int imageByte(int image, int offset) {
            synchronized (lock) {
                open();
                var data = images.get(Objects.checkIndex(image, images.size()));
                return data[Objects.checkIndex(offset, data.length)] & 255;
            }
        }

        private void open() {
            if (vertices == null) {
                throw new IllegalStateException("Decoded global payload closed");
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                vertices = null;
                indices = null;
                images = null;
            }
        }
    }

    /**
     * Opens exactly one regular NOFOLLOW handle. Full checksum/identity pass precedes payload
     * allocation; the same handle is decoded and hashed again before private owner transfer.
     */
    public static Decoded read(
            Path path, WorldGlobalSceneWriter.InputExpectation expected, BooleanSupplier stopping)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(stopping);
        WorldGlobalLayout.require(
                path.getFileSystem() == FileSystems.getDefault()
                        && path.isAbsolute()
                        && path.equals(path.normalize()));
        WorldGlobalFileAccess.stop(stopping);
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expected.length()) {
            throw new IOException("Global input is not the expected regular file");
        }
        Decoded result = null;
        try (var file =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            WorldGlobalFileCodec.check(file.size() == expected.length());
            byte[] rawHeader = WorldGlobalFileAccess.read(file, 1024, stopping);
            var header = WorldGlobalFileCodec.header(rawHeader, expected);
            var first = WorldGlobalFileAccess.hash(file, header.counts(), stopping);
            integrity(
                    rawHeader,
                    first,
                    expected.identity().fileSha256()); // No large CPU arrays before this.
            file.position(1024);
            byte[] rawMetadata =
                    WorldGlobalFileAccess.read(
                            file, header.counts().offsets().vertices() - 1024, stopping);
            var metadata = WorldGlobalFileCodec.metadata(header, rawMetadata);
            var c = header.counts();
            var cursor = new WorldGlobalFileAccess.Cursor(file, c, stopping);
            byte[] secondHeader = new byte[1024];
            cursor.bytes(secondHeader);
            WorldGlobalFileCodec.check(Arrays.equals(rawHeader, secondHeader));
            cursor.expect(rawMetadata);
            float[] vertices = new float[c.vertices() * 5];
            float[] bounds = {
                Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY
            };
            for (int vertex = 0; vertex < c.vertices(); vertex++) {
                WorldGlobalFileAccess.stop(stopping);
                for (int component = 0; component < 5; component++) {
                    float value = Float.intBitsToFloat(cursor.integer());
                    WorldGlobalFileCodec.check(
                            component < 3
                                    ? WorldGlobalFileCodec.position(value)
                                    : WorldGlobalFileCodec.uv(value));
                    vertices[vertex * 5 + component] = value;
                    if (component < 3) {
                        bounds[component] = Math.min(bounds[component], value);
                        bounds[component + 3] = Math.max(bounds[component + 3], value);
                    }
                }
            }
            WorldGlobalFileCodec.check(Arrays.equals(bounds, header.bounds()));
            int[] indices = new int[c.indices()];
            for (var group : metadata.groups()) {
                for (int n = group.indexFirst(); n < group.indexFirst() + group.indexCount(); n++) {
                    if ((n & 1023) == 0) {
                        WorldGlobalFileAccess.stop(stopping);
                    }
                    int value = cursor.integer();
                    WorldGlobalFileCodec.check(
                            value >= group.vertexFirst()
                                    && value < group.vertexFirst() + group.vertexCount());
                    indices[n] = value;
                }
            }
            var images = new ArrayList<byte[]>(c.images());
            for (int n = 0; n < c.images(); n++) {
                WorldGlobalFileAccess.stop(stopping);
                byte[] bytes = new byte[metadata.images().get(n).length()];
                cursor.bytes(bytes);
                for (int at = 3; at < bytes.length; at += 4) {
                    if ((at & 4095) == 3) {
                        WorldGlobalFileAccess.stop(stopping);
                    }
                    WorldGlobalFileCodec.check((bytes[at] & 255) == 255);
                }
                WorldGlobalFileCodec.check(
                        HexFormat.of()
                                .formatHex(WorldGlobalSceneWriter.sha().digest(bytes))
                                .equals(metadata.description().images().get(n).opaqueRgbaSha256()));
                images.add(bytes);
            }
            var second = cursor.finish();
            integrity(rawHeader, second, expected.identity().fileSha256());
            result = new Decoded(header, metadata, vertices, indices, images);
            var mesh = WorldGlobalHashes.mesh(result, stopping);
            WorldGlobalFileCodec.check(
                    mesh.nativeWire().equals(header.wire())
                            && mesh.groups().equals(metadata.groupHashes()));
            WorldGlobalFileAccess.stop(stopping);
            WorldGlobalFileCodec.check(file.size() == expected.length());
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            if (result != null) {
                result.close();
            }
            throw failure;
        }
    }

    private static void integrity(
            byte[] header, WorldGlobalHashes.FileDigest computed, String owned) throws IOException {
        try {
            WorldGlobalHashes.verifyIntegrity(header, computed, owned);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Global input integrity differs", invalid);
        }
    }
}
