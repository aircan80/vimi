package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextureOwnershipTest {
    @Test
    void theConstructorCopiesWhileAdoptTakesTheArrayWithoutCopying() {
        int[] pixels = {1, 2, 3, 4};
        var copied = new MeshAppearance.Texture(2, 2, pixels);
        var adopted = MeshAppearance.Texture.adopt(2, 2, pixels);
        pixels[0] = 9;
        assertEquals(1, copied.argbView().get(0), "the public constructor keeps its own copy");
        assertEquals(9, adopted.argbView().get(0), "adopt keeps the given array (no copy)");
        assertTrue(adopted.argbView().isReadOnly());
        // argb() still hands out a copy, so callers cannot change an adopted texture through it.
        int[] out = adopted.argb();
        out[1] = 7;
        assertArrayEquals(new int[] {9, 2, 3, 4}, adopted.argb());
    }

    @Test
    void adoptChecksTheSameBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshAppearance.Texture.adopt(2, 2, new int[3]));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshAppearance.Texture.adopt(4096, 1, new int[4096]));
        assertThrows(NullPointerException.class, () -> MeshAppearance.Texture.adopt(1, 1, null));
    }
}
