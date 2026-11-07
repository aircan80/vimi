package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldStageTimingsTest {
    @AfterEach
    void restore() {
        WorldStageTimings.enable(false);
        WorldStageTimings.reset();
    }

    @Test
    void disabledRecordsNothingAndStartIsZero() {
        WorldStageTimings.enable(false);
        assertEquals(0L, WorldStageTimings.start());
        WorldStageTimings.stop("a", WorldStageTimings.start());
        WorldStageTimings.record("b", 5);
        WorldStageTimings.value("c", 7);
        WorldStageTimings.add("d", 3);
        assertTrue(WorldStageTimings.snapshot().series().isEmpty());
    }

    @Test
    void stopIgnoresAStartTakenWhileDisabled() {
        WorldStageTimings.enable(false);
        long started = WorldStageTimings.start();
        WorldStageTimings.enable(true);
        WorldStageTimings.stop("late", started);
        assertNull(WorldStageTimings.snapshot().get("late"));
    }

    @Test
    void enabledStopRecordsAPositiveDuration() {
        WorldStageTimings.enable(true);
        long started = WorldStageTimings.start();
        assertTrue(started != 0L);
        WorldStageTimings.stop("stage", started);
        var series = WorldStageTimings.snapshot().get("stage");
        assertEquals(1, series.count());
        assertTrue(series.sum() >= 0);
        assertEquals(WorldStageTimings.Kind.NANOS, series.kind());
    }

    @Test
    void summarisesCountSumMaxAndPercentiles() {
        WorldStageTimings.enable(true);
        for (int i = 1; i <= 100; i++) {
            WorldStageTimings.value("draws", i);
        }
        var series = WorldStageTimings.snapshot().get("draws");
        assertEquals(WorldStageTimings.Kind.VALUE, series.kind());
        assertEquals(100, series.count());
        assertEquals(5050, series.sum());
        assertEquals(100, series.max());
        assertEquals(50, series.percentile(50));
        assertEquals(95, series.percentile(95));
        assertEquals(100, series.percentile(100));
        assertEquals(50.5, series.mean(), 1e-9);
    }

    @Test
    void countersAccumulateWithoutSamples() {
        WorldStageTimings.enable(true);
        WorldStageTimings.add("bytes", 10);
        WorldStageTimings.add("bytes", 32);
        var series = WorldStageTimings.snapshot().get("bytes");
        assertEquals(WorldStageTimings.Kind.COUNTER, series.kind());
        assertEquals(2, series.count());
        assertEquals(42, series.sum());
    }

    @Test
    void reservoirStaysBoundedButTotalsStayExact() {
        WorldStageTimings.enable(true);
        int n = WorldStageTimings.RESERVOIR * 3;
        for (int i = 0; i < n; i++) {
            WorldStageTimings.record("many", 1000);
        }
        var series = WorldStageTimings.snapshot().get("many");
        assertEquals(n, series.count());
        assertEquals(1000L * n, series.sum());
        assertEquals(WorldStageTimings.RESERVOIR, series.samples().length);
        assertEquals(1000, series.percentile(50));
    }

    @Test
    void negativeDurationsClampToZero() {
        WorldStageTimings.enable(true);
        WorldStageTimings.record("clock", -5);
        assertEquals(0, WorldStageTimings.snapshot().get("clock").max());
    }

    @Test
    void resetClearsEverything() {
        WorldStageTimings.enable(true);
        WorldStageTimings.record("x", 1);
        WorldStageTimings.reset();
        assertTrue(WorldStageTimings.snapshot().series().isEmpty());
    }

    @Test
    void concurrentRecordingKeepsExactTotals() throws Exception {
        WorldStageTimings.enable(true);
        var pool = Executors.newFixedThreadPool(4);
        var done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            pool.execute(
                    () -> {
                        for (int i = 0; i < 10_000; i++) {
                            WorldStageTimings.record("shared", 2);
                        }
                        done.countDown();
                    });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        var series = WorldStageTimings.snapshot().get("shared");
        assertEquals(40_000, series.count());
        assertEquals(80_000, series.sum());
    }

    @Test
    void formatListsSeriesInNameOrderWithUnits() {
        WorldStageTimings.enable(true);
        WorldStageTimings.record("b.stage", 2_000_000);
        WorldStageTimings.value("a.draws", 12);
        WorldStageTimings.add("c.bytes", 4096);
        String text = WorldStageTimings.snapshot().format();
        int a = text.indexOf("a.draws"), b = text.indexOf("b.stage"), c = text.indexOf("c.bytes");
        assertTrue(a >= 0 && a < b && b < c, text);
        assertTrue(text.contains("2.000"), text); // milliseconds for NANOS series
        assertFalse(text.contains("NaN"), text);
    }

    @Test
    void lapChainsRecordEachLegAndStaySilentFromAZeroStamp() {
        WorldStageTimings.enable(true);
        long first = WorldStageTimings.start();
        long second = WorldStageTimings.lap("leg.one", first);
        long third = WorldStageTimings.lap("leg.two", second);
        assertTrue(second != 0L && third != 0L);
        assertEquals(1, WorldStageTimings.snapshot().get("leg.one").count());
        assertEquals(1, WorldStageTimings.snapshot().get("leg.two").count());
        assertEquals(0L, WorldStageTimings.lap("leg.silent", 0L));
        assertNull(WorldStageTimings.snapshot().get("leg.silent"));
        WorldStageTimings.enable(false);
        assertEquals(0L, WorldStageTimings.lap("leg.off", third));
        assertNull(WorldStageTimings.snapshot().get("leg.off"));
    }

    @Test
    void propertyNameIsStable() {
        assertEquals("vimi.world.stageTimings", WorldStageTimings.PROPERTY);
    }
}
