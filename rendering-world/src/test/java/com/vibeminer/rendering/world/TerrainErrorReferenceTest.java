package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class TerrainErrorReferenceTest {
    private static final long WORK = 20_000_000;

    @Test
    void planarFineFansAndStitchedCoarseFanHaveExactlyZeroError() {
        var fine = fine((x, y) -> (float) (3 * x - 2 * y + 17));
        var coarse = coarse((x, y) -> (float) (3 * x - 2 * y + 17), true);
        assertEquals(0, oracle().compare(fine, coarse).upperBound());
    }

    @Test
    void isolatedOriginalInnerSampleAndBoundaryRidgeCannotHideBetweenCoarseCorners() {
        BiFunction<Integer, Integer, Float> peak = (x, y) -> x == 1 && y == 1 ? 3f : 0f;
        assertEquals(3, oracle().compare(fine(peak), coarse(peak, false)).upperBound());
        BiFunction<Integer, Integer, Float> ridge = (x, y) -> x == 2 ? 2f : 0f;
        assertEquals(2, oracle().compare(fine(ridge), coarse(ridge, false)).upperBound());
    }

    @Test
    void saddleMaximumAtTriangleIntersectionIsNotAtAnyOriginalTriangleVertex() {
        var a = vertex(0, 0, 0);
        var b = vertex(2, 0, 0);
        var c = vertex(2, 2, 4);
        var d = vertex(0, 2, 0);
        var first = List.of(triangle(a, b, c), triangle(a, c, d));
        var second = List.of(triangle(a, b, d), triangle(b, c, d));
        assertEquals(2, oracle().compare(first, second).upperBound());
        assertEquals(2, oracle().compare(second, first).upperBound());
        assertEquals(2, oracle().compare(transform(first), transform(second)).upperBound());
    }

    @Test
    void nonBinaryIntersectionMaximumRoundsOutwardInsteadOfRoundingBelowTheBound() {
        var a = vertex(0, 0, 0);
        var b = vertex(1, 0, 0);
        var c = vertex(2, 2, 1);
        var d = vertex(0, 2, 0);
        var first = List.of(triangle(a, b, c), triangle(a, c, d));
        var second = List.of(triangle(a, b, d), triangle(b, c, d));
        assertEquals(Math.nextUp(1.0 / 3), oracle().compare(first, second).upperBound());
    }

    @Test
    void equalAreaHoleAndOverlapCannotCancelAndFilledHoleIsRejectedBothDirections() {
        var fine = fine((x, y) -> 0f);
        var missing = new ArrayList<>(fine);
        missing.removeFirst();
        assertEquals(0, oracle().compare(missing, missing).upperBound());
        assertThrows(IllegalArgumentException.class, () -> oracle().compare(fine, missing));
        assertThrows(IllegalArgumentException.class, () -> oracle().compare(missing, fine));
        var canceledArea = new ArrayList<>(missing);
        canceledArea.add(missing.getFirst());
        assertThrows(IllegalArgumentException.class, () -> oracle().compare(fine, canceledArea));
        assertThrows(IllegalArgumentException.class, () -> oracle().compare(canceledArea, fine));
    }

    @Test
    void workCancellationAndInvalidGeometryFailClosed() {
        var fine = fine((x, y) -> 0f);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainErrorReference(10, () -> false).compare(fine, fine));
        assertThrows(
                CancellationException.class,
                () -> new TerrainErrorReference(WORK, () -> true).compare(fine, fine));
        var zero = vertex(0, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> oracle().compare(List.of(triangle(zero, zero, zero)), fine));
        assertThrows(IllegalArgumentException.class, () -> vertex(0, 0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> vertex(17, 0, 0));
    }

    private static TerrainErrorReference oracle() {
        return new TerrainErrorReference(WORK, () -> false);
    }

    private static TerrainErrorReference.Vertex vertex(int x, int y, float z) {
        return new TerrainErrorReference.Vertex(x, y, z);
    }

    private static TerrainErrorReference.Triangle triangle(
            TerrainErrorReference.Vertex a,
            TerrainErrorReference.Vertex b,
            TerrainErrorReference.Vertex c) {
        return new TerrainErrorReference.Triangle(a, b, c);
    }

    private static List<TerrainErrorReference.Triangle> fine(
            BiFunction<Integer, Integer, Float> height) {
        var result = new ArrayList<TerrainErrorReference.Triangle>();
        for (int x = 0; x < 4; x += 2) {
            for (int y = 0; y < 4; y += 2) {
                var center = vertex(x + 1, y + 1, height.apply(x + 1, y + 1));
                int[][] corners = {{x, y}, {x + 2, y}, {x + 2, y + 2}, {x, y + 2}};
                for (int i = 0; i < 4; i++) {
                    int[] a = corners[i], b = corners[(i + 1) % 4];
                    result.add(
                            triangle(
                                    vertex(a[0], a[1], height.apply(a[0], a[1])),
                                    vertex(b[0], b[1], height.apply(b[0], b[1])),
                                    center));
                }
            }
        }
        return result;
    }

    private static List<TerrainErrorReference.Triangle> coarse(
            BiFunction<Integer, Integer, Float> height, boolean stitched) {
        int[][] boundary =
                stitched
                        ? new int[][] {{0, 0}, {2, 0}, {4, 0}, {4, 4}, {0, 4}}
                        : new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}};
        var result = new ArrayList<TerrainErrorReference.Triangle>();
        var center = vertex(2, 2, height.apply(2, 2));
        for (int i = 0; i < boundary.length; i++) {
            int[] a = boundary[i], b = boundary[(i + 1) % boundary.length];
            result.add(
                    triangle(
                            vertex(a[0], a[1], height.apply(a[0], a[1])),
                            vertex(b[0], b[1], height.apply(b[0], b[1])),
                            center));
        }
        return result;
    }

    private static List<TerrainErrorReference.Triangle> transform(
            List<TerrainErrorReference.Triangle> source) {
        return source.stream()
                .map(t -> triangle(transform(t.a()), transform(t.b()), transform(t.c())))
                .toList();
    }

    private static TerrainErrorReference.Vertex transform(TerrainErrorReference.Vertex v) {
        // Invertible XY reflection/translation and exact shared altitude offset.
        return vertex(8 - v.y2(), v.x2() + 4, v.z() + 128);
    }
}
