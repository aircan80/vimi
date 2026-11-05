package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainRenderer;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Static NPC spawn markers projected with the explorer's own camera over the presented frame.
 *
 * <p>Only declared world-yard points with a recorded height are drawn; points without height are
 * counted, never snapped to a guessed floor. Markers are not depth tested against terrain. One
 * worker builds at most one source index at a time; results from a retired source revision or a
 * closed overlay never publish. FX-owned apart from the worker.
 */
final class WorldSpawnOverlay extends Pane implements AutoCloseable {
    static final double RADIUS = 300, LABEL_DEPTH = 120;
    static final int LIMIT = 256, LABELS = 16;

    /** Local prepared-source read; must not download or prepare data. */
    @FunctionalInterface
    interface Loader {
        WorldSpawns.Index load(Cancellation token) throws Exception;
    }

    /** What the viewport presents: the camera, its client Map ID and the frame's pixel size. */
    record View(TerrainRenderer.Camera camera, long mapId, double frameWidth, double frameHeight) {
        View {
            Objects.requireNonNull(camera);
        }
    }

    /** One marker in viewport pixels. */
    record Marker(WorldSpawns.Spawn spawn, double x, double footY, double headY, double depth) {}

    /**
     * A clicked marker's declared point, its NPC record from the same snapshot and its cached
     * creature model outcome, each null when not known (yet).
     */
    record Picked(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, WorldSpawnModels.Resolved model) {
        Picked {
            Objects.requireNonNull(spawn);
        }
    }

    private final Canvas canvas = new Canvas();
    private final CheckBox enabled = new CheckBox("NPC spawns");
    private final Label status = new Label("NPC spawn markers off");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final HBox controls = new HBox(8, enabled, activity, status);
    private final Loader loader;
    private final Supplier<Object> revision;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
                    runnable -> {
                        var thread = new Thread(runnable, "world-spawn-index");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AnimationTimer timer;
    private Supplier<View> views = () -> null;
    private WorldSpawns.Index index;
    private Object indexRevision;
    private Cancellation loading;
    private long generation;
    private View drawn;
    private WorldSpawns.Index drawnIndex;
    private List<Marker> markers = List.of();
    private double lastWidth, lastHeight;
    private WorldSpawns.Nearby lastNearby;
    private int lastCapped;
    private WorldSpawnModels models;
    private boolean closed, running, rerun, suspended;

    WorldSpawnOverlay(Loader loader, Supplier<Object> revision) {
        this.loader = Objects.requireNonNull(loader);
        this.revision = Objects.requireNonNull(revision);
        setId("world-spawn-overlay");
        setMouseTransparent(true);
        setPickOnBounds(false);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        enabled.setId("world-spawns");
        enabled.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        status.setId("world-spawn-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        activity.setPrefSize(18, 18);
        activity.setMaxSize(18, 18);
        activity.setVisible(false);
        activity.setManaged(false);
        activity.setAccessibleText("Indexing declared NPC spawn points");
        Tooltip.install(status, new Tooltip(
                "Declared spawn points in world yards from the selected prepared source."
                        + " Points without a recorded height are counted but not placed."
                        + " Markers are not occluded by terrain; phase, patrols and live"
                        + " server state are unknown."));
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                refresh();
            }
        };
        enabled.selectedProperty().addListener((observable, before, on) -> {
            if (on) {
                if (!suspended) timer.start();
            } else {
                timer.stop();
                clearCanvas();
                status.setText("NPC spawn markers off");
            }
        });
    }

    /** Explorer controls row for the World footer. */
    HBox controls() {
        return controls;
    }

    /** Supplies the presented view, or null while no explorer frame is visible. */
    void bind(Supplier<View> views) {
        this.views = Objects.requireNonNull(views);
    }

    List<Marker> markers() {
        return markers;
    }

    /**
     * The drawn marker under an overlay-local point. Empty while markers are hidden, so a hidden
     * marker is never inspected. FX thread; reads only the drawn snapshot.
     */
    Optional<Picked> pick(double x, double y) {
        var current = index;
        if (closed || suspended || !enabled.isSelected() || current == null || drawn == null)
            return Optional.empty();
        return WorldPick.marker(markers, x, y)
                .map(m -> new Picked(m.spawn(), current.npc(m.spawn().npcId()).orElse(null),
                        models == null ? null : models.cached(m.spawn().npcId()).orElse(null)));
    }

    /**
     * Resolves creature model outcomes for drawn markers and counts them in the status line. FX
     * thread; the overlay closes it.
     */
    void attachModels(WorldSpawnModels resolver) {
        if (models != null || closed) throw new IllegalStateException("Spawn models already attached");
        models = Objects.requireNonNull(resolver);
        resolver.listen(npcId -> {
            if (drawn != null && index == drawnIndex && index != null && index.source().isPresent())
                status.setText(summary(index, lastNearby, markers.size(), lastCapped) + modelSummary());
        });
    }

    /** Model outcomes of the drawn markers, if a resolver is attached. */
    Optional<WorldSpawnModels> models() {
        return Optional.ofNullable(models);
    }

    /** Drawn head radius for a marker at an eye depth. */
    static double headRadius(double depth) {
        return Math.clamp(240 / depth, 2.5, 7);
    }

    CheckBox toggle() {
        return enabled;
    }

    Label status() {
        return status;
    }

    /** Explorer hidden or left: stop polling and drop markers; the index is retained. */
    void suspend() {
        suspended = true;
        timer.stop();
        clearCanvas();
    }

    /** Explorer showing frames again: poll while the toggle is on. */
    void resume() {
        if (closed) return;
        suspended = false;
        if (enabled.isSelected()) timer.start();
    }

    void refresh() {
        if (closed || suspended || !enabled.isSelected()) return;
        var view = views.get();
        if (view == null) {
            if (drawn != null) clearCanvas();
            return;
        }
        Object current = revision.get();
        if (index == null || !Objects.equals(indexRevision, current)) {
            if (loading == null || !Objects.equals(indexRevision, current)) load(current);
            if (index == null) return;
        }
        if (view.equals(drawn) && index == drawnIndex
                && canvas.getWidth() == lastWidth && canvas.getHeight() == lastHeight) return;
        draw(view);
    }


    /** Latest request wins: at most one load runs; a newer revision waits for it to drain. */
    private void load(Object current) {
        if (loading != null) loading.cancel();
        loading = new Cancellation();
        indexRevision = current;
        index = null;
        if (models != null) models.refresh();
        ++generation;
        clearCanvas();
        show(true);
        status.setText("Indexing declared NPC spawn points…");
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
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, worker).whenComplete((result, failure) -> Platform.runLater(() -> {
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
            if (failure != null) WorldDiagnostics.warn("World NPC spawn source could not be read", failure);
            index = failure == null ? result : WorldSpawns.unavailable(LocationTypes.State.UNKNOWN,
                    "Spawn source could not be read: " + rootMessage(failure));
            drawn = null;
            if (!enabled.isSelected() || suspended) return;
            refresh();
            if (drawn == null && index != null) status.setText(summary(index, null, 0, 0));
        }));
    }

    private void draw(View view) {
        drawn = view;
        drawnIndex = index;
        lastWidth = canvas.getWidth();
        lastHeight = canvas.getHeight();
        var g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, lastWidth, lastHeight);
        if (index.source().isEmpty()) {
            markers = List.of();
            status.setText(summary(index, null, 0, 0));
            return;
        }
        var camera = view.camera();
        // Cull to the frame first so off-screen points never consume the marker budget.
        var nearby = index.near(view.mapId(), camera.x(), camera.y(), RADIUS, WorldSpawns.MAX_NEARBY);
        var visible = project(nearby.placed(), camera, view.frameWidth(), view.frameHeight(),
                lastWidth, lastHeight);
        int capped = Math.max(0, visible.size() - LIMIT);
        markers = visible.subList(capped, visible.size());
        lastNearby = nearby;
        lastCapped = capped;
        paint(g, markers);
        if (models != null) {
            // Nearest markers are drawn last; resolve them first.
            var ids = new java.util.LinkedHashSet<Long>();
            for (int i = markers.size() - 1; i >= 0; i--) ids.add(markers.get(i).spawn().npcId());
            models.request(ids);
        }
        status.setText(summary(index, nearby, markers.size(), capped) + modelSummary());
    }

    private String modelSummary() {
        if (models == null || markers.isEmpty()) return "";
        return modelSummary(models.count(markers.stream().map(m -> m.spawn().npcId()).toList()));
    }

    /** Status suffix: per-state counts of drawn markers, with refusal reasons, then pending. */
    static String modelSummary(WorldSpawnModels.Counts counts) {
        var text = new StringBuilder(" · models:");
        boolean first = true;
        for (var state : CreatureModelPolicy.State.values()) {
            int n = counts.count(state);
            if (n == 0) continue;
            text.append(first ? " " : ", ").append(n).append(' ').append(state.name().toLowerCase(java.util.Locale.ROOT));
            first = false;
            if (state == CreatureModelPolicy.State.QUALIFIED || state == CreatureModelPolicy.State.AMBIGUOUS) continue;
            var reasons = new ArrayList<String>();
            counts.outcomes().get(state).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> reasons.add(entry.getValue() + " " + WorldPick.reason(entry.getKey())));
            if (!reasons.isEmpty()) text.append(" (").append(String.join(", ", reasons)).append(')');
        }
        if (counts.pending() > 0) text.append(first ? " " : ", ").append(counts.pending()).append(" resolving");
        return text.toString();
    }

    /**
     * Projects with the same native camera convention as terrain rendering: eye right/up/depth and
     * a focal length of 0.8 frame widths, then fits the frame centered in the viewport.
     */
    static List<Marker> project(List<WorldSpawns.Spawn> spawns, TerrainRenderer.Camera camera,
            double frameWidth, double frameHeight, double viewWidth, double viewHeight) {
        if (!(frameWidth > 0) || !(frameHeight > 0) || !(viewWidth > 0) || !(viewHeight > 0))
            return List.of();
        var eyeCamera = TerrainCamera.from(camera);
        double scale = Math.min(viewWidth / frameWidth, viewHeight / frameHeight);
        double left = (viewWidth - frameWidth * scale) / 2, top = (viewHeight - frameHeight * scale) / 2;
        double focal = frameWidth * .8;
        var result = new ArrayList<Marker>();
        for (var spawn : spawns) {
            if (spawn.z() == null) continue;
            var foot = eyeCamera.eye(spawn.x(), spawn.y(), spawn.z());
            var head = eyeCamera.eye(spawn.x(), spawn.y(), spawn.z() + 2);
            if (foot.depth() <= TerrainCamera.NEAR || head.depth() <= TerrainCamera.NEAR) continue;
            double x = frameWidth * .5 + foot.right() * focal / foot.depth();
            double footY = frameHeight * .5 - foot.up() * focal / foot.depth();
            double headY = frameHeight * .5 - head.up() * focal / head.depth();
            double sx = left + x * scale, fy = top + footY * scale, hy = top + headY * scale;
            if (sx < left - 8 || sx > left + frameWidth * scale + 8
                    || Math.max(fy, hy) < top - 8 || Math.min(fy, hy) > top + frameHeight * scale + 8)
                continue;
            result.add(new Marker(spawn, sx, fy, hy, foot.depth()));
        }
        // Far markers first so near markers and labels remain legible.
        result.sort((a, b) -> Double.compare(b.depth(), a.depth()));
        return List.copyOf(result);
    }

    private void paint(GraphicsContext g, List<Marker> markers) {
        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        int labelStart = Math.max(0, markers.size() - LABELS);
        for (int i = 0; i < markers.size(); i++) {
            var marker = markers.get(i);
            var npc = index.npc(marker.spawn().npcId()).orElse(null);
            double radius = headRadius(marker.depth());
            Color color = rankColor(npc == null ? null : npc.rank());
            g.setStroke(Color.rgb(20, 24, 22, .85));
            g.setLineWidth(1.5);
            g.strokeLine(marker.x(), marker.footY(), marker.x(), marker.headY());
            g.setFill(color);
            g.fillOval(marker.x() - radius, marker.headY() - radius, radius * 2, radius * 2);
            g.strokeOval(marker.x() - radius, marker.headY() - radius, radius * 2, radius * 2);
            if (i >= labelStart && marker.depth() <= LABEL_DEPTH) {
                String label = npc == null || npc.name() == null
                        ? "NPC " + marker.spawn().npcId() : npc.name();
                double y = marker.headY() - radius - 2;
                g.setFill(Color.rgb(12, 16, 14, .8));
                g.fillText(label, marker.x() + 1, y + 1);
                g.setFill(Color.WHITE);
                g.fillText(label, marker.x(), y);
            }
        }
    }

    static Color rankColor(Integer rank) {
        if (rank == null) return Color.rgb(170, 176, 180);
        return switch (rank) {
            case 0 -> Color.rgb(242, 201, 76);
            case 1 -> Color.rgb(242, 153, 74);
            case 2 -> Color.rgb(187, 107, 217);
            case 3 -> Color.rgb(235, 87, 87);
            case 4 -> Color.rgb(200, 214, 229);
            default -> Color.rgb(170, 176, 180);
        };
    }

    static String summary(WorldSpawns.Index index, WorldSpawns.Nearby nearby, int visible, int capped) {
        if (index.source().isEmpty())
            return "NPC spawns unavailable: " + String.join(" ", index.issues());
        var source = index.source().get();
        var text = new StringBuilder("NPC spawns · ").append(provider(source.provider()))
                .append(" source client ").append(source.build());
        if (nearby != null) {
            text.append(" · ").append(visible).append(" in view of ").append(nearby.placed().size())
                    .append(" placed within ").append((int) nearby.radius()).append(" yd");
            if (nearby.heightless() > 0)
                text.append(" · ").append(nearby.heightless()).append(" without recorded height not placed");
            if (capped > 0)
                text.append(" · ").append(capped).append(" beyond the ").append(LIMIT).append("-marker cap");
            if (nearby.omitted() > 0)
                text.append(" · ").append(nearby.omitted()).append(" farther points beyond the ")
                        .append(WorldSpawns.MAX_NEARBY).append("-point query bound");
            if (nearby.placed().isEmpty() && nearby.heightless() == 0 && index.count(nearby.mapId()) == 0)
                text.append(" · no declared points on this map");
        } else {
            text.append(" · ").append(index.spawns()).append(" declared points");
        }
        if (index.state() == LocationTypes.State.PARTIAL) text.append(" · partial: ").append(index.issues().getFirst());
        return text.append(" · not depth-tested; phase unknown").toString();
    }

    private static String provider(LocationTypes.Provider provider) {
        return switch (provider) {
            case POLESTAR -> "Polestar";
            case QUESTIE -> "Questie";
            case EG_LINK -> "EG Link";
        };
    }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null && failure instanceof java.util.concurrent.CompletionException)
            failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void show(boolean busy) {
        activity.setVisible(busy);
        activity.setManaged(busy);
    }

    private void clearCanvas() {
        drawn = null;
        markers = List.of();
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }


    @Override
    public void close() {
        closed = true;
        timer.stop();
        if (loading != null) loading.cancel();
        loading = null;
        index = null;
        clearCanvas();
        if (models != null) models.close();
        worker.shutdown();
    }
}
