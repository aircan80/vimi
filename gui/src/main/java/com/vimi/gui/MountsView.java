package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.mounts.BuildMounts;
import com.vibeminer.concepts.mounts.MountTypes;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Build-scoped mount browser; model assets load only for the selected recorded variant. */
final class MountsView extends BorderPane implements AutoCloseable {
    private enum PreviewState {
        NOT_CHECKED("Not checked"),
        LOADING("Loading preview"),
        READY("Preview resolved"),
        UNAVAILABLE("Preview unavailable");
        final String label;

        PreviewState(String label) {
            this.label = label;
        }
    }

    private final Supplier<BuildMounts> handles;
    private final LongSupplier revisions;
    private final String context;
    private final ListView<MountTypes.Mount> mounts = new ListView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();
    private final ComboBox<MountTypes.Variant> variants = new ComboBox<>();
    private final Label status = text("Loading mount records…", "muted"),
            count = text("", "muted"),
            heading = text("Choose a mount", "h2"),
            identity = text("", "muted"),
            previewStatus = text("Select a recorded appearance", "muted");
    private final VBox rail = new VBox(14), body = new VBox(12);
    private final ModelPreviewView model = new ModelPreviewView();
    private final Map<Long, PreviewState> previewStates = new HashMap<>();
    private final Map<Long, Long> selectedVariants = new HashMap<>();
    private List<MountTypes.Mount> catalog = List.of();
    private BuildMounts bound;
    private MountTypes.Overview current;
    private Cancellation catalogToken = new Cancellation(),
            selectionToken = new Cancellation(),
            previewToken = new Cancellation();
    private long generation, selectionGeneration, previewGeneration, revision = -1;
    private Long selected;
    private boolean catalogLoading;
    private boolean loaded, closed, settingVariants;

    MountsView(Supplier<BuildMounts> handles, LongSupplier revisions, String context) {
        this.handles = Objects.requireNonNull(handles);
        this.revisions = Objects.requireNonNull(revisions);
        this.context = context;
        search.setPromptText("Search name, mount or spell ID");
        search.setAccessibleText("Search mount records");
        filter.getItems()
                .setAll("All previews", "Not checked", "Preview resolved", "Preview unavailable");
        filter.getSelectionModel().selectFirst();
        filter.setMaxWidth(Double.MAX_VALUE);
        filter.setAccessibleText("Known mount preview status");
        filter.getStyleClass().add("build-choice");
        mounts.setAccessibleText("Build mount records");
        mounts.getStyleClass().add("product-menu");
        mounts.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(MountTypes.Mount mount, boolean empty) {
                                super.updateItem(mount, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || mount == null) {
                                    return;
                                }
                                var box =
                                        new VBox(
                                                4,
                                                text(mount.name(), "product-name"),
                                                text(
                                                        previewStates.getOrDefault(
                                                                        mount.id(),
                                                                        PreviewState.NOT_CHECKED)
                                                                .label,
                                                        "product-code"));
                                box.prefWidthProperty().bind(widthProperty().subtract(24));
                                setPrefWidth(0);
                                setGraphic(box);
                            }
                        });
        mounts.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, b, a) -> {
                            if (a != null && !Objects.equals(selected, a.id())) {
                                select(a);
                            }
                        });
        search.textProperty().addListener((o, b, a) -> filter());
        filter.setOnAction(e -> filter());
        var sidebar = new VBox(12, text("MOUNTS", "eyebrow"), search, filter, count, mounts);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(236);
        sidebar.setMinWidth(215);
        VBox.setVgrow(mounts, Priority.ALWAYS);
        setLeft(sidebar);
        var reload = new Button("Reload");
        reload.setOnAction(
                e -> {
                    invalidate();
                    ensureLoaded();
                });
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var title = new HBox(12, text("Mounts", "h1"), spacer, reload);
        title.setAlignment(Pos.CENTER_LEFT);
        var header =
                new VBox(
                        9,
                        title,
                        text("Recorded mount appearances from this build", "subtitle"),
                        status);
        header.setPadding(new Insets(24, 28, 12, 28));
        variants.setAccessibleText("Recorded mount display variants");
        variants.getStyleClass().add("build-choice");
        variants.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(variants, Priority.ALWAYS);
        variants.setConverter(
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(MountTypes.Variant variant) {
                        return variant == null
                                ? "No readable appearances"
                                : "Display #"
                                        + variant.displayId()
                                        + " · record #"
                                        + variant.recordId();
                    }

                    @Override
                    public MountTypes.Variant fromString(String text) {
                        throw new UnsupportedOperationException();
                    }
                });
        variants.setOnAction(
                e -> {
                    if (!settingVariants && variants.getValue() != null) {
                        preview(variants.getValue());
                    }
                });
        var retry = new Button("Retry preview");
        retry.setOnAction(
                e -> {
                    if (variants.getValue() != null) {
                        preview(variants.getValue());
                    }
                });
        var controls = new HBox(10, text("Appearance", "muted"), variants, retry);
        controls.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().setAll(heading, identity, controls, previewStatus, model);
        body.setPadding(new Insets(12, 28, 28, 28));
        body.setMinWidth(0);
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var main = new BorderPane(scroll);
        main.setTop(header);
        main.setMinWidth(0);
        setCenter(main);
        rail.setPadding(new Insets(24, 16, 24, 16));
        rail.setPrefWidth(316);
        rail.getStyleClass().add("race-racial-rail");
        var details = new ScrollPane(rail);
        details.setFitToWidth(true);
        details.setPrefWidth(316);
        details.setMinWidth(260);
        setRight(details);
        rail.getChildren()
                .setAll(
                        text("Mount details", "h2"),
                        text("Select a mount to inspect its description and sources.", "muted"));
        model.clear("No model selected");
    }

    private static VBox activity(String message) {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(26, 26);
        spinner.setMaxSize(26, 26);
        spinner.setAccessibleText(message);
        var region = new VBox(10, spinner, text(message, "muted"));
        region.setAlignment(Pos.CENTER);
        region.setPadding(new Insets(20));
        return region;
    }

    private static <T> java.util.concurrent.CompletableFuture<T> request(
            Supplier<java.util.concurrent.CompletableFuture<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get());
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
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
            invalidate();
            mounts.setPlaceholder(text("Mount records are unavailable.", "muted"));
            status.setText("Mount data unavailable; see Overview.");
            return;
        }
        invalidate();
        bound = handle;
        revision = currentRevision;
        loaded = true;
        mounts.setDisable(false);
        catalogLoading = true;
        mounts.setPlaceholder(activity("Loading mount records…"));
        long request = generation;
        status.setText("Loading readable mount records…");
        request(() -> handle.catalog(catalogToken))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request, handle)) {
                                                return;
                                            }
                                            catalogLoading = false;
                                            if (failure != null || result == null) {
                                                loaded = false;
                                                mounts.setPlaceholder(
                                                        text(
                                                                "Mount records are unavailable."
                                                                        + " Reload to retry.",
                                                                "muted"));
                                                status.setText(
                                                        "Mount records could not be loaded. Reload"
                                                                + " to retry.");
                                                return;
                                            }
                                            catalog = result.mounts();
                                            status.setText(
                                                    catalog.size()
                                                            + " readable mounts · "
                                                            + result.state()
                                                                    .toString()
                                                                    .toLowerCase(Locale.ROOT)
                                                            + " · "
                                                            + context);
                                            filter();
                                            if (selected == null && !mounts.getItems().isEmpty()) {
                                                mounts.getSelectionModel().selectFirst();
                                            }
                                            if (result.message() != null) {
                                                status.setText(result.message() + " · " + context);
                                            }
                                        }));
    }

    private void filter() {
        if (catalogLoading) return;
        String term =
                search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        String state = filter.getValue();
        var values =
                catalog.stream()
                        .filter(
                                m ->
                                        term.isEmpty()
                                                || m.name().toLowerCase(Locale.ROOT).contains(term)
                                                || Long.toString(m.id()).equals(term)
                                                || m.sourceSpellId() != null
                                                        && Long.toString(m.sourceSpellId())
                                                                .equals(term))
                        .filter(
                                m ->
                                        state == null
                                                || state.equals("All previews")
                                                || state.equals(
                                                        previewStates.getOrDefault(
                                                                        m.id(),
                                                                        PreviewState.NOT_CHECKED)
                                                                .label))
                        .toList();
        mounts.getItems().setAll(values);
        count.setText(values.size() + " of " + catalog.size() + " readable records");
        mounts.setPlaceholder(
                text(
                        catalog.isEmpty()
                                ? "No readable mount records"
                                : "No matches. Clear search or filters.",
                        "muted"));
        if (selected != null) {
            values.stream()
                    .filter(m -> m.id() == selected)
                    .findFirst()
                    .ifPresent(m -> mounts.getSelectionModel().select(m));
        }
    }

    private void select(MountTypes.Mount mount) {
        if (stale(generation, bound)) return;
        selected = mount.id();
        selectionToken.cancel();
        previewToken.cancel();
        selectionToken = new Cancellation();
        previewToken = new Cancellation();
        long request = ++selectionGeneration;
        ++previewGeneration;
        current = null;
        heading.setText(mount.name());
        identity.setText(
                "Mount #"
                        + mount.id()
                        + (mount.sourceSpellId() == null
                                ? ""
                                : " · Source spell #" + mount.sourceSpellId()));
        settingVariants = true;
        variants.getItems().clear();
        settingVariants = false;
        variants.setDisable(true);
        model.loading("Loading recorded appearances…");
        previewStatus.setText("Loading mount details…");
        showDetails(mount, List.of(), List.of(), null);
        rail.getChildren().setAll(activity("Loading mount details…"));
        BuildMounts handle = bound;
        long catalogRequest = generation;
        request(() -> handle.overview(mount.id(), selectionToken))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (request != selectionGeneration
                                                    || !Objects.equals(selected, mount.id())
                                                    || stale(catalogRequest, handle)) {
                                                return;
                                            }
                                            if (failure != null || result == null) {
                                                previewStatus.setText(
                                                        "Mount details could not be loaded.");
                                                model.clear("Appearance metadata unavailable");
                                                rail.getChildren()
                                                        .setAll(
                                                                text(
                                                                        "Mount details are"
                                                                                + " unavailable.",
                                                                        "muted"));
                                                return;
                                            }
                                            current = result;
                                            showDetails(
                                                    mount,
                                                    result.variants(),
                                                    result.issues(),
                                                    null);
                                            settingVariants = true;
                                            variants.getItems().setAll(result.variants());
                                            var preferred =
                                                    result.variants().stream()
                                                            .filter(
                                                                    v ->
                                                                            Objects.equals(
                                                                                    selectedVariants
                                                                                            .get(
                                                                                                    mount
                                                                                                            .id()),
                                                                                    v.recordId()))
                                                            .findFirst()
                                                            .orElse(
                                                                    result.variants().isEmpty()
                                                                            ? null
                                                                            : result.variants()
                                                                                    .getFirst());
                                            variants.getSelectionModel().select(preferred);
                                            variants.setDisable(preferred == null);
                                            settingVariants = false;
                                            if (preferred != null) {
                                                preview(preferred);
                                            } else {
                                                previewStates.put(
                                                        mount.id(), PreviewState.UNAVAILABLE);
                                                mounts.refresh();
                                                model.clear("No readable display mapping");
                                                previewStatus.setText(
                                                        result.message() == null
                                                                ? "No readable appearance variants"
                                                                : result.message());
                                            }
                                        }));
    }

    private void preview(MountTypes.Variant variant) {
        if (stale(generation, bound)) return;
        if (current == null || selected == null) {
            return;
        }
        long mountId = selected;
        selectedVariants.put(mountId, variant.recordId());
        previewToken.cancel();
        previewToken = new Cancellation();
        long request = ++previewGeneration;
        long catalogRequest = generation;
        BuildMounts handle = bound;
        previewStates.put(mountId, PreviewState.LOADING);
        mounts.refresh();
        model.loading("Loading selected model assets…");
        previewStatus.setText("Preparing display #" + variant.displayId() + "…");
        request(() -> handle.preview(mountId, variant.recordId(), previewToken))
                .whenComplete(
                        (result, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (request != previewGeneration
                                                    || !Objects.equals(selected, mountId)
                                                    || stale(catalogRequest, handle)) {
                                                return;
                                            }
                                            if (failure != null
                                                    || result == null
                                                    || result.appearance() == null) {
                                                previewStates.put(
                                                        mountId, PreviewState.UNAVAILABLE);
                                                mounts.refresh();
                                                model.clear("Model preview unavailable");
                                                previewStatus.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Model preview unavailable."
                                                                        + " Metadata remains"
                                                                        + " readable.");
                                                if (result != null) {
                                                    showDetails(
                                                            current.mount(),
                                                            current.variants(),
                                                            current.issues(),
                                                            result);
                                                }
                                                return;
                                            }
                                            previewStates.put(mountId, PreviewState.READY);
                                            mounts.refresh();
                                            model.setAppearance(
                                                    result.appearance(), result.visibleParts(), true);
                                            previewStatus.setText(
                                                    materialCoverage(result.appearance())
                                                            + " · " + (result.restored()
                                                                    ? "restored from saved"
                                                                            + " derivative"
                                                                    : "prepared from selected build"
                                                                            + " assets"));
                                            showDetails(
                                                    current.mount(),
                                                    current.variants(),
                                                    current.issues(),
                                                    result);
                                        }));
    }

    private static String materialCoverage(com.vibeminer.rendering.models.MeshAppearance appearance) {
        long supported = appearance.surfaces().stream()
                .map(com.vibeminer.rendering.models.MeshAppearance.Surface::sectionIndex)
                .distinct().count();
        int total = appearance.mesh().parts().size();
        return (supported < total ? "Partial material preview · " : "Static model preview · ")
                + supported + "/" + total + " supported sections"
                + (supported < total
                        ? ". Unsupported passes are hidden unless All recorded surfaces is enabled."
                        : "");
    }

    private void showDetails(
            MountTypes.Mount mount,
            List<MountTypes.Variant> recorded,
            List<String> issues,
            com.vibeminer.concepts.models.DisplayModelTypes.Preview preview) {
        rail.getChildren()
                .setAll(
                        text("Mount details", "h2"),
                        text("DESCRIPTION", "eyebrow"),
                        text(value(mount.description()), "research-details"),
                        text("SOURCE", "eyebrow"),
                        text(value(mount.sourceText()), "research-details"),
                        text("APPEARANCES", "eyebrow"),
                        text(
                                recorded.isEmpty()
                                        ? "No readable variants"
                                        : recorded.size()
                                                + " recorded display variant"
                                                + (recorded.size() == 1 ? "" : "s"),
                                "value"));
        if (mount.conditionId() != null && mount.conditionId() != 0) {
            rail.getChildren()
                    .add(text("Condition #" + mount.conditionId() + " · not evaluated", "muted"));
        }
        var source =
                new VBox(
                        8,
                        text("Selected build · " + context, "muted"),
                        text(
                                "Mount #"
                                        + mount.id()
                                        + " · source spell "
                                        + value(mount.sourceSpellId()),
                                "muted"),
                        text(
                                "Source type: "
                                        + value(mount.sourceType())
                                        + " · mount type: "
                                        + value(mount.mountTypeId()),
                                "muted"),
                        text("Flags: " + value(mount.flags()), "muted"));
        for (var variant : recorded) {
            source.getChildren()
                    .add(
                            text(
                                    variant.sourceTable()
                                            + " #"
                                            + variant.recordId()
                                            + " → display #"
                                            + variant.displayId()
                                            + " · condition "
                                            + value(variant.conditionId()),
                                    "muted"));
        }
        for (String issue : issues) {
            source.getChildren().add(text(issue, "muted"));
        }
        if (preview != null) {
            if (preview.reference() != null) {
                var ref = preview.reference();
                source.getChildren()
                        .add(
                                text(
                                        "Display #"
                                                + ref.displayId()
                                                + " → model #"
                                                + ref.modelId()
                                                + " → file #"
                                                + ref.modelFileDataId(),
                                        "muted"));
            }
            for (String issue : preview.issues()) {
                source.getChildren().add(text(issue, "muted"));
            }
        }
        var evidence = new TitledPane("Source records & conditions", source);
        evidence.setExpanded(false);
        rail.getChildren().add(evidence);
    }

    private static String value(Object value) {
        return value == null
                ? "Not recorded"
                : value.toString().isBlank() ? "No text recorded" : value.toString();
    }

    private static Label text(String value, String style) {
        var label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private boolean stale(long request, BuildMounts handle) {
        if (closed || request != generation) return true;
        if (bound == handle && revisions.getAsLong() == revision && handles.get() == handle)
            return false;
        invalidate();
        mounts.setDisable(true);
        variants.getItems().clear();
        variants.setDisable(true);
        mounts.setPlaceholder(text("Build changed. Reload mount records.", "muted"));
        model.clear("Build changed. Reload mount records.");
        status.setText("Build changed. Reload mount records.");
        return true;
    }

    void invalidate() {
        generation++;
        selectionGeneration++;
        previewGeneration++;
        catalogToken.cancel();
        selectionToken.cancel();
        previewToken.cancel();
        catalogToken = new Cancellation();
        selectionToken = new Cancellation();
        previewToken = new Cancellation();
        loaded = false;
        catalogLoading = false;
        mounts.setPlaceholder(text("Choose a mount record.", "muted"));
        selected = null;
        current = null;
        catalog = List.of();
        mounts.getItems().clear();
        previewStates.clear();
        selectedVariants.clear();
        model.clear("Select a mount");
        rail.getChildren().setAll(text("Select a mount to inspect its details.", "muted"));
    }

    @Override
    public void close() {
        closed = true;
        invalidate();
        model.close();
    }
}
