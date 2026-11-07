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
            // Three slots sized to the shared 2560x1600 cap, each with a 64-byte header.
            assertEquals(64L + 3L * (64 + 2560L * 1600 * 4), Files.size(path));
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
    void nonopaquePixelsFailLoudlyInEveryMode() throws Exception {
        Path path = directory.resolve("alpha");
        try (var writer = verifying(path);
                var plain = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            // Both writers refuse: the verifying one scans every pixel, the default one samples.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.write(ticket(1, 1, 1, 1, 1, 1), pixels(0x7f112233)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> plain.write(ticket(1, 1, 1, 1, 1, 1), pixels(0x7f112233)));
            var big = ByteBuffer.allocate(800 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 800; i++) {
                big.putInt(i == 799 ? 0x00112233 : 0xff112233);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> plain.write(ticket(1, 1, 1, 1, 800, 1), big.flip()),
                    "the last pixel is always sampled");
            // A verifying reader skips the CRC of an unchecksummed slot, so modes can mix.
            plain.write(ticket(1, 1, 1, 1, 1, 1), pixels(0xff112233));
            assertEquals(1, writer.readLatest(0, barrier(1, 1)).metadata().sequence());
            // A consistent published slot with nonopaque pixels is a renderer fault: the
            // default reader throws instead of presenting a see-through frame.
            plain.write(ticket(2, 2, 1, 1, 1, 1), pixels(0xff112233));
            overwrite(path, 64L + WorldFrameFileV2.SLOT_BYTES + 64, pixels(0x7f112233));
            var failure =
                    assertThrows(
                            WorldFrameFileV2.NonopaqueFrameException.class,
                            () -> plain.readLatest(1, barrier(1, 1)));
            assertTrue(failure.getMessage().contains("frame 2"));
            assertEquals(1, plain.pool().retained(), "the refused array returns to the pool");
            // The verifying reader drops that unchecksummed slot the same loud way.
            assertThrows(
                    WorldFrameFileV2.NonopaqueFrameException.class,
                    () -> writer.readLatest(1, barrier(1, 1)));
            writer.write(ticket(3, 3, 1, 1, 1, 1), pixels(0xff112233));
            overwrite(path, 64L + 2L * WorldFrameFileV2.SLOT_BYTES + 64, pixels(0x7f112233));
            assertNull(writer.readLatest(2, barrier(1, 1)), "checksummed slot alpha changed");
        }
    }

    @Test
    void poolTakeWithMixedSizesReusesFittingArraysAndDropsTheRest() {
        var pool = new WorldFrameFileV2.Pool();
        int[] small = pool.take(100), large = pool.take(1000);
        assertEquals(2, pool.allocations());
        pool.recycle(large);
        pool.recycle(small); // most recent first: polled, too small, dropped
        // A 600-pixel frame: the large array fits (<= 2x); the small one is dropped on the way.
        assertTrue(pool.take(600) == large, "fitting array reused");
        assertEquals(0, pool.retained(), "too-small array dropped");
        pool.recycle(large);
        // A 300-pixel frame: 1000 is more than twice 300, so it is dropped and a new one made.
        int[] fresh = pool.take(300);
        assertEquals(300, fresh.length);
        assertEquals(3, pool.allocations());
        assertEquals(0, pool.retained(), "oversized array dropped after a shrink");
        pool.recycle(fresh);
        assertTrue(pool.take(150) == fresh, "exactly twice is still reused");
    }

    @Test
    void sampledAlphaStrideCoversAFrameEvenly() {
        assertEquals(1, WorldFrameFileV2.alphaStride(1));
        assertEquals(1, WorldFrameFileV2.alphaStride(64));
        assertEquals(6250, WorldFrameFileV2.alphaStride(800 * 500));
    }

    @Test
    void seqlockStressNeverYieldsMixedPixelsWithPooledArrays() throws Exception {
        Path path = directory.resolve("stress");
        int frames = 3000;
        try (var writer = WorldFrameFileV2.create(path, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(path, NONCE, SCOPE)) {
            var producing =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> {
                                try {
                                    var buffer =
                                            ByteBuffer.allocate(32 * 32 * 4)
                                                    .order(ByteOrder.LITTLE_ENDIAN);
                                    for (int sequence = 1; sequence <= frames; sequence++) {
                                        buffer.clear();
                                        for (int i = 0; i < 1024; i++) {
                                            buffer.putInt(0xff000000 | sequence);
                                        }
                                        writer.write(
                                                ticket(sequence, sequence, sequence, 1, 32, 32),
                                                buffer.flip());
                                    }
                                } catch (IOException error) {
                                    throw new java.io.UncheckedIOException(error);
                                }
                            });
            var bounds = new WorldFrameFileV2.Barrier(1, 32, 32, 1, frames, 0, frames);
            long last = 0;
            int accepted = 0;
            while (last < frames) {
                if (producing.isCompletedExceptionally()) {
                    producing.join();
                }
                var frame = reader.readLatest(last, bounds);
                if (frame == null) {
                    Thread.onSpinWait();
                    continue;
                }
                last = frame.metadata().sequence();
                accepted++;
                var data = frame.pixels();
                while (data.hasRemaining()) {
                    assertEquals(0xff000000 | (int) last, data.get(), "frame " + last);
                }
                frame.release();
            }
            producing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(accepted > 0);
            assertTrue(reader.pool().allocations() <= 1, "released arrays are reused");
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
            file.write(ticket(sequence, 1, 0, 1, 2560, 1600), bgra);
            assertEquals(4, bgra.position());
            var bounds = new WorldFrameFileV2.Barrier(1, 2560, 1600, 1, 1, 0, 0);
            var frame = file.readLatest(0, bounds);
            assertNotNull(frame);
            assertEquals(sequence, frame.metadata().sequence());
            assertEquals(2560 * 1600, frame.pixels().remaining());
            assertEquals(0xff123456, frame.pixels().get(2560 * 1600 - 1));
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

    /** Writes {@code rows} rows of {@code stride} bytes: frame pixels then padding. */
    private static void fillDirect(
            ByteBuffer slot,
            int width,
            int height,
            int stride,
            java.util.function.IntBinaryOperator argb) {
        var rows = slot.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < stride / 4; x++) {
                rows.putInt(y * stride + x * 4, x < width ? argb.applyAsInt(x, y) : 0x00badbad);
            }
        }
    }

    @Test
    void directReadbackPublishesPaddedRowsInPlaceAndReadersGetTightPixels() throws Exception {
        Path path = directory.resolve("direct");
        for (boolean verify : new boolean[] {false, true}) {
            Files.deleteIfExists(path);
            try (var writer = WorldFrameFileV2.open(path, NONCE, SCOPE, true, verify);
                    var reader = WorldFrameFileV2.open(path, NONCE, SCOPE, false, verify)) {
                int width = 3, height = 2, stride = 8 * 4; // a 3x2 frame in an 8-wide target
                var slot = writer.beginDirect(1, stride * height);
                assertNull(reader.readLatest(0, barrier(1, 1)), "an in-progress slot is skipped");
                fillDirect(slot, width, height, stride, (x, y) -> 0xff000000 | (y << 8) | x);
                writer.publishDirect(1, ticket(1, 1, 1, 1, width, height), stride);
                var frame =
                        reader.readLatest(
                                0, new WorldFrameFileV2.Barrier(1, width, height, 1, 1, 1, 1));
                assertNotNull(frame, "verify=" + verify);
                var pixels = frame.pixels();
                assertEquals(width * height, pixels.remaining());
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        assertEquals(0xff000000 | (y << 8) | x, pixels.get(y * width + x));
                    }
                }
                frame.release();
                // Begun again then abandoned: the slot holds no frame.
                writer.beginDirect(1, stride * height);
                writer.abandonDirect(1);
                assertNull(
                        reader.readLatest(
                                0, new WorldFrameFileV2.Barrier(1, width, height, 1, 1, 1, 1)));
            }
        }
    }

    @Test
    void directPublicationKeepsTheWriterChecks() throws Exception {
        Path path = directory.resolve("direct-checks");
        try (var file = WorldFrameFileV2.create(path, NONCE, SCOPE)) {
            // Not begun: a slot that was never marked in progress cannot be published in place.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.publishDirect(0, ticket(1, 1, 1, 1, 1, 1), 4));
            var slot = file.beginDirect(0, 64);
            fillDirect(slot, 2, 2, 16, (x, y) -> 0xff123456);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.publishDirect(0, ticket(1, 1, 1, 1, 2, 2), 4),
                    "stride shorter than a row");
            fillDirect(slot, 2, 2, 16, (x, y) -> x == 1 && y == 1 ? 0x10123456 : 0xff123456);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.publishDirect(0, ticket(1, 1, 1, 1, 2, 2), 16),
                    "the last pixel is always sampled for alpha");
            fillDirect(slot, 2, 2, 16, (x, y) -> 0xff123456);
            file.publishDirect(0, ticket(5, 1, 1, 1, 2, 2), 16);
            file.beginDirect(1, 64);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.publishDirect(1, ticket(5, 1, 1, 1, 2, 2), 16),
                    "sequences still only grow");
            assertThrows(IllegalArgumentException.class, () -> file.beginDirect(3, 64));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> file.beginDirect(0, WorldFrameFileV2.PAYLOAD_BYTES + 1));
        }
    }
}
