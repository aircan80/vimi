package com.vimi.gui;

import com.vibeminer.access.Cancellation;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Quest giver, vendor, trainer and flight master markers on the World explorer's minimap.
 *
 * <p>Positions are the Maps overlay's placements carried to world yards through the selected
 * build's recorded UiMapAssignment frames ({@link MapRoles#world}). Questie records no height, so
 * these markers are drawn on the 2D minimap only, never in the 3D viewport. FX-owned apart from
 * the worker; results for a retired key or a closed overlay never publish.
 */
final class WorldRoleOverlay implements AutoCloseable {
    /** What one load produces: the role catalog (for details) and its world placement. */
    record Loaded(MapRoles.Catalog catalog, MapRoles.WorldPlacement world) {
        Loaded {
            Objects.requireNonNull(catalog);
            Objects.requireNonNull(world);
        }
    }

    /** Local prepared-source reads and selected-build frames for one world map. */
    @FunctionalInterface
    interface Loader {
        Loaded load(long worldMapId, Cancellation token) throws Exception;
    }

    /** Converts world yards to minimap canvas coordinates (before rotation). */
    @FunctionalInterface
    interface Projection {
        double[] toCanvas(double worldX, double worldY);
    }

    static final double DOT = 3;

    private final Map<MapRoles.Layer, CheckBox> toggles = new EnumMap<>(MapRoles.Layer.class);
    private final ChoiceBox<MapRoleOverlay.Faction> faction = new ChoiceBox<>();
    private final Label status = new Label("Minimap role markers off");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final FlowPane controls = new FlowPane(8, 4);
    private final Loader loader;
    private final Supplier<Object> key;
    private Runnable redraw = () -> {};
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(2),
                    runnable -> {
                        var thread = new Thread(runnable, "world-role-overlay");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ExecutorService cancellation =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-role-cancel").factory());
    private Loaded loaded;
    private String failure;
    private List<Object> loadedKey;
    private Cancellation loading;
    private long generation;
    private boolean closed, running, rerun;
    private MapRoleOverlay.HitGrid grid = MapRoleOverlay.HitGrid.EMPTY;
    private List<MapRoles.WorldMarker> drawn = List.of();

    WorldRoleOverlay(Loader loader, Supplier<Object> key) {
        this.loader = Objects.requireNonNull(loader);
        this.key = Objects.requireNonNull(key);
        controls.setId("world-role-overlays");
        controls.getChildren().add(new Label("Minimap roles"));
        for (var layer : MapRoles.Layer.values()) {
            var toggle = new CheckBox(MapRoleOverlay.label(layer));
            toggle.setId(
                    "world-role-" + layer.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            toggle.setTextFill(MapRoleOverlay.color(layer));
            toggle.selectedProperty().addListener((o, before, on) -> changed(on));
            toggles.put(layer, toggle);
            controls.getChildren().add(toggle);
        }
        faction.getItems().setAll(MapRoleOverlay.Faction.values());
        faction.setValue(MapRoleOverlay.Faction.ALL);
        faction.setId("world-role-faction");
        faction.setAccessibleText("Show minimap role NPCs friendly to this faction");
        faction.valueProperty().addListener((o, before, after) -> {
            if (!closed && !layers().isEmpty()) redraw.run();
        });
        activity.setPrefSize(18, 18);
        activity.setMaxSize(18, 18);
        activity.setAccessibleText("Placing minimap role markers");
        show(false);
        status.setId("world-role-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        Tooltip.install(status, new Tooltip(
                "Roles from the build's prepared QuestieDB snapshot, placed on this build's maps and"
                        + " carried to world yards through its recorded map assignments. Questie"
                        + " records no height, so they are shown on the minimap only."));
        controls.getChildren().addAll(faction, activity, status);
    }

    /** The minimap's repaint; set once by the minimap that draws this overlay. */
    void bindRedraw(Runnable value) {
        redraw = Objects.requireNonNull(value);
    }

    FlowPane controls() {
        return controls;
    }

    CheckBox toggle(MapRoles.Layer layer) {
        return toggles.get(layer);
    }

    ChoiceBox<MapRoleOverlay.Faction> faction() {
        return faction;
    }

    Label status() {
        return status;
    }

    List<MapRoles.WorldMarker> drawn() {
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
        if (on && failure != null && layers().size() == 1) {
            failure = null;
            loadedKey = null;
        }
        if (layers().isEmpty()) {
            drawn = List.of();
            grid = MapRoleOverlay.HitGrid.EMPTY;
            status.setText("Minimap role markers off");
        }
        redraw.run();
    }

    /**
     * Draws the markers of {@code worldMapId} inside the minimap circle. {@code rotation} (radians)
     * is the minimap's own rotation about {@code center}, applied by the caller to the graphics
     * context and here to the hover grid so hit tests use the screen positions.
     */
    void paint(
            GraphicsContext g,
            long worldMapId,
            Projection projection,
            double center,
            double radius,
            double rotation) {
        drawn = List.of();
        grid = MapRoleOverlay.HitGrid.EMPTY;
        var layers = layers();
        if (closed || layers.isEmpty()) return;
        var current = java.util.Arrays.asList(key.get(), worldMapId);
        if (!current.equals(loadedKey) || loaded == null && failure == null && loading == null) {
            load(current, worldMapId);
            return;
        }
        if (loaded == null) {
            if (failure != null) status.setText(failure);
            return;
        }
        var filter = faction.getValue() == null ? MapRoleOverlay.Faction.ALL : faction.getValue();
        var shown = new ArrayList<MapRoles.WorldMarker>();
        var hits = new ArrayList<MapRoleOverlay.Hit>();
        int onMap = 0, hidden = 0;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        for (var marker : loaded.world().markers()) {
            var npc = marker.marker().npc();
            if (npc.layers().stream().noneMatch(layers::contains)) continue;
            onMap++;
            if (!MapRoleOverlay.visible(npc.friendly(), filter)) {
                hidden++;
                continue;
            }
            var at = projection.toCanvas(marker.x(), marker.y());
            double dx = at[0] - center, dy = at[1] - center;
            if (dx * dx + dy * dy > (radius - DOT) * (radius - DOT)) continue;
            g.setFill(MapRoleOverlay.color(MapRoleOverlay.primary(npc.layers(), layers)));
            g.setStroke(Color.rgb(12, 16, 14, .9));
            g.setLineWidth(1);
            g.fillOval(at[0] - DOT, at[1] - DOT, 2 * DOT, 2 * DOT);
            g.strokeOval(at[0] - DOT, at[1] - DOT, 2 * DOT, 2 * DOT);
            shown.add(marker);
            // Screen position after the minimap's rotation about its center.
            hits.add(new MapRoleOverlay.Hit(
                    marker.marker(), center + dx * cos - dy * sin, center + dx * sin + dy * cos));
        }
        drawn = List.copyOf(shown);
        grid = new MapRoleOverlay.HitGrid(hits);
        status.setText(summary(loaded, layers, filter, shown.size(), onMap, hidden));
    }

    /** Hover text for the markers under a minimap canvas point, or null. */
    String hoverText(double x, double y) {
        var near = grid.near(x, y);
        if (near.markers().isEmpty() || loaded == null) return null;
        return MapRoleOverlay.details(near.markers(), near.more(), loaded.catalog())
                + "\nMinimap position: this build's map assignment carried to world yards.";
    }

    static String summary(Loaded loaded, Set<MapRoles.Layer> layers,
            MapRoleOverlay.Faction filter, int inView, int onMap, int hidden) {
        var catalog = loaded.catalog();
        var world = loaded.world();
        var text = new StringBuilder("Minimap roles · QuestieDB ")
                .append(catalog.releaseTag() == null ? "untagged" : catalog.releaseTag())
                .append(" ").append(catalog.source().product())
                .append(" · ").append(inView).append(" in view of ").append(onMap)
                .append(" on this map");
        if (filter != MapRoleOverlay.Faction.ALL) {
            text.append(" (").append(filter.label).append("-friendly");
            if (hidden > 0) text.append("; ").append(hidden).append(" hidden by the faction filter");
            text.append(")");
        }
        int unplaced = world.outside() + world.ambiguous() + world.degenerate();
        if (unplaced > 0)
            text.append(" · ").append(unplaced)
                    .append(unplaced == 1 ? " marker" : " markers")
                    .append(" without a unique world point (")
                    .append(world.outside()).append(" outside every frame, ")
                    .append(world.ambiguous()).append(" conflicting, ")
                    .append(world.degenerate()).append(" degenerate)");
        if (!world.issues().isEmpty()) text.append(" · partial: ").append(world.issues().getFirst());
        return text.append(" · minimap only: Questie positions record no height").toString();
    }

    private void load(List<Object> current, long worldMapId) {
        cancel(loading);
        loading = new Cancellation();
        loadedKey = current;
        loaded = null;
        failure = null;
        ++generation;
        show(true);
        status.setText("Reading role NPCs and carrying them to this map's world yards…");
        if (running) {
            rerun = true;
            return;
        }
        submit(worldMapId);
    }

    private void submit(long worldMapId) {
        var token = loading;
        long request = generation;
        running = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return loader.load(worldMapId, token);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, worker).whenComplete((result, error) -> Platform.runLater(() -> {
            running = false;
            if (closed) return;
            if (request != generation) {
                if (rerun) {
                    rerun = false;
                    submit((Long) loadedKey.get(1));
                }
                return;
            }
            rerun = false;
            loading = null;
            show(false);
            loaded = error == null ? result : null;
            failure = error == null ? null : "Minimap role markers unavailable: " + rootMessage(error);
            redraw.run();
        }));
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null && error instanceof CompletionException)
            error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void cancel(Cancellation token) {
        if (token != null) cancellation.execute(() -> {
            try {
                token.cancel();
            } catch (RuntimeException ignored) {
                // The load is already retired; a lower cancellation failure has no reader.
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
        loaded = null;
        drawn = List.of();
        grid = MapRoleOverlay.HitGrid.EMPTY;
        worker.shutdown();
        cancellation.shutdown();
    }
}
