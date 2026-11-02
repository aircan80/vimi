package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.*;
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
            return "enUS";
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
                        null,
                        null,
                        ItemRowState.FOUND,
                        null);
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

        public CompletableFuture<ResearchPresentation.Page> page(
                String area, String query, String after, Cancellation token) {
            var row =
                    switch (area) {
                        case "Files" -> ResearchPresentation.file(file);
                        case "Tables" -> ResearchPresentation.table(table);
                        case "Items" -> ResearchPresentation.item(item);
                        default -> ResearchPresentation.creature(creature);
                    };
            return CompletableFuture.completedFuture(
                    new ResearchPresentation.Page(
                            List.of(row),
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
            return CompletableFuture.failedFuture(new IllegalStateException("No fixture art"));
        }
    }

    private static TableView<?> grid(ResearchBrowser browser) {
        return (TableView<?>) browser.lookup("#research-results");
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
        Platform.startup(() -> start.complete(null));
        start.get(15, TimeUnit.SECONDS);
        try {
            Path output = Path.of(System.getProperty("vimi.uiReviewDirectory", "build/ui-review"));
            Files.createDirectories(output);
            for (String area : List.of("Files", "Tables", "Items", "Creatures")) {
                var browser = new AtomicReference<ResearchBrowser>();
                var scene = new AtomicReference<Scene>();
                fx(
                        () -> {
                            var view = new ResearchBrowser(area, new Fixture(), name -> {});
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
                fx(() -> browser.get().close());
            }
            staleReplies();
            System.out.println(
                    "Passed rendered Files/Tables/Items/Creatures, attribute tooltip, table row navigation and stale/closed request retirement");
        } finally {
            Platform.exit();
        }
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
