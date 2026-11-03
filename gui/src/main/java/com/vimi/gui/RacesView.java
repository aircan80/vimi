package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceTypes;
import com.vibeminer.formats.images.BlpImageTypes;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Race/class records and client racial cards; no character-creation eligibility is inferred. */
final class RacesView extends BorderPane implements AutoCloseable {
    private final Supplier<BuildRaces> handles;
    private final LongSupplier revisions;
    private final AsyncIconRequests icons;
    private final ListView<RaceTypes.Race> races = new ListView<>();
    private final CheckBox otherRecords = new CheckBox("Include other race records");
    private final VBox details = new VBox(18), racialRail = new VBox(14);
    private RaceModelPanel modelPanel;
    private final Label status = text("Loading race records…", "muted");
    private BuildRaces bound;
    private List<RaceTypes.Race> catalog = List.of();
    private Cancellation token = new Cancellation();
    private long generation, revision = -1;
    private Long selected;
    private boolean loaded, closed, catalogLoading;
    private final java.util.concurrent.ThreadPoolExecutor iconWorker =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(64),
                    runnable -> {
                        var thread = new Thread(runnable, "race-icon-pixels");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private record IconPixels(int width, int height, int[] argb) {}

    private static VBox activity(String message) {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(28, 28);
        spinner.setMaxSize(28, 28);
        spinner.setAccessibleText(message);
        var box = new VBox(12, spinner, text(message, "muted"));
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        box.setAccessibleText(message);
        return box;
    }

    private static <T> CompletableFuture<T> request(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    RacesView(BuildPreparation.Job job) {
        this(
                () -> nativeWork(job).racesHandle(),
                () -> nativeWork(job).revision(),
                (id, token) -> nativeWork(job).icon(id, token));
    }

    RacesView(
            Supplier<BuildRaces> handles,
            LongSupplier revisions,
            BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> fetch) {
        this.handles = handles;
        this.revisions = revisions;
        icons = new AsyncIconRequests(2, 64, fetch);
        races.setAccessibleText("Build race records");
        races.getStyleClass().add("product-menu");
        races.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(RaceTypes.Race race, boolean empty) {
                                super.updateItem(race, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || race == null) return;
                                VBox item =
                                        new VBox(
                                                4,
                                                text(race.name(), "product-name"),
                                                text(
                                                        race.recordedClasses() == 0
                                                                ? "Other race record"
                                                                : race.recordedClasses()
                                                                        + " recorded classes",
                                                        "product-code"));
                                item.prefWidthProperty().bind(widthProperty().subtract(24));
                                setPrefWidth(0);
                                setGraphic(item);
                            }
                        });
        races.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (observable, before, after) -> {
                            if (after != null) select(after);
                        });
        otherRecords.setWrapText(true);
        otherRecords.setOnAction(event -> filter());
        VBox sidebar = new VBox(16, text("RACES", "eyebrow"), otherRecords, races);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(210);
        VBox.setVgrow(races, Priority.ALWAYS);
        setLeft(sidebar);
        Button reload = new Button("Reload");
        reload.setOnAction(
                event -> {
                    invalidate();
                    ensureLoaded();
                });
        HBox heading = new HBox(12, text("Races", "h1"), space(), reload);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox header =
                new VBox(
                        10,
                        heading,
                        text("Race and racial ability records from this build", "subtitle"),
                        status);
        header.setPadding(new Insets(24, 28, 12, 28));
        details.setPadding(new Insets(12, 28, 28, 28));
        details.setMaxWidth(1150);
        var scroll = new ScrollPane(details);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        BorderPane main = new BorderPane(scroll);
        main.setTop(header);
        main.setMinWidth(0);
        setCenter(main);
        racialRail.setPadding(new Insets(24, 16, 24, 16));
        racialRail.getStyleClass().add("race-racial-rail");
        var railScroll = new ScrollPane(racialRail);
        railScroll.setFitToWidth(true);
        railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        railScroll.setMinWidth(290);
        railScroll.setPrefWidth(310);
        railScroll.setMaxWidth(310);
        setRight(railScroll);
        clear();
    }

    private static NativePreparationWork nativeWork(BuildPreparation.Job job) {
        if (job.work() instanceof NativePreparationWork work) return work;
        throw new IllegalStateException("Races require a native build workspace");
    }

    void invalidate() {
        if (closed) return;
        loaded = false;
        catalogLoading = false;
        generation++;
        token.cancel();
        token = new Cancellation();
        icons.clear();
        catalog = List.of();
        races.getItems().clear();
        clear();
    }

    void ensureLoaded() {
        if (closed) return;
        BuildRaces handle = handles.get();
        long current = revisions.getAsLong();
        if (handle != bound || current != revision) {
            invalidate();
            bound = handle;
            revision = current;
        }
        if (loaded) return;
        if (handle == null) {
            races.setPlaceholder(text("Race records are unavailable.", "muted"));
            status.setText("Race records are unavailable. Prepare a build to continue.");
            return;
        }
        loaded = true;
        long request = ++generation;
        status.setText("Loading race records…");
        catalogLoading = true;
        races.setPlaceholder(activity("Loading race records…"));
        details.getChildren().setAll(activity("Loading race catalog…"));
        racialRail
                .getChildren()
                .setAll(text("Racial abilities", "h2"), text("Waiting for race records…", "muted"));
        request(() -> handle.catalog(token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            catalogLoading = false;
                                            if (error != null || result == null) {
                                                loaded = false;
                                                clear();
                                                races.setPlaceholder(
                                                        text(
                                                                "Race records are unavailable."
                                                                    + " Reload to retry.",
                                                                "muted"));
                                                details.getChildren()
                                                        .setAll(
                                                                text(
                                                                        "Race records are"
                                                                            + " unavailable. Reload"
                                                                            + " to retry.",
                                                                        "muted"));
                                                status.setText(
                                                        "Race records are unavailable. Reload to"
                                                            + " retry.");
                                                return;
                                            }
                                            races.setPlaceholder(
                                                    text("No race records are readable.", "muted"));
                                            catalog = result.races();
                                            status.setText(
                                                    result.message() != null
                                                            ? result.message()
                                                            : result.state()
                                                                            == RaceTypes.State
                                                                                    .PARTIAL
                                                                    ? "Partial client data ·"
                                                                          + " readable records are"
                                                                          + " shown"
                                                                    : "Recorded client data ·"
                                                                          + " choose a race");
                                            filter();
                                        }));
    }

    private void filter() {
        if (catalogLoading) return;
        var visible =
                catalog.stream()
                        .filter(race -> otherRecords.isSelected() || race.recordedClasses() > 0)
                        .toList();
        if (visible.isEmpty() && !catalog.isEmpty()) {
            otherRecords.setSelected(true);
            visible = catalog;
        }
        races.getItems().setAll(visible);
        if (!visible.isEmpty())
            races.getSelectionModel()
                    .select(
                            visible.stream()
                                    .filter(race -> selected != null && race.id() == selected)
                                    .findFirst()
                                    .orElse(visible.getFirst()));
        else clear();
    }

    private void select(RaceTypes.Race race) {
        if (modelPanel != null) {
            modelPanel.close();
            modelPanel = null;
        }
        racialRail.getChildren().setAll(activity("Loading racial abilities…"));
        selected = race.id();
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        details.getChildren().setAll(text(race.name(), "h1"), activity("Loading race overview…"));
        icons.retain(java.util.Set.of());
        var handle = bound;
        request(() -> handle.overview(race.id(), token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null
                                                    || result == null
                                                    || result.race() == null) {
                                                racialRail
                                                        .getChildren()
                                                        .setAll(
                                                                text(
                                                                        "Racial ability records are"
                                                                            + " unavailable.",
                                                                        "muted"));
                                                details.getChildren()
                                                        .setAll(
                                                                text(race.name(), "h1"),
                                                                text(
                                                                        result != null
                                                                                        && result
                                                                                                        .message()
                                                                                                != null
                                                                                ? result.message()
                                                                                : "Race overview is"
                                                                                      + " unavailable.",
                                                                        "muted"));
                                                return;
                                            }
                                            show(result, request);
                                        }));
    }

    private void show(RaceTypes.Overview result, long request) {
        var race = result.race();
        status.setText(
                result.state() == RaceTypes.State.PARTIAL
                        ? "Partial client data · readable records are shown"
                        : "Recorded client data · choose a race");
        Label initial =
                text(race.name().isBlank() ? "?" : race.name().substring(0, 1), "race-initial");
        StackPane badge = new StackPane(initial);
        badge.getStyleClass().add("race-badge");
        badge.setMinSize(68, 68);
        badge.setMaxSize(68, 68);
        String faction =
                result.faction() != null && !result.faction().isBlank()
                        ? result.faction()
                        : result.factionTemplateId() != null && result.factionTemplateId() > 0
                                ? "Faction template #" + result.factionTemplateId()
                                : "Faction not recorded";
        VBox identity = new VBox(7, text(race.name(), "h1"), text(faction, "race-faction"));
        HBox title = new HBox(18, badge, identity);
        title.setAlignment(Pos.CENTER_LEFT);
        var lore =
                text(
                        result.lore() == null || result.lore().isBlank()
                                ? "Lore is unavailable in the readable client data."
                                : result.lore(),
                        "race-lore");
        VBox abilities = new VBox(12, text("Racial abilities", "h2"));
        if (result.abilities().isEmpty())
            abilities
                    .getChildren()
                    .add(
                            text(
                                    "No racial ability descriptions are readable for this race.",
                                    "muted"));
        var wanted = new java.util.HashSet<Long>();
        for (var ability : result.abilities()) {
            StackPane art = new StackPane(text("—", "talent-placeholder"));
            art.setAccessibleText("Racial icon reference unavailable");
            art.setMinSize(30, 30);
            art.setPrefSize(30, 30);
            art.setMaxSize(30, 30);
            art.getStyleClass().add("race-ability-icon");
            VBox words =
                    new VBox(
                            7,
                            text(ability.name(), "race-racial-name"),
                            text(
                                    ability.description() == null || ability.description().isBlank()
                                            ? "Description unavailable."
                                            : ability.description(),
                                    "race-description"));
            words.setMinWidth(0);
            HBox.setHgrow(words, Priority.ALWAYS);
            HBox card = new HBox(9, art, words);
            card.setAlignment(Pos.TOP_LEFT);
            card.getStyleClass().add("race-racial-card");
            abilities.getChildren().add(card);
            if (ability.iconFileDataId() != null) {
                wanted.add(ability.iconFileDataId());
                loadIcon(ability.iconFileDataId(), art, request);
            }
        }
        icons.retain(wanted);
        VBox evidence =
                new VBox(
                        9,
                        text("ChrRaces ID " + race.id(), "muted"),
                        text("Class links: CharBaseInfo → ChrClasses", "muted"),
                        text(
                                "Racial cards: ChrRaceRacialAbility. Cards are not inferred SpellID"
                                    + " links.",
                                "muted"));
        for (var issue : result.issues()) evidence.getChildren().add(text(issue, "muted"));
        TitledPane source = new TitledPane("Source details", evidence);
        source.setExpanded(false);
        if (modelPanel != null) modelPanel.close();
        modelPanel =
                new RaceModelPanel(
                        bound, handles, revisions, revision, race.id(), result.classes());
        TitledPane lorePanel = new TitledPane("Lore", lore);
        lorePanel.setExpanded(false);
        details.getChildren().setAll(title, modelPanel, lorePanel, source);
        racialRail.getChildren().setAll(abilities);
    }

    private void loadIcon(long id, StackPane target, long request) {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.setAccessibleText("Loading racial icon");
        target.setAccessibleText("Loading racial icon");
        target.getChildren().setAll(spinner);
        icons.request(id)
                .thenApplyAsync(
                        result -> {
                            if (result == null
                                    || result.state() != BlpImageTypes.State.READY
                                    || result.image() == null) return null;
                            var pixels = result.image();
                            int width = pixels.width(),
                                    height = pixels.height(),
                                    stride = pixels.rowStride();
                            if (width < 1
                                    || height < 1
                                    || width > 64
                                    || height > 64
                                    || stride < width * 4
                                    || stride > 256)
                                throw new IllegalArgumentException(
                                        "Icon dimensions exceed preview bounds");
                            byte[] rgba = pixels.rgba();
                            if (rgba.length != stride * height)
                                throw new IllegalArgumentException("Icon pixel length disagrees");
                            int[] argb = new int[width * height];
                            for (int y = 0; y < height; y++)
                                for (int x = 0; x < width; x++) {
                                    int at = y * stride + x * 4;
                                    argb[y * width + x] =
                                            (rgba[at + 3] & 255) << 24
                                                    | (rgba[at] & 255) << 16
                                                    | (rgba[at + 1] & 255) << 8
                                                    | (rgba[at + 2] & 255);
                                }
                            return new IconPixels(width, height, argb);
                        },
                        iconWorker)
                .whenComplete(
                        (pixels, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null || pixels == null) {
                                                target.setAccessibleText("Racial icon unavailable");
                                                target.getChildren().setAll(text("—", "muted"));
                                                return;
                                            }
                                            var bitmap =
                                                    new WritableImage(
                                                            pixels.width(), pixels.height());
                                            bitmap.getPixelWriter()
                                                    .setPixels(
                                                            0,
                                                            0,
                                                            pixels.width(),
                                                            pixels.height(),
                                                            PixelFormat.getIntArgbInstance(),
                                                            pixels.argb(),
                                                            0,
                                                            pixels.width());
                                            var image = new ImageView(bitmap);
                                            image.setFitWidth(30);
                                            image.setFitHeight(30);
                                            image.setPreserveRatio(true);
                                            target.setAccessibleText("Racial ability icon");
                                            target.getChildren().setAll(image);
                                        }));
    }

    private boolean stale(long request) {
        return closed
                || request != generation
                || handles.get() != bound
                || revisions.getAsLong() != revision;
    }

    private void clear() {
        if (modelPanel != null) {
            modelPanel.close();
            modelPanel = null;
        }
        racialRail
                .getChildren()
                .setAll(
                        text("Racial abilities", "h2"),
                        text("Choose a race to inspect its racial abilities.", "muted"));
        details.getChildren()
                .setAll(
                        text("Choose a race", "h2"),
                        text(
                                "Recorded race/class entries appear in the sidebar. Include other"
                                    + " records to inspect internal or unlinked race data.",
                                "muted"));
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        icons.close();
        iconWorker.shutdownNow();
        clear();
        races.setPlaceholder(text("Race browser closed.", "muted"));
        if (modelPanel != null) modelPanel.close();
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Region space() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
