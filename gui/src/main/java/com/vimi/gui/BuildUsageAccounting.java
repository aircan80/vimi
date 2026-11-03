package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.accounting.UsageIndex.Cursor;
import com.vibeminer.accounting.UsageIndex.Inventory;
import com.vibeminer.accounting.UsageIndex.Resource;
import com.vibeminer.accounting.UsageIndex.Role;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.concepts.items.BuildItems;
import com.vibeminer.files.PreparedFileCatalog;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.files.names.NameCoverage;
import com.vibeminer.files.names.NamesTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.PreparedTablesCache;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** One context's reporting adapter; independent of preparation readiness and ownership. */
final class BuildUsageAccounting {
    private final OverviewAccounting accounting;
    private final BuildInfo info;
    private final BuildTypes.BuildRef ref;
    private final String locale;
    private final CommunityFileNames community;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final com.vibeminer.store.Cancellation token = new com.vibeminer.store.Cancellation();
    private final Cancellation accessToken = new Cancellation();
    private volatile CompletableFuture<Void> job = CompletableFuture.completedFuture(null);
    private volatile Build opened;
    private volatile PreparedFileCatalog.Catalog files;
    private volatile boolean hasNames, busy;
    private volatile UsageIndex.Figures usage;
    private volatile NameCoverage.Result names;
    private volatile String namesRevision, problem;
    private long revision = -1;
    private boolean reading, pending, pendingNames, running;
    private List<Path> observedDatabaseFiles = List.of();
    private TableInventory staged;
    private volatile TableInventory published;

    private record TableInventory(
            String identity, List<TableTypes.Entry> entries, BuildTables tables, BuildItems items) {
        TableInventory {
            entries = List.copyOf(entries);
        }
    }

    BuildUsageAccounting(
            OverviewAccounting accounting,
            BuildInfo info,
            BuildTypes.BuildRef ref,
            String locale,
            CommunityFileNames community) {
        this.accounting = accounting;
        this.info = info;
        this.ref = ref;
        this.locale = locale;
        this.community = community;
    }

    /** Declare verified retained membership before its potentially slow EN inventory is indexed. */
    void retain() {
        accounting
                .index
                .retain(info.identity(), false, "encoding", Role.BLIZZARD_ENCODED)
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) accounting.changed();
                            else problem = "Retained build membership could not be updated";
                        });
    }

    void files(Build opened, PreparedFileCatalog.Catalog files) {
        this.opened = opened;
        this.files = files;
        request(hasNames);
    }

    synchronized void tables(String identity, List<TableTypes.Entry> entries) {
        staged = new TableInventory(identity, entries, null, null);
    }

    synchronized void workspace(BuildTables tables, BuildItems items) {
        if (staged != null) {
            published = new TableInventory(staged.identity(), staged.entries(), tables, items);
        }
        request(false);
    }

    void namesReady() {
        hasNames = true;
        if (opened != null) {
            request(true);
        }
    }

    synchronized BuildAccounting view() {
        var current = published;
        if (!accounting.unified() && !closed.get() && current != null && current.items() != null) {
            var paths = current.items().savedDatabaseFiles();
            if (!observedDatabaseFiles.equals(paths)) {
                observedDatabaseFiles = List.copyOf(paths);
                request(false);
            }
        }
        long latest = accounting.revision();
        if (!closed.get() && !reading && latest != revision) {
            reading = true;
            revision = latest;
            accounting
                    .index
                    .figures(info.identity())
                    .whenComplete(
                            (value, error) -> {
                                synchronized (BuildUsageAccounting.this) {
                                    reading = false;
                                    if (error == null) {
                                        usage = value;
                                    } else {
                                        problem =
                                                "Accounting unavailable; prior measurement"
                                                        + " retained";
                                    }
                                }
                            });
        }
        return new BuildAccounting(
                usage,
                names,
                namesRevision,
                problem != null ? problem : accounting.problem(),
                busy,
                accounting.disk());
    }

    /** Coalesce refreshes: one worker and one pending latest snapshot per retained context. */
    private synchronized void request(boolean coverage) {
        if (closed.get()) {
            return;
        }
        pending = true;
        pendingNames |= coverage;
        busy = true;
        if (running) {
            return;
        }
        running = true;
        var done = new CompletableFuture<Void>();
        job = done;
        Thread.startVirtualThread(
                () -> {
                    while (true) {
                        boolean namesRequested;
                        synchronized (this) {
                            pending = false;
                            namesRequested = pendingNames;
                            pendingNames = false;
                        }
                        try {
                            measure(namesRequested);
                        } catch (Throwable failure) {
                            if (!token.isCanceled()) {
                                problem = "Measurement incomplete; prior figures retained";
                            }
                        }
                        synchronized (this) {
                            if (!pending || closed.get()) {
                                running = false;
                                busy = false;
                                break;
                            }
                        }
                    }
                    done.complete(null);
                });
    }

    private void measure(boolean coverage) {
        token.check();
        var index = accounting.index;
        index.retain(info.identity(), false, "encoding", Role.BLIZZARD_ENCODED).join();
        if (!accounting.unified()) {
            for (String producer : List.of("assets", "provider-dependencies")) {
                index.retain(info.identity(), false, producer, Role.DERIVED).join();
            }
        }
        originals();
        var catalog = files;
        if (catalog != null) {
            generated(source("file-catalog"), "record-file-v1", catalog.records().keys());
        }
        var tables = published;
        if (tables != null) {
            generated(
                    source("table-inventory"),
                    PreparedTablesCache.key(tables.identity()).hex(),
                    List.of(PreparedTablesCache.key(tables.identity())));
            tableGraphs(tables);
            itemDatabase(tables);
        }
        if (coverage && hasNames) {
            names();
        }
        token.check();
        usage = index.figures(info.identity()).join();
        accounting.changed();
        problem = null;
    }

    private String source(String kind) {
        return kind + ":" + locale + ":" + ref.region() + ":" + ref.cdnConfig();
    }

    private void originals() {
        if (accounting.unified()) {
            accounting.indexBuild(info, opened).join();
            return;
        }
        String version = opened.metadata().encodingContentKey().hex();
        if (accounting.index.current(info.identity(), "encoding", version).join()) {
            return;
        }
        Inventory inventory =
                () ->
                        new Cursor() {
                            int offset;

                            public List<Resource> next(
                                    com.vibeminer.store.Cancellation cancellation) {
                                cancellation.check();
                                var page = opened.encodedContentPage(offset, 256);
                                offset += page.size();
                                return page.stream()
                                        .map(
                                                entry ->
                                                        new Resource(
                                                                "objects",
                                                                OverviewAccounting.key(entry.key()),
                                                                Role.BLIZZARD_ENCODED,
                                                                entry.publishedBytes()))
                                        .toList();
                            }
                        };
        accounting
                .index
                .replace(
                        info.identity(),
                        "encoding",
                        version,
                        inventory,
                        accounting.originals,
                        token)
                .join();
    }

    private void generated(String source, String version, List<GeneratedKey> keys) {
        accounting.index.retain(info.identity(), false, source, Role.DERIVED).join();
        accounting
                .index
                .replace(
                        info.identity(),
                        source,
                        version,
                        OverviewAccounting.keys(keys),
                        accounting.generated,
                        token)
                .join();
    }

    private void tableGraphs(TableInventory tables) {
        String source = source("table-graphs");
        accounting.index.retain(info.identity(), false, source, Role.DERIVED).join();
        if (accounting
                .index
                .current(info.identity(), source, PreparedTablesCache.key(tables.identity()).hex())
                .join()) {
            return;
        }
        Inventory inventory =
                () ->
                        new Cursor() {
                            int table, offset;
                            List<GeneratedKey> keys = List.of();

                            public List<Resource> next(
                                    com.vibeminer.store.Cancellation cancellation) {
                                var page = new ArrayList<Resource>();
                                while (page.size() < 256) {
                                    cancellation.check();
                                    if (offset == keys.size()) {
                                        keys = List.of();
                                        offset = 0;
                                        while (table < tables.entries().size()) {
                                            var entry = tables.entries().get(table++);
                                            if (entry.state() == TableTypes.State.READY
                                                    || entry.state() == TableTypes.State.PARTIAL) {
                                                keys =
                                                        tables.tables()
                                                                .savedArtifacts(
                                                                        TableTypes.Ref.id(
                                                                                entry.fileDataId()),
                                                                        accessToken)
                                                                .join();
                                                break;
                                            }
                                        }
                                        if (keys.isEmpty() && table >= tables.entries().size()) {
                                            break;
                                        }
                                        if (keys.isEmpty()) {
                                            continue;
                                        }
                                    }
                                    page.add(
                                            new Resource(
                                                    "generated",
                                                    keys.get(offset++).hex(),
                                                    Role.DERIVED,
                                                    -1));
                                }
                                return List.copyOf(page);
                            }
                        };
        accounting
                .index
                .replace(
                        info.identity(),
                        source,
                        PreparedTablesCache.key(tables.identity()).hex(),
                        inventory,
                        accounting.generated,
                        token)
                .join();
    }

    private void itemDatabase(TableInventory tables) {
        if (accounting.unified() || tables.items() == null) {
            return;
        }
        var paths = tables.items().savedDatabaseFiles();
        if (paths.isEmpty()) {
            return;
        }
        String source = source("items-database");
        accounting.index.retain(info.identity(), false, source, Role.DATABASE).join();
        Inventory inventory =
                () ->
                        new Cursor() {
                            boolean done;

                            public List<Resource> next(
                                    com.vibeminer.store.Cancellation cancellation) {
                                if (done) {
                                    return List.of();
                                }
                                done = true;
                                return paths.stream()
                                        .map(
                                                path ->
                                                        new Resource(
                                                                "database",
                                                                path.toAbsolutePath()
                                                                        .normalize()
                                                                        .toString(),
                                                                Role.DATABASE,
                                                                -1))
                                        .toList();
                            }
                        };
        accounting
                .index
                .replace(
                        info.identity(),
                        source,
                        PreparedTablesCache.key(tables.identity()).hex(),
                        inventory,
                        accounting.databases,
                        token)
                .join();
    }

    private void names() {
        token.check();
        var before = community.status(accessToken).join();
        var snapshot = community.current(accessToken).join();
        if (snapshot == null || before.state() != NamesTypes.FileNamesState.READY) {
            return;
        }
        String fingerprint = snapshot.revision();
        String scope = locale + ":" + files.records().keys().getFirst().hex();
        NameCoverage.Result measured;
        var saved =
                fingerprint == null
                        ? null
                        : accounting.index.coverage(info.identity(), scope, fingerprint).join();
        if (saved != null) {
            measured = new NameCoverage.Result(saved.files(), saved.named());
        } else {
            measured =
                    NameCoverage.measure(
                            opened.fileDataIds(locale).iterator(), snapshot, accessToken);
        }
        token.check();
        if (snapshot.isDamaged()) {
            throw new IllegalStateException("Names snapshot is damaged");
        }
        var after = community.status(accessToken).join();
        if (Objects.equals(before.tag(), after.tag())
                && after.state() == NamesTypes.FileNamesState.READY) {
            if (fingerprint != null) {
                accounting
                        .index
                        .saveCoverage(
                                info.identity(),
                                scope,
                                fingerprint,
                                measured.files(),
                                measured.named(),
                                token)
                        .join();
                var keys = snapshot.artifactKeys();
                String provider =
                        accounting.unified() ? "provider:community-listfile" : "provider:listfile";
                if (!keys.isEmpty()) {
                    accounting.index.retain(provider, true, "index", Role.DERIVED).join();
                    accounting
                            .index
                            .replace(
                                    provider,
                                    "index",
                                    fingerprint,
                                    OverviewAccounting.keys(keys),
                                    accounting.generated,
                                    token)
                            .join();
                }
            }
            names = measured;
            namesRevision =
                    before.tag()
                            + (fingerprint == null
                                    ? ""
                                    : " · "
                                            + fingerprint.substring(
                                                    0, Math.min(12, fingerprint.length())));
        }
    }

    void close() {
        closed.set(true);
        token.cancel();
        accessToken.cancel();
        job.join();
    }
}
