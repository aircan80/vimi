package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void theTileBoundsHoldTheLargestCarriedSets() {
        // One root's portal tables may use a whole tile's bounds, and the bounds hold the roots and
        // groups a tile reads.
        assertEquals(24, WmoPortalSet.MAX_TILE_SETS);
        assertEquals(256, WmoPortalSet.MAX_TILE_GROUPS);
        assertTrue(WmoPortalSet.MAX_TILE_PORTALS >= 1024);
        assertTrue(WmoPortalSet.MAX_TILE_VERTICES >= 4 * WmoPortalSet.MAX_TILE_PORTALS);
        assertTrue(WmoPortalSet.MAX_TILE_REFS >= 2 * WmoPortalSet.MAX_TILE_PORTALS);
    }
}
