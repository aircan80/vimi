package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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

    /** The community TACT keys the work was pinned to: the library's loaded snapshot, or none. */
    final com.vibeminer.files.keys.TactKeys pinnedKeys;

    private final NativePreparationWork work;
    private final NativePreparationWork.MeasurementWorld measured;

    private WorldMeasurementOpen(
            BackendBuildService service,
            BuildInfo build,
            PreparationHistory.Receipt receipt,
            com.vibeminer.files.keys.TactKeys pinnedKeys,
            NativePreparationWork work,
            NativePreparationWork.MeasurementWorld measured) {
        this.service = service;
        this.build = build;
        this.receipt = receipt;
        this.pinnedKeys = pinnedKeys;
        this.work = work;
        this.measured = measured;
    }

    BuildWorld world() {
        return measured.world();
    }

    /**
     * Creature and race handles over the measured World's tables (#553); the caller closes them
     * before this open.
     */
    NativePreparationWork.MeasurementCreatures openCreatures(Cancellation token) {
        return work.openCreaturesForMeasurement(measured.tables(), token);
    }

    /** A new display preview component over the measured tables and files; caller closes it. */
    com.vibeminer.concepts.models.BuildDisplayModels displays() {
        return new com.vibeminer.concepts.models.DisplayModelService(
                measured.tables(), work.filesHandle(), work.generatedHandle());
    }

    /** The library's data sources (world spawns), opened without scheduled checks. */
    DataSourcesService sources() {
        return service.dataSources();
    }

    /** The marker a measurement copy must carry at its root; a real library never has it. */
    static final String MARKER = "MEASUREMENT_COPY";

    /** Seconds the library close may take; part of the probe's time budget. */
    static final long CLOSE_SECONDS = 60;

    /**
     * Why {@code configured} must not be measured on, or null when it may. It has to be given,
     * carry the {@value #MARKER} file at its root, hold a real (not linked) {@code library}
     * folder, and be neither the application's own data directory ({@code application}) nor share
     * its {@code library}, by path or by real path.
     */
    static String libraryRefusal(String configured, Path application) {
        if (configured == null || configured.isBlank()) {
            return "Pass -PdataDirectory=<a measurement copy of the library>; the probe never runs"
                    + " on the application's own library";
        }
        Path given = Path.of(configured).toAbsolutePath().normalize();
        Path own = application.toAbsolutePath().normalize();
        if (given.equals(own)) {
            return "-PdataDirectory names the application's own library; pass a copy";
        }
        Path library = given.resolve("library");
        try {
            if (Files.exists(own) && Files.exists(given)
                    && Files.isSameFile(given.toRealPath(), own.toRealPath())) {
                return "-PdataDirectory resolves to the application's own library; pass a copy";
            }
            if (!Files.isRegularFile(given.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
                return "-PdataDirectory has no " + MARKER + " file at its root; only a copy made"
                        + " for measurement may be used";
            }
            if (!Files.exists(library, LinkOption.NOFOLLOW_LINKS)) {
                return "-PdataDirectory has no library folder; copy the whole data directory";
            }
            var attributes =
                    Files.readAttributes(
                            library, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !attributes.isDirectory()
                    || !library.toRealPath().equals(given.toRealPath().resolve("library"))) {
                return "The copy's library folder is a link or junction; copy it, do not link it";
            }
            Path ownLibrary = own.resolve("library");
            if (Files.exists(ownLibrary) && Files.isSameFile(library, ownLibrary)) {
                return "The copy's library folder is the application's own; copy it";
            }
        } catch (IOException unreadable) {
            return "Cannot check -PdataDirectory against the application's library: " + unreadable;
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

    /**
     * Opens the build at {@code directory}; refuses (throws) rather than prepare anything. An
     * open that takes longer than {@code openNanos} is canceled, and this waits up to {@link
     * #CLOSE_SECONDS} for the opening thread to close what it opened before throwing.
     */
    static WorldMeasurementOpen open(
            Path directory,
            String wanted,
            com.vibeminer.network.TactKeyClient keys,
            Consumer<String> say,
            long openNanos)
            throws Exception {
        return open(directory, wanted, keys, say, openNanos, false);
    }

    /**
     * As above; with {@code communityKeys} the work also uses the library's already-loaded
     * community TACT keys (no refresh, no network), as a local build comparison does. The creature
     * counts need them for the encrypted sections of the Creature table (#553); the other modes
     * keep their baselines without them.
     */
    static WorldMeasurementOpen open(
            Path directory,
            String wanted,
            com.vibeminer.network.TactKeyClient keys,
            Consumer<String> say,
            long openNanos,
            boolean communityKeys)
            throws Exception {
        long until = System.nanoTime() + Math.max(1, openNanos);
        var settled = new java.util.concurrent.CompletableFuture<Void>();
        var opening = BuildApplication.openWithoutBackgroundWork(directory, keys, settled);
        BackendBuildService service;
        try {
            service = opening.get(Math.max(1, openNanos), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException late) {
            opening.cancel(true);
            settled.get(CLOSE_SECONDS, TimeUnit.SECONDS);
            throw new IllegalStateException("Library open overran its budget; canceled", late);
        }
        NativePreparationWork work = null;
        try {
            var selection =
                    select(
                            service.listBuilds()
                                    .get(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS),
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
            var sources = service.dataSources();
            var pinned =
                    communityKeys && sources != null
                            ? sources.tactKeys()
                            : com.vibeminer.files.keys.TactKeys.EMPTY;
            work =
                    contexts.createLocalComparison(
                            selection.build(), evidence, receipt.locale(), true, pinned);
            var token = Cancellation.NONE;
            work.metadata(token);
            work.openFilesForMeasurement(token);
            for (String issue : work.keysPreparedLocal(token)) say.accept("key issue: " + issue);
            var measured = work.openWorldForMeasurement(token);
            return new WorldMeasurementOpen(
                    service, selection.build(), receipt, pinned, work, measured);
        } catch (Throwable failure) {
            try {
                if (work != null) {
                    work.abortPending();
                    work.close();
                }
            } finally {
                service.closeAsync().get(CLOSE_SECONDS, TimeUnit.SECONDS);
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
                    service.closeAsync().get(CLOSE_SECONDS, TimeUnit.SECONDS);
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
