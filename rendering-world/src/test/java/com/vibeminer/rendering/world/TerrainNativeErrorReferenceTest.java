package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainNativeErrorReferenceTest {
    private static TerrainErrorReference oracle() {
        return new TerrainErrorReference(20_000_000, () -> false);
    }

    private static TerrainErrorReference.Vertex v(int x, int y) {
        return new TerrainErrorReference.Vertex(x, y, x);
    }

    private static TerrainErrorReference.Triangle t(
            TerrainErrorReference.Vertex a,
            TerrainErrorReference.Vertex b,
            TerrainErrorReference.Vertex c) {
        return new TerrainErrorReference.Triangle(a, b, c);
    }

    private static List<TerrainErrorReference.Triangle> fine() {
        var triangles = new ArrayList<TerrainErrorReference.Triangle>();
        for (int x = 0; x < 4; x += 2) {
            for (int y = 0; y < 4; y += 2) {
                var p =
                        new TerrainErrorReference.Vertex[] {
                            v(x, y), v(x + 2, y), v(x + 2, y + 2), v(x, y + 2)
                        };
                for (int i = 0; i < 4; i++) {
                    triangles.add(t(p[i], p[(i + 1) % 4], v(x + 1, y + 1)));
                }
            }
        }
        return triangles;
    }

    private static List<TerrainErrorReference.Triangle> coarse() {
        return List.of(
                t(v(0, 0), v(4, 0), v(2, 2)),
                t(v(4, 0), v(4, 4), v(2, 2)),
                t(v(4, 4), v(0, 4), v(2, 2)),
                t(v(0, 4), v(0, 0), v(2, 2)));
    }

    private static TerrainErrorReference.NativeVertex nativeVertex(
            TerrainErrorReference.Vertex v, float origin, boolean nativePlane) {
        // Exactly the legacy staggered source arithmetic, including float quantization.
        float x = (float) (origin - v.x2() * (WorldFormats.TILE_SIZE / 256));
        float y =
                (float)
                        (origin
                                - (v.y2() / 2) * (WorldFormats.TILE_SIZE / 128)
                                - (v.x2() % 2) * (WorldFormats.TILE_SIZE / 256));
        return new TerrainErrorReference.NativeVertex(x, y, nativePlane ? x - origin : v.z());
    }

    private static List<TerrainErrorReference.NativeTriangle> nativeTriangles(
            List<TerrainErrorReference.Triangle> source, float origin, boolean plane) {
        return source.stream()
                .map(
                        t ->
                                new TerrainErrorReference.NativeTriangle(
                                        nativeVertex(t.a(), origin, plane),
                                        nativeVertex(t.b(), origin, plane),
                                        nativeVertex(t.c(), origin, plane)))
                .toList();
    }

    @Test
    void normalizedPlaneZeroDoesNotCertifyTheQuantizedNativeSurface() {
        assertEquals(0, oracle().compare(fine(), coarse()).upperBound());
        var nativeFine = nativeTriangles(fine(), 1_000_000, false);
        var nativeCoarse = nativeTriangles(coarse(), 1_000_000, false);
        double error = oracle().compareNative(nativeFine, nativeCoarse).upperBound();
        assertTrue(
                error > 0.01,
                "Native sample quantization is measurable, not an affine-grid identity");
        assertTrue(error < 0.1);
        System.out.println("NATIVE_XY_QUANTIZATION normalizedError=0 nativeUpperBound=" + error);
        assertEquals(error, oracle().compareNative(nativeCoarse, nativeFine).upperBound());
    }

    @Test
    void actualNativePlaneRemainsExactAtOrdinaryAndLargeOrigins() {
        for (float origin : new float[] {0, 10000, 1_000_000}) {
            assertEquals(
                    0,
                    oracle().compareNative(
                                    nativeTriangles(fine(), origin, true),
                                    nativeTriangles(coarse(), origin, true))
                            .upperBound());
        }
    }

    @Test
    void sourceGapAndCollapsedOrUnboundedNativeInputsFailClosedInsteadOfWelding() {
        var a = new TerrainErrorReference.NativeVertex(-33.333332f, 0, 1);
        var b = new TerrainErrorReference.NativeVertex(-32, 0, 1);
        var c = new TerrainErrorReference.NativeVertex(-32, 1, 1);
        var other = new TerrainErrorReference.NativeVertex(-33.333984f, 0, 1);
        var fine = List.of(new TerrainErrorReference.NativeTriangle(a, b, c));
        var shifted = List.of(new TerrainErrorReference.NativeTriangle(other, b, c));
        assertThrows(IllegalArgumentException.class, () -> oracle().compareNative(fine, shifted));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        oracle().compareNative(
                                        List.of(new TerrainErrorReference.NativeTriangle(a, a, a)),
                                        fine));
        var far = new TerrainErrorReference.NativeVertex(100, 0, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        oracle().compareNative(
                                        List.of(
                                                new TerrainErrorReference.NativeTriangle(
                                                        a, b, far)),
                                        fine));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainErrorReference.NativeVertex(Float.NaN, 0, 1));
    }
}
