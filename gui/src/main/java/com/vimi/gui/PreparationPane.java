package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.ConceptQueries;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.ItemQuery;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Real build workspace. Table-backed areas share one initial readiness gate. */
public final class PreparationPane extends BorderPane implements AutoCloseable {
    private final BuildPreparation.Job job;
    private final Label readiness = new Label();
    private final Label filesFact = new Label(), tablesFact = new Label(), namesFact = new Label();
    private final TextArea issues = new TextArea();
    private final ProgressBar progress = new ProgressBar(-1);
    private final Map<String, Button> areas = new LinkedHashMap<>();
    private final Map<String, Browser> browsers = new LinkedHashMap<>();
    private final Timeline clock;
    private final Button retry, cancel;
    private final VBox overview = new VBox(12);
    private boolean closed;
    private long workspaceRevision;
    private String currentArea = "Overview";

    public PreparationPane(
            BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich) {
        this.job = job;
        var heading =
                new VBox(
                        5,
                        label(job.build().edition() + " · " + job.build().product(), "eyebrow"),
                        label(job.build().version() + " · " + job.locale(), "h2"));
        var navigation = new HBox(8);
        for (String area : List.of("Overview", "Files", "Tables", "Items", "Creatures")) {
            var button = new Button(area);
            button.setOnAction(event -> show(area));
            areas.put(area, button);
            navigation.getChildren().add(button);
        }
        var top = new VBox(10, heading, navigation);
        top.setPadding(new Insets(15));
        setTop(top);
        progress.setMaxWidth(Double.MAX_VALUE);
        retry = new Button("Retry / enrich");
        retry.setOnAction(event -> enrich.run());
        cancel = new Button("Cancel preparation");
        cancel.setOnAction(event -> job.cancel());
        var chooseButton = new Button("Choose build");
        chooseButton.setOnAction(event -> choose.run());
        var closeButton = new Button("Close build");
        closeButton.setOnAction(event -> release.run());
        var metadata = job.snapshot().metadata();
        overview.getChildren()
                .addAll(
                        label("Overview", "h1"),
                        readiness,
                        progress,
                        label("BUILD METADATA", "eyebrow"),
                        new Label("Build config: " + job.build().config()),
                        new Label(
                                "Source: "
                                        + job.source().region().toUpperCase(java.util.Locale.ROOT)),
                        new Label(
                                "Locales: "
                                        + (metadata == null
                                                ? "Preparing…"
                                                : String.join(", ", metadata.locales()))),
                        label("PREPARATION", "eyebrow"),
                        filesFact,
                        tablesFact,
                        namesFact,
                        new HBox(10, cancel, retry, chooseButton, closeButton),
                        label("Availability details", "eyebrow"),
                        issues,
                        new Label(
                                "Media is excluded from preparation and is acquired only on demand."));
        overview.setPadding(new Insets(24));
        issues.setEditable(false);
        issues.setWrapText(true);
        issues.setPrefRowCount(8);
        show("Overview");
        clock = new Timeline(new KeyFrame(Duration.millis(250), event -> update()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        update();
    }

    private static Label label(String text, String style) {
        var label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    public static String describe(BuildPreparation.Snapshot snapshot) {
        return PreparationStatus.describe(snapshot);
    }

    private void update() {
        if (closed) return;
        var snapshot = job.snapshot();
        if (job.work() instanceof NativePreparationWork work
                && workspaceRevision != work.revision()) {
            workspaceRevision = work.revision();
            browsers.values().forEach(Browser::invalidate);
            var visible = browsers.get(currentArea);
            if (visible != null) visible.ensureLoaded();
        }
        readiness.setText(describe(snapshot));
        progress.setVisible(snapshot.busy());
        progress.setManaged(snapshot.busy());
        progress.setProgress(
                snapshot.total() != null && snapshot.total() > 0
                        ? Math.min(1, (double) snapshot.done() / snapshot.total())
                        : -1);
        cancel.setDisable(!snapshot.busy() && !job.enrichingNames());
        retry.setDisable(snapshot.busy() || job.enrichingNames());
        var files = snapshot.files();
        filesFact.setText(
                files == null
                        ? "Files: "
                                + (snapshot.busy()
                                        ? "preparing catalog and local presence…"
                                        : "not ready · " + snapshot.activity())
                        : "Files at last local assessment: "
                                + files.files()
                                + " · Stored selected variants: "
                                + files.stored()
                                + " · Size unknown: "
                                + files.unknownSize());
        boolean tablesReady = snapshot.ready() == BuildPreparation.Ready.TABLES_READY;
        tablesFact.setText(
                tablesReady
                        ? tableSummary(snapshot.tables())
                        : !snapshot.busy()
                                ? "Tables: not ready · " + snapshot.activity()
                                : snapshot.ready().ordinal()
                                                < BuildPreparation.Ready.FILES_READY.ordinal()
                                        ? "Tables: waiting for Files"
                                        : "Tables: preparing known table data locally…");
        namesFact.setText(
                job.enrichingNames()
                        ? "Community names/types: enriching…"
                        : job.namesProblem() != null
                                ? "Community names unavailable: " + job.namesProblem()
                                : snapshot.ready().ordinal()
                                                >= BuildPreparation.Ready.FILES_READY.ordinal()
                                        ? "Community names/types: independent enrichment"
                                        : "Community names/types: waiting for Files");
        var problems = new ArrayList<>(snapshot.issues());
        if (snapshot.problem() != null) problems.add(snapshot.problem());
        if (tablesReady)
            for (var table : snapshot.tables()) {
                if (table.state() != TableTypes.State.READY
                        && table.state() != TableTypes.State.NOT_IN_BUILD
                        && table.state() != TableTypes.State.NOT_IN_LOCALE)
                    problems.add(
                            table.name()
                                    + " ("
                                    + table.fileDataId()
                                    + "): "
                                    + table.state()
                                    + (table.reason() == null ? "" : " · " + table.reason()));
            }
        String details =
                problems.isEmpty()
                        ? "No preparation issues reported."
                        : String.join("\n", problems);
        if (!issues.getText().equals(details)) issues.setText(details);
        for (var entry : areas.entrySet()) {
            boolean enabled = PreparationStatus.available(entry.getKey(), snapshot.ready());
            entry.getValue().setDisable(!enabled);
            entry.getValue()
                    .setText(PreparationStatus.tab(entry.getKey(), snapshot, job.enrichingNames()));
            boolean active =
                    entry.getKey().equals("Files")
                            ? job.enrichingNames()
                                    || snapshot.busy()
                                            && snapshot.phase() == BuildPreparation.Phase.FILES
                            : entry.getKey().equals("Tables")
                                    && snapshot.busy()
                                    && snapshot.phase().ordinal()
                                            >= BuildPreparation.Phase.KEYS.ordinal();
            if (active && entry.getValue().getGraphic() == null) {
                var indicator = new javafx.scene.control.ProgressIndicator(-1);
                indicator.setPrefSize(14, 14);
                indicator.setMaxSize(14, 14);
                entry.getValue().setGraphic(indicator);
            } else if (!active) entry.getValue().setGraphic(null);
            entry.getValue()
                    .setAccessibleHelp(
                            enabled ? "Ready to browse" : "Waiting for initial preparation");
        }
    }

    private static String tableSummary(List<TableTypes.Entry> tables) {
        long absent =
                tables.stream()
                        .filter(
                                t ->
                                        t.state() == TableTypes.State.NOT_IN_BUILD
                                                || t.state() == TableTypes.State.NOT_IN_LOCALE)
                        .count();
        long readable = tables.stream().filter(t -> t.state() == TableTypes.State.READY).count();
        return "Known tables assessed: "
                + tables.size()
                + " · Fully readable: "
                + readable
                + " · Absent from build/locale: "
                + absent
                + " · Other outcomes: "
                + (tables.size() - absent - readable);
    }

    private void show(String area) {
        currentArea = area;
        if (area.equals("Overview")) {
            var scroll = new ScrollPane(overview);
            scroll.setFitToWidth(true);
            setCenter(scroll);
            return;
        }
        var browser = browsers.computeIfAbsent(area, Browser::new);
        setCenter(browser);
        browser.ensureLoaded();
    }

    @Override
    public void close() {
        closed = true;
        clock.stop();
        browsers.values().forEach(Browser::close);
    }

    private NativePreparationWork nativeWork() {
        if (!(job.work() instanceof NativePreparationWork work))
            throw new IllegalStateException("No native workspace");
        return work;
    }

    private record Record(
            long id,
            String title,
            java.util.function.Function<Cancellation, CompletableFuture<?>> details,
            java.util.function.Function<Cancellation, CompletableFuture<?>> media) {
        @Override
        public String toString() {
            return title;
        }
    }

    private final class Browser extends BorderPane implements AutoCloseable {
        private final String area;
        private final ListView<Record> records = new ListView<>();
        private final TextArea detail = new TextArea();
        private final TextArea mediaDetail = new TextArea();
        private Cancellation mediaToken = new Cancellation();
        private long mediaGeneration;
        private final TextField search = new TextField();
        private final Label state = new Label();
        private final Button next = new Button("Next page");
        private final Button refresh = new Button("Search / refresh");
        private final Cancellation lifetime = new Cancellation();
        private Cancellation detailToken = new Cancellation();
        private boolean loading;
        private boolean needsRefresh;
        private final Button media = new Button("Inspect media on demand");
        private Cancellation token = new Cancellation();
        private com.vibeminer.files.AsyncCursor<FileTypes.BuildFile> cursor;
        private String after;
        private long generation, detailGeneration;
        private boolean loaded;
        private volatile boolean disposed;

        Browser(String area) {
            this.area = area;
            search.setPromptText(
                    "Files".equals(area)
                            ? "Filter this page by ID/name"
                            : "Search name or exact ID");
            refresh.setOnAction(
                    event -> {
                        after = null;
                        load(true);
                    });
            search.setOnAction(event -> refresh.fire());
            next.setOnAction(event -> load(false));
            var toolbar = new HBox(10, search, refresh, next, state);
            toolbar.setPadding(new Insets(12));
            setTop(toolbar);
            detail.setEditable(false);
            detail.setWrapText(true);
            mediaDetail.setEditable(false);
            mediaDetail.setWrapText(true);
            mediaDetail.setVisible(false);
            mediaDetail.setManaged(false);
            media.setDisable(true);
            media.setOnAction(
                    event -> {
                        var record = records.getSelectionModel().getSelectedItem();
                        if (record != null && record.media() != null)
                            details(record.media(), "Loading media asynchronously…", true);
                    });
            records.getSelectionModel()
                    .selectedItemProperty()
                    .addListener(
                            (observable, before, record) -> {
                                if (record != null) {
                                    cancelMedia();
                                    media.setDisable(record.media() == null);
                                    details(record.details(), "Loading local data…", false);
                                }
                            });
            var inspector = new VBox(10, detail, media, mediaDetail);
            VBox.setVgrow(detail, Priority.ALWAYS);
            VBox.setVgrow(mediaDetail, Priority.ALWAYS);
            var split = new SplitPane(records, inspector);
            split.setDividerPositions(.48);
            setCenter(split);
            setBottom(
                    new Label(
                            "Prepared local data · Presence checked per page; refresh to reassess. Protected/unsupported outcomes remain explicit. Media is independent."));
        }

        void ensureLoaded() {
            if (!loaded || needsRefresh) load(true);
        }

        void invalidate() {
            needsRefresh = true;
        }

        private void cancelMedia() {
            mediaToken.cancel();
            mediaGeneration++;
            mediaDetail.clear();
            mediaDetail.setVisible(false);
            mediaDetail.setManaged(false);
        }

        private void details(
                java.util.function.Function<Cancellation, CompletableFuture<?>> request,
                String text,
                boolean mediaRequest) {
            final Cancellation selectedToken;
            final long selected;
            final TextArea target;
            if (mediaRequest) {
                mediaToken.cancel();
                mediaToken = new Cancellation();
                selectedToken = mediaToken;
                selected = ++mediaGeneration;
                target = mediaDetail;
                mediaDetail.setVisible(true);
                mediaDetail.setManaged(true);
            } else {
                detailToken.cancel();
                detailToken = new Cancellation();
                selectedToken = detailToken;
                selected = ++detailGeneration;
                target = detail;
            }
            target.setText(text);
            CompletableFuture.supplyAsync(
                            () -> request.apply(selectedToken),
                            command -> Thread.startVirtualThread(command))
                    .thenCompose(value -> value)
                    .handleAsync(
                            (value, error) ->
                                    error == null
                                            ? format(value, 0)
                                            : "Unavailable: " + errorMessage(error),
                            command -> Thread.startVirtualThread(command))
                    .whenComplete(
                            (value, error) ->
                                    Platform.runLater(
                                            () -> {
                                                if (disposed
                                                        || selected
                                                                != (mediaRequest
                                                                        ? mediaGeneration
                                                                        : detailGeneration)) return;
                                                String textValue =
                                                        error == null ? value : errorMessage(error);
                                                target.setText(
                                                        textValue.length() > 131072
                                                                ? textValue.substring(0, 131072)
                                                                        + "\nPreview truncated."
                                                                : textValue);
                                            }));
        }

        private void load(boolean reset) {
            if (loading || disposed) return;
            loading = true;
            needsRefresh = false;
            refresh.setDisable(true);
            detailToken.cancel();
            detailGeneration++;
            cancelMedia();
            long request = ++generation;
            token.cancel();
            token = new Cancellation();
            var cancellation = token;
            loaded = true;
            next.setDisable(true);
            state.setText("Loading…");
            String query = search.getText().strip();
            String continuation = after;
            CompletableFuture.supplyAsync(
                            () -> {
                                var work = nativeWork();
                                var rows = new ArrayList<Record>();
                                if (area.equals("Files")) {
                                    if (reset && cursor != null) {
                                        cursor.close();
                                        cursor = null;
                                    }
                                    if (cursor == null)
                                        cursor =
                                                work.filesHandle()
                                                        .listMetadata(
                                                                FileTypes.FileFilter.ALL, lifetime);
                                    if (disposed) {
                                        cursor.close();
                                        throw new java.util.concurrent.CancellationException();
                                    }
                                    var page = cursor.next(250, cancellation).join();
                                    for (var file : page) {
                                        if (!query.isEmpty()
                                                && !(file.fileDataId() + " " + file.path())
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .contains(
                                                                query.toLowerCase(
                                                                        java.util.Locale.ROOT)))
                                            continue;
                                        rows.add(
                                                new Record(
                                                        file.fileDataId(),
                                                        file.fileDataId()
                                                                + " · "
                                                                + (file.path() == null
                                                                        ? "Unnamed"
                                                                        : file.path())
                                                                + " · "
                                                                + file.format()
                                                                + " · "
                                                                + (file.size() == null
                                                                        ? "Size unknown"
                                                                        : file.size()
                                                                                + " decoded bytes")
                                                                + " · "
                                                                + file.storage(),
                                                        ct ->
                                                                CompletableFuture.completedFuture(
                                                                        file),
                                                        null));
                                    }
                                    return new Page(
                                            rows,
                                            page.isEmpty()
                                                    ? "End of file catalog"
                                                    : "Selected locale: " + work.locale(),
                                            !page.isEmpty(),
                                            null);
                                }
                                if (area.equals("Tables")) {
                                    var ledger = job.snapshot().tables();
                                    for (var table : ledger) {
                                        if (!query.isEmpty()
                                                && !(table.fileDataId() + " " + table.name())
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .contains(
                                                                query.toLowerCase(
                                                                        java.util.Locale.ROOT)))
                                            continue;
                                        rows.add(
                                                new Record(
                                                        table.fileDataId(),
                                                        table.name()
                                                                + " · "
                                                                + table.fileDataId()
                                                                + " · "
                                                                + table.state(),
                                                        ct ->
                                                                work.tablesHandle()
                                                                        .page(
                                                                                new TableTypes
                                                                                        .PageRequest(
                                                                                        TableTypes
                                                                                                .Ref
                                                                                                .id(
                                                                                                        table
                                                                                                                .fileDataId())),
                                                                                TableTypes.Priority
                                                                                        .INTERACTIVE,
                                                                                ct),
                                                        null));
                                    }
                                    return new Page(
                                            rows, "Known table preparation outcomes", false, null);
                                }
                                Long exact = ConceptQueries.tryParseId(query);
                                if (area.equals("Items")) {
                                    if (work.itemsHandle() == null)
                                        throw new IllegalStateException(
                                                "Item service unavailable; see Overview details");
                                    var page =
                                            work.itemsHandle()
                                                    .list(
                                                            new ItemQuery(
                                                                    exact == null ? query : "",
                                                                    exact,
                                                                    50,
                                                                    reset ? null : continuation),
                                                            TableTypes.Priority.INTERACTIVE,
                                                            cancellation)
                                                    .join();
                                    for (var item : page.entries())
                                        rows.add(
                                                new Record(
                                                        item.id(),
                                                        item.id() + " · " + item.name(),
                                                        ct ->
                                                                work.itemsHandle()
                                                                        .getData(
                                                                                item.id(),
                                                                                TableTypes.Priority
                                                                                        .INTERACTIVE,
                                                                                ct),
                                                        ct ->
                                                                work.itemsHandle()
                                                                        .get(
                                                                                item.id(),
                                                                                TableTypes.Priority
                                                                                        .INTERACTIVE,
                                                                                ct)));
                                    return new Page(
                                            rows,
                                            page.state()
                                                    + (page.message() == null
                                                            ? ""
                                                            : " · " + page.message()),
                                            page.next() != null,
                                            page.next());
                                }
                                if (work.creaturesHandle() == null)
                                    throw new IllegalStateException(
                                            "Creature service unavailable; see Overview details");
                                var page =
                                        work.creaturesHandle()
                                                .list(
                                                        new CreatureTypes.CreatureQuery(
                                                                exact == null ? query : "",
                                                                exact,
                                                                50,
                                                                reset ? null : continuation),
                                                        TableTypes.Priority.INTERACTIVE,
                                                        cancellation)
                                                .join();
                                if (page.entries() != null)
                                    for (var creature : page.entries())
                                        rows.add(
                                                new Record(
                                                        creature.id(),
                                                        creature.id() + " · " + creature.name(),
                                                        ct ->
                                                                work.creaturesHandle()
                                                                        .getData(
                                                                                creature.id(),
                                                                                TableTypes.Priority
                                                                                        .INTERACTIVE,
                                                                                ct),
                                                        ct ->
                                                                work.creaturesHandle()
                                                                        .get(
                                                                                creature.id(),
                                                                                TableTypes.Priority
                                                                                        .INTERACTIVE,
                                                                                ct)));
                                return new Page(
                                        rows,
                                        page.state()
                                                + (page.message() == null
                                                        ? ""
                                                        : " · " + page.message()),
                                        page.next() != null,
                                        page.next());
                            },
                            command -> Thread.startVirtualThread(command))
                    .whenComplete(
                            (page, error) ->
                                    Platform.runLater(
                                            () -> {
                                                if (disposed || request != generation) return;
                                                loading = false;
                                                refresh.setDisable(false);
                                                if (error != null) {
                                                    state.setText(
                                                            "Unavailable: " + errorMessage(error));
                                                    return;
                                                }
                                                records.getItems().setAll(page.rows());
                                                state.setText(page.state());
                                                next.setDisable(!page.more());
                                                after = page.next();
                                                detail.clear();
                                                media.setDisable(true);
                                                detailGeneration++;
                                                if (needsRefresh) load(true);
                                            }));
        }

        @Override
        public void close() {
            disposed = true;
            lifetime.cancel();
            cancelMedia();
            detailToken.cancel();
            token.cancel();
            detailGeneration++;
            generation++;
            var closing = cursor;
            if (closing != null) Thread.startVirtualThread(closing::close);
        }
    }

    private record Page(List<Record> rows, String state, boolean more, String next) {}

    private static String errorMessage(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException
                && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String format(Object value, int depth) {
        var output = new StringBuilder();
        append(value, output, depth);
        return output.toString();
    }

    private static void text(StringBuilder output, String text) {
        int remaining = 131072 - output.length();
        if (remaining > 0) output.append(text, 0, Math.min(text.length(), remaining));
    }

    private static void append(Object value, StringBuilder output, int depth) {
        if (output.length() >= 131072) return;
        if (value == null) {
            text(output, "Not available");
            return;
        }
        if (value.getClass().isArray()) {
            text(output, java.lang.reflect.Array.getLength(value) + " array values");
            return;
        }
        if (depth > 8) {
            text(output, value.getClass().getSimpleName());
            return;
        }
        if (value instanceof List<?> list) {
            int count = 0;
            for (Object item : list) {
                if (count++ >= 100 || output.length() >= 131072) break;
                append(item, output, depth + 1);
                text(output, "\n");
            }
            if (list.size() > 100) text(output, "Additional entries omitted.\n");
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                if (output.length() >= 131072) break;
                text(output, "  ".repeat(depth) + component.getName() + ": ");
                try {
                    append(component.getAccessor().invoke(value), output, depth + 1);
                } catch (ReflectiveOperationException error) {
                    text(output, "Unavailable");
                }
                text(output, "\n");
            }
        } else if (value instanceof java.util.Map<?, ?> map) {
            int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= 100 || output.length() >= 131072) break;
                append(entry.getKey(), output, depth + 1);
                text(output, ": ");
                append(entry.getValue(), output, depth + 1);
                text(output, "\n");
            }
        } else {
            String part = String.valueOf(value);
            text(output, part.length() > 1024 ? part.substring(0, 1024) + "…" : part);
        }
    }
}
