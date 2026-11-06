package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.rendering.world.WorldFrameLimits;
import org.junit.jupiter.api.Test;

class WorldStreamNativeOwnerTargetTest {
    @Test
    void helperTargetGrowsInStepsAndShrinksOnlyBelowHalf() {
        int max = WorldFrameLimits.MAX_WIDTH;
        assertEquals(256, WorldStreamNativeOwner.targetSize(200, 0, max), "first allocation");
        assertEquals(1024, WorldStreamNativeOwner.targetSize(800, 256, max), "grow to a step");
        // A window drag within the allocation never reallocates.
        for (int frame = 513; frame <= 1024; frame += 37) {
            assertEquals(
                    1024, WorldStreamNativeOwner.targetSize(frame, 1024, max), "drag " + frame);
        }
        assertEquals(1280, WorldStreamNativeOwner.targetSize(1025, 1024, max));
        assertEquals(2560, WorldStreamNativeOwner.targetSize(2560, 1024, max), "cap");
        assertEquals(1600, WorldStreamNativeOwner.targetSize(1600, 512, 1600), "height cap");
        // Shrinking below half the allocation gives the memory back.
        assertEquals(512, WorldStreamNativeOwner.targetSize(400, 1024, max));
        assertEquals(1024, WorldStreamNativeOwner.targetSize(512, 1024, max), "exactly half keeps");
    }
}
