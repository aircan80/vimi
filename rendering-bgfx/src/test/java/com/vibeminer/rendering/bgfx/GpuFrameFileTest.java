package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class GpuFrameFileTest {
    @TempDir Path directory;

    private static ByteBuffer pixels(int... argb) {
        var bytes = ByteBuffer.allocate(argb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel : argb) {
            bytes.putInt(pixel);
        }
        return bytes.flip();
    }

    @Test
    void mappingsExchangeLatestDetachedArgbPixelsAndKeepCallerPositionsIndependent()
            throws Exception {
        Path file = directory.resolve("frames.bin");
        GpuFrameFile.Frame retained;
        try (var parent = GpuFrameFile.create(file, 3, 2);
                var child = GpuFrameFile.open(file)) {
            assertEquals(parent.sessionNonce(), child.sessionNonce());
            assertNotEquals(0, parent.sessionNonce());
            assertNull(parent.readLatest(0));
            var data = pixels(0x10203040, 0xff123456, 0xffabcdef);
            data.position(4);
            child.write(1, 7, 2, 1, data);
            assertEquals(4, data.position());
            retained = parent.readLatest(0);
            assertEquals(1, retained.sequence());
            assertEquals(7, retained.controlRevision());
            assertEquals(2, retained.width());
            assertEquals(1, retained.height());
            assertEquals(0xff123456, retained.pixels().get(0));
            assertEquals(0xffabcdef, retained.pixels().get(1));
            retained.pixels().position(1);
            assertEquals(0, retained.pixels().position());
            assertThrows(ReadOnlyBufferException.class, () -> retained.pixels().put(0, 0));
            assertNull(parent.readLatest(1));
            for (int sequence = 2; sequence <= 8; sequence++) {
                child.write(sequence, sequence + 10, 1, 1, pixels(0xff000000 | sequence));
            }
            assertEquals(8, parent.readLatest(1).sequence());
            assertEquals(0xff000008, parent.readLatest(7).pixels().get(0));
            assertEquals(
                    0xff123456, retained.pixels().get(0), "Published frame owns detached pixels");
        }
        Files.delete(file);
        assertEquals(0xffabcdef, retained.pixels().get(1), "FX data outlives deterministic unmap");
    }

    @Test
    void tornMetadataBadCrcAndInvalidBoundsAreRejectedBeforePublicationIsAccepted()
            throws Exception {
        for (int mutation : new int[] {0, 1, 2, 3, 4}) {
            Path file = directory.resolve("bad-" + mutation);
            try (var parent = GpuFrameFile.create(file, 2, 2);
                    var child = GpuFrameFile.open(file)) {
                child.write(1, 1, 1, 1, pixels(0xff112233));
                long offset;
                ByteBuffer changed;
                if (mutation == 0) {
                    offset = 64;
                    changed =
                            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(3).flip();
                } else if (mutation == 1) {
                    offset = 64 + 64;
                    changed = pixels(0xff445566);
                } else if (mutation == 2) {
                    offset = 64 + 16;
                    changed =
                            ByteBuffer.allocate(4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(Integer.MAX_VALUE)
                                    .flip();
                } else if (mutation == 3) {
                    offset = 64 + 8;
                    changed =
                            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(2).flip();
                } else {
                    offset = 64 + 40;
                    changed =
                            ByteBuffer.allocate(8)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putLong(parent.sessionNonce() ^ 1L)
                                    .flip();
                }
                try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.write(changed, offset);
                }
                assertNull(parent.readLatest(0), "Mutation " + mutation);
            }
        }
    }

    @Test
    void corruptedNewestSlotDoesNotHideAnOlderValidUnseenFrame() throws Exception {
        Path file = directory.resolve("recover.bin");
        try (var parent = GpuFrameFile.create(file, 1, 1);
                var child = GpuFrameFile.open(file)) {
            child.write(1, 1, 1, 1, pixels(0xff000001));
            child.write(2, 2, 1, 1, pixels(0xff000002));
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.write(pixels(0), 64 + 128 + 64);
            }
            assertEquals(1, parent.readLatest(0).sequence());
            assertNull(parent.readLatest(1));
        }
    }

    @Test
    void rejectsInvalidHeaderSessionWriteArgumentsSequencesAndUseAfterClose() throws Exception {
        Path file = directory.resolve("lifetime.bin");
        var bridge = GpuFrameFile.create(file, 2, 2);
        assertThrows(IOException.class, () -> GpuFrameFile.create(file, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> bridge.write(0, 1, 1, 1, pixels(1)));
        assertThrows(IllegalArgumentException.class, () -> bridge.write(1, 0, 1, 1, pixels(1)));
        assertThrows(
                IllegalArgumentException.class, () -> bridge.write(1, 1, 3, 1, pixels(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> bridge.write(1, 1, 1, 1, pixels(1, 2)));
        bridge.write(1, 1, 1, 1, pixels(0xff000001));
        assertThrows(IllegalArgumentException.class, () -> bridge.write(1, 1, 1, 1, pixels(1)));
        assertThrows(IllegalArgumentException.class, () -> bridge.readLatest(-1));
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(
                    ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(bridge.sessionNonce() ^ 1L)
                            .flip(),
                    32);
        }
        assertThrows(IOException.class, () -> bridge.readLatest(0));
        bridge.close();
        bridge.close();
        assertThrows(IllegalStateException.class, () -> bridge.readLatest(0));
        assertThrows(IllegalStateException.class, () -> bridge.write(2, 2, 1, 1, pixels(1)));
        Files.delete(file);
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuFrameFile.create(directory.resolve("too-wide"), 1281, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuFrameFile.create(directory.resolve("too-high"), 1, 961));
        Path small = directory.resolve("small");
        Files.write(small, new byte[10]);
        assertThrows(IOException.class, () -> GpuFrameFile.open(small));
    }

    @Test
    void concurrentOverwriteNeverReturnsMixedPixelGenerations() throws Exception {
        Path file = directory.resolve("concurrent.bin");
        try (var parent = GpuFrameFile.create(file, 64, 64);
                var child = GpuFrameFile.open(file)) {
            var done = new CompletableFuture<Void>();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            for (int sequence = 1; sequence <= 200; sequence++) {
                                var bytes =
                                        ByteBuffer.allocateDirect(64 * 64 * 4)
                                                .order(ByteOrder.LITTLE_ENDIAN);
                                while (bytes.hasRemaining()) {
                                    bytes.putInt(0xff000000 | sequence);
                                }
                                child.write(sequence, sequence, 64, 64, bytes.flip());
                            }
                            done.complete(null);
                        } catch (Throwable failure) {
                            done.completeExceptionally(failure);
                        }
                    });
            long after = 0;
            int observed = 0;
            while (!done.isDone()) {
                var frame = parent.readLatest(after);
                if (frame == null) {
                    Thread.yield();
                    continue;
                }
                assertEquals(frame.sequence(), frame.controlRevision());
                var values = frame.pixels();
                while (values.hasRemaining()) {
                    assertEquals(0xff000000 | (int) frame.sequence(), values.get());
                }
                after = frame.sequence();
                observed++;
            }
            done.get(2, TimeUnit.SECONDS);
            var last = parent.readLatest(0);
            assertEquals(200, last.sequence());
            assertTrue(observed > 0 || last.pixels().get(0) == 0xff0000c8);
        }
    }

    @Test
    void consumerBudgetRejectsOversizedMappingsAndWrongAspectDimensions() throws Exception {
        Path file = directory.resolve("consumer-bounds");
        try (var owner = GpuFrameFile.create(file, 960, 400)) {
            assertEquals(960, owner.maxWidth());
            assertThrows(IOException.class, () -> GpuFrameFile.open(file, 800, 500));
        }
        Path oversized = directory.resolve("consumer-bytes");
        try (var owner = GpuFrameFile.create(oversized, 800, 501)) {
            assertEquals(501, owner.maxHeight());
            assertThrows(IOException.class, () -> GpuFrameFile.open(oversized, 800, 500));
        }
        Path valid = directory.resolve("consumer-valid");
        try (var owner = GpuFrameFile.create(valid, 800, 500);
                var consumer = GpuFrameFile.open(valid, 800, 500)) {
            assertEquals(owner.sessionNonce(), consumer.sessionNonce());
        }
    }
}
