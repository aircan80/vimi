package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.professions.BuildProfessions;
import com.vibeminer.concepts.professions.ProfessionTypes;
import com.vibeminer.formats.images.BlpImageTypes;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Build-local recipe inspector; projections and icon decoding never block JavaFX. */
final class ProfessionsView extends BorderPane implements AutoCloseable {
    private final Supplier<BuildProfessions> handleSupplier;
    private final LongSupplier workspaceRevision;
    private final AsyncIconRequests icons;
    private final ProgressIndicator activity = spinner("Loading records", 18);
    private final java.util.concurrent.ThreadPoolExecutor iconWorker =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(64),
                    runnable -> {
                        var thread = new Thread(runnable, "professionsview-icon-pixels");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private record IconPixels(int width, int height, int[] argb) {}

    private static ProgressIndicator spinner(String message, double size) {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(size, size);
        spinner.setMaxSize(size, size);
        spinner.setAccessibleText(message);
        return spinner;
    }

    private void busy(boolean value) {
        activity.setVisible(value);
        activity.setManaged(value);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> request(
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(error);
        }
    }

    private static IconPixels convert(com.vibeminer.formats.images.BlpImageTypes.Result result) {
        if (result == null
                || result.state() != com.vibeminer.formats.images.BlpImageTypes.State.READY
                || result.image() == null) return null;
        var image = result.image();
        int width = image.width(), height = image.height(), stride = image.rowStride();
        if (width < 1
                || height < 1
                || width > 64
                || height > 64
                || stride < width * 4
                || stride > 256)
            throw new IllegalArgumentException("Icon dimensions exceed preview bounds");
        byte[] rgba = image.rgba();
        if (rgba.length != stride * height)
            throw new IllegalArgumentException("Invalid icon pixels");
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
    }

    private final BiFunction<Long, Cancellation, CompletableFuture<String>> itemNames;
    private int itemNameRequests;
    private final ListView<ProfessionTypes.Profession> professions = new ListView<>();
    private final CheckBox otherRecords = new CheckBox("Other skill records");
    private List<ProfessionTypes.Profession> catalogue = List.of();
    private final TableView<ProfessionTypes.Ability> abilities = new TableView<>();
    private final TextField search = new TextField();
    private final Label title = text("Professions", "h1"),
            status = text("Preparing professions…", "muted");
    private final VBox inspection = new VBox(12);
    private final ScrollPane detailScroll = new ScrollPane(inspection);
    private final Button more = new Button("More recipes"), details = new Button("Details");
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(250));
    private Cancellation token = new Cancellation(), detailToken = new Cancellation();
    private BuildProfessions handle;
    private long revision = -1, generation, detailGeneration;
    private Long nextAfter;
    private boolean closed, loaded;
    private Dialog<Void> drawer;

    ProfessionsView(
            Supplier<BuildProfessions> handle,
            LongSupplier revision,
            BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> fetch) {
        this(handle, revision, fetch, (id, token) -> CompletableFuture.completedFuture(null));
    }

    ProfessionsView(
            Supplier<BuildProfessions> handle,
            LongSupplier revision,
            BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> fetch,
            BiFunction<Long, Cancellation, CompletableFuture<String>> itemNames) {
        this.handleSupplier = handle;
        this.workspaceRevision = revision;
        this.itemNames = itemNames;
        icons = new AsyncIconRequests(3, 96, fetch);
        professions.setAccessibleText("Professions in the selected build");
        professions.getStyleClass().add("product-menu");
        professions.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(
                                    ProfessionTypes.Profession value, boolean empty) {
                                super.updateItem(value, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || value == null) return;
                                var name = text(value.name(), "product-name");
                                var count =
                                        text(value.abilityCount() + " recorded abilities", "muted");
                                var box = new VBox(4, name, count);
                                box.setPadding(new Insets(7, 0, 7, 0));
                                setGraphic(box);
                            }
                        });
        professions
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, old, value) -> {
                            if (value != null) {
                                title.setText(value.name());
                                loadAbilities(false);
                            }
                        });
        otherRecords.setAccessibleText("Include other primary and secondary skill records");
        otherRecords.selectedProperty().addListener((o, old, value) -> filterProfessions());
        var sidebar = new VBox(16, text("PROFESSIONS", "eyebrow"), otherRecords, professions);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(180);
        VBox.setVgrow(professions, Priority.ALWAYS);
        setLeft(sidebar);
        search.setPromptText("Search recipes, spell ID or ability ID");
        search.setAccessibleText("Search profession abilities");
        search.textProperty().addListener((o, old, value) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(event -> loadAbilities(false));
        var name = new TableColumn<ProfessionTypes.Ability, String>("Recipe / ability");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        name.setPrefWidth(280);
        var rank = new TableColumn<ProfessionTypes.Ability, String>("Minimum rank");
        rank.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(known(cell.getValue().minimumRank())));
        rank.setPrefWidth(110);
        var id = new TableColumn<ProfessionTypes.Ability, String>("Spell ID");
        id.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(Long.toString(cell.getValue().spellId())));
        id.setPrefWidth(95);
        abilities.getColumns().add(name);
        abilities.getColumns().add(rank);
        abilities.getColumns().add(id);
        abilities.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Server-side cursor order remains stable; presentation sorting must not imply globally
        // sorted pages.
        for (var column : abilities.getColumns()) column.setSortable(false);
        abilities.setAccessibleText("Recorded profession recipes and abilities");
        abilities.setPlaceholder(
                text("Select a profession to inspect its readable abilities.", "muted"));
        abilities
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, old, value) -> {
                            if (value != null) loadDetail(value);
                            else clearDetail("Select a recipe or ability.");
                        });
        more.setDisable(true);
        more.setOnAction(event -> loadAbilities(true));
        details.setOnAction(event -> openDrawer());
        details.setVisible(false);
        details.setManaged(false);
        var reload = new Button("Reload");
        reload.setOnAction(
                event -> {
                    invalidate();
                    ensureLoaded();
                });
        var heading = new HBox(12, title, spacer(), details, reload);
        heading.setAlignment(Pos.CENTER_LEFT);
        busy(false);
        activity.setId("professions-activity");
        var top =
                new VBox(
                        14,
                        heading,
                        text("Recorded build data · no character skill or learned state", "muted"),
                        search,
                        new HBox(10, activity, status));
        top.setPadding(new Insets(24, 24, 16, 24));
        var bottom = new HBox(more);
        bottom.setPadding(new Insets(12, 24, 20, 24));
        var center = new BorderPane(abilities);
        center.setTop(top);
        center.setBottom(bottom);
        center.setMinWidth(0);
        setCenter(center);
        inspection.setPadding(new Insets(24, 18, 24, 18));
        inspection.getStyleClass().add("talent-detail-rail");
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setPrefWidth(350);
        detailScroll.setMinWidth(300);
        detailScroll.setMaxWidth(350);
        setRight(detailScroll);
        clearDetail("Select a recipe or ability.");
        widthProperty().addListener((o, old, value) -> layoutDetails());
    }

    void ensureLoaded() {
        if (closed) return;
        long currentRevision = workspaceRevision.getAsLong();
        var current = handleSupplier.get();
        if (currentRevision != revision || current != handle) {
            invalidate();
            handle = current;
            revision = currentRevision;
        }
        if (loaded || handle == null) {
            if (handle == null) {
                status.setText("Profession tables are not available yet.");
                busy(false);
            }
            return;
        }
        loaded = true;
        long request = ++generation;
        var selected = handle;
        status.setText("Preparing profession records…");
        busy(true);
        professions.setDisable(true);
        search.setDisable(false);
        if (professions.getItems().isEmpty())
            abilities.setPlaceholder(loading("Loading profession catalog…"));
        request(() -> selected.catalog(token))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (!current(selected, request)) return;
                                            busy(false);
                                            abilities.setDisable(false);
                                            abilities.setPlaceholder(
                                                    text("No readable abilities.", "muted"));
                                            if (failure != null) {
                                                status.setText(
                                                        "Profession records could not be read."
                                                                + " Reload to retry.");
                                                abilities.setPlaceholder(
                                                        text(
                                                                "Profession records unavailable.",
                                                                "muted"));
                                                loaded = false;
                                                return;
                                            }
                                            status.setText(
                                                    result.message() != null
                                                            ? result.message()
                                                            : coverage(
                                                                    result.state(),
                                                                    result.issues()));
                                            professions.setDisable(false);
                                            catalogue = result.professions();
                                            filterProfessions();
                                        }));
    }

    private void filterProfessions() {
        var selected = professions.getSelectionModel().getSelectedItem();
        professions
                .getItems()
                .setAll(
                        catalogue.stream()
                                .filter(
                                        value ->
                                                otherRecords.isSelected()
                                                        || value.classicProfession())
                                .toList());
        if (selected != null && professions.getItems().contains(selected)) {
            professions.getSelectionModel().select(selected);
        } else if (!professions.getItems().isEmpty()) {
            professions.getSelectionModel().selectFirst();
        } else {
            abilities.getItems().clear();
            clearDetail("No matching profession records are readable.");
            abilities.setPlaceholder(text("No matching profession records are readable.", "muted"));
        }
    }

    void invalidate() {
        token.cancel();
        token = new Cancellation();
        detailToken.cancel();
        detailToken = new Cancellation();
        generation++;
        detailGeneration++;
        loaded = false;
        busy(false);
        icons.clear();
        searchDelay.stop();
        catalogue = List.of();
        professions.getItems().clear();
        abilities.getItems().clear();
        nextAfter = null;
        more.setDisable(true);
        clearDetail("Select a recipe or ability.");
    }

    private void loadAbilities(boolean append) {
        if (closed || handle == null || professions.getSelectionModel().getSelectedItem() == null)
            return;
        if (append && nextAfter == null) return;
        token.cancel();
        token = new Cancellation();
        if (!append) {
            detailToken.cancel();
            detailGeneration++;
        }
        long request = ++generation;
        var selected = handle;
        var profession = professions.getSelectionModel().getSelectedItem();
        long after = append ? nextAfter : -1;
        more.setDisable(true);
        if (!append) {
            abilities.getItems().clear();
            clearDetail("Select a recipe or ability.");
        }
        status.setText("Reading " + profession.name() + " abilities…");
        String query = search.getText();
        if (query.length() > 128) {
            busy(false);
            abilities.setPlaceholder(text("Search is limited to 128 characters.", "muted"));
            status.setText("Search is limited to 128 characters.");
            return;
        }
        busy(true);
        abilities.setDisable(true);
        if (abilities.getItems().isEmpty())
            abilities.setPlaceholder(loading("Loading profession abilities…"));
        request(() -> selected.abilities(profession.id(), query, after, 100, token))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (!current(selected, request)) return;
                                            busy(false);
                                            abilities.setDisable(false);
                                            abilities.setPlaceholder(
                                                    text("No readable abilities.", "muted"));
                                            if (failure != null) {
                                                status.setText(
                                                        "Recipes could not be read. Reload to"
                                                                + " retry.");
                                                abilities.setPlaceholder(
                                                        text("Recipes unavailable.", "muted"));
                                                return;
                                            }
                                            abilities.getItems().addAll(result.abilities());
                                            nextAfter = result.nextAfter();
                                            more.setDisable(nextAfter == null);
                                            status.setText(
                                                    result.message() != null
                                                            ? result.message()
                                                            : coverage(
                                                                    result.state(),
                                                                    result.issues()));
                                            if (abilities.getItems().isEmpty()) {
                                                abilities.setPlaceholder(
                                                        text(
                                                                query.isBlank()
                                                                        ? "No abilities in the"
                                                                              + " readable records."
                                                                        : "No matching readable"
                                                                                + " abilities.",
                                                                "muted"));
                                            } else if (!append)
                                                abilities.getSelectionModel().selectFirst();
                                        }));
    }

    private void loadDetail(ProfessionTypes.Ability ability) {
        detailToken.cancel();
        detailToken = new Cancellation();
        long request = ++detailGeneration;
        var selected = handle;
        clearDetail("Reading recipe details…");
        inspection.getChildren().add(spinner("Loading recipe details", 24));
        request(() -> selected.detail(ability.id(), detailToken))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (!detailCurrent(selected, request)) return;
                                            if (failure != null) {
                                                clearDetail("Recipe details could not be read.");
                                                return;
                                            }
                                            render(result, request);
                                        }));
    }

    private void render(ProfessionTypes.Detail detail, long request) {
        inspection.getChildren().clear();
        itemNameRequests = 0;
        if (detail.ability() == null) {
            clearDetail(detail.message() == null ? "Recipe unavailable." : detail.message());
            return;
        }
        var ability = detail.ability();
        var heading = new HBox(12);
        var icon = new StackPane(text("—", "muted"));
        icon.setMinSize(38, 38);
        icon.setMaxSize(38, 38);
        icon.setAccessibleText("Recipe icon unavailable");
        heading.getChildren().add(icon);
        heading.getChildren().add(text(ability.name(), "h2"));
        inspection.getChildren().add(heading);
        if (ability.iconFileDataId() != null) {
            icon.setAccessibleText("Loading recipe icon");
            icon.getChildren().setAll(spinner("Loading recipe icon", 18));
            var imageHandle = handle;
            icons.request(ability.iconFileDataId())
                    .thenApplyAsync(ProfessionsView::convert, iconWorker)
                    .whenComplete(
                            (pixels, error) ->
                                    Platform.runLater(
                                            () -> {
                                                if (!detailCurrent(imageHandle, request)) return;
                                                if (error != null || pixels == null) {
                                                    icon.setAccessibleText(
                                                            "Recipe icon unavailable");
                                                    icon.getChildren().setAll(text("—", "muted"));
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
                                                image.setFitWidth(38);
                                                image.setFitHeight(38);
                                                image.setPreserveRatio(true);
                                                icon.setAccessibleText("Recipe icon");
                                                icon.getChildren().setAll(image);
                                            }));
        }
        inspection
                .getChildren()
                .add(text("Spell " + ability.spellId() + " · ability " + ability.id(), "muted"));
        section("RESULT");
        for (var output : detail.results()) {
            String label =
                    switch (output.kind()) {
                        case ITEM -> "Item #" + output.itemId();
                        case ENCHANTMENT ->
                                output.enchantmentId() == null
                                        ? "Enchantment not resolved"
                                        : "Enchantment #" + output.enchantmentId();
                        case RANDOM_OR_SCRIPTED -> "Random or scripted result";
                        case OTHER -> "Other result";
                        case UNRESOLVED -> "Result not resolved";
                    };
            inspection
                    .getChildren()
                    .add(
                            output.kind() == ProfessionTypes.ResultKind.ITEM
                                            && output.itemId() != null
                                    ? itemLabel(output.itemId(), "", request)
                                    : text(label, "value"));
            inspection
                    .getChildren()
                    .add(
                            text(
                                    output.quantity() == null
                                            ? "Quantity not resolved"
                                            : "Quantity " + output.quantity(),
                                    "muted"));
        }
        if (detail.results().isEmpty())
            inspection
                    .getChildren()
                    .add(
                            text(
                                    "Result data "
                                            + detail.resultState()
                                                    .toString()
                                                    .toLowerCase(java.util.Locale.ROOT),
                                    "muted"));
        section("REAGENTS");
        for (var reagent : detail.reagents())
            inspection
                    .getChildren()
                    .add(
                            itemLabel(
                                    reagent.itemId(),
                                    (reagent.count() == null ? "?" : reagent.count()) + " × ",
                                    request));
        if (detail.reagents().isEmpty())
            inspection
                    .getChildren()
                    .add(
                            text(
                                    detail.reagentState() == ProfessionTypes.State.READY
                                            ? "No reagent rows in the readable records."
                                            : "Reagent list not fully resolved.",
                                    "muted"));
        section("TOOLS");
        for (var tool : detail.tools()) {
            if (tool.itemId() != null)
                inspection.getChildren().add(itemLabel(tool.itemId(), "", request));
            if (tool.totemCategoryId() != null)
                inspection
                        .getChildren()
                        .add(text("Tool category #" + tool.totemCategoryId(), "muted"));
        }
        if (detail.tools().isEmpty())
            inspection
                    .getChildren()
                    .add(
                            text(
                                    detail.toolState() == ProfessionTypes.State.READY
                                            ? "No tool rows in the readable records."
                                            : "Tool requirements not fully resolved.",
                                    "muted"));
        section("SKILL FACTS");
        if (detail.skill() != null) {
            var skill = detail.skill();
            inspection
                    .getChildren()
                    .add(text("Minimum rank " + known(skill.minimumRank()), "value"));
            inspection
                    .getChildren()
                    .add(
                            text(
                                    "Trivial thresholds "
                                            + known(skill.trivialLow())
                                            + " / "
                                            + known(skill.trivialHigh()),
                                    "muted"));
            inspection
                    .getChildren()
                    .add(text("Recorded skill-ups " + known(skill.numSkillUps()), "muted"));
        }
        section("HOW TO LEARN");
        inspection.getChildren().add(text("No verified source relationship", "muted"));
        var evidence = new VBox(7);
        if (detail.skill() != null) {
            evidence.getChildren()
                    .add(
                            text(
                                    "Acquisition method "
                                            + known(detail.skill().acquireMethod())
                                            + " is a client value, not a trainer/vendor/drop"
                                            + " relationship.",
                                    "muted"));
            for (var source : detail.skill().sources()) evidence.getChildren().add(source(source));
        }
        for (var ingredient : detail.reagents())
            for (var source : ingredient.sources()) evidence.getChildren().add(source(source));
        for (var tool : detail.tools())
            for (var source : tool.sources()) evidence.getChildren().add(source(source));
        for (var output : detail.results())
            for (var source : output.sources()) evidence.getChildren().add(source(source));
        for (String issue : detail.issues()) evidence.getChildren().add(text(issue, "muted"));
        var pane = new TitledPane("Source records and restrictions", evidence);
        pane.setExpanded(false);
        inspection.getChildren().add(pane);
        inspection.getChildren().add(text(coverage(detail.state(), detail.issues()), "muted"));
    }

    private Node itemLabel(long id, String prefix, long request) {
        var label = text(prefix + "Item #" + id, "value");
        if (itemNameRequests++ >= 16) return label;
        var activity = spinner("Resolving item name for #" + id, 14);
        var row = new HBox(7, label, activity);
        var selected = handle;
        request(() -> itemNames.apply(id, detailToken))
                .whenComplete(
                        (name, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (!detailCurrent(selected, request)) return;
                                            activity.setVisible(false);
                                            activity.setManaged(false);
                                            if (failure == null
                                                    && name != null
                                                    && !name.isBlank()) {
                                                label.setText(prefix + name + " (#" + id + ")");
                                                row.setAccessibleText(
                                                        "Recorded item reference " + id);
                                            } else {
                                                row.setAccessibleText(
                                                        "Item name unavailable; recorded item #"
                                                                + id);
                                            }
                                        }));
        return row;
    }

    private static VBox loading(String message) {
        var box = new VBox(10, spinner(message, 28), text(message, "muted"));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void layoutDetails() {
        boolean narrow = getWidth() > 0 && getWidth() < 1120;
        details.setVisible(narrow);
        details.setManaged(narrow);
        if (drawer != null && drawer.isShowing()) return;
        setRight(narrow ? null : detailScroll);
    }

    private void openDrawer() {
        if (drawer != null && drawer.isShowing()) return;
        setRight(null);
        drawer = new Dialog<>();
        drawer.setTitle("Recipe details");
        drawer.setResizable(true);
        if (getScene() != null) drawer.initOwner(getScene().getWindow());
        drawer.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        drawer.getDialogPane().setContent(detailScroll);
        drawer.getDialogPane().setPrefSize(440, 680);
        drawer.setOnHidden(
                event -> {
                    drawer.getDialogPane().setContent(null);
                    drawer = null;
                    layoutDetails();
                });
        drawer.show();
    }

    private boolean current(BuildProfessions selected, long request) {
        return !closed && request == generation && sourceCurrent(selected);
    }

    private boolean detailCurrent(BuildProfessions selected, long request) {
        return !closed && request == detailGeneration && sourceCurrent(selected);
    }

    private boolean sourceCurrent(BuildProfessions selected) {
        if (selected != handle) return false;
        if (selected == handleSupplier.get() && revision == workspaceRevision.getAsLong())
            return true;
        generation++;
        detailGeneration++;
        token.cancel();
        detailToken.cancel();
        busy(false);
        loaded = false;
        icons.clear();
        iconWorker.getQueue().clear();
        searchDelay.stop();
        professions.setDisable(true);
        abilities.setDisable(true);
        more.setDisable(true);
        search.setDisable(true);
        abilities.setPlaceholder(text("Profession sources changed. Reload to update.", "muted"));
        clearDetail("Profession sources changed. Reload to update.");
        status.setText("Profession sources changed. Reload to update; previous rows retained.");
        return false;
    }

    private void clearDetail(String message) {
        inspection.getChildren().setAll(text("RECIPE DETAILS", "eyebrow"), text(message, "muted"));
    }

    private void section(String label) {
        inspection.getChildren().add(text(label, "eyebrow"));
    }

    private static Label source(ProfessionTypes.Source source) {
        return text(
                source.table()
                        + " #"
                        + source.rowId()
                        + " · "
                        + source.column()
                        + " = "
                        + source.value(),
                "muted");
    }

    private static String known(Long value) {
        return value == null ? "Unknown" : value.toString();
    }

    private static String coverage(ProfessionTypes.State state, List<String> issues) {
        return state == ProfessionTypes.State.READY
                ? "Readable build records"
                : state == ProfessionTypes.State.PARTIAL
                        ? "Some source data is unavailable; readable facts are shown."
                        : "Source data " + state.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static Label text(String value, String style) {
        var label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Region spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        invalidate();
        icons.close();
        iconWorker.shutdownNow();
        busy(false);
        if (drawer != null) drawer.close();
    }
}
