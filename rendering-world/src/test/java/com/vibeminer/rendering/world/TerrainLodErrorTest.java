package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.TerrainGrid;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class TerrainLodErrorTest {
    private static ErrorWork work() {
        return new ErrorWork(100_000, 10_000);
    }

    private static TerrainGrid.Chunk source(float origin, long holes, int seed) {
        float[] h = new float[145];
        var random = new Random(seed);
        for (int i = 0; i < h.length; i++) {
            h[i] = seed == 0 ? 3 : seed == 1 ? i == 20 ? 16 : 0 : random.nextInt(9) - 4;
        }
        return new TerrainGrid.Chunk(
                0,
                0,
                0,
                0,
                origin,
                origin,
                0,
                h,
                holes,
                TerrainGrid.ColorState.ABSENT,
                new byte[0]);
    }

    private static TerrainErrorReference.NativeVertex vertex(TerrainGrid.Chunk source, int index) {
        int r = index / 17, within = index % 17;
        var p = source.position(2 * r + (within >= 9 ? 1 : 0), within >= 9 ? within - 9 : within);
        return new TerrainErrorReference.NativeVertex(p.x(), p.y(), p.z());
    }

    private static TerrainErrorReference.NativeTriangle triangle(
            TerrainGrid.Chunk source, int a, int b, int c) {
        return new TerrainErrorReference.NativeTriangle(
                vertex(source, a), vertex(source, b), vertex(source, c));
    }

    private static double exact(CheckedLeafPlan plan) {
        var source = plan.source();
        var fine = new ArrayList<TerrainErrorReference.NativeTriangle>();
        for (int r = plan.row(); r < plan.row() + plan.size(); r++) {
            for (int c = plan.column(); c < plan.column() + plan.size(); c++) {
                int a = r * 17 + c, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                fine.add(triangle(source, a, m, b));
                fine.add(triangle(source, b, m, e));
                fine.add(triangle(source, e, m, d));
                fine.add(triangle(source, d, m, a));
            }
        }
        var coarse = new ArrayList<TerrainErrorReference.NativeTriangle>();
        for (int i = 0; i < plan.triangleCount(); i++) {
            coarse.add(
                    triangle(
                            source,
                            plan.perimeter(i),
                            plan.center(),
                            plan.perimeter((i + 1) % plan.triangleCount())));
        }
        return new TerrainErrorReference(50_000_000, () -> false)
                .compareNative(fine, coarse)
                .upperBound();
    }

    @Test
    void
            conservativeNativeBoundsDominateIndependentExactClippingAcrossQuantizedOriginsAndStitches() {
        for (float origin : new float[] {0, 10000, 1_000_000}) {
            for (int seed : new int[] {0, 1, 43, 71}) {
                for (int mask : new int[] {0, 5, 15}) {
                    var plan = CheckedLeafPlan.create(source(origin, 0, seed), 0, 0, 2, mask);
                    var work = work();
                    var result = TerrainLodError.evaluate(plan, work, () -> false);
                    assertEquals(TerrainLodError.Kind.BOUNDED, result.kind());
                    double exact = exact(plan);
                    assertTrue(
                            result.upperBound() >= exact,
                            () -> result.upperBound() + " must enclose " + exact);
                    assertTrue(result.certifies(result.upperBound() + 1));
                    assertFalse(result.certifies(0));
                    assertTrue(work.arithmeticUsed() > 0 && work.planesUsed() > 0);
                }
            }
        }
    }

    @Test
    void largerLeafUsesOnlyOriginalSourceSamplesAndExactLegacyPositionBits() {
        var source = source(10000, 0, 22);
        var plan = CheckedLeafPlan.create(source, 0, 0, 4, 15);
        var work = work();
        var result = TerrainLodError.evaluate(plan, work, () -> false);
        assertEquals(TerrainLodError.Kind.BOUNDED, result.kind());
        assertTrue(result.upperBound() >= exact(plan));
        for (int r = 0; r <= 8; r++) {
            for (int c = 0; c < 4 + (r % 2 == 0 ? 1 : 0); c++) {
                int id = (r / 2) * 17 + (r % 2) * 9 + c;
                var expected = source.position(r, c);
                assertEquals(
                        Float.floatToRawIntBits(expected.x()),
                        Float.floatToRawIntBits(work.coordinates[id * 3]));
                assertEquals(
                        Float.floatToRawIntBits(expected.y()),
                        Float.floatToRawIntBits(work.coordinates[id * 3 + 1]));
                assertEquals(
                        Float.floatToRawIntBits(expected.z()),
                        Float.floatToRawIntBits(work.coordinates[id * 3 + 2]));
            }
        }
    }

    @Test
    void fullChunkUsesBoundedReusableScratchAndZeroToleranceNeverMeansApproximateAcceptance() {
        var source = source(1_000_000, 0, 0);
        var plan = CheckedLeafPlan.create(source, 0, 0, 8, 15);
        var work = work();
        var result = TerrainLodError.evaluate(plan, work, () -> false);
        assertEquals(145, plan.sampleCount());
        assertEquals(TerrainLodError.Kind.BOUNDED, result.kind());
        assertTrue(result.upperBound() >= 0 && result.upperBound() < 1e-10);
        assertFalse(result.certifies(0));
        assertTrue(result.certifies(0.01));
        assertEquals(8 + 8 * 145, work.planesUsed());
        float[] sameScratch = work.coordinates;
        TerrainLodError.evaluate(CheckedLeafPlan.create(source, 0, 0, 2, 0), work, () -> false);
        assertTrue(sameScratch == work.coordinates);
    }

    @Test
    void defensiveCoefficientMathRefusesZeroDeterminantsAndNonfiniteIntervals() throws Exception {
        var method =
                TerrainLodError.class.getDeclaredMethod(
                        "coefficients",
                        ErrorWork.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        java.util.function.BooleanSupplier.class);
        method.setAccessible(true);
        var work = work();
        work.coordinates[3] = 1;
        work.coordinates[6] = 2; // Collinear XY, independent determinant oracle=0.
        var zero =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () ->
                                method.invoke(
                                        null,
                                        work,
                                        0,
                                        1,
                                        2,
                                        0,
                                        (java.util.function.BooleanSupplier) () -> false));
        var reason = zero.getCause().getClass().getDeclaredField("reason");
        reason.setAccessible(true);
        assertEquals(TerrainLodError.Reason.DETERMINANT, reason.get(zero.getCause()));
        work.coordinates[3] = Float.NaN;
        var invalid =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () ->
                                method.invoke(
                                        null,
                                        work,
                                        0,
                                        1,
                                        2,
                                        0,
                                        (java.util.function.BooleanSupplier) () -> false));
        assertEquals(TerrainLodError.Reason.NONFINITE, reason.get(invalid.getCause()));
    }

    @Test
    void holesExactBypassAndRefusalDoNotTurnEmptyIntoAZeroFilledCertificate() {
        var source = source(0, 1, 0);
        var empty =
                TerrainLodError.evaluate(
                        CheckedLeafPlan.create(source, 0, 0, 1, 0), work(), () -> false);
        assertEquals(TerrainLodError.Kind.EMPTY_SOURCE, empty.kind());
        assertFalse(empty.certifies(0));
        assertFalse(empty.certifies(10));
        var coarse =
                TerrainLodError.evaluate(
                        CheckedLeafPlan.create(source, 0, 0, 2, 0), work(), () -> false);
        assertEquals(TerrainLodError.Reason.SOURCE_HOLE, coarse.reason());
        assertFalse(coarse.certifies(100));
        var exact =
                TerrainLodError.evaluate(
                        CheckedLeafPlan.create(source, 0, 1, 1, 0), work(), () -> false);
        assertEquals(TerrainLodError.Kind.EXACT_GEOMETRY, exact.kind());
        assertTrue(exact.certifies(0));
        assertThrows(IllegalArgumentException.class, () -> exact.certifies(Double.NaN));
        assertThrows(
                IllegalArgumentException.class, () -> exact.certifies(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> exact.certifies(-1));
    }

    @Test
    void sharedBudgetCancellationAndStructuralGuardsFailClosed() {
        var source = source(0, 0, 3);
        var plan = CheckedLeafPlan.create(source, 0, 0, 2, 0);
        var limited = new ErrorWork(1, 1);
        var result = TerrainLodError.evaluate(plan, limited, () -> false);
        assertEquals(TerrainLodError.Reason.WORK_BUDGET, result.reason());
        assertEquals(1, limited.arithmeticUsed());
        assertEquals(
                TerrainLodError.Kind.UNRESOLVED,
                TerrainLodError.evaluate(plan, limited, () -> false).kind());
        var cancel = work();
        assertThrows(
                CancellationException.class,
                () -> TerrainLodError.evaluate(plan, cancel, () -> true));
        assertEquals(0, cancel.arithmeticUsed());
        assertThrows(
                IllegalArgumentException.class,
                () -> CheckedLeafPlan.create(source, Integer.MAX_VALUE, 0, 2, 0));
        assertThrows(
                IllegalArgumentException.class, () -> CheckedLeafPlan.create(source, 1, 0, 2, 0));
        assertThrows(
                IllegalArgumentException.class, () -> CheckedLeafPlan.create(source, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ErrorWork(100_000_001, 1));
    }
}
