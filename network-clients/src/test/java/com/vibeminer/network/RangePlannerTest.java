package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.RangePlanner.Location;
import com.vibeminer.network.RangePlanner.ObjectToPlan;
import com.vibeminer.network.RangePlanner.Options;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RangePlannerTest {
    private static CdnKey key(int n) {
        return CdnKey.parse(String.format(java.util.Locale.ROOT, "%032x", n));
    }

    private static ObjectToPlan object(int n, long offset, long size) {
        return new ObjectToPlan(key(n), null, List.of(new Location(key(99), offset, size)));
    }

    @Test
    void smallIndexedObjectsAreRangesAndLargeUnindexedObjectsAreLoose() {
        var plan =
                RangePlanner.plan(
                        List.of(
                                object(1, 100, 10),
                                new ObjectToPlan(key(2), 10_000_000L, List.of())));
        assertEquals(1, plan.ranges().size());
        assertEquals(1, plan.loose().size());
        assertEquals(key(2), plan.loose().getFirst().key());
    }

    @Test
    void mergesWithinGapTargetAndCapAndSplitsBackIntoOwnedMembers() {
        var plan =
                RangePlanner.plan(
                        List.of(object(1, 0, 3), object(2, 5, 3), object(3, 20, 3)),
                        List.of(),
                        new Options(8, 10, 3));
        assertEquals(2, plan.ranges().size());
        var range = plan.ranges().getFirst();
        assertEquals(8, range.length());
        assertEquals(2, range.gapBytes());
        var parts = RangePlanner.split(range, new byte[] {1, 2, 3, 9, 9, 4, 5, 6});
        assertArrayEquals(new byte[] {4, 5, 6}, parts.get(1).bytes());
        assertThrows(IllegalArgumentException.class, () -> RangePlanner.split(range, new byte[9]));
    }

    @Test
    void duplicateKeysMergeLocationsButConflictingSizesAreRefusedAlone() {
        var plan =
                RangePlanner.plan(
                        List.of(
                                object(1, 0, 10),
                                object(1, 0, 10),
                                new ObjectToPlan(
                                        key(2), 11L, List.of(new Location(key(99), 20, 10))),
                                new ObjectToPlan(key(3), null, List.of())));
        assertEquals(1, plan.ranges().size());
        assertEquals(1, plan.ranges().getFirst().members().size());
        assertEquals(RangePlanner.SIZE_CONFLICT, plan.rejected().getFirst().code());
        assertEquals(1, plan.loose().size());
    }

    @Test
    void nestedAndChainedOverlapsRefuseEveryIntersectingMember() {
        var plan =
                RangePlanner.plan(
                        List.of(
                                object(1, 0, 100),
                                object(2, 10, 5),
                                object(3, 30, 5),
                                object(4, 100, 5)));
        assertEquals(3, plan.rejected().size());
        assertTrue(plan.rejected().stream().allMatch(r -> r.code().equals(RangePlanner.OVERLAP)));
        assertEquals(key(4), plan.ranges().getFirst().members().getFirst().key());
    }

    @Test
    void archiveRankChoosesLocationThenOrdersRanges() {
        var a =
                new ObjectToPlan(
                        key(1),
                        10L,
                        List.of(new Location(key(8), 50, 10), new Location(key(9), 30, 10)));
        var b = new ObjectToPlan(key(2), 10L, List.of(new Location(key(8), 100, 10)));
        var plan = RangePlanner.plan(List.of(a, b), List.of(key(9), key(8)), new Options());
        assertEquals(
                List.of(key(9), key(8)),
                plan.ranges().stream().map(RangePlanner.Range::archive).toList());
    }

    @Test
    void overflowInvalidLocationsAndOversizedSingletonsAreHandledWithoutWraparound() {
        var plan =
                RangePlanner.plan(
                        List.of(
                                object(1, Long.MAX_VALUE, 10),
                                object(2, -1, 10),
                                object(3, 10, 0),
                                object(4, 0, 100)),
                        List.of(),
                        new Options(8, 16, 0));
        assertEquals(3, plan.rejected().size());
        assertEquals(100, plan.ranges().getFirst().length());
        assertThrows(IllegalArgumentException.class, () -> new Options(0, 1, 0));
    }
}
