package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TextureComposerTest {
    @Test
    void copyAndStraightAlphaOverPreserveAlphaAndSectionPlacement() {
        var red = new MeshAppearance.Texture(1, 1, new int[] {0xffff0000});
        var blue = new MeshAppearance.Texture(1, 1, new int[] {0x800000ff});
        var result =
                TextureComposer.compose(
                        2,
                        1,
                        List.of(
                                new TextureComposer.Layer(
                                        red, 0, 0, 1, 1, TextureComposer.Blend.COPY),
                                new TextureComposer.Layer(
                                        blue, 0, 0, 2, 1, TextureComposer.Blend.SOURCE_OVER)),
                        () -> false);
        assertEquals(0xff7f0080, result.argb()[0]);
        assertEquals(0x800000ff, result.argb()[1]);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TextureComposer.compose(
                                2,
                                1,
                                List.of(
                                        new TextureComposer.Layer(
                                                red, 2, 0, 1, 1, TextureComposer.Blend.COPY)),
                                () -> false));
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        TextureComposer.compose(
                                2,
                                1,
                                List.of(
                                        new TextureComposer.Layer(
                                                red, 0, 0, 1, 1, TextureComposer.Blend.COPY)),
                                () -> true));
    }
}
