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
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.ReadOnlyGeneratedStore;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.ReadOnlyStore;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li><b>Refuses rather than prepares</b>, with a {@link Refused#code()} that says whether
 *       preparing again would help:
 *       <ul>
 *         <li>{@code BUILD_NEEDS_PREPARATION}: no stored file-resolution projection (checked
 *             before any metadata read), or a step that would have to write prepared data.
 *         <li>{@code BUILD_PROJECTION_UNREADABLE} / {@code PREPARED_DATA_UNREADABLE}: the data is
 *             there but cannot be read now (store closed or busy, a pack lost to a compaction,
 *             corrupt data); preparing again would not help.
 *         <li>{@code LOCALE_NOT_IN_BUILD}, {@code BUILD_NOT_REGISTERED}, {@code LIBRARY_MISSING},
 *             {@code LIBRARY_UNREADABLE}, {@code LIBRARY_UNSUPPORTED}, {@code
 *             LIBRARY_READ_ONLY_MEDIA}, and {@code READ_FAILED} for anything else.
 *       </ul>
 *       Cancellation is not a refusal: it propagates as {@link CancellationException}.
 * </ul>
 *
 * <p>SQLite open mode: the library database is opened read-only ({@code SQLITE_OPEN_READONLY}) in
 * its WAL mode. With no writer running, a WAL reader creates the WAL index sidecars ({@code -shm}
 * and an empty {@code -wal}), and every WAL reader records its read marks in the {@code -shm}
 * shared-memory index. The sidecars hold no data and the next writer reuses them, while the
 * database, packs and lock files keep their bytes. {@code immutable=1} would avoid the sidecars but
 * is unsafe: if the application starts writing while this reader is open, SQLite would serve stale
 * or inconsistent pages. The store itself falls back to {@code immutable=1} only when the library
 * folder is not writable; this route refuses that case ({@code LIBRARY_READ_ONLY_MEDIA}) in both
 * {@link #list} and {@link #open}, so it always reads in WAL mode.
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
    private final List<CachingObjectSource> sources;
    private final BuildOpener opener;
    private final BuildResolutionCache cache;
    private final TerrainAtlasPool atlases;
    private final NativePreparationWork work;
    private final NativePreparationWork.MeasurementWorld measured;
    private final List<String> issues;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PreparedBuildReader(Parts parts, NativePreparationWork.MeasurementWorld measured) {
        this.library = parts.library;
        this.sources = List.copyOf(parts.sources);
        this.opener = parts.opener;
        this.cache = parts.cache;
        this.atlases = parts.atlases;
        this.work = parts.work;
        this.measured = measured;
        this.issues = List.copyOf(parts.issues);
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

    static Path libraryFolder(Path dataDirectory) {
        return dataDirectory.resolve("library");
    }

    static Path databasePath(Path dataDirectory) {
        return libraryFolder(dataDirectory).resolve("objects.sqlite3");
    }

    /** Refuses a library this route cannot read in WAL mode; see the class comment. */
    private static void requireLibrary(Path dataDirectory) {
        Path folder = libraryFolder(dataDirectory);
        if (!Files.isRegularFile(databasePath(dataDirectory))) {
            throw new Refused("LIBRARY_MISSING", "No library at " + folder, null);
        }
        if (!Files.isWritable(folder)) {
            throw new Refused(
                    "LIBRARY_READ_ONLY_MEDIA",
                    "The library folder is not writable; this route reads only in WAL mode",
                    null);
        }
    }

    /**
     * The builds with a Tables-ready preparation receipt in the library at {@code dataDirectory},
     * oldest receipt first, read through a read-only connection. A receipt whose build is not in
     * the registry is listed without a version; {@link #open} refuses it.
     */
    public static List<Prepared> list(Path dataDirectory) {
        requireLibrary(dataDirectory);
        try (var connection = DerivedSqliteConnection.openReadOnly(databasePath(dataDirectory))) {
            return connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    session -> {
                        requireSchema(session);
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
        } catch (Refused refused) {
            throw refused;
        } catch (Exception unreadable) {
            throw new Refused(
                    "LIBRARY_UNREADABLE", "Library database cannot be read read-only", unreadable);
        }
    }

    /** The same library and preparation schema checks as {@code SqlPreparationReceipts}. */
    private static void requireSchema(DerivedSqliteConnection.Session session) {
        if (number(session, "PRAGMA application_id") != 2
                || number(session, "PRAGMA user_version") != 1
                || !table(session, "storage_metadata")
                || number(session, "SELECT version FROM storage_metadata") != 1) {
            throw new Refused(
                    "LIBRARY_UNSUPPORTED", "Not a unified pack library of a known version", null);
        }
        boolean metadata = table(session, "builds_preparation_metadata");
        if (metadata != table(session, "builds_preparation_receipts")
                || metadata
                        && (number(session, "SELECT count(*) FROM builds_preparation_metadata") != 1
                                || number(session, "SELECT version FROM builds_preparation_metadata")
                                        != 1)) {
            throw new Refused(
                    "LIBRARY_UNSUPPORTED", "Unknown or incomplete preparation schema", null);
        }
    }

    private static long number(DerivedSqliteConnection.Session session, String sql) {
        return ((Number) session.query(sql).getFirst()[0]).longValue();
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

    /** Everything opened so far, closed in reverse on a refusal or by {@link #close}. */
    private static final class Parts {
        ReadOnlyStore library;
        final List<CachingObjectSource> sources = new ArrayList<>();
        BuildOpener opener;
        BuildResolutionCache cache;
        TerrainAtlasPool atlases;
        NativePreparationWork work;
        final List<String> issues = new ArrayList<>();

        List<Runnable> closers() {
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
            for (var source : sources) {
                closers.add(() -> source.closeAsync().join());
            }
            if (library != null) {
                closers.add(() -> library.closeAsync().join());
            }
            return closers;
        }
    }

    /**
     * Opens {@code build} read-only from the library at {@code dataDirectory}, or throws {@link
     * Refused} without writing anything. Every resource opened before a refusal is released.
     */
    public static PreparedBuildReader open(
            Path dataDirectory, Prepared build, com.vibeminer.access.Cancellation token) {
        Objects.requireNonNull(build);
        requireLibrary(dataDirectory);
        if (build.version() == null) {
            throw new Refused(
                    "BUILD_NOT_REGISTERED",
                    "The build has a preparation receipt but no registry entry",
                    null);
        }
        var parts = new Parts();
        try {
            try {
                parts.library =
                        ObjectStore.openReadOnly(
                                        libraryFolder(dataDirectory).toAbsolutePath(),
                                        new StoreTypes.Options(
                                                StoreTypes.Kind.LIBRARY,
                                                2L << 30,
                                                java.time.Clock.systemUTC()))
                                .join();
            } catch (RuntimeException unreadable) {
                throw new Refused(
                        "LIBRARY_UNREADABLE", "Library cannot be opened read-only", unreadable);
            }
            var library = parts.library;
            // Two distinct local-only sources: prepared tables require an isolated one.
            var objects = CachingObjectSource.localOnly(library, library);
            parts.sources.add(objects);
            var localObjects = CachingObjectSource.localOnly(library, library);
            parts.sources.add(localObjects);
            parts.cache = new BuildResolutionCache();
            parts.opener =
                    new BuildOpener(
                            objects,
                            new BuildOpener.Options(),
                            parts.cache,
                            BuildResolutionCache.Storage.readOnly(library));
            var opener = parts.opener;
            Builds builds = (ref, cancel) -> opener.open(ref, cancel);
            var ref =
                    new BuildTypes.BuildRef(
                            build.product(), build.config(), build.cdnConfig(), build.region());
            // Refuse an unprepared build before any metadata, catalog or table read.
            var opened = step("build", () -> builds.open(ref, token).join());
            if (opened.state() != BuildTypes.BuildOpenState.OPEN || opened.opened() == null) {
                String detail =
                        opened.state()
                                + (opened.code() == null ? "" : " " + opened.code())
                                + (opened.message() == null ? "" : ": " + opened.message());
                // The projection codes say whether preparing helps; any other failure (corrupt
                // or missing metadata objects, a closed store) is stored data that cannot be read.
                String code =
                        BuildResolutionCache.NEEDS_PREPARATION.equals(opened.code())
                                        || BuildResolutionCache.PROJECTION_UNREADABLE.equals(
                                                opened.code())
                                ? opened.code()
                                : "PREPARED_DATA_UNREADABLE";
                throw new Refused(code, "Build cannot be opened read-only: " + detail, null);
            }
            if (!opened.opened().metadata().locales().contains(build.locale())) {
                throw new Refused(
                        "LOCALE_NOT_IN_BUILD",
                        "Locale " + build.locale() + " is not in this build",
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
                            build.version(),
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
            parts.atlases = new TerrainAtlasPool();
            parts.work =
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
                                    parts.atlases)
                            .withSourcePolicy(false)
                            .withPinnedCommunityKeys(com.vibeminer.files.keys.TactKeys.EMPTY);
            var work = parts.work;
            step("metadata", () -> work.metadata(token));
            step("file handles", () -> {
                work.openFilesForMeasurement(token);
                return null;
            });
            step("key graphs", () -> parts.issues.addAll(work.keysPreparedLocal(token)));
            var measured = step("World tables", () -> work.openWorldForMeasurement(token));
            return new PreparedBuildReader(parts, measured);
        } catch (Throwable failure) {
            for (Runnable closer : parts.closers()) {
                try {
                    closer.run();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private interface Step<T> {
        T run();
    }

    /** Runs one open step; a failure becomes a {@link Refused} with an honest code. */
    private static <T> T step(String name, Step<T> step) {
        try {
            return step.run();
        } catch (Refused refused) {
            throw refused;
        } catch (RuntimeException failure) {
            throw refusal(name, failure);
        }
    }

    static RuntimeException refusal(String step, RuntimeException failure) {
        String code = "READ_FAILED";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException canceled) {
                return canceled;
            }
            if (cause instanceof GeneratedStoreException generated) {
                // A step that would have written prepared data: preparing again supplies it.
                code =
                        GeneratedTypes.READ_ONLY.equals(generated.code())
                                ? "BUILD_NEEDS_PREPARATION"
                                : "PREPARED_DATA_UNREADABLE";
                break;
            }
            if (cause instanceof StoreException) {
                // Closed, busy, a pack lost to a live compaction, corrupt data: re-preparing
                // would not help.
                code = "PREPARED_DATA_UNREADABLE";
                break;
            }
        }
        return new Refused(
                code, "Cannot open read-only, " + step + ": " + failure.getMessage(), failure);
    }

    /** Releases everything; idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        List<Runnable> closers = new ArrayList<>();
        closers.add(measured::close);
        var parts = new Parts();
        parts.library = library;
        parts.sources.addAll(sources);
        parts.opener = opener;
        parts.cache = cache;
        parts.atlases = atlases;
        parts.work = work;
        closers.addAll(parts.closers());
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
