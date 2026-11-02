package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.accounting.LibraryUsage;
import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.files.names.NameCoverage;
import com.vibeminer.formats.tables.TableTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/** Display-only authored data review; never opens real stores or user builds. */
public final class OverviewAccountingUiTest {
    private OverviewAccountingUiTest() {}

    public static void main(String[] args) throws Exception {
        var route =
                new BuildInfo.RegionEvidence(
                        "us", "2".repeat(32), true, Instant.EPOCH, Instant.EPOCH);
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3.45678",
                        "us",
                        "1".repeat(32),
                        BuildInfo.BuildSource.SAVED,
                        false,
                        null,
                        0,
                        "overview-fixture",
                        Instant.EPOCH,
                        Instant.EPOCH,
                        List.of(route));
        var accounting =
                new BuildAccounting(
                        new UsageIndex.Figures(
                                new UsageIndex.Split(1L << 30, 2L << 30, 0, 0),
                                new UsageIndex.Split(40L << 20, 20L << 20, 0, 12L << 20),
                                5L << 30,
                                10,
                                20,
                                0,
                                3,
                                1,
                                false,
                                Instant.parse("2026-11-02T12:00:00Z")),
                        new NameCoverage.Result(1000000, 750000),
                        "community-v1 · aabbccddeeff",
                        null,
                        false,
                        new LibraryUsage.Totals(
                                Map.of(
                                        LibraryUsage.Kind.ORIGINAL_PACKS,
                                        4L << 30,
                                        LibraryUsage.Kind.GENERATED_PACKS,
                                        100L << 20,
                                        LibraryUsage.Kind.DATABASES,
                                        12L << 20,
                                        LibraryUsage.Kind.WAL,
                                        1L << 20),
                                12,
                                true));
        var engine =
                new BuildPreparation(
                        (build, source, locale) ->
                                new BuildPreparation.Work() {
                                    public BuildTypes.BuildMetadata metadata(Cancellation token) {
                                        var key =
                                                com.vibeminer.network.CdnKey.parse("1".repeat(32));
                                        return new BuildTypes.BuildMetadata(
                                                "fixture",
                                                key,
                                                key,
                                                key,
                                                0,
                                                1000000,
                                                List.of("enUS"));
                                    }

                                    public BuildPreparation.FilesSummary files(
                                            java.util.function.Consumer<BuildPreparation.Counter>
                                                    progress,
                                            Cancellation token) {
                                        return new BuildPreparation.FilesSummary(
                                                1000000, 0, 0, false);
                                    }

                                    public List<String> keys(Cancellation token) {
                                        return List.of();
                                    }

                                    public TableTypes.PreloadResult tables(
                                            java.util.function.Consumer<TableTypes.PreloadProgress>
                                                    progress,
                                            Cancellation token) {
                                        return new TableTypes.PreloadResult(
                                                TableTypes.PreloadOutcome.COMPLETED,
                                                null,
                                                List.of(),
                                                com.vibeminer.files.FileTypes.FilesOpenState.OPEN,
                                                null,
                                                null);
                                    }

                                    public List<String> warm(Cancellation token) {
                                        return List.of();
                                    }

                                    public BuildAccounting accounting() {
                                        return accounting;
                                    }
                                },
                        Runnable::run);
        var job = engine.start(info, route, "enUS");
        var ready = job.completion().get(5, TimeUnit.SECONDS);
        if (ready.ready() != BuildPreparation.Ready.TABLES_READY)
            throw new AssertionError(ready.problem());
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        var rendered = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    Stage stage = null;
                    try {
                        var live =
                                BuildOverviewView.live(
                                        job,
                                        ignored -> {},
                                        new Label("Authored accounting fixture"));
                        var scroll = new ScrollPane(live.node());
                        scroll.setFitToWidth(true);
                        var scene = new Scene(scroll, 1320, 1100);
                        scene.getStylesheets()
                                .add(
                                        OverviewAccountingUiTest.class
                                                .getResource("/vimi.css")
                                                .toExternalForm());
                        stage = new Stage();
                        stage.setTitle("Vimi Overview accounting review");
                        stage.setScene(scene);
                        stage.show();
                        scroll.applyCss();
                        scroll.layout();
                        var image = scene.snapshot(null);
                        int width = (int) image.getWidth(), height = (int) image.getHeight();
                        int[] pixels = new int[width * height];
                        image.getPixelReader()
                                .getPixels(
                                        0,
                                        0,
                                        width,
                                        height,
                                        PixelFormat.getIntArgbInstance(),
                                        pixels,
                                        0,
                                        width);
                        var buffered =
                                new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        buffered.setRGB(0, 0, width, height, pixels, 0, width);
                        ImageIO.write(
                                buffered,
                                "png",
                                Path.of(
                                                System.getProperty("java.io.tmpdir"),
                                                "vimi-accounting-overview.png")
                                        .toFile());
                        rendered.complete(null);
                    } catch (Throwable failure) {
                        rendered.completeExceptionally(failure);
                    } finally {
                        if (stage != null) stage.close();
                    }
                });
        try {
            rendered.get(20, TimeUnit.SECONDS);
            System.out.println("Overview accounting authored scene rendered");
        } finally {
            engine.closeAsync().join();
            Platform.exit();
        }
    }
}
