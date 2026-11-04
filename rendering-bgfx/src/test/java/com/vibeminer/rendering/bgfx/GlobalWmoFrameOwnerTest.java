package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static final class FaultDriver implements GlobalWmoFrameOwner.Driver {
        long now;
        int frees;
        Error frameError, freeError;

        public int frame() {
            if (frameError != null) {
                throw frameError;
            }
            return 0;
        }

        public int read(short texture, ByteBuffer bytes) {
            throw new AssertionError("Unexpected read");
        }

        public long nanoTime() {
            now += 3_000_000_000L;
            return now;
        }

        public void free(ByteBuffer bytes) {
            if (++frees == 1 && freeError != null) {
                throw freeError;
            }
            org.lwjgl.system.MemoryUtil.memFree(bytes);
        }
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object firstSlot(GlobalWmoFrameOwner owner) throws Exception {
        var field = owner.getClass().getDeclaredField("slots");
        field.setAccessible(true);
        return ((Object[]) field.get(owner))[0];
    }

    @Test
    void heldReadbackTimeoutRetainsPointersUntilSeparateSuccessfulShutdown() throws Exception {
        try (var mapping = GpuFrameFile.create(directory.resolve("held"), 800, 500)) {
            var driver = new FaultDriver();
            var owner = GlobalWmoFrameOwner.allocate(mapping, driver);
            var pixels = org.lwjgl.system.MemoryUtil.memAlloc(1_600_000);
            var slot = firstSlot(owner);
            field(slot, "pixels", pixels);
            field(
                    slot,
                    "request",
                    new GlobalWmoFrameRequest(1, new GlobalWmoCamera(0, 0, 0, 0, 0), 317, 163));
            field(slot, "ready", 100);
            field(slot, "fenceKnown", true);
            var failure = assertThrows(IllegalStateException.class, owner::close);
            assertSame(failure, assertThrows(IllegalStateException.class, owner::close));
            assertEquals(1_600_000, owner.stats().directBytes());
            assertEquals(0, driver.frees);
            // A failed enclosing shutdown MUST withhold afterContextShutdown: no timeout frees.
            assertThrows(IllegalStateException.class, owner::advance);
            assertEquals(0, driver.frees);
            // Explicit pure-fixture successful-shutdown boundary; no actual DMA exists here.
            owner.afterContextShutdown();
            assertEquals(1, driver.frees);
            assertEquals(0, owner.stats().directBytes());
        }
    }

    @Test
    void frameErrorIsStickyAndUnknownFreeAttemptsEveryDestinationOnce() throws Exception {
        try (var mapping = GpuFrameFile.create(directory.resolve("fault"), 800, 500)) {
            var driver = new FaultDriver();
            driver.frameError = new AssertionError("Authored frame failure");
            var owner = GlobalWmoFrameOwner.allocate(mapping, driver);
            field(owner, "initialized", true);
            assertSame(driver.frameError, assertThrows(AssertionError.class, owner::advance));
            assertSame(
                    driver.frameError,
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    owner.submit(
                                            new GlobalWmoFrameRequest(
                                                    1,
                                                    new GlobalWmoCamera(0, 0, 0, 0, 0),
                                                    800,
                                                    500))));
            var leaked = org.lwjgl.system.MemoryUtil.memAlloc(1_600_000);
            field(firstSlot(owner), "pixels", leaked);
            field(owner, "packed", org.lwjgl.system.MemoryUtil.memAlloc(1_600_000));
            assertSame(driver.frameError, assertThrows(AssertionError.class, owner::close));
            driver.freeError = new AssertionError("Authored free failure");
            try {
                assertSame(
                        driver.freeError,
                        assertThrows(AssertionError.class, owner::afterContextShutdown));
                assertEquals(2, driver.frees);
                assertEquals(1_600_000, owner.stats().directBytes());
                assertSame(
                        driver.freeError,
                        assertThrows(AssertionError.class, owner::afterContextShutdown));
                assertEquals(2, driver.frees);
            } finally {
                // Fault seam threw BEFORE this one actual memFree; reclaim test-only allocation.
                org.lwjgl.system.MemoryUtil.memFree(leaked);
            }
        }
    }
}
