package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalWmoFrameOwnerTest {
    @TempDir Path directory;

    @Test
    void modularReadinessDoesNotRetireBeforeWrap() {
        assertFalse(GlobalWmoFrameOwner.ready(-1, 1));
        assertFalse(GlobalWmoFrameOwner.ready(0, 1));
        assertTrue(GlobalWmoFrameOwner.ready(1, 1));
        assertTrue(GlobalWmoFrameOwner.ready(0, -1));
        assertTrue(GlobalWmoFrameOwner.ready(1, -2));
    }

    @Test
    void fixedStridePackingKeepsActualRectangleAndBothOrigins() {
        var source = ByteBuffer.allocate(800 * 500 * 4);
        var target = ByteBuffer.allocate(800 * 500 * 4);
        for (int y = 0; y < 500; y++) {
            for (int x = 0; x < 800; x++) {
                source.putInt((y * 800 + x) * 4, y * 1000 + x);
            }
        }
        for (boolean flip : new boolean[] {false, true}) {
            GlobalWmoFrameOwner.pack(source, target, 317, 163, flip);
            assertEquals(317 * 163 * 4, target.remaining());
            for (int y = 0; y < 163; y++) {
                for (int x = 0; x < 317; x++) {
                    assertEquals((flip ? 499 - y : y) * 1000 + x, target.getInt());
                }
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobalWmoFrameOwner.pack(source, target, 801, 1, false));
    }

    @Test
    void shellAllocatesNoDirectPayloadAndCannotFreeBeforeClose() throws Exception {
        try (var mapping = GpuFrameFile.create(directory.resolve("frame"), 800, 500)) {
            var owner = GlobalWmoFrameOwner.allocate(mapping);
            assertEquals(0, owner.stats().directBytes());
            assertEquals(19_200_256, owner.stats().framePayloadBytes());
            assertThrows(IllegalStateException.class, owner::afterContextShutdown);
            owner.close();
            owner.afterContextShutdown();
            owner.afterContextShutdown();
            assertEquals(0, owner.stats().directBytes());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlobalWmoFrameRequest(0, new GlobalWmoCamera(0, 0, 0, 0, 0), 800, 500));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlobalWmoFrameRequest(1, new GlobalWmoCamera(0, 0, 0, 0, 0), 801, 500));
    }
}
