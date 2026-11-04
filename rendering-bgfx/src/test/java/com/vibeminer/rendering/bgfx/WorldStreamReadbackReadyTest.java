package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldStreamReadbackReadyTest {
    @Test
    void frameWrapDoesNotCompleteFutureReadbackEarly() {
        assertFalse(WorldStreamNativeOwner.readbackReady(0xfffffffe, 1));
        assertFalse(WorldStreamNativeOwner.readbackReady(0xffffffff, 1));
        assertFalse(WorldStreamNativeOwner.readbackReady(0, 1));
        assertTrue(WorldStreamNativeOwner.readbackReady(1, 1));
        assertTrue(WorldStreamNativeOwner.readbackReady(2, 1));
        assertTrue(WorldStreamNativeOwner.readbackReady(0, 0xffffffff));
        assertFalse(WorldStreamNativeOwner.readbackReady(0x7fffffff, 0x80000000));
        assertTrue(WorldStreamNativeOwner.readbackReady(0x80000000, 0x80000000));
        assertTrue(WorldStreamNativeOwner.readbackReady(0x80000001, 0x80000000));
    }
}
