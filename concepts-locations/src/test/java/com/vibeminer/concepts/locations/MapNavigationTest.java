package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.locations.MapNavigationGraph.HierarchyState;
import com.vibeminer.concepts.locations.MapNavigationGraph.HitState;
import com.vibeminer.concepts.locations.MapNavigationGraph.Reason;
import com.vibeminer.concepts.locations.MapNavigationGraph.RegionState;
import com.vibeminer.concepts.locations.MapNavigationTypes.Assignment;
import com.vibeminer.concepts.locations.MapNavigationTypes.Node;
import com.vibeminer.concepts.locations.MapNavigationTypes.Parent;
import com.vibeminer.concepts.locations.MapNavigationTypes.ReadState;
import com.vibeminer.concepts.locations.MapNavigationTypes.Rect;
import com.vibeminer.concepts.locations.MapNavigationTypes.RowSource;
import com.vibeminer.concepts.locations.MapNavigationTypes.Scope;
import com.vibeminer.concepts.locations.MapNavigationTypes.Table;
import com.vibeminer.concepts.locations.MapNavigationTypes.TableRead;
import com.vibeminer.concepts.locations.MapNavigationTypes.UiMapId;
import com.vibeminer.concepts.locations.MapNavigationTypes.View;
import com.vibeminer.concepts.locations.MapNavigationTypes.WorldMapId;
import com.vibeminer.concepts.locations.MapNavigationTypes.WorldRect;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class MapNavigationTest {
    private static final TableRead MAPS = table(Table.UI_MAP, "DB51D55F", ReadState.READY);
    private static final TableRead ASSIGNMENTS =
            table(Table.ASSIGNMENT, "C9CC8DFB", ReadState.READY);
    private static final Scope SCOPE =
            new Scope(
                    new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us"),
                    "enUS",
                    MAPS,
                    ASSIGNMENTS);
    private static final Rect FULL = new Rect(0, 0, 1, 1);

    @Test
    void uiMapAndWorldMapIdentifiersCannotBecomeTheSameDomain() {
        assertThrows(IllegalArgumentException.class, () -> new UiMapId(0));
        assertEquals(0, new WorldMapId(0).value());
        assertNotEquals(new UiMapId(947), new WorldMapId(947));
        var parent = node(947, Parent.root());
        var child = node(1415, Parent.of(parent.id()));
        var graph =
                graph(
                        List.of(parent, child),
                        List.of(
                                assignment(1, parent.id(), 0, new WorldRect(0, 0, 10, 10), FULL),
                                assignment(
                                        2, child.id(), 1415, new WorldRect(0, 0, 10, 10), FULL)));
        assertTrue(graph.regions(parent.id(), 10, () -> false).regions().isEmpty());
        assertEquals(List.of(child), graph.children(parent.id()));
    }

    @Test
    void historyBackIsVisitHistoryWhileUpFollowsTheParentAndDirectSwitchClearsForward() {
        var root = node(947, Parent.root());
        var continent = node(1415, Parent.of(root.id()));
        var elwynn = node(1429, Parent.of(continent.id()));
        var durotar = node(1411, Parent.of(root.id()));
        var graph = graph(List.of(root, continent, elwynn, durotar), List.of());
        var initial = MapVisitHistory.start(graph, root.id());
        var visited = initial.switchTo(elwynn.id()).view(new View(.2, .7, 3));
        assertEquals(root.id(), visited.back().current().map());
        assertEquals(continent.id(), visited.up().current().map());
        assertEquals(elwynn.id(), visited.up().back().current().map());
        assertEquals(new View(.2, .7, 3), visited.up().back().current().view());
        assertEquals(2, visited.size());
        var switched = visited.back().switchTo(durotar.id());
        assertFalse(switched.canForward());
        assertEquals(root.id(), switched.back().current().map());
        assertEquals(durotar.id(), switched.back().forward().current().map());
        assertEquals(1, initial.size()); // immutable previous value
        assertThrows(IllegalArgumentException.class, () -> initial.switchTo(new UiMapId(99)));
    }

    @Test
    void historyIsBoundedAndPanZoomDoesNotInsertVisits() {
        var root = node(1, Parent.root());
        var child = node(2, Parent.of(root.id()));
        var history = MapVisitHistory.start(graph(List.of(root, child), List.of()), root.id());
        for (int i = 0; i < 300; i++) {
            history = history.switchTo(i % 2 == 0 ? child.id() : root.id());
        }
        assertEquals(64, history.size());
        history = history.view(new View(.1, .2, 32));
        assertEquals(64, history.size());
        for (int i = 0; i < 63; i++) {
            history = history.back();
        }
        assertFalse(history.canBack());
        assertTrue(history.canForward());
    }

    @Test
    void missingUnknownCyclicDeepAndDifferentSystemParentsDisableUpButNotDirectVisits() {
        for (var expected :
                List.of(
                        HierarchyState.MISSING_PARENT,
                        HierarchyState.UNKNOWN_PARENT,
                        HierarchyState.CYCLE,
                        HierarchyState.SYSTEM_MISMATCH)) {
            var root = node(1, Parent.root());
            var child =
                    switch (expected) {
                        case MISSING_PARENT -> node(2, Parent.of(new UiMapId(99)));
                        case UNKNOWN_PARENT -> node(2, Parent.unknown());
                        case CYCLE -> node(2, Parent.of(new UiMapId(2)));
                        default ->
                                new Node(
                                        new UiMapId(2),
                                        "Taxi",
                                        Parent.of(root.id()),
                                        1,
                                        2,
                                        0L,
                                        0L,
                                        new RowSource(MAPS, 2));
                    };
            var graph = graph(List.of(root, child), List.of());
            var visit = MapVisitHistory.start(graph, root.id()).switchTo(child.id());
            assertEquals(expected, visit.hierarchy().state());
            assertEquals(child.id(), visit.up().current().map());
            assertEquals(root.id(), visit.back().current().map());
        }
        var deep = new ArrayList<Node>();
        for (int i = 1; i <= 66; i++) {
            deep.add(node(i, i == 1 ? Parent.root() : Parent.of(new UiMapId(i - 1))));
        }
        assertEquals(
                HierarchyState.TOO_DEEP, graph(deep, List.of()).hierarchy(new UiMapId(66)).state());
        assertEquals(
                "Map #7",
                new Node(
                                new UiMapId(7),
                                null,
                                Parent.root(),
                                0,
                                3,
                                0L,
                                null,
                                new RowSource(MAPS, 7))
                        .displayName());
    }

    @Test
    void asymmetricSharedWorldRectangleProjectsThroughTheParentsInsetWithReversedAxes() {
        var parent = node(947, Parent.root());
        var child = node(1415, Parent.of(parent.id()));
        var graph =
                graph(
                        List.of(parent, child),
                        List.of(
                                assignment(
                                        1,
                                        parent.id(),
                                        0,
                                        new WorldRect(100, 1000, 300, 1400),
                                        new Rect(.55, .1, .9, .8)),
                                assignment(
                                        2,
                                        child.id(),
                                        0,
                                        new WorldRect(150, 1100, 250, 1200),
                                        FULL)));
        var page = graph.regions(parent.id(), 8, () -> false);
        assertEquals(RegionState.READY, page.state());
        var region = page.regions().getFirst();
        assertEquals(.725, region.bounds().minX(), 1e-14);
        assertEquals(.8125, region.bounds().maxX(), 1e-14);
        assertEquals(.275, region.bounds().minY(), 1e-14);
        assertEquals(.625, region.bounds().maxY(), 1e-14);
        assertEquals(2, region.childSource().rowId());
        assertEquals(1, region.parentSource().rowId());
        assertEquals(HitState.UNIQUE, page.hit(.75, .3).state());
        assertEquals(HitState.NONE, page.hit(.3, .75).state());
        assertEquals(HitState.NONE, page.hit(Double.NaN, .3).state());
        var larger =
                graph(
                        List.of(parent, child),
                        List.of(
                                assignment(
                                        1,
                                        parent.id(),
                                        0,
                                        new WorldRect(100, 1000, 300, 1400),
                                        new Rect(.55, .1, .9, .8)),
                                assignment(
                                        2,
                                        child.id(),
                                        0,
                                        new WorldRect(50, 900, 350, 1500),
                                        FULL)));
        assertEquals(
                new Rect(.55, .1, .9, .8),
                larger.regions(parent.id(), 8, () -> false).regions().getFirst().bounds());
    }

    @Test
    void overlappingChildrenRemainChoicesAndDuplicateAssignmentsKeepTheirEvidence() {
        var root = node(1, Parent.root());
        var a = node(2, Parent.of(root.id()));
        var b = node(3, Parent.of(root.id()));
        var bounds = new WorldRect(0, 0, 10, 10);
        var page =
                graph(
                                List.of(root, a, b),
                                List.of(
                                        assignment(1, root.id(), 0, bounds, FULL),
                                                assignment(2, a.id(), 0, bounds, FULL),
                                        assignment(3, a.id(), 0, bounds, FULL),
                                                assignment(4, b.id(), 0, bounds, FULL)))
                        .regions(root.id(), 8, () -> false);
        assertEquals(HitState.AMBIGUOUS, page.hit(.5, .5).state());
        assertEquals(List.of(a.id(), b.id()), page.hit(.5, .5).choices());
        assertEquals(3, page.hit(.5, .5).evidence().size());
        assertTrue(new Rect(0, 0, .5, 1).contains(0, 0));
        assertFalse(new Rect(0, 0, .5, 1).contains(.5, .5));
        assertTrue(new Rect(.5, 0, 1, 1).contains(.5, .5));
        assertTrue(new Rect(.5, 0, 1, 1).contains(1, 1));
    }

    @Test
    void workExhaustionRefusesAllHitGeometryRatherThanPublishingAnUncheckedBiasedSubset() {
        var root = node(1, Parent.root());
        var child = node(2, Parent.of(root.id()));
        var world = new WorldRect(0, 0, 10, 10);
        var page =
                graph(
                                List.of(root, child),
                                List.of(
                                        assignment(1, root.id(), 0, world, FULL),
                                        assignment(2, root.id(), 0, world, FULL),
                                        assignment(3, child.id(), 0, world, FULL)))
                        .regions(root.id(), 1, () -> false);
        assertEquals(RegionState.UNAVAILABLE, page.state());
        assertEquals(1, page.testedPairs());
        assertEquals(List.of(child), page.children());
        assertTrue(page.regions().isEmpty());
        assertEquals(Reason.WORK_LIMIT, page.issues().getFirst().reason());
    }

    @Test
    void unsupportedFramesAndPartialInputsAreExplicitAndNeverInventHotspots() {
        var root = node(1, Parent.root());
        var child = node(2, Parent.of(root.id()));
        var world = new WorldRect(0, 0, 10, 10);
        var scoped =
                new Assignment(
                        child.id(),
                        new WorldMapId(0),
                        world,
                        FULL,
                        false,
                        new RowSource(ASSIGNMENTS, 2));
        var page =
                graph(
                                List.of(root, child),
                                List.of(assignment(1, root.id(), 0, world, FULL), scoped))
                        .regions(root.id(), 8, () -> false);
        assertTrue(page.regions().isEmpty());
        assertTrue(page.issues().stream().anyMatch(issue -> issue.reason() == Reason.WMO_CONTEXT));
        var noWorld = new Node(root.id(), "World", Parent.root(), 0, 1, 0x80L, 0L, root.source());
        assertEquals(
                Reason.NO_WORLD_POSITIONS,
                graph(List.of(noWorld, child), List.of())
                        .regions(root.id(), 8, () -> false)
                        .issues()
                        .getFirst()
                        .reason());
        var partial =
                new TableRead(Table.ASSIGNMENT, "c".repeat(32), "C9CC8DFB", ReadState.PARTIAL);
        var scope = new Scope(SCOPE.build(), SCOPE.locale(), MAPS, partial);
        assertEquals(
                RegionState.LIMITED,
                new MapNavigationGraph(scope, List.of(root, child), List.of(), () -> false)
                        .regions(root.id(), 8, () -> false)
                        .state());
    }

    @Test
    void countAndByteCapsPrecedeCopiesAndInputsAreDetachedAndCancelable() {
        var tooMany =
                new AbstractList<Node>() {
                    public int size() {
                        return 8193;
                    }

                    public Node get(int index) {
                        throw new AssertionError("Count guard must precede traversal");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> graph(tooMany, List.of()));
        var nodes = new ArrayList<Node>();
        nodes.add(node(1, Parent.root()));
        var graph = graph(nodes, List.of());
        nodes.clear();
        assertEquals(1, graph.nodes().size());
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
        assertThrows(
                CancellationException.class,
                () ->
                        new MapNavigationGraph(
                                SCOPE, List.of(node(1, Parent.root())), List.of(), () -> true));
        assertThrows(
                CancellationException.class, () -> graph.regions(new UiMapId(1), 8, () -> true));
        assertThrows(
                IllegalArgumentException.class,
                () -> graph.regions(new UiMapId(1), 65_537, () -> false));
        var large = new ArrayList<Node>();
        for (int i = 1; i <= 8192; i++) {
            large.add(
                    new Node(
                            new UiMapId(i),
                            "n".repeat(256),
                            Parent.root(),
                            0,
                            1,
                            0L,
                            0L,
                            new RowSource(MAPS, i)));
        }
        assertThrows(IllegalArgumentException.class, () -> graph(large, List.of()));
        assertTrue(graph.logicalBytes() <= MapNavigationGraph.MAX_LOGICAL_BYTES);
    }

    @Test
    void noRecordedArtworkOrFloorsRequiresCompletePositiveTableEvidenceNotMapTypeOrNull() {
        assertEquals(
                MapResourceEvidence.State.NOT_CHECKED,
                MapResourceEvidence.unchecked(MapResourceEvidence.Resource.ARTWORK_IMAGES).state());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.FLOORS,
                                MapResourceEvidence.State.NONE_RECORDED,
                                0,
                                List.of(),
                                List.of()));
        var groups = table(Table.GROUP, "6D5EB3BD", ReadState.READY);
        var members = table(Table.GROUP_MEMBER, "0FF75B01", ReadState.READY);
        assertEquals(
                MapResourceEvidence.State.NONE_RECORDED,
                new MapResourceEvidence(
                                MapResourceEvidence.Resource.FLOORS,
                                MapResourceEvidence.State.NONE_RECORDED,
                                0,
                                List.of(groups, members),
                                List.of())
                        .state());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.ARTWORK_IMAGES,
                                MapResourceEvidence.State.NONE_RECORDED,
                                0,
                                List.of(groups, members),
                                List.of()));
        var partialMembers = table(Table.GROUP_MEMBER, "0FF75B01", ReadState.PARTIAL);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.FLOORS,
                                MapResourceEvidence.State.NONE_RECORDED,
                                0,
                                List.of(groups, partialMembers),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.FLOORS,
                                MapResourceEvidence.State.NONE_RECORDED,
                                0,
                                List.of(groups, members, partialMembers),
                                List.of()));
    }

    @Test
    void manifestAvailabilityNeverInventsImagesAndMissingFilesRetainTheirOwnEvidence() {
        var route =
                List.of(
                        table(Table.ART_RELATION, "EAE7DA2A", ReadState.READY),
                        table(Table.ART, "3AE7D144", ReadState.READY),
                        table(Table.ART_STYLE, "3F597F5A", ReadState.READY),
                        table(Table.ART_TILE, "2DA5B77B", ReadState.READY));
        var manifest =
                new MapResourceEvidence(
                        MapResourceEvidence.Resource.ARTWORK_MANIFEST,
                        MapResourceEvidence.State.READY,
                        1,
                        route,
                        List.of());
        assertEquals(MapResourceEvidence.State.READY, manifest.state());
        assertEquals(
                MapResourceEvidence.State.NOT_CHECKED,
                MapResourceEvidence.unchecked(MapResourceEvidence.Resource.ARTWORK_IMAGES).state());
        var absent =
                new MapResourceEvidence.FileRead(31, null, MapResourceEvidence.FileState.NOT_LOCAL);
        var images =
                new MapResourceEvidence(
                        MapResourceEvidence.Resource.ARTWORK_IMAGES,
                        MapResourceEvidence.State.UNAVAILABLE,
                        -1,
                        List.of(),
                        List.of(absent));
        assertEquals(MapResourceEvidence.FileState.NOT_LOCAL, images.files().getFirst().state());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.ARTWORK_IMAGES,
                                MapResourceEvidence.State.READY,
                                1,
                                route,
                                List.of(absent)));
        var oversized =
                new AbstractList<MapResourceEvidence.FileRead>() {
                    public int size() {
                        return 257;
                    }

                    public MapResourceEvidence.FileRead get(int index) {
                        throw new AssertionError("Count before copy");
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MapResourceEvidence(
                                MapResourceEvidence.Resource.ARTWORK_IMAGES,
                                MapResourceEvidence.State.UNAVAILABLE,
                                -1,
                                List.of(),
                                oversized));
    }

    @Test
    void duplicateAndMixedSnapshotRowsAreRejectedAndAssignmentCallerListsAreDetached() {
        var root = node(1, Parent.root());
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(root, root), List.of()));
        var otherMaps = new TableRead(Table.UI_MAP, "d".repeat(32), "DB51D55F", ReadState.READY);
        var mixed =
                new Node(
                        new UiMapId(2),
                        "Other snapshot",
                        Parent.root(),
                        0,
                        3,
                        0L,
                        0L,
                        new RowSource(otherMaps, 2));
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(root, mixed), List.of()));
        var child = node(2, Parent.of(root.id()));
        var world = new WorldRect(0, 0, 10, 10);
        var rows =
                new ArrayList<Assignment>(
                        List.of(
                                assignment(1, root.id(), 0, world, FULL),
                                assignment(2, child.id(), 0, world, FULL)));
        var graph = graph(List.of(root, child), rows);
        rows.clear();
        assertEquals(1, graph.regions(root.id(), 4, () -> false).regions().size());
    }

    @Test
    void regionCountRefusesTheEntireHitPageAndAssignmentCountRefusesBeforeTraversal() {
        var root = node(1, Parent.root());
        var child = node(2, Parent.of(root.id()));
        var tooMany =
                new AbstractList<Assignment>() {
                    public int size() {
                        return 16_385;
                    }

                    public Assignment get(int index) {
                        throw new AssertionError("Assignment count before copy");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(root, child), tooMany));
        var assignments = new ArrayList<Assignment>();
        var world = new WorldRect(0, 0, 10, 10);
        for (int i = 0; i < 130; i++) {
            assignments.add(assignment(i + 1, root.id(), 0, world, FULL));
            assignments.add(assignment(i + 131, child.id(), 0, world, FULL));
        }
        var page =
                graph(List.of(root, child), assignments)
                        .regions(root.id(), MapNavigationGraph.MAX_PAIR_TESTS, () -> false);
        assertEquals(RegionState.UNAVAILABLE, page.state());
        assertEquals(Reason.REGION_LIMIT, page.issues().getFirst().reason());
        assertEquals(16_385, page.testedPairs());
        assertEquals(List.of(child), page.children());
        assertTrue(page.regions().isEmpty());
    }

    @Test
    void absentCurrentOrParentSystemIsUnknownWhileUnequalKnownSystemsAreAMismatch() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Integer parentSystem = scenario == 1 ? null : 0;
            Integer childSystem = scenario == 0 ? null : scenario == 2 ? 1 : 0;
            var root =
                    new Node(
                            new UiMapId(1),
                            "Root",
                            Parent.root(),
                            parentSystem,
                            1,
                            0L,
                            0L,
                            new RowSource(MAPS, 1));
            var child =
                    new Node(
                            new UiMapId(2),
                            "Child",
                            Parent.of(root.id()),
                            childSystem,
                            3,
                            0L,
                            0L,
                            new RowSource(MAPS, 2));
            var graph = graph(List.of(root, child), List.of());
            assertEquals(
                    scenario == 2 ? HierarchyState.SYSTEM_MISMATCH : HierarchyState.MISSING_SYSTEM,
                    graph.hierarchy(child.id()).state());
            assertTrue(graph.hierarchy(child.id()).parent().isEmpty());
            var page = graph.regions(root.id(), 8, () -> false);
            assertEquals(RegionState.LIMITED, page.state());
            assertEquals(
                    scenario == 2 ? Reason.SYSTEM_MISMATCH : Reason.MISSING_SYSTEM,
                    page.issues().getFirst().reason());
            assertTrue(page.regions().isEmpty());
            assertEquals(
                    child.id(),
                    MapVisitHistory.start(graph, root.id()).switchTo(child.id()).current().map());
        }
    }

    private static TableRead table(Table table, String layout, ReadState state) {
        return new TableRead(table, "c".repeat(32), layout, state);
    }

    private static Node node(long id, Parent parent) {
        return new Node(
                new UiMapId(id), "Map " + id, parent, 0, 3, 0L, 0L, new RowSource(MAPS, id));
    }

    private static Assignment assignment(
            long row, UiMapId map, long world, WorldRect rect, Rect ui) {
        return new Assignment(
                map, new WorldMapId(world), rect, ui, true, new RowSource(ASSIGNMENTS, row));
    }

    private static MapNavigationGraph graph(List<Node> nodes, List<Assignment> assignments) {
        return new MapNavigationGraph(SCOPE, nodes, assignments, () -> false);
    }
}
