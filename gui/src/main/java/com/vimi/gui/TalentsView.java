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
    private ScrollPane scroll;

    private record PendingIcon(
            long id, StackPane target, double size, long request, boolean guarded) {}

    private final java.util.List<PendingIcon> waitingIcons = new java.util.ArrayList<>();
    private final javafx.animation.PauseTransition iconPulse =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(20));
    private final ListView<TalentTypes.PlayerClass> classes = new ListView<>();
    private final VBox content = new VBox(18), selection = new VBox(10);
    private final HBox treePanels = new HBox(16);
    private final Label status = text("Loading talent tables…", "muted"),
            classTitle = text("Talents", "h1");
    private final AsyncIconRequests icons;
    private final Map<Long, Button> nodeButtons = new HashMap<>();
    private Cancellation token = new Cancellation();
    private long generation, revision = -1;
    private boolean loaded, closed;
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
        HBox state = new HBox(12, status, space(), reload);
        state.setAlignment(Pos.CENTER_LEFT);
        selection.getStyleClass().add("card");
        selection.setVisible(false);
        selection.setManaged(false);
        content.getChildren()
                .addAll(
                        heading,
                        state,
                        text(
                                "Rank badges show recorded maximum ranks. This view does not"
                                        + " allocate character talent points.",
                                "muted"),
                        treePanels,
                        selection);
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        setCenter(scroll);
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
        generation++;
        token.cancel();
        token = new Cancellation();
        icons.clear();
        waitingIcons.clear();
    }

    void ensureLoaded() {
        if (closed || loaded) return;
        var handle = handleSupplier.get();
        if (handle == null) {
            status.setText("Talent data is unavailable; see Overview.");
            return;
        }
        loaded = true;
        revision = workspaceRevision.getAsLong();
        long request = ++generation;
        status.setText("Loading class and talent table facts…");
        handle.catalog(token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
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
        return closed || request != generation || workspaceRevision.getAsLong() != revision;
    }

    private void loadClass(TalentTypes.PlayerClass player) {
        selectedClass = player.id();
        classTitle.setText(player.name() + " · Talents");
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        treePanels.getChildren().clear();
        nodeButtons.clear();
        selection.setVisible(false);
        selection.setManaged(false);
        icons.retain(java.util.Set.of());
        status.setText("Loading " + player.name() + " talent trees…");
        var handle = handleSupplier.get();
        if (handle == null) return;
        handle.trees(player.id(), token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null) {
                                                status.setText(
                                                        "Talent trees could not be loaded. Reload"
                                                                + " to retry.");
                                                return;
                                            }
                                            if (result.state() != TalentTypes.State.READY) {
                                                status.setText(result.message());
                                                return;
                                            }
                                            status.setText(
                                                    result.issues().isEmpty()
                                                            ? "Three trees from this build · select"
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
        selection
                .getChildren()
                .setAll(
                        text(talent.name(), "h2"),
                        text(
                                tree.name()
                                        + " · "
                                        + talent.rankSpells().size()
                                        + " recorded ranks",
                                "value"));
        for (var edge : talent.prerequisites())
            selection
                    .getChildren()
                    .add(
                            text(
                                    "Requires "
                                            + byId.get(edge.talentId()).name()
                                            + " · rank "
                                            + (edge.rankIndex() + 1),
                                    "muted"));
        selection
                .getChildren()
                .add(
                        text(
                                talent.description() == null || talent.description().isEmpty()
                                        ? "Spell description is unavailable in the readable table"
                                                + " data."
                                        : "Raw spell description: " + talent.description(),
                                "muted"));
        var evidence =
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
        evidence.setExpanded(false);
        selection.getChildren().add(evidence);
        selection.setVisible(true);
        selection.setManaged(true);
    }

    private void queueImage(long id, StackPane target, double size, long request, boolean guarded) {
        waitingIcons.add(new PendingIcon(id, target, size, request, guarded));
        iconPulse.playFromStart();
    }

    private void requestVisibleIcons() {
        var iterator = waitingIcons.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (closed || pending.guarded() && stale(pending.request())) {
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
        icons.request(id)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || (guarded && stale(request))
                                                    || error != null
                                                    || result == null
                                                    || result.state()
                                                            != com.vibeminer.formats.images
                                                                    .BlpImageTypes.State.READY)
                                                return;
                                            var image = result.image();
                                            WritableImage bitmap =
                                                    new WritableImage(
                                                            image.width(), image.height());
                                            bitmap.getPixelWriter()
                                                    .setPixels(
                                                            0,
                                                            0,
                                                            image.width(),
                                                            image.height(),
                                                            PixelFormat.getByteBgraInstance(),
                                                            rgbaToBgra(image.rgba()),
                                                            0,
                                                            image.width() * 4);
                                            ImageView view = new ImageView(bitmap);
                                            view.setFitWidth(size);
                                            view.setFitHeight(size);
                                            view.setPreserveRatio(true);
                                            target.getChildren().setAll(view);
                                        }));
    }

    private static byte[] rgbaToBgra(byte[] input) {
        byte[] result = input.clone();
        for (int i = 0; i < result.length; i += 4) {
            byte red = result[i];
            result[i] = result[i + 2];
            result[i + 2] = red;
        }
        return result;
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        iconPulse.stop();
        waitingIcons.clear();
        icons.close();
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
