package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PackedTerrainPlannerTest {
    private static PackedTerrainPlanner.Limits limits() {
        return new PackedTerrainPlanner.Limits(
                32,
                9,
                9 * TerrainScene.MAX_VERTICES_PER_TILE,
                9 * TerrainScene.MAX_INDICES_PER_TILE);
    }

    private static PackedTerrainPlanner.RingTolerances tolerances() {
        return new PackedTerrainPlanner.RingTolerances(1, 4, 16);
    }

    @Test
    void exactCenterCountsPreserveSourceSamplesAndClosingRetiresBorrowedInputs() {
        var work = new ErrorWork(ErrorWork.MAX_ARITHMETIC, ErrorWork.MAX_PLANES);
        var source =
                CompactTerrainSource.copyOf(
                        TerrainSourceWindowTest.descriptor(
                                32, 32, CompactTerrainSource.Outcome.READABLE),
                        TerrainSourceWindowTest.grid(2, 0),
                        work,
                        () -> false);
        var result =
                PackedTerrainPlanner.plan(
                        TerrainSourceWindowTest.window(source),
                        tolerances(),
                        limits(),
                        work,
                        () -> false);
        assertEquals(PackedTerrainPlanner.Status.READY, result.status());
        var plan = result.plan().orElseThrow();
        assertEquals(37120, plan.counts().vertices());
        assertEquals(196608, plan.counts().indices());
        assertEquals(1, plan.counts().batches());
        var leaves = new AtomicInteger();
        plan.visit(
                (s, c, r, col, size, mask) -> {
                    assertEquals(1, size);
                    assertEquals(0, mask);
                    leaves.incrementAndGet();
                },
                () -> false);
        assertEquals(16384, leaves.get());
        float[] position = new float[3];
        plan.copyPosition(24, 0, 0, position, 0);
        assertEquals(10000, position[0]);
        assertEquals(20000, position[1]);
        assertEquals(5, position[2]);
        plan.close();
        plan.close();
        assertThrows(IllegalStateException.class, plan::counts);
    }

    @Test
    void explicitAbsenceDiffersFromUnknownCoverageAndBudgetNeverPublishesUncheckedPlan() {
        var absent =
                PackedTerrainPlanner.plan(
                        TerrainSourceWindowTest.window(null),
                        tolerances(),
                        limits(),
                        new ErrorWork(ErrorWork.MAX_ARITHMETIC, 1),
                        () -> false);
        assertEquals(PackedTerrainPlanner.Status.EMPTY, absent.status());
        absent.plan().orElseThrow().close();
        var rows = new ArrayList<>(TerrainSourceWindowTest.window(null).sources());
        rows.set(
                24,
                CompactTerrainSource.omitted(
                        TerrainSourceWindowTest.descriptor(
                                32, 32, CompactTerrainSource.Outcome.UNAVAILABLE)));
        var unknown =
                PackedTerrainPlanner.plan(
                        TerrainSourceWindow.of(32, 32, rows),
                        tolerances(),
                        limits(),
                        new ErrorWork(ErrorWork.MAX_ARITHMETIC, 1),
                        () -> false);
        assertEquals(PackedTerrainPlanner.Status.UNAVAILABLE, unknown.status());
        assertTrue(unknown.plan().isEmpty());
        var budget =
                PackedTerrainPlanner.plan(
                        TerrainSourceWindowTest.window(null),
                        tolerances(),
                        limits(),
                        new ErrorWork(1, 1),
                        () -> false);
        assertEquals(PackedTerrainPlanner.Reason.WORK_BUDGET, budget.reason());
        assertTrue(budget.plan().isEmpty());
        assertThrows(
                CancellationException.class,
                () ->
                        PackedTerrainPlanner.plan(
                                TerrainSourceWindowTest.window(null),
                                tolerances(),
                                limits(),
                                new ErrorWork(1, 1),
                                () -> true));
    }
}
