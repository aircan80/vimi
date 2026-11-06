package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class WorldFrameFileV2Test {
    @TempDir Path directory;
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(17, 29);
    private static final String SCOPE = "a".repeat(64);

    private static WorldStreamFrameGate.Frame ticket(
            long sequence, long camera, long content, long policy, int width, int height) {
        return new WorldStreamFrameGate.Frame(
                NONCE, sequence, camera, content, policy, width, height);
    }

    private static ByteBuffer pixels(int... argb) {
        var buffer = ByteBuffer.allocate(argb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : argb) {
            buffer.putInt(value);
        }
        return buffer.flip();
    }

    private static WorldFrameFileV2.Barrier barrier(long cameraFloor, long contentCeiling) {
        return new WorldFrameFileV2.Barrier(1, 1, 1, cameraFloor, 100, 0, contentCeiling);
    }

    private static void overwrite(Path path, long offset, ByteBuffer bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                int written = channel.write(bytes, offset);
                if (written <= 0) {
                    throw new IOException("Fixture write stalled");
                }
                offset += written;
            }
        }
    }

    @Test
    void nativeOwnerRequiresMatchingFreshSessionBeforeAnyGpuAllocation() throws Exception {
        Path path = directory.resolve("fresh-owner");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            assertTrue(file.freshFor(new WorldStreamEnvelope.Session(NONCE, SCOPE, 1)));
            assertFalse(
                    file.freshFor(
                            new WorldStreamEnvelope.Session(
                                    new WorldStreamState.Nonce(3, 4), SCOPE, 1)));
            assertFalse(file.freshFor(new WorldStreamEnvelope.Session(NONCE, "f".repeat(64), 1)));
            file.write(ticket(1, 1, 0, 1, 1, 1), pixels(0xff123456));
            assertFalse(file.freshFor(new WorldStreamEnvelope.Session(NONCE, SCOPE, 1)));
        }
    }

    @Test
    void exactFixedMappingRepeatedReadsAndDetachedPixels() throws Exception {
        Path path = directory.resolve("frames");
        WorldFrameFileV2.Frame last;
        try (var writer = WorldFrameFileV2.create(path, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            assertEquals(4_800_256, Files.size(path));
            assertNull(reader.readLatest(0, barrier(1, 20)));
            for (int i = 1; i <= 20; i++) {
                writer.write(ticket(i, i, i, 1, 1, 1), pixels(0xff000000 | i));
                last = reader.readLatest(i - 1, barrier(1, 20));
                assertNotNull(last);
                assertEquals(i, last.metadata().sequence());
                assertEquals(i, last.metadata().content());
                assertEquals(0xff000000 | i, last.pixels().get());
                assertNull(reader.readLatest(i, barrier(1, 20)));
            }
            last = reader.readLatest(0, barrier(1, 20));
        }
        assertNotNull(last);
        assertEquals(0xff000014, last.pixels().get());
        var retained = last;
        assertThrows(ReadOnlyBufferException.class, () -> retained.pixels().put(0, 1));
        Files.delete(path); // Both foreign arenas have unmapped before deletion.
    }

    @Test
    void contentAcknowledgementAndCameraFloorsFilterNewerIneligibleFrames() throws Exception {
        Path path = directory.resolve("ordering");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            file.write(ticket(1, 5, 1, 1, 1, 1), pixels(0xff112233));
            file.write(ticket(2, 4, 2, 1, 1, 1), pixels(0xff445566));
            assertEquals(1, file.readLatest(0, barrier(1, 1)).metadata().sequence());
            assertEquals(2, file.readLatest(1, barrier(1, 2)).metadata().sequence());
            assertEquals(1, file.readLatest(0, barrier(5, 2)).metadata().sequence());
            file.write(ticket(3, 6, 2, 1, 1, 1), pixels(0xff778899));
            assertEquals(3, file.readLatest(1, barrier(5, 2)).metadata().sequence());
            var newerContent = new WorldFrameFileV2.Barrier(1, 1, 1, 1, 100, 2, 2);
            assertEquals(3, file.readLatest(0, newerContent).metadata().sequence());
        }
    }

    @Test
    void resizePolicyRequiresMatchingDimensionsAndFirstCameraRevision() throws Exception {
        Path path = directory.resolve("resize");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            file.write(ticket(1, 5, 1, 1, 1, 1), pixels(0xff112233));
            var resized = new WorldFrameFileV2.Barrier(2, 2, 1, 7, 20, 1, 2);
            assertNull(file.readLatest(0, resized));
            file.write(ticket(2, 6, 2, 2, 2, 1), pixels(0xff112233, 0xff445566));
            assertNull(file.readLatest(0, resized));
            file.write(ticket(3, 7, 2, 2, 2, 1), pixels(0xff778899, 0xffaabbcc));
            assertEquals(3, file.readLatest(0, resized).metadata().sequence());
        }
    }

    private WorldFrameFileV2 verifying(Path path) throws IOException {
        return WorldFrameFileV2.open(path, NONCE, SCOPE, true, true);
    }

    @Test
    void debugVerificationRejectsCorruptPixelsMetadataAndOddStamps() throws Exception {
        Path path = directory.resolve("corruption");
        try (var file = verifying(path)) {
            file.write(ticket(1, 1, 1, 1, 1, 1), pixels(0xff112233));
            overwrite(path, 128, pixels(0xffaabbcc));
            assertNull(file.readLatest(0, barrier(1, 1)));
            file.write(ticket(2, 2, 1, 1, 1, 1), pixels(0xff445566));
            long slot = 64L + WorldFrameFileV2.SLOT_BYTES;
            overwrite(
                    path,
                    slot + 24,
                    ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(3)
                            .flip()); // Valid camera range, but CRC binds the original camera.
            assertNull(file.readLatest(1, barrier(1, 1)));
            file.write(ticket(3, 3, 1, 1, 1, 1), pixels(0xff778899));
            long third = 64L + 2L * WorldFrameFileV2.SLOT_BYTES;
            overwrite(
                    path,
                    third,
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(7).flip());
            assertNull(file.readLatest(2, barrier(1, 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.write(ticket(3, 3, 1, 1, 1, 1), pixels(0xff778899)));
        }
    }

    @Test
    void debugVerificationRejectsNonopaquePixelsOnBothSides() throws Exception {
        Path path = directory.resolve("alpha");
        try (var writer = verifying(path);
                var plain = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.write(ticket(1, 1, 1, 1, 1, 1), pixels(0x7f112233)));
            // The default writer neither scans alpha nor checksums.
            plain.write(ticket(1, 1, 1, 1, 1, 1), pixels(0x7f112233));
            assertEquals(0x7f112233, plain.readLatest(0, barrier(1, 1)).pixels().get(0));
            // A verifying reader skips the CRC of an unchecksummed slot, so modes can mix.
            assertEquals(1, writer.readLatest(0, barrier(1, 1)).metadata().sequence());
            writer.write(ticket(2, 2, 1, 1, 1, 1), pixels(0xff112233));
            overwrite(path, 64L + WorldFrameFileV2.SLOT_BYTES + 64, pixels(0x7f112233));
            assertNull(writer.readLatest(1, barrier(1, 1)), "checksummed slot alpha changed");
        }
    }

    @Test
    void tornReadsAreRejectedByTheStampWithoutCrc() throws Exception {
        Path path = directory.resolve("torn");
        try (var writer = WorldFrameFileV2.open(path, NONCE, SCOPE, true, false);
                var reader = WorldFrameFileV2.open(path, NONCE, SCOPE, false, false)) {
            writer.write(ticket(1, 1, 1, 1, 1, 1), pixels(0xff000001));
            // The writer republishes slot 0 (sequence 4) while the reader copies sequence 1.
            reader.afterCopy =
                    () -> {
                        try {
                            for (int sequence = 2; sequence <= 4; sequence++) {
                                writer.write(
                                        ticket(sequence, sequence, 1, 1, 1, 1),
                                        pixels(0xff000000 | sequence));
                            }
                        } catch (IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    };
            assertNull(reader.readLatest(0, barrier(1, 1)), "torn copy of a republished slot");
            reader.afterCopy = null;
            assertEquals(4, reader.readLatest(0, barrier(1, 1)).metadata().sequence());
            // A slot whose stamp is odd (write in progress) is never a candidate.
            overwrite(
                    path,
                    64L + WorldFrameFileV2.SLOT_BYTES,
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(5).flip());
            assertNull(reader.readLatest(4, barrier(1, 1)));
            // The newest slot's stamp turns odd mid-copy: that read is rejected, its array goes
            // back to the pool, and the older unseen frame is returned instead.
            long allocations = reader.pool().allocations();
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            reader.afterCopy =
                    () -> {
                        if (once.compareAndSet(false, true)) {
                            try {
                                overwrite(
                                        path,
                                        64L,
                                        ByteBuffer.allocate(8)
                                                .order(ByteOrder.LITTLE_ENDIAN)
                                                .putLong(9)
                                                .flip());
                            } catch (IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                        }
                    };
            var fallback = reader.readLatest(2, barrier(1, 1));
            assertEquals(3, fallback.metadata().sequence());
            assertEquals(0xff000003, fallback.pixels().get(0));
            assertEquals(allocations + 1, reader.pool().allocations(), "rejected array reused");
        }
    }

    @Test
    void releasedFrameArraysAreReusedAndLiveFramesNeverShareOne() throws Exception {
        Path path = directory.resolve("pool");
        try (var writer = WorldFrameFileV2.create(path, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            var bounds = barrier(1, 1000);
            // Steady state: each frame released before the next read reuses one array.
            writer.write(ticket(1, 1, 1, 1, 1, 1), pixels(0xff000001));
            var first = reader.readLatest(0, bounds);
            int[] array = first.array();
            first.release();
            for (int sequence = 2; sequence <= 50; sequence++) {
                writer.write(ticket(sequence, sequence, 1, 1, 1, 1), pixels(0xff000000 | sequence));
                var frame = reader.readLatest(sequence - 1, bounds);
                assertTrue(frame.array() == array, "steady state reuses the array");
                assertEquals(0xff000000 | sequence, frame.pixels().get(0));
                frame.release();
            }
            assertEquals(1, reader.pool().allocations(), "no per-frame allocation");
            // Three live owners (read, transport latest, presentation) get distinct arrays.
            var live = new java.util.ArrayList<WorldFrameFileV2.Frame>();
            for (int sequence = 51; sequence <= 53; sequence++) {
                writer.write(ticket(sequence, sequence, 1, 1, 1, 1), pixels(0xff000000 | sequence));
                live.add(reader.readLatest(sequence - 1, bounds));
            }
            assertEquals(3, live.stream().map(WorldFrameFileV2.Frame::array).distinct().count());
            for (int i = 0; i < 3; i++) {
                assertEquals(
                        0xff000033 + i, live.get(i).pixels().get(0), "unreleased stays intact");
            }
            // Released pixels are unreadable; release is idempotent and the pool stays bounded.
            var released = live.getFirst();
            released.release();
            released.release();
            assertThrows(IllegalStateException.class, released::pixels);
            for (var frame : live) {
                frame.release();
            }
            assertEquals(WorldFrameFileV2.SLOTS, reader.pool().retained());
            reader.pool().recycle(new int[WorldFrameFileV2.WIDTH * WorldFrameFileV2.HEIGHT]);
            assertEquals(WorldFrameFileV2.SLOTS, reader.pool().retained());
            // A frame smaller than the array exposes exactly its own pixels.
            writer.write(ticket(54, 54, 1, 2, 2, 1), pixels(0xff0000aa, 0xff0000bb));
            var small = reader.readLatest(53, new WorldFrameFileV2.Barrier(2, 2, 1, 1, 100, 0, 1));
            assertEquals(2, small.pixels().remaining());
            assertEquals(0, small.pixels().position());
            assertEquals(0xff0000bb, small.pixels().get(1));
        }
        // Releasing after the mapping closed is harmless.
    }

    @Test
    void wrongSessionWrongLengthAndV1MappingsAreRejected() throws Exception {
        Path path = directory.resolve("scope");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            assertThrows(
                    IOException.class,
                    () -> WorldFrameFileV2.open(path, new WorldStreamState.Nonce(17, 30), SCOPE));
            assertThrows(
                    IOException.class, () -> WorldFrameFileV2.open(path, NONCE, "b".repeat(64)));
            assertThrows(IOException.class, () -> GpuFrameFile.open(path));
            assertThrows(IOException.class, () -> WorldFrameFileV2.create(path, NONCE, SCOPE));
            overwrite(path, 32, ByteBuffer.wrap(new byte[] {0}));
            assertThrows(IOException.class, () -> file.readLatest(0, barrier(1, 1)));
        }
        Path legacy = directory.resolve("v1");
        try (var old = GpuFrameFile.create(legacy, 1, 1)) {
            assertEquals(1, old.maxWidth());
            assertThrows(IOException.class, () -> WorldFrameFileV2.open(legacy, NONCE, SCOPE));
        }
        Path shortFile = directory.resolve("short");
        Files.write(shortFile, new byte[64]);
        assertThrows(IOException.class, () -> WorldFrameFileV2.open(shortFile, NONCE, SCOPE));
    }

    @Test
    void maximumFrameAndSequenceRespectCapsAndPreserveInputPosition() throws Exception {
        Path path = directory.resolve("maximum");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            var bgra =
                    ByteBuffer.allocate(WorldFrameFileV2.PAYLOAD_BYTES + 4)
                            .order(ByteOrder.LITTLE_ENDIAN);
            bgra.putInt(0);
            for (int i = 0; i < WorldFrameFileV2.WIDTH * WorldFrameFileV2.HEIGHT; i++) {
                bgra.putInt(0xff123456);
            }
            bgra.flip().position(4);
            long sequence = WorldFrameFileV2.MAX_SEQUENCE;
            file.write(ticket(sequence, 1, 0, 1, 800, 500), bgra);
            assertEquals(4, bgra.position());
            var bounds = new WorldFrameFileV2.Barrier(1, 800, 500, 1, 1, 0, 0);
            var frame = file.readLatest(0, bounds);
            assertNotNull(frame);
            assertEquals(sequence, frame.metadata().sequence());
            assertEquals(400000, frame.pixels().remaining());
            assertEquals(0xff123456, frame.pixels().get(399999));
            assertNull(file.readLatest(sequence, bounds));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.write(ticket(1, 1, 0, 1, 1, 1), pixels(0xff123456)));
        }
    }

    @Test
    void independentProducerAndConsumerNeverAcceptMixedPixelsOrRevisionMetadata() throws Exception {
        Path path = directory.resolve("concurrent");
        int frames = 128;
        try (var writer = WorldFrameFileV2.create(path, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            var producing =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> {
                                try {
                                    var buffer =
                                            ByteBuffer.allocate(16 * 16 * 4)
                                                    .order(ByteOrder.LITTLE_ENDIAN);
                                    for (int sequence = 1; sequence <= frames; sequence++) {
                                        buffer.clear();
                                        for (int i = 0; i < 256; i++) {
                                            buffer.putInt(0xff000000 | sequence);
                                        }
                                        buffer.flip();
                                        writer.write(
                                                ticket(sequence, sequence, sequence, 1, 16, 16),
                                                buffer);
                                    }
                                } catch (IOException error) {
                                    throw new java.io.UncheckedIOException(error);
                                }
                            });
            var bounds = new WorldFrameFileV2.Barrier(1, 16, 16, 1, frames, 0, frames);
            long last = 0;
            while (last < frames) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Concurrent frame test interrupted");
                }
                if (producing.isCompletedExceptionally()) {
                    producing.join();
                }
                var frame = reader.readLatest(last, bounds);
                if (frame == null) {
                    Thread.yield();
                    continue;
                }
                last = frame.metadata().sequence();
                assertEquals(last, frame.metadata().camera());
                assertEquals(last, frame.metadata().content());
                var data = frame.pixels();
                while (data.hasRemaining()) {
                    assertEquals(0xff000000 | (int) last, data.get());
                }
            }
            producing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(frames, last);
        }
    }

    @Test
    void invalidPublicationsLeaveTheLastGoodFrameAndClosedReadsFail() throws Exception {
        Path path = directory.resolve("invalid");
        var file = WorldFrameFileV2.create(path, NONCE, SCOPE);
        try (file) {
            file.write(ticket(1, 1, 1, 1, 1, 1), pixels(0xff112233));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.write(ticket(2, 2, 1, 1, 801, 1), pixels(0xff112233)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.write(ticket(2, 2, -1, 1, 1, 1), pixels(0xff112233)));
            // Nonopaque pixels are refused only in debug verification (see the alpha test).
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.write(ticket(2, 2, 1, 1, 1, 1), pixels(0xff112233, 0xff445566)));
            assertEquals(1, file.readLatest(0, barrier(1, 1)).metadata().sequence());
        }
        assertThrows(IllegalStateException.class, () -> file.readLatest(0, barrier(1, 1)));
        file.close();
    }
}
