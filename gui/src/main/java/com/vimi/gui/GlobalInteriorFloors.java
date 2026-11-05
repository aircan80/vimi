package com.vimi.gui;

import com.vibeminer.concepts.locations.MapTypes;
import com.vibeminer.rendering.bgfx.WorldGlobalTransport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Floors or map layers of one global interior, linked only through exact recorded keys: a
 * group-scoped UiMapAssignment row ({@code WMOGroupID != 0}, {@code WMODoodadPlacementID == 0})
 * whose WMO group ID is recorded by exactly one MOGP header of the drawn WMO. Every other row is
 * reported with a reason. Floors are never inferred from height, bounds or group order.
 */
record GlobalInteriorFloors(List<Floor> floors, List<Unlinked> unlinked, String summary) {
    /** Why an assignment row was not linked to a group. */
    enum Reason {
        NO_RECORDED_GROUP("no group in this interior records its WMO group ID"),
        AMBIGUOUS_GROUP("more than one group in this interior records its WMO group ID"),
        PLACEMENT_SCOPED("it also names a WMO placement, which is not matched yet"),
        UI_MAP_UNAVAILABLE("its UiMap record is unavailable"),
        UNREADABLE("its group, placement or UiMap fields could not be read"),
        OVER_LIMIT("it is beyond the per-map assignment list limit");

        final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    /** At most this many example WMO group IDs are kept per reason. */
    static final int MAX_EXAMPLES = 8;

    /** Count of assignment rows not linked for one reason, with a few example WMO group IDs. */
    record Unlinked(Reason reason, int count, List<Long> exampleGroupIds) {
        Unlinked {
            Objects.requireNonNull(reason);
            exampleGroupIds = List.copyOf(exampleGroupIds);
        }

        String text() {
            return count
                    + (count == 1 ? " assignment" : " assignments")
                    + " not linked: "
                    + reason.text
                    + (exampleGroupIds.isEmpty()
                            ? ""
                            : " (WMO group ID "
                                    + String.join(
                                            ", ",
                                            exampleGroupIds.stream().map(String::valueOf).toList())
                                    + (count > exampleGroupIds.size() ? ", …" : "")
                                    + ")");
        }
    }

    /**
     * One UiMap with at least one exactly linked group. {@code depth} is its nesting under another
     * listed floor through the recorded ParentUiMapID; {@code bounds} is the union of its groups'
     * usable boxes, or null when none can frame a camera.
     */
    record Floor(
            long uiMapId,
            String name,
            Integer type,
            Long parentId,
            String parentName,
            int depth,
            List<GlobalInteriorOutline.Group> groups,
            List<Long> assignmentIds,
            float[] bounds) {
        Floor {
            groups = List.copyOf(groups);
            assignmentIds = List.copyOf(assignmentIds);
            bounds = bounds == null ? null : bounds.clone();
        }

        @Override
        public float[] bounds() {
            return bounds == null ? null : bounds.clone();
        }

        String label() {
            return "  ".repeat(depth) + name + " · UiMap #" + uiMapId;
        }

        String details() {
            var text = new StringBuilder(typeText(type));
            if (parentId != null && parentId != 0) {
                text.append(" · parent ")
                        .append(parentName == null ? "UiMap" : parentName)
                        .append(" #")
                        .append(parentId);
            }
            text.append(" · groups ");
            text.append(
                    String.join(
                            ", ",
                            groups.stream()
                                    .map(
                                            g ->
                                                    g.ordinal()
                                                            + (g.name() == null || g.name().isEmpty()
                                                                    ? ""
                                                                    : " " + g.name()))
                                    .toList()));
            if (bounds == null) {
                text.append(" · bounds outside the camera range");
            }
            return text.toString();
        }

        /** Inspection framing of the floor's groups; null when they cannot frame a camera. */
        WorldGlobalTransport.Camera camera() {
            if (bounds == null) {
                return null;
            }
            try {
                return WorldGlobalTransport.Camera.inspecting(bounds);
            } catch (IllegalArgumentException outside) {
                return null;
            }
        }
    }

    GlobalInteriorFloors {
        floors = List.copyOf(floors);
        unlinked = List.copyOf(unlinked);
        Objects.requireNonNull(summary);
    }

    static final String READING = "Floors: reading group-scoped UiMap assignments…";

    /**
     * The whole off-FX lookup: resolves the source, reads its group assignments and links them.
     * Never throws; any failure (including an Error) becomes a not-linked result, and null is
     * returned only when {@code cancel} was canceled, so nothing is published after close.
     */
    static GlobalInteriorFloors lookup(
            java.util.function.Supplier<com.vibeminer.concepts.locations.BuildLocationMaps> maps,
            long mapId,
            GlobalInteriorOutline outline,
            com.vibeminer.access.Cancellation cancel) {
        GlobalInteriorFloors result;
        try {
            var source = maps == null ? null : maps.get();
            result =
                    source == null
                            ? notLinked("UiMap tables are not ready for this build")
                            : link(outline, source.wmoGroupAssignments(mapId, cancel).join());
        } catch (Throwable failed) {
            result = notLinked("group-scoped UiMap assignments could not be read");
        }
        return cancel.isCanceled() ? null : result;
    }

    /** No floors and a reason, keeping the unknown-by-default wording. */
    static GlobalInteriorFloors notLinked(String reason) {
        return new GlobalInteriorFloors(
                List.of(), List.of(), "Floors: not linked · " + Objects.requireNonNull(reason));
    }

    private static final String[] TYPES = {
        "cosmic", "world", "continent", "zone", "dungeon", "micro", "orphan"
    };

    /** Recorded UiMap.Type with the commonly documented values named; others stay raw. */
    static String typeText(Integer type) {
        if (type == null) {
            return "UiMap type unknown";
        }
        return "UiMap type "
                + type
                + (type >= 0 && type < TYPES.length ? " (" + TYPES[type] + ")" : "");
    }

    /** Links recorded assignments to recorded groups by exact WMO group ID only. */
    static GlobalInteriorFloors link(
            GlobalInteriorOutline outline, MapTypes.WmoGroupAssignments recorded) {
        Objects.requireNonNull(outline);
        Objects.requireNonNull(recorded);
        if (!outline.groupsReadable()) {
            return notLinked("the recorded groups of this interior could not be read");
        }
        if (recorded.state() == MapTypes.State.UNSUPPORTED
                || recorded.state() == MapTypes.State.UNAVAILABLE) {
            return notLinked(
                    recorded.message() == null
                            ? "group-scoped UiMap assignments are unavailable"
                            : recorded.message());
        }
        var byGroupId = new LinkedHashMap<Long, List<GlobalInteriorOutline.Group>>();
        for (var group : outline.groups()) {
            if (group.recordedGroupId() != 0) {
                byGroupId.computeIfAbsent(group.recordedGroupId(), k -> new ArrayList<>()).add(group);
            }
        }
        var counts = new EnumMap<Reason, Integer>(Reason.class);
        var examples = new EnumMap<Reason, List<Long>>(Reason.class);
        var linked = new TreeMap<Long, Linked>();
        for (var assignment : recorded.assignments()) {
            Reason reason = null;
            List<GlobalInteriorOutline.Group> matches = List.of();
            if (assignment.wmoDoodadPlacementId() != 0) {
                reason = Reason.PLACEMENT_SCOPED;
            } else if (assignment.map() == null) {
                reason = Reason.UI_MAP_UNAVAILABLE;
            } else {
                matches = byGroupId.getOrDefault(assignment.wmoGroupId(), List.of());
                if (matches.isEmpty()) {
                    reason = Reason.NO_RECORDED_GROUP;
                } else if (matches.size() > 1) {
                    reason = Reason.AMBIGUOUS_GROUP;
                }
            }
            if (reason != null) {
                counts.merge(reason, 1, Integer::sum);
                var list = examples.computeIfAbsent(reason, k -> new ArrayList<>());
                if (list.size() < MAX_EXAMPLES && !list.contains(assignment.wmoGroupId())) {
                    list.add(assignment.wmoGroupId());
                }
                continue;
            }
            var floor = linked.computeIfAbsent(assignment.uiMapId(), id -> new Linked(assignment.map()));
            floor.groups.add(matches.getFirst());
            floor.assignments.add(assignment.assignmentId());
        }
        if (recorded.unreadable() > 0) {
            counts.merge(Reason.UNREADABLE, recorded.unreadable(), Integer::sum);
        }
        if (recorded.overLimit() > 0) {
            counts.merge(Reason.OVER_LIMIT, recorded.overLimit(), Integer::sum);
        }
        var unlinked = new ArrayList<Unlinked>();
        int unlinkedRows = 0;
        for (var entry : counts.entrySet()) {
            unlinked.add(
                    new Unlinked(
                            entry.getKey(),
                            entry.getValue(),
                            examples.getOrDefault(entry.getKey(), List.of())));
            unlinkedRows += entry.getValue();
        }
        var parentNames = new java.util.HashMap<Long, String>();
        for (var parent : recorded.parents()) {
            parentNames.put(parent.id(), parent.name());
        }
        var floors = new ArrayList<Floor>(linked.size());
        var placed = new HashSet<Long>();
        var cyclic = new HashSet<Long>();
        for (var id : linked.keySet()) {
            if (cyclic(linked, id)) {
                cyclic.add(id);
            }
        }
        for (var entry : linked.entrySet()) {
            var parent = entry.getValue().map.parentId();
            boolean nested =
                    parent != null && linked.containsKey(parent) && !cyclic.contains(entry.getKey());
            if (!nested) {
                place(entry.getKey(), 0, linked, cyclic, parentNames, floors, placed);
            }
        }
        // Parent cycles among listed floors: list the rest flat rather than dropping them.
        for (var id : linked.keySet()) {
            if (!placed.contains(id)) {
                place(id, 0, linked, cyclic, parentNames, floors, placed);
            }
        }
        return new GlobalInteriorFloors(
                floors, unlinked, summary(floors.size(), linked, unlinkedRows, recorded));
    }

    private static final class Linked {
        final MapTypes.MapInfo map;
        final Set<GlobalInteriorOutline.Group> groups = new java.util.LinkedHashSet<>();
        final List<Long> assignments = new ArrayList<>();

        Linked(MapTypes.MapInfo map) {
            this.map = map;
        }
    }

    /** True only when {@code start} itself lies on a parent cycle among the listed floors. */
    private static boolean cyclic(Map<Long, Linked> linked, long start) {
        var seen = new HashSet<Long>();
        Long current = linked.get(start).map.parentId();
        while (current != null && linked.containsKey(current)) {
            if (current == start) {
                return true;
            }
            if (!seen.add(current)) {
                return false;
            }
            current = linked.get(current).map.parentId();
        }
        return false;
    }

    private static void place(
            long id,
            int depth,
            Map<Long, Linked> linked,
            Set<Long> cyclic,
            Map<Long, String> parentNames,
            List<Floor> out,
            Set<Long> placed) {
        if (!placed.add(id)) {
            return;
        }
        var floor = linked.get(id);
        var groups =
                floor.groups.stream()
                        .sorted(java.util.Comparator.comparingInt(GlobalInteriorOutline.Group::ordinal))
                        .toList();
        var parent = floor.map.parentId();
        String parentName =
                parent == null
                        ? null
                        : linked.containsKey(parent)
                                ? linked.get(parent).map.name()
                                : parentNames.get(parent);
        out.add(
                new Floor(
                        id,
                        floor.map.name(),
                        floor.map.type(),
                        parent,
                        parentName,
                        depth,
                        groups,
                        floor.assignments,
                        union(groups)));
        for (var child : linked.entrySet()) {
            if (Objects.equals(child.getValue().map.parentId(), id)
                    && !cyclic.contains(child.getKey())) {
                place(child.getKey(), depth + 1, linked, cyclic, parentNames, out, placed);
            }
        }
    }

    /** Union of the groups' usable (already clipped) boxes; null when none is usable. */
    static float[] union(List<GlobalInteriorOutline.Group> groups) {
        float[] result = null;
        for (var group : groups) {
            var b = group.bounds();
            if (b == null) {
                continue;
            }
            if (result == null) {
                result = b;
            } else {
                for (int axis = 0; axis < 3; axis++) {
                    result[axis] = Math.min(result[axis], b[axis]);
                    result[axis + 3] = Math.max(result[axis + 3], b[axis + 3]);
                }
            }
        }
        return result;
    }

    private static String summary(
            int floors, Map<Long, Linked> linked, int unlinked, MapTypes.WmoGroupAssignments a) {
        int rows = linked.values().stream().mapToInt(l -> l.assignments.size()).sum();
        String partial =
                a.state() == MapTypes.State.PARTIAL
                        ? " · some assignment or UiMap records were incomplete"
                        : "";
        if (floors == 0) {
            return "Floors: not linked · "
                    + (unlinked == 0
                            ? "no group-scoped UiMap assignment names this map"
                            : "no group-scoped UiMap assignment matches a recorded group exactly")
                    + partial;
        }
        return "Floors: "
                + floors
                + (floors == 1 ? " UiMap layer" : " UiMap layers")
                + " linked by "
                + rows
                + (rows == 1 ? " exact WMO group assignment" : " exact WMO group assignments")
                + (unlinked == 0 ? "" : " · " + unlinked + " not linked")
                + " · listed by UiMap ID within each level, not by height"
                + partial;
    }
}
