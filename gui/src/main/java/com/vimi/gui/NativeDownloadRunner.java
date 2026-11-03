package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildOpener;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.CdnsRoutes;
import com.vibeminer.builds.DownloadAllJobs;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Dedicated build capabilities outlive all GUI workspace selections. */
final class NativeDownloadRunner implements BuildDownloads.Runner {
    private final ObjectStore library;
    private final NetworkClients network;
    private final ObjectReader legacy;
    private final OverviewAccounting accounting;
    private final Path directory;

    NativeDownloadRunner(
            ObjectStore library,
            NetworkClients network,
            ObjectReader legacy,
            OverviewAccounting accounting,
            Path directory) {
        this.library = library;
        this.network = network;
        this.legacy = legacy;
        this.accounting = accounting;
        this.directory = directory;
    }

    @Override
    public DownloadAllJobs.Result run(
            DownloadIntents.Entry entry,
            Cancellation token,
            Consumer<DownloadAllJobs.Progress> progress)
            throws Exception {
        var owner =
                library.forOwner(
                        entry.identity(),
                        LibraryCatalog.Scope.BUILD,
                        "download-all",
                        LibraryCatalog.Role.ORIGINAL);
        var source =
                new CachingObjectSource(
                        owner,
                        owner,
                        owner,
                        legacy,
                        network,
                        List.of(ObservationAdapters.versions(network)));
        var opener = new BuildOpener(source);
        var jobs =
                new DownloadAllJobs(
                        source,
                        ignored -> {
                            try {
                                return CompletableFuture.completedFuture(
                                        Files.getFileStore(directory).getUsableSpace());
                            } catch (Exception failure) {
                                return CompletableFuture.failedFuture(failure);
                            }
                        },
                        new DownloadAllJobs.Options(
                                4096, 64, 1, 1L << 30, java.time.Duration.ofMillis(250)));
        try {
            token.check();
            if (CdnsRoutes.route(
                            CdnsRoutes.newestFirst(source, entry.build().product(), token).join(),
                            entry.build().region())
                    == null) {
                source.observe(
                                new SourceTypes.ObservationRequest(
                                        SourceTypes.ObservationKind.CDNS,
                                        entry.build().product(),
                                        entry.build().region()),
                                token)
                        .join();
            }
            var opened = opener.open(entry.build(), token).join();
            if (opened.state() != BuildTypes.BuildOpenState.OPEN || opened.opened() == null)
                throw new IllegalStateException(
                        "Build could not be opened for download: " + opened.code());
            var info =
                    new BuildInfo(
                            entry.build().product(),
                            "",
                            "",
                            entry.build().region(),
                            entry.build().buildConfig(),
                            BuildInfo.BuildSource.SAVED,
                            false,
                            null,
                            0,
                            entry.identity(),
                            null,
                            null,
                            List.of());
            accounting.indexBuild(info, opened.opened()).join();
            token.check();
            var job = jobs.start(opened.opened(), progress);
            var registration = token.onCancel(job::cancel);
            try {
                return job.completion().join();
            } finally {
                registration.close();
            }
        } finally {
            try {
                jobs.closeAsync().join();
            } finally {
                try {
                    opener.closeAsync().join();
                } finally {
                    source.closeAsync().join();
                }
            }
        }
    }
}
