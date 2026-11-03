package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Race/class records and client racial cards; no character-creation eligibility is inferred. */
final class RacesView extends BorderPane implements AutoCloseable {
    private final Supplier<BuildRaces> handles;
    private final LongSupplier revisions;
    private final AsyncIconRequests icons;
    private final ListView<RaceTypes.Race> races = new ListView<>();
    private final CheckBox otherRecords = new CheckBox("Include other race records");
    private final VBox details = new VBox(22);
    private final Label status = text("Loading race records…", "muted");
    private BuildRaces bound;
    private List<RaceTypes.Race> catalog = List.of();
    private Cancellation token = new Cancellation();
    private long generation, revision = -1;
    private Long selected;
    private boolean loaded, closed;

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
        clear();
    }

    private static NativePreparationWork nativeWork(BuildPreparation.Job job) {
        if (job.work() instanceof NativePreparationWork work) return work;
        throw new IllegalStateException("Races require a native build workspace");
    }

    void invalidate() {
        if (closed) return;
        loaded = false;
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
        if (loaded || handle == null) return;
        loaded = true;
        long request = ++generation;
        status.setText("Loading race records…");
        handle.catalog(token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null || result == null) {
                                                loaded = false;
                                                status.setText(
                                                        "Race records are unavailable. Reload to retry.");
                                                return;
                                            }
                                            catalog = result.races();
                                            status.setText(
                                                    result.message() != null
                                                            ? result.message()
                                                            : result.state()
                                                                            == RaceTypes.State
                                                                                    .PARTIAL
                                                                    ? "Partial client data · readable records are shown"
                                                                    : "Recorded client data · choose a race");
                                            filter();
                                        }));
    }

    private void filter() {
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
        selected = race.id();
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        details.getChildren()
                .setAll(text(race.name(), "h1"), text("Loading race overview…", "muted"));
        icons.retain(java.util.Set.of());
        var handle = bound;
        handle.overview(race.id(), token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null
                                                    || result == null
                                                    || result.race() == null) {
                                                details.getChildren()
                                                        .setAll(
                                                                text(race.name(), "h1"),
                                                                text(
                                                                        result != null
                                                                                        && result
                                                                                                        .message()
                                                                                                != null
                                                                                ? result.message()
                                                                                : "Race overview is unavailable.",
                                                                        "muted"));
                                                return;
                                            }
                                            show(result, request);
                                        }));
    }

    private void show(RaceTypes.Overview result, long request) {
        var race = result.race();
        status.setText(result.state() == RaceTypes.State.PARTIAL
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
        VBox intro = new VBox(18, title, lore);
        intro.getStyleClass().add("card");
        FlowPane classes = new FlowPane(10, 10);
        classes.setAccessibleText("Recorded race and class combinations");
        for (var entry : result.classes()) {
            var chip = text(entry.name(), "badge");
            classes.getChildren().add(chip);
        }
        if (result.classes().isEmpty())
            classes.getChildren()
                    .add(text("No class combinations are readable for this race.", "muted"));
        VBox classSection =
                new VBox(
                        12,
                        text("Recorded classes", "h2"),
                        classes,
                        text(
                                "Client combinations do not establish account unlocks or current realm availability.",
                                "muted"));
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
            StackPane art = new StackPane(text("?", "talent-placeholder"));
            art.setMinSize(44, 44);
            art.setPrefSize(44, 44);
            art.setMaxSize(44, 44);
            art.getStyleClass().add("race-ability-icon");
            VBox words =
                    new VBox(
                            7,
                            text(ability.name(), "h2"),
                            text(
                                    ability.description() == null || ability.description().isBlank()
                                            ? "Description unavailable."
                                            : ability.description(),
                                    "race-description"));
            words.setMinWidth(0);
            HBox.setHgrow(words, Priority.ALWAYS);
            HBox card = new HBox(15, art, words);
            card.setAlignment(Pos.TOP_LEFT);
            card.getStyleClass().add("card");
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
                                "Racial cards: ChrRaceRacialAbility. Cards are not inferred SpellID links.",
                                "muted"));
        for (var issue : result.issues()) evidence.getChildren().add(text(issue, "muted"));
        TitledPane source = new TitledPane("Source details", evidence);
        source.setExpanded(false);
        details.getChildren().setAll(intro, classSection, abilities, source);
    }

    private void loadIcon(long id, StackPane target, long request) {
        icons.request(id)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)
                                                    || error != null
                                                    || result == null
                                                    || result.state() != BlpImageTypes.State.READY)
                                                return;
                                            var pixels = result.image();
                                            byte[] rgba = pixels.rgba();
                                            for (int at = 0; at < rgba.length; at += 4) {
                                                byte red = rgba[at];
                                                rgba[at] = rgba[at + 2];
                                                rgba[at + 2] = red;
                                            }
                                            WritableImage bitmap =
                                                    new WritableImage(
                                                            pixels.width(), pixels.height());
                                            bitmap.getPixelWriter()
                                                    .setPixels(
                                                            0,
                                                            0,
                                                            pixels.width(),
                                                            pixels.height(),
                                                            PixelFormat.getByteBgraInstance(),
                                                            rgba,
                                                            0,
                                                            pixels.width() * 4);
                                            ImageView image = new ImageView(bitmap);
                                            image.setFitWidth(44);
                                            image.setFitHeight(44);
                                            image.setPreserveRatio(true);
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
        details.getChildren()
                .setAll(
                        text("Choose a race", "h2"),
                        text(
                                "Recorded race/class entries appear in the sidebar. Include other records to inspect internal or unlinked race data.",
                                "muted"));
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        icons.close();
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
