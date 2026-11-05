package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.MapRoles;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Quest giver, vendor, trainer and flight master markers for the Maps browser.
 *
 * <p>Roles come from one prepared Questie snapshot and positions are its recorded area percentages,
 * resolved through the selected build's map relationships. Faction reaction, phase, quest
 * availability and service inventories are not evaluated. One worker builds at most one placement
 * at a time; results for a retired source key or a closed overlay never publish. FX-owned apart
 * from the worker.
 */
final class MapRoleOverlay implements AutoCloseable {
    static final int LIMIT = 2048;
    /** Hover reach in pixels, the hit grid's cell size, and the most NPCs one hover lists. */
    static final double RADIUS = 6, CELL = 16;
    static final int HOVER_LIMIT = 5;

    /** Questie {@code friendlyToFaction} filter; "both" NPCs show for either faction. */
    enum Faction {
        ALL("All factions"),
        ALLIANCE("Alliance"),
        HORDE("Horde");

        final String label;

        Faction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One drawn marker at its canvas position. */
    record Hit(MapRoles.Marker marker, double x, double y) {}

    /** Local prepared-source reads and selected-build resolution; must not download or prepare. */
    @FunctionalInterface
    interface Loader {
        MapRoles.Placement load(Cancellation token) throws Exception;
    }

    private final Map<MapRoles.Layer, CheckBox> toggles = new EnumMap<>(MapRoles.Layer.class);
    private final ChoiceBox<Faction> faction = new ChoiceBox<>();
    /** Drawn markers bucketed by canvas cell, so a hover checks only nearby cells. */
    private HitGrid grid = HitGrid.EMPTY;
    private final Label status = new Label("Map overlays off");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final FlowPane controls = new FlowPane(8, 4);
    private final Loader loader;
    private final Supplier<Object> key;
    private final Runnable redraw;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
                    runnable -> {
                        var thread = new Thread(runnable, "map-role-overlay");
                        thread.setDaemon(true);
                        return thread;
                    });
    /** Lower cancellation callbacks may block or throw; they never run on FX. */
    private final java.util.concurrent.ExecutorService cancellation =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("map-role-cancel").factory());
    private MapRoles.Placement placement;
    private String failure;
    private Object placementKey;
    private Cancellation loading;
    private long generation;
    private boolean closed, running, rerun;
    private List<MapRoles.Marker> drawn = List.of();

    MapRoleOverlay(Loader loader, Supplier<Object> key, Runnable redraw) {
        this.loader = Objects.requireNonNull(loader);
        this.key = Objects.requireNonNull(key);
        this.redraw = Objects.requireNonNull(redraw);
        controls.setId("maps-role-overlays");
        controls.getChildren().add(new Label("Overlays"));
        for (var layer : MapRoles.Layer.values()) {
            var toggle = new CheckBox(label(layer));
            toggle.setId("maps-role-" + layer.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            toggle.setTextFill(color(layer));
            toggle.selectedProperty().addListener((o, before, on) -> changed(on));
            toggles.put(layer, toggle);
            controls.getChildren().add(toggle);
        }
        faction.getItems().setAll(Faction.values());
        faction.setValue(Faction.ALL);
        faction.setId("maps-role-faction");
        faction.setAccessibleText("Show role NPCs friendly to this faction");
        faction.valueProperty().addListener((o, before, after) -> {
            if (!closed && !layers().isEmpty()) redraw.run();
        });
        controls.getChildren().add(faction);
        activity.setPrefSize(18, 18);
        activity.setMaxSize(18, 18);
        activity.setAccessibleText("Placing map overlay markers");
        show(false);
        status.setId("maps-role-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        Tooltip.install(status, new Tooltip(
                "Roles from the build's prepared QuestieDB snapshot: quest givers from recorded quest"
                        + " starts, services from NPC flags. Positions are Questie area percentages"
                        + " resolved through this build's map assignments. Faction reaction, phase,"
                        + " quest availability and service inventories are not evaluated."));
        controls.getChildren().addAll(activity, status);
    }

    FlowPane controls() {
        return controls;
    }

    CheckBox toggle(MapRoles.Layer layer) {
        return toggles.get(layer);
    }

    Label status() {
        return status;
    }

    List<MapRoles.Marker> drawn() {
        return drawn;
    }

    ChoiceBox<Faction> faction() {
        return faction;
    }

    /** Whether an NPC recorded as {@code friendly} shows under {@code filter}. */
    static boolean visible(MapRoles.Friendly friendly, Faction filter) {
        return switch (filter) {
            case ALL -> true;
            case ALLIANCE -> friendly == MapRoles.Friendly.ALLIANCE
                    || friendly == MapRoles.Friendly.BOTH;
            case HORDE -> friendly == MapRoles.Friendly.HORDE || friendly == MapRoles.Friendly.BOTH;
        };
    }

    /**
     * Drawn markers within {@link #RADIUS} of a canvas point, nearest first, one per NPC, at most
     * {@link #HOVER_LIMIT}. Only the nine grid cells around the point are examined.
     */
    List<MapRoles.Marker> hits(double x, double y) {
        return grid.near(x, y);
    }

    /** Drawn marker positions in CELL-sized buckets; immutable once built. */
    static final class HitGrid {
        static final HitGrid EMPTY = new HitGrid(List.of());
        private final Map<Long, List<Hit>> buckets = new java.util.HashMap<>();

        HitGrid(List<Hit> hits) {
            for (var hit : hits)
                buckets.computeIfAbsent(cell((long) Math.floor(hit.x() / CELL), (long) Math.floor(hit.y() / CELL)),
                        k -> new java.util.ArrayList<>()).add(hit);
        }

        /** Within RADIUS, nearest first, one per NPC, at most HOVER_LIMIT; nine cells examined. */
        List<MapRoles.Marker> near(double x, double y) {
            if (buckets.isEmpty()) return List.of();
            long cx = (long) Math.floor(x / CELL), cy = (long) Math.floor(y / CELL);
            var near = new java.util.ArrayList<Hit>();
            for (long dx = -1; dx <= 1; dx++)
                for (long dy = -1; dy <= 1; dy++)
                    for (var hit : buckets.getOrDefault(cell(cx + dx, cy + dy), List.of()))
                        if (Math.hypot(hit.x() - x, hit.y() - y) <= RADIUS) near.add(hit);
            near.sort(java.util.Comparator.comparingDouble(h -> Math.hypot(h.x() - x, h.y() - y)));
            var result = new java.util.ArrayList<MapRoles.Marker>();
            var seen = new java.util.HashSet<Long>();
            for (var hit : near)
                if (seen.add(hit.marker().npc().id()) && result.size() < HOVER_LIMIT) result.add(hit.marker());
            return result;
        }
    }

    /** Hover text for the markers under a canvas point, or null when there are none. */
    String hoverText(double x, double y) {
        var markers = hits(x, y);
        return markers.isEmpty() || placement == null ? null : details(markers, placement.catalog());
    }

    private static long cell(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    /** Recorded facts for hovered markers; no local paths or derived claims. */
    static String details(List<MapRoles.Marker> markers, MapRoles.Catalog catalog) {
        var text = new StringBuilder();
        for (var marker : markers) {
            var npc = marker.npc();
            if (!text.isEmpty()) text.append("\n\n");
            text.append(npc.name() == null || npc.name().isBlank() ? "NPC " + npc.id() : npc.name());
            if (npc.subname() != null && !npc.subname().isBlank())
                text.append(" <").append(npc.subname()).append(">");
            text.append("\nNPC ").append(npc.id()).append(" · ");
            var roles = new java.util.ArrayList<String>();
            for (var layer : MapRoles.Layer.values())
                if (npc.layers().contains(layer)) roles.add(singular(layer));
            text.append(String.join(", ", roles));
            text.append("\nFaction: ").append(switch (npc.friendly()) {
                case ALLIANCE -> "Alliance";
                case HORDE -> "Horde";
                case BOTH -> "Alliance and Horde";
                case UNKNOWN -> "not recorded";
            });
            var at = marker.location().coordinates().getFirst();
            text.append("\nRecorded at ").append(at.map().kind() == LocationTypes.MapKind.AREA ? "AreaID " : "UiMap ")
                    .append(at.map().id()).append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f, %.1f", at.x(), at.y()));
            if (at.units() == LocationTypes.Units.PERCENT) text.append(" %");
        }
        if (markers.size() == HOVER_LIMIT) text.append("\n\nMore markers may overlap here.");
        return text.append("\n\nSource: QuestieDB ")
                .append(catalog.releaseTag() == null ? "untagged" : catalog.releaseTag())
                .append(" ").append(catalog.source().product()).toString();
    }

    static String singular(MapRoles.Layer layer) {
        return switch (layer) {
            case QUEST_GIVER -> "Quest giver";
            case VENDOR -> "Vendor";
            case TRAINER -> "Trainer";
            case FLIGHT_MASTER -> "Flight master";
        };
    }

    Set<MapRoles.Layer> layers() {
        var result = EnumSet.noneOf(MapRoles.Layer.class);
        toggles.forEach((layer, toggle) -> {
            if (toggle.isSelected()) result.add(layer);
        });
        return result;
    }

    private void changed(boolean on) {
        if (closed) return;
        // Turning the first layer on retries a failed placement instead of keeping a stale error.
        if (on && failure != null && layers().size() == 1) {
            failure = null;
            placementKey = null;
        }
        if (layers().isEmpty()) {
            drawn = List.of();
            status.setText("Map overlays off");
        }
        redraw.run();
    }

    /**
     * Draws markers for the presented UiMap inside {@code box} (left, top, width, height), or
     * starts a placement when the source key changed. Null {@code uiMapId} means nothing presented.
     */
    void paint(GraphicsContext g, double[] box, Long uiMapId) {
        drawn = List.of();
        grid = HitGrid.EMPTY;
        var layers = layers();
        if (closed || layers.isEmpty()) return;
        Object current = key.get();
        if (!Objects.equals(placementKey, current) || (placement == null && failure == null && loading == null)) {
            load(current);
            return;
        }
        if (placement == null) {
            if (failure != null) status.setText(failure);
            return;
        }
        var filter = faction.getValue() == null ? Faction.ALL : faction.getValue();
        if (uiMapId == null) {
            status.setText(summary(placement, null, layers, List.of(), filter, 0));
            return;
        }
        var markers = new java.util.ArrayList<MapRoles.Marker>();
        int unrecorded = 0;
        for (var marker : placement.markers(uiMapId, layers)) {
            if (visible(marker.npc().friendly(), filter)) markers.add(marker);
            else if (marker.npc().friendly() == MapRoles.Friendly.UNKNOWN) unrecorded++;
        }
        drawn = markers.size() > LIMIT ? List.copyOf(markers.subList(0, LIMIT)) : List.copyOf(markers);
        var hits = new java.util.ArrayList<Hit>(drawn.size());
        for (var marker : drawn) {
            double x = box[0] + marker.x() * box[2], y = box[1] + marker.y() * box[3];
            hits.add(new Hit(marker, x, y));
            var color = color(primary(marker.npc().layers(), layers));
            g.setFill(color);
            g.setStroke(Color.rgb(12, 16, 14, .9));
            g.setLineWidth(1.2);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.strokeOval(x - 4, y - 4, 8, 8);
        }
        grid = new HitGrid(hits);
        status.setText(summary(placement, uiMapId, layers, markers, filter, unrecorded));
    }

    /** Latest request wins: at most one placement runs; a newer key waits for it to drain. */
    private void load(Object current) {
        cancel(loading);
        loading = new Cancellation();
        placementKey = current;
        placement = null;
        failure = null;
        ++generation;
        show(true);
        status.setText("Reading service NPC roles and resolving their map positions…");
        if (running) {
            rerun = true;
            return;
        }
        submit();
    }

    private void submit() {
        var token = loading;
        long request = generation;
        running = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return loader.load(token);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, worker).whenComplete((result, error) -> Platform.runLater(() -> {
            running = false;
            if (closed) return;
            if (request != generation) {
                if (rerun) {
                    rerun = false;
                    submit();
                }
                return;
            }
            rerun = false;
            loading = null;
            show(false);
            placement = error == null ? result : null;
            failure = error == null ? null : "Map overlays unavailable: " + rootMessage(error);
            redraw.run();
        }));
    }

    /** The first enabled layer in declaration order decides a multi-role marker's color. */
    static MapRoles.Layer primary(Set<MapRoles.Layer> roles, Set<MapRoles.Layer> enabled) {
        for (var layer : MapRoles.Layer.values())
            if (roles.contains(layer) && enabled.contains(layer)) return layer;
        return roles.iterator().next();
    }

    static String label(MapRoles.Layer layer) {
        return switch (layer) {
            case QUEST_GIVER -> "Quest givers";
            case VENDOR -> "Vendors";
            case TRAINER -> "Trainers";
            case FLIGHT_MASTER -> "Flight masters";
        };
    }

    static Color color(MapRoles.Layer layer) {
        return switch (layer) {
            case QUEST_GIVER -> Color.rgb(242, 201, 76);
            case VENDOR -> Color.rgb(111, 207, 151);
            case TRAINER -> Color.rgb(86, 204, 242);
            case FLIGHT_MASTER -> Color.rgb(187, 107, 217);
        };
    }

    static String summary(MapRoles.Placement placement, Long uiMapId, Set<MapRoles.Layer> layers,
            List<MapRoles.Marker> markers) {
        return summary(placement, uiMapId, layers, markers, Faction.ALL, 0);
    }

    /** {@code markers} are those shown after the faction filter; {@code unrecorded} were hidden. */
    static String summary(MapRoles.Placement placement, Long uiMapId, Set<MapRoles.Layer> layers,
            List<MapRoles.Marker> markers, Faction filter, int unrecorded) {
        var catalog = placement.catalog();
        var text = new StringBuilder("Overlays · QuestieDB ")
                .append(catalog.releaseTag() == null ? "untagged" : catalog.releaseTag())
                .append(" ").append(catalog.source().product());
        if (uiMapId != null) {
            var counts = new EnumMap<MapRoles.Layer, Integer>(MapRoles.Layer.class);
            for (var marker : markers)
                for (var layer : marker.npc().layers())
                    if (layers.contains(layer)) counts.merge(layer, 1, Integer::sum);
            text.append(" · positions on this map:");
            for (var layer : layers)
                text.append(" ").append(counts.getOrDefault(layer, 0)).append(" ")
                        .append(label(layer).toLowerCase(java.util.Locale.ROOT)).append(",");
            text.setLength(text.length() - 1);
            if (markers.size() > LIMIT)
                text.append(" · ").append(markers.size() - LIMIT).append(" beyond the ")
                        .append(LIMIT).append("-marker cap");
        }
        text.append(" · ").append(catalog.npcs().size()).append(" role NPCs, ")
                .append(placement.locations()).append(" recorded positions");
        long withoutPositions = catalog.npcs().stream().filter(n -> n.locations().isEmpty()).count();
        if (withoutPositions > 0)
            text.append(" · ").append(withoutPositions).append(" role NPCs without recorded positions");
        if (placement.unplaced() > 0)
            text.append(" · ").append(placement.unplaced()).append(" positions not placed on any map");
        if (placement.ambiguous() > 0)
            text.append(" · ").append(placement.ambiguous())
                    .append(" map placements with conflicting assignments not drawn");
        if (catalog.state() == LocationTypes.State.PARTIAL || !placement.issues().isEmpty())
            text.append(" · partial: ").append(placement.issues().isEmpty()
                    ? "source coverage incomplete" : placement.issues().getFirst());
        if (filter == Faction.ALL)
            return text.append(" · faction, phase and quest availability not evaluated").toString();
        text.append(" · ").append(filter.label).append("-friendly NPCs (recorded Questie faction)");
        if (unrecorded > 0)
            text.append(" · ").append(unrecorded)
                    .append(" positions with no recorded faction hidden");
        return text.append(" · phase and quest availability not evaluated").toString();
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null && error instanceof java.util.concurrent.CompletionException)
            error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void cancel(Cancellation token) {
        if (token != null) cancellation.execute(() -> {
            try {
                token.cancel();
            } catch (RuntimeException ignored) {
                // The placement is already retired; a lower cancellation failure has no reader.
            }
        });
    }

    private void show(boolean busy) {
        activity.setVisible(busy);
        activity.setManaged(busy);
    }

    @Override
    public void close() {
        closed = true;
        cancel(loading);
        loading = null;
        placement = null;
        drawn = List.of();
        grid = HitGrid.EMPTY;
        worker.shutdown();
        cancellation.shutdown();
    }
}
