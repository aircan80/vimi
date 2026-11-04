package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.MapRoles;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
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

    /** Local prepared-source reads and selected-build resolution; must not download or prepare. */
    @FunctionalInterface
    interface Loader {
        MapRoles.Placement load(Cancellation token) throws Exception;
    }

    private final Map<MapRoles.Layer, CheckBox> toggles = new EnumMap<>(MapRoles.Layer.class);
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
        if (uiMapId == null) {
            status.setText(summary(placement, null, layers, List.of()));
            return;
        }
        var markers = placement.markers(uiMapId, layers);
        drawn = markers.size() > LIMIT ? markers.subList(0, LIMIT) : markers;
        for (var marker : drawn) {
            double x = box[0] + marker.x() * box[2], y = box[1] + marker.y() * box[3];
            var color = color(primary(marker.npc().layers(), layers));
            g.setFill(color);
            g.setStroke(Color.rgb(12, 16, 14, .9));
            g.setLineWidth(1.2);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.strokeOval(x - 4, y - 4, 8, 8);
        }
        status.setText(summary(placement, uiMapId, layers, markers));
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
        return text.append(" · faction, phase and quest availability not evaluated").toString();
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
        worker.shutdown();
        cancellation.shutdown();
    }
}
