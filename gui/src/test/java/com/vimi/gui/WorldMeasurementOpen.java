package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * How the World measurement probe opens a build (#353, #422). It never prepares anything:
 *
 * <ul>
 *   <li>It refuses the application's own library; the caller passes a copy.
 *   <li>The library opens without background work: no download resumes and no provider check is
 *       scheduled.
 *   <li>The build must have a TABLES_READY preparation receipt in that library. The receipt
 *       supplies the region, CDN configuration and locale.
 *   <li>One local-only work (no network sources) opens the metadata, the resolution indexes and
 *       file handle (no file catalog pass), the build's cached key graphs, and a World over its own
 *       workspace tables. No preparation job is started and nothing is staged or published.
 * </ul>
 */
final class WorldMeasurementOpen implements AutoCloseable {
    final BackendBuildService service;
    final BuildInfo build;
    final PreparationHistory.Receipt receipt;
    private final NativePreparationWork work;
    private final NativePreparationWork.MeasurementWorld measured;

    private WorldMeasurementOpen(
            BackendBuildService service,
            BuildInfo build,
            PreparationHistory.Receipt receipt,
            NativePreparationWork work,
            NativePreparationWork.MeasurementWorld measured) {
        this.service = service;
        this.build = build;
        this.receipt = receipt;
        this.work = work;
        this.measured = measured;
    }

    BuildWorld world() {
        return measured.world();
    }

    /**
     * Why {@code configured} must not be measured on, or null when it may: it has to be given and
     * must not be the application's own library ({@code application}), by path or by real path.
     */
    static String libraryRefusal(String configured, Path application) {
        if (configured == null || configured.isBlank()) {
            return "Pass -PdataDirectory=<a copy of the library>; the probe never runs on the"
                    + " application's own library";
        }
        Path given = Path.of(configured).toAbsolutePath().normalize();
        Path own = application.toAbsolutePath().normalize();
        if (given.equals(own)) {
            return "-PdataDirectory names the application's own library; pass a copy";
        }
        try {
            if (Files.exists(given)
                    && Files.exists(own)
                    && Files.isSameFile(given.toRealPath(), own.toRealPath())) {
                return "-PdataDirectory resolves to the application's own library; pass a copy";
            }
        } catch (IOException unreadable) {
            return "Cannot compare -PdataDirectory with the application's library: " + unreadable;
        }
        if (!Files.isDirectory(given.resolve("library"))) {
            return "-PdataDirectory has no library folder; copy the whole data directory";
        }
        return null;
    }

    /**
     * The TABLES_READY receipt of the newest listed build whose version contains {@code wanted},
     * or null. Only a receipt of the same identity, product and build configuration counts.
     */
    static Selection select(
            List<BuildInfo> builds, List<PreparationHistory.Receipt> receipts, String wanted) {
        Selection found = null;
        for (var build : builds) {
            if (!build.version().contains(wanted)) continue;
            for (var receipt : receipts) {
                if (Objects.equals(receipt.identity(), build.identity())
                        && Objects.equals(receipt.product(), build.product())
                        && Objects.equals(receipt.config(), build.config())
                        && BuildPreparation.Ready.TABLES_READY.name().equals(receipt.ready())
                        && receipt.region() != null
                        && receipt.locale() != null
                        && receipt.cdnConfig() != null
                        && receipt.cdnConfig().matches("[0-9a-fA-F]{32}")) {
                    found = new Selection(build, receipt);
                }
            }
        }
        return found;
    }

    record Selection(BuildInfo build, PreparationHistory.Receipt receipt) {}

    /** Opens the build at {@code directory}; refuses (throws) rather than prepare anything. */
    static WorldMeasurementOpen open(
            Path directory,
            String wanted,
            com.vibeminer.network.TactKeyClient keys,
            Consumer<String> say)
            throws Exception {
        var service =
                BuildApplication.openWithoutBackgroundWork(directory, keys)
                        .get(5, TimeUnit.MINUTES);
        NativePreparationWork work = null;
        try {
            var selection =
                    select(
                            service.listBuilds().get(2, TimeUnit.MINUTES),
                            service.preparedReceipts(),
                            wanted);
            if (selection == null) {
                throw new IllegalStateException(
                        "No build matching "
                                + wanted
                                + " has a Tables-ready preparation in this library; prepare it"
                                + " in the application first (the probe never does)");
            }
            var receipt = selection.receipt();
            var evidence =
                    new BuildInfo.RegionEvidence(
                            receipt.region(), receipt.cdnConfig(), false, null, null);
            var contexts = (NativePreparationContexts) service.preparation().factory();
            work =
                    contexts.createLocalComparison(
                            selection.build(),
                            evidence,
                            receipt.locale(),
                            true,
                            com.vibeminer.files.keys.TactKeys.EMPTY);
            var token = Cancellation.NONE;
            work.metadata(token);
            work.openFilesForMeasurement(token);
            for (String issue : work.keysPreparedLocal(token)) say.accept("key issue: " + issue);
            var measured = work.openWorldForMeasurement(token);
            return new WorldMeasurementOpen(service, selection.build(), receipt, work, measured);
        } catch (Throwable failure) {
            try {
                if (work != null) {
                    work.abortPending();
                    work.close();
                }
            } finally {
                service.closeAsync().get(2, TimeUnit.MINUTES);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        try {
            measured.close();
        } finally {
            try {
                work.abortPending();
                work.close();
            } finally {
                try {
                    service.closeAsync().get(2, TimeUnit.MINUTES);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Library close interrupted", interrupted);
                } catch (java.util.concurrent.ExecutionException
                        | java.util.concurrent.TimeoutException failed) {
                    throw new IllegalStateException("Library close failed", failed);
                }
            }
        }
    }
}
