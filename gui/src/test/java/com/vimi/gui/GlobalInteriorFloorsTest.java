package com.vimi.gui;

import com.vibeminer.concepts.locations.MapTypes;
import com.vibeminer.concepts.world.WorldGlobalScenes;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WorldPlacements.Vec3;
import com.vibeminer.rendering.bgfx.WorldGlobalTransport;

import java.util.Arrays;
import java.util.List;

/** Authored groups and assignment rows only: no client files, tables, child process or display. */
public final class GlobalInteriorFloorsTest {
    private GlobalInteriorFloorsTest() {}

    public static void main(String[] args) {
        outlineCarriesTheRecordedHeaderGroupId();
        exactGroupIdsLinkHierarchicalFloors();
        unmatchedAndAmbiguousRowsAreCountedWithReasons();
        floorsFrameTheUnionOfTheirGroups();
        nothingIsInferredWithoutAnExactAssignment();
        unavailableSourcesAndUnreadableGroupsStayNotLinked();
        parentCyclesListEveryFloorOnce();
        lookupNeverThrowsAndPublishesNothingAfterCancel();
        System.out.println(
                "Global interior floors: exact WMO group links, hierarchy, framing and unlinked"
                        + " reasons passed");
    }

    private static final float[] SCENE = {-100, -50, -10, 100, 50, 30};

    private static WorldGlobalScenes.Group group(
            int ordinal, long groupId, String name, Vec3 lower, Vec3 upper) {
        return new WorldGlobalScenes.Group(
                ordinal,
                900 + ordinal,
                0x2005,
                3,
                1,
                1,
                1,
                false,
                false,
                0,
                "0".repeat(64),
                new WmoFormats.GroupHeader(
                        0, 0, 0x2005, lower, upper, 0, 0, 0, 0, 1, 0, 0, 0, groupId, 0, 0),
                name);
    }

    /** Native boxes: WMO-local (x, y) mirror to (-x, -y); see GlobalInteriorOutlineTest. */
    private static GlobalInteriorOutline outline() {
        return GlobalInteriorOutline.of(
                List.of(
                        group(0, 77, "Upper", v(-10, -10, 0), v(-5, -5, 4)),
                        group(1, 78, "Lower", v(5, 5, -8), v(10, 10, -2)),
                        group(2, 79, "Stair A", v(0, 0, 0), v(1, 1, 1)),
                        group(3, 79, "Stair B", v(0, 0, 0), v(1, 1, 1)),
                        group(4, 0, "Unset", v(0, 0, 0), v(1, 1, 1)),
                        group(5, 81, "Away", v(500, 500, 500), v(600, 600, 600))),
                SCENE);
    }

    private static MapTypes.MapInfo map(long id, String name, Long parent, Integer type) {
        return new MapTypes.MapInfo(id, name, parent, type, 0L);
    }

    private static final MapTypes.MapInfo ZONE = map(1411, "Authored zone", 1414L, 3);
    private static final MapTypes.MapInfo UPPER = map(2001, "Upper hall", 1411L, 4);
    private static final MapTypes.MapInfo LOWER = map(2002, "Lower hall", 2001L, 4);

    private static MapTypes.WmoGroupAssignment row(
            long id, MapTypes.MapInfo map, long uiMap, long group, long placement) {
        return new MapTypes.WmoGroupAssignment(id, uiMap, map, group, placement, List.of());
    }

    private static MapTypes.WmoGroupAssignments recorded(
            int unreadable, MapTypes.WmoGroupAssignment... rows) {
        return new MapTypes.WmoGroupAssignments(
                MapTypes.State.READY,
                null,
                "enUS",
                249,
                List.of(rows),
                List.of(ZONE),
                unreadable,
                0,
                List.of(),
                null);
    }

    private static MapTypes.WmoGroupAssignments sample() {
        return recorded(
                2,
                row(501, UPPER, 2001, 77, 0),
                row(502, LOWER, 2002, 78, 0),
                row(503, LOWER, 2002, 78, 9),
                row(504, null, 3000, 80, 0),
                row(506, UPPER, 2001, 79, 0),
                row(507, UPPER, 2001, 99, 0),
                row(508, UPPER, 2001, 78, 0),
                row(509, UPPER, 2001, 77, 0));
    }

    private static void outlineCarriesTheRecordedHeaderGroupId() {
        var groups = outline().groups();
        require(groups.get(0).recordedGroupId() == 77, "MOGP group id is kept");
        require(groups.get(4).recordedGroupId() == 0, "unset group id stays 0");
    }

    private static void exactGroupIdsLinkHierarchicalFloors() {
        var linked = GlobalInteriorFloors.link(outline(), sample());
        var floors = linked.floors();
        require(floors.size() == 2, "two UiMaps link exactly: " + floors);
        var upper = floors.get(0);
        require(upper.uiMapId() == 2001 && upper.depth() == 0, "parent floor first");
        require(
                upper.groups().stream().map(GlobalInteriorOutline.Group::ordinal).toList()
                        .equals(List.of(0, 1)),
                "upper lists its groups in file order, deduplicated: " + upper.groups());
        require(upper.assignmentIds().equals(List.of(501L, 508L, 509L)), "" + upper.assignmentIds());
        require(upper.parentName().equals("Authored zone"), "parent from the recorded UiMap");
        require(
                upper.details()
                        .equals(
                                "UiMap type 4 (dungeon) · parent Authored zone #1411 · groups 0"
                                        + " Upper, 1 Lower"),
                upper.details());
        var lower = floors.get(1);
        require(lower.uiMapId() == 2002 && lower.depth() == 1, "child floor nested under parent");
        require(lower.parentName().equals("Upper hall"), "parent is the listed floor");
        require(lower.label().equals("  Lower hall · UiMap #2002"), lower.label());
        require(
                lower.groups().stream().map(GlobalInteriorOutline.Group::ordinal).toList()
                        .equals(List.of(1)),
                "lower lists only its exactly linked group");
        require(
                linked.summary()
                        .equals(
                                "Floors: 2 UiMap layers linked by 4 exact WMO group assignments · 6"
                                        + " not linked · listed by UiMap ID within each level, not by height"),
                linked.summary());
        require(GlobalInteriorFloors.typeText(9).equals("UiMap type 9"), "unknown type raw");
        require(GlobalInteriorFloors.typeText(null).equals("UiMap type unknown"), "no type");
    }

    private static void unmatchedAndAmbiguousRowsAreCountedWithReasons() {
        var unlinked = GlobalInteriorFloors.link(outline(), sample()).unlinked();
        require(
                unlinked.stream().map(GlobalInteriorFloors.Unlinked::reason).toList()
                        .equals(
                                List.of(
                                        GlobalInteriorFloors.Reason.NO_RECORDED_GROUP,
                                        GlobalInteriorFloors.Reason.AMBIGUOUS_GROUP,
                                        GlobalInteriorFloors.Reason.PLACEMENT_SCOPED,
                                        GlobalInteriorFloors.Reason.UI_MAP_UNAVAILABLE,
                                        GlobalInteriorFloors.Reason.UNREADABLE)),
                "every reason in a stable order: " + unlinked);
        for (var entry : unlinked) {
            int expected = entry.reason() == GlobalInteriorFloors.Reason.UNREADABLE ? 2 : 1;
            require(entry.count() == expected, entry.text());
        }
        require(
                unlinked.get(1)
                        .text()
                        .equals(
                                "1 assignment not linked: more than one group in this interior"
                                        + " records its WMO group ID (WMO group ID 79)"),
                unlinked.get(1).text());
        require(
                unlinked.get(4)
                        .text()
                        .equals(
                                "2 assignments not linked: its group, placement or UiMap fields"
                                        + " could not be read"),
                unlinked.get(4).text());
        var many = new MapTypes.WmoGroupAssignment[12];
        for (int i = 0; i < many.length; i++) {
            many[i] = row(600 + i, UPPER, 2001, 1000 + i, 0);
        }
        var capped = GlobalInteriorFloors.link(outline(), recorded(0, many)).unlinked().getFirst();
        require(capped.count() == 12, "count is exact");
        require(capped.exampleGroupIds().size() == GlobalInteriorFloors.MAX_EXAMPLES, "examples capped");
        require(capped.text().endsWith(", …)"), capped.text());
    }

    private static void floorsFrameTheUnionOfTheirGroups() {
        var floors = GlobalInteriorFloors.link(outline(), sample()).floors();
        var upper = floors.get(0);
        // Upper native x 5..10, y 5..10, z 0..4; Lower native x -10..-5, y -10..-5, z -8..-2.
        require(
                Arrays.equals(upper.bounds(), new float[] {-10, -10, -8, 10, 10, 4}),
                Arrays.toString(upper.bounds()));
        var camera = upper.camera();
        require(
                camera.equals(WorldGlobalTransport.Camera.inspecting(upper.bounds())),
                "a floor uses the reviewed inspection framing of its groups: " + camera);
        var away =
                GlobalInteriorFloors.link(outline(), recorded(0, row(700, UPPER, 2001, 81, 0)))
                        .floors()
                        .getFirst();
        require(away.bounds() == null && away.camera() == null, "unusable groups cannot frame");
        require(away.details().endsWith("bounds outside the camera range"), away.details());
    }

    private static void nothingIsInferredWithoutAnExactAssignment() {
        var none = GlobalInteriorFloors.link(outline(), recorded(0));
        require(none.floors().isEmpty() && none.unlinked().isEmpty(), "no rows, no floors");
        require(
                none.summary()
                        .equals(
                                "Floors: not linked · no group-scoped UiMap assignment names this"
                                        + " map"),
                none.summary());
        var unmatched = GlobalInteriorFloors.link(outline(), recorded(0, row(1, UPPER, 2001, 5, 0)));
        require(unmatched.floors().isEmpty(), "a near or similar id is never matched");
        require(
                unmatched.summary().contains("matches a recorded group exactly"),
                unmatched.summary());
        // Groups 2 and 3 share bounds and an id; group 4 has id 0. Neither becomes a floor.
        var floors = GlobalInteriorFloors.link(outline(), sample()).floors();
        require(
                floors.stream()
                        .flatMap(f -> f.groups().stream())
                        .noneMatch(g -> g.ordinal() >= 2),
                "ambiguous and unset groups never join a floor");
    }

    private static void unavailableSourcesAndUnreadableGroupsStayNotLinked() {
        var unsupported =
                GlobalInteriorFloors.link(outline(), MapTypes.WmoGroupAssignments.unsupported(249));
        require(unsupported.floors().isEmpty(), "unsupported source links nothing");
        require(unsupported.summary().startsWith("Floors: not linked · "), unsupported.summary());
        var unreadable = GlobalInteriorFloors.link(GlobalInteriorOutline.unreadable(), sample());
        require(
                unreadable.floors().isEmpty()
                        && unreadable.summary().contains("recorded groups of this interior"),
                unreadable.summary());
        var partial =
                new MapTypes.WmoGroupAssignments(
                        MapTypes.State.PARTIAL,
                        null,
                        "enUS",
                        249,
                        List.of(row(1, UPPER, 2001, 77, 0)),
                        List.of(),
                        0,
                        3,
                        List.of("partial"),
                        null);
        var linked = GlobalInteriorFloors.link(outline(), partial);
        require(linked.floors().size() == 1, "partial table still links exact rows");
        require(
                linked.summary().endsWith("some assignment or UiMap records were incomplete"),
                linked.summary());
        var over = linked.unlinked();
        require(
                over.size() == 1
                        && over.getFirst().reason() == GlobalInteriorFloors.Reason.OVER_LIMIT
                        && over.getFirst().count() == 3,
                "rows beyond the list limit are their own reason: " + over);
        require(
                linked.floors().getFirst().parentName() == null
                        && linked.floors().getFirst().details().contains("parent UiMap #1411"),
                "an unrecorded parent name stays unknown: " + linked.floors().getFirst().details());
    }

    private static void parentCyclesListEveryFloorOnce() {
        var a = map(3001, "A", 3002L, 4);
        var b = map(3002, "B", 3001L, 4);
        var floors =
                GlobalInteriorFloors.link(
                                outline(), recorded(0, row(1, a, 3001, 77, 0), row(2, b, 3002, 78, 0)))
                        .floors();
        require(
                floors.stream().map(GlobalInteriorFloors.Floor::uiMapId).sorted().toList()
                        .equals(List.of(3001L, 3002L)),
                "both cyclic floors listed once: " + floors);
        require(floors.stream().allMatch(f -> f.depth() == 0), "cycles are listed flat");
        // C's parent A is on the A<->B cycle, but C itself is not: it stays nested under A.
        var c = map(3003, "C", 3001L, 4);
        var tail =
                GlobalInteriorFloors.link(
                                outline(),
                                recorded(
                                        0,
                                        row(1, a, 3001, 77, 0),
                                        row(2, b, 3002, 78, 0),
                                        row(3, c, 3003, 77, 0)))
                        .floors();
        require(tail.size() == 3, "every floor once: " + tail);
        var nested = tail.stream().filter(f -> f.uiMapId() == 3003).findFirst().orElseThrow();
        require(nested.depth() == 1, "a floor leading into a cycle stays nested: " + tail);
        int ai = tail.indexOf(tail.stream().filter(f -> f.uiMapId() == 3001).findFirst().get());
        require(tail.indexOf(nested) == ai + 1, "nested directly under its parent: " + tail);
    }

    private static com.vibeminer.concepts.locations.BuildLocationMaps source(
            java.util.function.Function<
                            Long,
                            java.util.concurrent.CompletableFuture<MapTypes.WmoGroupAssignments>>
                    query) {
        return new com.vibeminer.concepts.locations.BuildLocationMaps() {
            public java.util.concurrent.CompletableFuture<MapTypes.Resolution> resolve(
                    com.vibeminer.concepts.locations.LocationTypes.Location location,
                    com.vibeminer.access.Cancellation token) {
                throw new AssertionError();
            }

            public java.util.concurrent.CompletableFuture<MapTypes.Artwork> artwork(
                    long uiMapId, com.vibeminer.access.Cancellation token) {
                throw new AssertionError();
            }

            public java.util.concurrent.CompletableFuture<MapTypes.TileImage> tile(
                    long uiMapId, long tileRowId, com.vibeminer.access.Cancellation token) {
                throw new AssertionError();
            }

            @Override
            public java.util.concurrent.CompletableFuture<MapTypes.WmoGroupAssignments>
                    wmoGroupAssignments(long worldMapId, com.vibeminer.access.Cancellation token) {
                return query.apply(worldMapId);
            }

            public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
    }

    private static void lookupNeverThrowsAndPublishesNothingAfterCancel() {
        var live = new com.vibeminer.access.Cancellation();
        var asked = new java.util.concurrent.atomic.AtomicLong();
        var ok =
                GlobalInteriorFloors.lookup(
                        () ->
                                source(
                                        id -> {
                                            asked.set(id);
                                            return java.util.concurrent.CompletableFuture
                                                    .completedFuture(sample());
                                        }),
                        249,
                        outline(),
                        live);
        require(asked.get() == 249 && ok.floors().size() == 2, "lookup queries the map and links");
        require(
                GlobalInteriorFloors.lookup(null, 249, outline(), live)
                        .summary()
                        .contains("not ready"),
                "no supplier");
        require(
                GlobalInteriorFloors.lookup(() -> null, 249, outline(), live)
                        .summary()
                        .contains("not ready"),
                "no source yet");
        for (Throwable failure :
                List.<Throwable>of(new IllegalStateException("x"), new AssertionError("x"))) {
            var failed =
                    GlobalInteriorFloors.lookup(
                            () ->
                                    source(
                                            id ->
                                                    java.util.concurrent.CompletableFuture
                                                            .failedFuture(failure)),
                            249,
                            outline(),
                            live);
            require(
                    failed.floors().isEmpty() && failed.summary().contains("could not be read"),
                    "failure " + failure + " becomes not linked: " + failed.summary());
        }
        var thrower =
                GlobalInteriorFloors.lookup(
                        () -> {
                            throw new AssertionError("supplier");
                        },
                        249,
                        outline(),
                        live);
        require(thrower.summary().contains("could not be read"), "an Error never escapes");
        var canceled = new com.vibeminer.access.Cancellation();
        canceled.cancel();
        require(
                GlobalInteriorFloors.lookup(
                                () ->
                                        source(
                                                id ->
                                                        java.util.concurrent.CompletableFuture
                                                                .completedFuture(sample())),
                                249,
                                outline(),
                                canceled)
                        == null,
                "nothing is published after close");
    }

    private static Vec3 v(float x, float y, float z) {
        return new Vec3(x, y, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
