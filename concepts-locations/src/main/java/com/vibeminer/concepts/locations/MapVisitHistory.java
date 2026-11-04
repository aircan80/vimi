package com.vibeminer.concepts.locations;

import com.vibeminer.concepts.locations.MapNavigationTypes.UiMapId;
import com.vibeminer.concepts.locations.MapNavigationTypes.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable bounded visits: entries retain IDs and small view state, never images or map graphs.
 */
public final class MapVisitHistory {
    public static final int MAX_ENTRIES = 64;

    public record Visit(UiMapId map, View view) {
        public Visit {
            Objects.requireNonNull(map);
            Objects.requireNonNull(view);
        }
    }

    private final MapNavigationGraph graph;
    private final List<Visit> visits;
    private final int index;

    private MapVisitHistory(MapNavigationGraph graph, List<Visit> visits, int index) {
        this.graph = graph;
        this.visits = List.copyOf(visits);
        this.index = index;
    }

    public static MapVisitHistory start(MapNavigationGraph graph, UiMapId id) {
        Objects.requireNonNull(graph);
        requireKnown(graph, id);
        return new MapVisitHistory(graph, List.of(new Visit(id, View.fit())), 0);
    }

    public Visit current() {
        return visits.get(index);
    }

    public int size() {
        return visits.size();
    }

    public boolean canBack() {
        return index > 0;
    }

    public boolean canForward() {
        return index + 1 < visits.size();
    }

    public MapNavigationGraph.Hierarchy hierarchy() {
        return graph.hierarchy(current().map());
    }

    public MapVisitHistory back() {
        return canBack() ? new MapVisitHistory(graph, visits, index - 1) : this;
    }

    public MapVisitHistory forward() {
        return canForward() ? new MapVisitHistory(graph, visits, index + 1) : this;
    }

    public MapVisitHistory up() {
        var parent = hierarchy().parent();
        return parent.isPresent() ? switchTo(parent.get()) : this;
    }

    public MapVisitHistory switchTo(UiMapId map) {
        requireKnown(graph, map);
        if (current().map().equals(map)) {
            return this;
        }
        var next = new ArrayList<>(visits.subList(Math.max(0, index + 2 - MAX_ENTRIES), index + 1));
        next.add(new Visit(map, View.fit()));
        return new MapVisitHistory(graph, next, next.size() - 1);
    }

    public MapVisitHistory view(View view) {
        Objects.requireNonNull(view);
        if (current().view().equals(view)) {
            return this;
        }
        var next = new ArrayList<>(visits);
        next.set(index, new Visit(current().map(), view));
        return new MapVisitHistory(graph, next, index);
    }

    private static void requireKnown(MapNavigationGraph graph, UiMapId id) {
        if (graph.node(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown UiMap visit");
        }
    }
}
