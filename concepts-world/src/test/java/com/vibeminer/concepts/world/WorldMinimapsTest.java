package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldMinimapsTest {
    @Test
    void recordedIdOnlyAndTopLeftPixelsAreDetachedAndReceiptBound() {
        var source = new Source();
        var images = new WorldMinimaps(source.files());
        try {
            assertNull(images.image(tile(0), Cancellation.NONE).join().pixels());
            assertEquals(0, source.calls.get());
            var first = images.image(tile(91), Cancellation.NONE).join();
            assertEquals(2, first.width());
            assertEquals(0xffff0000, first.pixels().get(0));
            assertEquals(0xff00ff00, first.pixels().get(1));
            assertEquals(0xff0000ff, first.pixels().get(2));
            assertThrows(java.nio.ReadOnlyBufferException.class, () -> first.pixels().put(0, 0));
            first.pixels().position(3);
            assertEquals(0, first.pixels().position());
            images.image(tile(91), Cancellation.NONE).join();
            assertEquals(1, source.opens.get());
            source.key = "b".repeat(32);
            images.image(tile(91), Cancellation.NONE).join();
            assertEquals(2, source.opens.get());
            assertEquals(2, source.closed.get());
        } finally {
            images.closeAsync().join();
        }
        assertTrue(images.image(tile(91), Cancellation.NONE).isCompletedExceptionally());
    }

    @Test
    void protectedMalformedOversizedAndChangedSourcesRemainUnavailable() {
        for (int mode = 0; mode < 4; mode++) {
            var source = new Source();
            source.mode = mode + 1;
            var images = new WorldMinimaps(source.files());
            try {
                var result = images.image(tile(91), Cancellation.NONE).join();
                assertNull(result.pixels(), "mode " + mode);
                assertNotNull(result.issue());
                assertEquals(source.opens.get(), source.closed.get());
                if (source.mode == 3) {
                    assertEquals(0, source.opens.get());
                }
            } finally {
                images.closeAsync().join();
            }
        }
    }

    @Test
    void single512MipUsesBoundedPixelCenterDownsampleWithoutInventingDecoderMip() {
        var source = new Source();
        source.side = 512;
        source.gradient = true;
        var images = new WorldMinimaps(source.files());
        try {
            var image = images.image(tile(91), Cancellation.NONE).join();
            assertEquals(256, image.width());
            assertEquals(256, image.height());
            assertEquals(0xff010100, image.pixels().get(0));
            assertEquals(0xff030100, image.pixels().get(1));
            assertEquals(0xff010300, image.pixels().get(256));
            assertEquals(0xffffff00, image.pixels().get(256 * 256 - 1));
            assertEquals(1, source.closed.get());
        } finally {
            images.closeAsync().join();
        }
        var tooLarge = new Source();
        tooLarge.side = 1024;
        var limited = new WorldMinimaps(tooLarge.files());
        try {
            assertNull(limited.image(tile(91), Cancellation.NONE).join().pixels());
            assertEquals(1, tooLarge.closed.get());
        } finally {
            limited.closeAsync().join();
        }
    }

    @Test
    void canceledRequestDoesNotOpenSourceOrBecomeUnavailableSuccess() {
        var source = new Source();
        var images = new WorldMinimaps(source.files());
        var token = new Cancellation();
        token.cancel();
        try {
            assertThrows(RuntimeException.class, () -> images.image(tile(91), token).join());
            assertEquals(0, source.opens.get());
        } finally {
            images.closeAsync().join();
        }
    }

    private static WorldFormats.Tile tile(long fid) {
        return new WorldFormats.Tile(17, 23, 0, 0, fid);
    }

    private static final class Source {
        final AtomicInteger calls = new AtomicInteger(),
                opens = new AtomicInteger(),
                closed = new AtomicInteger();
        String key = "a".repeat(32);
        int mode;
        int side = 2;
        boolean gradient;

        BuildFiles files() {
            return (BuildFiles)
                    Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (proxy, method, args) -> {
                                calls.incrementAndGet();
                                assertEquals(FileTypes.FileRef.id(91), args[0]);
                                byte[] data = blp(side, gradient);
                                if (method.getName().equals("stat")
                                        || method.getName().equals("statContent")) {
                                    return CompletableFuture.completedFuture(
                                            new FileTypes.FileStat(
                                                    FileTypes.FileLookupState.FOUND,
                                                    new FileTypes.BuildFile(
                                                            91,
                                                            null,
                                                            FileTypes.FilePathSource.NONE,
                                                            mode == 3
                                                                    ? 17L << 20
                                                                    : (long) data.length,
                                                            mode == 4 && opens.get() > 0
                                                                    ? "c".repeat(32)
                                                                    : key,
                                                            FileTypes.FileFormat.UNKNOWN,
                                                            FileTypes.FileFormatSource.NONE,
                                                            FileTypes.FileStorage.STORED,
                                                            false)));
                                }
                                if (method.getName().equals("openRead")) {
                                    opens.incrementAndGet();
                                    if (mode == 2) {
                                        data[0] = 0;
                                    }
                                    return CompletableFuture.completedFuture(
                                            new FileTypes.FileOpen(
                                                    FileTypes.FileReadState.COMPLETE,
                                                    new FileReadStream() {
                                                        int position;

                                                        public long length() {
                                                            return data.length;
                                                        }

                                                        public long position() {
                                                            return position;
                                                        }

                                                        public void seek(long p) {
                                                            position = (int) p;
                                                        }

                                                        public List<FileTypes.FileHole> holes() {
                                                            return mode == 1
                                                                    ? List.of(
                                                                            new FileTypes.FileHole(
                                                                                    0,
                                                                                    4,
                                                                                    "protected"))
                                                                    : List.of();
                                                        }

                                                        public CompletableFuture<Integer> read(
                                                                ByteBuffer buffer,
                                                                Cancellation token) {
                                                            token.check();
                                                            int n =
                                                                    Math.min(
                                                                            buffer.remaining(),
                                                                            data.length - position);
                                                            buffer.put(data, position, n);
                                                            position += n;
                                                            return CompletableFuture
                                                                    .completedFuture(n);
                                                        }

                                                        public void close() {
                                                            closed.incrementAndGet();
                                                        }
                                                    },
                                                    null,
                                                    null,
                                                    null));
                                }
                                throw new AssertionError(
                                        "Borrowed source must not close: " + method.getName());
                            });
        }
    }

    private static byte[] blp(int side, boolean gradient) {
        var b = ByteBuffer.allocate(148 + side * side * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x32504c42).putInt(1).put((byte) 3).put((byte) 8).put((byte) 0).put((byte) 0);
        b.putInt(side).putInt(side).putInt(148);
        b.position(84);
        b.putInt(side * side * 4);
        b.position(148);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int color =
                        gradient
                                ? 0xff000000 | (x & 255) << 16 | (y & 255) << 8
                                : y < side / 2
                                        ? x < side / 2 ? 0xffff0000 : 0xff00ff00
                                        : x < side / 2 ? 0xff0000ff : 0xffffffff;
                b.putInt(color);
            }
        }
        return b.array();
    }
}
