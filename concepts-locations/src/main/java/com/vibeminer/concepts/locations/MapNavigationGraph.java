package com.vibeminer.concepts.locations;

import com.vibeminer.concepts.locations.MapNavigationTypes.Assignment;
import com.vibeminer.concepts.locations.MapNavigationTypes.Node;
import com.vibeminer.concepts.locations.MapNavigationTypes.ReadState;
import com.vibeminer.concepts.locations.MapNavigationTypes.Rect;
import com.vibeminer.concepts.locations.MapNavigationTypes.RowSource;
import com.vibeminer.concepts.locations.MapNavigationTypes.Scope;
import com.vibeminer.concepts.locations.MapNavigationTypes.UiMapId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable worker-built navigation facts. Regions are assignment rectangles, not zone polygons.
 */
public final class MapNavigationGraph {
    public static final int MAX_DEPTH = 64, MAX_PAIR_TESTS = 65_536, MAX_REGIONS = 16_384;
    public static final long MAX_LOGICAL_BYTES = 16L << 20;
    // Only observed, reviewed non-frame flags are admitted by this first projection profile.
    private static final long NON_FRAME_FLAGS = 0x902, NO_WORLD_POSITIONS = 0x80;

    public enum HierarchyState {
        COMPLETE,
        UNKNOWN_PARENT,
        MISSING_PARENT,
        CYCLE,
        TOO_DEEP,
        SYSTEM_MISMATCH
    }

    public enum RegionState {
        READY,
        LIMITED,
        UNAVAILABLE
    }

    public enum Reason {
        PARTIAL_TABLE,
        UNAVAILABLE_ASSIGNMENTS,
        UNKNOWN_FLAGS,
        NO_WORLD_POSITIONS,
        UNSUPPORTED_FLAGS,
        MISSING_SYSTEM,
        NO_ASSIGNMENT,
        CHILD_FRAME,
        WMO_CONTEXT,
        DISJOINT_FRAME,
        WORK_LIMIT,
        REGION_LIMIT
    }

    public enum HitState {
        NONE,
        UNIQUE,
        AMBIGUOUS
    }

    public record Issue(Reason reason, UiMapId map) {}

    public record Hierarchy(HierarchyState state, List<Node> path) {
        public Hierarchy {
            Objects.requireNonNull(state);
            Objects.requireNonNull(path);
            if (path.size() > MAX_DEPTH) {
                throw new IllegalArgumentException("Hierarchy path limit");
            }
            path = List.copyOf(path);
        }

        public Optional<UiMapId> parent() {
            return state == HierarchyState.COMPLETE && path.size() > 1
                    ? Optional.of(path.get(path.size() - 2).id())
                    : Optional.empty();
        }
    }

    public record Region(
            UiMapId child, Rect bounds, RowSource childSource, RowSource parentSource) {}

    public record Hit(HitState state, List<UiMapId> choices, List<Region> evidence) {
        public Hit {
            Objects.requireNonNull(state);
            Objects.requireNonNull(choices);
            Objects.requireNonNull(evidence);
            if (choices.size() > MapNavigationTypes.MAX_NODES || evidence.size() > MAX_REGIONS) {
                throw new IllegalArgumentException("Map hit result limit");
            }
            if (state == HitState.NONE && !choices.isEmpty()
                    || state == HitState.UNIQUE && choices.size() != 1
                    || state == HitState.AMBIGUOUS && choices.size() < 2) {
                throw new IllegalArgumentException("Map hit result shape");
            }
            choices = List.copyOf(choices);
            evidence = List.copyOf(evidence);
        }
    }

    public static final class Regions {
        private final RegionState state;
        private final List<Node> children;
        private final List<Region> regions;
        private final List<Issue> issues;
        private final int testedPairs;

        private Regions(
                RegionState state,
                List<Node> children,
                List<Region> regions,
                List<Issue> issues,
                int testedPairs) {
            this.state = state;
            this.children = children;
            this.regions = List.copyOf(regions);
            this.issues = List.copyOf(issues);
            this.testedPairs = testedPairs;
        }

        public RegionState state() {
            return state;
        }

        public List<Node> children() {
            return children;
        }

        public List<Region> regions() {
            return regions;
        }

        public List<Issue> issues() {
            return issues;
        }

        public int testedPairs() {
            return testedPairs;
        }

        public Hit hit(double x, double y) {
            var hits = new ArrayList<Region>();
            var choices = new LinkedHashMap<UiMapId, Boolean>();
            for (var region : regions) {
                if (region.bounds().contains(x, y)) {
                    hits.add(region);
                    choices.put(region.child(), true);
                }
            }
            var ids = List.copyOf(choices.keySet());
            return new Hit(
                    ids.isEmpty()
                            ? HitState.NONE
                            : ids.size() == 1 ? HitState.UNIQUE : HitState.AMBIGUOUS,
                    ids,
                    hits);
        }
    }

    private final Scope scope;
    private final List<Node> nodes;
    private final Map<UiMapId, Node> byId;
    private final Map<UiMapId, List<Node>> children;
    private final Map<UiMapId, List<Assignment>> assignments;
    private final long logicalBytes;

    /**
     * Constructor/copying belongs on a bounded preparation worker, never a pointer-move callback.
     */
    public MapNavigationGraph(
            Scope scope, List<Node> nodes, List<Assignment> assignments, BooleanSupplier canceled) {
        this.scope = Objects.requireNonNull(scope);
        Objects.requireNonNull(nodes);
        Objects.requireNonNull(assignments);
        Objects.requireNonNull(canceled);
        if (nodes.size() > MapNavigationTypes.MAX_NODES
                || assignments.size() > MapNavigationTypes.MAX_ASSIGNMENTS) {
            throw new IllegalArgumentException("Navigation catalogue count limit");
        }
        // Check all logical charges before making another list/index copy. Refuse rather than
        // truncate.
        long charge = 1L << 20;
        for (var node : nodes) {
            check(canceled);
            Objects.requireNonNull(node);
            charge += 1024L + (node.name() == null ? 0 : 4L * node.name().length());
        }
        charge += assignments.size() * 768L;
        if (charge > MAX_LOGICAL_BYTES) {
            throw new IllegalArgumentException("Navigation catalogue byte limit");
        }
        logicalBytes = charge;
        this.nodes = List.copyOf(nodes);
        var ids = new HashMap<UiMapId, Node>();
        var childIndex = new HashMap<UiMapId, List<Node>>();
        for (var node : this.nodes) {
            check(canceled);
            if (!node.source().table().equals(scope.maps()) || ids.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate or mixed-source UiMap");
            }
            if (node.parent().known() && node.parent().id() != null) {
                childIndex
                        .computeIfAbsent(node.parent().id(), ignored -> new ArrayList<>())
                        .add(node);
            }
        }
        byId = Map.copyOf(ids);
        var indexed = new HashMap<UiMapId, List<Assignment>>();
        var rowIds = new HashSet<Long>();
        for (var assignment : assignments) {
            check(canceled);
            Objects.requireNonNull(assignment);
            if (!assignment.source().table().equals(scope.assignments())
                    || !rowIds.add(assignment.source().rowId())) {
                throw new IllegalArgumentException("Duplicate or mixed-source map assignment");
            }
            indexed.computeIfAbsent(assignment.uiMap(), ignored -> new ArrayList<>())
                    .add(assignment);
        }
        indexed.replaceAll((id, value) -> List.copyOf(value));
        childIndex.replaceAll((id, value) -> List.copyOf(value));
        this.assignments = Map.copyOf(indexed);
        children = Map.copyOf(childIndex);
        check(canceled);
    }

    public Scope scope() {
        return scope;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public long logicalBytes() {
        return logicalBytes;
    }

    public Optional<Node> node(UiMapId id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id)));
    }

    public List<Node> children(UiMapId id) {
        requireNode(id);
        return children.getOrDefault(id, List.of());
    }

    public Hierarchy hierarchy(UiMapId id) {
        var current = requireNode(id);
        var reversed = new ArrayList<Node>();
        var seen = new HashSet<UiMapId>();
        while (true) {
            if (!seen.add(current.id())) {
                return hierarchy(HierarchyState.CYCLE, reversed);
            }
            if (reversed.size() == MAX_DEPTH) {
                return hierarchy(HierarchyState.TOO_DEEP, reversed);
            }
            reversed.add(current);
            if (!current.parent().known()) {
                return hierarchy(HierarchyState.UNKNOWN_PARENT, reversed);
            }
            if (current.parent().id() == null) {
                return hierarchy(HierarchyState.COMPLETE, reversed);
            }
            var parent = byId.get(current.parent().id());
            if (parent == null) {
                return hierarchy(HierarchyState.MISSING_PARENT, reversed);
            }
            if (current.system() == null || !current.system().equals(parent.system())) {
                return hierarchy(HierarchyState.SYSTEM_MISMATCH, reversed);
            }
            current = parent;
        }
    }

    private static Hierarchy hierarchy(HierarchyState state, List<Node> reversed) {
        Collections.reverse(reversed);
        return new Hierarchy(state, reversed);
    }

    public Regions regions(UiMapId parentId, int pairLimit, BooleanSupplier canceled) {
        Objects.requireNonNull(canceled);
        check(canceled);
        if (pairLimit < 1 || pairLimit > MAX_PAIR_TESTS) {
            throw new IllegalArgumentException("Pair work limit");
        }
        var parent = requireNode(parentId);
        var childNodes = children(parentId);
        var issues = new ArrayList<Issue>();
        var result = new ArrayList<Region>();
        if (scope.maps().state() == ReadState.PARTIAL
                || scope.assignments().state() == ReadState.PARTIAL) {
            issues.add(new Issue(Reason.PARTIAL_TABLE, parentId));
        }
        if (!scope.assignments().readable()) {
            issues.add(new Issue(Reason.UNAVAILABLE_ASSIGNMENTS, parentId));
            return new Regions(RegionState.LIMITED, childNodes, result, issues, 0);
        }
        var parentProblem = flags(parent);
        if (parentProblem != null) {
            issues.add(new Issue(parentProblem, parentId));
            return new Regions(RegionState.LIMITED, childNodes, result, issues, 0);
        }
        var parents = assignments.getOrDefault(parentId, List.of());
        int tested = 0;
        for (var child : childNodes) {
            check(canceled);
            Reason problem = flags(child);
            if (parent.system() == null || !parent.system().equals(child.system())) {
                problem = Reason.MISSING_SYSTEM;
            }
            if (problem != null) {
                issues.add(new Issue(problem, child.id()));
                continue;
            }
            var childAssignments = assignments.getOrDefault(child.id(), List.of());
            int issuesBefore = issues.size();
            boolean parentWmo = false;
            int before = result.size();
            for (var childAssignment : childAssignments) {
                if (!childAssignment.unscoped()) {
                    issues.add(new Issue(Reason.WMO_CONTEXT, child.id()));
                    continue;
                }
                if (!childAssignment.ui().fullExtent()) {
                    issues.add(new Issue(Reason.CHILD_FRAME, child.id()));
                    continue;
                }
                for (var parentAssignment : parents) {
                    check(canceled);
                    if (tested == pairLimit) {
                        return refused(childNodes, Reason.WORK_LIMIT, parentId, tested);
                    }
                    tested++;
                    if (!parentAssignment.unscoped()) {
                        parentWmo = true;
                        continue;
                    }
                    if (!parentAssignment.worldMap().equals(childAssignment.worldMap())) {
                        continue;
                    }
                    var rectangle = intersect(childAssignment, parentAssignment);
                    if (rectangle == null) {
                        continue;
                    }
                    if (result.size() == MAX_REGIONS) {
                        return refused(childNodes, Reason.REGION_LIMIT, parentId, tested);
                    }
                    result.add(
                            new Region(
                                    child.id(),
                                    rectangle,
                                    childAssignment.source(),
                                    parentAssignment.source()));
                }
            }
            if (parentWmo) {
                issues.add(new Issue(Reason.WMO_CONTEXT, child.id()));
            }
            if (before == result.size() && issues.size() == issuesBefore) {
                issues.add(
                        new Issue(
                                childAssignments.isEmpty() || parents.isEmpty()
                                        ? Reason.NO_ASSIGNMENT
                                        : Reason.DISJOINT_FRAME,
                                child.id()));
            }
        }
        return new Regions(
                issues.isEmpty() ? RegionState.READY : RegionState.LIMITED,
                childNodes,
                result,
                issues,
                tested);
    }

    private static Regions refused(List<Node> children, Reason reason, UiMapId parent, int tested) {
        return new Regions(
                RegionState.UNAVAILABLE,
                children,
                List.of(),
                List.of(new Issue(reason, parent)),
                tested);
    }

    private static Reason flags(Node node) {
        if (node.flags() == null) {
            return Reason.UNKNOWN_FLAGS;
        }
        if ((node.flags() & NO_WORLD_POSITIONS) != 0) {
            return Reason.NO_WORLD_POSITIONS;
        }
        return (node.flags() & ~NON_FRAME_FLAGS) == 0 ? null : Reason.UNSUPPORTED_FLAGS;
    }

    private static Rect intersect(Assignment child, Assignment parent) {
        var a = child.world();
        var b = parent.world();
        var inset = parent.ui();
        double minX = Math.max(a.minX(), b.minX()), minY = Math.max(a.minY(), b.minY());
        double maxX = Math.min(a.maxX(), b.maxX()), maxY = Math.min(a.maxY(), b.maxY());
        if (minX >= maxX || minY >= maxY) {
            return null;
        }
        double left = project(maxY, b.minY(), b.maxY(), inset.minX(), inset.maxX());
        double right = project(minY, b.minY(), b.maxY(), inset.minX(), inset.maxX());
        double top = project(maxX, b.minX(), b.maxX(), inset.minY(), inset.maxY());
        double bottom = project(minX, b.minX(), b.maxX(), inset.minY(), inset.maxY());
        left = Math.max(inset.minX(), left);
        right = Math.min(inset.maxX(), right);
        top = Math.max(inset.minY(), top);
        bottom = Math.min(inset.maxY(), bottom);
        return left < right && top < bottom ? new Rect(left, top, right, bottom) : null;
    }

    private static double project(
            double value, double min, double max, double uiMin, double uiMax) {
        double t = (value - min) / (max - min);
        return t * uiMin + (1 - t) * uiMax;
    }

    private Node requireNode(UiMapId id) {
        var value = byId.get(Objects.requireNonNull(id));
        if (value == null) {
            throw new IllegalArgumentException("Unknown UiMap");
        }
        return value;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
