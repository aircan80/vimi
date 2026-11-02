package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.ItemClassLabel;
import com.vibeminer.concepts.items.ItemClassLabelState;
import com.vibeminer.concepts.items.ItemClassLabels;
import com.vibeminer.concepts.items.ItemDetail;
import com.vibeminer.concepts.items.ItemFact;
import com.vibeminer.concepts.items.ItemFactAgreement;
import com.vibeminer.concepts.items.ItemFactSet;
import com.vibeminer.concepts.items.ItemFactState;
import com.vibeminer.concepts.items.ItemFactValue;
import com.vibeminer.concepts.items.ItemFactsState;
import com.vibeminer.concepts.items.ItemListLabels;
import com.vibeminer.concepts.items.ItemName;
import com.vibeminer.concepts.items.ItemNameState;
import com.vibeminer.concepts.items.ItemResult;
import com.vibeminer.concepts.items.ItemResultState;
import com.vibeminer.concepts.items.ItemRowState;
import com.vibeminer.concepts.items.ItemSourceValue;
import com.vibeminer.concepts.items.ItemSparseState;
import com.vibeminer.concepts.items.ItemSummary;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javax.imageio.ImageIO;

/**
 * Display-required UI review: authored fixtures, screenshots, async retirement and row navigation.
 */
public final class ResearchBrowserUiTest {
    private ResearchBrowserUiTest() {}

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        done.complete(null);
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
        done.get(15, TimeUnit.SECONDS);
    }

    private static void flush() throws Exception {
        fx(() -> {});
        fx(() -> {});
    }

    private static ItemFact fact(String key, ItemFactValue... cells) {
        return new ItemFact(key, ItemFactState.VALUE, ItemFactAgreement.SINGLE, List.of(cells));
    }

    private static ItemFactValue cell(String column, String value) {
        return new ItemFactValue("ItemSparse", column, 0, 100, value, null);
    }

    private static final class Fixture implements ResearchDataSource {
        public String locale() {
            return "deDE";
        }

        final ItemSummary item =
                new ItemSummary(
                        100,
                        false,
                        null,
                        new ItemName(ItemNameState.VALID, "Synthetic guardian's helm"),
                        List.of(
                                new ItemSourceValue("Item", "ClassID", 0, "4"),
                                new ItemSourceValue("Item", "SubclassID", 1, "4"),
                                new ItemSourceValue("ItemSparse", "OverallQualityID", 2, "4"),
                                new ItemSourceValue("Item", "InventoryType", 3, "1"),
                                new ItemSourceValue("ItemSparse", "ItemLevel", 4, "60")),
                        1000L,
                        null,
                        ItemRowState.FOUND,
                        null);
        final ItemSummary second =
                new ItemSummary(
                        101,
                        false,
                        null,
                        new ItemName(ItemNameState.VALID, "Synthetic second helm"),
                        item.values(),
                        1001L,
                        null,
                        ItemRowState.FOUND,
                        null);
        final java.util.concurrent.CountDownLatch iconsStarted =
                new java.util.concurrent.CountDownLatch(2);
        final java.util.Set<Long> iconIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private static ItemClassLabels labels() {
            return new ItemClassLabels(
                    new ItemClassLabel(
                            ItemClassLabelState.READY,
                            List.of(),
                            "Rüstung",
                            null,
                            null,
                            null,
                            0,
                            null,
                            null,
                            null),
                    new ItemClassLabel(
                            ItemClassLabelState.READY,
                            List.of(),
                            "Platte",
                            null,
                            null,
                            null,
                            0,
                            null,
                            null,
                            null));
        }

        final TableTypes.Entry table =
                new TableTypes.Entry(
                        200,
                        "SyntheticTable",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(2, 2, 0, 0, "WDC5", "12345678"),
                        "fixture");
        final FileTypes.BuildFile file =
                new FileTypes.BuildFile(
                        300,
                        "world/synthetic/model.m2",
                        FileTypes.FilePathSource.LISTFILE,
                        123456L,
                        "fixture",
                        FileTypes.FileFormat.M2,
                        FileTypes.FileFormatSource.EXTENSION,
                        FileTypes.FileStorage.STORED,
                        false);
        final CreatureTypes.CreatureSummary creature =
                new CreatureTypes.CreatureSummary(
                        400,
                        false,
                        null,
                        new CreatureTypes.CreatureName(
                                CreatureTypes.CreatureNameState.VALID,
                                "Synthetic forest guardian",
                                null),
                        List.of(new CreatureTypes.CreatureSourceValue("Classification", 0, "1")),
                        List.of());

        final AtomicReference<com.vibeminer.concepts.items.ItemGridQuery> latestQuery =
                new AtomicReference<>();

        public CompletableFuture<ResearchPresentation.Page> items(
                com.vibeminer.concepts.items.ItemGridQuery query, Cancellation token) {
            latestQuery.set(query);
            var choice = new com.vibeminer.concepts.items.ItemGridPage.Choice("4", "Rüstung");
            var sub = new com.vibeminer.concepts.items.ItemGridPage.Choice("4:4", "Platte");
            return page("Items", query.search(), null, token)
                    .thenApply(
                            page ->
                                    new ResearchPresentation.Page(
                                            page.rows(),
                                            null,
                                            page.status(),
                                            1200,
                                            new com.vibeminer.concepts.items.ItemGridPage.Options(
                                                    List.of(
                                                            choice,
                                                            new com.vibeminer.concepts.items
                                                                    .ItemGridPage.Choice(
                                                                    "12", "Quest")),
                                                    List.of(sub),
                                                    List.of(),
                                                    List.of())));
        }

        public CompletableFuture<ResearchPresentation.Page> page(
                String area, String query, String after, Cancellation token) {
            var row =
                    switch (area) {
                        case "Files" -> ResearchPresentation.file(file);
                        case "Tables" -> ResearchPresentation.table(table);
                        case "Items" ->
                                ResearchPresentation.item(new ItemListLabels(item, labels()));
                        default -> ResearchPresentation.creature(creature);
                    };
            return CompletableFuture.completedFuture(
                    new ResearchPresentation.Page(
                            area.equals("Items")
                                    ? List.of(
                                            row,
                                            ResearchPresentation.item(
                                                    new ItemListLabels(second, labels())))
                                    : List.of(row),
                            null,
                            "Authored UI fixture · no live build measurements"));
        }

        public CompletableFuture<?> detail(
                String area, ResearchPresentation.Row row, Cancellation token) {
            Object value =
                    switch (area) {
                        case "Files" -> file;
                        case "Tables" -> table;
                        case "Items" ->
                                new ItemResult(
                                        ItemResultState.FOUND,
                                        new ItemDetail(
                                                item,
                                                null,
                                                null,
                                                null,
                                                new ItemFactSet(
                                                        ItemFactsState.READY,
                                                        ItemSparseState.PRESENT,
                                                        List.of(
                                                                fact(
                                                                        "statType",
                                                                        cell(
                                                                                "StatModifier_bonusStat[0]",
                                                                                "7")),
                                                                fact(
                                                                        "statAmount",
                                                                        cell(
                                                                                "StatModifier_bonusAmount[0]",
                                                                                "20"))),
                                                        null,
                                                        null),
                                                null,
                                                List.of(),
                                                null),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null);
                        default ->
                                new CreatureTypes.CreatureResult(
                                        CreatureTypes.CreatureResultState.FOUND,
                                        new CreatureTypes.CreatureDetail(
                                                creature, null, "Elite", List.of()),
                                        null,
                                        null,
                                        null,
                                        null);
                    };
            return CompletableFuture.completedFuture(value);
        }

        public CompletableFuture<?> media(
                String area, ResearchPresentation.Row row, Cancellation token) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Authored unavailable media"));
        }

        public CompletableFuture<ResearchSource.TableRows> tableRows(
                long id, Long after, String identity, Cancellation token) {
            return CompletableFuture.completedFuture(
                    new ResearchSource.TableRows(
                            new TableTypes.SchemaResult(table, List.of(), null),
                            new TableTypes.Page(
                                    table,
                                    TableTypes.PageState.OK,
                                    List.of(
                                            new TableTypes.Row(
                                                    0,
                                                    new com.vibeminer.formats.db2.Db2Types.Row(
                                                            42, false, null, List.of(),
                                                            List.of()))),
                                    null,
                                    "fixture",
                                    1,
                                    null,
                                    null)));
        }

        public CompletableFuture<BlpImageTypes.Result> icon(long id, Cancellation token) {
            if (iconIds.add(id)) iconsStarted.countDown();
            var pixels = new byte[] {30, (byte) 150, 100, (byte) 255};
            var image =
                    new com.vibeminer.formats.images.Blp2Types.Image(
                            1,
                            1,
                            4,
                            pixels,
                            com.vibeminer.formats.images.Blp2Types.Encoding.BGRA8,
                            8,
                            0,
                            0,
                            1,
                            1,
                            List.of(),
                            0);
            return CompletableFuture.completedFuture(
                    new BlpImageTypes.Result(
                            BlpImageTypes.State.READY,
                            image,
                            BlpImageTypes.Cache.NONE,
                            List.of(),
                            null,
                            null,
                            null,
                            null));
        }
    }

    private static TableView<?> grid(ResearchBrowser browser) {
        return (TableView<?>) browser.lookup("#research-results");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addSort(TableView table, javafx.scene.control.TableColumn column) {
        table.getSortOrder().add(column);
    }

    private static javafx.scene.control.ComboBox<?> itemFilter(
            ResearchBrowser browser, String name) {
        return browser.lookupAll(".combo-box").stream()
                .filter(
                        node ->
                                node instanceof javafx.scene.control.ComboBox<?> combo
                                        && combo.getAccessibleText().startsWith(name + " ·"))
                .map(node -> (javafx.scene.control.ComboBox<?>) node)
                .findFirst()
                .orElseThrow();
    }

    private static void assertFilterText(ResearchBrowser browser, String name, String expected) {
        browser.applyCss();
        browser.layout();
        var box = itemFilter(browser, name);
        var shown =
                box.lookupAll(".list-cell").stream()
                        .filter(
                                node ->
                                        node instanceof javafx.scene.control.ListCell<?> cell
                                                && expected.equals(cell.getText()))
                        .findFirst();
        if (shown.isEmpty())
            throw new AssertionError(
                    "Selected "
                            + name
                            + " label disappeared: expected "
                            + expected
                            + ", value="
                            + box.getValue()
                            + ", selected index="
                            + box.getSelectionModel().getSelectedIndex());
    }

    private static void assertToolbarAlignment(ResearchBrowser browser) {
        browser.applyCss();
        browser.layout();
        var search = browser.lookup("#research-search");
        var reference = search.localToScene(search.getBoundsInLocal());
        var controls = new java.util.ArrayList<javafx.scene.Node>(browser.lookupAll(".combo-box"));
        controls.add(button(browser, "Refresh"));
        for (var control : controls) {
            var bounds = control.localToScene(control.getBoundsInLocal());
            if (Math.abs(bounds.getMinY() - reference.getMinY()) > 1
                    || Math.abs(bounds.getMaxY() - reference.getMaxY()) > 1)
                throw new AssertionError(
                        "Browser controls are not aligned: " + reference + " vs " + bounds);
        }
    }

    private static Button button(ResearchBrowser browser, String text) {
        return browser.lookupAll(".button").stream()
                .filter(node -> node instanceof Button b && b.getText().equals(text))
                .map(node -> (Button) node)
                .findFirst()
                .orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        var start = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    start.complete(null);
                });
        start.get(15, TimeUnit.SECONDS);
        try {
            Path output = Path.of(System.getProperty("vimi.uiReviewDirectory", "build/ui-review"));
            Files.createDirectories(output);
            for (String area : List.of("Files", "Tables", "Items", "Creatures")) {
                var fixture = new Fixture();
                var browser = new AtomicReference<ResearchBrowser>();
                var scene = new AtomicReference<Scene>();
                fx(
                        () -> {
                            var view = new ResearchBrowser(area, fixture, name -> {});
                            browser.set(view);
                            var root = new javafx.scene.layout.BorderPane(view);
                            var title =
                                    new javafx.scene.control.Label(
                                            "VibeMiner · " + area + " · AUTHORED UI FIXTURE");
                            title.setStyle("-fx-font-size: 18px; -fx-padding: 16px;");
                            root.setTop(title);
                            var s = new Scene(root, 1320, 850);
                            s.getStylesheets()
                                    .add(
                                            ResearchBrowser.class
                                                    .getResource("/vimi.css")
                                                    .toExternalForm());
                            scene.set(s);
                            root.applyCss();
                            root.layout();
                            view.ensureLoaded();
                        });
                flush();
                fx(() -> assertToolbarAlignment(browser.get()));
                if (area.equals("Items")) {
                    if (!fixture.iconsStarted.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("Unselected row did not request its icon");
                    fx(
                            () -> {
                                var records = grid(browser.get()).getItems();
                                if (records.size() != 2
                                        || !((ResearchPresentation.Row) records.get(1))
                                                .cell(2)
                                                .equals("Rüstung"))
                                    throw new AssertionError(
                                            "Localized classification missing from grid");
                            });
                }
                fx(() -> grid(browser.get()).getSelectionModel().selectFirst());
                flush();
                if (area.equals("Tables")) {
                    fx(() -> button(browser.get(), "Browse table rows").fire());
                    flush();
                }
                if (area.equals("Items"))
                    fx(
                            () -> {
                                boolean line =
                                        browser.get().lookupAll(".wow-stat").stream()
                                                .anyMatch(
                                                        node ->
                                                                node
                                                                                instanceof
                                                                                javafx.scene.control
                                                                                                .Label
                                                                                        label
                                                                        && label.getText()
                                                                                .equals(
                                                                                        "+20 Stamina"));
                                if (!line)
                                    throw new AssertionError(
                                            "Layer-projected attribute missing from tooltip");
                            });
                fx(
                        () -> {
                            try {
                                scene.get().getRoot().applyCss();
                                scene.get().getRoot().layout();
                                var image = scene.get().snapshot(null);
                                int w = (int) image.getWidth(), h = (int) image.getHeight();
                                int[] pixels = new int[w * h];
                                image.getPixelReader()
                                        .getPixels(
                                                0,
                                                0,
                                                w,
                                                h,
                                                PixelFormat.getIntArgbInstance(),
                                                pixels,
                                                0,
                                                w);
                                var bitmap = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                                bitmap.setRGB(0, 0, w, h, pixels, 0, w);
                                ImageIO.write(
                                        bitmap,
                                        "png",
                                        output.resolve(
                                                        "research-"
                                                                + area.toLowerCase(
                                                                        java.util.Locale.ROOT)
                                                                + ".png")
                                                .toFile());
                            } catch (Exception error) {
                                throw new RuntimeException(error);
                            }
                        });
                if (area.equals("Items")) {
                    fx(
                            () -> {
                                var box =
                                        browser.get().lookupAll(".combo-box").stream()
                                                .filter(
                                                        node ->
                                                                node
                                                                                instanceof
                                                                                javafx.scene.control
                                                                                                        .ComboBox<
                                                                                                ?>
                                                                                        combo
                                                                        && combo.getAccessibleText()
                                                                                .startsWith(
                                                                                        "Class ·"))
                                                .map(
                                                        node ->
                                                                (javafx.scene.control.ComboBox<?>)
                                                                        node)
                                                .findFirst()
                                                .orElseThrow();
                                box.getSelectionModel().select(1);
                            });
                    flush();
                    if (!"4".equals(fixture.latestQuery.get().itemClass()))
                        throw new AssertionError("Class filter did not reach layer query");
                    fx(() -> assertFilterText(browser.get(), "Class", "Rüstung"));
                    fx(() -> itemFilter(browser.get(), "Class").getSelectionModel().select(2));
                    flush();
                    if (!"12".equals(fixture.latestQuery.get().itemClass()))
                        throw new AssertionError("Quest filter did not reach layer query");
                    fx(() -> assertFilterText(browser.get(), "Class", "Quest"));
                    fx(() -> button(browser.get(), "Refresh").fire());
                    flush();
                    fx(() -> assertFilterText(browser.get(), "Class", "Quest"));
                    fx(
                            () -> {
                                var table = grid(browser.get());
                                var column = table.getColumns().getLast();
                                column.setSortType(
                                        javafx.scene.control.TableColumn.SortType.DESCENDING);
                                table.getSortOrder().clear();
                                addSort(table, column);
                            });
                    flush();
                    if (fixture.latestQuery.get().sort()
                                    != com.vibeminer.concepts.items.ItemGridQuery.Sort.LEVEL
                            || !fixture.latestQuery.get().descending())
                        throw new AssertionError("Global sort did not reach layer query");
                    fx(
                            () -> {
                                var input = (TextField) browser.get().lookup(".text-field");
                                input.setText("beyond first page");
                                input.fireEvent(new javafx.event.ActionEvent());
                            });
                    flush();
                    if (!fixture.latestQuery.get().search().equals("beyond first page")
                            || fixture.latestQuery.get().offset() != 0)
                        throw new AssertionError("Search did not reset global query");
                }
                fx(() -> browser.get().close());
            }
            dropdownContrast();
            staleReplies();
            System.out.println(
                    "Passed rendered Files/Tables/Items/Creatures, attribute tooltip, table row navigation and stale/closed request retirement");
        } finally {
            Platform.exit();
        }
    }

    private static void dropdownContrast() throws Exception {
        var stage = new AtomicReference<javafx.stage.Stage>();
        var browser = new AtomicReference<ResearchBrowser>();
        fx(
                () -> {
                    var view = new ResearchBrowser("Items", new Fixture(), name -> {});
                    browser.set(view);
                    var window = new javafx.stage.Stage();
                    stage.set(window);
                    window.setX(-10000);
                    window.setY(-10000);
                    var scene = new Scene(view, 1320, 850);
                    scene.getStylesheets()
                            .add(ResearchBrowser.class.getResource("/vimi.css").toExternalForm());
                    window.setScene(scene);
                    window.show();
                    view.ensureLoaded();
                });
        flush();
        try {
            fx(() -> itemFilter(browser.get(), "Class").show());
            flush();
            fx(
                    () -> {
                        var popup =
                                javafx.stage.Window.getWindows().stream()
                                        .filter(
                                                window ->
                                                        window instanceof javafx.stage.PopupWindow
                                                                && window.isShowing())
                                        .findFirst()
                                        .orElseThrow();
                        var root = popup.getScene().getRoot();
                        root.applyCss();
                        root.layout();
                        var hovered =
                                root.lookupAll(".list-cell").stream()
                                        .filter(
                                                node ->
                                                        node
                                                                        instanceof
                                                                        javafx.scene.control
                                                                                                .ListCell<
                                                                                        ?>
                                                                                cell
                                                                && "Quest".equals(cell.getText()))
                                        .map(node -> (javafx.scene.control.ListCell<?>) node)
                                        .findFirst()
                                        .orElseThrow();
                        hovered.pseudoClassStateChanged(
                                javafx.css.PseudoClass.getPseudoClass("hover"), true);
                        root.applyCss();
                        assertCellColors(hovered, "#243e3c", "#dceee6");
                        var selected =
                                root.lookupAll(".list-cell").stream()
                                        .filter(
                                                node ->
                                                        node
                                                                        instanceof
                                                                        javafx.scene.control
                                                                                                .ListCell<
                                                                                        ?>
                                                                                cell
                                                                && cell.isSelected())
                                        .map(node -> (javafx.scene.control.ListCell<?>) node)
                                        .findFirst()
                                        .orElseThrow();
                        assertCellColors(selected, "#ffffff", "#137b70");
                        selected.pseudoClassStateChanged(
                                javafx.css.PseudoClass.getPseudoClass("hover"), true);
                        root.applyCss();
                        assertCellColors(selected, "#ffffff", "#137b70");
                        itemFilter(browser.get(), "Class").hide();
                    });
        } finally {
            fx(
                    () -> {
                        browser.get().close();
                        stage.get().close();
                    });
        }
    }

    private static void assertCellColors(
            javafx.scene.control.ListCell<?> cell, String text, String background) {
        if (!javafx.scene.paint.Color.web(text).equals(cell.getTextFill())
                || cell.getBackground().getFills().stream()
                        .noneMatch(
                                fill ->
                                        javafx.scene.paint.Color.web(background)
                                                .equals(fill.getFill())))
            throw new AssertionError(
                    "Unreadable dropdown state: text="
                            + cell.getTextFill()
                            + ", background="
                            + cell.getBackground());
    }

    private static void staleReplies() throws Exception {
        var first = new CompletableFuture<ResearchPresentation.Page>();
        var second = new CompletableFuture<ResearchPresentation.Page>();
        var firstToken = new AtomicReference<Cancellation>();
        var browser = new AtomicReference<ResearchBrowser>();
        ResearchDataSource source =
                new ResearchDataSource() {
                    int calls;

                    public CompletableFuture<ResearchPresentation.Page> page(
                            String a, String q, String c, Cancellation token) {
                        if (calls++ == 0) {
                            firstToken.set(token);
                            return first;
                        }
                        return second;
                    }

                    public CompletableFuture<?> detail(
                            String a, ResearchPresentation.Row r, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<?> media(
                            String a, ResearchPresentation.Row r, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<ResearchSource.TableRows> tableRows(
                            long id, Long after, String identity, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<BlpImageTypes.Result> icon(long id, Cancellation t) {
                        return new CompletableFuture<>();
                    }
                };
        fx(
                () -> {
                    var view = new ResearchBrowser("Files", source, name -> {});
                    browser.set(view);
                    new Scene(view, 1320, 850);
                    view.applyCss();
                    view.ensureLoaded();
                    var search = (TextField) view.lookup("#research-search");
                    search.setText("new");
                    search.fireEvent(new javafx.event.ActionEvent());
                });
        try {
            firstToken.get().check();
            throw new AssertionError("Superseded query was not canceled");
        } catch (java.util.concurrent.CancellationException expected) {
            /* expected */
        }
        var row = new ResearchPresentation.Row(2, "New", List.of("2", "New"), null);
        second.complete(new ResearchPresentation.Page(List.of(row), null, "New result"));
        flush();
        first.complete(
                new ResearchPresentation.Page(
                        List.of(new ResearchPresentation.Row(1, "Old", List.of("1", "Old"), null)),
                        null,
                        "Stale"));
        flush();
        fx(
                () -> {
                    if (grid(browser.get()).getItems().size() != 1
                            || !grid(browser.get()).getItems().getFirst().equals(row))
                        throw new AssertionError("Stale result replaced current page");
                    browser.get().close();
                });
        var pending = new CompletableFuture<ResearchPresentation.Page>();
        var closed = new AtomicReference<ResearchBrowser>();
        var closedToken = new AtomicReference<Cancellation>();
        ResearchDataSource delayed =
                new ResearchDataSource() {
                    public CompletableFuture<ResearchPresentation.Page> page(
                            String a, String q, String c, Cancellation t) {
                        closedToken.set(t);
                        return pending;
                    }

                    public CompletableFuture<?> detail(
                            String a, ResearchPresentation.Row r, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<?> media(
                            String a, ResearchPresentation.Row r, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<ResearchSource.TableRows> tableRows(
                            long id, Long after, String identity, Cancellation t) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<BlpImageTypes.Result> icon(long id, Cancellation t) {
                        return new CompletableFuture<>();
                    }
                };
        fx(
                () -> {
                    var view = new ResearchBrowser("Files", delayed, name -> {});
                    closed.set(view);
                    new Scene(view, 1320, 850);
                    view.applyCss();
                    view.ensureLoaded();
                    view.close();
                });
        try {
            closedToken.get().check();
            throw new AssertionError("Close did not cancel pending page");
        } catch (java.util.concurrent.CancellationException expected) {
            /* expected */
        }
        pending.complete(new ResearchPresentation.Page(List.of(row), null, "Late result"));
        flush();
        fx(
                () -> {
                    if (!grid(closed.get()).getItems().isEmpty())
                        throw new AssertionError("Closed browser accepted late result");
                });
    }
}
