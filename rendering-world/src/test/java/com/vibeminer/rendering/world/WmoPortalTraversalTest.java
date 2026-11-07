package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WorldPlacements.Vec3;
import com.vibeminer.rendering.world.WmoPortalTraversal.Group;
import com.vibeminer.rendering.world.WmoPortalTraversal.Plane;
import com.vibeminer.rendering.world.WmoPortalTraversal.Portal;
import com.vibeminer.rendering.world.WmoPortalTraversal.Ref;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WmoPortalTraversalTest {
    // Three rooms along +x, 10 wide, and an exterior yard before the first:
    //   yard E x -10..0 (exterior), room A 0..10, room B 10..20, room C 20..30 (interior).
    // Doors: E|A at x = 0 and A|B at x = 10, both y -1..1, z 0..2; B|C at x = 20, y 4..5, off
    // to the side.
    private static final int A = 0, B = 1, C = 2, E = 3;

    private static Group room(double minX, long flags, int firstRef, int refCount) {
        return new Group(flags, minX, -5, 0, minX + 10, 5, 5, firstRef, refCount);
    }

    /** A door in the plane x = at, normal +x: dot = x - at. */
    private static Portal door(double at, double minY, double maxY) {
        return new Portal(
                new double[] {at, minY, 0, at, maxY, 0, at, maxY, 2, at, minY, 2}, 1, 0, 0, -at);
    }

    /**
     * The client's sign: a group at lower x (dot < 0) references the door with a negative side, the
     * group at higher x with a positive one. {@code flip} inverts every side.
     */
    private static WmoPortalTraversal building(boolean flip) {
        int s = flip ? -1 : 1;
        var portals = List.of(door(10, -1, 1), door(20, 4, 5), door(0, -1, 1));
        var refs =
                List.of(
                        // A: to B through door 0, to E through door 2.
                        new Ref(0, B, -s),
                        new Ref(2, E, s),
                        // B: to A, to C.
                        new Ref(0, A, s),
                        new Ref(1, C, -s),
                        // C: to B.
                        new Ref(1, B, s),
                        // E: to A.
                        new Ref(2, A, -s));
        var groups =
                List.of(
                        room(0, WmoPortalTraversal.INTERIOR, 0, 2),
                        room(10, WmoPortalTraversal.INTERIOR, 2, 2),
                        room(20, WmoPortalTraversal.INTERIOR, 4, 1),
                        room(-10, WmoPortalTraversal.EXTERIOR, 5, 1));
        return WmoPortalTraversal.of(groups, portals, refs);
    }

    /**
     * The four side planes of a 90-degree frustum looking along +x ({@code sign} 1) or -x ({@code
     * sign} -1), without a near plane, as the traversal takes it.
     */
    private static List<Plane> looking(int sign, double x, double y, double z) {
        return List.of(
                new Plane(sign, -1, 0, -sign * x + y),
                new Plane(sign, 1, 0, -sign * x - y),
                new Plane(sign, 0, -1, -sign * x + z),
                new Plane(sign, 0, 1, -sign * x - z));
    }

    private static List<Integer> visible(
            WmoPortalTraversal t, int sign, double x, double y, double z) {
        var result = t.visible(x, y, z, looking(sign, x, y, z));
        var groups = new ArrayList<Integer>();
        result.visible().stream().forEach(groups::add);
        return groups;
    }

    @Test
    void aRoomSeesTheNextRoomOnlyThroughADoorInView() {
        var t = building(false);
        var result = t.visible(5, 0, 1, looking(1, 5, 0, 1));
        assertTrue(result.inside());
        assertTrue(result.visible(A) && result.visible(B));
        // Looking away from the A|B door: A, and the yard through the door behind the camera.
        assertEquals(List.of(A, E), visible(t, -1, 5, 0, 1));
        assertEquals(List.of(A, B), visible(t, 1, 5, 0, 1));
    }

    @Test
    void nestedPortalsNarrowTheFrustum() {
        var t = building(false);
        // From the middle of A, the A|B door (y -1..1 at x = 10) limits the view to |y| <= 3 at
        // x = 20, so the B|C door (y 4..5) is hidden although the camera frustum holds it.
        assertFalse(t.visible(5, 0, 1, looking(1, 5, 0, 1)).visible(C));
        // Right at the door the opening is wide enough to see through both.
        assertTrue(t.visible(9, 0, 1, looking(1, 9, 0, 1)).visible(C));
        // Inside B, C is seen directly.
        assertTrue(t.visible(15, 0, 1, looking(1, 15, 0, 1)).visible(C));
    }

    @Test
    void theSideSignDecidesWhichWayAPortalIsPassable() {
        // With every side inverted the camera is never on its group's side: nothing is entered.
        var flipped = building(true);
        assertEquals(List.of(A), visible(flipped, 1, 5, 0, 1));
        // From B looking back at A, A is visible through the same door the other way.
        assertTrue(building(false).visible(15, 0, 1, looking(-1, 15, 0, 1)).visible(A));
    }

    @Test
    void nearAPortalPlaneItIsPassedWithTheCurrentFrustum() {
        // 0.01 from the A|B door with inverted sides: not facing, but within the epsilon.
        var flipped = building(true);
        assertTrue(flipped.visible(9.99, 0, 1, looking(1, 9.99, 0, 1)).visible(B));
        assertFalse(flipped.visible(9.5, 0, 1, looking(1, 9.5, 0, 1)).visible(B));
    }

    @Test
    void fromOutsideTheWalkStartsAtEveryExteriorGroup() {
        var t = building(false);
        var result = t.visible(-5, 0, 1, looking(1, -5, 0, 1));
        assertFalse(result.inside());
        // Through the yard door into A, and on through the aligned A|B door into B.
        assertTrue(result.visible(E) && result.visible(A) && result.visible(B));
        assertFalse(result.visible(C));
        // Looking away from the building: the yard only.
        assertEquals(List.of(E), visible(t, -1, -5, 0, 1));
    }

    @Test
    void cyclesTerminate() {
        // Two rooms whose doors lead to each other both ways, with the camera on the door plane
        // so every reference is passable.
        var portals = List.of(door(10, -1, 1));
        var refs = List.of(new Ref(0, B, 1), new Ref(0, A, 1));
        var groups =
                List.of(
                        room(0, WmoPortalTraversal.INTERIOR, 0, 1),
                        room(10, WmoPortalTraversal.INTERIOR, 1, 1));
        var t = WmoPortalTraversal.of(groups, portals, refs);
        assertEquals(List.of(A, B), visible(t, 1, 10, 0, 1));
    }

    @Test
    void everyInteriorBoxHoldingTheCameraStartsTheWalk() {
        // An L-shaped room L whose box also covers a small closet K the camera is not in: the
        // client's BSP would say L, the smallest box says K. Both start, so neither the room the
        // camera is in nor what it sees through its door goes hollow.
        var portals = List.of(door(10, -1, 1));
        var groups =
                List.of(
                        new Group(WmoPortalTraversal.INTERIOR, 0, -5, 0, 10, 5, 5, 0, 1),
                        new Group(WmoPortalTraversal.INTERIOR, 4, -1, 0, 6, 1, 2, 1, 0),
                        room(10, WmoPortalTraversal.INTERIOR, 1, 0),
                        new Group(WmoPortalTraversal.EXTERIOR, 0, 0, 0, 5, 5, 5, 1, 0));
        var t = WmoPortalTraversal.of(groups, portals, List.of(new Ref(0, 2, -1)));
        var result = t.visible(5, 0, 1, looking(1, 5, 0, 1));
        assertTrue(result.inside());
        // L, K and the room behind L's door; the exterior group is not a start when inside.
        assertEquals(List.of(0, 1, 2), visible(t, 1, 5, 0, 1));
    }

    @Test
    void aDoorCloserThanTheNearPlaneStillLeadsOn() {
        // 0.3 from the A|B door, inside a 0.5 near distance. The traversal takes the frustum
        // without its near plane, so B stays visible; with the near plane the door would be culled.
        var t = building(false);
        var sides = looking(1, 9.7, 0, 1);
        assertTrue(t.visible(9.7, 0, 1, sides).visible(B));
        var withNear = new ArrayList<>(sides);
        withNear.add(new Plane(1, 0, 0, -(9.7 + TerrainCamera.NEAR)));
        assertFalse(t.visible(9.7, 0, 1, withNear).visible(B));
    }

    @Test
    void aGroupPastItsEntryCapIsWalkedWithTheCallersFrustum() {
        // A room S opens on a hall H through MAX_ENTRIES_PER_GROUP narrow arches at the far left
        // and then one wide arch in the middle; H's door to T is seen only through the wide one.
        int narrow = WmoPortalTraversal.MAX_ENTRIES_PER_GROUP;
        var portals = new ArrayList<Portal>();
        var refs = new ArrayList<Ref>();
        for (int i = 0; i < narrow; i++) {
            portals.add(door(10, -4.9 + 0.1 * i, -4.85 + 0.1 * i));
            refs.add(new Ref(i, 1, -1));
        }
        portals.add(door(10, -1, 1));
        refs.add(new Ref(narrow, 1, -1));
        portals.add(door(20, -1, 1));
        refs.add(new Ref(narrow + 1, 2, -1));
        var groups =
                List.of(
                        room(0, WmoPortalTraversal.INTERIOR, 0, narrow + 1),
                        room(10, WmoPortalTraversal.INTERIOR, narrow + 1, 1),
                        room(20, WmoPortalTraversal.INTERIOR, narrow + 2, 0));
        var t = WmoPortalTraversal.of(groups, portals, refs);
        assertEquals(List.of(0, 1, 2), visible(t, 1, 5, 0, 1));
    }

    @Test
    void aNonConvexPortalKeepsItsWholeOpening() {
        // An L-shaped door at x = 10 (y -3..3 along the floor, rising to z 4 on the left only).
        // Its reflex edges would cut away the lower right; the B|C door seen only through that
        // part (low, at y 0..2) must stay visible.
        var l =
                new Portal(
                        new double[] {
                            10, -3, 0, 10, 3, 0, 10, 3, 1, 10, -1, 1, 10, -1, 4, 10, -3, 4
                        },
                        1,
                        0,
                        0,
                        -10);
        var low = new Portal(new double[] {20, 0, 0, 20, 2, 0, 20, 2, 1, 20, 0, 1}, 1, 0, 0, -20);
        var groups =
                List.of(
                        room(0, WmoPortalTraversal.INTERIOR, 0, 1),
                        room(10, WmoPortalTraversal.INTERIOR, 1, 1),
                        room(20, WmoPortalTraversal.INTERIOR, 2, 0));
        var t =
                WmoPortalTraversal.of(
                        groups, List.of(l, low), List.of(new Ref(0, 1, -1), new Ref(1, 2, -1)));
        assertEquals(List.of(0, 1, 2), visible(t, 1, 5, 0, 0.5));
    }

    @Test
    void aReusedTraversalGivesTheSameAnswers() {
        var t = building(false);
        var into = new java.util.BitSet();
        assertTrue(t.visible(5, 0, 1, looking(1, 5, 0, 1), into));
        assertEquals(java.util.BitSet.valueOf(new long[] {0b11}), into);
        assertFalse(t.visible(-5, 0, 1, looking(-1, -5, 0, 1), into));
        assertEquals(java.util.BitSet.valueOf(new long[] {0b1000}), into);
        assertTrue(t.visible(5, 0, 1, looking(1, 5, 0, 1), into));
        assertEquals(java.util.BitSet.valueOf(new long[] {0b11}), into);
    }

    @Test
    void rangesOutsideTheRecordedPortalsAreRefused() {
        var portals = List.of(door(10, -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoPortalTraversal.of(
                                List.of(room(0, WmoPortalTraversal.INTERIOR, 0, 2)),
                                portals,
                                List.of(new Ref(0, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoPortalTraversal.of(
                                List.of(room(0, WmoPortalTraversal.INTERIOR, 0, 1)),
                                portals,
                                List.of(new Ref(1, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoPortalTraversal.of(
                                List.of(room(0, WmoPortalTraversal.INTERIOR, 0, 1)),
                                portals,
                                List.of(new Ref(0, 1, 1))));
    }

    @Test
    void recordedPortalsAndGroupHeadersBuildTheSameTraversal() {
        var vertices = buffer(48);
        for (float[] v : new float[][] {{10, -1, 0}, {10, 1, 0}, {10, 1, 2}, {10, -1, 2}}) {
            vertices.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
        }
        var portal = buffer(20).putShort((short) 0).putShort((short) 4);
        portal.putFloat(1).putFloat(0).putFloat(0).putFloat(-10);
        var refs = buffer(16);
        refs.putShort((short) 0).putShort((short) 1).putShort((short) -1).putShort((short) 0);
        refs.putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 0);
        var header = buffer(64).putInt(0).putInt(2);
        header.position(36);
        header.putFloat(-100).putFloat(-100).putFloat(-100);
        header.putFloat(100).putFloat(100).putFloat(100);
        var root =
                WmoFormats.root(
                        join(
                                chunk("MVER", buffer(4).putInt(17).array()),
                                chunk("MOHD", header.array()),
                                chunk("MOMT", new byte[0]),
                                chunk("MOPV", vertices.array()),
                                chunk("MOPT", portal.array()),
                                chunk("MOPR", refs.array())),
                        () -> false);
        assertTrue(root.portals().retained());
        var headers = List.of(header(0, 0, 1), header(10, 1, 1));
        var t = WmoPortalTraversal.of(root.portals(), headers);
        assertEquals(2, t.groupCount());
        assertEquals(List.of(A, B), visible(t, 1, 5, 0, 1));
        assertEquals(List.of(A), visible(t, -1, 5, 0, 1));
    }

    private static WmoFormats.GroupHeader header(float minX, int first, int count) {
        return new WmoFormats.GroupHeader(
                0,
                0,
                WmoPortalTraversal.INTERIOR,
                new Vec3(minX, -5, 0),
                new Vec3(minX + 10, 5, 5),
                first,
                count,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] chunk(String name, byte[] body) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = (tag << 8) | c;
        }
        return buffer(body.length + 8).putInt(tag).putInt(body.length).put(body).array();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
