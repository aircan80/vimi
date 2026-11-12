package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Interior WMO vertex light: FixColorVertexAlpha plus the MOHD ambient the shader adds. */
class WmoInteriorLightTest {
    /** MOHD ambColor as stored: B, G, R, A bytes of a little-endian u32. */
    private static long ambient(int r, int g, int b) {
        return 0xff00_0000L | (long) r << 16 | (long) g << 8 | b;
    }

    /** One vertex's MOCV bytes B, G, R, A. */
    private static int[] mocv(int... bgra) {
        return bgra;
    }

    private static int[] colours(int[] mocv, int trans, long ambient, int flags) {
        return WmoInteriorLight.colours(i -> mocv[i], mocv.length / 4, trans, ambient, flags);
    }

    @Test
    void anInteriorVertexIsItsColourWhenTheColourIsOverTheAmbient() {
        // ((100 * 0 >> 6) + 100 - 30) / 2 = 35, light 2 * 35 + 30 = 100.
        assertEquals(35, WmoInteriorLight.fixed(100, 0, 30, false, 0));
        assertEquals(100, WmoInteriorLight.light(35, 30));
        assertArrayEquals(
                new int[] {WorldExteriorObjects.packColour(100, 100, 100)},
                colours(mocv(100, 100, 100, 0), 0, ambient(30, 30, 30), 0));
    }

    @Test
    void anInteriorVertexIsNeverDarkerThanTheAmbient() {
        // A colour under the ambient adjusts to 0: the ambient alone is left.
        assertEquals(0, WmoInteriorLight.fixed(10, 0, 40, false, 0));
        assertArrayEquals(
                new int[] {WorldExteriorObjects.packColour(40, 50, 60)},
                colours(mocv(10, 10, 10, 0), 0, ambient(60, 50, 40), 0));
    }

    @Test
    void theColourAlphaBrightensAnInteriorVertex() {
        // a = 64 adds the colour once more: ((80 * 64 >> 6) + 80 - 20) / 2 = 70, light 160.
        assertEquals(70, WmoInteriorLight.fixed(80, 64, 20, false, 0));
        assertEquals(160, WmoInteriorLight.light(70, 20));
        // The adjusted colour is clamped to 255 and the light kept to 1.0.
        assertEquals(255, WmoInteriorLight.fixed(200, 255, 0, false, 0));
        assertEquals(255, WmoInteriorLight.light(255, 0));
    }

    @Test
    void transitionVerticesLoseTheirAlphaShareOfTheColourOverTheAmbient() {
        // (100 - 20) * (1 - 51 / 255) / 2 = 32, light 2 * 32 + 20 = 84.
        assertEquals(32, WmoInteriorLight.fixed(100, 51, 20, true, 0));
        assertEquals(40, WmoInteriorLight.fixed(100, 0, 20, true, 0));
        assertEquals(0, WmoInteriorLight.fixed(10, 0, 20, true, 0), "floored, never negative");
        int[] two = colours(mocv(100, 100, 100, 51, 100, 100, 100, 51), 1, ambient(20, 20, 20), 0);
        assertEquals(WorldExteriorObjects.packColour(84, 84, 84), two[0], "a transition vertex");
        // ((100 * 51 >> 6) + 100 - 20) / 2 = 79, light 178.
        assertEquals(WorldExteriorObjects.packColour(178, 178, 178), two[1], "an interior one");
    }

    @Test
    void mohdFlag0x8KeepsTheColourAndAddsTheAmbient() {
        int keep = WmoInteriorLight.KEEP_COLOUR;
        assertEquals(22, WmoInteriorLight.fixed(22, 116, 33, false, keep));
        assertEquals(22, WmoInteriorLight.fixed(22, 116, 33, true, keep));
        assertArrayEquals(
                new int[] {WorldExteriorObjects.packColour(77, 77, 77)},
                colours(mocv(22, 22, 22, 116), 0, ambient(33, 33, 33), keep | 0x1));
    }

    @Test
    void aGroupWithoutVertexColoursIsLitByTheAmbient() {
        var colours = WmoInteriorLight.colours(null, 2, 0, ambient(12, 34, 56), 0);
        int expected = WorldExteriorObjects.packColour(56, 34, 12);
        assertArrayEquals(new int[] {expected, expected}, colours);
    }

    @Test
    void eachChannelTakesItsOwnAmbient() {
        // B, G, R bytes 90, 60, 30 against ambient R 40, G 40, B 40: R under it keeps the ambient.
        assertArrayEquals(
                new int[] {WorldExteriorObjects.packColour(90, 60, 40)},
                colours(mocv(90, 60, 30, 0), 0, ambient(40, 40, 40), 0));
    }

    @Test
    void theTransitionVerticesEndAtTheLastTransitionBatchsLastVertex() {
        var batches = List.of(batch(0, 9), batch(10, 19), batch(20, 29));
        assertEquals(0, WorldExteriorObjects.transVertices(batches, 0));
        assertEquals(10, WorldExteriorObjects.transVertices(batches, 1));
        assertEquals(20, WorldExteriorObjects.transVertices(batches, 2));
        assertEquals(0, WorldExteriorObjects.transVertices(batches, 4), "more than recorded");
    }

    private static com.vibeminer.formats.world.WmoFormats.Batch batch(int first, int last) {
        return new com.vibeminer.formats.world.WmoFormats.Batch(
                List.of(0, 0, 0, 0, 0, 0), 0, 3, first, last, 0, 0);
    }
}
