package com.vibeminer.formats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class AssetSourceTest {
    @Test
    void aBorrowedSourceReadsTheCallersArrayWithoutCopyingIt() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        var borrowed = AssetSource.borrowed(bytes);
        var owned = AssetSource.bytes(bytes);
        bytes[1] = 9;
        // The borrowed source sees the caller's array (no copy was taken); the owned one does not.
        assertArrayEquals(new byte[] {1, 9}, borrowed.readAt(0, 2, Cancellation.NONE).get());
        assertArrayEquals(new byte[] {1, 2}, owned.readAt(0, 2, Cancellation.NONE).get());
        assertEquals(4, borrowed.length());
    }

    @Test
    void readsFromABorrowedSourceCannotChangeTheCallersBytes() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        var borrowed = AssetSource.borrowed(bytes);
        byte[] read = borrowed.readAt(1, 2, Cancellation.NONE).get();
        read[0] = 7;
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes, "a read is a fresh array");
        assertArrayEquals(new byte[] {2, 3}, borrowed.readAt(1, 2, Cancellation.NONE).get());
        var outside =
                assertThrows(
                        ExecutionException.class,
                        () -> borrowed.readAt(3, 2, Cancellation.NONE).get());
        assertEquals(IllegalArgumentException.class, outside.getCause().getClass());
        assertThrows(NullPointerException.class, () -> AssetSource.borrowed(null));
    }

    @Test
    void everyLevelDecodesTheSameFromABorrowedSource() {
        byte[] file = blp(16, 16, 5);
        byte[] before = file.clone();
        for (int level = 0; level < 5; level++) {
            var owned =
                    Blp2Decoder.decodeSynchronous(
                            AssetSource.bytes(file),
                            level,
                            new Blp2Types.Limits(),
                            Cancellation.NONE,
                            Blp2Types.MipProfile.STRICT);
            var borrowed =
                    Blp2Decoder.decodeSynchronous(
                            AssetSource.borrowed(file),
                            level,
                            new Blp2Types.Limits(),
                            Cancellation.NONE,
                            Blp2Types.MipProfile.STRICT);
            assertEquals(owned.width(), borrowed.width());
            assertArrayEquals(owned.rgba(), borrowed.rgba(), "level " + level);
        }
        assertArrayEquals(before, file, "decoding never writes the borrowed bytes");
    }

    /** BC1 BLP2 recording {@code levels} levels, each a different colour. */
    private static byte[] blp(int width, int height, int levels) {
        int offset = 148, total = 148;
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            total += ((w + 3) / 4) * ((h + 3) / 4) * 8;
        }
        var b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'B', 'L', 'P', '2'}).putInt(1);
        b.put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 1);
        b.putInt(width).putInt(height);
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            int blocks = ((w + 3) / 4) * ((h + 3) / 4);
            b.putInt(20 + level * 4, offset).putInt(84 + level * 4, blocks * 8);
            for (int i = 0; i < blocks; i++) {
                b.putShort(offset + i * 8, (short) (0x1f << level));
            }
            offset += blocks * 8;
        }
        return b.array();
    }
}
