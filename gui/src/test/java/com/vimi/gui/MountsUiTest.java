package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.concepts.mounts.BuildMounts;
import com.vibeminer.concepts.mounts.MountTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

/** Authored-only UI proof: readable partial records, lazy variants, search and stale selection. */
public final class MountsUiTest {
    private MountsUiTest() {}

    private static final CompletableFuture<Void> loadingChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> retirementChecks = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        loadingFeedback();
                        retirementFeedback();
                        var runner =
                                new MountTypes.Mount(
                                        6,
                                        "Authored Plains Runner",
                                        "An authored description for reviewing the mount layout.",
                                        "Recorded source text appears here. No player collection"
                                                + " state is inferred.",
                                        458L,
                                        0L,
                                        10L,
                                        0L,
                                        0L);
                        var hidden =
                                new MountTypes.Mount(
                                        7,
                                        "Delayed appearance",
                                        null,
                                        null,
                                        459L,
                                        null,
                                        null,
                                        null,
                                        null);
                        var pending = new CompletableFuture<MountTypes.Overview>();
                        var calls = new AtomicInteger();
                        var closes = new AtomicInteger();
                        var catalogs = new AtomicInteger();
                        var revision = new AtomicLong(1);
                        BuildMounts handle =
                                new BuildMounts() {
                                    public CompletableFuture<MountTypes.Catalog> catalog(
                                            Cancellation token) {
                                        catalogs.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new MountTypes.Catalog(
                                                        MountTypes.State.PARTIAL,
                                                        List.of(runner, hidden),
                                                        List.of(
                                                                "Authored protected section;"
                                                                        + " readable mounts remain"
                                                                        + " available."),
                                                        null));
                                    }

                                    public CompletableFuture<MountTypes.Overview> overview(
                                            long id, Cancellation token) {
                                        if (id == 7) {
                                            return pending;
                                        }
                                        return CompletableFuture.completedFuture(
                                                new MountTypes.Overview(
                                                        MountTypes.State.PARTIAL,
                                                        runner,
                                                        List.of(
                                                                new MountTypes.Variant(
                                                                        1, 20, 0L, "MountXDisplay"),
                                                                new MountTypes.Variant(
                                                                        2,
                                                                        21,
                                                                        99L,
                                                                        "MountXDisplay")),
                                                        List.of("Authored partial links"),
                                                        null));
                                    }

                                    public CompletableFuture<DisplayModelTypes.Preview> preview(
                                            long id, long variant, Cancellation token) {
                                        calls.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new DisplayModelTypes.Preview(
                                                        DisplayModelTypes.State.PARTIAL,
                                                        new DisplayModelTypes.Reference(
                                                                variant == 1 ? 20 : 21,
                                                                30,
                                                                40,
                                                                List.of(50L),
                                                                0L,
                                                                0L),
                                                        appearance(variant == 2),
                                                        Set.of(0),
                                                        List.of(
                                                                "Authored geometry and diffuse"
                                                                    + " texture; no game assets."),
                                                        true,
                                                        null));
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        closes.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        var view =
                                new MountsView(
                                        () -> handle, revision::get, "Authored build · enUS");
                        var root = new BorderPane(view);
                        root.setTop(
                                new Label(
                                        "AUTHORED MOUNTS UI FIXTURE · no game assets or real build"
                                                + " data"));
                        var scene = new Scene(root, 1320, 850);
                        scene.getStylesheets()
                                .add(MountsUiTest.class.getResource("/vimi.css").toExternalForm());
                        var stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        await(
                                () ->
                                        labels(view).stream()
                                                .anyMatch(
                                                        t -> t.startsWith("Static model preview")),
                                () -> {
                                    require(
                                            calls.get() == 1,
                                            "Only the selected first variant loads");
                                    require(
                                            labels(view).contains("Authored Plains Runner"),
                                            "Readable mount shown");
                                    require(
                                            labels(view).stream()
                                                    .anyMatch(t -> t.contains("partial")),
                                            "Partial state remains visible");
                                    @SuppressWarnings("unchecked")
                                    var list =
                                            (ListView<MountTypes.Mount>)
                                                    view.lookupAll(".list-view").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Build mount records"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    var query =
                                            (TextField)
                                                    view.lookupAll(".text-field").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Search mount records"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    query.setText("458");
                                    require(list.getItems().size() == 1, "Source spell search");
                                    require(
                                            calls.get() == 1,
                                            "Filtering does not load more models");
                                    query.clear();
                                    @SuppressWarnings("unchecked")
                                    var variant =
                                            (ComboBox<MountTypes.Variant>)
                                                    view.lookupAll(".combo-box").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Recorded mount display variants"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    variant.getSelectionModel().select(1);
                                    await(
                                            () ->
                                                    calls.get() == 2
                                                            && labels(view).stream()
                                                                    .anyMatch(
                                                                            t ->
                                                                                    t.startsWith(
                                                                                            "Partial material preview"))
                                                            && hasImage(view),
                                            () -> {
                                                require(
                                                        calls.get() == 2,
                                                        "Explicit variant selection loads only"
                                                                + " chosen variant");
                                                require(labels(view).stream().anyMatch(t ->
                                                        t.contains("1/2 supported sections")
                                                            && t.contains("All recorded surfaces")),
                                                        "Partial material coverage explains omitted passes and raw diagnostics");
                                                try {
                                                    stage.setWidth(1150);
                                                    stage.setHeight(760);
                                                    root.applyCss();
                                                    root.layout();
                                                    require(
                                                            view.getBoundsInParent().getWidth()
                                                                    > 900,
                                                            "Resize retains content");
                                                    stage.setWidth(1320);
                                                    stage.setHeight(850);
                                                    root.applyCss();
                                                    root.layout();
                                                    snapshot(scene);
                                                } catch (Exception failure) {
                                                    done.completeExceptionally(failure);
                                                    stage.close();
                                                    return;
                                                }
                                                list.getSelectionModel().select(hidden);
                                                view.close();
                                                pending.complete(
                                                        new MountTypes.Overview(
                                                                MountTypes.State.READY,
                                                                hidden,
                                                                List.of(),
                                                                List.of(),
                                                                "STALE SHOULD NOT APPEAR"));
                                                Platform.runLater(
                                                        () -> {
                                                            try {
                                                                require(
                                                                        labels(view).stream()
                                                                                .noneMatch(
                                                                                        t ->
                                                                                                t
                                                                                                        .contains(
                                                                                                                "STALE"
                                                                                                                    + " SHOULD"
                                                                                                                    + " NOT APPEAR")),
                                                                        "Stale selection suppressed"
                                                                                + " after close");
                                                                require(
                                                                        closes.get() == 0,
                                                                        "View never closes borrowed"
                                                                                + " handle");
                                                                require(
                                                                        catalogs.get() == 1,
                                                                        "Catalogue is reused until"
                                                                                + " invalidated");
                                                                stage.close();
                                                                done.complete(null);
                                                            } catch (Throwable failure) {
                                                                done.completeExceptionally(failure);
                                                                stage.close();
                                                            }
                                                        });
                                            },
                                            done,
                                            0);
                                },
                                done,
                                0);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        try {
            done.get(20, TimeUnit.SECONDS);
            loadingChecks.get(5, TimeUnit.SECONDS);
            retirementChecks.get(5, TimeUnit.SECONDS);
            System.out.println(
                    "Mounts UI: partial records, lazy variants, source-spell search, resize, stale"
                            + " selection and borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static MeshAppearance appearance() { return appearance(false); }

    private static MeshAppearance appearance(boolean partial) {
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 1, -1, -1, -1, 1, -1, -1, 0, 1, -1},
                        new int[] {0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2},
                        partial ? List.of(new PreviewMesh.Part(0, 0, 6), new PreviewMesh.Part(0, 6, 6))
                                : List.of(new PreviewMesh.Part(0, 0, 12)));
        return new MeshAppearance(
                mesh,
                new float[] {.5f, 0, 0, 1, 1, 1, .5f, .5f},
                List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                List.of(
                        new MeshAppearance.Texture(
                                2, 2, new int[] {0xffd8ac66, 0xff43968a, 0xff43968a, 0xffd8ac66})));
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            Runnable action,
            CompletableFuture<Void> done,
            int attempts) {
        if (done.isDone()) {
            return;
        }
        try {
            if (condition.getAsBoolean()) {
                action.run();
                return;
            }
            if (attempts > 100) {
                throw new AssertionError("UI readiness timed out");
            }
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
            pause.setOnFinished(e -> await(condition, action, done, attempts + 1));
            pause.play();
        } catch (Throwable failure) {
            done.completeExceptionally(failure);
        }
    }

    private static List<String> labels(javafx.scene.Parent parent) {
        return parent.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static boolean hasImage(javafx.scene.Node node) {
        if (node instanceof javafx.scene.image.ImageView image && image.getImage() != null) {
            var source = image.getImage();
            var reader = source.getPixelReader();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int pixel =
                            reader.getArgb(
                                    (int) (source.getWidth() * (x + .5) / 16),
                                    (int) (source.getHeight() * (y + .5) / 16));
                    int r = pixel >>> 16 & 255, g = pixel >>> 8 & 255, b = pixel & 255;
                    if (r != g - 8 || b != g + 2) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (var child : parent.getChildrenUnmodifiable()) {
                if (hasImage(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void snapshot(Scene scene) throws Exception {
        var snapshot = scene.snapshot(null);
        int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
        int[] pixels = new int[width * height];
        snapshot.getPixelReader()
                .getPixels(
                        0,
                        0,
                        width,
                        height,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        pixels,
                        0,
                        width);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        var directory = Path.of(System.getProperty("vimi.uiReviewDirectory", "build/ui-review"));
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve("mounts-authored.png").toFile());
    }

    private static long activityCount(javafx.scene.Node node) {
        if (!node.isVisible()) return 0;
        long count = node instanceof javafx.scene.control.ProgressIndicator ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) count += activityCount(child);
        return count;
    }

    private static void retirementFeedback() {
        var pages = new java.util.ArrayDeque<CompletableFuture<MountTypes.Catalog>>();
        var first = new CompletableFuture<MountTypes.Catalog>();
        var superseded = new CompletableFuture<MountTypes.Catalog>();
        var latest = new CompletableFuture<MountTypes.Catalog>();
        pages.add(first);
        pages.add(superseded);
        pages.add(latest);
        var detail = new CompletableFuture<MountTypes.Overview>();
        var newerDetail = new CompletableFuture<MountTypes.Overview>();
        var details = new java.util.ArrayDeque<CompletableFuture<MountTypes.Overview>>();
        details.add(detail);
        details.add(newerDetail);
        var other = new MountTypes.Mount(2, "New selection", "", "", null, null, null, null, null);
        var record =
                new MountTypes.Mount(1, "Retirement fixture", "", "", null, null, null, null, null);
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        BuildMounts handle =
                new BuildMounts() {
                    public CompletableFuture<MountTypes.Catalog> catalog(Cancellation token) {
                        return pages.remove();
                    }

                    public CompletableFuture<MountTypes.Overview> overview(
                            long id, Cancellation token) {
                        return details.remove();
                    }

                    public CompletableFuture<
                                    com.vibeminer.concepts.models.DisplayModelTypes.Preview>
                            preview(long id, long variant, Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var handles = new java.util.concurrent.atomic.AtomicReference<BuildMounts>(handle);
        var view = new MountsView(handles::get, revision::get, "Authored retirement fixture");
        new Scene(view, 1200, 850);
        view.applyCss();
        view.layout();
        view.ensureLoaded();
        view.applyCss();
        view.layout();
        revision.incrementAndGet();
        first.complete(new MountTypes.Catalog(MountTypes.State.READY, List.of(), List.of(), null));
        Platform.runLater(
                () -> {
                    try {
                        require(
                                activityCount(view) == 0,
                                "External revision retires current catalog activity");
                        require(
                                view.lookupAll(".list-view").stream()
                                        .anyMatch(javafx.scene.Node::isDisabled),
                                "Retired catalog actions disabled");
                        view.ensureLoaded();
                        view.invalidate();
                        view.ensureLoaded();
                        superseded.complete(
                                new MountTypes.Catalog(
                                        MountTypes.State.READY, List.of(), List.of(), null));
                        Platform.runLater(
                                () -> {
                                    try {
                                        require(
                                                activityCount(view) > 0,
                                                "Old catalog cannot clear newer pending activity");
                                        latest.complete(
                                                new MountTypes.Catalog(
                                                        MountTypes.State.READY,
                                                        List.of(record, other),
                                                        List.of(),
                                                        null));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                activityCount(view) > 0,
                                                                "Current detail remains pending");
                                                        var list =
                                                                (javafx.scene.control.ListView<?>)
                                                                        view
                                                                                .lookupAll(
                                                                                        ".list-view")
                                                                                .stream()
                                                                                .filter(
                                                                                        node ->
                                                                                                ((javafx
                                                                                                                                        .scene
                                                                                                                                        .control
                                                                                                                                        .ListView<
                                                                                                                                ?>)
                                                                                                                        node)
                                                                                                                .getItems()
                                                                                                                .size()
                                                                                                        == 2)
                                                                                .findFirst()
                                                                                .orElseThrow();
                                                        list.getSelectionModel().select(1);
                                                        handles.set(null);
                                                        detail.completeExceptionally(
                                                                new IllegalStateException(
                                                                        "Authored retired detail"));
                                                        Platform.runLater(
                                                                () -> {
                                                                    try {
                                                                        require(
                                                                                activityCount(view)
                                                                                        > 0,
                                                                                "Older selection"
                                                                                    + " cannot"
                                                                                    + " retire"
                                                                                    + " newer"
                                                                                    + " pending"
                                                                                    + " detail");
                                                                        newerDetail
                                                                                .completeExceptionally(
                                                                                        new IllegalStateException(
                                                                                                "Authored"
                                                                                                    + " current"
                                                                                                    + " retired"
                                                                                                    + " detail"));
                                                                        Platform.runLater(
                                                                                () -> {
                                                                                    try {
                                                                                        require(
                                                                                                activityCount(
                                                                                                                view)
                                                                                                        == 0,
                                                                                                "External"
                                                                                                    + " handle"
                                                                                                    + " retirement"
                                                                                                    + " clears"
                                                                                                    + " detail"
                                                                                                    + " activity");
                                                                                        require(
                                                                                                labels(
                                                                                                                view)
                                                                                                        .stream()
                                                                                                        .anyMatch(
                                                                                                                text ->
                                                                                                                        text
                                                                                                                                .contains(
                                                                                                                                        "Build"
                                                                                                                                            + " changed")),
                                                                                                "Retirement"
                                                                                                    + " is explicit");
                                                                                        view
                                                                                                .close();
                                                                                        retirementChecks
                                                                                                .complete(
                                                                                                        null);
                                                                                    } catch (
                                                                                            Throwable
                                                                                                    failure) {
                                                                                        view
                                                                                                .close();
                                                                                        retirementChecks
                                                                                                .completeExceptionally(
                                                                                                        failure);
                                                                                    }
                                                                                });
                                                                    } catch (Throwable failure) {
                                                                        view.close();
                                                                        retirementChecks
                                                                                .completeExceptionally(
                                                                                        failure);
                                                                    }
                                                                });
                                                    } catch (Throwable failure) {
                                                        view.close();
                                                        retirementChecks.completeExceptionally(
                                                                failure);
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        view.close();
                                        retirementChecks.completeExceptionally(failure);
                                    }
                                });
                    } catch (Throwable failure) {
                        view.close();
                        retirementChecks.completeExceptionally(failure);
                    }
                });
    }

    private static void loadingFeedback() {
        var catalog = new CompletableFuture<MountTypes.Catalog>();
        var overview = new CompletableFuture<MountTypes.Overview>();
        var preview = new CompletableFuture<DisplayModelTypes.Preview>();
        var mount =
                new MountTypes.Mount(
                        1, "Pending authored mount", "", "", null, null, null, null, null);
        BuildMounts handle =
                new BuildMounts() {
                    public CompletableFuture<MountTypes.Catalog> catalog(Cancellation token) {
                        return catalog;
                    }

                    public CompletableFuture<MountTypes.Overview> overview(
                            long id, Cancellation token) {
                        return overview;
                    }

                    public CompletableFuture<DisplayModelTypes.Preview> preview(
                            long id, long variant, Cancellation token) {
                        return preview;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var view = new MountsView(() -> handle, () -> 1, "Authored pending fixture");
        new Scene(view, 1200, 850);
        view.applyCss();
        view.layout();
        view.ensureLoaded();
        view.applyCss();
        view.layout();
        require(activityCount(view) > 0, "Pending mount catalog has animated local activity");
        catalog.complete(
                new MountTypes.Catalog(MountTypes.State.READY, List.of(mount), List.of(), null));
        Platform.runLater(
                () -> {
                    try {
                        require(
                                activityCount(view) > 0,
                                "Pending mount details keep activity visible");
                        overview.complete(
                                new MountTypes.Overview(
                                        MountTypes.State.READY,
                                        mount,
                                        List.of(new MountTypes.Variant(1, 1, null, "Authored")),
                                        List.of(),
                                        null));
                        Platform.runLater(
                                () -> {
                                    try {
                                        require(
                                                activityCount(view) > 0,
                                                "Pending mount model acquisition shows central"
                                                        + " activity");
                                        preview.completeExceptionally(
                                                new IllegalStateException(
                                                        "Authored asset failure"));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                activityCount(view) == 0,
                                                                "Failed mount acquisition retires"
                                                                        + " activity");
                                                        view.close();
                                                        require(
                                                                activityCount(view) == 0,
                                                                "Closed mount view has no"
                                                                        + " activity");
                                                        loadingChecks.complete(null);
                                                    } catch (Throwable error) {
                                                        view.close();
                                                        loadingChecks.completeExceptionally(error);
                                                    }
                                                });
                                    } catch (Throwable error) {
                                        view.close();
                                        loadingChecks.completeExceptionally(error);
                                    }
                                });
                    } catch (Throwable error) {
                        view.close();
                        loadingChecks.completeExceptionally(error);
                    }
                });
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
