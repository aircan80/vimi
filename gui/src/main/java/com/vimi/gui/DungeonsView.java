package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.dungeons.BuildDungeons;
import com.vibeminer.concepts.dungeons.DungeonTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Selected-build dungeon/raid list and exact client metadata; no inferred entrance or server
 * roster.
 */
final class DungeonsView extends BorderPane implements AutoCloseable {
    private record EncounterFilter(Long id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final Supplier<BuildDungeons> handles;
    private final LongSupplier revisions;
    private final String context;
    private final AsyncIconRequests icons;
    private final ListView<DungeonTypes.Dungeon> dungeons = new ListView<>();
    private final ListView<DungeonTypes.Encounter> encounters = new ListView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> kind = new ComboBox<>();
    private final ComboBox<EncounterFilter> encounterFilter = new ComboBox<>();
    private final Label status = text("Loading readable instance maps…", "muted"),
            count = text("", "muted"),
            heading = text("Choose a dungeon or raid", "h2"),
            identity = text("", "muted"),
            encounterCount = text("", "muted");
    private final VBox details = new VBox(18), sources = new VBox(14);
    private final ScrollPane scroller = new ScrollPane(details);
    private List<DungeonTypes.Dungeon> catalog = List.of();
    private List<DungeonTypes.Encounter> recordedEncounters = List.of();
    private BuildDungeons bound;
    private Cancellation catalogueToken = new Cancellation(), selectionToken = new Cancellation();
    private long generation, selectionGeneration, revision = -1;
    private Long selected, wanted;
    private boolean loaded, closed;

    DungeonsView(Supplier<BuildDungeons> handles, LongSupplier revisions, String context) {
        this(handles, revisions, context, null);
    }

    DungeonsView(
            Supplier<BuildDungeons> handles,
            LongSupplier revisions,
            String context,
            BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> iconFetch) {
        this.handles = Objects.requireNonNull(handles);
        this.revisions = Objects.requireNonNull(revisions);
        this.context = context;
        icons = iconFetch == null ? null : new AsyncIconRequests(2, 64, iconFetch);
        search.setPromptText("Search name or map ID");
        search.setAccessibleText("Search dungeons and raids");
        kind.getItems().setAll("Dungeons & raids", "Dungeons", "Raids");
        kind.getSelectionModel().selectFirst();
        kind.setAccessibleText("Instance type filter");
        kind.getStyleClass().add("build-choice");
        kind.setMaxWidth(Double.MAX_VALUE);
        dungeons.setAccessibleText("Build dungeon and raid records");
        dungeons.getStyleClass().add("product-menu");
        dungeons.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(DungeonTypes.Dungeon dungeon, boolean empty) {
                                super.updateItem(dungeon, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || dungeon == null) {
                                    return;
                                }
                                var content =
                                        new VBox(
                                                4,
                                                text(dungeon.name(), "product-name"),
                                                text(
                                                        label(dungeon.kind())
                                                                + " · Map #"
                                                                + dungeon.mapId(),
                                                        "product-code"));
                                content.prefWidthProperty().bind(widthProperty().subtract(24));
                                setPrefWidth(0);
                                setGraphic(content);
                            }
                        });
        dungeons.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, b, a) -> {
                            if (a != null && !Objects.equals(selected, a.mapId())) {
                                select(a);
                            }
                        });
        search.textProperty().addListener((o, b, a) -> filter());
        kind.setOnAction(e -> filter());
        var sidebar =
                new VBox(12, text("DUNGEONS & RAIDS", "eyebrow"), search, kind, count, dungeons);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(236);
        sidebar.setMinWidth(215);
        VBox.setVgrow(dungeons, Priority.ALWAYS);
        setLeft(sidebar);
        var reload = new Button("Reload");
        reload.setOnAction(
                e -> {
                    invalidate();
                    ensureLoaded();
                });
        var space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        var title = new HBox(12, text("Dungeons & raids", "h1"), space, reload);
        title.setAlignment(Pos.CENTER_LEFT);
        var header =
                new VBox(
                        9,
                        title,
                        text(
                                "Instance maps, difficulty facts and recorded client encounters",
                                "subtitle"),
                        status);
        header.setPadding(new Insets(24, 28, 12, 28));
        details.setPadding(new Insets(12, 28, 28, 28));
        details.setMinWidth(0);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var main = new BorderPane(scroller);
        main.setTop(header);
        main.setMinWidth(0);
        setCenter(main);
        sources.setPadding(new Insets(24, 16, 24, 16));
        sources.setPrefWidth(300);
        sources.getStyleClass().add("race-racial-rail");
        var sourceScroll = new ScrollPane(sources);
        sourceScroll.setFitToWidth(true);
        sourceScroll.setPrefWidth(300);
        sourceScroll.setMinWidth(260);
        setRight(sourceScroll);
        encounterFilter.setAccessibleText("Recorded encounter difficulty");
        encounterFilter.getStyleClass().add("build-choice");
        encounterFilter.setOnAction(e -> filterEncounters());
        encounters.setAccessibleText("Recorded dungeon encounters");
        encounters.setPrefHeight(260);
        encounters.setMinHeight(160);
        encounters.setCellFactory(ignored -> encounterCell());
        clearDetails("Choose a dungeon or raid to inspect its recorded details.");
    }

    void ensureLoaded() {
        if (closed) {
            return;
        }
        var handle = handles.get();
        long currentRevision = revisions.getAsLong();
        if (loaded && bound == handle && revision == currentRevision) {
            return;
        }
        if (handle == null) {
            status.setText("Dungeon data unavailable; see Overview.");
            return;
        }
        invalidate();
        bound = handle;
        revision = currentRevision;
        loaded = true;
        long request = generation;
        status.setText("Loading readable dungeon and raid maps…");
        handle.catalog(catalogueToken)
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request, handle)) {
                                                return;
                                            }
                                            if (failure != null || result == null) {
                                                status.setText(
                                                        "Dungeon records could not be loaded. Reload to retry.");
                                                return;
                                            }
                                            catalog = result.dungeons();
                                            status.setText(
                                                    result.message() != null
                                                            ? result.message()
                                                            : catalog.size()
                                                                    + " readable instance maps · "
                                                                    + context
                                                                    + (result.state()
                                                                                    == DungeonTypes
                                                                                            .State
                                                                                            .PARTIAL
                                                                            ? " · Some records are unavailable"
                                                                            : ""));
                                            filter();
                                            if (selected == null
                                                    && !dungeons.getItems().isEmpty()) {
                                                dungeons.getSelectionModel()
                                                        .select(
                                                                dungeons.getItems().stream()
                                                                        .filter(
                                                                                d ->
                                                                                        Objects
                                                                                                .equals(
                                                                                                        wanted,
                                                                                                        d
                                                                                                                .mapId()))
                                                                        .findFirst()
                                                                        .orElse(
                                                                                dungeons.getItems()
                                                                                        .getFirst()));
                                            }
                                        }));
    }

    private void filter() {
        String term =
                search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        String type = kind.getValue();
        var values =
                catalog.stream()
                        .filter(
                                d ->
                                        term.isEmpty()
                                                || d.name().toLowerCase(Locale.ROOT).contains(term)
                                                || Long.toString(d.mapId()).equals(term))
                        .filter(
                                d ->
                                        !"Dungeons".equals(type)
                                                || d.kind() == DungeonTypes.Kind.DUNGEON)
                        .filter(d -> !"Raids".equals(type) || d.kind() == DungeonTypes.Kind.RAID)
                        .toList();
        dungeons.getItems().setAll(values);
        count.setText(values.size() + " of " + catalog.size() + " records");
        dungeons.setPlaceholder(
                text(
                        catalog.isEmpty()
                                ? "No readable instance records"
                                : "No matches. Clear search or filters.",
                        "muted"));
        if (selected != null) {
            values.stream()
                    .filter(d -> Objects.equals(d.mapId(), selected))
                    .findFirst()
                    .ifPresent(d -> dungeons.getSelectionModel().select(d));
        }
    }

    private void select(DungeonTypes.Dungeon dungeon) {
        selected = dungeon.mapId();
        wanted = selected;
        selectionToken.cancel();
        selectionToken = new Cancellation();
        if (icons != null) {
            icons.clear();
        }
        long selection = ++selectionGeneration;
        long request = generation;
        BuildDungeons handle = bound;
        clearDetails("Loading recorded details…");
        heading.setText(dungeon.name());
        identity.setText(label(dungeon.kind()) + " · Map #" + dungeon.mapId());
        details.getChildren()
                .setAll(
                        heading,
                        identity,
                        text("Loading location, difficulties and encounter records…", "muted"));
        scroller.setVvalue(0);
        handle.overview(dungeon.mapId(), selectionToken)
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request, handle)
                                                    || selection != selectionGeneration
                                                    || !Objects.equals(selected, dungeon.mapId())) {
                                                return;
                                            }
                                            if (failure != null
                                                    || result == null
                                                    || result.dungeon() == null) {
                                                details.getChildren()
                                                        .setAll(
                                                                heading,
                                                                identity,
                                                                text(
                                                                        result != null
                                                                                        && result
                                                                                                        .message()
                                                                                                != null
                                                                                ? result.message()
                                                                                : "Dungeon details could not be loaded. The instance list remains available.",
                                                                        "muted"));
                                                return;
                                            }
                                            show(result);
                                        }));
    }

    private void show(DungeonTypes.Overview result) {
        var dungeon = result.dungeon();
        heading.setText(dungeon.name());
        identity.setText(label(dungeon.kind()) + " · Map #" + dungeon.mapId());
        details.getChildren().setAll(heading, identity);
        var capacity =
                metric(
                        "RECORDED MAP CAPACITY",
                        dungeon.maxPlayers() == null
                                ? "Unknown"
                                : dungeon.maxPlayers() + " players");
        var count = metric("ENCOUNTER RECORDS", Integer.toString(result.encounters().size()));
        var facts = new HBox(12, capacity, count);
        HBox.setHgrow(capacity, Priority.ALWAYS);
        HBox.setHgrow(count, Priority.ALWAYS);
        details.getChildren().add(facts);
        var location = new VBox(7, text("ASSOCIATED AREA", "eyebrow"));
        location.getStyleClass().add("source-panel");
        if (result.areas().isEmpty()) {
            location.getChildren()
                    .add(
                            text(
                                    dungeon.areaId() == null
                                            ? "Associated area unavailable"
                                            : "Area #" + dungeon.areaId() + " · name unavailable",
                                    "muted"));
        } else {
            location.getChildren()
                    .add(
                            text(
                                    String.join(
                                            "  ›  ",
                                            result.areas().stream()
                                                    .map(
                                                            a ->
                                                                    a.name() == null
                                                                                    || a.name()
                                                                                            .isBlank()
                                                                            ? "Area #" + a.id()
                                                                            : a.name())
                                                    .toList()),
                                    "value"));
            location.getChildren()
                    .add(
                            text(
                                    "Recorded area hierarchy · entrance coordinates are not supplied",
                                    "muted"));
        }
        details.getChildren().add(location);
        for (var description : result.descriptions()) {
            var card =
                    new VBox(
                            7,
                            text(description.title(), "eyebrow"),
                            text(description.text(), "research-details"));
            card.getStyleClass().add("source-panel");
            details.getChildren().add(card);
        }
        var difficultyBox = new VBox(8, text("Difficulties", "h2"));
        if (result.difficulties().isEmpty()) {
            difficultyBox.getChildren().add(text("No readable map-difficulty records", "muted"));
        } else {
            for (var difficulty : result.difficulties()) {
                var title = text(difficulty.name() + " · #" + difficulty.difficultyId(), "value");
                var room = new Region();
                HBox.setHgrow(room, Priority.ALWAYS);
                var row =
                        new HBox(
                                10,
                                title,
                                room,
                                text(
                                        difficulty.maxPlayers() == null
                                                ? "Capacity unavailable"
                                                : difficulty.maxPlayers() + " players",
                                        "muted"));
                row.setAlignment(Pos.CENTER_LEFT);
                var card = new VBox(5, row);
                card.getStyleClass().add("source-panel");
                card.setStyle("-fx-padding: 12;");
                if (difficulty.message() != null && !difficulty.message().isBlank()) {
                    card.getChildren().add(text(difficulty.message(), "research-details"));
                }
                difficultyBox.getChildren().add(card);
            }
        }
        details.getChildren().add(difficultyBox);
        if (!result.queues().isEmpty()) {
            var queues = new VBox(8, text("Queue records", "h2"));
            for (var queue : result.queues()) {
                String levels =
                        queue.minLevel() == null && queue.maxLevel() == null
                                ? "Level bounds unavailable"
                                : "Recorded levels "
                                        + (queue.minLevel() == null ? "?" : queue.minLevel())
                                        + "–"
                                        + (queue.maxLevel() == null ? "?" : queue.maxLevel());
                queues.getChildren()
                        .add(
                                text(
                                        (queue.name() == null
                                                        ? "Queue #" + queue.recordId()
                                                        : queue.name())
                                                + " · "
                                                + levels,
                                        "research-details"));
            }
            details.getChildren().add(queues);
        }
        recordedEncounters = result.encounters();
        var filters = new java.util.LinkedHashMap<Long, String>();
        for (var difficulty : result.difficulties()) {
            filters.put(difficulty.difficultyId(), difficulty.name());
        }
        for (var encounter : recordedEncounters) {
            if (encounter.difficultyId() != null) {
                filters.putIfAbsent(
                        encounter.difficultyId(),
                        encounter.difficultyName() == null
                                ? "Difficulty #" + encounter.difficultyId()
                                : encounter.difficultyName());
            }
        }
        encounterFilter.getItems().setAll(new EncounterFilter(null, "All recorded difficulties"));
        filters.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                encounterFilter
                                        .getItems()
                                        .add(
                                                new EncounterFilter(
                                                        e.getKey(),
                                                        e.getValue() + " · #" + e.getKey())));
        encounterFilter.getSelectionModel().selectFirst();
        filterEncounters();
        details.getChildren()
                .addAll(
                        new HBox(12, text("Recorded encounters", "h2"), encounterFilter),
                        encounterCount,
                        encounters);
        showSources(result);
    }

    private void filterEncounters() {
        var filter = encounterFilter.getValue();
        var values =
                recordedEncounters.stream()
                        .filter(
                                e ->
                                        filter == null
                                                || filter.id() == null
                                                || Objects.equals(filter.id(), e.difficultyId()))
                        .toList();
        encounters.getItems().setAll(values);
        encounterCount.setText(values.size() + " readable encounter records");
        encounters.setPlaceholder(text("No matching readable encounter records", "muted"));
        if (icons != null) {
            icons.retain(
                    values.stream()
                            .map(DungeonTypes.Encounter::iconFileDataId)
                            .filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private ListCell<DungeonTypes.Encounter> encounterCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DungeonTypes.Encounter encounter, boolean empty) {
                super.updateItem(encounter, empty);
                setText(null);
                setGraphic(null);
                if (empty || encounter == null) {
                    return;
                }
                var image = new ImageView();
                image.setFitWidth(32);
                image.setFitHeight(32);
                image.setPreserveRatio(true);
                var icon = new StackPane(text("·", "muted"), image);
                icon.setMinSize(32, 32);
                icon.setPrefSize(32, 32);
                icon.setStyle("-fx-background-color: #e5f1eb; -fx-background-radius: 5;");
                var copy =
                        new VBox(
                                4,
                                text(encounter.name(), "value"),
                                text(
                                        (encounter.difficultyName() == null
                                                        ? "Difficulty not recorded"
                                                        : encounter.difficultyName()
                                                                + " · #"
                                                                + encounter.difficultyId())
                                                + " · Encounter #"
                                                + encounter.id(),
                                        "muted"));
                var row = new HBox(12, icon, copy);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(5));
                setGraphic(row);
                long request = selectionGeneration;
                if (icons != null && encounter.iconFileDataId() != null) {
                    icons.request(encounter.iconFileDataId())
                            .whenComplete(
                                    (result, failure) ->
                                            Platform.runLater(
                                                    () -> {
                                                        if (closed
                                                                || request != selectionGeneration
                                                                || getItem() != encounter
                                                                || failure != null
                                                                || result == null
                                                                || result.image() == null) {
                                                            return;
                                                        }
                                                        var source = result.image();
                                                        if (source.width() < 1
                                                                || source.height() < 1
                                                                || source.width() > 128
                                                                || source.height() > 128) {
                                                            return;
                                                        }
                                                        byte[] rgba = source.rgba();
                                                        if (rgba.length
                                                                != source.width()
                                                                        * source.height()
                                                                        * 4) {
                                                            return;
                                                        }
                                                        int[] pixels =
                                                                new int
                                                                        [source.width()
                                                                                * source.height()];
                                                        for (int i = 0; i < pixels.length; i++) {
                                                            pixels[i] =
                                                                    (rgba[i * 4 + 3] & 255) << 24
                                                                            | (rgba[i * 4] & 255)
                                                                                    << 16
                                                                            | (rgba[i * 4 + 1]
                                                                                            & 255)
                                                                                    << 8
                                                                            | (rgba[i * 4 + 2]
                                                                                    & 255);
                                                        }
                                                        var bitmap =
                                                                new WritableImage(
                                                                        source.width(),
                                                                        source.height());
                                                        bitmap.getPixelWriter()
                                                                .setPixels(
                                                                        0,
                                                                        0,
                                                                        source.width(),
                                                                        source.height(),
                                                                        PixelFormat
                                                                                .getIntArgbInstance(),
                                                                        pixels,
                                                                        0,
                                                                        source.width());
                                                        image.setImage(bitmap);
                                                    }));
                }
            }
        };
    }

    private void showSources(DungeonTypes.Overview result) {
        sources.getChildren()
                .setAll(
                        text("Source details", "h2"),
                        text(context, "muted"),
                        text("Selected-build Blizzard records", "value"));
        for (var entry : result.sources()) {
            String coverage =
                    entry.counts() == null
                            ? "Coverage not recorded"
                            : entry.counts().readableRows()
                                    + " / "
                                    + entry.counts().declaredRows()
                                    + " readable rows";
            sources.getChildren()
                    .add(
                            new VBox(
                                    4,
                                    text(entry.name(), "value"),
                                    text(
                                            coverage
                                                    + " · "
                                                    + entry.state()
                                                            .toString()
                                                            .toLowerCase(Locale.ROOT),
                                            "muted")));
        }
        var notes = new VBox(8);
        for (String issue : result.issues()) {
            notes.getChildren().add(text(issue, "muted"));
        }
        if (result.issues().isEmpty()) {
            notes.getChildren().add(text("No source diagnostics", "muted"));
        }
        var diagnostics = new TitledPane("Source notes (" + result.issues().size() + ")", notes);
        diagnostics.setExpanded(false);
        sources.getChildren().add(diagnostics);
        var references = new VBox(8, text("Map #" + result.dungeon().mapId(), "muted"));
        for (var area : result.areas()) {
            references
                    .getChildren()
                    .add(
                            text(
                                    "Area #"
                                            + area.id()
                                            + " · parent "
                                            + area.parentId()
                                            + " · map "
                                            + area.mapId(),
                                    "muted"));
        }
        for (var difficulty : result.difficulties()) {
            references
                    .getChildren()
                    .add(
                            text(
                                    "MapDifficulty #"
                                            + difficulty.recordId()
                                            + " · difficulty "
                                            + difficulty.difficultyId(),
                                    "muted"));
        }
        for (var encounter : result.encounters().stream().limit(128).toList()) {
            references
                    .getChildren()
                    .add(
                            text(
                                    "Encounter #"
                                            + encounter.id()
                                            + " · display "
                                            + encounter.displayId(),
                                    "muted"));
        }
        if (result.encounters().size() > 128) {
            references
                    .getChildren()
                    .add(
                            text(
                                    "Showing the first 128 encounter references; all retained encounter records remain in the list.",
                                    "muted"));
        }
        var ids = new TitledPane("Record references", references);
        ids.setExpanded(false);
        sources.getChildren().add(ids);
    }

    private static VBox metric(String title, String value) {
        var card = new VBox(8, text(title, "eyebrow"), text(value, "h2"));
        card.getStyleClass().add("source-panel");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static String label(DungeonTypes.Kind kind) {
        return kind == DungeonTypes.Kind.RAID ? "Raid" : "Dungeon";
    }

    private static Label text(String value, String style) {
        var label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private boolean stale(long request, BuildDungeons handle) {
        return closed
                || generation != request
                || bound != handle
                || revision != revisions.getAsLong()
                || handles.get() != handle;
    }

    private void clearDetails(String message) {
        details.getChildren().setAll(text(message, "muted"));
        sources.getChildren()
                .setAll(
                        text("Source details", "h2"),
                        text("Select an instance to inspect its sources.", "muted"));
        recordedEncounters = List.of();
        encounters.getItems().clear();
    }

    void invalidate() {
        generation++;
        selectionGeneration++;
        catalogueToken.cancel();
        selectionToken.cancel();
        catalogueToken = new Cancellation();
        selectionToken = new Cancellation();
        if (icons != null) {
            icons.clear();
        }
        loaded = false;
        selected = null;
        catalog = List.of();
        dungeons.getItems().clear();
        clearDetails("Choose a dungeon or raid.");
    }

    @Override
    public void close() {
        closed = true;
        invalidate();
        if (icons != null) {
            icons.close();
        }
    }
}
