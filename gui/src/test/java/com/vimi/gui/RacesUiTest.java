package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * Authored data only: layout, stale selection, readable partial cards and borrowed handle lifetime.
 */
public final class RacesUiTest {
    private RacesUiTest() {}

    private static final CompletableFuture<Void> loadingChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> voiceLoadingChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> voiceRetirementChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> raceRetirementChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> modelRetirementChecks = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        loadingFeedback();
                        voiceLoadingFeedback();
                        voiceRetirementFeedback();
                        raceRetirementFeedback();
                        modelRetirementFeedback();
                        var human = new RaceTypes.Race(1, "Authored Human", 1, 2);
                        var internal = new RaceTypes.Race(99, "Internal record", 99, 0);
                        var pending = new CompletableFuture<RaceTypes.Overview>();
                        var closes = new java.util.concurrent.atomic.AtomicInteger();
                        var authoredView =
                                new java.util.concurrent.atomic.AtomicReference<RacesView>();
                        BuildRaces handle =
                                new BuildRaces() {
                                    public CompletableFuture<RaceTypes.Catalog> catalog(
                                            Cancellation token) {
                                        return CompletableFuture.completedFuture(
                                                new RaceTypes.Catalog(
                                                        RaceTypes.State.PARTIAL,
                                                        List.of(human, internal),
                                                        List.of("Authored partial fixture"),
                                                        null));
                                    }

                                    public CompletableFuture<RaceTypes.Overview> overview(
                                            long id, Cancellation token) {
                                        if (id == 99) return pending;
                                        return CompletableFuture.completedFuture(
                                                new RaceTypes.Overview(
                                                        RaceTypes.State.PARTIAL,
                                                        human,
                                                        "An authored lore paragraph used to review"
                                                                + " layout. This screen does not"
                                                                + " contain real game build data.",
                                                        10L,
                                                        "Authored faction",
                                                        List.of(
                                                                new RaceTypes.PlayerClass(
                                                                        1, "Warrior"),
                                                                new RaceTypes.PlayerClass(
                                                                        2, "Mage")),
                                                        List.of(
                                                                new RaceTypes.Ability(
                                                                        101,
                                                                        "Authored Diplomacy",
                                                                        "Recorded racial"
                                                                            + " description remains"
                                                                            + " usable when another"
                                                                            + " section is"
                                                                            + " protected.",
                                                                        900L,
                                                                        0),
                                                                new RaceTypes.Ability(
                                                                        102,
                                                                        "Authored racial",
                                                                        "A second recorded"
                                                                                + " description.",
                                                                        null,
                                                                        1)),
                                                        List.of("Some source rows are protected."),
                                                        null));
                                    }

                                    public CompletableFuture<
                                                    com.vibeminer.concepts.races.RaceMediaTypes
                                                            .Variants>
                                            modelVariants(long raceId, Cancellation token) {
                                        return CompletableFuture.completedFuture(
                                                new com.vibeminer.concepts.races.RaceMediaTypes
                                                        .Variants(
                                                        RaceTypes.State.READY,
                                                        List.of(
                                                                new com.vibeminer.concepts.races
                                                                        .RaceMediaTypes.Variant(
                                                                        1,
                                                                        0,
                                                                        1,
                                                                        1,
                                                                        1,
                                                                        authoredDefinition())),
                                                        List.of(),
                                                        null));
                                    }

                                    public CompletableFuture<
                                                    com.vibeminer.concepts.races.RaceMediaTypes
                                                            .Preview>
                                            preview(
                                                    long raceId,
                                                    long classId,
                                                    long modelId,
                                                    Cancellation token) {
                                        // An authored tetrahedron exercises the renderer without
                                        // shipping game assets.
                                        var mesh =
                                                new com.vibeminer.rendering.models.PreviewMesh(
                                                        new float[] {
                                                            0, 0, 1, -1, -1, -1, 1, -1, -1, 0, 1, -1
                                                        },
                                                        new int[] {
                                                            0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2
                                                        },
                                                        List.of(
                                                                new com.vibeminer.rendering.models
                                                                        .PreviewMesh.Part(
                                                                        0, 0, 12)));
                                        return CompletableFuture.completedFuture(
                                                new com.vibeminer.concepts.races.RaceMediaTypes
                                                        .Preview(
                                                        RaceTypes.State.PARTIAL,
                                                        new com.vibeminer.concepts.races
                                                                .RaceMediaTypes.Variant(
                                                                1,
                                                                0,
                                                                1,
                                                                1,
                                                                1,
                                                                authoredDefinition()),
                                                        mesh,
                                                        java.util.Set.of(0),
                                                        List.of(
                                                                "Authored renderer fixture; no game"
                                                                        + " assets."),
                                                        false,
                                                        null));
                                    }

                                    public CompletableFuture<
                                                    com.vibeminer.concepts.races.RaceVoiceTypes
                                                            .Catalog>
                                            voices(
                                                    long raceId,
                                                    long classId,
                                                    int sex,
                                                    Cancellation token) {
                                        return CompletableFuture.completedFuture(
                                                new com.vibeminer.concepts.races.RaceVoiceTypes
                                                        .Catalog(
                                                        RaceTypes.State.PARTIAL,
                                                        "enUS",
                                                        List.of(
                                                                new com.vibeminer.concepts.races
                                                                        .RaceVoiceTypes.Clip(
                                                                        101,
                                                                        329,
                                                                        "Authored joke",
                                                                        100,
                                                                        200,
                                                                        null)),
                                                        List.of(),
                                                        List.of(
                                                                "Authored voice metadata; no"
                                                                        + " recording."),
                                                        null));
                                    }

                                    public CompletableFuture<
                                                    com.vibeminer.concepts.races.RaceAppearanceTypes
                                                            .Result>
                                            appearance(
                                                    long raceId,
                                                    long classId,
                                                    long modelId,
                                                    Cancellation token) {
                                        var mesh =
                                                new com.vibeminer.rendering.models.PreviewMesh(
                                                        new float[] {
                                                            0, 0, 1, -1, -1, -1, 1, -1, -1, 0, 1, -1
                                                        },
                                                        new int[] {
                                                            0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2
                                                        },
                                                        List.of(
                                                                new com.vibeminer.rendering.models
                                                                        .PreviewMesh.Part(
                                                                        0, 0, 12)));
                                        var surface =
                                                new com.vibeminer.rendering.models.MeshAppearance(
                                                        mesh,
                                                        new float[] {
                                                            0.5f, 0, 0, 1, 1, 1, 0.5f, 0.5f
                                                        },
                                                        List.of(
                                                                new com.vibeminer.rendering.models
                                                                        .MeshAppearance.Surface(
                                                                        0, 0, false, false, false)),
                                                        List.of(
                                                                new com.vibeminer.rendering.models
                                                                        .MeshAppearance.Texture(
                                                                        2,
                                                                        2,
                                                                        new int[] {
                                                                            0xffd8ac66,
                                                                            0xff43968a,
                                                                            0xff43968a,
                                                                            0xffd8ac66
                                                                        })));
                                        var appearance =
                                                new com.vibeminer.concepts.races.RaceAppearanceTypes
                                                        .Result(
                                                        RaceTypes.State.PARTIAL,
                                                        surface,
                                                        java.util.Set.of(0),
                                                        List.of(
                                                                new com.vibeminer.concepts.races
                                                                        .RaceAppearanceTypes.Item(
                                                                        1001, 4, 2001)),
                                                        List.of(
                                                                "Authored diffuse fixture; no game"
                                                                        + " data."),
                                                        true,
                                                        null);
                                        var future =
                                                new CompletableFuture<
                                                        com.vibeminer.concepts.races
                                                                .RaceAppearanceTypes.Result>();
                                        var delay =
                                                new javafx.animation.PauseTransition(
                                                        javafx.util.Duration.millis(100));
                                        delay.setOnFinished(
                                                event -> {
                                                    try {
                                                        require(
                                                                labels(authoredView.get()).stream()
                                                                        .anyMatch(
                                                                                value ->
                                                                                        value
                                                                                                .contains(
                                                                                                        "preparing"
                                                                                                            + " textures"
                                                                                                            + " and starting"
                                                                                                            + " clothing")),
                                                                "Pending appearance does not claim"
                                                                        + " a completed textured"
                                                                        + " sample");
                                                        require(
                                                                authoredView
                                                                                        .get()
                                                                                        .lookup(
                                                                                                "#model-appearance-activity")
                                                                                != null
                                                                        && authoredView
                                                                                .get()
                                                                                .lookup(
                                                                                        "#model-appearance-activity")
                                                                                .isVisible(),
                                                                "Pending appearance shows"
                                                                    + " accessible local activity");
                                                        var pendingImage =
                                                                authoredView
                                                                        .get()
                                                                        .getScene()
                                                                        .snapshot(null);
                                                        int width = (int) pendingImage.getWidth(),
                                                                height =
                                                                        (int)
                                                                                pendingImage
                                                                                        .getHeight();
                                                        int[] pixels = new int[width * height];
                                                        pendingImage
                                                                .getPixelReader()
                                                                .getPixels(
                                                                        0,
                                                                        0,
                                                                        width,
                                                                        height,
                                                                        javafx.scene.image
                                                                                .PixelFormat
                                                                                .getIntArgbInstance(),
                                                                        pixels,
                                                                        0,
                                                                        width);
                                                        var pendingPng =
                                                                new BufferedImage(
                                                                        width,
                                                                        height,
                                                                        BufferedImage
                                                                                .TYPE_INT_ARGB);
                                                        pendingPng.setRGB(
                                                                0, 0, width, height, pixels, 0,
                                                                width);
                                                        Path directory =
                                                                Path.of(
                                                                        System.getProperty(
                                                                                "vimi.uiReviewDirectory",
                                                                                "gui/build/ui-review"));
                                                        Files.createDirectories(directory);
                                                        ImageIO.write(
                                                                pendingPng,
                                                                "png",
                                                                directory
                                                                        .resolve(
                                                                                "races-appearance-pending.png")
                                                                        .toFile());
                                                        future.complete(appearance);
                                                    } catch (Throwable error) {
                                                        future.completeExceptionally(error);
                                                        done.completeExceptionally(error);
                                                    }
                                                });
                                        delay.play();
                                        return future;
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        closes.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        var decoded =
                                new com.vibeminer.formats.images.Blp2Types.Image(
                                        1,
                                        1,
                                        4,
                                        new byte[] {0x12, (byte) 0xaa, (byte) 0xbb, (byte) 0xff},
                                        null,
                                        8,
                                        0,
                                        0,
                                        1,
                                        1,
                                        List.of(),
                                        0);
                        var image =
                                new com.vibeminer.formats.images.BlpImageTypes.Result(
                                        com.vibeminer.formats.images.BlpImageTypes.State.READY,
                                        decoded,
                                        com.vibeminer.formats.images.BlpImageTypes.Cache.NONE,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null);
                        var revision = new java.util.concurrent.atomic.AtomicLong(1);
                        var view =
                                new RacesView(
                                        () -> handle,
                                        revision::get,
                                        (id, token) -> CompletableFuture.completedFuture(image));
                        authoredView.set(view);
                        BorderPane root = new BorderPane(view);
                        root.setTop(
                                new Label(
                                        "AUTHORED RACES LAYOUT FIXTURE · no character or real build"
                                                + " data"));
                        double width =
                                Double.parseDouble(
                                        System.getProperty("vimi.racesReviewWidth", "1320"));
                        Scene scene = new Scene(root, width, 900);
                        scene.getStylesheets()
                                .add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        Stage stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        var pause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(25));
                        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        pause.setOnFinished(
                                event -> {
                                    boolean ready =
                                            labels(view).stream()
                                                            .anyMatch(
                                                                    value ->
                                                                            value.startsWith(
                                                                                    "Static"
                                                                                        + " textured"
                                                                                        + " sample"))
                                                    && labels(view).stream()
                                                            .anyMatch(
                                                                    value ->
                                                                            value.startsWith(
                                                                                    "Drag to"
                                                                                        + " rotate"));
                                    if (!ready && System.nanoTime() < readyDeadline) {
                                        pause.playFromStart();
                                        return;
                                    }
                                    try {
                                        var list = (ListView<?>) view.lookup(".list-view");
                                        require(
                                                list.getItems().size() == 1,
                                                "Default sidebar only shows recorded class links");
                                        require(
                                                labels(view).contains("Authored Diplomacy"),
                                                "Readable partial racial card displayed");
                                        require(
                                                labels(view)
                                                                .contains(
                                                                        "Model definition: HD ·"
                                                                                + " ChrRaces #1"
                                                                                + " HighResMaleDisplayID")
                                                        && view.lookup("#race-model-variant")
                                                                        instanceof
                                                                        javafx.scene.control
                                                                                        .ComboBox<?>
                                                                                combo
                                                        && combo.getValue()
                                                                instanceof
                                                                com.vibeminer.concepts.races
                                                                                .RaceMediaTypes
                                                                                .Variant
                                                                        selectedModel
                                                        && selectedModel.label().equals("Male · HD"),
                                                "Source-stated definition and its field are"
                                                        + " shown: "
                                                        + labels(view));
                                        require(
                                                !view.lookupAll(".image-view").isEmpty()
                                                        || descendantsImage(view),
                                                "Racial image rendered");
                                        require(
                                                labels(view).stream()
                                                        .anyMatch(
                                                                value ->
                                                                        value.startsWith(
                                                                                "Partial static"
                                                                                    + " textured"
                                                                                    + " sample")),
                                                "Async textured sample reaches the view: "
                                                        + labels(view));
                                        var imageSnapshot = scene.snapshot(null);
                                        var out =
                                                new BufferedImage(
                                                        (int) imageSnapshot.getWidth(),
                                                        (int) imageSnapshot.getHeight(),
                                                        BufferedImage.TYPE_INT_ARGB);
                                        for (int y = 0; y < out.getHeight(); y++)
                                            for (int x = 0; x < out.getWidth(); x++)
                                                out.setRGB(
                                                        x,
                                                        y,
                                                        imageSnapshot
                                                                .getPixelReader()
                                                                .getArgb(x, y));
                                        Path directory =
                                                Path.of(
                                                        System.getProperty(
                                                                "vimi.uiReviewDirectory",
                                                                "gui/build/ui-review"));
                                        Files.createDirectories(directory);
                                        ImageIO.write(
                                                out,
                                                "png",
                                                directory.resolve("races-authored.png").toFile());
                                        var check = (CheckBox) view.lookup(".check-box");
                                        check.fire();
                                        require(
                                                list.getItems().size() == 2,
                                                "Other internal records remain inspectable");
                                        list.getSelectionModel().select(1);
                                        view.invalidate();
                                        pending.complete(
                                                new RaceTypes.Overview(
                                                        RaceTypes.State.READY,
                                                        internal,
                                                        "STALE OVERVIEW",
                                                        null,
                                                        null,
                                                        List.of(),
                                                        List.of(),
                                                        List.of(),
                                                        null));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                !labels(view)
                                                                        .contains("STALE OVERVIEW"),
                                                                "Stale overview cannot repopulate"
                                                                        + " invalidated view");
                                                        view.close();
                                                        require(
                                                                closes.get() == 0,
                                                                "GUI borrows domain handle");
                                                        stage.close();
                                                        done.complete(null);
                                                    } catch (Throwable failure) {
                                                        done.completeExceptionally(failure);
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        done.completeExceptionally(failure);
                                    }
                                });
                        pause.play();
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        try {
            done.get(20, TimeUnit.SECONDS);
            loadingChecks.get(5, TimeUnit.SECONDS);
            voiceLoadingChecks.get(5, TimeUnit.SECONDS);
            voiceRetirementChecks.get(5, TimeUnit.SECONDS);
            raceRetirementChecks.get(5, TimeUnit.SECONDS);
            modelRetirementChecks.get(5, TimeUnit.SECONDS);
            System.out.println(
                    "Races UI: partial data, icons, internal-record filter, stale selection and"
                            + " borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static void raceRetirementFeedback() {
        var first = new CompletableFuture<RaceTypes.Catalog>();
        var superseded = new CompletableFuture<RaceTypes.Catalog>();
        var latest = new CompletableFuture<RaceTypes.Catalog>();
        var catalogs = new java.util.ArrayDeque<CompletableFuture<RaceTypes.Catalog>>();
        catalogs.add(first);
        catalogs.add(superseded);
        catalogs.add(latest);
        var firstDetail = new CompletableFuture<RaceTypes.Overview>();
        var latestDetail = new CompletableFuture<RaceTypes.Overview>();
        var overviews = new java.util.ArrayDeque<CompletableFuture<RaceTypes.Overview>>();
        overviews.add(firstDetail);
        overviews.add(latestDetail);
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        BuildRaces handle =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return catalogs.remove();
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return overviews.remove();
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var handles = new java.util.concurrent.atomic.AtomicReference<BuildRaces>(handle);
        var view =
                new RacesView(
                        handles::get, revision::get, (id, token) -> new CompletableFuture<>());
        new Scene(view, 1200, 900);
        view.applyCss();
        view.layout();
        view.ensureLoaded();
        view.applyCss();
        view.layout();
        revision.incrementAndGet();
        first.complete(new RaceTypes.Catalog(RaceTypes.State.READY, List.of(), List.of(), null));
        Platform.runLater(
                () -> {
                    try {
                        require(
                                activityCount(view) == 0,
                                "External race revision retires pending catalog activity");
                        require(
                                view.lookupAll(".list-view").stream()
                                        .anyMatch(javafx.scene.Node::isDisabled),
                                "Retired race list actions disabled");
                        view.ensureLoaded();
                        view.invalidate();
                        view.ensureLoaded();
                        superseded.complete(
                                new RaceTypes.Catalog(
                                        RaceTypes.State.READY, List.of(), List.of(), null));
                        Platform.runLater(
                                () -> {
                                    try {
                                        require(
                                                activityCount(view) > 0,
                                                "Old race catalog cannot clear newer pending"
                                                        + " activity");
                                        latest.complete(
                                                new RaceTypes.Catalog(
                                                        RaceTypes.State.READY,
                                                        List.of(
                                                                new RaceTypes.Race(
                                                                        1,
                                                                        "First pending race",
                                                                        1,
                                                                        1),
                                                                new RaceTypes.Race(
                                                                        2,
                                                                        "Latest pending race",
                                                                        2,
                                                                        1)),
                                                        List.of(),
                                                        null));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        var list =
                                                                (ListView<?>)
                                                                        view.lookup(".list-view");
                                                        list.getSelectionModel().select(1);
                                                        handles.set(null);
                                                        firstDetail.completeExceptionally(
                                                                new IllegalStateException(
                                                                        "Older retired race"));
                                                        Platform.runLater(
                                                                () -> {
                                                                    try {
                                                                        require(
                                                                                activityCount(view)
                                                                                        > 0,
                                                                                "Older race"
                                                                                    + " selection"
                                                                                    + " cannot"
                                                                                    + " clear newer"
                                                                                    + " overview"
                                                                                    + " activity");
                                                                        latestDetail
                                                                                .completeExceptionally(
                                                                                        new IllegalStateException(
                                                                                                "Current"
                                                                                                    + " retired"
                                                                                                    + " race"));
                                                                        Platform.runLater(
                                                                                () -> {
                                                                                    try {
                                                                                        require(
                                                                                                activityCount(
                                                                                                                view)
                                                                                                        == 0,
                                                                                                "Current"
                                                                                                    + " retired"
                                                                                                    + " overview"
                                                                                                    + " clears"
                                                                                                    + " center"
                                                                                                    + " and racial"
                                                                                                    + " rail"
                                                                                                    + " activity");
                                                                                        require(
                                                                                                labels(
                                                                                                                view)
                                                                                                        .contains(
                                                                                                                "Build"
                                                                                                                    + " changed."
                                                                                                                    + " Reload"
                                                                                                                    + " race"
                                                                                                                    + " records."),
                                                                                                "Race retirement"
                                                                                                    + " is explicit");
                                                                                        view
                                                                                                .close();
                                                                                        raceRetirementChecks
                                                                                                .complete(
                                                                                                        null);
                                                                                    } catch (
                                                                                            Throwable
                                                                                                    failure) {
                                                                                        view
                                                                                                .close();
                                                                                        raceRetirementChecks
                                                                                                .completeExceptionally(
                                                                                                        failure);
                                                                                    }
                                                                                });
                                                                    } catch (Throwable failure) {
                                                                        view.close();
                                                                        raceRetirementChecks
                                                                                .completeExceptionally(
                                                                                        failure);
                                                                    }
                                                                });
                                                    } catch (Throwable failure) {
                                                        view.close();
                                                        raceRetirementChecks.completeExceptionally(
                                                                failure);
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        view.close();
                                        raceRetirementChecks.completeExceptionally(failure);
                                    }
                                });
                    } catch (Throwable failure) {
                        view.close();
                        raceRetirementChecks.completeExceptionally(failure);
                    }
                });
    }

    private static void modelRetirementFeedback() {
        var variantDone = new CompletableFuture<Void>();
        var previewDone = new CompletableFuture<Void>();
        var appearanceDone = new CompletableFuture<Void>();
        var appearanceErrorDone = new CompletableFuture<Void>();
        CompletableFuture.allOf(variantDone, previewDone, appearanceDone, appearanceErrorDone)
                .whenComplete(
                        (value, failure) -> {
                            if (failure == null) modelRetirementChecks.complete(null);
                            else modelRetirementChecks.completeExceptionally(failure);
                        });
        var variants =
                new CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Variants>();
        var oldPreview =
                new CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>();
        var latestPreview =
                new CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>();
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        var oldCalls = new java.util.concurrent.atomic.AtomicInteger();
        var latestCalls = new java.util.concurrent.atomic.AtomicInteger();
        BuildRaces handle =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Variants>
                            modelVariants(long id, Cancellation token) {
                        return variants;
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>
                            preview(long race, long playerClass, long model, Cancellation token) {
                        if (model == 1) oldCalls.incrementAndGet(); else latestCalls.incrementAndGet();
                        return model == 1 ? oldPreview : latestPreview;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var retired = new RaceModelPanel(handle, () -> handle, revision::get, 1, 1, List.of());
        new Scene(retired, 800, 600);
        retired.applyCss();
        retired.layout();
        revision.incrementAndGet();
        variants.complete(
                new com.vibeminer.concepts.races.RaceMediaTypes.Variants(
                        RaceTypes.State.READY,
                        List.of(
                                new com.vibeminer.concepts.races.RaceMediaTypes.Variant(
                                        1, 0, 1, 1, 1),
                                new com.vibeminer.concepts.races.RaceMediaTypes.Variant(
                                        2, 1, 2, 2, 2)),
                        List.of(),
                        null));
        var current = new RaceModelPanel(handle, () -> handle, revision::get, 2, 1, List.of());
        new Scene(current, 800, 600);
        current.applyCss();
        current.layout();
        afterFx(() -> oldCalls.get() > 0 && activityCount(retired) == 0,
                () -> {
                    try {
                        require(
                                activityCount(retired) == 0
                                        && labels(retired)
                                                .contains("Build changed. Reload model records."),
                                "Retired variant completion clears busy");
                        require(
                                retired.lookupAll(".combo-box").stream()
                                        .allMatch(javafx.scene.Node::isDisabled),
                                "Retired model choices disabled");
                        variantDone.complete(null);
                        var model =
                                current.lookupAll(".combo-box").stream()
                                        .filter(
                                                node ->
                                                        "Recorded model variant"
                                                                .equals(node.getAccessibleText()))
                                        .map(javafx.scene.control.ComboBox.class::cast)
                                        .findFirst()
                                        .orElseThrow();
                        model.getSelectionModel().select(1);
                        model.fireEvent(new javafx.event.ActionEvent());
                        oldPreview.completeExceptionally(new IllegalStateException("Older mesh"));
                        afterFx(() -> latestCalls.get() > 0,
                                () -> {
                                    try {
                                        revision.incrementAndGet();
                                        require(
                                                activityCount(current) > 0,
                                                "Older mesh completion cannot clear newer model"
                                                        + " acquisition");
                                        latestPreview.completeExceptionally(
                                                new IllegalStateException("Current retired mesh"));
                                        afterFx(() -> activityCount(current) == 0,
                                                () -> {
                                                    try {
                                                        require(
                                                                activityCount(current) == 0
                                                                        && labels(current)
                                                                                .contains(
                                                                                        "Build"
                                                                                            + " changed."
                                                                                            + " Reload"
                                                                                            + " model"
                                                                                            + " records."),
                                                                "Current retired mesh clears busy");
                                                        current.close();
                                                        retired.close();
                                                        previewDone.complete(null);
                                                    } catch (Throwable failure) {
                                                        current.close();
                                                        retired.close();
                                                        previewDone.completeExceptionally(failure);
                                                    }
                                                }, previewDone);
                                    } catch (Throwable failure) {
                                        current.close();
                                        retired.close();
                                        previewDone.completeExceptionally(failure);
                                    }
                                }, previewDone);
                    } catch (Throwable failure) {
                        current.close();
                        retired.close();
                        variantDone.completeExceptionally(failure);
                        previewDone.completeExceptionally(failure);
                    }
                }, previewDone);
        var appearance =
                new CompletableFuture<com.vibeminer.concepts.races.RaceAppearanceTypes.Result>();
        var appearanceRevision = new java.util.concurrent.atomic.AtomicLong(1);
        var variant = new com.vibeminer.concepts.races.RaceMediaTypes.Variant(1, 0, 1, 1, 1);
        var mesh =
                new com.vibeminer.rendering.models.PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        List.of(new com.vibeminer.rendering.models.PreviewMesh.Part(0, 0, 3)));
        BuildRaces surface =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Variants>
                            modelVariants(long id, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new com.vibeminer.concepts.races.RaceMediaTypes.Variants(
                                        RaceTypes.State.READY, List.of(variant), List.of(), null));
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>
                            preview(long race, long playerClass, long model, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new com.vibeminer.concepts.races.RaceMediaTypes.Preview(
                                        RaceTypes.State.READY,
                                        variant,
                                        mesh,
                                        java.util.Set.of(0),
                                        List.of(),
                                        false,
                                        null));
                    }

                    public CompletableFuture<
                                    com.vibeminer.concepts.races.RaceAppearanceTypes.Result>
                            appearance(
                                    long race, long playerClass, long model, Cancellation token) {
                        return appearance;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var composed =
                new RaceModelPanel(
                        surface, () -> surface, appearanceRevision::get, 1, 1, List.of());
        new Scene(composed, 800, 600);
        composed.applyCss();
        composed.layout();
        afterFx(() -> composed.lookup("#model-appearance-activity").isVisible(), () -> {
            require(activityCount(composed) > 0, "Appearance is visibly pending");
            appearanceRevision.incrementAndGet();
            appearance.completeExceptionally(new IllegalStateException("Retired appearance"));
            afterFx(() -> activityCount(composed) == 0, () -> {
                require(labels(composed).contains("Build changed. Reload model records."),
                        "Retired appearance clears all activity");
                composed.close();
                appearanceDone.complete(null);
            }, appearanceDone);
        }, appearanceDone);
        var failedAppearance =
                new CompletableFuture<com.vibeminer.concepts.races.RaceAppearanceTypes.Result>();
        var failureSource =
                (BuildRaces)
                        java.lang.reflect.Proxy.newProxyInstance(
                                BuildRaces.class.getClassLoader(),
                                new Class<?>[] {BuildRaces.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("appearance"))
                                        return failedAppearance;
                                    try {
                                        return method.invoke(surface, args);
                                    } catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
        var failedPanel =
                new RaceModelPanel(failureSource, () -> failureSource, () -> 1, 1, 1, List.of());
        new Scene(failedPanel, 800, 600);
        failedPanel.applyCss();
        failedPanel.layout();
        afterFx(() -> failedPanel.lookup("#model-appearance-activity").isVisible(), () -> {
            require(failedPanel.lookup("#model-appearance-activity").isVisible(),
                    "Failed appearance starts with persistent viewport phase");
            failedAppearance.completeExceptionally(new IllegalStateException("Authored texture failure"));
            afterFx(() -> !failedPanel.lookup("#model-appearance-activity").isVisible()
                    && !failedPanel.lookup("#race-model-activity").isVisible(), () -> {
                require(labels(failedPanel).stream().anyMatch(text -> text.contains("Textured appearance is unavailable")),
                        "Texture failure clears phase with honest terminal state");
                failedPanel.close();
                appearanceErrorDone.complete(null);
            }, appearanceErrorDone);
        }, appearanceErrorDone);
    }

    private static void voiceRetirementFeedback() {
        var first = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>();
        var superseded =
                new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>();
        var latest = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>();
        var catalogs =
                new java.util.ArrayDeque<
                        CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>>();
        catalogs.add(first);
        catalogs.add(superseded);
        catalogs.add(latest);
        var audio = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Audio>();
        var external = new java.util.concurrent.atomic.AtomicBoolean();
        BuildRaces handle =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>
                            voices(long race, long playerClass, int sex, Cancellation token) {
                        return catalogs.remove();
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Audio>
                            voice(
                                    long race,
                                    long playerClass,
                                    int sex,
                                    long entry,
                                    Cancellation token) {
                        return audio;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var ready =
                new com.vibeminer.concepts.races.RaceVoiceTypes.Catalog(
                        RaceTypes.State.READY,
                        "Authored",
                        List.of(
                                new com.vibeminer.concepts.races.RaceVoiceTypes.Clip(
                                        1, 1, "Authored", 1, 1, null)),
                        List.of(),
                        List.of(),
                        null);
        var panel = new RaceVoicePanel(handle, external::get);
        new Scene(panel, 650, 250);
        panel.applyCss();
        panel.layout();
        panel.selection(1, 1, 0);
        external.set(true);
        first.complete(ready);
        Platform.runLater(
                () -> {
                    try {
                        require(activityCount(panel) == 0, "Retired voice catalog clears activity");
                        require(
                                panel.lookupAll(".combo-box").stream()
                                        .allMatch(javafx.scene.Node::isDisabled),
                                "Retired voice choices disabled");
                        external.set(false);
                        panel.selection(1, 1, 1);
                        panel.selection(1, 2, 1);
                        superseded.complete(ready);
                        Platform.runLater(
                                () -> {
                                    try {
                                        require(
                                                activityCount(panel) > 0,
                                                "Old voice catalog cannot clear newer pending"
                                                        + " state");
                                        latest.complete(ready);
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        var play =
                                                                panel.lookupAll(".button").stream()
                                                                        .filter(
                                                                                node ->
                                                                                        node
                                                                                                        instanceof
                                                                                                        javafx
                                                                                                                        .scene
                                                                                                                        .control
                                                                                                                        .Button
                                                                                                                button
                                                                                                && button.getText()
                                                                                                        .equals(
                                                                                                                "Play"))
                                                                        .map(
                                                                                javafx.scene.control
                                                                                                .Button
                                                                                                .class
                                                                                        ::cast)
                                                                        .findFirst()
                                                                        .orElseThrow();
                                                        play.fire();
                                                        require(
                                                                activityCount(panel) > 0,
                                                                "Voice decode is pending before"
                                                                        + " retirement");
                                                        external.set(true);
                                                        audio.completeExceptionally(
                                                                new IllegalStateException(
                                                                        "Authored retired decode"));
                                                        Platform.runLater(
                                                                () -> {
                                                                    try {
                                                                        require(
                                                                                activityCount(panel)
                                                                                                == 0
                                                                                        && play
                                                                                                .isDisabled(),
                                                                                "Retired voice"
                                                                                    + " decode"
                                                                                    + " clears busy"
                                                                                    + " and disables"
                                                                                    + " old playback");
                                                                        require(
                                                                                labels(panel)
                                                                                        .contains(
                                                                                                "Build"
                                                                                                    + " changed."
                                                                                                    + " Reload"
                                                                                                    + " voice"
                                                                                                    + " records."),
                                                                                "Voice retirement"
                                                                                    + " explicit");
                                                                        require(
                                                                                labels(panel)
                                                                                        .stream()
                                                                                        .noneMatch(
                                                                                                text ->
                                                                                                        text
                                                                                                                .startsWith(
                                                                                                                        "Source:")),
                                                                                "Retired voice"
                                                                                    + " clears its"
                                                                                    + " source"
                                                                                    + " chain");
                                                                        panel.close();
                                                                        voiceRetirementChecks
                                                                                .complete(null);
                                                                    } catch (Throwable failure) {
                                                                        panel.close();
                                                                        voiceRetirementChecks
                                                                                .completeExceptionally(
                                                                                        failure);
                                                                    }
                                                                });
                                                    } catch (Throwable failure) {
                                                        panel.close();
                                                        voiceRetirementChecks.completeExceptionally(
                                                                failure);
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        panel.close();
                                        voiceRetirementChecks.completeExceptionally(failure);
                                    }
                                });
                    } catch (Throwable failure) {
                        panel.close();
                        voiceRetirementChecks.completeExceptionally(failure);
                    }
                });
    }

    private static void voiceLoadingFeedback() {
        var catalog = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>();
        var audio = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Audio>();
        var decodes = new java.util.concurrent.atomic.AtomicInteger();
        BuildRaces handle =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>
                            voices(long race, long playerClass, int sex, Cancellation token) {
                        return catalog;
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Audio>
                            voice(
                                    long race,
                                    long playerClass,
                                    int sex,
                                    long entry,
                                    Cancellation token) {
                        decodes.incrementAndGet();
                        return audio;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var panel = new RaceVoicePanel(handle, () -> false);
        new Scene(panel, 650, 250);
        panel.applyCss();
        panel.layout();
        panel.selection(1, 1, 0);
        require(activityCount(panel) > 0, "Pending voice catalog has accessible activity");
        catalog.complete(
                new com.vibeminer.concepts.races.RaceVoiceTypes.Catalog(
                        RaceTypes.State.PARTIAL,
                        "Authored locale",
                        List.of(
                                new com.vibeminer.concepts.races.RaceVoiceTypes.Clip(
                                        1,
                                        1,
                                        "Authored joke",
                                        1,
                                        1,
                                        new com.vibeminer.concepts.races.RaceVoiceTypes.Source(
                                                "JOKE", 7, 1, 0, 0))),
                        List.of(
                                new com.vibeminer.concepts.races.RaceVoiceTypes.Refusal(
                                        "Authored link #9",
                                        "Authored reason; no recording.")),
                        List.of("Authored voice note."),
                        null));
        Platform.runLater(
                () -> {
                    try {
                        require(activityCount(panel) == 0, "Ready voice catalog retires activity");
                        require(decodes.get() == 0, "Voice catalog never autoplays");
                        require(
                                labels(panel)
                                        .contains(
                                                "Source: EmotesText #1 JOKE → EmotesTextSound #7"
                                                        + " (race 1, sex 0, every class) →"
                                                        + " SoundKit #1 → SoundKitEntry #1 →"
                                                        + " FileDataID 1"),
                                "Selected voice shows its recorded source chain");
                        require(
                                labels(panel)
                                        .contains(
                                                "Not playable (1 listed):\nAuthored link #9 —"
                                                        + " Authored reason; no recording."),
                                "Refused voice links show their reasons");
                        require(
                                labels(panel).contains("Authored voice note."),
                                "Voice record notes are shown");
                        panel.lookupAll(".button").stream()
                                .filter(
                                        node ->
                                                node instanceof javafx.scene.control.Button button
                                                        && button.getText().equals("Play"))
                                .map(javafx.scene.control.Button.class::cast)
                                .findFirst()
                                .orElseThrow()
                                .fire();
                        require(
                                activityCount(panel) > 0
                                        && labels(panel).contains("Decoding voice preview…"),
                                "Voice decode stays visibly pending");
                        require(
                                labels(panel).stream()
                                        .noneMatch(text -> text.startsWith("Playing")),
                                "Decode cannot claim actual playback");
                        audio.completeExceptionally(
                                new IllegalStateException("Authored decode failure"));
                        Platform.runLater(
                                () -> {
                                    try {
                                        require(
                                                activityCount(panel) == 0
                                                        && labels(panel)
                                                                .contains(
                                                                        "Voice preview is"
                                                                                + " unavailable."),
                                                "Voice failure retires loading");
                                        require(
                                                decodes.get() == 1,
                                                "Only the explicit Play button decodes");
                                        panel.selection(1, 1, 1);
                                        panel.close();
                                        require(
                                                activityCount(panel) == 0,
                                                "Closing cancels pending voice activity");
                                        voiceLoadingChecks.complete(null);
                                    } catch (Throwable failure) {
                                        panel.close();
                                        voiceLoadingChecks.completeExceptionally(failure);
                                    }
                                });
                    } catch (Throwable failure) {
                        panel.close();
                        voiceLoadingChecks.completeExceptionally(failure);
                    }
                });
    }

    private static void loadingFeedback() {
        var catalog = new CompletableFuture<RaceTypes.Catalog>();
        var overview = new CompletableFuture<RaceTypes.Overview>();
        var variants =
                new CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Variants>();
        var mesh = new CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>();
        var previewCalls = new java.util.concurrent.atomic.AtomicInteger();
        var handle =
                new BuildRaces() {
                    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
                        return catalog;
                    }

                    public CompletableFuture<RaceTypes.Overview> overview(
                            long id, Cancellation token) {
                        return overview;
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Variants>
                            modelVariants(long id, Cancellation token) {
                        return variants;
                    }

                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Preview>
                            preview(long race, long playerClass, long model, Cancellation token) {
                        previewCalls.incrementAndGet();
                        return mesh;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var view = new RacesView(() -> handle, () -> 1, (id, token) -> new CompletableFuture<>());
        new Scene(view, 1000, 800);
        view.ensureLoaded();
        view.applyCss();
        view.layout();
        require(activityCount(view) > 0, "Pending catalog shows region activity immediately");
        require(!catalog.isDone(), "Activity does not require catalog completion");
        view.close();
        catalog.complete(
                new RaceTypes.Catalog(
                        RaceTypes.State.READY,
                        List.of(new RaceTypes.Race(1, "STALE RACE", 1, 1)),
                        List.of(),
                        null));
        require(activityCount(view) == 0, "Closing removes all pending activity");
        var panel = new RaceModelPanel(handle, () -> handle, () -> 1, 1, 1, List.of());
        new Scene(panel, 800, 600);
        panel.applyCss();
        panel.layout();
        require(activityCount(panel) > 0, "Model variants show central pending activity");
        require(!variants.isDone(), "Variant acquisition stays asynchronous");
        variants.complete(
                new com.vibeminer.concepts.races.RaceMediaTypes.Variants(
                        RaceTypes.State.READY,
                        List.of(
                                new com.vibeminer.concepts.races.RaceMediaTypes.Variant(
                                        1, 0, 1, 1, 1)),
                        List.of(),
                        null));
        afterFx(() -> previewCalls.get() == 1,
                () -> {
                    try {
                        require(
                                previewCalls.get() == 1,
                                "Resolved variants start one mesh request");
                        require(
                                activityCount(panel) > 0,
                                "Pending mesh keeps model activity visible");
                        mesh.completeExceptionally(
                                new IllegalStateException("Authored mesh failure"));
                        afterFx(() -> activityCount(panel) == 0,
                                () -> {
                                    try {
                                        require(
                                                activityCount(panel) == 0,
                                                "Failed mesh clears pending activity");
                                        require(
                                                labels(panel)
                                                        .contains("Model surface is unavailable."),
                                                "Failed mesh shows explicit unavailable state");
                                        require(
                                                !labels(view).contains("STALE RACE"),
                                                "Closed catalog cannot repopulate view");
                                        panel.close();
                                        loadingChecks.complete(null);
                                    } catch (Throwable error) {
                                        loadingChecks.completeExceptionally(error);
                                    }
                                }, loadingChecks);
                    } catch (Throwable error) {
                        panel.close();
                        loadingChecks.completeExceptionally(error);
                    }
                }, loadingChecks);
    }

    private static void afterFx(java.util.function.BooleanSupplier ready, Runnable action,
            CompletableFuture<Void> completion) {
        afterFx(ready, action, completion, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    private static void afterFx(java.util.function.BooleanSupplier ready, Runnable action,
            CompletableFuture<Void> completion, long deadline) {
        try {
            if (ready.getAsBoolean()) { action.run(); return; }
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for actual model request/UI completion");
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(10));
            pause.setOnFinished(event -> afterFx(ready, action, completion, deadline));
            pause.play();
        } catch (Throwable failure) { completion.completeExceptionally(failure); }
    }

    private static long activityCount(javafx.scene.Node node) {
        if (!node.isVisible()) return 0;
        long count = node instanceof javafx.scene.control.ProgressIndicator ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) count += activityCount(child);
        return count;
    }

    private static boolean descendantsImage(javafx.scene.Node node) {
        if (node instanceof ImageView) return true;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable())
                if (descendantsImage(child)) return true;
        return false;
    }

    private static List<String> labels(javafx.scene.Parent parent) {
        return parent.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Authored explicit pair: display 1 is the recorded high-resolution male display. */
    private static com.vibeminer.concepts.races.RaceMediaTypes.Definition authoredDefinition() {
        return new com.vibeminer.concepts.races.RaceMediaTypes.Definition(
                com.vibeminer.concepts.races.RaceMediaTypes.DefinitionState.SELECTED,
                com.vibeminer.concepts.races.RaceMediaTypes.Quality.HD,
                "ChrRaces #1 HighResMaleDisplayID",
                2,
                1,
                "Display 1 is the recorded high-resolution display; its SD counterpart is display"
                        + " 2.");
    }
}
