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

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        loadingFeedback();
                        voiceLoadingFeedback();
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
                                                                        1, 0, 1, 1, 1)),
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
                                                                1, 0, 1, 1, 1),
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
                                                                        200)),
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
                                                                activityCount(authoredView.get())
                                                                        > 0,
                                                                "Pending appearance shows"
                                                                    + " accessible local activity");
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
                                                !view.lookupAll(".image-view").isEmpty()
                                                        || descendantsImage(view),
                                                "Racial image rendered");
                                        require(
                                                labels(view).stream()
                                                        .anyMatch(
                                                                value ->
                                                                        value.startsWith(
                                                                                "Static textured"
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
            System.out.println(
                    "Races UI: partial data, icons, internal-record filter, stale selection and"
                            + " borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static void voiceLoadingFeedback() {
        var catalog = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Catalog>();
        var audio = new CompletableFuture<com.vibeminer.concepts.races.RaceVoiceTypes.Audio>();
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
                                        1, 1, "Authored joke", 1, 1)),
                        List.of(),
                        null));
        Platform.runLater(
                () -> {
                    try {
                        require(activityCount(panel) == 0, "Ready voice catalog retires activity");
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
        Platform.runLater(
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
                        Platform.runLater(
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
                                });
                    } catch (Throwable error) {
                        panel.close();
                        loadingChecks.completeExceptionally(error);
                    }
                });
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
}
