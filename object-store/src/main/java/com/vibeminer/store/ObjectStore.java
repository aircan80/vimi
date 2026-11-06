package com.vibeminer.store;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.vibeminer.store.StoreTypes.CompactReport;
import com.vibeminer.store.StoreTypes.DeleteOutcome;
import com.vibeminer.store.StoreTypes.DeleteResult;
import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.ObjectInfo;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.OpenReport;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import com.vibeminer.store.StoreTypes.ReadState;
import com.vibeminer.store.StoreTypes.Refusal;
import com.vibeminer.store.StoreTypes.ScanReport;
import com.vibeminer.store.StoreTypes.ScrubReport;
import com.vibeminer.store.StoreTypes.Stats;
import com.vibeminer.store.StoreTypes.VerifyReport;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Layer 1 format-1 store. Public operations enqueue work; no GUI or network dependencies. */
public final class ObjectStore implements ObjectReader, ObjectWriter, HistoryLog, StoreMaintenance {
    public static final String DEFAULT_FOLDER_NAME = "cdn-store-v1";
    private static final ThreadPoolExecutor OPENERS = Async.pool("vimi-store-open", 2);
    final Path folder, packs, staging, indexPath;
    final Options options;
    final boolean readOnly;
    final Verification verifier;
    final WriteQueue writer = new WriteQueue();
    final PackReaders<FileChannel> packReaders;
    final SessionHashes sessionHashes = new SessionHashes();
    final ThreadPoolExecutor readers = Async.pool("vimi-store-read", 8);
    final ThreadPoolExecutor verificationWorkers =
            Async.pool(
                    "vimi-store-verify",
                    PreparedPut.parallelism(Runtime.getRuntime().availableProcessors()));
    private final Object lifecycle = new Object();
    private boolean accepting = true;
    private CompletableFuture<Void> closing;
    WriterLock lock;
    NativeSqlite database;
    StoreIndex index;
    int nextPack = 1;
    boolean immutable;
    volatile StoreException writeFailure;
    OpenReport openReport;
    final Map<ObjectKey, Staging> stagings = new HashMap<>();
    long stagingBytes;
    final ReadDays readDays = new ReadDays();
    ReadDays.Scheduler readDayScheduler = ReadDays::schedule;
    private ReadDays.Wakeup readDayWakeup;
    private boolean readDayFlushPending;
    private PendingWrites pending;
    private boolean grouping;
    java.util.function.LongSupplier commitClock = System::nanoTime;

    final StoreIo io = new StoreIo();
    final BitSet corruptPacks = new BitSet(PackFiles.MAX_PACK + 1);
    // Package-private, instance-scoped test seam. No property, public option or global hook.
    java.util.function.BiConsumer<WritePoint, ObjectKey> writeProbe;
    java.util.function.Consumer<MaintenancePoint> maintenanceProbe;
    // Test-only: observes the index connection as soon as open creates it. Unset in production.
    static volatile java.util.function.Consumer<NativeSqlite> indexOpened;
    java.util.function.Consumer<Maintenance.Resources> maintenanceResourcesProbe;
    java.util.function.BiConsumer<ReadPoint, ObjectKey> readProbe;
    java.util.function.Consumer<ObjectKey> wholeShaProbe;
    java.util.function.Consumer<Boolean> verificationProbe;
    // Refusal code for a key, or null; replaces verification's outcome for that object.
    java.util.function.Function<ObjectKey, String> refusalProbe;

    private record Committed<T>(T result, CompletableFuture<Void> durable) {}

    private static <T> CompletableFuture<T> finish(CompletableFuture<Committed<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        operation.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        value.durable.whenComplete(
                                (ignored, failure) -> {
                                    if (failure != null) {
                                        result.completeExceptionally(failure);
                                    } else {
                                        result.complete(value.result);
                                    }
                                });
                    }
                });
        return result;
    }

    private boolean present(ObjectKey key) {
        Boolean state = pending == null ? null : pending.keys.get(key);
        return state != null ? state : index.find(key) != null;
    }

    private void trackRead(ObjectKey key) {
        if (readOnly) {
            return;
        }
        readDays.offer(key);
        synchronized (lifecycle) {
            scheduleReadDays();
        }
    }

    // lifecycle guards scheduling only; readers never perform SQLite work or wait for a writer
    // slot.
    private void scheduleReadDays() {
        if (!accepting || writeFailure != null || readDays.isEmpty() || readDayFlushPending) {
            return;
        }
        readDayFlushPending = true;
        try {
            readDayWakeup = readDayScheduler.schedule(this::onReadDayTimer, 30, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            // Accounting is best effort. A later read/commit/close can still flush the keys.
            readDayFlushPending = false;
        }
    }

    private void onReadDayTimer() {
        synchronized (lifecycle) {
            readDayWakeup = null;
            if (!accepting || writeFailure != null) {
                return;
            }
            write(() -> null)
                    .whenComplete(
                            (ignored, error) -> {
                                synchronized (lifecycle) {
                                    readDayFlushPending = false;
                                    scheduleReadDays();
                                }
                            });
        }
    }

    private void updateReadDays(List<ObjectKey> keys) {
        long day = Math.floorDiv(options.clock().instant().getEpochSecond(), 86_400);
        // Stay below even SQLite builds with a 999-parameter limit; avoid preparing per key.
        for (int start = 0; start < keys.size(); start += 256) {
            int count = Math.min(256, keys.size() - start);
            Object[] parameters = new Object[count + 2];
            parameters[0] = day;
            for (int i = 0; i < count; i++) {
                parameters[i + 1] = keys.get(start + i).indexBytes();
            }
            parameters[count + 1] = day;
            String placeholders = String.join(",", Collections.nCopies(count, "?"));
            database.exec(
                    "UPDATE objects SET last_read=? WHERE k IN ("
                            + placeholders
                            + ") AND (last_read IS NULL OR last_read<?)",
                    parameters);
        }
    }

    private void flushReadDays() {
        List<ObjectKey> keys = readDays.drain();
        if (keys.isEmpty()) {
            return;
        }
        try {
            database.uncancelled(() -> database.transaction(false, () -> updateReadDays(keys)));
        } catch (NativeSqlite.TransactionFailure failure) {
            // An unresolved transaction must not be reused by subsequent data writes.
            throw stopWriting(failure);
        } catch (NativeSqlite.Failure failure) {
            // Confirmed rollback of this best-effort metadata must not fail reads or later writes.
        }
    }

    private void commitPending() throws IOException {
        PendingWrites batch = pending;
        if (batch == null) {
            flushReadDays();
            return;
        }
        List<ObjectKey> readKeys = readDays.drain();
        boolean committed = false;
        try (FileChannel file = FileChannel.open(packPath(batch.pack), READ, WRITE)) {
            io.sync(file, packPath(batch.pack));
            for (var entry : batch.entries) {
                atWrite(WritePoint.PACK_SYNCED, entry.header().key());
            }
            // NORMAL, never FULL: the pack fsync above orders the bytes before these rows, and a
            // lost commit loses a WAL suffix that reopen truncates away
            // (docs/durability-policy.md).
            database.uncancelled(
                    () ->
                            database.transaction(
                                    false,
                                    () -> {
                                        for (var entry : batch.entries) {
                                            applyEntry(
                                                    batch.pack,
                                                    entry.offset(),
                                                    entry.header(),
                                                    entry.history());
                                        }
                                        for (var claim : batch.claims) {
                                            LibraryOwnership.attach(
                                                    database,
                                                    claim.owner(),
                                                    claim.key(),
                                                    claim.length());
                                        }
                                        index.save();
                                        updateReadDays(readKeys);
                                        for (var entry : batch.entries) {
                                            atWrite(WritePoint.INDEX_UPDATED, entry.header().key());
                                        }
                                    }));
            committed = true;
        } catch (Throwable error) {
            if (committed) {
                pending = null;
                writeFailure = storeFailure(error);
                batch.finish(writeFailure);
                throw writeFailure;
            }
            throw abortPending(error, batch.pack, batch.start);
        }
        pending = null;
        batch.finish(null);
        for (var entry : batch.entries) {
            atWrite(WritePoint.INDEX_COMMITTED, entry.header().key());
        }
    }

    private RuntimeException abortPending(Throwable error, int pack, long start) {
        PendingWrites batch = pending;
        pending = null;
        RuntimeException failure;
        if (error instanceof NativeSqlite.TransactionFailure transaction
                && transaction.outcome != NativeSqlite.Outcome.ROLLED_BACK) {
            writeFailure = storeFailure(error);
            failure = writeFailure;
        } else {
            try (FileChannel file = FileChannel.open(packPath(pack), READ, WRITE)) {
                io.truncate(file, packPath(pack), start);
                io.sync(file, packPath(pack));
                database.uncancelled(index::load);
                failure = translate(error, "OBJECT_STORE_WRITE_FAILED");
            } catch (Throwable cleanup) {
                error.addSuppressed(cleanup);
                writeFailure = storeFailure(error);
                failure = writeFailure;
            }
        }
        if (batch != null) {
            batch.finish(failure);
        }
        return failure;
    }

    private void applyEntry(int number, long start, Format.Entry entry, History.Entry history) {
        ObjectKey key = entry.key();
        StoreIndex.Row previous = index.find(key);
        long live =
                database.scalar("SELECT live_bytes FROM packs WHERE seq=?", number)
                        + Format.entrySize(entry.length());
        if (entry.tombstone() && previous != null) {
            index.remove(previous);
            if (previous.pack() == number) {
                live -= Format.entrySize(previous.length());
            } else {
                Object[] pack =
                        database.query(
                                        "SELECT state,committed_length,live_bytes FROM packs WHERE"
                                                + " seq=?",
                                        previous.pack())
                                .getFirst();
                index.pack(
                        previous.pack(),
                        Math.toIntExact((long) pack[0]),
                        (long) pack[1],
                        (long) pack[2] - Format.entrySize(previous.length()));
            }
        } else if (!entry.tombstone()) {
            index.insert(
                    new StoreIndex.Row(key, number, start, entry.length(), entry.sha()), history);
        }
        index.next = entry.sequence() + 1;
        index.pack(number, 0, start + Format.entrySize(entry.length()), live);
    }

    private void atRead(ReadPoint point, ObjectKey key) {
        if (readProbe != null) {
            readProbe.accept(point, key);
        }
    }

    void atMaintenance(MaintenancePoint point) {
        if (maintenanceProbe != null) {
            maintenanceProbe.accept(point);
        }
    }

    private void atWrite(WritePoint point, ObjectKey key) {
        if (writeProbe != null) {
            writeProbe.accept(point, key);
        }
    }

    private ObjectStore(Path path, Options options, boolean readOnly) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Store folder must be absolute");
        }
        this.folder = path.normalize();
        this.options = options;
        this.readOnly = readOnly;
        packs = folder.resolve("packs");
        staging = folder.resolve("staging");
        indexPath = folder.resolve("objects.sqlite3");
        verifier =
                options.kind() == Kind.LIBRARY
                        ? Verification.LIBRARY
                        : options.kind() == Kind.CDN ? Verification.CDN : Verification.GENERATED;
        // Default FileChannel sharing permits concurrent writers/deletion on supported JDKs.
        packReaders =
                new PackReaders<>(
                        options.packHandles(), number -> FileChannel.open(packPath(number), READ));
    }

    public static CompletableFuture<ObjectStore> openLibrary(Path folder) {
        return open(
                folder.toAbsolutePath().normalize(),
                new Options(Kind.LIBRARY, 2L << 30, java.time.Clock.systemUTC()));
    }

    public Path databasePath() {
        return indexPath;
    }

    public PackLibraryOwner forOwner(
            String owner, LibraryCatalog.Scope scope, String source, LibraryCatalog.Role role) {
        if (kind() != Kind.LIBRARY) {
            throw new IllegalStateException("Owner views require a unified pack library");
        }
        return new PackLibraryOwner(this, new LibraryOwnership.Claim(owner, scope, source, role));
    }

    public static CompletableFuture<ObjectStore> open(Path folder) {
        return open(folder, new Options());
    }

    public static CompletableFuture<ObjectStore> open(Path folder, Options options) {
        return open(folder, options, null);
    }

    // Used only by same-package tests to observe rebuilds before open completes.
    static CompletableFuture<ObjectStore> open(
            Path folder, Options options, java.util.function.Consumer<MaintenancePoint> probe) {
        return open(folder, options, probe, null);
    }

    static CompletableFuture<ObjectStore> open(
            Path folder,
            Options options,
            java.util.function.Consumer<MaintenancePoint> probe,
            StoreIo.Fault fault) {
        return Async.submit(
                OPENERS,
                () -> {
                    ObjectStore store = new ObjectStore(folder, options, false);
                    store.maintenanceProbe = probe;
                    store.io.fault = fault;
                    try {
                        SqliteLibrary.lookup();
                        store.openCore();
                        return store;
                    } catch (Throwable e) {
                        store.release();
                        throw translate(e, "OBJECT_STORE_WRITE_FAILED");
                    }
                });
    }

    public static CompletableFuture<ReadOnlyStore> openReadOnly(Path folder, Options options) {
        return Async.submit(
                OPENERS,
                () -> {
                    ObjectStore store = new ObjectStore(folder, options, true);
                    try {
                        SqliteLibrary.lookup();
                        store.openCore();
                        return new ReadOnlyStore(store);
                    } catch (Throwable e) {
                        store.release();
                        throw translate(e, "OBJECT_STORE_INDEX_UNREADABLE");
                    }
                });
    }

    public Path folder() {
        return folder;
    }

    public Kind kind() {
        return options.kind();
    }

    public OpenReport openReport() {
        return openReport;
    }

    long now() {
        return options.clock().millis();
    }

    Path packPath(int number) {
        return packs.resolve(String.format(Locale.ROOT, "pack-%06d.vmp", number));
    }

    PackFiles diskPacks() throws IOException {
        return PackFiles.discover(packs);
    }

    int allocatePack() {
        if (nextPack > 999999) {
            throw new StoreException("OBJECT_STORE_LIMIT", "Pack number limit reached");
        }
        return nextPack++;
    }

    private void openCore() throws IOException {
        if (!readOnly) {
            Files.createDirectories(packs);
            lock = new WriterLock(folder.resolve("writer.lock"));
        } else if (!Files.exists(indexPath)) {
            throw new StoreException("OBJECT_STORE_MISSING", "No complete index");
        }
        PackFiles files = diskPacks();
        if (!files.isEmpty()) {
            nextPack = files.lastKey() + 1;
        }
        byte[] packId = null;
        boolean readable = false;
        // Inspect all pack headers before modifying any index or pack bytes.
        for (var item : files.entrySet()) {
            try (FileChannel file = FileChannel.open(item.getValue(), READ)) {
                Format.Pack p =
                        Format.pack(Format.read(file, 0, 64), item.getKey(), options.kind());
                if (p != null) {
                    if (packId != null && !Arrays.equals(packId, p.id())) {
                        throw new StoreException(
                                "OBJECT_STORE_FOREIGN_PACK", "Packs belong to different stores");
                    }
                    packId = p.id();
                    readable = true;
                }
            }
        }
        boolean valid = false, full = false;
        if (Files.exists(indexPath)) {
            try (NativeSqlite check = openReader()) {
                StoreIndex candidate = new StoreIndex(check);
                long version = check.scalar("PRAGMA user_version");
                boolean damaged = candidate.damagedVersion(version);
                if (!damaged && version > 1) {
                    throw Format.newer();
                }
                if (readOnly && !damaged && version < 1) {
                    throw new StoreException("OBJECT_STORE_MISSING", "No complete index");
                }
                if (version == 1 || damaged) {
                    long kind = check.scalar("PRAGMA application_id");
                    if (kind > (options.kind() == Kind.LIBRARY ? 2 : 1)) {
                        throw Format.newer();
                    }
                    if (kind != options.kind().ordinal()) {
                        throw Format.mismatch();
                    }
                }
                if (version == 1 && candidate.validate(packId, false, Cancellation.NONE)) {
                    full = candidate.clean == 0;
                    valid = !full || candidate.fullCheck(Cancellation.NONE);
                    nextPack =
                            Math.max(
                                    nextPack,
                                    Math.toIntExact(
                                                    check.scalar(
                                                            "SELECT coalesce(max(seq),0) FROM"
                                                                    + " packs"))
                                            + 1);
                }
                if (immutable && candidate.clean != 1) {
                    valid = false;
                }
            } catch (NativeSqlite.Failure | ClassCastException | IllegalArgumentException e) {
                valid = false;
            }
        }
        if (readOnly) {
            if (!valid) {
                throw new StoreException(
                        "OBJECT_STORE_INDEX_UNREADABLE",
                        "Index failed validation; writer must rebuild it");
            }
            openReport = new OpenReport(0, 0, false, full, List.of());
            return;
        }
        boolean rebuilt = !valid;
        List<String> problems = new ArrayList<>();
        if (!valid && kind() == Kind.LIBRARY && (Files.exists(indexPath) || !files.isEmpty())) {
            throw new StoreException(
                    "OBJECT_STORE_INDEX_UNREADABLE",
                    "Unified library index requires repair; ownership and build metadata were"
                            + " preserved");
        }
        if (!valid) {
            Maintenance.preflight(
                    this, files); // Refuse newer entries before deleting/replacing any bytes.
            deleteRebuild();
            if (readable) {
                Path rebuilding = folder.resolve("objects.sqlite3.rebuild");
                try (NativeSqlite db = new NativeSqlite(rebuilding, false)) {
                    StoreIndex target = new StoreIndex(db);
                    target.create(packId, now(), options.kind().ordinal());
                    atMaintenance(MaintenancePoint.REBUILD_CREATED);
                    Maintenance.replay(this, target, files, Cancellation.NONE);
                    atMaintenance(MaintenancePoint.REBUILD_REPLAYED);
                    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
                    atMaintenance(MaintenancePoint.REBUILD_CHECKPOINTED);
                }
                atMaintenance(MaintenancePoint.REBUILD_BEFORE_REPLACE);
                // Keep the previous main index until the atomic switch. An interruption
                // must not create a gap between deleting the old file and installing the new one.
                for (String suffix : List.of("-wal", "-shm")) {
                    io.delete(folder.resolve("objects.sqlite3" + suffix));
                }
                io.replace(rebuilding, indexPath);
                atMaintenance(MaintenancePoint.REBUILD_REPLACED);
                io.syncDirectory(folder);
            } else {
                deleteIndexFiles();
                for (Path path : files.values()) {
                    if (Files.size(path) <= 64) {
                        io.delete(path);
                    }
                }
                byte[] id = new byte[16];
                new SecureRandom().nextBytes(id);
                try (NativeSqlite db = new NativeSqlite(indexPath, false)) {
                    new StoreIndex(db).create(id, now(), options.kind().ordinal());
                }
                rebuilt = Files.exists(indexPath) && !files.isEmpty();
            }
        }
        database = new NativeSqlite(indexPath, false);
        if (indexOpened != null) {
            indexOpened.accept(database);
        }
        database.exec("PRAGMA journal_mode=WAL");
        database.exec("PRAGMA synchronous=NORMAL");
        index = new StoreIndex(database);
        index.load();
        if (kind() == Kind.LIBRARY) {
            LibraryOwnership.initialize(database);
        }
        nextPack =
                Math.max(
                        nextPack,
                        Math.toIntExact(database.scalar("SELECT coalesce(max(seq),0) FROM packs"))
                                + 1);
        // Protect newer active tails before clearing the clean-close marker.
        for (Object[] row :
                database.query("SELECT seq,committed_length FROM packs WHERE state=0")) {
            Path path = packPath(Math.toIntExact((long) row[0]));
            if (kind() == Kind.LIBRARY && Files.exists(path)) {
                try (FileChannel file = FileChannel.open(path, READ, WRITE)) {
                    long committedLength = (long) row[1];
                    if (file.size() > committedLength) {
                        io.truncate(file, path, committedLength);
                        io.sync(file, path);
                    }
                }
            }
            if (Files.exists(path)) {
                Maintenance.preflightTail(this, path, (long) row[1]);
            }
        }
        if (options.kind() != Kind.GENERATED) {
            Files.createDirectories(staging);
        }
        deleteRebuild();
        database.transaction(
                true,
                () -> {
                    index.clean = 0;
                    index.save();
                });
        long recovered = 0, truncated = 0;
        BitSet listed = new BitSet(PackFiles.MAX_PACK + 1);
        for (Object[] row : PackRows.all(database, "seq,state,committed_length,live_bytes")) {
            int number = Math.toIntExact((long) row[0]), state = Math.toIntExact((long) row[1]);
            listed.set(number);
            long committed = (long) row[2], live = (long) row[3];
            Path path = packPath(number);
            if (state >= 2) {
                io.delete(path);
                io.syncDirectory(packs);
                database.exec("DELETE FROM packs WHERE seq=?", number);
                continue;
            }
            if (!Files.exists(path)) {
                Maintenance.problem(problems, "Missing pack " + number);
                if (state == 0) {
                    index.pack(number, 1, committed, live);
                }
            } else if (Files.size(path) < committed
                    || state == 1 && Files.size(path) != committed) {
                Maintenance.problem(
                        problems, "Pack length differs from committed length: " + number);
                if (state == 0) {
                    index.pack(number, 1, committed, live);
                }
            } else if (state == 0 && Files.size(path) > committed) {
                long[] result = Maintenance.recover(this, number, committed, live, problems);
                recovered += result[0];
                truncated += result[1];
            }
        }
        for (var item : diskPacks().entrySet()) {
            if (!listed.get(item.getKey())) {
                if (kind() == Kind.LIBRARY || Files.size(item.getValue()) <= 64) {
                    io.delete(item.getValue());
                    io.syncDirectory(packs);
                } else {
                    Maintenance.problem(problems, "Unlisted pack " + item.getKey());
                }
            }
        }
        if (Files.isDirectory(staging)) {
            try (var paths = Files.newDirectoryStream(staging)) {
                for (Path path : paths) {
                    if (path.getFileName().toString().matches("[0-9a-f]{36}\\.part")
                            && Files.getLastModifiedTime(path).toMillis() < now() - 7L * 86400000) {
                        io.delete(path);
                    }
                }
            }
        }
        openReport = new OpenReport(recovered, truncated, rebuilt, full, problems);
    }

    NativeSqlite openReader() throws IOException {
        if (immutable) {
            return new NativeSqlite(indexPath.toUri() + "?immutable=1", true);
        }
        try {
            NativeSqlite reader = new NativeSqlite(indexPath, true);
            try {
                // WAL sidecar access can fail on the first query rather than sqlite3_open_v2.
                reader.scalar("PRAGMA user_version");
                return reader;
            } catch (RuntimeException | Error error) {
                try {
                    reader.close();
                } catch (RuntimeException | Error cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        } catch (NativeSqlite.Failure e) {
            Path wal = folder.resolve("objects.sqlite3-wal");
            if (readOnly && ((e.result & 255) == 14 || (e.result & 255) == 8)) {
                if (!Files.isWritable(folder) && (!Files.exists(wal) || Files.size(wal) == 0)) {
                    immutable = true;
                    return new NativeSqlite(indexPath.toUri() + "?immutable=1", true);
                }
            }
            throw e;
        }
    }

    private void deleteRebuild() throws IOException {
        for (String suffix : List.of("", "-wal", "-shm")) {
            io.delete(folder.resolve("objects.sqlite3.rebuild" + suffix));
        }
    }

    private void deleteIndexFiles() throws IOException {
        for (String suffix : List.of("-wal", "-shm", "")) {
            io.delete(folder.resolve("objects.sqlite3" + suffix));
        }
    }

    <T> CompletableFuture<T> write(Async.Work<T> work) {
        return write(0, Cancellation.NONE, work);
    }

    <T> CompletableFuture<T> write(long bytes, Cancellation cancellation, Async.Work<T> work) {
        return write(bytes, cancellation, false, false, work);
    }

    private <T> CompletableFuture<T> write(
            long bytes,
            Cancellation cancellation,
            boolean grouped,
            boolean commitNow,
            Async.Work<T> work) {
        return write(bytes, cancellation, grouped, commitNow, work, null, null);
    }

    private <T> CompletableFuture<T> write(
            long bytes,
            Cancellation cancellation,
            boolean grouped,
            boolean commitNow,
            Async.Work<T> work,
            Runnable preparation,
            CompletableFuture<?> readiness) {
        synchronized (lifecycle) {
            if (!accepting) {
                return CompletableFuture.failedFuture(closed());
            }
            if (readOnly) {
                return CompletableFuture.failedFuture(
                        new StoreException("OBJECT_STORE_READ_ONLY", "Store is read-only"));
            }
            if (writeFailure != null) {
                return CompletableFuture.failedFuture(writeFailure);
            }
            return writer.submit(
                    bytes,
                    cancellation,
                    () -> {
                        if (writeFailure != null) {
                            throw writeFailure;
                        }
                        try {
                            if (!grouped) {
                                commitPending();
                            }
                            grouping = grouped;
                            T result;
                            try {
                                result = database.withCancellation(cancellation, work);
                            } catch (Throwable error) {
                                if (grouped && pending != null) {
                                    if (error instanceof CancellationException) {
                                        commitPending();
                                    } else {
                                        throw abortPending(error, pending.pack, pending.start);
                                    }
                                }
                                throw error;
                            }
                            if (grouped
                                    && pending != null
                                    && (commitNow
                                            || pending.expired(commitClock.getAsLong())
                                            || !writer.hasReadyWork())) {
                                commitPending();
                            }
                            return result;
                        } catch (StoreIndex.MalformedRow error) {
                            throw new StoreException(
                                    "OBJECT_STORE_INDEX_UNREADABLE",
                                    "Object index row is malformed; scrub or reopen the store",
                                    error);
                        } catch (IOException | NativeSqlite.Failure error) {
                            throw translate(error, "OBJECT_STORE_WRITE_FAILED");
                        } catch (NativeSqlite.TransactionFailure error) {
                            writeFailure = storeFailure(error);
                            throw writeFailure;
                        } finally {
                            grouping = false;
                        }
                    },
                    preparation,
                    readiness);
        }
    }

    <T> CompletableFuture<T> reading(Cancellation cancellation, ReadWork<T> work) {
        synchronized (lifecycle) {
            if (!accepting) {
                return CompletableFuture.failedFuture(closed());
            }
            long queued = ReadTimings.start();
            return Async.submit(
                    readers,
                    () -> {
                        ReadTimings.stop("store.read.queue", queued);
                        cancellation.check();
                        long opening = ReadTimings.start();
                        try {
                            NativeSqlite db = openReader();
                            ReadTimings.stop("store.read.openConnection", opening);
                            long working = ReadTimings.start();
                            try {
                                return db.withCancellation(
                                        cancellation, () -> work.run(new StoreIndex(db)));
                            } finally {
                                ReadTimings.stop("store.read.work", working);
                                long closing = ReadTimings.start();
                                db.close();
                                ReadTimings.stop("store.read.closeConnection", closing);
                            }
                        } catch (NativeSqlite.Failure e) {
                            throw translate(e, "OBJECT_STORE_INDEX_UNREADABLE");
                        }
                    });
        }
    }

    interface ReadWork<T> {
        T run(StoreIndex index) throws Exception;
    }

    static StoreException closed() {
        return new StoreException("OBJECT_STORE_CLOSED", "Store is closed");
    }

    static RuntimeException translate(Throwable e, String fallback) {
        if (e instanceof NativeSqlite.TransactionFailure transaction) {
            return translate(transaction.getCause(), fallback);
        }
        if (e instanceof StoreException s) {
            return s;
        }
        if (e instanceof CancellationException c) {
            return c;
        }
        if (e instanceof IllegalArgumentException a) {
            return a;
        }
        boolean full = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message =
                    cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (cause instanceof NativeSqlite.Failure sqlite && (sqlite.result & 255) == 13
                    || message.contains("no space left")
                    || message.contains("not enough space")
                    || message.contains("disk quota exceeded")) {
                full = true;
                break;
            }
        }
        String code = full ? "OBJECT_STORE_DISK_FULL" : fallback;
        return new StoreException(code, "Store operation failed", e);
    }

    private static StoreException storeFailure(Throwable error) {
        RuntimeException translated = translate(error, "OBJECT_STORE_WRITE_FAILED");
        if (translated instanceof StoreException store
                && store.code().startsWith("OBJECT_STORE_")) {
            return store;
        }
        return new StoreException(
                "OBJECT_STORE_WRITE_FAILED", "Writing stopped until reopen", error);
    }

    StoreException stopWriting(Throwable error) {
        writeFailure = storeFailure(error);
        return writeFailure;
    }

    void reloadIndex(Throwable primary) {
        try {
            database.uncancelled(index::load);
        } catch (Throwable reload) {
            if (primary != null) {
                primary.addSuppressed(reload);
                throw stopWriting(primary);
            }
            throw stopWriting(reload);
        }
    }

    @Override
    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation cancellation) {
        return reading(
                cancellation,
                i -> i.db.scalar("SELECT count(*) FROM objects WHERE k=?", key.indexBytes()) != 0);
    }

    @Override
    public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation cancellation) {
        return reading(cancellation, i -> readCore(i, key, 0, -1, false, false, cancellation));
    }

    @Override
    public CompletableFuture<ObjectRead> readRange(
            ObjectKey key, long offset, int length, Cancellation cancellation) {
        if (offset < 0 || length < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Negative range"));
        }
        if (length > Format.WHOLE_LIMIT) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_LIMIT", "Range exceeds 64 MiB"));
        }
        return reading(
                cancellation, i -> readCore(i, key, offset, length, false, false, cancellation));
    }

    ObjectRead readCore(
            StoreIndex idx,
            ObjectKey key,
            long offset,
            int requested,
            boolean fullSha,
            boolean keyCheck,
            Cancellation cancellation)
            throws IOException {
        return readCore(idx, key, offset, requested, fullSha, keyCheck, cancellation, true, null);
    }

    private ObjectRead readCore(
            StoreIndex idx,
            ObjectKey key,
            long offset,
            int requested,
            boolean fullSha,
            boolean keyCheck,
            Cancellation cancellation,
            boolean retry,
            StoreIndex.Row known)
            throws IOException {
        StoreIndex.Row row;
        try {
            row = known != null ? known : idx.find(key);
        } catch (ClassCastException | IllegalArgumentException | ArithmeticException e) {
            return corruptRead("Invalid index row");
        }
        if (row == null) {
            return bad(ReadState.ABSENT, null);
        }
        if (row.pack() < 1
                || row.offset() < 64
                || row.length() < 1
                || row.length() > Format.MAX_PAYLOAD
                || row.sha().length != 32) {
            return corruptRead("Invalid index row");
        }
        if (requested < 0 && row.length() > Format.WHOLE_LIMIT && !fullSha) {
            throw new StoreException("OBJECT_STORE_LIMIT", "Whole read exceeds 64 MiB; use ranges");
        }
        Path path = packPath(row.pack());
        atRead(ReadPoint.ROW_FOUND, key);
        try (var lease = packReaders.acquire(row.pack())) {
            if (lease == null) {
                throw new NoSuchFileException(path.toString());
            }
            FileChannel file = lease.handle();
            atRead(ReadPoint.PACK_OPENED, key);
            Format.Entry entry;
            try {
                entry = Format.entry(Format.read(file, row.offset(), 96), options.kind());
            } catch (StoreException e) {
                return corruptRead(e.code());
            }
            if (entry == null
                    || entry.tombstone()
                    || !entry.key().equals(key)
                    || entry.length() != row.length()
                    || !Arrays.equals(entry.sha(), row.sha())) {
                return corruptRead("Header differs from index");
            }
            ObjectInfo info =
                    new ObjectInfo(
                            key,
                            entry.length(),
                            entry.origin(),
                            Instant.ofEpochMilli(entry.time()));
            if (fullSha) {
                Maintenance.Sound sound =
                        Maintenance.sound(this, file, row.offset(), entry, keyCheck, cancellation);
                if (!sound.valid() || keyCheck && !sound.identity()) {
                    return corruptRead("Entry integrity or identity failed");
                }
                return new ObjectRead(ReadState.PRESENT, info, null, null);
            }
            int length = requested < 0 ? (int) entry.length() : requested;
            if (offset > entry.length() || length > entry.length() - offset) {
                throw new IllegalArgumentException("Range extends past object");
            }
            byte[] result = new byte[length];
            int copied = 0;
            long first = offset / Format.BLOCK,
                    last = length == 0 ? first - 1 : (offset + length - 1) / Format.BLOCK;
            for (long block = first; block <= last; block++) {
                cancellation.check();
                long blockAt = block * Format.BLOCK;
                int count = (int) Math.min(Format.BLOCK, entry.length() - blockAt);
                byte[] bytes = Format.read(file, row.offset() + 96 + blockAt, count);
                byte[] check = Format.read(file, row.offset() + 96 + entry.length() + 4 * block, 4);
                if (bytes.length != count
                        || check.length != 4
                        || Format.le(check).getInt() != (int) Format.crc(bytes)) {
                    return corruptRead("Block CRC mismatch");
                }
                int from = (int) Math.max(0, offset - blockAt),
                        take = Math.min(count - from, length - copied);
                System.arraycopy(bytes, from, result, copied, take);
                copied += take;
            }
            if (requested < 0 && entry.length() <= SessionHashes.MAX_LENGTH) {
                SessionHashes.Check check =
                        sessionHashes.begin(SessionHashes.Identity.of(lease.identity(), row));
                if (check != null) {
                    if (wholeShaProbe != null) {
                        wholeShaProbe.accept(key);
                    }
                    if (!Arrays.equals(Format.hash("SHA-256", result), entry.sha())) {
                        return corruptRead("SHA-256 mismatch");
                    }
                    sessionHashes.verified(check);
                }
            }
            // Empty range probes and maintenance verification are not payload reads.
            if (requested < 0 || length > 0) {
                trackRead(key);
            }
            return new ObjectRead(ReadState.PRESENT, info, result, null);
        } catch (NoSuchFileException e) {
            if (retry) {
                StoreIndex.Row current = idx.find(key);
                if (current != null
                        && (current.pack() != row.pack() || current.offset() != row.offset())) {
                    return readCore(
                            idx,
                            key,
                            offset,
                            requested,
                            fullSha,
                            keyCheck,
                            cancellation,
                            false,
                            current);
                }
            }
            return bad(ReadState.LOST, "Pack file missing");
        }
    }

    private ObjectRead corruptRead(String problem) {
        // Reject in-flight old proofs too: repairs must earn a fresh whole-read SHA check.
        sessionHashes.clear();
        return bad(ReadState.CORRUPT, problem);
    }

    static ObjectRead bad(ReadState state, String problem) {
        return new ObjectRead(state, null, null, problem);
    }

    @Override
    public CompletableFuture<List<PutResult>> put(
            List<PutObject> batch, boolean commitNow, Cancellation cancellation) {
        if (kind() == Kind.LIBRARY) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_OWNER_REQUIRED", "Use an owner-bound library view"));
        }
        return putOwned(null, batch, commitNow, cancellation, false);
    }

    /**
     * Like {@link #put}, but if verification refuses any object, none is stored: the refused
     * objects report their own code and the rest report {@code OBJECT_BATCH_REFUSED}. A caller
     * whose later objects reference earlier ones can then rely on a put keeping only an ordered
     * prefix.
     */
    public CompletableFuture<List<PutResult>> putUnlessRefused(
            List<PutObject> batch, boolean commitNow, Cancellation cancellation) {
        if (kind() == Kind.LIBRARY) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_OWNER_REQUIRED", "Use an owner-bound library view"));
        }
        return putOwned(null, batch, commitNow, cancellation, true);
    }

    CompletableFuture<List<PutResult>> putOwned(
            LibraryOwnership.Claim owner,
            List<PutObject> batch,
            boolean commitNow,
            Cancellation cancellation) {
        return putOwned(owner, batch, commitNow, cancellation, false);
    }

    CompletableFuture<List<PutResult>> putOwned(
            LibraryOwnership.Claim owner,
            List<PutObject> batch,
            boolean commitNow,
            Cancellation cancellation,
            boolean unlessRefused) {
        List<PutObject> owned = List.copyOf(batch);
        long bytes = 0;
        for (PutObject p : owned) {
            bytes += p.data().length;
        }
        if (owned.size() > 4096 || bytes > 256L << 20) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_LIMIT", "Put batch exceeds 4096 objects or 256 MiB"));
        }
        return enqueuePut(bytes, () -> owned, commitNow, cancellation, owner, unlessRefused);
    }

    private CompletableFuture<List<PutResult>> enqueuePut(
            long bytes,
            java.util.function.Supplier<List<PutObject>> inputs,
            boolean commitNow,
            Cancellation cancellation) {
        return enqueuePut(bytes, inputs, commitNow, cancellation, null, false);
    }

    private CompletableFuture<List<PutResult>> enqueuePut(
            long bytes,
            java.util.function.Supplier<List<PutObject>> inputs,
            boolean commitNow,
            Cancellation cancellation,
            LibraryOwnership.Claim owner,
            boolean unlessRefused) {
        CompletableFuture<List<PreparedPut>> prepared = new CompletableFuture<>();
        Runnable preparation =
                () ->
                        Async.submit(
                                        verificationWorkers,
                                        () -> {
                                            var observer = verificationProbe;
                                            try {
                                                if (observer != null) {
                                                    observer.accept(true);
                                                }
                                                List<PutObject> owned = inputs.get();
                                                long actualBytes = 0;
                                                for (PutObject item : owned) {
                                                    actualBytes += item.data().length;
                                                }
                                                if (owned.size() > 4096
                                                        || actualBytes > 256L << 20) {
                                                    throw new StoreException(
                                                            "OBJECT_STORE_LIMIT",
                                                            "Put batch exceeds 4096 objects or 256"
                                                                    + " MiB");
                                                }
                                                List<PreparedPut> result = new ArrayList<>();
                                                var refuse = refusalProbe;
                                                for (PutObject item : owned) {
                                                    String code =
                                                            refuse == null
                                                                    ? null
                                                                    : refuse.apply(item.key());
                                                    result.add(
                                                            code == null
                                                                    ? PreparedPut.prepare(
                                                                            verifier,
                                                                            item,
                                                                            cancellation)
                                                                    : new PreparedPut(
                                                                            item,
                                                                            Verification.refusal(
                                                                                    code),
                                                                            null,
                                                                            null,
                                                                            null));
                                                }
                                                return List.copyOf(result);
                                            } finally {
                                                if (observer != null) {
                                                    observer.accept(false);
                                                }
                                            }
                                        })
                                .whenComplete(
                                        (result, error) -> {
                                            if (error == null) {
                                                prepared.complete(result);
                                            } else {
                                                prepared.completeExceptionally(error);
                                            }
                                        });
        return finish(
                write(
                        bytes,
                        cancellation,
                        true,
                        commitNow,
                        () -> {
                            List<CompletableFuture<Void>> commits = new ArrayList<>();
                            List<PutResult> results = new ArrayList<>();
                            List<PreparedPut> proofs = prepared.join();
                            if (unlessRefused
                                    && proofs.stream().anyMatch(proof -> proof.refusal() != null)) {
                                // Nothing is appended, so no later object commits without it.
                                for (PreparedPut proof : proofs) {
                                    results.add(
                                            new PutResult(
                                                    proof.input().key(),
                                                    PutOutcome.REFUSED,
                                                    proof.refusal() != null
                                                            ? proof.refusal().code()
                                                            : "OBJECT_BATCH_REFUSED"));
                                }
                                return new Committed<>(
                                        List.copyOf(results),
                                        CompletableFuture.completedFuture(null));
                            }
                            for (PreparedPut proof : proofs) {
                                PutObject p = proof.input();
                                Refusal refusal = proof.refusal();
                                if (refusal != null) {
                                    results.add(
                                            new PutResult(
                                                    p.key(), PutOutcome.REFUSED, refusal.code()));
                                    continue;
                                }
                                if (cancellation.isCanceled()) {
                                    results.add(new PutResult(p.key(), PutOutcome.CANCELED, null));
                                    continue;
                                }
                                if (owner != null
                                        && pending != null
                                        && pending.claims.size() >= PendingWrites.MAX_OBJECTS) {
                                    commitPending();
                                }
                                if (present(p.key())) {
                                    if (owner != null) {
                                        commits.add(
                                                claimExisting(owner, p.key(), p.declaredLength()));
                                    }
                                    results.add(
                                            new PutResult(
                                                    p.key(), PutOutcome.ALREADY_PRESENT, null));
                                    continue;
                                }
                                History.Entry history = proof.history();
                                if (history != null && !present(history.document())) {
                                    results.add(
                                            new PutResult(
                                                    p.key(),
                                                    PutOutcome.REFUSED,
                                                    "OBJECT_HISTORY_DOCUMENT_MISSING"));
                                    continue;
                                }
                                commits.add(
                                        append(
                                                p.key(),
                                                p.declaredLength(),
                                                p.origin(),
                                                false,
                                                p.data(),
                                                null,
                                                history,
                                                proof,
                                                owner));
                                results.add(new PutResult(p.key(), PutOutcome.STORED, null));
                            }
                            return new Committed<>(
                                    List.copyOf(results),
                                    CompletableFuture.allOf(
                                            commits.toArray(CompletableFuture[]::new)));
                        },
                        preparation,
                        prepared));
    }

    /** Append to the active pack; puts share a durability future until their group commits. */
    CompletableFuture<Void> append(
            ObjectKey key,
            long length,
            Origin origin,
            boolean tombstone,
            byte[] bytes,
            Path source,
            History.Entry history)
            throws IOException {
        return append(key, length, origin, tombstone, bytes, source, history, null, null);
    }

    CompletableFuture<Void> appendOwned(
            ObjectKey key, long length, Origin origin, Path source, LibraryOwnership.Claim owner)
            throws IOException {
        return append(key, length, origin, false, null, source, null, null, owner);
    }

    CompletableFuture<Void> claim(
            LibraryOwnership.Claim owner, ObjectKey key, long length, Cancellation token) {
        return finish(
                write(
                        0,
                        token,
                        true,
                        true,
                        () -> new Committed<>((Void) null, claimExisting(owner, key, length))));
    }

    CompletableFuture<Void> claimExisting(
            LibraryOwnership.Claim owner, ObjectKey key, long length) {
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (pending != null && Boolean.TRUE.equals(pending.keys.get(key))) {
            LibraryOwnership.Attachment attachment =
                    new LibraryOwnership.Attachment(owner, key, length);
            if (!pending.claims.contains(attachment)) {
                pending.claims.add(attachment);
            }
            return pending.committed;
        }
        if (!LibraryOwnership.owned(database, owner, key)) {
            // Same WAL as the object rows and every later reader of this claim: NORMAL suffices.
            database.transaction(
                    false, () -> LibraryOwnership.attach(database, owner, key, length));
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> append(
            ObjectKey key,
            long length,
            Origin origin,
            boolean tombstone,
            byte[] bytes,
            Path source,
            History.Entry history,
            PreparedPut prepared,
            LibraryOwnership.Claim owner)
            throws IOException {
        long size = Format.entrySize(length);
        int number = activePack(size);
        Object[] pack =
                database.query("SELECT committed_length,live_bytes FROM packs WHERE seq=?", number)
                        .getFirst();
        long start = pending == null ? (long) pack[0] : pending.end;
        long sequence = index.next + (pending == null ? 0 : pending.entries.size());
        Format.Entry entry;
        boolean handedOff = false;
        try (FileChannel file = FileChannel.open(packPath(number), READ, WRITE)) {
            MessageDigest sha = prepared == null ? Format.digest("SHA-256") : null;
            byte[] table =
                    prepared == null ? new byte[Format.blocks(length) * 4] : prepared.table();
            try {
                atWrite(WritePoint.ENTRY_STARTED, key);
                if (source == null) {
                    byte[] content = tombstone ? new byte[0] : bytes;
                    if (writeProbe == null) {
                        io.write(file, packPath(number), start + 96, content);
                    } else if (content.length > 0) {
                        int first = Math.max(1, content.length / 2);
                        io.write(
                                file,
                                packPath(number),
                                start + 96,
                                Arrays.copyOfRange(content, 0, first));
                        atWrite(WritePoint.PAYLOAD_PARTIAL, key);
                        io.write(
                                file,
                                packPath(number),
                                start + 96 + first,
                                Arrays.copyOfRange(content, first, content.length));
                    }
                    if (prepared == null) {
                        sha.update(content);
                        for (int block = 0; block < Format.blocks(length); block++) {
                            int startAt = block * Format.BLOCK;
                            int count = Math.min(Format.BLOCK, content.length - startAt);
                            Format.le(table)
                                    .putInt(block * 4, (int) Format.crc(content, startAt, count));
                        }
                    }
                } else {
                    ObjectCheck check = verifier.begin(key, length);
                    try (FileChannel input = openStaged(source)) {
                        if (stagedLength(input) != length) {
                            throw new StoreException(
                                    "OBJECT_SIZE_MISMATCH", "Staged length changed");
                        }
                        for (int block = 0; block < Format.blocks(length); block++) {
                            byte[] data =
                                    readStaged(
                                            input,
                                            (long) block * Format.BLOCK,
                                            (int)
                                                    Math.min(
                                                            Format.BLOCK,
                                                            length - (long) block * Format.BLOCK));
                            Refusal r = check.append(data);
                            if (r != null) {
                                throw new StoreException(r.code(), r.message());
                            }
                            sha.update(data);
                            Format.le(table).putInt(block * 4, (int) Format.crc(data));
                            io.write(
                                    file,
                                    packPath(number),
                                    start + 96 + (long) block * Format.BLOCK,
                                    data);
                            if (block == 0) {
                                atWrite(WritePoint.PAYLOAD_PARTIAL, key);
                            }
                        }
                        Refusal r = check.complete();
                        if (r != null) {
                            throw new StoreException(r.code(), r.message());
                        }
                    }
                }
                atWrite(WritePoint.PAYLOAD_WRITTEN, key);
                entry =
                        new Format.Entry(
                                key,
                                length,
                                prepared == null ? sha.digest() : prepared.sha(),
                                now(),
                                origin,
                                sequence,
                                tombstone);
                io.write(file, packPath(number), start + 96 + length, table);
                atWrite(WritePoint.TABLE_WRITTEN, key);
                io.write(file, packPath(number), start, entry.header());
                atWrite(WritePoint.HEADER_WRITTEN, key);
                if (grouping) {
                    if (pending == null) {
                        pending = new PendingWrites(number, start, commitClock.getAsLong());
                    }
                    PendingWrites batch = pending;
                    pending.add(entry, history);
                    if (owner != null && !tombstone) {
                        LibraryOwnership.Attachment attachment =
                                new LibraryOwnership.Attachment(owner, key, length);
                        if (!pending.claims.contains(attachment)) {
                            pending.claims.add(attachment);
                        }
                    }
                    handedOff = true;
                    if (!tombstone && pending.full()) {
                        commitPending();
                    }
                    return batch.committed;
                }
                io.sync(file, packPath(number));
                atWrite(WritePoint.PACK_SYNCED, key);
                Format.Entry committed = entry;
                // NORMAL after the pack fsync, as in commitPending.
                database.transaction(
                        false,
                        () -> {
                            applyEntry(number, start, committed, history);
                            if (owner != null && !tombstone) {
                                LibraryOwnership.attach(database, owner, key, length);
                            }
                            index.save();
                            atWrite(WritePoint.INDEX_UPDATED, key);
                        });
            } catch (Throwable error) {
                if (grouping) {
                    // A threshold commit already performed its rollback; do not cut twice.
                    if (pending != null) {
                        throw abortPending(error, number, pending.start);
                    } else if (!handedOff) {
                        throw abortPending(error, number, start);
                    }
                    throw translate(error, "OBJECT_STORE_WRITE_FAILED");
                }
                if (error instanceof NativeSqlite.TransactionFailure transaction
                        && transaction.outcome != NativeSqlite.Outcome.ROLLED_BACK) {
                    writeFailure = storeFailure(error);
                    throw writeFailure;
                }
                try {
                    io.truncate(file, packPath(number), start);
                    io.sync(file, packPath(number));
                    database.uncancelled(index::load);
                } catch (Throwable cut) {
                    error.addSuppressed(cut);
                    writeFailure = storeFailure(error);
                    throw writeFailure;
                }
                if (source != null
                        && error instanceof StoreException s
                        && !s.code().equals("OBJECT_STORE_WRITE_FAILED")) {
                    throw s;
                }
                throw translate(error, "OBJECT_STORE_WRITE_FAILED");
            }
            // Outside rollback scope: after commit, the entry must never be truncated.
            atWrite(WritePoint.INDEX_COMMITTED, key);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static FileChannel openStaged(Path path) {
        try {
            return FileChannel.open(path, READ);
        } catch (IOException e) {
            throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot open staged file", e);
        }
    }

    private static long stagedLength(FileChannel file) {
        try {
            return file.size();
        } catch (IOException e) {
            throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot inspect staged file", e);
        }
    }

    private static byte[] readStaged(FileChannel file, long offset, int count) {
        try {
            byte[] bytes = Format.read(file, offset, count);
            if (bytes.length != count) {
                throw new StoreException("OBJECT_SIZE_MISMATCH", "Staged file is short");
            }
            return bytes;
        } catch (IOException e) {
            throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot read staged file", e);
        }
    }

    int activePack(long entrySize) throws IOException {
        if (pending != null) {
            if (pending.end + entrySize <= options.maxPackBytes()) {
                return pending.pack;
            }
            commitPending();
        }
        List<Object[]> active =
                database.query("SELECT seq,committed_length,live_bytes FROM packs WHERE state=0");
        if (!active.isEmpty()) {
            Object[] p = active.getFirst();
            long committed = (long) p[1];
            if (committed == 64 || committed + entrySize <= options.maxPackBytes()) {
                return Math.toIntExact((long) p[0]);
            }
        }
        int next = allocatePack();
        Path path = packPath(next);
        try (FileChannel file = FileChannel.open(path, CREATE_NEW, WRITE)) {
            io.write(file, path, 0, Format.pack(next, index.id, now(), options.kind()));
            io.sync(file, path);
        }
        io.syncDirectory(packs);
        database.transaction(
                true,
                () -> {
                    if (!active.isEmpty()) {
                        Object[] p = active.getFirst();
                        index.pack(Math.toIntExact((long) p[0]), 1, (long) p[1], (long) p[2]);
                    }
                    index.pack(next, 0, 64, 0);
                });
        return next;
    }

    @Override
    public CompletableFuture<List<DeleteResult>> delete(
            List<ObjectKey> keys, Cancellation cancellation) {
        List<ObjectKey> owned = List.copyOf(keys);
        if (owned.size() > 4096) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_LIMIT", "Delete batch exceeds 4096"));
        }
        return write(
                18L * owned.size(),
                cancellation,
                true,
                true,
                () -> {
                    commitPending();
                    List<DeleteResult> result = new ArrayList<>();
                    for (ObjectKey key : owned) {
                        cancellation.check();
                        if (key.type() == 5 || key.type() == 6) {
                            result.add(
                                    new DeleteResult(
                                            key, DeleteOutcome.REFUSED, "OBJECT_DELETE_REFUSED"));
                        } else if (!present(key)) {
                            result.add(new DeleteResult(key, DeleteOutcome.ABSENT, null));
                        } else {
                            append(key, 0, Origin.UNKNOWN, true, null, null, null);
                            result.add(new DeleteResult(key, DeleteOutcome.DELETED, null));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<PutResult>> appendHistory(
            History.Observation observation, Cancellation cancellation) {
        if (kind() == Kind.LIBRARY) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_OWNER_REQUIRED", "Use an owner-bound library view"));
        }
        History.validateObservation(observation);
        // Reserve both retained observation bytes and the generated owned PutObject copy.
        long retained = 2L * observation.replyLength() + 1136;
        return enqueuePut(retained, () -> History.objects(observation), true, cancellation);
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation cancellation) {
        return reading(
                cancellation,
                idx -> {
                    StringBuilder sql = new StringBuilder("SELECT * FROM history WHERE 1=1");
                    List<Object> args = new ArrayList<>();
                    if (query.product() != null) {
                        sql.append(" AND product=?");
                        args.add(query.product());
                    }
                    if (query.region() != null) {
                        sql.append(" AND region=?");
                        args.add(query.region());
                    }
                    if (query.kind() != null) {
                        sql.append(" AND kind=?");
                        args.add(query.kind());
                    }
                    if (query.after() != null) {
                        sql.append(" AND (observed_at>? OR (observed_at=? AND record>?))");
                        args.add(query.after().observedAt().toEpochMilli());
                        args.add(query.after().observedAt().toEpochMilli());
                        args.add(query.after().record().bytes());
                    }
                    sql.append(" ORDER BY observed_at,record LIMIT ?");
                    args.add(query.limit());
                    List<History.Entry> result = new ArrayList<>();
                    History.Entry previous = query.after();
                    for (Object[] row : idx.db.query(sql.toString(), args.toArray())) {
                        cancellation.check();
                        History.Entry e = StoreIndex.history(row);
                        if (previous != null
                                && (e.observedAt().isBefore(previous.observedAt())
                                        || e.observedAt().equals(previous.observedAt())
                                                && e.record().compareTo(previous.record()) <= 0)) {
                            throw new StoreException(
                                    "OBJECT_STORE_INDEX_UNREADABLE",
                                    "History page does not advance");
                        }
                        result.add(e);
                        previous = e;
                    }
                    return List.copyOf(result);
                });
    }

    /** Bounded local index lookup; absent entries use -1 and payloads are never read. */
    public CompletableFuture<List<Long>> storedLengths(List<ObjectKey> keys, Cancellation token) {
        if (keys.size() > 4096) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("At most 4096 keys"));
        }
        var owned = List.copyOf(keys);
        return reading(
                token,
                idx -> {
                    var lengths = new ArrayList<Long>();
                    idx.db.exec("BEGIN");
                    try {
                        for (var key : owned) {
                            token.check();
                            var row = idx.find(key);
                            lengths.add(row == null ? -1L : row.length());
                        }
                    } finally {
                        idx.db.uncancelled(() -> idx.db.exec("ROLLBACK"));
                    }
                    return List.copyOf(lengths);
                });
    }

    public CompletableFuture<Stats> stats() {
        return reading(
                Cancellation.NONE,
                idx -> {
                    idx.db.exec("BEGIN");
                    try {
                        idx.load();
                        Object[] p =
                                idx.db
                                        .query(
                                                "SELECT"
                                                        + " count(*),coalesce(sum(committed_length),0),coalesce(sum(live_bytes),0)"
                                                        + " FROM packs WHERE state<2")
                                        .getFirst();
                        return new Stats(
                                (long) p[0],
                                idx.objectCount,
                                idx.historyCount,
                                (long) p[1],
                                (long) p[2],
                                Instant.ofEpochMilli(idx.created));
                    } finally {
                        idx.db.exec("ROLLBACK");
                    }
                });
    }

    @Override
    public CompletableFuture<ObjectStaging> stage(
            ObjectKey key, long declaredLength, Origin origin, Cancellation cancellation) {
        if (kind() == Kind.LIBRARY) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_OWNER_REQUIRED", "Use an owner-bound library view"));
        }
        return stageOwned(null, key, declaredLength, origin, cancellation);
    }

    CompletableFuture<ObjectStaging> stageOwned(
            LibraryOwnership.Claim owner,
            ObjectKey key,
            long declaredLength,
            Origin origin,
            Cancellation cancellation) {
        return write(
                0,
                cancellation,
                () -> {
                    cancellation.check();
                    Staging result = Staging.open(this, key, declaredLength, origin, cancellation);
                    result.owner = owner;
                    return result;
                });
    }

    @Override
    public CompletableFuture<ScanReport> scan(Cancellation cancellation) {
        return write(0, cancellation, () -> Maintenance.scan(this, cancellation));
    }

    @Override
    public CompletableFuture<VerifyReport> verify(boolean full, Cancellation cancellation) {
        return reading(cancellation, idx -> Maintenance.verify(this, idx, full, cancellation));
    }

    @Override
    public CompletableFuture<CompactReport> compact(
            double fraction, int maxPacks, Cancellation cancellation) {
        if (!(fraction >= 0 && fraction <= 1) || maxPacks < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid compaction bounds"));
        }
        return write(
                0, cancellation, () -> Maintenance.compact(this, fraction, maxPacks, cancellation));
    }

    @Override
    public CompletableFuture<ScrubReport> scrub(boolean full, Cancellation cancellation) {
        return reading(
                        cancellation,
                        idx -> {
                            String problem =
                                    idx.fullCheck(cancellation)
                                            ? null
                                            : "Index integrity or totals failed";
                            return problem;
                        })
                .thenCompose(
                        problem -> {
                            if (problem == null || readOnly) {
                                return verify(full, cancellation)
                                        .thenApply(
                                                report -> new ScrubReport(problem, false, report));
                            }
                            return scan(cancellation)
                                    .thenCompose(ignored -> verify(full, cancellation))
                                    .thenApply(report -> new ScrubReport(problem, true, report));
                        });
    }

    /**
     * Drain accepted work, mark clean with FULL durability, checkpoint, then release ownership.
     * Never blocks caller.
     */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lifecycle) {
            if (closing != null) {
                return closing;
            }
            accepting = false;
            if (readDayWakeup != null) {
                readDayWakeup.cancel();
                readDayWakeup = null;
            }
            writer.shutdown();
            readers.shutdown();
            closing =
                    Async.submit(
                            command -> Thread.ofVirtual().name("vimi-store-close").start(command),
                            () -> {
                                Throwable failure = null;
                                try {
                                    while (!writer.awaitTermination(1, TimeUnit.SECONDS)) {}
                                    verificationWorkers.shutdown();
                                    while (!verificationWorkers.awaitTermination(
                                            1, TimeUnit.SECONDS)) {}
                                    while (!readers.awaitTermination(1, TimeUnit.SECONDS)) {}
                                    for (Staging staged : List.copyOf(stagings.values())) {
                                        staged.release(false);
                                    }
                                    if (database != null && writeFailure == null) {
                                        try {
                                            flushReadDays();
                                            database.transaction(
                                                    true,
                                                    () -> {
                                                        index.clean = 1;
                                                        index.save();
                                                    });
                                            database.exec("PRAGMA wal_checkpoint(TRUNCATE)");
                                        } catch (Throwable error) {
                                            writeFailure = storeFailure(error);
                                            try {
                                                database.transaction(
                                                        true,
                                                        () -> {
                                                            index.clean = 0;
                                                            index.save();
                                                        });
                                            } catch (Throwable reset) {
                                                error.addSuppressed(reset);
                                            }
                                            throw writeFailure;
                                        }
                                    }
                                    return null;
                                } catch (IOException | NativeSqlite.Failure error) {
                                    RuntimeException translated =
                                            translate(error, "OBJECT_STORE_WRITE_FAILED");
                                    failure = translated;
                                    throw translated;
                                } catch (Exception | Error error) {
                                    failure = error;
                                    throw error;
                                } finally {
                                    try {
                                        release();
                                    } catch (RuntimeException | Error cleanup) {
                                        if (failure == null) {
                                            throw cleanup;
                                        }
                                        failure.addSuppressed(cleanup);
                                    }
                                }
                            });
            return closing;
        }
    }

    private void release() {
        sessionHashes.clear();
        readDays.clear();
        writer.shutdown();
        readers.shutdown();
        verificationWorkers.shutdown();
        try {
            if (database != null) {
                database.close();
            }
        } finally {
            try {
                packReaders.close();
            } catch (IOException error) {
                throw translate(error, "OBJECT_STORE_WRITE_FAILED");
            } finally {
                if (lock != null) {
                    lock.close();
                }
            }
        }
    }
}
