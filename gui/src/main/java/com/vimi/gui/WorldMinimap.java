package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.MapRoles;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldInstanceLinks;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The minimap cluster: recorded artwork around the player (one active batch and one replaceable
 * pending view), role and instance-entrance markers, the tile streaming ring on the rim, and a
 * button row (zoom out, north-up/heading-up, zoom in, tracking popup). The buttons follow the HUD
 * rule: they are not focus-traversable, their events never reach the viewport's flight handlers,
 * and focus goes back to the viewport after a click. FX-owned.
 */
final class WorldMinimap extends VBox implements AutoCloseable {
    static final double SIZE = 180, CENTER = SIZE / 2, RADIUS = 86;
    /** Zoom levels: canvas scale relative to one tile across the circle; 1 is the default. */
    static final double[] ZOOMS = {.5, .75, 1, 1.5, 2, 3};
    private static final int DEFAULT_ZOOM = 2;
    /** Instance entrance marker colour (also the tracking checkbox's text colour). */
    static final Color ENTRANCE = Color.web("#c69cff");
    /** Streaming ring colour. */
    static final Color RING = Color.web("#6fd3ff");
    private static final double ENTRANCE_SIZE = 5, ENTRANCE_HIT = 7;
    private final Canvas canvas = new Canvas(SIZE, SIZE);
    private final Label message = new Label("Minimap artwork unavailable");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final ToggleButton orientation = new ToggleButton("N");
    private final Button zoomOut = new Button("-"), zoomIn = new Button("+"),
            trackingButton = new Button("*");
    private final WorldMinimapTracking tracking = new WorldMinimapTracking();
    private final WorldMinimapRing ring = new WorldMinimapRing();
    private final AnimationTimer ringFade = new AnimationTimer() {
        @Override
        public void handle(long now) {
            // Through the hold nothing visible changes: redraw only while fading, and once after.
            if (ring.opacity(now) < 1) redraw();
            if (!ring.animating(now)) stop();
        }
    };
    private int zoom = DEFAULT_ZOOM;
    private Runnable refocus = () -> {};
    // Shown by its own state (a valid view) and not hidden by the HUD; see followHud.
    private boolean ownVisible, hudHidden;
    /** Recorded entrances on the current map; drawn while the tracking layer is on. */
    private List<WorldInstanceLinks.Link> entrances = List.of();
    private final List<double[]> entranceHits = new ArrayList<>();
    private final List<WorldInstanceLinks.Link> entranceHitLinks = new ArrayList<>();
    private java.util.function.Consumer<Clicked> clicked;
    private final LinkedHashMap<Long, Artwork> cache = new LinkedHashMap<>(16, .75f, true);
    private final ThreadPoolExecutor worker = executor("world-minimap-requests");
    private final ThreadPoolExecutor cancellation = executor("world-minimap-cancel");
    private View view;
    private Job active;
    private WorldRoleOverlay roles;
    /** Role marker details under the pointer; shown and moved by hand, not installed. */
    private final javafx.scene.control.Tooltip roleTip = new javafx.scene.control.Tooltip();
    /** Last pointer over the canvas: canvas x/y and screen x/y; null when outside. */
    private double[] pointer;
    private long generation;
    private boolean closed;
    // Tiles around the player's tile, rescanned from the whole map only when that tile, the
    // origin or the inventory changes; and what the canvas last drew from update().
    private Neighbourhood neighbourhood;
    private DrawKey drawn;
    private long redraws, scans;

    /** The 3x3 tiles around one player tile; a superset of every tile the circle can touch. */
    private record Neighbourhood(
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            double originX,
            double originY,
            int tileX,
            int tileY,
            List<WorldFormats.Tile> tiles) {}

    /**
     * What a redraw from {@link #update} would show: the player position to a quarter of a canvas
     * pixel, the heading to a quarter degree, the tiles and the origin they are placed from, and
     * the role overlay's source key (markers can become available while the player stands still).
     * Equal keys draw the same picture.
     */
    record DrawKey(long x, long y, long heading, List<WorldFormats.Tile> tiles, WorldFormats.Tile origin,
            double originX, double originY, Object roles, double zoom) {
        /** Canvas pixels per yard of the player's position. */
        static final double PIXELS_PER_YARD = 2 * RADIUS / WorldFormats.TILE_SIZE;

        static DrawKey of(double x, double y, double yaw, List<WorldFormats.Tile> tiles,
                WorldFormats.Tile origin, double originX, double originY, Object roles, double zoom) {
            double quarter = 4 * PIXELS_PER_YARD * zoom;
            return new DrawKey(Math.round(x * quarter), Math.round(y * quarter),
                    Math.round(Math.toDegrees(Math.IEEEremainder(yaw, 2 * Math.PI)) * 4), tiles,
                    origin, originX, originY, roles, zoom);
        }
    }

    private record View(
            BuildWorld source,
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            double originX,
            double originY,
            double x,
            double y,
            double yaw,
            List<WorldFormats.Tile> tiles) {}

    private record Artwork(Image image, String issue) {}

    /**
     * A clicked minimap marker: a role NPC ({@code role}) or an instance entrance ({@code
     * entrance}), exactly one of them non-null, with its hover description.
     */
    record Clicked(MapRoles.Marker role, WorldInstanceLinks.Link entrance, String description) {}

    private record Loaded(long id, WorldService.Minimap image) {}

    private static final class Job {
        final long generation;
        final Cancellation token = new Cancellation();
        final View view;
        final List<WorldFormats.Tile> tiles;
        boolean cancelRequested;

        Job(long generation, View view, List<WorldFormats.Tile> tiles) {
            this.generation = generation;
            this.view = view;
            this.tiles = List.copyOf(tiles);
        }
    }

    WorldMinimap() {
        // An obsolete queued cancellation belongs to an already completed older batch. Keep only
        // the latest retirement if a custom provider stalls inside its cancellation callback.
        cancellation.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        setId("world-minimap");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(3);
        setMinSize(190, 244);
        setPrefSize(190, 244);
        setMaxSize(190, 244);
        setStyle(
                "-fx-background-color: rgba(14,24,31,0.85); -fx-background-radius: 12; -fx-padding:"
                        + " 5;");
        canvas.setId("world-minimap-canvas");
        canvas.setAccessibleText("North-up minimap; player centered");
        // Pointer moves only feed the role tooltip; every mouse event still bubbles to the viewport.
        canvas.setOnMouseMoved(event -> {
            pointer = new double[] {event.getX(), event.getY(), event.getScreenX(), event.getScreenY()};
            hover();
        });
        canvas.setOnMouseExited(event -> {
            pointer = null;
            roleTip.hide();
        });
        // A still primary click on a marker is reported and kept from the world pick; any other
        // click on the artwork bubbles to the viewport as before.
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (clicked == null || event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress())
                return;
            var hit = markerAt(event.getX(), event.getY());
            if (hit == null) return;
            event.consume();
            clicked.accept(hit);
        });
        roleTip.setWrapText(true);
        roleTip.setMaxWidth(360);
        orientation.setId("world-minimap-orientation");
        orientation.setAccessibleText("Minimap orientation: north up");
        orientation.setTooltip(new Tooltip("North up / heading up"));
        orientation.selectedProperty().addListener((o, was, headingUp) -> {
            orientation.setText(headingUp ? "^" : "N");
            redraw();
        });
        button(orientation, () -> {});
        zoomOut.setId("world-minimap-zoom-out");
        zoomOut.setAccessibleText("Zoom the minimap out");
        zoomOut.setTooltip(new Tooltip("Zoom out"));
        button(zoomOut, () -> zoom(zoom - 1));
        zoomIn.setId("world-minimap-zoom-in");
        zoomIn.setAccessibleText("Zoom the minimap in");
        zoomIn.setTooltip(new Tooltip("Zoom in"));
        button(zoomIn, () -> zoom(zoom + 1));
        trackingButton.setId("world-minimap-tracking");
        trackingButton.setAccessibleText("Tracking: NPC spawns, minimap roles and instance entrances");
        trackingButton.setTooltip(new Tooltip("Tracking: spawns, roles, entrances"));
        button(trackingButton, null);
        // Opening the popup moves focus to it; the popup gives focus back when it closes.
        trackingButton.setOnAction(event -> tracking.toggle(this));
        tracking.entrances().selectedProperty().addListener((o, was, on) -> redraw());
        var buttons = new HBox(4, zoomOut, orientation, zoomIn, trackingButton);
        buttons.setId("world-minimap-buttons");
        buttons.setAlignment(Pos.CENTER);
        activity.setId("world-minimap-activity");
        activity.setPrefSize(14, 14);
        activity.setMaxSize(14, 14);
        activity.setAccessibleText("Loading recorded minimap artwork");
        message.setId("world-minimap-message");
        message.setWrapText(true);
        message.setMaxWidth(156);
        message.setStyle("-fx-text-fill: white; -fx-font-size: 10px;");
        var state = new HBox(5, activity, message);
        state.setAlignment(Pos.CENTER);
        state.setMouseTransparent(true);
        getChildren().addAll(canvas, state, buttons);
        zoom(DEFAULT_ZOOM);
        hide();
    }

    /**
     * The HUD button pattern: the control handles its input first, then the event stops here so
     * flight keys and gestures never reach the viewport; after {@code action}, focus goes back.
     */
    private void button(ButtonBase button, Runnable action) {
        button.setFocusTraversable(false);
        button.setMinWidth(34);
        button.setStyle("-fx-font-size: 11px; -fx-padding: 2 6 2 6;");
        if (action != null) button.setOnAction(event -> {
            action.run();
            refocus.run();
        });
        button.addEventHandler(KeyEvent.ANY, KeyEvent::consume);
        button.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        button.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
    }

    /** Gives keyboard focus back to the World viewport after a cluster click. */
    void refocus(Runnable next) {
        refocus = java.util.Objects.requireNonNull(next);
        tracking.refocus(next);
    }

    /** The tracking popup; the view mounts the spawn and role controls in it. */
    WorldMinimapTracking tracking() {
        return tracking;
    }

    /** Reports clicked markers (null: clicks on markers bubble to the viewport like any other). */
    void onMarkerClicked(java.util.function.Consumer<Clicked> next) {
        clicked = next;
    }

    /** True in heading-up mode, false north-up. */
    boolean headingUp() {
        return orientation.isSelected();
    }

    void headingUp(boolean next) {
        orientation.setSelected(next);
    }

    /** The current zoom scale, one of {@link #ZOOMS}. */
    double zoom() {
        return ZOOMS[zoom];
    }

    private void zoom(int index) {
        int next = Math.clamp(index, 0, ZOOMS.length - 1);
        boolean changed = next != zoom;
        zoom = next;
        zoomOut.setDisable(zoom == 0);
        zoomIn.setDisable(zoom == ZOOMS.length - 1);
        if (!changed || view == null) return;
        // A wider view can reach tiles the current view list left out: place them again.
        var current = view;
        drawn = null;
        update(current.source(), current.inventory(), current.origin(), current.originX(),
                current.originY(), current.x(), current.y(), current.yaw());
    }

    /** The tile streaming state for the rim ring; {@code desired <= 0} shows no ring. */
    void progress(int prepared, int desired) {
        long now = System.nanoTime();
        double before = ring.fraction(), shown = ring.opacity(now);
        ring.update(prepared, desired, now);
        if (ring.animating(now)) ringFade.start();
        if (ring.fraction() != before || ring.opacity(now) != shown) redraw();
    }

    /** Ring fraction and opacity now; for tests. */
    double[] ring() {
        long now = System.nanoTime();
        return new double[] {ring.fraction(), ring.opacity(now)};
    }

    /** Recorded instance entrances on the current map, drawn while the tracking layer is on. */
    void entrances(List<WorldInstanceLinks.Link> next) {
        entrances = List.copyOf(next);
        if (tracking.entrances().isSelected()) redraw();
    }

    /** The marker under a canvas point: a role NPC first, then an entrance; null for none. */
    Clicked markerAt(double x, double y) {
        if (roles != null && view != null) {
            var role = roles.markerAt(x, y);
            if (role != null) return new Clicked(role, null, roles.hoverText(x, y));
        }
        for (int i = 0; i < entranceHits.size(); i++) {
            var at = entranceHits.get(i);
            if (Math.hypot(at[0] - x, at[1] - y) <= ENTRANCE_HIT) {
                var link = entranceHitLinks.get(i);
                return new Clicked(null, link, entranceText(link));
            }
        }
        return null;
    }

    private static String entranceText(WorldInstanceLinks.Link link) {
        return link + " entrance\nMap #" + link.mapId()
                + " · recorded Map corpse position on this map";
    }

    private static ThreadPoolExecutor executor(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Mounts minimap role markers; their controls are placed by the caller. FX thread. */
    void attachRoles(WorldRoleOverlay overlay) {
        roleTip.hide();
        roles = overlay;
        overlay.bindRedraw(this::redraw);
        redraw();
    }

    /** The role tooltip's text while it is showing, else null; for tests. */
    String roleTipText() {
        return roleTip.isShowing() ? roleTip.getText() : null;
    }

    /** Shows, moves, updates or hides the role tooltip against what is drawn now. */
    private void hover() {
        var hit = pointer == null || view == null ? null : markerAt(pointer[0], pointer[1]);
        String text = hit == null ? null : hit.description();
        if (text == null) {
            roleTip.hide();
            return;
        }
        if (!text.equals(roleTip.getText())) roleTip.setText(text);
        if (!roleTip.isShowing()) roleTip.show(canvas, pointer[2] + 14, pointer[3] + 14);
        else {
            roleTip.setAnchorX(pointer[2] + 14);
            roleTip.setAnchorY(pointer[3] + 14);
        }
    }

    void update(
            BuildWorld source,
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            double originX,
            double originY,
            double x,
            double y,
            double yaw) {
        if (closed) return;
        if (source == null
                || inventory == null
                || inventory.world() == null
                || origin == null
                || !Double.isFinite(originX)
                || !Double.isFinite(originY)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(yaw)) {
            hide();
            return;
        }
        boolean reset = view == null || view.source() != source || view.inventory() != inventory;
        if (reset) {
            generation++;
            cancelActive();
            cache.clear();
            drawn = null;
        }
        int tileX = playerTile(origin.x(), originX, x), tileY = playerTile(origin.y(), originY, y);
        var around = neighbourhood;
        if (reset || around == null || around.inventory() != inventory || around.origin() != origin
                || around.originX() != originX || around.originY() != originY
                || around.tileX() != tileX || around.tileY() != tileY) {
            var tiles = new ArrayList<WorldFormats.Tile>(9);
            for (var tile : inventory.world().tiles()) {
                if (Math.abs(tile.x() - tileX) <= 1 && Math.abs(tile.y() - tileY) <= 1) tiles.add(tile);
            }
            scans++;
            around = neighbourhood = new Neighbourhood(inventory, origin, originX, originY, tileX, tileY,
                    List.copyOf(tiles));
        }
        var nearby = new ArrayList<WorldFormats.Tile>(9);
        double scale = ZOOMS[zoom];
        for (var tile : around.tiles()) {
            double left = screenY(maxY(tile, origin, originY), y, scale);
            double top = screenX(maxX(tile, origin, originX), x, scale);
            double side = 2 * RADIUS * scale;
            if (left < CENTER + RADIUS
                    && left + side > CENTER - RADIUS
                    && top < CENTER + RADIUS
                    && top + side > CENTER - RADIUS) {
                if (nearby.size() == 9) break;
                nearby.add(tile);
            }
        }
        view =
                new View(
                        source,
                        inventory,
                        origin,
                        originX,
                        originY,
                        x,
                        y,
                        yaw,
                        List.copyOf(nearby));
        ownVisible = true;
        applyVisible();
        var key = DrawKey.of(x, y, yaw, view.tiles(), origin, originX, originY,
                roles == null ? null : roles.paintKey(), scale);
        if (!key.equals(drawn)) {
            drawn = key;
            redraw();
        }
        startMissing(false);
    }

    /**
     * Index of the tile holding the player along one axis: tile maxima fall by one tile size per
     * index from the origin tile's maximum.
     */
    static int playerTile(int originIndex, double originMax, double position) {
        return originIndex + (int) Math.floor((originMax - position) / WorldFormats.TILE_SIZE);
    }

    /** Canvas redraws and whole-map tile scans so far; for tests. */
    long redraws() {
        return redraws;
    }

    long scans() {
        return scans;
    }

    static double maxX(WorldFormats.Tile tile, WorldFormats.Tile origin, double originX) {
        return originX - (tile.x() - origin.x()) * WorldFormats.TILE_SIZE;
    }

    static double maxY(WorldFormats.Tile tile, WorldFormats.Tile origin, double originY) {
        return originY - (tile.y() - origin.y()) * WorldFormats.TILE_SIZE;
    }

    static double screenX(double nativeX, double playerX) {
        return screenX(nativeX, playerX, 1);
    }

    static double screenY(double nativeY, double playerY) {
        return screenY(nativeY, playerY, 1);
    }

    /** As {@link #screenX(double, double)} at a zoom scale. */
    static double screenX(double nativeX, double playerX, double zoom) {
        return CENTER - (nativeX - playerX) * (2 * RADIUS * zoom / WorldFormats.TILE_SIZE);
    }

    static double screenY(double nativeY, double playerY, double zoom) {
        return CENTER - (nativeY - playerY) * (2 * RADIUS * zoom / WorldFormats.TILE_SIZE);
    }

    private void startMissing() {
        startMissing(true);
    }

    /** Starts loading missing artwork; with nothing missing, redraws only when asked to. */
    private void startMissing(boolean redrawWhenComplete) {
        if (closed || active != null || view == null) return;
        var missing = new ArrayList<WorldFormats.Tile>();
        for (var tile : view.tiles()) {
            if (tile.minimapId() > 0
                    && !cache.containsKey(tile.minimapId())
                    && missing.stream().noneMatch(t -> t.minimapId() == tile.minimapId())) {
                missing.add(tile);
            }
        }
        if (missing.isEmpty()) {
            if (redrawWhenComplete) redraw();
            return;
        }
        var job = new Job(generation, view, missing);
        active = job;
        redraw();
        worker.execute(
                () -> {
                    var results = new ArrayList<Loaded>(job.tiles.size());
                    try {
                        for (var tile : job.tiles) {
                            job.token.check();
                            WorldService.Minimap result;
                            try {
                                result = job.view.source().minimap(tile, job.token).join();
                                if (result == null)
                                    result =
                                            WorldService.Minimap.unavailable(
                                                    "Minimap artwork unavailable");
                            } catch (RuntimeException failure) {
                                job.token.check();
                                result =
                                        WorldService.Minimap.unavailable(
                                                "Minimap artwork unavailable");
                            }
                            job.token.check();
                            results.add(new Loaded(tile.minimapId(), result));
                        }
                    } catch (java.util.concurrent.CancellationException ignored) {
                        // A retired provider may finish later; only the current generation can
                        // publish.
                    }
                    Platform.runLater(() -> completed(job, results));
                });
    }

    private void completed(Job job, List<Loaded> results) {
        if (active != job) return;
        active = null;
        if (!closed
                && view != null
                && job.generation == generation
                && job.view.source() == view.source()
                && job.view.inventory() == view.inventory()) {
            for (var result : results) {
                var data = result.image();
                Image image = null;
                if (data.pixels() != null) {
                    var writable = new WritableImage(data.width(), data.height());
                    writable.getPixelWriter()
                            .setPixels(
                                    0,
                                    0,
                                    data.width(),
                                    data.height(),
                                    PixelFormat.getIntArgbInstance(),
                                    data.pixels(),
                                    data.width());
                    image = writable;
                }
                if (!cache.containsKey(result.id()) && cache.size() == 9) {
                    cache.remove(cache.keySet().iterator().next());
                }
                cache.put(result.id(), new Artwork(image, data.issue()));
            }
            redraw();
        }
        startMissing();
    }

    private void redraw() {
        redraws++;
        var g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        g.clearRect(0, 0, SIZE, SIZE);
        g.save();
        g.beginPath();
        g.arc(CENTER, CENTER, RADIUS, RADIUS, 0, 360);
        g.closePath();
        g.clip();
        g.setFill(Color.web("#223038"));
        g.fillRect(0, 0, SIZE, SIZE);
        boolean rotating = orientation.isSelected();
        double scale = ZOOMS[zoom];
        double yaw = view == null ? 0 : view.yaw();
        double rotation = rotating ? yaw : 0;
        g.translate(CENTER, CENTER);
        g.rotate(Math.toDegrees(rotation));
        g.translate(-CENTER, -CENTER);
        int readable = 0, missing = 0;
        if (view != null) {
            for (var tile : view.tiles()) {
                var artwork = cache.get(tile.minimapId());
                if (artwork == null || artwork.image() == null) {
                    missing++;
                    continue;
                }
                readable++;
                double left = screenY(maxY(tile, view.origin(), view.originY()), view.y(), scale);
                double top = screenX(maxX(tile, view.origin(), view.originX()), view.x(), scale);
                g.drawImage(artwork.image(), left, top, 2 * RADIUS * scale, 2 * RADIUS * scale);
            }
            if (roles != null && view.inventory().map() != null) {
                var current = view;
                roles.paint(g, current.inventory().map().id(),
                        (worldX, worldY) -> new double[] {
                            screenY(worldY, current.y(), scale), screenX(worldX, current.x(), scale)},
                        CENTER, RADIUS, rotation);
            }
        }
        paintEntrances(g, rotation, scale);
        g.restore();
        g.setStroke(Color.web("#a6b9b7"));
        g.setLineWidth(2);
        g.strokeOval(CENTER - RADIUS, CENTER - RADIUS, 2 * RADIUS, 2 * RADIUS);
        paintRing(g);
        g.setFill(Color.WHITE);
        g.fillText("N", CENTER + Math.sin(rotation) * (RADIUS - 8) - 4,
                CENTER - Math.cos(rotation) * (RADIUS - 8) + 4);
        double arrowYaw = rotating ? 0 : yaw;
        double dx = -Math.sin(arrowYaw), dy = -Math.cos(arrowYaw);
        g.setFill(Color.web("#ffd66b"));
        g.setStroke(Color.web("#172129"));
        g.setLineWidth(2);
        double[] xs = {CENTER + dx * 14, CENTER - dx * 8 - dy * 6, CENTER - dx * 8 + dy * 6};
        double[] ys = {CENTER + dy * 14, CENTER - dy * 8 + dx * 6, CENTER - dy * 8 - dx * 6};
        g.fillPolygon(xs, ys, 3);
        g.strokePolygon(xs, ys, 3);
        g.setFill(Color.WHITE);
        g.fillOval(CENTER - 2, CENTER - 2, 4, 4);
        boolean loading =
                view != null
                        && view.tiles().stream()
                                .anyMatch(
                                        tile ->
                                                tile.minimapId() > 0
                                                        && !cache.containsKey(tile.minimapId()));
        activity.setVisible(loading);
        activity.setManaged(loading);
        String mode = rotating ? "Heading up" : "North up";
        orientation.setAccessibleText("Minimap orientation: " + mode.toLowerCase(java.util.Locale.ROOT));
        message.setText(
                loading
                        ? "Loading minimap artwork…"
                        : readable == 0
                                ? "Minimap artwork unavailable"
                                : mode + (missing > 0 ? " · partial artwork" : ""));
        setAccessibleText(mode + " minimap. " + message.getText());
        canvas.setAccessibleText(mode + " minimap; player centered");
        hover();
    }

    /** Entrance diamonds inside the circle; hit points are recorded after the map's rotation. */
    private void paintEntrances(javafx.scene.canvas.GraphicsContext g, double rotation, double scale) {
        entranceHits.clear();
        entranceHitLinks.clear();
        if (view == null || !tracking.entrances().isSelected()) return;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        for (var link : entrances) {
            var entrance = link.entrance();
            if (entrance == null) continue;
            double px = screenY(entrance.y(), view.y(), scale), py = screenX(entrance.x(), view.x(), scale);
            double dx = px - CENTER, dy = py - CENTER;
            double inside = RADIUS - ENTRANCE_SIZE;
            if (dx * dx + dy * dy > inside * inside) continue;
            double[] xs = {px, px + ENTRANCE_SIZE, px, px - ENTRANCE_SIZE};
            double[] ys = {py - ENTRANCE_SIZE, py, py + ENTRANCE_SIZE, py};
            g.setFill(ENTRANCE);
            g.setStroke(Color.rgb(12, 16, 14, .9));
            g.setLineWidth(1);
            g.fillPolygon(xs, ys, 4);
            g.strokePolygon(xs, ys, 4);
            entranceHits.add(new double[] {CENTER + dx * cos - dy * sin, CENTER + dx * sin + dy * cos});
            entranceHitLinks.add(link);
        }
    }

    /** The streaming ring: clockwise from north over the rim, fading after it fills. */
    private void paintRing(javafx.scene.canvas.GraphicsContext g) {
        double opacity = ring.opacity(System.nanoTime());
        if (opacity <= 0) return;
        g.save();
        g.setGlobalAlpha(opacity);
        g.setStroke(RING);
        g.setLineWidth(3);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.BUTT);
        // JavaFX arcs run counter-clockwise from three o'clock; a negative extent is clockwise.
        g.strokeArc(CENTER - RADIUS, CENTER - RADIUS, 2 * RADIUS, 2 * RADIUS, 90,
                -360 * ring.fraction(), javafx.scene.shape.ArcType.OPEN);
        g.restore();
    }

    void hide() {
        ringFade.stop();
        ring.reset();
        tracking.hide();
        roleTip.hide();
        generation++;
        view = null;
        neighbourhood = null;
        drawn = null;
        cache.clear();
        cancelActive();
        activity.setVisible(false);
        activity.setManaged(false);
        ownVisible = false;
        applyVisible();
    }

    /**
     * Follows the HUD's hidden state (H, Alt+Z) by listening, not binding, since the minimap also
     * shows and hides itself: it shows while it has a view and the HUD is shown. Hiding the HUD
     * closes the tracking popup and the role tip; the view and its artwork are kept.
     */
    void followHud(javafx.beans.value.ObservableBooleanValue hidden) {
        java.util.Objects.requireNonNull(hidden);
        hidden.addListener((o, was, now) -> hudHidden(now));
        hudHidden(hidden.get());
    }

    private void hudHidden(boolean hidden) {
        hudHidden = hidden;
        if (hidden) {
            tracking.hide();
            roleTip.hide();
        }
        applyVisible();
    }

    private void applyVisible() {
        boolean shown = ownVisible && !hudHidden;
        setVisible(shown);
        setManaged(shown);
    }

    private void cancelActive() {
        if (active == null || active.cancelRequested) return;
        active.cancelRequested = true;
        var token = active.token;
        cancellation.execute(token::cancel);
    }

    @Override
    public void close() {
        if (closed) return;
        hide();
        closed = true;
        ringFade.stop();
        if (roles != null) roles.close();
        worker.shutdown();
        cancellation.shutdown();
    }
}
