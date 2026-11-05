package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.BuildLocationMaps;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.rendering.bgfx.WorldGlobalTransport;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Inspection view of one qualified global WMO-only instance map through the application GPU owner.
 * The initial camera frames the native bounds; it is not a client entrance, spawn or floor.
 */
final class GlobalInteriorView extends BorderPane implements AutoCloseable {
    private record Delivery(long generation, WorldGlobalTransport.Frame frame) {}

    private final Object owner = new Object();
    private final GpuPreviewService service;
    private final ImageView image = new ImageView();
    private final StackPane viewport = new StackPane(image);
    private final Label status = new Label("Preparing interior…");
    private final Label help =
            new Label("W/A/S/D move · Q/E down/up · arrows or drag to look · Shift faster");
    private final Set<KeyCode> keys = new HashSet<>();
    /** The empty entry is the overview (initial inspection framing). */
    private final ListView<java.util.Optional<GlobalInteriorOutline.Group>> groups =
            new ListView<>();
    private final Label floors = new Label(GlobalInteriorOutline.FLOORS_UNQUALIFIED);
    private final ListView<GlobalInteriorFloors.Floor> floorList = new ListView<>();
    private final Label unlinked = new Label();
    private final Supplier<BuildLocationMaps> maps;
    private final long mapId;
    /** Cancels the off-FX UiMap assignment lookup when the window closes. */
    private final Cancellation lookup = new Cancellation();
    private final Label entrance = new Label(GlobalInteriorOutline.ENTRANCE_UNKNOWN);
    private final ModelPreviewView.LatestDelivery<Delivery> frames =
            new ModelPreviewView.LatestDelivery<>(Platform::runLater, this::present);
    private final AnimationTimer timer;
    private WorldGlobalTransport.Session session;
    private WorldGlobalTransport.Camera camera, overview;
    private volatile long generation, floor, latestSent;
    private volatile int width, height;
    private volatile boolean polling;
    private long presentedSequence, lastTick;
    private double dragX, dragY;
    private boolean dirty, closed;
    private CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);

    GlobalInteriorView(
            GpuPreviewService service,
            Supplier<BuildWorld> worlds,
            Supplier<BuildLocationMaps> maps,
            long mapId,
            String name) {
        this.service = Objects.requireNonNull(service);
        Objects.requireNonNull(worlds);
        this.maps = maps;
        this.mapId = mapId;
        setId("global-interior");
        status.setId("global-interior-status");
        status.setWrapText(true);
        help.getStyleClass().add("muted");
        var heading = new Label(name + " · Map #" + mapId);
        heading.getStyleClass().add("h2");
        var top = new VBox(4, heading, status, help);
        top.setPadding(new Insets(12));
        setTop(top);
        viewport.setMinSize(160, 100);
        viewport.setStyle("-fx-background-color: #112233;");
        viewport.setFocusTraversable(true);
        image.fitWidthProperty().bind(viewport.widthProperty());
        image.fitHeightProperty().bind(viewport.heightProperty());
        image.setPreserveRatio(false);
        image.setSmooth(true);
        setCenter(viewport);
        setRight(navigation());
        viewport.setOnKeyPressed(e -> keys.add(e.getCode()));
        viewport.setOnKeyReleased(e -> keys.remove(e.getCode()));
        // A key released while unfocused would otherwise keep the camera drifting.
        viewport.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) keys.clear();
        });
        viewport.setOnMouseClicked(e -> viewport.requestFocus());
        viewport.setOnMousePressed(
                e -> {
                    dragX = e.getX();
                    dragY = e.getY();
                    viewport.requestFocus();
                });
        viewport.setOnMouseDragged(
                e -> {
                    look(-(e.getX() - dragX) * .005, -(e.getY() - dragY) * .005);
                    dragX = e.getX();
                    dragY = e.getY();
                });
        viewport.widthProperty().addListener((o, a, b) -> dirty = true);
        viewport.heightProperty().addListener((o, a, b) -> dirty = true);
        timer =
                new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        tick(now);
                    }
                };
        service.openGlobal(
                        owner,
                        (parent, cancel) -> {
                            var world = worlds.get();
                            if (world == null) {
                                throw new GpuPreviewService.GlobalUnavailable(
                                        "World data is not ready for this build");
                            }
                            var scenes =
                                    world.globalScenes()
                                            .orElseThrow(
                                                    () ->
                                                            new GpuPreviewService.GlobalUnavailable(
                                                                    "Interior rendering needs local"
                                                                            + " client files"));
                            var catalog = world.catalog(cancel).join();
                            var map =
                                    catalog.maps().stream()
                                            .filter(m -> m.id() == mapId)
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new GpuPreviewService.GlobalUnavailable(
                                                                    "Map #"
                                                                            + mapId
                                                                            + " is not in the"
                                                                            + " readable World"
                                                                            + " catalog"));
                            var scope = scenes.createScope(map, cancel);
                            scenes.activate(scope);
                            return WorldGlobalSourceBridge.prepare(scenes, scope, parent, cancel);
                        },
                        opened -> Platform.runLater(() -> opened(opened)),
                        update -> Platform.runLater(() -> state(update)))
                .exceptionally(ignored -> null);
    }

    private VBox navigation() {
        groups.setId("global-interior-groups");
        groups.setPlaceholder(new Label("Groups appear when the interior opens"));
        groups.setPrefWidth(260);
        groups.setCellFactory(
                list ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(
                                    java.util.Optional<GlobalInteriorOutline.Group> entry,
                                    boolean empty) {
                                super.updateItem(entry, empty);
                                // Only a click on a row jumps; scrollbar/empty space do not.
                                setOnMouseClicked(
                                        empty || entry == null
                                                ? null
                                                : e -> jumpToSelection());
                                var group = entry == null ? null : entry.orElse(null);
                                if (empty || entry == null) {
                                    setText(null);
                                    setDisable(false);
                                } else if (group == null) {
                                    setText("Overview · whole interior bounds");
                                    setDisable(false);
                                } else {
                                    setText(group.label() + "\n" + group.details());
                                    setDisable(group.bounds() == null);
                                }
                            }
                        });
        groups.setOnKeyPressed(
                e -> {
                    if (e.getCode() == KeyCode.ENTER) {
                        jumpToSelection();
                    }
                });
        floors.setId("global-interior-floors");
        entrance.setId("global-interior-entrance");
        unlinked.setId("global-interior-unlinked");
        floorList.setId("global-interior-floor-list");
        floorList.setPrefHeight(150);
        floorList.setPlaceholder(new Label("No linked floors"));
        floorList.setCellFactory(
                list ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(
                                    GlobalInteriorFloors.Floor floor, boolean empty) {
                                super.updateItem(floor, empty);
                                setOnMouseClicked(
                                        empty || floor == null ? null : e -> jumpToFloor(floor));
                                setText(
                                        empty || floor == null
                                                ? null
                                                : floor.label() + "\n" + floor.details());
                            }
                        });
        floorList.setOnKeyPressed(
                e -> {
                    var floor = floorList.getSelectionModel().getSelectedItem();
                    if (e.getCode() == KeyCode.ENTER && floor != null) {
                        jumpToFloor(floor);
                    }
                });
        for (var label : new Label[] {floors, unlinked, entrance}) {
            label.setWrapText(true);
            label.getStyleClass().add("muted");
        }
        var heading = new Label("Recorded WMO groups");
        heading.getStyleClass().add("eyebrow");
        var hint = new Label("Select a group to move the inspection camera to its bounds.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        VBox.setVgrow(groups, Priority.ALWAYS);
        var floorHeading = new Label("Floors / map layers (UiMap)");
        floorHeading.getStyleClass().add("eyebrow");
        var box = new VBox(6, heading, hint, groups, floorHeading, floors, floorList, unlinked, entrance);
        box.setPadding(new Insets(12));
        box.setPrefWidth(280);
        return box;
    }

    private void jumpToSelection() {
        if (groups.getSelectionModel().isEmpty()) {
            return;
        }
        var entry = groups.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        jumpTo(entry.orElse(null));
        viewport.requestFocus();
    }

    /** Replaces the latest camera only; the transport still sends one coalesced latest CAM. */
    private void jumpTo(GlobalInteriorOutline.Group group) {
        if (closed || session == null || overview == null || groups.isDisabled()) {
            return;
        }
        var target = GlobalInteriorOutline.target(java.util.Optional.ofNullable(group), overview);
        if (target == null) {
            status.setText(
                    group.label() + " · recorded bounds are outside the camera range; not moved");
            return;
        }
        camera = target;
        dirty = true;
        status.setText(
                group == null
                        ? "Overview · inspection camera, not a client entrance"
                        : group.label() + " · recorded group bounds, not a client entrance or"
                                + " floor");
    }

    /**
     * Frames the floor's exactly linked groups. Replaces the latest camera only, like a group jump.
     */
    private void jumpToFloor(GlobalInteriorFloors.Floor floor) {
        if (closed
                || session == null
                || overview == null
                || groups.isDisabled()
                || floorList.isDisabled()) {
            return;
        }
        var target = floor.camera();
        if (target == null) {
            status.setText(
                    floor.name() + " · recorded group bounds are outside the camera range; not moved");
            return;
        }
        camera = target;
        dirty = true;
        status.setText(
                floor.name()
                        + " · framing its "
                        + floor.groups().size()
                        + (floor.groups().size() == 1 ? " linked group" : " linked groups")
                        + " (UiMap assignment), not a client entrance");
        viewport.requestFocus();
    }

    /** Reads and links group-scoped UiMap assignments off FX; the result is published on FX. */
    private void readFloors(GlobalInteriorOutline outline) {
        floors.setText(GlobalInteriorFloors.READING);
        Thread.ofVirtual()
                .name("global-interior-floors")
                .start(
                        () -> {
                            var result =
                                    GlobalInteriorFloors.lookup(maps, mapId, outline, lookup);
                            if (result != null) {
                                Platform.runLater(() -> showFloors(result));
                            }
                        });
    }

    private void showFloors(GlobalInteriorFloors result) {
        if (closed) {
            return;
        }
        floors.setText(result.summary());
        floorList.getItems().setAll(result.floors());
        unlinked.setText(
                String.join(
                        "\n",
                        result.unlinked().stream().map(GlobalInteriorFloors.Unlinked::text).toList()));
    }

    private static final java.util.Map<Long, Stage> OPEN = new java.util.HashMap<>();

    /** One window per map; reopening focuses the live one instead of superseding it. */
    static Stage open(
            Supplier<BuildWorld> worlds,
            Supplier<BuildLocationMaps> maps,
            long mapId,
            String name) {
        var existing = OPEN.get(mapId);
        if (existing != null && existing.isShowing()) {
            existing.toFront();
            existing.requestFocus();
            return existing;
        }
        var view = new GlobalInteriorView(GpuPreviewService.application(), worlds, maps, mapId, name);
        var stage = new Stage();
        stage.setTitle(name + " · interior");
        stage.setScene(new Scene(view, 1200, 680));
        stage.setOnHidden(
                e -> {
                    view.close();
                    OPEN.remove(mapId, stage);
                });
        OPEN.put(mapId, stage);
        stage.show();
        view.viewport.requestFocus();
        return stage;
    }

    private void state(GpuPreviewService.Status update) {
        if (closed) {
            return;
        }
        switch (update.state()) {
            case READY ->
                    status.setText(
                            update.message() + " · inspection camera, not a client entrance");
            case FAILED, CLOSED -> {
                stopPolling();
                // A jump would no longer reach the renderer and must not hide this message.
                groups.setDisable(true);
                floorList.setDisable(true);
                status.setText(update.message());
            }
            default -> status.setText(update.message());
        }
    }

    private void opened(GpuPreviewService.GlobalOpened opened) {
        if (closed) {
            return;
        }
        session = opened.session();
        camera = opened.initial();
        overview = camera;
        var outline = opened.outline();
        var items = new java.util.ArrayList<java.util.Optional<GlobalInteriorOutline.Group>>();
        items.add(java.util.Optional.empty());
        outline.groups().forEach(group -> items.add(java.util.Optional.of(group)));
        groups.getItems().setAll(items);
        if (!outline.groupsReadable()) {
            groups.setPlaceholder(new Label(GlobalInteriorOutline.GROUPS_UNREADABLE));
            groups.getItems().clear();
        }
        floors.setText(outline.floors());
        entrance.setText(outline.entrance());
        readFloors(outline);
        dirty = true;
        polling = true;
        long token = ++generation;
        var current = session;
        Thread.ofVirtual().name("global-interior-frames").start(() -> poll(current, token));
        timer.start();
    }

    private void poll(WorldGlobalTransport.Session current, long token) {
        while (polling && generation == token) {
            try {
                int w = width, h = height;
                if (w > 0 && h > 0 && latestSent >= floor && floor > 0) {
                    var frame = current.poll(floor, w, h);
                    if (frame != null) {
                        frames.submit(new Delivery(token, frame));
                    }
                }
                Thread.sleep(8);
            } catch (InterruptedException interrupted) {
                return;
            } catch (java.io.IOException | RuntimeException failed) {
                Platform.runLater(
                        () -> {
                            // A later jump must not hide this failure.
                            groups.setDisable(true);
                            floorList.setDisable(true);
                            status.setText("Interior frames unavailable");
                        });
                return;
            }
        }
    }

    private void tick(long now) {
        if (closed || session == null || camera == null) {
            return;
        }
        double dt = lastTick == 0 ? 0 : Math.min(.1, (now - lastTick) / 1e9);
        lastTick = now;
        double speed = 40 * dt * (keys.contains(KeyCode.SHIFT) ? 4 : 1);
        double forward = axis(KeyCode.W, KeyCode.S), right = axis(KeyCode.D, KeyCode.A);
        double up = axis(KeyCode.E, KeyCode.Q);
        if (forward != 0 || right != 0 || up != 0) {
            try {
                camera = camera.move(forward * speed, right * speed, up * speed);
                dirty = true;
            } catch (IllegalArgumentException outside) {
                // Global camera bounds reached; keep the last valid camera.
            }
        }
        double turn = axis(KeyCode.LEFT, KeyCode.RIGHT), tilt = axis(KeyCode.UP, KeyCode.DOWN);
        if (turn != 0 || tilt != 0) {
            look(turn * 1.6 * dt, tilt * 1.2 * dt);
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        double paneW = Math.max(1, viewport.getWidth()), paneH = Math.max(1, viewport.getHeight());
        double scale = Math.min(1, Math.min(800 / paneW, 500 / paneH));
        int w = Math.clamp(Math.round(paneW * scale), 1, 800);
        int h = Math.clamp(Math.round(paneH * scale), 1, 500);
        boolean resized = w != width || h != height;
        long revision = session.camera(w, h, camera);
        if (resized) {
            // Resize floor: never present an older size or a pre-resize revision.
            floor = revision;
            width = w;
            height = h;
        }
        latestSent = revision;
    }

    private double axis(KeyCode positive, KeyCode negative) {
        return (keys.contains(positive) ? 1 : 0) - (keys.contains(negative) ? 1 : 0);
    }

    private void look(double turn, double tilt) {
        if (camera == null) {
            return;
        }
        try {
            camera = camera.look(turn, tilt);
            dirty = true;
        } catch (IllegalArgumentException outside) {
            // Pitch/yaw bound reached.
        }
    }

    private void present(Delivery delivery) {
        var frame = delivery.frame();
        if (closed
                || delivery.generation() != generation
                || frame.sequence() <= presentedSequence
                || frame.revision() < floor
                || frame.width() != width
                || frame.height() != height) {
            return;
        }
        var bitmap =
                image.getImage() instanceof WritableImage existing
                                && (int) existing.getWidth() == frame.width()
                                && (int) existing.getHeight() == frame.height()
                        ? existing
                        : new WritableImage(frame.width(), frame.height());
        bitmap.getPixelWriter()
                .setPixels(
                        0,
                        0,
                        frame.width(),
                        frame.height(),
                        PixelFormat.getIntArgbPreInstance(),
                        frame.pixels(),
                        frame.width());
        image.setImage(bitmap);
        presentedSequence = frame.sequence();
    }

    boolean presented() {
        return presentedSequence > 0;
    }

    private void stopPolling() {
        polling = false;
        ++generation;
        frames.clear();
        timer.stop();
    }

    @Override
    public void close() {
        closeAsync();
    }

    /**
     * Closes the view and releases its GPU request; completes when that request has drained. Later
     * calls return the same drain.
     */
    CompletableFuture<Void> closeAsync() {
        if (closed) {
            return drained.copy();
        }
        closed = true;
        stopPolling();
        // Cancellation callbacks may reach lower services; never run them on FX.
        Thread.ofVirtual().name("global-interior-floors-cancel").start(lookup::cancel);
        drained = service.closeOwner(owner);
        return drained.copy();
    }

    /** Gives keyboard focus to the interior viewport (inline use in the World view). */
    void focusViewport() {
        viewport.requestFocus();
    }
}
