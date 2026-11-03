package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.TalentTypes;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

import java.util.HashMap;
import java.util.Map;

/** Read-only, build-derived Classic trees; domain queries and media never block JavaFX. */
final class TalentsView extends BorderPane implements AutoCloseable {
    private static final double ICON = 44, PITCH = 62, PADDING = 24;
    private final java.util.function.Supplier<com.vibeminer.concepts.talents.BuildTalents>
            handleSupplier;
    private final java.util.function.LongSupplier workspaceRevision;
    private com.vibeminer.concepts.talents.BuildTalents boundHandle;
    private ScrollPane scroll;

    private record PendingIcon(
            long id, StackPane target, double size, long request, boolean guarded) {}

    private final java.util.Set<StackPane> pendingIcons = new java.util.HashSet<>();
    private final java.util.List<PendingIcon> waitingIcons = new java.util.ArrayList<>();
    private final javafx.animation.PauseTransition iconPulse =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(20));
    private final Runnable afterLayout = this::requestVisibleIcons;
    private final ListView<TalentTypes.PlayerClass> classes = new ListView<>();
    private final VBox content = new VBox(18), selection = new VBox(8), evidence = new VBox(12);
    private final HBox treePanels = new HBox(16);
    private final Label status = text("Loading talent tables…", "muted"),
            classTitle = text("Talents", "h1");
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
                        var thread = new Thread(runnable, "talentsview-icon-pixels");
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

    private final Map<Long, Button> nodeButtons = new HashMap<>();
    private Cancellation token = new Cancellation(), descriptionToken = new Cancellation();
    private long descriptionGeneration;
    private long generation, revision = -1;
    private boolean loaded, closed, sourceRetired;
    private Long selectedClass;

    TalentsView(BuildPreparation.Job job) {
        this(
                () -> nativeWork(job).talentsHandle(),
                () -> nativeWork(job).revision(),
                (id, token) -> nativeWork(job).icon(id, token));
    }

    TalentsView(
            java.util.function.Supplier<com.vibeminer.concepts.talents.BuildTalents> handle,
            java.util.function.LongSupplier revision,
            java.util.function.BiFunction<
                            Long,
                            Cancellation,
                            java.util.concurrent.CompletableFuture<
                                    com.vibeminer.formats.images.BlpImageTypes.Result>>
                    fetch) {
        this.handleSupplier = handle;
        this.workspaceRevision = revision;
        icons = new AsyncIconRequests(4, 256, fetch);
        iconPulse.setOnFinished(event -> requestVisibleIcons());
        sceneProperty()
                .addListener(
                        (observable, previous, current) -> {
                            if (previous != null)
                                previous.removePostLayoutPulseListener(afterLayout);
                            if (current != null && !closed)
                                current.addPostLayoutPulseListener(afterLayout);
                        });
        classes.setAccessibleText("Classes with talent trees in this build");
        classes.getStyleClass().add("product-menu");
        classes.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(TalentTypes.PlayerClass item, boolean empty) {
                                super.updateItem(item, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || item == null) return;
                                var name = text(item.name(), "product-name");

                                var row = new HBox(9, classIcon(item), name);
                                row.setAlignment(Pos.CENTER_LEFT);
                                row.prefWidthProperty().bind(widthProperty().subtract(24));
                                setPrefWidth(0);
                                setGraphic(row);
                            }
                        });
        classes.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, b, a) -> {
                            if (a != null) loadClass(a);
                        });
        VBox sidebar = new VBox(16, text("CLASSES", "eyebrow"), classes);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(210);
        sidebar.setMinWidth(190);
        VBox.setVgrow(classes, Priority.ALWAYS);
        setLeft(sidebar);
        content.setPadding(new Insets(24));
        HBox heading = new HBox(16, classTitle, space(), text("CLASSIC TALENT REFERENCE", "badge"));
        heading.setAlignment(Pos.CENTER_LEFT);
        Button reload = new Button("Reload");
        reload.setOnAction(
                event -> {
                    invalidate();
                    ensureLoaded();
                });
        busy(false);
        activity.setId("talents-activity");
        HBox state = new HBox(12, activity, status, space(), reload);
        state.setAlignment(Pos.CENTER_LEFT);
        selection.getStyleClass().addAll("wow-item-tooltip", "talent-tooltip");
        selection.setAccessibleText("Selected talent tooltip");
        clearSelection();
        VBox detailRail = new VBox(14, text("TALENT DETAILS", "eyebrow"), selection, evidence);
        detailRail.setPadding(new Insets(24, 16, 24, 16));
        detailRail.getStyleClass().add("talent-detail-rail");
        ScrollPane detailsScroll = new ScrollPane(detailRail);
        detailsScroll.setFitToWidth(true);
        detailsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailsScroll.setMinWidth(320);
        detailsScroll.setPrefWidth(320);
        detailsScroll.setMaxWidth(320);
        setRight(detailsScroll);
        VBox header =
                new VBox(
                        18,
                        heading,
                        state,
                        text(
                                "Rank badges show recorded maximum ranks. This view does not"
                                        + " allocate character talent points.",
                                "muted"));
        header.setPadding(new Insets(24));
        content.setPadding(new Insets(0, 24, 24, 24));
        content.getChildren().add(treePanels);
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        BorderPane main = new BorderPane(scroll);
        main.setTop(header);
        main.setMinWidth(0);
        setCenter(main);
        scroll.viewportBoundsProperty().addListener((o, b, a) -> iconPulse.playFromStart());
        scroll.vvalueProperty().addListener((o, b, a) -> iconPulse.playFromStart());
        scroll.hvalueProperty().addListener((o, b, a) -> iconPulse.playFromStart());
        classes.skinProperty().addListener((o, b, a) -> iconPulse.playFromStart());
    }

    private static NativePreparationWork nativeWork(BuildPreparation.Job job) {
        if (!(job.work() instanceof NativePreparationWork work))
            throw new IllegalStateException("Native talent data unavailable");
        return work;
    }

    void invalidate() {
        loaded = false;
        sourceRetired = false;
        busy(false);
        generation++;
        token.cancel();
        token = new Cancellation();
        icons.clear();
        pendingIcons.clear();
        iconWorker.getQueue().clear();
        waitingIcons.clear();
        clearSelection();
    }

    void ensureLoaded() {
        if (closed) return;
        if (boundHandle != null
                && (boundHandle != handleSupplier.get()
                        || revision != workspaceRevision.getAsLong())) invalidate();
        if (loaded) return;
        var handle = handleSupplier.get();
        if (handle == null) {
            status.setText("Talent data is unavailable; see Overview.");
            return;
        }
        loaded = true;
        boundHandle = handle;
        sourceRetired = false;
        revision = workspaceRevision.getAsLong();
        long request = ++generation;
        status.setText("Loading class and talent table facts…");
        busy(true);
        classes.setDisable(true);
        if (classes.getItems().isEmpty())
            treePanels.getChildren().setAll(loading("Loading talent catalog…"));
        request(() -> handle.catalog(token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            busy(false);
                                            treePanels.getChildren().clear();
                                            if (error != null) {
                                                status.setText(
                                                        "Talent data could not be loaded. Reload to"
                                                                + " retry.");
                                                loaded = false;
                                                return;
                                            }
                                            status.setText(
                                                    result.message() == null
                                                            ? "Choose a class to view its talent"
                                                                    + " trees."
                                                            : result.message());
                                            classes.setDisable(false);
                                            classes.getItems().setAll(result.classes());
                                            treePanels.getChildren().clear();
                                            if (!result.classes().isEmpty())
                                                classes.getSelectionModel()
                                                        .select(
                                                                result.classes().stream()
                                                                        .filter(
                                                                                c ->
                                                                                        selectedClass
                                                                                                        != null
                                                                                                && c
                                                                                                                .id()
                                                                                                        == selectedClass)
                                                                        .findFirst()
                                                                        .orElse(
                                                                                result.classes()
                                                                                        .getFirst()));
                                        }));
    }

    private boolean stale(long request) {
        return closed || request != generation || !sourceCurrent();
    }

    private boolean sourceCurrent() {
        if (closed || sourceRetired || boundHandle == null || revision < 0) return false;
        if (workspaceRevision.getAsLong() == revision && handleSupplier.get() == boundHandle)
            return true;
        generation++;
        sourceRetired = true;
        loaded = false;
        token.cancel();
        busy(false);
        classes.setDisable(true);
        nodeButtons.values().forEach(node -> node.setDisable(true));
        clearSelection();
        iconPulse.stop();
        waitingIcons.clear();
        for (var target : pendingIcons) {
            target.setAccessibleText("Talent icon unavailable after source change");
            target.getChildren().setAll(text("—", "talent-placeholder"));
        }
        pendingIcons.clear();
        treePanels
                .lookupAll(".progress-indicator")
                .forEach(
                        node -> {
                            node.setVisible(false);
                            node.setManaged(false);
                        });
        icons.clear();
        iconWorker.getQueue().clear();
        status.setText("Talent data changed. Reload to update; previous trees retained.");
        return false;
    }

    private void loadClass(TalentTypes.PlayerClass player) {
        if (stale(generation)) {
            invalidate();
            ensureLoaded();
            return;
        }
        selectedClass = player.id();
        classTitle.setText(player.name() + " · Talents");
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        treePanels.getChildren().clear();
        pendingIcons.clear();
        nodeButtons.clear();
        clearSelection();
        icons.retain(java.util.Set.of());
        status.setText("Loading " + player.name() + " talent trees…");
        var handle = boundHandle;
        if (handle == null) return;
        busy(true);
        treePanels.getChildren().setAll(loading("Loading talent trees…"));
        request(() -> handle.trees(player.id(), token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            busy(false);
                                            treePanels.getChildren().clear();
                                            if (error != null) {
                                                status.setText(
                                                        "Talent trees could not be loaded. Reload"
                                                                + " to retry.");
                                                return;
                                            }
                                            if (result.state() != TalentTypes.State.READY
                                                    && result.state()
                                                            != TalentTypes.State.PARTIAL) {
                                                status.setText(result.message());
                                                return;
                                            }
                                            status.setText(
                                                    result.issues().isEmpty()
                                                            ? "Trees from this build · select"
                                                                    + " a talent for details"
                                                            : String.join(" · ", result.issues()));
                                            for (var tree : result.trees())
                                                treePanels.getChildren().add(tree(tree, request));
                                        }));
    }

    private Node classIcon(TalentTypes.PlayerClass player) {
        Label fallback =
                text(
                        player.name().isEmpty() ? "?" : player.name().substring(0, 1),
                        "talent-class-initial");
        StackPane pane = new StackPane(fallback);
        pane.setMinSize(24, 24);
        pane.setPrefSize(24, 24);
        pane.setMaxSize(24, 24);
        if (player.color() != null)
            pane.setStyle("-fx-border-color: " + player.color() + "; -fx-border-width: 0 0 3 0;");
        if (player.iconFileDataId() != null)
            loadImage(player.iconFileDataId(), pane, 24, generation, false);
        return pane;
    }

    private Node tree(TalentTypes.Tree tree, long request) {
        Pane canvas = new Pane();
        canvas.getStyleClass().add("talent-canvas");
        canvas.setPrefWidth(280);
        canvas.setMinWidth(280);
        canvas.setMaxWidth(280);
        int rows = tree.talents().stream().mapToInt(TalentTypes.Talent::row).max().orElse(0) + 1;
        canvas.setMinHeight(rows * PITCH + PADDING * 2);
        canvas.setPrefHeight(rows * PITCH + PADDING * 2);
        var byId = new HashMap<Long, TalentTypes.Talent>();
        for (var talent : tree.talents()) byId.put(talent.id(), talent);
        for (var talent : tree.talents())
            for (var edge : talent.prerequisites()) {
                var prerequisite = byId.get(edge.talentId());
                if (prerequisite != null) drawEdge(canvas, prerequisite, talent);
            }
        for (var talent : tree.talents()) {
            StackPane graphic = new StackPane(text("?", "talent-placeholder"));
            graphic.setAccessibleText("Talent icon unavailable");
            graphic.setMinSize(ICON, ICON);
            graphic.setMaxSize(ICON, ICON);
            Button node = new Button();
            node.setGraphic(graphic);
            node.setPadding(Insets.EMPTY);
            node.getStyleClass().add("talent-node");
            node.setMinSize(ICON, ICON);
            node.setPrefSize(ICON, ICON);
            node.setMaxSize(ICON, ICON);
            node.setLayoutX(PADDING + talent.column() * PITCH);
            node.setLayoutY(PADDING + talent.row() * PITCH);
            node.setAccessibleText(
                    talent.name() + ", " + talent.rankSpells().size() + " ranks, " + tree.name());
            Tooltip.install(
                    node,
                    new Tooltip(
                            talent.name()
                                    + "\n"
                                    + talent.rankSpells().size()
                                    + " ranks\nSelect for source details"));
            node.setOnAction(event -> select(tree, talent, byId, node));
            node.setOnKeyPressed(
                    event -> {
                        int dx = 0, dy = 0;
                        switch (event.getCode()) {
                            case LEFT -> dx = -1;
                            case RIGHT -> dx = 1;
                            case UP -> dy = -1;
                            case DOWN -> dy = 1;
                            default -> {
                                return;
                            }
                        }
                        final int x = dx, y = dy;
                        var next =
                                tree.talents().stream()
                                        .filter(
                                                t ->
                                                        x != 0
                                                                ? t.row() == talent.row()
                                                                        && (t.column()
                                                                                                - talent
                                                                                                        .column())
                                                                                        * x
                                                                                > 0
                                                                : t.column() == talent.column()
                                                                        && (t.row() - talent.row())
                                                                                        * y
                                                                                > 0)
                                        .min(
                                                java.util.Comparator.comparingInt(
                                                        t ->
                                                                Math.abs(t.row() - talent.row())
                                                                        + Math.abs(
                                                                                t.column()
                                                                                        - talent
                                                                                                .column())));
                        next.ifPresent(
                                t -> {
                                    var button = nodeButtons.get(t.id());
                                    if (button != null) button.requestFocus();
                                });
                        event.consume();
                    });
            nodeButtons.put(talent.id(), node);
            canvas.getChildren().add(node);
            Label rank = text(Integer.toString(talent.rankSpells().size()), "talent-rank");
            rank.setMouseTransparent(true);
            rank.setLayoutX(node.getLayoutX() + 29);
            rank.setLayoutY(node.getLayoutY() + 33);
            canvas.getChildren().add(rank);
            if (talent.iconFileDataId() != null)
                queueImage(talent.iconFileDataId(), graphic, ICON, request, true);
        }
        VBox treeHeader =
                new VBox(
                        6,
                        text(tree.name().isEmpty() ? "Tree #" + tree.id() : tree.name(), "h2"),
                        text(tree.talents().size() + " talents", "muted"));
        treeHeader.setMinHeight(64);
        VBox panel = new VBox(10, treeHeader, canvas);
        panel.setMinWidth(280);
        panel.setPrefWidth(280);
        return panel;
    }

    private static void drawEdge(
            Pane canvas, TalentTypes.Talent source, TalentTypes.Talent target) {
        double sx = PADDING + source.column() * PITCH + ICON / 2,
                sy = PADDING + source.row() * PITCH + ICON / 2,
                tx = PADDING + target.column() * PITCH + ICON / 2,
                ty = PADDING + target.row() * PITCH + ICON / 2;
        double length = Math.hypot(tx - sx, ty - sy);
        if (length == 0) return;
        double ux = (tx - sx) / length, uy = (ty - sy) / length;
        double ex = tx - ux * (ICON / 2 + 4), ey = ty - uy * (ICON / 2 + 4);
        Line line = new Line(sx + ux * ICON / 2, sy + uy * ICON / 2, ex, ey);
        line.setStroke(Color.web("#8c805b"));
        line.setStrokeWidth(2);
        line.setMouseTransparent(true);
        Polygon arrow =
                new Polygon(
                        ex,
                        ey,
                        ex - ux * 9 - uy * 4,
                        ey - uy * 9 + ux * 4,
                        ex - ux * 9 + uy * 4,
                        ey - uy * 9 - ux * 4);
        arrow.setFill(Color.web("#b5a475"));
        arrow.setMouseTransparent(true);
        canvas.getChildren().addAll(line, arrow);
    }

    private void select(
            TalentTypes.Tree tree,
            TalentTypes.Talent talent,
            Map<Long, TalentTypes.Talent> byId,
            Button button) {
        nodeButtons.values().forEach(node -> node.getStyleClass().remove("talent-selected"));
        button.getStyleClass().add("talent-selected");
        StackPane art = new StackPane(text("?", "talent-placeholder"));
        art.setMinSize(ICON, ICON);
        art.setPrefSize(ICON, ICON);
        art.setMaxSize(ICON, ICON);
        HBox title = new HBox(10, art, text(talent.name(), "wow-item-name"));
        title.setAlignment(Pos.CENTER_LEFT);
        selection
                .getChildren()
                .setAll(
                        title,
                        text(tree.name(), "talent-tooltip-context"),
                        text("Rank 1/" + talent.rankSpells().size(), "wow-line"));
        if (talent.iconFileDataId() != null)
            loadImage(talent.iconFileDataId(), art, ICON, generation, true);
        for (var edge : talent.prerequisites())
            selection
                    .getChildren()
                    .add(
                            text(
                                    "Requires "
                                            + (byId.containsKey(edge.talentId())
                                                    ? byId.get(edge.talentId()).name()
                                                    : "unavailable talent #" + edge.talentId())
                                            + " · rank "
                                            + (edge.rankIndex() + 1),
                                    "wow-line"));
        var description = text("Loading description…", "wow-level");
        var descriptionActivity = spinner("Loading talent description", 18);
        descriptionActivity.setId("talent-description-activity");
        descriptionActivity.setVisible(false);
        descriptionActivity.setManaged(false);
        selection.getChildren().add(new HBox(8, descriptionActivity, description));
        evidence.getChildren()
                .setAll(
                        text(
                                "Previewing the first recorded rank, not character talent points.",
                                "muted"));
        var sourceDetails =
                new TitledPane(
                        "Source details",
                        new VBox(
                                7,
                                text(
                                        "Talent ID "
                                                + talent.id()
                                                + " · tier "
                                                + (talent.row() + 1)
                                                + " · column "
                                                + (talent.column() + 1),
                                        "muted"),
                                text("Rank spell IDs: " + talent.rankSpells(), "muted")));
        sourceDetails.setExpanded(false);
        evidence.getChildren().add(sourceDetails);
        var facts = (VBox) sourceDetails.getContent();
        if (talent.description() != null && talent.description().length() <= 16384)
            facts.getChildren()
                    .add(text("Raw client description: " + talent.description(), "muted"));
        descriptionToken.cancel();
        descriptionToken = new Cancellation();
        long preview = ++descriptionGeneration;
        long workspace = generation;
        if (talent.description() == null
                || talent.description().isBlank()
                || talent.description().length() > 16384) {
            description.setText("Description unavailable in the readable data.");
        } else if (!talent.description().contains("$") && !talent.description().contains("|")) {
            description.setText(talent.description());
        } else {
            descriptionActivity.setVisible(true);
            descriptionActivity.setManaged(true);
            request(() -> boundHandle.description(talent.rankSpells().getFirst(), descriptionToken))
                    .whenComplete(
                            (result, error) ->
                                    Platform.runLater(
                                            () -> {
                                                if (preview != descriptionGeneration
                                                        || stale(workspace)) return;
                                                descriptionActivity.setVisible(false);
                                                descriptionActivity.setManaged(false);
                                                if (error != null || result == null) {
                                                    description.setText(
                                                            "Description values are unavailable.");
                                                    return;
                                                }
                                                description.setText(
                                                        result.display() == null
                                                                        || result.display()
                                                                                .isBlank()
                                                                ? "Description values are"
                                                                        + " unavailable."
                                                                : result.display());
                                                if (result.state()
                                                        == com.vibeminer.concepts.effects.SpellTexts
                                                                .State.UNRESOLVED)
                                                    evidence.getChildren()
                                                            .add(
                                                                    1,
                                                                    text(
                                                                            "Some spell values are"
                                                                                + " unavailable;"
                                                                                + " raw text is in"
                                                                                + " Source"
                                                                                + " details.",
                                                                            "muted"));
                                                for (var substitution : result.substitutions()) {
                                                    facts.getChildren()
                                                            .add(
                                                                    text(
                                                                            substitution.token()
                                                                                    + " → "
                                                                                    + (substitution
                                                                                                            .value()
                                                                                                    == null
                                                                                            ? "unavailable:"
                                                                                                  + " "
                                                                                                    + substitution
                                                                                                            .issue()
                                                                                            : substitution
                                                                                                    .value()),
                                                                            "muted"));
                                                    for (var source : substitution.sources())
                                                        facts.getChildren()
                                                                .add(
                                                                        text(
                                                                                source.table()
                                                                                        + " row "
                                                                                        + source
                                                                                                .rowId()
                                                                                        + " · "
                                                                                        + source
                                                                                                .column()
                                                                                        + " = "
                                                                                        + source
                                                                                                .value(),
                                                                                "muted"));
                                                }
                                                for (var issue : result.issues())
                                                    facts.getChildren().add(text(issue, "muted"));
                                            }));
        }
    }

    private void clearSelection() {
        descriptionGeneration++;
        descriptionToken.cancel();
        descriptionToken = new Cancellation();
        selection
                .getChildren()
                .setAll(
                        text("Select a talent", "wow-item-name"),
                        text(
                                "Choose a tree icon to inspect its recorded rank and description.",
                                "wow-line"));
        evidence.getChildren().clear();
    }

    private void queueImage(long id, StackPane target, double size, long request, boolean guarded) {
        target.setAccessibleText("Talent icon pending visibility");
        waitingIcons.add(new PendingIcon(id, target, size, request, guarded));
        iconPulse.playFromStart();
    }

    private void requestVisibleIcons() {
        if (closed || !sourceCurrent()) return;
        var iterator = waitingIcons.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (closed || pending.guarded() && pending.request() != generation) {
                iterator.remove();
                continue;
            }
            if (pending.target().getScene() == null) continue;
            var bounds = pending.target().localToScene(pending.target().getBoundsInLocal());
            Node viewport = pending.guarded() ? scroll : classes;
            var visible = viewport.localToScene(viewport.getBoundsInLocal());
            if (bounds.intersects(visible)) {
                iterator.remove();
                loadImage(
                        pending.id(),
                        pending.target(),
                        pending.size(),
                        pending.request(),
                        pending.guarded());
            }
        }
    }

    private void loadImage(long id, StackPane target, double size, long request, boolean guarded) {
        var imageHandle = boundHandle;
        long imageRevision = revision;
        pendingIcons.add(target);
        target.setAccessibleText("Loading talent icon");
        target.getChildren().setAll(spinner("Loading talent icon", Math.min(size, 18)));
        icons.request(id)
                .thenApplyAsync(TalentsView::convert, iconWorker)
                .whenComplete(
                        (pixels, error) ->
                                Platform.runLater(
                                        () -> {
                                            pendingIcons.remove(target);
                                            if (closed
                                                    || imageHandle != boundHandle
                                                    || imageRevision != revision
                                                    || (guarded && request != generation)) return;
                                            if (!sourceCurrent()) {
                                                target.setAccessibleText(
                                                        "Talent icon unavailable after source"
                                                            + " change");
                                                target.getChildren()
                                                        .setAll(text("—", "talent-placeholder"));
                                                return;
                                            }
                                            if (error != null || pixels == null) {
                                                target.setAccessibleText("Talent icon unavailable");
                                                target.getChildren()
                                                        .setAll(text("—", "talent-placeholder"));
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
                                            var view = new ImageView(bitmap);
                                            view.setFitWidth(size);
                                            view.setFitHeight(size);
                                            view.setPreserveRatio(true);
                                            target.setAccessibleText("Talent icon");
                                            target.getChildren().setAll(view);
                                        }));
    }

    private static VBox loading(String message) {
        var box = new VBox(10, spinner(message, 28), text(message, "muted"));
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        return box;
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        descriptionToken.cancel();
        iconPulse.stop();
        if (getScene() != null) getScene().removePostLayoutPulseListener(afterLayout);
        waitingIcons.clear();
        pendingIcons.clear();
        icons.close();
        iconWorker.shutdownNow();
        busy(false);
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Region space() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }
}
