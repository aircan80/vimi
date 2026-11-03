package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.BuildLocationMaps;
import com.vibeminer.concepts.locations.BuildLocations;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.MapTypes;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Selected-build artwork with source-labelled spawn or pickup observations. */
final class LocationMapView extends VBox implements AutoCloseable {
    private static final int MAX_OBSERVATIONS = 512, MAX_TILES = 32;

    private record Point(LocationTypes.Location location, MapTypes.Candidate candidate) {}

    private record Choice(MapTypes.MapInfo map, List<Point> points) {
        @Override
        public String toString() {
            return (map.name() == null || map.name().isBlank() ? "Map " + map.id() : map.name())
                    + " · "
                    + points.size();
        }
    }

    private record Batch(
            List<Choice> choices, int observations, int unresolved, List<String> issues) {}

    private final Supplier<BuildLocationMaps> maps;
    private final Supplier<BuildLocations> providers;
    private final LongSupplier revision, sourceRevision;
    private final boolean quest;
    private final ComboBox<Choice> choices = new ComboBox<>();
    private final Label status = new Label(), evidence = new Label(), artStatus = new Label();
    private final ProgressIndicator locationActivity = activity("location-map-activity"),
            artworkActivity = activity("location-map-art-activity");
    private final ThreadPoolExecutor queries = worker("location-map-queries"),
            pixels = worker("location-map-pixels");
    private CompletableFuture<?> lookupTask, artworkTask;
    private long imagePixels;

    private record TilePixels(int width, int height, int stride, byte[] bgra) {}

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ProgressIndicator activity(String id) {
        var value = new ProgressIndicator(-1);
        value.setId(id);
        value.setPrefSize(18, 18);
        value.setMaxSize(18, 18);
        value.setAccessibleText(id.replace('-', ' '));
        value.setVisible(false);
        value.setManaged(false);
        return value;
    }

    private static void show(ProgressIndicator indicator, boolean value) {
        indicator.setVisible(value);
        indicator.setManaged(value);
    }

    private void cancelArtwork() {
        if (artworkTask != null) artworkTask.cancel(false);
        pixels.getQueue().clear();
        show(artworkActivity, false);
    }

    private <T> CompletableFuture<T> artworkRequest(
            java.util.function.Supplier<CompletableFuture<T>> action) {
        try {
            return CompletableFuture.supplyAsync(action, pixels).thenCompose(value -> value);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private final Canvas canvas = new Canvas(300, 200);
    private final Tooltip tooltip = new Tooltip();
    private final Map<Long, Image> images = new LinkedHashMap<>();
    private Cancellation token = new Cancellation(), artToken = new Cancellation();
    private BuildLocations source;
    private BuildLocationMaps bound;
    private MapTypes.Artwork artwork;
    private long generation, artGeneration, boundSourceRevision, boundRevision;
    private String artDescription = "";
    private int unavailableTiles;
    private boolean closed, setting;

    LocationMapView(
            Supplier<BuildLocationMaps> maps,
            Supplier<BuildLocations> providers,
            LongSupplier revision,
            boolean quest) {
        this(maps, providers, revision, () -> 0L, quest);
    }

    LocationMapView(
            Supplier<BuildLocationMaps> maps,
            Supplier<BuildLocations> providers,
            LongSupplier revision,
            LongSupplier sourceRevision,
            boolean quest) {
        super(8);
        this.sourceRevision = sourceRevision;
        this.maps = maps;
        this.providers = providers;
        this.revision = revision;
        this.quest = quest;
        var heading = new Label(quest ? "Quest pickup locations" : "Recorded locations");
        heading.getStyleClass().add("eyebrow");
        status.setWrapText(true);
        evidence.setWrapText(true);
        artStatus.setWrapText(true);
        status.getStyleClass().add("muted");
        evidence.getStyleClass().add("muted");
        artStatus.getStyleClass().add("muted");
        choices.setMaxWidth(Double.MAX_VALUE);
        choices.getStyleClass().add("build-choice");
        choices.setId("location-map-choice");
        status.setId("location-map-status");
        canvas.setId("location-map-canvas");
        artStatus.setId("location-map-art-status");
        choices.setAccessibleText("Location map");
        choices.setOnAction(
                event -> {
                    if (!setting) loadArtwork();
                });
        canvas.setAccessibleText("Blizzard zone map with recorded location markers");
        canvas.widthProperty().bind(widthProperty());
        canvas.widthProperty()
                .addListener(
                        (o, a, b) -> {
                            resizeCanvas();
                            draw();
                        });
        canvas.setOnMouseMoved(
                event -> {
                    var selected = choices.getValue();
                    if (selected == null) return;
                    String text = "";
                    for (var point : selected.points()) {
                        double dx = event.getX() - point.candidate().x() * canvas.getWidth();
                        double dy = event.getY() - point.candidate().y() * canvas.getHeight();
                        if (dx * dx + dy * dy < 100) {
                            text = describe(point);
                            break;
                        }
                    }
                    tooltip.setText(text);
                });
        Tooltip.install(canvas, tooltip);
        getChildren()
                .addAll(
                        heading,
                        choices,
                        canvas,
                        new HBox(8, artworkActivity, artStatus),
                        new HBox(8, locationActivity, status),
                        evidence);
        select(null);
    }

    void select(Long id) {
        generation++;
        artGeneration++;
        if (lookupTask != null) lookupTask.cancel(false);
        queries.getQueue().clear();
        cancelArtwork();
        show(locationActivity, false);
        token.cancel();
        artToken.cancel();
        token = new Cancellation();
        artToken = new Cancellation();
        if (source != null
                && (closed
                        || id == null
                        || bound != maps.get()
                        || boundSourceRevision != sourceRevision.getAsLong())) {
            source.closeAsync();
            source = null;
        }
        setting = true;
        choices.getItems().clear();
        setting = false;
        images.clear();
        imagePixels = 0;
        artwork = null;
        bound = null;
        draw();
        evidence.setText("");
        artStatus.setText("");
        if (closed || id == null) {
            status.setText("Select a record to locate it.");
            return;
        }
        bound = maps.get();
        boundSourceRevision = sourceRevision.getAsLong();
        boundRevision = revision.getAsLong();
        if (bound == null) {
            status.setText("Build map tables are unavailable.");
            return;
        }
        if (source == null) source = providers.get();
        var selectedSource = source;
        var selectedMaps = bound;
        var cancel = token;
        long request = generation,
                version = revision.getAsLong(),
                sourcesVersion = boundSourceRevision;
        status.setText("Resolving prepared source locations…");
        show(locationActivity, true);
        CompletableFuture<Batch> lookup;
        try {
            lookup =
                    CompletableFuture.supplyAsync(
                            () -> load(id, selectedSource, selectedMaps, cancel), queries);
        } catch (RuntimeException error) {
            lookup = CompletableFuture.failedFuture(error);
        }
        lookupTask = lookup;
        lookup.whenComplete(
                (batch, error) ->
                        Platform.runLater(
                                () -> {
                                    if (closed || request != generation) return;
                                    show(locationActivity, false);
                                    if (version != revision.getAsLong()
                                            || selectedMaps != maps.get()
                                            || sourcesVersion != sourceRevision.getAsLong()) {
                                        status.setText(
                                                "Location sources changed. Select again to"
                                                    + " update.");
                                        return;
                                    }
                                    if (error != null || batch == null) {
                                        status.setText(
                                                "Locations unavailable. Select again to"
                                                        + " retry.");
                                        return;
                                    }
                                    setting = true;
                                    choices.getItems().setAll(batch.choices());
                                    choices.getSelectionModel().selectFirst();
                                    setting = false;
                                    status.setText(
                                            batch.observations()
                                                    + " observations · "
                                                    + batch.unresolved()
                                                    + " without a unique drawable point"
                                                    + (batch.observations() == MAX_OBSERVATIONS
                                                            ? " · observation limit reached"
                                                            : ""));
                                    evidence.setText(
                                            "Source coverage and build compatibility may be"
                                                    + " partial. Base map artwork does not"
                                                    + " evaluate character phase or visibility."
                                                    + " Blue: declared spawn. Amber: observed"
                                                    + " player interaction, approximate."
                                                    + (batch.issues().isEmpty()
                                                            ? ""
                                                            : "\n"
                                                                    + String.join(
                                                                            " · ",
                                                                            batch.issues().stream()
                                                                                    .limit(4)
                                                                                    .toList())));
                                    if (batch.choices().isEmpty())
                                        status.setText(
                                                status.getText()
                                                        + " · No drawable map relationship"
                                                        + " recorded.");
                                    loadArtwork();
                                }));
    }

    private static <T> T await(CompletableFuture<T> result, Cancellation cancel) {
        while (true) {
            cancel.check();
            try {
                return result.get(50, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException pending) {
                /* Recheck owned cancellation. */
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new java.util.concurrent.CompletionException(failure.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Map lookup interrupted");
            }
        }
    }

    private Batch load(
            long id, BuildLocations sources, BuildLocationMaps maps, Cancellation cancel) {
        var locations = new ArrayList<LocationTypes.Location>();
        var issues = new ArrayList<String>();
        if (quest) {
            var result = await(sources.pickups(id, cancel), cancel);
            issues.addAll(result.issues());
            for (var pickup : result.pickups()) {
                issues.addAll(pickup.locations().issues());
                for (var location : pickup.locations().locations()) {
                    if (locations.size() >= MAX_OBSERVATIONS) break;
                    locations.add(location);
                }
                if (pickup.entity().kind() == LocationTypes.EntityKind.ITEM)
                    issues.add(
                            "Item "
                                    + pickup.entity().id()
                                    + " starts this quest; no intrinsic pickup position.");
            }
        } else {
            var result =
                    await(
                            sources.locations(
                                    new LocationTypes.Entity(LocationTypes.EntityKind.NPC, id),
                                    cancel),
                            cancel);
            issues.addAll(result.issues());
            locations.addAll(result.locations().stream().limit(MAX_OBSERVATIONS).toList());
        }
        var byMap = new LinkedHashMap<Long, List<Point>>();
        var mapInfo = new LinkedHashMap<Long, MapTypes.MapInfo>();
        int unresolved = 0;
        for (var location : locations) {
            cancel.check();
            MapTypes.Resolution resolved;
            try {
                resolved = await(maps.resolve(location, cancel), cancel);
            } catch (RuntimeException error) {
                cancel.check();
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof java.util.concurrent.CancellationException cancellation)
                    throw cancellation;
                unresolved++;
                issue(
                        issues,
                        "A location could not be resolved; other readable observations retained.");
                continue;
            }
            for (var diagnostic : resolved.issues()) issue(issues, diagnostic);
            if (resolved.state() != MapTypes.State.READY) issue(issues, resolved.message());
            var alternatives = new LinkedHashMap<Long, List<MapTypes.Candidate>>();
            for (var candidate : resolved.candidates()) {
                issue(issues, candidate.message());
                alternatives
                        .computeIfAbsent(candidate.map().id(), ignored -> new ArrayList<>())
                        .add(candidate);
            }
            boolean drawable = false;
            for (var entry : alternatives.entrySet()) {
                var candidate = entry.getValue().getFirst();
                mapInfo.put(entry.getKey(), candidate.map());
                byMap.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
                var drawableCandidates =
                        entry.getValue().stream()
                                .filter(c -> c.x() != null && c.y() != null)
                                .toList();
                if (drawableCandidates.isEmpty()) continue;
                candidate = drawableCandidates.getFirst();
                var chosen = candidate;
                boolean unique =
                        drawableCandidates.stream()
                                .allMatch(
                                        other ->
                                                Math.abs(other.x() - chosen.x()) < .000001
                                                        && Math.abs(other.y() - chosen.y())
                                                                < .000001);
                if (!unique) continue;
                mapInfo.put(entry.getKey(), candidate.map());
                byMap.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new Point(location, candidate));
                drawable = true;
            }
            if (!drawable) unresolved++;
        }
        var result = new ArrayList<Choice>();
        byMap.forEach(
                (idKey, points) -> result.add(new Choice(mapInfo.get(idKey), List.copyOf(points))));
        return new Batch(List.copyOf(result), locations.size(), unresolved, List.copyOf(issues));
    }

    private void loadArtwork() {
        artToken.cancel();
        artToken = new Cancellation();
        cancelArtwork();
        long request = ++artGeneration, selection = generation;
        var choice = choices.getValue();
        var selectedMaps = bound;
        var cancel = artToken;
        images.clear();
        imagePixels = 0;
        artwork = null;
        unavailableTiles = 0;
        artStatus.setText("");
        resizeCanvas();
        draw();
        if (closed || choice == null || selectedMaps == null) return;
        artStatus.setText("Loading selected-build base map artwork…");
        show(artworkActivity, true);
        var requestFuture = artworkRequest(() -> selectedMaps.artwork(choice.map().id(), cancel));
        artworkTask = requestFuture;
        requestFuture.whenComplete(
                (result, error) ->
                        Platform.runLater(
                                () -> {
                                    if (!current(selection, request, selectedMaps)) return;
                                    if (error != null || result == null) {
                                        show(artworkActivity, false);
                                        artStatus.setText(
                                                "Map artwork unavailable; recorded markers"
                                                        + " retained.");
                                        return;
                                    }
                                    artwork = result;
                                    resizeCanvas();
                                    draw();
                                    artDescription =
                                            result.state()
                                                    + " · "
                                                    + result.message()
                                                    + (result.issues().isEmpty()
                                                            ? ""
                                                            : " · "
                                                                    + String.join(
                                                                            " · ",
                                                                            result.issues().stream()
                                                                                    .limit(3)
                                                                                    .toList()));
                                    artStatus.setText(artDescription);
                                    if (result.layer() == null || result.tiles().isEmpty()) {
                                        show(artworkActivity, false);
                                        return;
                                    }
                                    if (result.tiles().size() > MAX_TILES)
                                        artStatus.setText(
                                                artDescription
                                                        + " · Artwork tile limit reached;"
                                                        + " readable prefix shown.");
                                    loadTile(result, 0, selection, request, selectedMaps, cancel);
                                }));
    }

    private boolean current(long selection, long request, BuildLocationMaps selectedMaps) {
        if (closed || selection != generation || request != artGeneration) return false;
        if (selectedMaps != maps.get()
                || boundSourceRevision != sourceRevision.getAsLong()
                || boundRevision != revision.getAsLong()) {
            show(artworkActivity, false);
            artStatus.setText(
                    "Map sources changed; recorded markers retained. Select again to update.");
            return false;
        }
        return true;
    }

    private void loadTile(
            MapTypes.Artwork art,
            int index,
            long selection,
            long request,
            BuildLocationMaps selectedMaps,
            Cancellation cancel) {
        if (!current(selection, request, selectedMaps)) return;
        int total = Math.min(MAX_TILES, art.tiles().size());
        if (index >= total) {
            show(artworkActivity, false);
            tileProgress(index, total, false, art.tiles().size() > MAX_TILES);
            return;
        }
        show(artworkActivity, true);
        tileProgress(index, total, true, art.tiles().size() > MAX_TILES);
        var tile = art.tiles().get(index);
        var converted =
                artworkRequest(() -> selectedMaps.tile(art.map().id(), tile.rowId(), cancel))
                        .thenApplyAsync(
                                result -> {
                                    cancel.check();
                                    if (result == null
                                            || result.image() == null
                                            || result.image().image() == null) return null;
                                    var data = result.image().image();
                                    int width = data.width(),
                                            height = data.height(),
                                            stride = data.rowStride();
                                    if (width < 1
                                            || height < 1
                                            || width > 8192
                                            || height > 8192
                                            || (long) width * height > 16_777_216
                                            || stride != width * 4)
                                        throw new IllegalArgumentException(
                                                "Map tile exceeds preview bounds");
                                    var bytes = data.rgba();
                                    if (bytes.length != stride * height)
                                        throw new IllegalArgumentException(
                                                "Map tile pixel length disagrees");
                                    var value = new TilePixels(width, height, stride, bgra(bytes));
                                    cancel.check();
                                    return value;
                                },
                                pixels);
        artworkTask = converted;
        converted.whenComplete(
                (data, error) ->
                        Platform.runLater(
                                () -> {
                                    if (!current(selection, request, selectedMaps)) return;
                                    if (error == null
                                            && data != null
                                            && imagePixels + (long) data.width() * data.height()
                                                    <= 16_777_216) {
                                        var image = new WritableImage(data.width(), data.height());
                                        image.getPixelWriter()
                                                .setPixels(
                                                        0,
                                                        0,
                                                        data.width(),
                                                        data.height(),
                                                        PixelFormat.getByteBgraInstance(),
                                                        data.bgra(),
                                                        0,
                                                        data.stride());
                                        images.put(tile.rowId(), image);
                                        imagePixels += (long) data.width() * data.height();
                                        draw();
                                    } else unavailableTiles++;
                                    loadTile(
                                            art,
                                            index + 1,
                                            selection,
                                            request,
                                            selectedMaps,
                                            cancel);
                                }));
    }

    private void tileProgress(int completed, int total, boolean loading, boolean limited) {
        artStatus.setText(
                artDescription
                        + " · "
                        + completed
                        + "/"
                        + total
                        + " map tiles"
                        + (loading ? " · loading…" : "")
                        + (unavailableTiles == 0
                                ? ""
                                : " · "
                                        + unavailableTiles
                                        + " unavailable tiles; readable tiles retained")
                        + (limited ? " · Artwork tile limit reached; readable prefix shown." : ""));
    }

    private static void issue(List<String> issues, String text) {
        if (text == null || text.isBlank() || issues.size() >= 32 || issues.contains(text)) return;
        issues.add(text.length() > 400 ? text.substring(0, 400) + "…" : text);
    }

    private static byte[] bgra(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i += 4) {
            byte red = bytes[i];
            bytes[i] = bytes[i + 2];
            bytes[i + 2] = red;
        }
        return bytes;
    }

    private void resizeCanvas() {
        double ratio =
                artwork != null && artwork.layer() != null
                        ? (double) artwork.layer().height() / artwork.layer().width()
                        : 2.0 / 3;
        canvas.setHeight(Math.max(1, canvas.getWidth() * ratio));
    }

    private void draw() {
        var gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth(), height = canvas.getHeight();
        gc.setFill(Color.web("#182127"));
        gc.fillRect(0, 0, width, height);
        if (artwork != null && artwork.layer() != null) {
            var layer = artwork.layer();
            double sx = width / layer.width(), sy = height / layer.height();
            for (var tile : artwork.tiles()) {
                var image = images.get(tile.rowId());
                if (image == null) continue;
                gc.drawImage(
                        image,
                        0,
                        0,
                        Math.min(image.getWidth(), tile.visibleWidth()),
                        Math.min(image.getHeight(), tile.visibleHeight()),
                        tile.x() * sx,
                        tile.y() * sy,
                        tile.visibleWidth() * sx,
                        tile.visibleHeight() * sy);
            }
        }
        var choice = choices.getValue();
        if (choice == null) return;
        for (var point : choice.points()) {
            double x = point.candidate().x() * width, y = point.candidate().y() * height;
            gc.setFill(
                    point.location().origin() == LocationTypes.Origin.OBSERVED_INTERACTION
                            ? Color.web("#ffcb63")
                            : Color.web("#77d5ff"));
            gc.fillOval(x - 4, y - 4, 8, 8);
            gc.setStroke(Color.web("#101518"));
            gc.strokeOval(x - 4, y - 4, 8, 8);
        }
    }

    private static String describe(Point point) {
        var source = point.location().evidence().source();
        return point.location().entity().kind()
                + " "
                + point.location().entity().id()
                + " · "
                + source.provider()
                + "\n"
                + point.location().origin()
                + " · "
                + String.format(
                        java.util.Locale.ROOT,
                        "%.2f, %.2f",
                        point.candidate().x() * 100,
                        point.candidate().y() * 100)
                + "\n"
                + point.candidate().basis()
                + " · assignment "
                + point.candidate().assignmentId()
                + (point.location().phase() == null
                        ? ""
                        : " · recorded phase " + point.location().phase())
                + "\nSnapshot: "
                + source.snapshot()
                + (source.build() == null ? "" : " · " + source.build());
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        artGeneration++;
        token.cancel();
        artToken.cancel();
        cancelArtwork();
        if (lookupTask != null) lookupTask.cancel(false);
        show(locationActivity, false);
        queries.shutdownNow();
        pixels.shutdownNow();
        if (source != null) source.closeAsync();
        images.clear();
        imagePixels = 0;
        Tooltip.uninstall(canvas, tooltip);
    }
}
