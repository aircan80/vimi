package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.WmoPortalTraversal.Group;
import com.vibeminer.rendering.world.WmoPortalTraversal.Plane;
import com.vibeminer.rendering.world.WmoPortalTraversal.Portal;
import com.vibeminer.rendering.world.WmoPortalTraversal.Ref;
import java.util.List;
import org.junit.jupiter.api.Test;

class WmoPortalSetTest {
    // Room A (x 0..10) and room B (x 10..20), joined by a door in the plane x = 10.
    private static Group room(double minX, int firstRef) {
        return new Group(WmoPortalTraversal.INTERIOR, minX, -5, 0, minX + 10, 5, 5, firstRef, 1);
    }

    private static Portal door() {
        return new Portal(new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2, 10, -1, 2}, 1, 0, 0, -10);
    }

    private static WmoPortalSet twoRooms() {
        return new WmoPortalSet(
                List.of(room(0, 0), room(10, 1)),
                List.of(door()),
                List.of(new Ref(0, 1, -1), new Ref(0, 0, 1)));
    }

    private static List<Plane> lookingAlongX(double x) {
        return List.of(
                new Plane(1, -1, 0, -x),
                new Plane(1, 1, 0, -x),
                new Plane(1, 0, -1, -x + 1),
                new Plane(1, 0, 1, -x - 1));
    }

    @Test
    void aSetCountsItsTablesAndBuildsItsTraversal() {
        var set = twoRooms();
        assertEquals(2, set.groups().size());
        assertEquals(1, set.portals().size());
        assertEquals(4, set.vertexCount());
        assertEquals(2, set.refs().size());
        var t = set.traversal();
        assertEquals(2, t.groupCount());
        var result = t.visible(5, 0, 1, lookingAlongX(5));
        assertTrue(result.inside());
        assertTrue(result.visible(0) && result.visible(1));
    }

    @Test
    void malformedSetsAreRefused() {
        // No groups; a range or reference outside the tables (as the traversal refuses them).
        assertThrows(
                IllegalArgumentException.class,
                () -> new WmoPortalSet(List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0), room(10, 2)),
                                List.of(door()),
                                List.of(new Ref(0, 1, -1), new Ref(0, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0), room(10, 1)),
                                List.of(door()),
                                List.of(new Ref(0, 2, -1), new Ref(0, 0, 1))));
        // Non-finite or inverted boxes, non-finite portals and sides outside a signed 16-bit word.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(
                                        new Group(
                                                WmoPortalTraversal.INTERIOR,
                                                Double.NaN,
                                                0,
                                                0,
                                                1,
                                                1,
                                                1,
                                                0,
                                                0)),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(new Group(0, 2, 0, 0, 1, 1, 1, 0, 0)),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0)),
                                List.of(
                                        new Portal(
                                                new double[] {
                                                    10, -1, 0, 10, 1, 0, 10, 1, Double.NaN
                                                },
                                                1,
                                                0,
                                                0,
                                                -10)),
                                List.of(new Ref(0, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0)),
                                List.of(door()),
                                List.of(new Ref(0, 0, 1 << 15))));
        // Group flags are an unsigned 32-bit word.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(new Group(1L << 32, 0, 0, 0, 1, 1, 1, 0, 0)),
                                List.of(),
                                List.of()));
    }

    @Test
    void valuesPastTheObjectsFileBoundAreRefused() {
        // The objects file refuses any portal value past 1e6, so a set carrying one is refused
        // here, where its root's set is dropped, not later, where the tile's buildings would be.
        assertEquals(1_000_000, WmoPortalSet.MAX_VALUE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(new Group(0, 0, 0, 0, 2e6, 1, 1, 0, 0)),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0)),
                                List.of(
                                        new Portal(
                                                new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2e6},
                                                1,
                                                0,
                                                0,
                                                -10)),
                                List.of(new Ref(0, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(room(0, 0)),
                                List.of(
                                        new Portal(
                                                new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2},
                                                1,
                                                0,
                                                0,
                                                -2e6)),
                                List.of(new Ref(0, 0, 1))));
        // Doubles past float range would become infinite in the file.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoPortalSet(
                                List.of(new Group(0, 0, 0, -1e300, 1, 1, 1, 0, 0)),
                                List.of(),
                                List.of()));
        // Exactly at the bound is accepted.
        new WmoPortalSet(List.of(new Group(0, -1e6, 0, 0, 1e6, 1, 1, 0, 0)), List.of(), List.of());
    }

    @Test
    void theTileBoundsHoldTheLargestCarriedSets() {
        // One root's portal tables may use a whole tile's bounds, and the bounds hold the roots and
        // groups a tile reads.
        assertEquals(24, WmoPortalSet.MAX_TILE_SETS);
        // The tile's own reads, plus every group of one root drawn by group (at most 512).
        assertEquals(256 + 512, WmoPortalSet.MAX_TILE_GROUPS);
        // Stormwind (root 7962574): 289 groups, 319 portals, 626 references.
        assertTrue(WmoPortalSet.MAX_TILE_GROUPS - 256 >= 289);
        assertTrue(WmoPortalSet.MAX_TILE_PORTALS >= 319);
        assertTrue(WmoPortalSet.MAX_TILE_REFS >= 626);
        assertTrue(WmoPortalSet.MAX_TILE_PORTALS >= 1024);
        assertTrue(WmoPortalSet.MAX_TILE_VERTICES >= 4 * WmoPortalSet.MAX_TILE_PORTALS);
        assertTrue(WmoPortalSet.MAX_TILE_REFS >= 2 * WmoPortalSet.MAX_TILE_PORTALS);
    }

    /** A reference from the front (+1) or back (-1) of its portal. */
    private static Ref ref(int portal, int group, int side) {
        return new Ref(portal, group, side);
    }

    @Test
    void referenceRangesAreRecoveredFromPairedPortals() {
        // Group 0 leads through portals 0 and 1 to groups 1 and 2; each refers back from the
        // other side. Group 3 has no portal.
        var refs = List.of(ref(0, 1, 1), ref(1, 2, 1), ref(0, 0, -1), ref(1, 0, -1));
        assertArrayEquals(new int[] {0, 2, 2, 1, 3, 1, 0, 0}, WmoPortalSet.refRanges(refs, 4));
        assertArrayEquals(new int[] {0, 0}, WmoPortalSet.refRanges(List.of(), 1));
    }

    @Test
    void oneSidedReferencesJoinTheSpanTheyLieInOrBothBesideThem() {
        // Portal 5 is referenced once, from inside group 0's span: group 0's, as MOGP says.
        var inside =
                List.of(ref(0, 1, 1), ref(5, 2, 1), ref(1, 2, 1), ref(0, 0, -1), ref(1, 0, -1));
        assertArrayEquals(new int[] {0, 3, 3, 1, 4, 1}, WmoPortalSet.refRanges(inside, 3));
        // Portal 5 at the end of group 0's span, before group 1's: given to both, so the walk
        // never misses it from the group it leads from.
        var between =
                List.of(ref(0, 1, 1), ref(1, 2, 1), ref(5, 2, 1), ref(0, 0, -1), ref(1, 0, -1));
        assertArrayEquals(new int[] {0, 3, 2, 2, 4, 1}, WmoPortalSet.refRanges(between, 3));
        // At either end of MOPR it joins the first or last span, and the last one also goes to
        // group 2, which has no span and comes after group 1.
        var ends = List.of(ref(5, 2, 1), ref(0, 1, 1), ref(0, 0, -1), ref(6, 2, 1));
        assertArrayEquals(new int[] {0, 2, 2, 2, 3, 1}, WmoPortalSet.refRanges(ends, 3));
    }

    @Test
    void aGroupWhoseReferencesAreAllOneSidedStillGetsThem() {
        // MOPR lists groups 0, 1 and 2; group 1's only reference (portal 7, to group 3) is
        // one-sided. Group 1 has no span, but lies between groups 0 and 2, so it gets the
        // reference too: with the camera in group 1, group 3 behind portal 7 is not hidden.
        var refs = List.of(ref(0, 2, 1), ref(7, 3, -1), ref(0, 0, -1));
        int[] ranges = WmoPortalSet.refRanges(refs, 4);
        assertArrayEquals(new int[] {0, 2, 1, 1, 1, 2, 0, 0}, ranges);
        var groups = new java.util.ArrayList<Group>();
        for (int g = 0; g < 4; g++) {
            groups.add(
                    new Group(
                            WmoPortalTraversal.INTERIOR,
                            g * 10,
                            -5,
                            0,
                            g * 10 + 10,
                            5,
                            5,
                            ranges[g * 2],
                            ranges[g * 2 + 1]));
        }
        var door =
                new Portal(new double[] {20, -1, 0, 20, 1, 0, 20, 1, 2, 20, -1, 2}, 1, 0, 0, -20);
        var walk =
                new WmoPortalSet(
                                groups,
                                List.of(door, door, door, door, door, door, door, door),
                                refs)
                        .traversal();
        assertTrue(walk.visible(15, 0, 1, List.of()).visible(3));
        // When the spans are not in group order, every group without a span gets every
        // one-sided reference.
        var unordered = List.of(ref(0, 0, -1), ref(7, 3, -1), ref(0, 2, 1));
        assertArrayEquals(new int[] {1, 2, 1, 1, 0, 2, 1, 1}, WmoPortalSet.refRanges(unordered, 4));
    }

    @Test
    void onlyReferencesFromOppositeSidesOfTwoGroupsArePaired() {
        // Portal 0 is referenced twice from group 0, both to group 1: not a pair, so both are
        // left unplaced and join group 0's span (they lie before it).
        var twice = List.of(ref(0, 1, 1), ref(0, 1, 1), ref(1, 1, 1), ref(1, 0, -1));
        assertArrayEquals(new int[] {0, 3, 3, 1}, WmoPortalSet.refRanges(twice, 2));
        // Two groups on the same side of a portal are not a pair either.
        assertNull(WmoPortalSet.refRanges(List.of(ref(0, 1, 1), ref(0, 0, 1)), 2));
    }

    @Test
    void unrecoverableReferenceRangesAreRefused() {
        // Group 0's references are split by group 1's: MOPR is not listed group by group.
        var interleaved =
                List.of(
                        ref(0, 1, 1),
                        ref(1, 0, 1),
                        ref(2, 2, 1),
                        ref(0, 0, -1),
                        ref(1, 1, -1),
                        ref(2, 0, -1));
        assertNull(WmoPortalSet.refRanges(interleaved, 3));
        // Not one portal referenced from both sides.
        assertNull(WmoPortalSet.refRanges(List.of(ref(0, 1, 1), ref(1, 0, 1)), 2));
        assertNull(WmoPortalSet.refRanges(List.of(ref(0, 1, 1), ref(0, 0, -1), ref(0, 1, 1)), 2));
    }

    @Test
    void recoveredRangesWalkLikeTheRecordedOnes() {
        // The two-room set's recorded ranges are what the references give.
        var set = twoRooms();
        int[] ranges = WmoPortalSet.refRanges(set.refs(), 2);
        for (int g = 0; g < 2; g++) {
            assertEquals(set.groups().get(g).firstRef(), ranges[g * 2]);
            assertEquals(set.groups().get(g).refCount(), ranges[g * 2 + 1]);
        }
    }
}
