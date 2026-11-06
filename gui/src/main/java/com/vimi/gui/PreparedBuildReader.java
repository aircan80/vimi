package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.builds.BuildOpener;
import com.vibeminer.builds.BuildResolutionCache;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.Builds;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.generated.ReadOnlyGeneratedStore;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.ReadOnlyStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only access to an already-prepared build's {@link BuildFiles} and {@link BuildWorld}
 * (#346), for developer probes that run while the application holds the library.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li><b>No writer lock, no writes.</b> The pack library opens with {@link
 *       ObjectStore#openReadOnly}; objects are read straight from it, so no ownership claim or read
 *       day is recorded. Generated data (file catalog, table cache, key graphs) is read through a
 *       {@link ReadOnlyGeneratedStore}, whose writes are refused, so an attempted write fails
 *       loudly instead of happening. The library database is only queried through read-only
 *       connections; the build registry, ownership catalog and settings are never opened as
 *       writers.
 *   <li><b>No network.</b> Every object source is {@link CachingObjectSource#localOnly}; there is
 *       no CDN, listfile or key provider. Community TACT keys are not loaded (version 1): files that
 *       need only those keys read as protected holes. Community file names are off.
 *   <li><b>Refuses rather than prepares.</b> Only builds with a Tables-ready preparation receipt are
 *       listed. A build without a stored file-resolution projection is refused as {@code
 *       BUILD_NEEDS_PREPARATION} before any metadata read; an unreadable one as {@code
 *       BUILD_PROJECTION_UNREADABLE}. A missing catalog, table cache or key graph refuses with the
 *       step that needed it.
 * </ul>
 *
 * <p>SQLite open mode: the library database is opened read-only ({@code SQLITE_OPEN_READONLY}) in
 * its WAL mode, never with {@code immutable=1}. With no writer running, a WAL reader creates the
 * WAL index sidecars ({@code -shm} and an empty {@code -wal}), and every WAL reader records its
 * read marks in the {@code -shm} shared-memory index. The sidecars hold no data and the next
 * writer reuses them, while the database, packs and lock files keep their bytes and modification
 * times.
 * {@code immutable=1} would avoid the sidecars but is unsafe: if the application starts writing
 * while this reader is open, SQLite would serve stale or inconsistent pages.
 */
public final class PreparedBuildReader implements AutoCloseable {
    /** A build with a Tables-ready preparation receipt in the library. */
    public record Prepared(
            String identity,
            String product,
            String version,
            String config,
            String cdnConfig,
            String region,
            String locale) {
        public Prepared {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(product);
            Objects.requireNonNull(config);
            Objects.requireNonNull(cdnConfig);
            Objects.requireNonNull(region);
            Objects.requireNonNull(locale);
        }
    }

    /** A refusal to open read-only, with the reason the build cannot be read as it is. */
    public static final class Refused extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Refused(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final ReadOnlyStore library;
    private final BuildOpener opener;
    private final BuildResolutionCache cache;
    private final TerrainAtlasPool atlases;
    private final NativePreparationWork work;
    private final NativePreparationWork.MeasurementWorld measured;
    private final List<String> issues;

    private PreparedBuildReader(
            ReadOnlyStore library,
            BuildOpener opener,
            BuildResolutionCache cache,
            TerrainAtlasPool atlases,
            NativePreparationWork work,
            NativePreparationWork.MeasurementWorld measured,
            List<String> issues) {
        this.library = library;
        this.opener = opener;
        this.cache = cache;
        this.atlases = atlases;
        this.work = work;
        this.measured = measured;
        this.issues = List.copyOf(issues);
    }

    public BuildFiles files() {
        return work.filesHandle();
    }

    public BuildWorld world() {
        return measured.world();
    }

    /** Non-fatal notes from opening, such as key graphs that could not be read. */
    public List<String> issues() {
        return issues;
    }

    static Path databasePath(Path dataDirectory) {
        return dataDirectory.resolve("library").resolve("objects.sqlite3");
    }

    /**
     * The builds with a Tables-ready preparation receipt in the library at {@code dataDirectory},
     * oldest receipt first, read through a read-only connection.
     */
    public static List<Prepared> list(Path dataDirectory) throws Exception {
        Path database = databasePath(dataDirectory);
        if (!Files.isRegularFile(database)) {
            throw new Refused("LIBRARY_MISSING", "No library at " + dataDirectory, null);
        }
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    session -> {
                        if (!table(session, "builds_preparation_receipts")) {
                            return List.<Prepared>of();
                        }
                        boolean registry = table(session, "builds_registry");
                        var rows =
                                session.query(
                                        "SELECT r.identity,r.product,"
                                                + (registry ? "g.version" : "NULL")
                                                + ",r.build_config,r.cdn_config,r.region,r.locale"
                                                + " FROM builds_preparation_receipts r"
                                                + (registry
                                                        ? " LEFT JOIN builds_registry g ON"
                                                                + " g.identity=r.identity"
                                                        : "")
                                                + " WHERE r.readiness=? ORDER BY r.sequence",
                                        BuildPreparation.Ready.TABLES_READY.name());
                        var prepared = new ArrayList<Prepared>();
                        for (Object[] row : rows) {
                            if (row[4] == null || row[5] == null || row[6] == null) {
                                continue;
                            }
                            prepared.add(
                                    new Prepared(
                                            (String) row[0],
                                            (String) row[1],
                                            (String) row[2],
                                            (String) row[3],
                                            (String) row[4],
                                            (String) row[5],
                                            (String) row[6]));
                        }
                        return List.copyOf(prepared);
                    });
        }
    }

    private static boolean table(DerivedSqliteConnection.Session session, String name) {
        return ((Number)
                                session.query(
                                                "SELECT count(*) FROM sqlite_master WHERE"
                                                        + " type='table' AND name=?",
                                                name)
                                        .getFirst()[0])
                        .longValue()
                != 0;
    }

    /**
     * Opens {@code build} read-only from the library at {@code dataDirectory}, or throws {@link
     * Refused} without writing anything.
     */
    public static PreparedBuildReader open(
            Path dataDirectory, Prepared build, com.vibeminer.access.Cancellation token) {
        Objects.requireNonNull(build);
        ReadOnlyStore library;
        try {
            library =
                    ObjectStore.openReadOnly(
                                    dataDirectory.resolve("library").toAbsolutePath(),
                                    new StoreTypes.Options(
                                            StoreTypes.Kind.LIBRARY,
                                            2L << 30,
                                            java.time.Clock.systemUTC()))
                            .join();
        } catch (RuntimeException unreadable) {
            throw new Refused("LIBRARY_UNREADABLE", "Library cannot be opened read-only", unreadable);
        }
        BuildResolutionCache cache = null;
        BuildOpener opener = null;
        TerrainAtlasPool atlases = null;
        NativePreparationWork work = null;
        try {
            // Two distinct local-only sources: prepared tables require an isolated one.
            var objects = CachingObjectSource.localOnly(library, library);
            var localObjects = CachingObjectSource.localOnly(library, library);
            cache = new BuildResolutionCache();
            opener =
                    new BuildOpener(
                            objects,
                            new BuildOpener.Options(),
                            cache,
                            BuildResolutionCache.Storage.readOnly(library));
            var readOnlyOpener = opener;
            Builds builds = (ref, cancel) -> readOnlyOpener.open(ref, cancel);
            var ref =
                    new BuildTypes.BuildRef(
                            build.product(), build.config(), build.cdnConfig(), build.region());
            // Refuse an unprepared build before any metadata, catalog or table read.
            var opened = builds.open(ref, token).join();
            if (opened.state() != BuildTypes.BuildOpenState.OPEN) {
                throw new Refused(
                        opened.code() == null ? "BUILD_" + opened.state() : opened.code(),
                        opened.message() == null ? "Build " + opened.state() : opened.message(),
                        null);
            }
            var generated = new ReadOnlyGeneratedStore(library);
            var names =
                    new CommunityFileNames(
                            objects,
                            generated,
                            cancel -> CompletableFuture.completedFuture(false));
            var evidence =
                    new BuildInfo.RegionEvidence(build.region(), build.cdnConfig(), false, null, null);
            var info =
                    new BuildInfo(
                            build.product(),
                            "",
                            build.version() == null ? build.config() : build.version(),
                            build.region(),
                            build.config(),
                            BuildInfo.BuildSource.SAVED,
                            true,
                            null,
                            0,
                            build.identity(),
                            null,
                            null,
                            List.of(evidence));
            atlases = new TerrainAtlasPool();
            work =
                    new NativePreparationWork(
                                    info,
                                    evidence,
                                    build.locale(),
                                    builds,
                                    objects,
                                    generated,
                                    names,
                                    localObjects,
                                    null,
                                    atlases)
                            .withSourcePolicy(false, true)
                            .withPinnedCommunityKeys(com.vibeminer.files.keys.TactKeys.EMPTY);
            step("metadata", work, w -> w.metadata(token));
            step("file handles", work, w -> w.openFilesForMeasurement(token));
            var issues = new ArrayList<String>();
            step("key graphs", work, w -> issues.addAll(w.keysPreparedLocal(token)));
            var measured = openWorld(work, token);
            return new PreparedBuildReader(
                    library, opener, cache, atlases, work, measured, issues);
        } catch (Throwable failure) {
            closeQuietly(work, opener, cache, atlases, library, failure);
            throw failure;
        }
    }

    private interface Step {
        void run(NativePreparationWork work);
    }

    private static void step(String name, NativePreparationWork work, Step step) {
        try {
            step.run(work);
        } catch (Refused refused) {
            throw refused;
        } catch (RuntimeException failure) {
            throw new Refused(
                    "BUILD_NEEDS_PREPARATION",
                    "Cannot open read-only, " + name + ": " + failure.getMessage(),
                    failure);
        }
    }

    private static NativePreparationWork.MeasurementWorld openWorld(
            NativePreparationWork work, com.vibeminer.access.Cancellation token) {
        try {
            return work.openWorldForMeasurement(token);
        } catch (RuntimeException failure) {
            throw new Refused(
                    "BUILD_NEEDS_PREPARATION",
                    "Cannot open read-only, World tables: " + failure.getMessage(),
                    failure);
        }
    }

    private static void closeQuietly(
            NativePreparationWork work,
            BuildOpener opener,
            BuildResolutionCache cache,
            TerrainAtlasPool atlases,
            ReadOnlyStore library,
            Throwable primary) {
        List<Runnable> closers = new ArrayList<>();
        if (work != null) {
            closers.add(work::abortPending);
            closers.add(work::close);
        }
        if (opener != null) {
            closers.add(() -> opener.closeAsync().join());
        }
        if (cache != null) {
            closers.add(cache::close);
        }
        if (atlases != null) {
            closers.add(() -> atlases.closeAsync().join());
        }
        closers.add(() -> library.closeAsync().join());
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException | Error cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        List<Runnable> closers =
                List.of(
                        measured::close,
                        work::abortPending,
                        work::close,
                        () -> opener.closeAsync().join(),
                        cache::close,
                        () -> atlases.closeAsync().join(),
                        () -> library.closeAsync().join());
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
