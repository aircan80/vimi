package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.GeometryTypes.Artifact;
import com.vibeminer.formats.models.GeometryTypes.OriginalSource;
import com.vibeminer.formats.models.GeometryTypes.SourceBinding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ModelGeometryArtifactsTest {
    private static SourceBinding binding() {
        return new SourceBinding(
                new OriginalSource(
                        "1".repeat(32),
                        "2".repeat(32),
                        "3".repeat(64),
                        1024,
                        589,
                        "content-blte-v1"),
                new OriginalSource(
                        "4".repeat(32),
                        "5".repeat(32),
                        "6".repeat(64),
                        512,
                        158,
                        "content-blte-v1"),
                123,
                900,
                0);
    }

    private static M2Types.GeometrySkin skin() {
        var model = M2ReadersTest.model(274);
        return M2GeometrySkinReader.read(
                        AssetSource.bytes(M2ReadersTest.golden("skin-274.skin")),
                        model,
                        null,
                        Cancellation.NONE)
                .join();
    }

    private static ModelFormatException failure(Runnable action) {
        Throwable error = AssetTasks.unwrap(assertThrows(RuntimeException.class, action::run));
        return assertInstanceOf(ModelFormatException.class, error);
    }

    @Test
    void originalCsharpGoldenBindingArtifactHeaderAndBytesAreExact() {
        MemoryChannel channel = new MemoryChannel(null);
        Artifact artifact =
                ModelGeometryArtifacts.build(skin(), binding(), channel, Cancellation.NONE).join();
        assertEquals(
                "b30ac8a24c76e1c9e72b8e32a3783bb3b0d30c8721ee86f362f18309ca026e2c",
                artifact.bindingSha256());
        assertEquals(
                "43d6ba97b6f42fc02e711c29761ad825cfd5aa8e3a11af0732e88ba74525cf8a",
                artifact.artifactId());
        assertEquals(
                "d3fca996b30ee38ebac782c1a0d8d4ed0f9b2033cbc73d03130131f5799017e7",
                artifact.sha256());
        assertEquals(232, artifact.byteLength());
        assertArrayEquals(M2ReadersTest.golden("geometry.vmg1"), channel.bytes());
        assertEquals(6, artifact.manifest().lineIndexCount());
        assertEquals(128, artifact.manifest().positions().offset());
        ModelGeometryArtifacts.verify(
                        AssetSource.bytes(channel.bytes()), artifact, Cancellation.NONE)
                .join();
        assertTrue(channel.isOpen());
    }

    @Test
    void everyArtifactTruncationAndTrailingByteRefused() {
        MemoryChannel channel = new MemoryChannel(null);
        Artifact artifact =
                ModelGeometryArtifacts.build(skin(), binding(), channel, Cancellation.NONE).join();
        byte[] bytes = channel.bytes();
        for (int size = 0; size < bytes.length; size++) {
            byte[] truncated = Arrays.copyOf(bytes, size);
            assertEquals(
                    "GEOMETRY_ARTIFACT_INVALID",
                    failure(
                                    () ->
                                            ModelGeometryArtifacts.verify(
                                                            AssetSource.bytes(truncated),
                                                            artifact,
                                                            Cancellation.NONE)
                                                    .join())
                            .code());
        }
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 1);
        assertEquals(
                "GEOMETRY_ARTIFACT_INVALID",
                failure(
                                () ->
                                        ModelGeometryArtifacts.verify(
                                                        AssetSource.bytes(extra),
                                                        artifact,
                                                        Cancellation.NONE)
                                                .join())
                        .code());
    }

    @Test
    void headerPatternDirectoryBoundsAndCompleteHashVerified() {
        MemoryChannel channel = new MemoryChannel(null);
        Artifact artifact =
                ModelGeometryArtifacts.build(skin(), binding(), channel, Cancellation.NONE).join();
        for (int at : new int[] {0, 16, 124, 128, 176, 184, 200, 224}) {
            byte[] changed = channel.bytes();
            changed[at] ^= 1;
            assertEquals(
                    "GEOMETRY_ARTIFACT_INVALID",
                    failure(
                                    () ->
                                            ModelGeometryArtifacts.verify(
                                                            AssetSource.bytes(changed),
                                                            artifact,
                                                            Cancellation.NONE)
                                                    .join())
                            .code());
        }
        byte[] nan = channel.bytes();
        M2ReadersTest.put(nan, 128, 0x7fc00000);
        assertTrue(
                failure(
                                () ->
                                        ModelGeometryArtifacts.verify(
                                                        AssetSource.bytes(nan),
                                                        artifact,
                                                        Cancellation.NONE)
                                                .join())
                        .getMessage()
                        .contains("finite"));
    }

    @Test
    void cpuScopesNeverCoverOutputOrSourceIoAndAreReleased() {
        AtomicInteger active = new AtomicInteger(), entries = new AtomicInteger();
        GeometryArtifactWorkBudget budget =
                token -> {
                    token.check();
                    assertEquals(0, active.getAndIncrement());
                    entries.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            () -> assertEquals(1, active.getAndDecrement()));
                };
        MemoryChannel output = new MemoryChannel(active);
        Artifact artifact =
                ModelGeometryArtifacts.build(skin(), binding(), output, budget, Cancellation.NONE)
                        .join();
        AssetSource inner = AssetSource.bytes(output.bytes());
        AssetSource guarded =
                new AssetSource() {
                    public long length() {
                        assertEquals(0, active.get());
                        return inner.length();
                    }

                    public CompletableFuture<byte[]> readAt(
                            long at, int count, Cancellation token) {
                        assertEquals(0, active.get());
                        assertTrue(count <= 65536);
                        return inner.readAt(at, count, token);
                    }
                };
        ModelGeometryArtifacts.verify(guarded, artifact, budget, Cancellation.NONE).join();
        assertEquals(0, active.get());
        assertTrue(entries.get() > 10);
    }

    @Test
    void cancellationAtAdmissionClosesScopeAndDoesNotWrite() {
        Cancellation token = new Cancellation();
        AtomicInteger closed = new AtomicInteger();
        GeometryArtifactWorkBudget budget =
                cancel -> {
                    cancel.cancel();
                    return CompletableFuture.completedFuture(closed::incrementAndGet);
                };
        MemoryChannel output = new MemoryChannel(null);
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        ModelGeometryArtifacts.build(
                                                        skin(), binding(), output, budget, token)
                                                .join())));
        assertEquals(1, closed.get());
        assertEquals(0, output.size());
    }

    @Test
    void exactMainSkinAndSourceLengthRequired() {
        SourceBinding wrong = new SourceBinding(binding().model(), binding().skin(), 123, 901, 0);
        assertEquals(
                "GEOMETRY_ARTIFACT_INVALID",
                failure(
                                () ->
                                        ModelGeometryArtifacts.build(
                                                        skin(),
                                                        wrong,
                                                        new MemoryChannel(null),
                                                        Cancellation.NONE)
                                                .join())
                        .code());
        SourceBinding upper =
                new SourceBinding(
                        new OriginalSource(
                                "A".repeat(32),
                                "2".repeat(32),
                                "3".repeat(64),
                                1024,
                                589,
                                "content-blte-v1"),
                        binding().skin(),
                        123,
                        900,
                        0);
        assertEquals(
                "GEOMETRY_ARTIFACT_INVALID",
                failure(() -> ModelGeometryArtifacts.computeBindingSha256(upper, Cancellation.NONE))
                        .code());
        SourceBinding tooLarge =
                new SourceBinding(
                        new OriginalSource(
                                "1".repeat(32),
                                "2".repeat(32),
                                "3".repeat(64),
                                (4L << 20) + 1,
                                589,
                                "content-blte-v1"),
                        binding().skin(),
                        123,
                        900,
                        0);
        assertEquals(
                "GEOMETRY_ARTIFACT_LIMIT",
                failure(
                                () ->
                                        ModelGeometryArtifacts.computeBindingSha256(
                                                tooLarge, Cancellation.NONE))
                        .code());
    }

    @Test
    void budgetFailureAndIoFailureDoNotLeakScopesOrCloseCallerOutput() {
        AtomicInteger active = new AtomicInteger();
        GeometryArtifactWorkBudget budget =
                token -> {
                    active.incrementAndGet();
                    return CompletableFuture.completedFuture(active::decrementAndGet);
                };
        MemoryChannel output =
                new MemoryChannel(active) {
                    @Override
                    public int write(ByteBuffer src) {
                        assertEquals(0, active.get());
                        throw new java.io.UncheckedIOException(new IOException("scripted"));
                    }
                };
        assertThrows(
                RuntimeException.class,
                () ->
                        ModelGeometryArtifacts.build(
                                        skin(), binding(), output, budget, Cancellation.NONE)
                                .join());
        assertEquals(0, active.get());
        assertTrue(output.isOpen());
    }

    private static class MemoryChannel implements SeekableByteChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicInteger active;
        private long position;
        private boolean open = true;

        MemoryChannel(AtomicInteger active) {
            this.active = active;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }

        public int read(ByteBuffer target) {
            throw new UnsupportedOperationException();
        }

        public int write(ByteBuffer source) {
            if (active != null) {
                assertEquals(0, active.get());
            }
            assertEquals(bytes.size(), position);
            int count = source.remaining();
            byte[] part = new byte[count];
            source.get(part);
            bytes.writeBytes(part);
            position += count;
            return count;
        }

        public long position() {
            return position;
        }

        public SeekableByteChannel position(long value) {
            position = value;
            return this;
        }

        public long size() {
            return bytes.size();
        }

        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        public boolean isOpen() {
            return open;
        }

        public void close() {
            open = false;
        }
    }
}
