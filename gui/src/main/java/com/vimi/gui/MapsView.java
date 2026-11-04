package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.BuildLocationMaps;
import com.vibeminer.concepts.locations.MapNavigationGraph;
import com.vibeminer.concepts.locations.MapNavigationSourceTypes;
import com.vibeminer.concepts.locations.MapNavigationTypes;
import com.vibeminer.concepts.locations.MapTypes;
import com.vibeminer.concepts.locations.MapVisitHistory;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/** Hierarchical selected-build Maps browser. Source and image work stays on one bounded worker. */
final class MapsView extends BorderPane implements AutoCloseable {
    private static final int MAX_PIXELS = 2048 * 2048, MAX_TILES = 32;
    private record Choice(MapNavigationTypes.Node node) {
        @Override public String toString() {
            return (node.name() == null || node.name().isBlank() ? "Map" : node.name())
                    + " · " + node.id().value();
        }
    }
    private record Picture(int width, int height, int[] pixels, String details, List<String> diagnostics) {}
    private record Selection(MapNavigationGraph.Regions regions, Picture picture) {}
    private static final class Request {
        final Cancellation token = new Cancellation();
        final CompletableFuture<Void> canceled = new CompletableFuture<>();
        final Runnable action;
        boolean cancelRequested;
        Request(Runnable action) { this.action = action; }
    }

    private final Supplier<BuildLocationMaps> maps;
    private final LongSupplier revision;
    private final Supplier<com.vibeminer.formats.tables.BuildTables> tables;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("maps-browser-source").factory());
    private final ExecutorService cancellation = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("maps-browser-cancel").factory());
    private final Object lock = new Object();
    private Request active, pending;
    private boolean running, closed;
    private long generation, loadedRevision = Long.MIN_VALUE;
    private final ComboBox<Choice> picker = new ComboBox<>();
    private final ListView<Choice> children = new ListView<>();
    private final HBox breadcrumbs = new HBox(4);
    private final Label status = new Label("Open Maps to load the selected build's map hierarchy."),
            details = new Label(), heading = new Label("Maps"), overlaps = new Label();
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final Button back = new Button("Back"), up = new Button("Up"), fit = new Button("Fit"),
            refresh = new Button("Reload maps"), prepare = new Button("Prepare map tables");
    private final Canvas canvas = new Canvas(800, 520);
    private final StackPane viewport = new StackPane(canvas);
    private final ComboBox<Choice> overlapChoices = new ComboBox<>();
    private BuildLocationMaps loadedSource, graphSource;
    private long graphRevision;
    private MapNavigationGraph graph;
    private MapVisitHistory history;
    private MapNavigationGraph.Regions regions;
    private WritableImage image;
    private MapNavigationTypes.UiMapId presented;
    private boolean setting;
    private double dragX, dragY;

    MapsView(Supplier<BuildLocationMaps> maps, LongSupplier revision) {
        this(maps, revision, () -> null);
    }
    MapsView(Supplier<BuildLocationMaps> maps, LongSupplier revision,
            Supplier<com.vibeminer.formats.tables.BuildTables> tables) {
        this.tables = tables;
        this.maps = maps;
        this.revision = revision;
        setId("maps-browser");
        back.setId("maps-back"); up.setId("maps-up");
        status.setId("maps-status"); details.setId("maps-source-details"); canvas.setId("maps-canvas");
        overlapChoices.setId("maps-overlaps");
        heading.getStyleClass().add("eyebrow");
        picker.setId("maps-picker"); picker.setMaxWidth(500);
        picker.setAccessibleText("Switch map");
        children.setId("maps-children"); children.setPrefWidth(240);
        children.setAccessibleText("Child maps; double-click to open");
        activity.setPrefSize(28, 28); activity.setMaxSize(28, 28);
        activity.setId("maps-activity"); activity.setAccessibleText("Map data or artwork loading");
        activity.setMouseTransparent(true);
        status.setWrapText(true); details.setWrapText(true);
        var source = new TitledPane("Source details", details); source.setExpanded(false);
        setTop(new VBox(8, heading, new HBox(8, back, up, picker, fit, refresh, prepare), breadcrumbs));
        setLeft(new VBox(8, new Label("Explore child maps"), children, overlaps, overlapChoices));
        VBox.setVgrow(children, javafx.scene.layout.Priority.ALWAYS);
        viewport.getChildren().add(activity);
        viewport.setMinSize(100, 100);
        canvas.widthProperty().bind(viewport.widthProperty());
        canvas.heightProperty().bind(viewport.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        setCenter(viewport); setBottom(new VBox(8, status, source));
        BorderPane.setMargin(viewport, new javafx.geometry.Insets(12));
        picker.setOnAction(e -> { if (!setting && picker.getValue() != null) open(picker.getValue().node().id()); });
        children.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && children.getSelectionModel().getSelectedItem() != null)
            open(children.getSelectionModel().getSelectedItem().node().id()); });
        overlapChoices.setOnAction(e -> { if (!setting && overlapChoices.getValue() != null) open(overlapChoices.getValue().node().id()); });
        back.setOnAction(e -> { if (currentGraph() && history != null && history.canBack()) { history = history.back(); select(); } });
        up.setOnAction(e -> { if (currentGraph() && history != null) { history = history.up(); select(); } });
        fit.setOnAction(e -> { if (currentGraph() && history != null) { history = history.view(MapNavigationTypes.View.fit()); draw(); } });
        refresh.setOnAction(e -> reload());
        prepare.setId("maps-prepare");
        prepare.setTooltip(new javafx.scene.control.Tooltip(
                "Prepare the map hierarchy for this build. Missing source files may download."));
        prepare.setOnAction(e -> prepareTables());
        canvas.setOnScroll(e -> {
            if (!currentGraph() || history == null) return;
            var view = history.current().view();
            history = history.view(new MapNavigationTypes.View(view.centerX(), view.centerY(),
                    Math.clamp(view.zoom() * (e.getDeltaY() > 0 ? 1.2 : 1 / 1.2), 1, 32)));
            draw(); e.consume();
        });
        canvas.setOnMousePressed(e -> { dragX = e.getX(); dragY = e.getY(); });
        canvas.setOnMouseDragged(e -> {
            if (!currentGraph() || history == null) return;
            var view = history.current().view();
            var box = bounds();
            history = history.view(new MapNavigationTypes.View(
                    Math.clamp(view.centerX() - (e.getX() - dragX) / box[2], 0, 1),
                    Math.clamp(view.centerY() - (e.getY() - dragY) / box[3], 0, 1), view.zoom()));
            dragX = e.getX(); dragY = e.getY(); draw();
        });
        canvas.setOnMouseClicked(e -> {
            if (!currentGraph() || !e.isStillSincePress() || regions == null || history == null
                    || !history.current().map().equals(presented)) return;
            var box = bounds();
            double x = (e.getX() - box[0]) / box[2], y = (e.getY() - box[1]) / box[3];
            var hit = regions.hit(x, y);
            if (hit.state() == MapNavigationGraph.HitState.UNIQUE) open(hit.choices().getFirst());
            else if (hit.state() == MapNavigationGraph.HitState.AMBIGUOUS) {
                setting = true;
                overlapChoices.getItems().setAll(hit.choices().stream()
                        .map(id -> new Choice(graph.node(id).orElseThrow())).toList());
                overlapChoices.setValue(null); setting = false;
                overlaps.setText("Overlapping recorded areas — choose a map:");
            }
        });
        overlapChoices.setPromptText("Choose overlapping map");
        busy(false); controls(); draw();
    }

    private boolean currentGraph() {
        return graph != null && graphSource == maps.get() && graphRevision == revision.getAsLong();
    }
    void ensureLoaded() {
        if (closed) return;
        if (loadedRevision != revision.getAsLong() || loadedSource != maps.get()) reload();
    }
    void invalidate() {
        loadedRevision = Long.MIN_VALUE; generation++; busy(false);
        graph = null; history = null; regions = null; graphSource = null; image = null;
        setting = true; picker.getItems().clear(); setting = false; controls(); draw();
        Request cancel = null;
        synchronized (lock) {
            pending = null;
            if (active != null && !active.cancelRequested) { active.cancelRequested = true; cancel = active; }
        }
        cancel(cancel);
    }

    private void prepareTables() {
        if (closed) return;
        var selectedTables = tables.get();
        if (selectedTables == null) return;
        long request = ++generation, version = revision.getAsLong();
        var source = maps.get(); busy(true);
        status.setText("Preparing selected map tables; missing source data may download…");
        queue(() -> {
            var token = token();
            try {
                for (String name : List.of("UiMap", "UiMapAssignment")) {
                    token.check();
                    var result = selectedTables.prepareNativeEvidence(
                            com.vibeminer.formats.tables.TableTypes.Ref.forName(name),
                            com.vibeminer.formats.tables.TableTypes.Priority.INTERACTIVE,
                            com.vibeminer.formats.tables.NativePreparationTypes.SourceAccess.CONFIGURED_SOURCE,
                            progress -> publish(request, version, source,
                                    () -> status.setText(name + " · " + progress.phase() + "…")), token).join();
                    if (result.state() != com.vibeminer.formats.tables.NativePreparationTypes.State.READY) {
                        publish(request, version, source, () -> {
                            busy(false); status.setText(name + " preparation " + result.state()
                                    + (result.table() == null ? "" : " · " + result.table().reason()));
                        });
                        return;
                    }
                }
                publish(request, version, source, () -> {
                    if (selectedTables == tables.get()) reload();
                    else { busy(false); loadedRevision = Long.MIN_VALUE; loadedSource = null;
                        status.setText("Map table source changed. Reload maps to continue."); }
                });
            } catch (RuntimeException failure) {
                failure(request, version, source, "Map table preparation could not finish.", failure);
            }
        });
    }

    private void busy(boolean value) {
        activity.setVisible(value); activity.setManaged(value);
    }

    private void reload() {
        if (closed) return;
        long request = ++generation, version = revision.getAsLong();
        var source = maps.get();
        loadedRevision = version; loadedSource = source;
        graph = null; history = null; regions = null; graphSource = null; image = null; presented = null;
        setting = true; picker.getItems().clear(); setting = false; controls(); draw();
        busy(true); status.setText("Loading prepared map hierarchy…");
        queue(() -> {
            var token = token();
            try {
                var result = source == null ? MapNavigationSourceTypes.Result.unsupported()
                        : source.navigation(token).join();
                token.check();
                List<Choice> choices = result.graph() == null ? List.of() : result.graph().nodes().stream()
                        .sorted(Comparator.comparing(n -> n.name() == null ? "" : n.name(), String.CASE_INSENSITIVE_ORDER))
                        .map(Choice::new).toList();
                publish(request, version, source, () -> {
                    busy(false); graph = result.graph(); graphSource = source; graphRevision = version; history = null; regions = null; image = null; presented = null;
                    setting = true; picker.getItems().setAll(choices); picker.setValue(null); setting = false;
                    details.setText(result.state() + " · " + String.join(" · ", result.diagnostics())
                            + "\nNavigation uses prepared selected-build tables. Base artwork does not evaluate character phase."
                            + " Child outlines are recorded assignment rectangles, not zone boundaries.");
                    if (graph == null || choices.isEmpty()) {
                        status.setText("Map hierarchy unavailable (" + result.state()
                                + "). Reload after preparing the selected build's map tables.");
                        controls(); draw(); return;
                    }
                    var initial = graph.node(new MapNavigationTypes.UiMapId(947))
                            .orElseGet(() -> choices.stream().map(Choice::node)
                                    .filter(n -> n.parent().known() && n.parent().id() == null)
                                    .findFirst().orElse(choices.getFirst().node()));
                    history = MapVisitHistory.start(graph, initial.id()); select();
                });
            } catch (RuntimeException failure) { failure(request, version, source, "Map hierarchy could not be loaded.", failure); }
        });
    }

    private void open(MapNavigationTypes.UiMapId id) {
        if (!currentGraph() || history == null) return;
        history = history.switchTo(id); select();
    }

    private void select() {
        long request = ++generation, version = revision.getAsLong();
        var source = maps.get(); var selectedGraph = graph; var id = history.current().map();
        controls(); busy(true); regions = null;
        overlaps.setText(""); setting = true; overlapChoices.getItems().clear(); setting = false;
        status.setText("Loading " + selectedGraph.node(id).map(n -> new Choice(n).toString()).orElse("map") + "…");
        queue(() -> {
            var token = token();
            try {
                var area = selectedGraph.regions(id, MapNavigationGraph.MAX_PAIR_TESTS, token::isCanceled);
                var picture = source == null ? new Picture(0, 0, null, "Map artwork source unavailable.", List.of("MAP_ARTWORK_SOURCE_UNAVAILABLE"))
                        : picture(source, selectedGraph.scope(), id.value(), token, request, version);
                token.check();
                var result = new Selection(area, picture);
                publish(request, version, source, () -> {
                    regions = result.regions(); presented = id; image = null;
                    var data = result.picture();
                    if (data.pixels() != null) {
                        image = new WritableImage(data.width(), data.height());
                        image.getPixelWriter().setPixels(0, 0, data.width(), data.height(),
                                PixelFormat.getIntArgbInstance(), data.pixels(), 0, data.width());
                    }
                    busy(false);
                    status.setText(data.details());
                    if (area.state() != MapNavigationGraph.RegionState.READY)
                        status.setText(status.getText() + " · Click regions " + area.state());
                    details.setText(details.getText().split("\nSelected map:", 2)[0]
                            + "\nSelected map: " + id.value() + " · " + area.children().size()
                            + " child maps · " + area.regions().size() + " assignment rectangles"
                            + " · " + area.issues().stream().map(i -> i.reason().name()).distinct().limit(8).toList()
                            + (data.diagnostics().isEmpty() ? "" : "\nArtwork: " + String.join(" · ", data.diagnostics())));
                    draw();
                });
            } catch (RuntimeException failure) { failure(request, version, source, "Map artwork could not be loaded.", failure); }
        });
    }

    private Picture picture(BuildLocationMaps source, MapNavigationTypes.Scope scope, long id, Cancellation token, long request, long version) {
        var art = source.artwork(id, token).join(); token.check();
        if (art == null || art.map() == null || art.map().id() != id
                || !scope.build().equals(art.build()) || !scope.locale().equals(art.locale()))
            throw new IllegalArgumentException("Map artwork source binding differs");
        if (art.layer() == null || art.tiles().isEmpty()) return new Picture(0, 0, null,
                art.state() + " · " + art.message(), art.issues().stream().limit(8).map(MapsView::bounded).toList());
        int width = art.layer().width(), height = art.layer().height();
        if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > MAX_PIXELS)
            return new Picture(0, 0, null, "Map artwork exceeds this browser's image limit.", List.of("MAP_IMAGE_LIMIT"));
        int[] pixels = new int[width * height]; java.util.Arrays.fill(pixels, 0xff152332);
        int readable = 0, count = Math.min(MAX_TILES, art.tiles().size());
        var unavailable = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < count; i++) {
            token.check(); var tile = art.tiles().get(i);
            int completed = i;
            publish(request, version, source, () -> status.setText("Loading map artwork " + completed + "/" + count + "…"));
            MapTypes.TileImage result;
            try { result = source.tile(id, tile.rowId(), token).join(); }
            catch (java.util.concurrent.CompletionException failure) {
                token.check(); issue(unavailable, "A map tile source request failed."); continue;
            }
            token.check();
            if (result == null || result.state() != MapTypes.State.READY || !tile.equals(result.tile())
                    || result.image() == null
                    || result.image().state() != com.vibeminer.formats.images.BlpImageTypes.State.READY
                    || result.image().image() == null) {
                issue(unavailable, result == null ? "Map tile returned no image result."
                        : !tile.equals(result.tile()) ? "Map tile descriptor differs from its requested source."
                        : result.message() == null ? "Map tile " + result.state() : result.message());
                continue;
            }
            var data = result.image().image();
            if (data.width() < 1 || data.height() < 1 || data.width() > 2048 || data.height() > 2048
                    || (long) data.width() * data.height() > MAX_PIXELS || data.rowStride() != data.width() * 4)
                throw new IllegalArgumentException("Map tile dimensions");
            byte[] rgba = data.rgba();
            if (rgba.length != data.rowStride() * data.height()) throw new IllegalArgumentException("Map tile length");
            if (tile.x() < 0 || tile.y() < 0 || tile.visibleWidth() < 1 || tile.visibleHeight() < 1
                    || tile.visibleWidth() > data.width() || tile.visibleHeight() > data.height()
                    || (long) tile.x() + tile.visibleWidth() > width || (long) tile.y() + tile.visibleHeight() > height)
                throw new IllegalArgumentException("Map tile placement");
            for (int y = 0; y < tile.visibleHeight(); y++) {
                token.check();
                for (int x = 0; x < tile.visibleWidth(); x++) {
                    int at = y * data.rowStride() + x * 4;
                    pixels[(tile.y() + y) * width + tile.x() + x] = (rgba[at + 3] & 255) << 24
                            | (rgba[at] & 255) << 16 | (rgba[at + 1] & 255) << 8 | rgba[at + 2] & 255;
                }
            }
            readable++;
        }
        return new Picture(width, height, pixels, art.state() + " · " + readable + "/"
                + art.tiles().size() + " readable artwork tiles"
                + (art.tiles().size() > MAX_TILES ? " · tile limit reached" : "")
                + " · " + art.message(), List.copyOf(unavailable));
    }

    private static String bounded(String text) {
        return text == null ? "Unavailable source detail" : text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
    private static void issue(java.util.Set<String> issues, String message) {
        if (issues.size() < 8) issues.add(bounded(message));
    }

    private void controls() {
        back.setDisable(history == null || !history.canBack());
        up.setDisable(history == null || history.hierarchy().parent().isEmpty());
        fit.setDisable(history == null);
        prepare.setDisable(tables.get() == null);
        setting = true;
        children.getItems().setAll(history == null ? List.of() : graph.children(history.current().map()).stream().map(Choice::new).toList());
        picker.setValue(history == null ? null : new Choice(graph.node(history.current().map()).orElseThrow()));
        setting = false; breadcrumbs.getChildren().clear();
        if (history != null) for (var node : history.hierarchy().path()) {
            var link = new Button(node.name() == null ? Long.toString(node.id().value()) : node.name());
            link.setOnAction(e -> open(node.id())); breadcrumbs.getChildren().add(link);
        }
    }

    private double[] bounds() {
        double ratio = image == null ? 1.5 : image.getWidth() / image.getHeight();
        double w = Math.max(1, Math.min(canvas.getWidth(), canvas.getHeight() * ratio)), h = w / ratio;
        var view = history == null ? MapNavigationTypes.View.fit() : history.current().view();
        w *= view.zoom(); h *= view.zoom();
        return new double[] {canvas.getWidth() / 2 - view.centerX() * w,
                canvas.getHeight() / 2 - view.centerY() * h, w, h};
    }

    private void draw() {
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#101b28")); gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        var box = bounds();
        boolean matching = history != null && history.current().map().equals(presented);
        if (image != null && matching) gc.drawImage(image, box[0], box[1], box[2], box[3]);
        else { gc.setFill(Color.LIGHTGRAY); gc.fillText(activity.isVisible() ? "Loading map…" : "Select a map to explore.", 24, 36); }
        if (regions != null && matching) {
            gc.setStroke(Color.color(.9, .77, .4, .6));
            for (var region : regions.regions().stream().limit(512).toList()) {
                var r = region.bounds(); gc.strokeRect(box[0] + r.minX() * box[2], box[1] + r.minY() * box[3],
                        (r.maxX() - r.minX()) * box[2], (r.maxY() - r.minY()) * box[3]);
            }
        }
    }

    private void publish(long request, long version, BuildLocationMaps source, Runnable action) {
        Platform.runLater(() -> {
            if (closed || request != generation) return;
            if (version != revision.getAsLong() || source != maps.get()) {
                busy(false); loadedRevision = Long.MIN_VALUE; loadedSource = null;
                graph = null; history = null; regions = null; graphSource = null; image = null;
                setting = true; picker.getItems().clear(); setting = false;
                status.setText("Map sources changed. Reload maps to continue."); controls(); draw();
                return;
            }
            action.run();
        });
    }
    private void failure(long request, long version, BuildLocationMaps source, String message, RuntimeException error) {
        publish(request, version, source, () -> { busy(false); status.setText(message + " Reload to retry."); draw(); });
    }
    private Cancellation token() { synchronized (lock) { return active.token; } }
    private void queue(Runnable action) {
        Request cancel = null;
        synchronized (lock) {
            if (closed) return;
            pending = new Request(action);
            if (active != null && !active.cancelRequested) { active.cancelRequested = true; cancel = active; }
            if (!running) { running = true; worker.execute(this::drain); }
        }
        cancel(cancel);
    }
    private void cancel(Request request) {
        if (request != null) cancellation.execute(() -> {
            try { request.token.cancel(); } finally { request.canceled.complete(null); }
        });
    }
    private void drain() {
        while (true) {
            Request request;
            synchronized (lock) {
                request = pending; pending = null;
                if (request == null || closed) { active = null; running = false; return; }
                active = request;
            }
            try { request.action.run(); }
            finally {
                boolean wait;
                synchronized (lock) { wait = request.cancelRequested; if (!wait) active = null; }
                if (wait) request.canceled.join();
                synchronized (lock) { active = null; }
            }
        }
    }
    @Override public void close() {
        Request cancel = null;
        synchronized (lock) {
            if (closed) return;
            closed = true; pending = null;
            if (active != null && !active.cancelRequested) { active.cancelRequested = true; cancel = active; }
        }
        generation++; busy(false); image = null; graph = null; history = null; regions = null;
        cancel(cancel); worker.shutdown(); cancellation.shutdown();
    }
}
