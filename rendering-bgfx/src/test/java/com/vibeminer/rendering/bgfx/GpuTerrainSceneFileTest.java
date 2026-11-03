package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GpuTerrainSceneFileTest {
    @TempDir Path scratch;

    private static TerrainScene scene() {
        return new TerrainScene(
                List.of(
                        TerrainScene.Tile.copyOf(
                                7,
                                12,
                                new float[] {100, 200, 30, 104, 200, 30, 100, 204, 35},
                                new int[] {0, 1, 2},
                                1),
                        TerrainScene.Tile.copyOf(6, 12, new float[] {90, 190, 20}, new int[0], 1)));
    }

    @Test
    void preservesSnapshotTileOrderNativeCoordinatesHolesAndBoundsThroughLittleEndianFile()
            throws Exception {
        Path file = scratch.resolve("scene");
        var source = scene();
        GpuTerrainSceneFile.write(file, source);
        var restored = GpuTerrainSceneFile.read(file);
        assertEquals(source.origin(), restored.origin());
        assertEquals(source.payloadBytes(), restored.payloadBytes());
        assertEquals(7, restored.tiles().get(0).x());
        assertEquals(6, restored.tiles().get(1).x());
        assertEquals(0, restored.tiles().get(1).indexCount());
        float[] points = new float[9];
        restored.tiles().get(0).vertices().get(points);
        assertArrayEquals(new float[] {100, 200, 30, 104, 200, 30, 100, 204, 35}, points);
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(1, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8));
        assertEquals(bytes.length, 64 + 2 * 32 + source.payloadBytes());
        assertEquals(11_087_200, GpuTerrainSceneFile.MAX_VALID_BYTES);
    }

    @Test
    void refusesMetadataAndPayloadCorruptionAndTruncation() throws Exception {
        Path original = scratch.resolve("original");
        GpuTerrainSceneFile.write(original, scene());
        byte[] clean = Files.readAllBytes(original);
        for (int offset : new int[] {16, 32, 64, 128}) {
            byte[] altered = clean.clone();
            altered[offset] ^= 1;
            Path file = scratch.resolve("corrupt" + offset);
            Files.write(file, altered);
            assertThrows(IOException.class, () -> GpuTerrainSceneFile.read(file));
        }
        Files.write(scratch.resolve("truncated"), java.util.Arrays.copyOf(clean, clean.length - 1));
        assertThrows(
                IOException.class, () -> GpuTerrainSceneFile.read(scratch.resolve("truncated")));
    }

    @Test
    void
            rejectsOversizedDeclarationsBeforeGeometryAllocationAndRejectsAuthenticatedInvalidGeometry()
                    throws Exception {
        Path file = scratch.resolve("bad");
        GpuTerrainSceneFile.write(file, scene());
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(72, Integer.MAX_VALUE);
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> GpuTerrainSceneFile.read(file));
        GpuTerrainSceneFile.write(scratch.resolve("valid"), scene());
        bytes = Files.readAllBytes(scratch.resolve("valid"));
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(128, Float.floatToRawIntBits(Float.NaN));
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes, 0, 32);
        digest.update(bytes, 64, bytes.length - 64);
        System.arraycopy(digest.digest(), 0, bytes, 32, 32);
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> GpuTerrainSceneFile.read(file));
        Path large = scratch.resolve("large");
        try (var channel =
                FileChannel.open(large, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(GpuTerrainSceneFile.MAX_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IOException.class, () -> GpuTerrainSceneFile.read(large));
    }

    @Test
    void failedCreateNeverDeletesOrOverwritesAnExistingWinner() throws Exception {
        Path file = scratch.resolve("winner");
        Files.writeString(file, "keep");
        assertThrows(IOException.class, () -> GpuTerrainSceneFile.write(file, scene()));
        assertEquals("keep", Files.readString(file));
    }
}
