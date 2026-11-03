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
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    private final Canvas canvas = new Canvas(300, 200);
    private final Tooltip tooltip = new Tooltip();
    private final Map<Long, Image> images = new LinkedHashMap<>();
    private Cancellation token = new Cancellation(), artToken = new Cancellation();
    private BuildLocations source;
    private BuildLocationMaps bound;
    private MapTypes.Artwork artwork;
    private long generation, artGeneration, boundSourceRevision;
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
        getChildren().addAll(heading, choices, canvas, artStatus, status, evidence);
        select(null);
    }

    void select(Long id) {
        generation++;
        artGeneration++;
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
        CompletableFuture.supplyAsync(
                        () -> load(id, selectedSource, selectedMaps, cancel),
                        command -> Thread.startVirtualThread(command))
                .whenComplete(
                        (batch, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || request != generation
                                                    || version != revision.getAsLong()
                                                    || selectedMaps != maps.get()
                                                    || sourcesVersion != sourceRevision.getAsLong())
                                                return;
                                            if (error != null) {
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
                                                            + (batch.observations()
                                                                            == MAX_OBSERVATIONS
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
                                                                                    batch
                                                                                            .issues()
                                                                                            .stream()
                                                                                            .limit(
                                                                                                    4)
                                                                                            .toList())));
                                            if (batch.choices().isEmpty())
                                                status.setText(
                                                        status.getText()
                                                                + " · No drawable map relationship"
                                                                + " recorded.");
                                            loadArtwork();
                                        }));
    }

    private Batch load(
            long id, BuildLocations sources, BuildLocationMaps maps, Cancellation cancel) {
        var locations = new ArrayList<LocationTypes.Location>();
        var issues = new ArrayList<String>();
        if (quest) {
            var result = sources.pickups(id, cancel).join();
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
                    sources.locations(
                                    new LocationTypes.Entity(LocationTypes.EntityKind.NPC, id),
                                    cancel)
                            .join();
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
                resolved = maps.resolve(location, cancel).join();
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
        long request = ++artGeneration, selection = generation;
        var choice = choices.getValue();
        var selectedMaps = bound;
        var cancel = artToken;
        images.clear();
        artwork = null;
        unavailableTiles = 0;
        artStatus.setText("");
        resizeCanvas();
        draw();
        if (closed || choice == null || selectedMaps == null) return;
        artStatus.setText("Loading selected-build base map artwork…");
        selectedMaps
                .artwork(choice.map().id(), cancel)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (!current(selection, request, selectedMaps)) return;
                                            if (error != null) {
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
                                                                                    result
                                                                                            .issues()
                                                                                            .stream()
                                                                                            .limit(
                                                                                                    3)
                                                                                            .toList()));
                                            artStatus.setText(artDescription);
                                            if (result.layer() == null
                                                    || result.tiles().isEmpty()) {
                                                return;
                                            }
                                            if (result.tiles().size() > MAX_TILES)
                                                artStatus.setText(
                                                        artDescription
                                                                + " · Artwork tile limit reached;"
                                                                + " readable prefix shown.");
                                            loadTile(
                                                    result,
                                                    0,
                                                    selection,
                                                    request,
                                                    selectedMaps,
                                                    cancel);
                                        }));
    }

    private boolean current(long selection, long request, BuildLocationMaps selectedMaps) {
        return !closed
                && selection == generation
                && request == artGeneration
                && selectedMaps == maps.get()
                && boundSourceRevision == sourceRevision.getAsLong();
    }

    private void loadTile(
            MapTypes.Artwork art,
            int index,
            long selection,
            long request,
            BuildLocationMaps selectedMaps,
            Cancellation cancel) {
        if (index >= Math.min(MAX_TILES, art.tiles().size())
                || !current(selection, request, selectedMaps)) return;
        var tile = art.tiles().get(index);
        selectedMaps
                .tile(art.map().id(), tile.rowId(), cancel)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (!current(selection, request, selectedMaps)) return;
                                            if (error == null
                                                    && result.image() != null
                                                    && result.image().image() != null) {
                                                var data = result.image().image();
                                                var image =
                                                        new WritableImage(
                                                                data.width(), data.height());
                                                image.getPixelWriter()
                                                        .setPixels(
                                                                0,
                                                                0,
                                                                data.width(),
                                                                data.height(),
                                                                PixelFormat.getByteBgraInstance(),
                                                                bgra(data.rgba()),
                                                                0,
                                                                data.rowStride());
                                                images.put(tile.rowId(), image);
                                                draw();
                                            } else {
                                                unavailableTiles++;
                                                artStatus.setText(
                                                        artDescription
                                                                + " · "
                                                                + unavailableTiles
                                                                + " unavailable tiles; readable"
                                                                + " tiles retained");
                                            }
                                            loadTile(
                                                    art,
                                                    index + 1,
                                                    selection,
                                                    request,
                                                    selectedMaps,
                                                    cancel);
                                        }));
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
        if (source != null) source.closeAsync();
        images.clear();
        Tooltip.uninstall(canvas, tooltip);
    }
}
