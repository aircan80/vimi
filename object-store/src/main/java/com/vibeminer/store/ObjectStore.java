package com.vibeminer.store;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static java.nio.file.StandardOpenOption.*;
import com.vibeminer.store.StoreTypes.*;

/** Layer 1 format-1 store. Public operations enqueue work; no GUI or network dependencies. */
public final class ObjectStore implements ObjectReader, ObjectWriter, HistoryLog, StoreMaintenance {
    public static final String DEFAULT_FOLDER_NAME = "cdn-store-v1";
    private static final ThreadPoolExecutor OPENERS = Async.pool("vimi-store-open", 2);
    final Path folder, packs, staging, indexPath;
    final Options options;
    final boolean readOnly;
    final Verification verifier;
    final ThreadPoolExecutor writer = Async.pool("vimi-store-writer", 1);
    final ThreadPoolExecutor readers = Async.pool("vimi-store-read", 8);
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
    // Package-private, instance-scoped test seam. No property, public option or global hook.
    java.util.function.BiConsumer<WritePoint, ObjectKey> writeProbe;
    java.util.function.Consumer<MaintenancePoint> maintenanceProbe;
    void atMaintenance(MaintenancePoint point) { if (maintenanceProbe != null) maintenanceProbe.accept(point); }
    private void atWrite(WritePoint point, ObjectKey key) { if (writeProbe != null) writeProbe.accept(point, key); }

    private ObjectStore(Path path, Options options, boolean readOnly) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Store folder must be absolute");
        this.folder = path.normalize(); this.options = options; this.readOnly = readOnly;
        packs = folder.resolve("packs"); staging = folder.resolve("staging"); indexPath = folder.resolve("objects.sqlite3");
        verifier = options.kind() == Kind.CDN ? Verification.CDN : Verification.GENERATED;
    }
    public static CompletableFuture<ObjectStore> open(Path folder) { return open(folder, new Options()); }
    public static CompletableFuture<ObjectStore> open(Path folder, Options options) {
        return open(folder, options, null);
    }
    // Used only by same-package tests to observe rebuilds before open completes.
    static CompletableFuture<ObjectStore> open(Path folder, Options options, java.util.function.Consumer<MaintenancePoint> probe) {
        return Async.submit(OPENERS, () -> {
            ObjectStore store = new ObjectStore(folder, options, false);
            store.maintenanceProbe = probe;
            try { store.openCore(); return store; } catch (Throwable e) { store.release(); throw translate(e, "OBJECT_STORE_INDEX_UNREADABLE"); }
        });
    }
    public static CompletableFuture<ReadOnlyStore> openReadOnly(Path folder, Options options) {
        return Async.submit(OPENERS, () -> {
            ObjectStore store = new ObjectStore(folder, options, true);
            try { store.openCore(); return new ReadOnlyStore(store); } catch (Throwable e) { store.release(); throw translate(e, "OBJECT_STORE_INDEX_UNREADABLE"); }
        });
    }
    public Path folder() { return folder; }
    public Kind kind() { return options.kind(); }
    public OpenReport openReport() { return openReport; }
    long now() { return options.clock().millis(); }
    Path packPath(int number) { return packs.resolve(String.format(Locale.ROOT, "pack-%06d.vmp", number)); }
    NavigableMap<Integer, Path> diskPacks() throws IOException {
        NavigableMap<Integer, Path> files = new TreeMap<>();
        if (!Files.isDirectory(packs)) return files;
        try (var paths = Files.list(packs)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.matches("pack-[0-9]{6}\\.vmp")) {
                    int number = Integer.parseInt(name.substring(5, 11)); if (number > 0) files.put(number, path);
                }
            }
        }
        return files;
    }
    int allocatePack() {
        if (nextPack > 999999) throw new StoreException("OBJECT_STORE_LIMIT", "Pack number limit reached");
        return nextPack++;
    }
    private void openCore() throws IOException {
        if (!readOnly) {
            Files.createDirectories(packs);
            lock = new WriterLock(folder.resolve("writer.lock"));
        } else if (!Files.exists(indexPath)) throw new StoreException("OBJECT_STORE_MISSING", "No complete index");
        NavigableMap<Integer, Path> files = diskPacks();
        if (!files.isEmpty()) nextPack = files.lastKey() + 1;
        byte[] packId = null; boolean readable = false;
        // Inspect all pack headers before modifying any index or pack bytes.
        for (var item : files.entrySet()) {
            try (FileChannel file = FileChannel.open(item.getValue(), READ)) {
                Format.Pack p = Format.pack(Format.read(file, 0, 64), item.getKey(), options.kind());
                if (p != null) {
                    if (packId != null && !Arrays.equals(packId, p.id())) throw new StoreException("OBJECT_STORE_FOREIGN_PACK", "Packs belong to different stores");
                    packId = p.id(); readable = true;
                }
            }
        }
        boolean valid = false, full = false;
        if (Files.exists(indexPath)) {
            try (NativeSqlite check = openReader()) {
                StoreIndex candidate = new StoreIndex(check);
                long version = check.scalar("PRAGMA user_version"); boolean damaged = candidate.damagedVersion(version);
                if (!damaged && version > 1) throw Format.newer();
                if (readOnly && !damaged && version < 1) throw new StoreException("OBJECT_STORE_MISSING", "No complete index");
                if (version == 1 || damaged) {
                    long kind = check.scalar("PRAGMA application_id"); if (kind > 1) throw Format.newer();
                    if (kind != options.kind().ordinal()) throw Format.mismatch();
                }
                if (version == 1 && candidate.validate(packId, false, Cancellation.NONE)) {
                    full = candidate.clean == 0; valid = !full || candidate.fullCheck(Cancellation.NONE);
                    for (Object[] p : check.query("SELECT seq FROM packs")) nextPack = Math.max(nextPack, Math.toIntExact((long)p[0]) + 1);
                }
                if (immutable && candidate.clean != 1) valid = false;
            } catch (NativeSqlite.Failure | ClassCastException | IllegalArgumentException e) { valid = false; }
        }
        if (readOnly) {
            if (!valid) throw new StoreException("OBJECT_STORE_INDEX_UNREADABLE", "Index failed validation; writer must rebuild it");
            openReport = new OpenReport(0, 0, false, full, List.of()); return;
        }
        boolean rebuilt = !valid;
        List<String> problems = new ArrayList<>();
        if (!valid) {
            Maintenance.preflight(this, files); // Refuse newer entries before deleting/replacing any bytes.
            deleteRebuild();
            if (readable) {
                Path rebuilding = folder.resolve("objects.sqlite3.rebuild");
                try (NativeSqlite db = new NativeSqlite(rebuilding, false)) {
                    StoreIndex target = new StoreIndex(db); target.create(packId, now(), options.kind().ordinal());
                    atMaintenance(MaintenancePoint.REBUILD_CREATED);
                    Maintenance.replay(this, target, files, Cancellation.NONE);
                    atMaintenance(MaintenancePoint.REBUILD_REPLAYED);
                    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
                    atMaintenance(MaintenancePoint.REBUILD_CHECKPOINTED);
                }
                atMaintenance(MaintenancePoint.REBUILD_BEFORE_REPLACE);
                // Keep the previous main index until the atomic switch. An interruption
                // must not create a gap between deleting the old file and installing the new one.
                for (String suffix : List.of("-wal", "-shm")) Files.deleteIfExists(folder.resolve("objects.sqlite3" + suffix));
                Files.move(rebuilding, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                atMaintenance(MaintenancePoint.REBUILD_REPLACED);
                Format.syncDirectory(folder);
            } else {
                deleteIndexFiles();
                for (Path path : files.values()) if (Files.size(path) <= 64) Files.delete(path);
                byte[] id = new byte[16]; new SecureRandom().nextBytes(id);
                try (NativeSqlite db = new NativeSqlite(indexPath, false)) { new StoreIndex(db).create(id, now(), options.kind().ordinal()); }
                rebuilt = Files.exists(indexPath) && !files.isEmpty();
            }
        }
        database = new NativeSqlite(indexPath, false); database.exec("PRAGMA journal_mode=WAL"); database.exec("PRAGMA synchronous=NORMAL");
        index = new StoreIndex(database); index.load();
        for (Object[] p : database.query("SELECT seq FROM packs")) nextPack = Math.max(nextPack, Math.toIntExact((long)p[0]) + 1);
        // Protect newer active tails before clearing the clean-close marker.
        for (Object[] row : database.query("SELECT seq,committed_length FROM packs WHERE state=0")) {
            Path path = packPath(Math.toIntExact((long)row[0]));
            if (Files.exists(path)) Maintenance.preflightTail(this, path, (long)row[1]);
        }
        if (options.kind() == Kind.CDN) Files.createDirectories(staging);
        deleteRebuild();
        database.transaction(true, () -> { index.clean = 0; index.save(); });
        long recovered = 0, truncated = 0;
        Set<Integer> listed = new HashSet<>();
        for (Object[] row : database.query("SELECT seq,state,committed_length,live_bytes FROM packs")) {
            int number = Math.toIntExact((long)row[0]), state = Math.toIntExact((long)row[1]); listed.add(number);
            long committed = (long)row[2], live = (long)row[3]; Path path = packPath(number);
            if (state >= 2) { Files.deleteIfExists(path); Format.syncDirectory(packs); database.exec("DELETE FROM packs WHERE seq=?", number); continue; }
            if (!Files.exists(path)) {
                problems.add("Missing pack " + number);
                if (state == 0) index.pack(number, 1, committed, live);
            } else if (Files.size(path) < committed || state == 1 && Files.size(path) != committed) {
                problems.add("Pack length differs from committed length: " + number);
                if (state == 0) index.pack(number, 1, committed, live);
            } else if (state == 0 && Files.size(path) > committed) {
                long[] result = Maintenance.recover(this, number, committed, live, problems);
                recovered += result[0]; truncated += result[1];
            }
        }
        for (var item : diskPacks().entrySet()) if (!listed.contains(item.getKey())) {
            if (Files.size(item.getValue()) <= 64) { Files.delete(item.getValue()); Format.syncDirectory(packs); }
            else problems.add("Unlisted pack " + item.getKey());
        }
        if (Files.isDirectory(staging)) try (var paths = Files.list(staging)) {
            for (Path path : paths.toList()) if (path.getFileName().toString().matches("[0-9a-f]{36}\\.part") && Files.getLastModifiedTime(path).toMillis() < now() - 7L * 86400000) Files.delete(path);
        }
        openReport = new OpenReport(recovered, truncated, rebuilt, full, problems);
    }
    NativeSqlite openReader() throws IOException {
        if (immutable) return new NativeSqlite(indexPath.toUri() + "?immutable=1", true);
        try { return new NativeSqlite(indexPath, true); }
        catch (NativeSqlite.Failure e) {
            Path wal = folder.resolve("objects.sqlite3-wal");
            if (readOnly && (e.result & 255) == 14 || readOnly && (e.result & 255) == 8) {
                if (!Files.isWritable(folder) && (!Files.exists(wal) || Files.size(wal) == 0)) {
                    immutable = true; return new NativeSqlite(indexPath.toUri() + "?immutable=1", true);
                }
            }
            throw e;
        }
    }
    private void deleteRebuild() throws IOException {
        for (String suffix : List.of("", "-wal", "-shm")) Files.deleteIfExists(folder.resolve("objects.sqlite3.rebuild" + suffix));
    }
    private void deleteIndexFiles() throws IOException {
        for (String suffix : List.of("-wal", "-shm", "")) Files.deleteIfExists(folder.resolve("objects.sqlite3" + suffix));
    }
    <T> CompletableFuture<T> write(Async.Work<T> work) {
        synchronized (lifecycle) {
            if (!accepting) return CompletableFuture.failedFuture(closed());
            if (readOnly) return CompletableFuture.failedFuture(new StoreException("OBJECT_STORE_READ_ONLY", "Store is read-only"));
            if (writeFailure != null) return CompletableFuture.failedFuture(writeFailure);
            return Async.submit(writer, () -> { if (writeFailure != null) throw writeFailure; return work.run(); });
        }
    }
    <T> CompletableFuture<T> reading(Cancellation cancellation, ReadWork<T> work) {
        synchronized (lifecycle) {
            if (!accepting) return CompletableFuture.failedFuture(closed());
            return Async.submit(readers, () -> {
                cancellation.check();
                try (NativeSqlite db = openReader()) { return work.run(new StoreIndex(db)); }
                catch (NativeSqlite.Failure e) { throw translate(e, "OBJECT_STORE_INDEX_UNREADABLE"); }
            });
        }
    }
    interface ReadWork<T> { T run(StoreIndex index) throws Exception; }
    static StoreException closed() { return new StoreException("OBJECT_STORE_CLOSED", "Store is closed"); }
    static RuntimeException translate(Throwable e, String fallback) {
        if (e instanceof StoreException s) return s;
        if (e instanceof CancellationException c) return c;
        if (e instanceof IllegalArgumentException a) return a;
        String code = e instanceof NativeSqlite.Failure s && (s.result & 255) == 13 || e.getMessage() != null && e.getMessage().contains("No space left") ? "OBJECT_STORE_DISK_FULL" : fallback;
        return new StoreException(code, "Store operation failed", e);
    }
    @Override public CompletableFuture<Boolean> has(ObjectKey key, Cancellation cancellation) {
        return reading(cancellation, i -> i.db.scalar("SELECT count(*) FROM objects WHERE k=?", key.indexBytes()) != 0);
    }
    @Override public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation cancellation) {
        return reading(cancellation, i -> readCore(i, key, 0, -1, false, false, cancellation));
    }
    @Override public CompletableFuture<ObjectRead> readRange(ObjectKey key, long offset, int length, Cancellation cancellation) {
        if (offset < 0 || length < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Negative range"));
        if (length > Format.WHOLE_LIMIT) return CompletableFuture.failedFuture(new StoreException("OBJECT_STORE_LIMIT", "Range exceeds 64 MiB"));
        return reading(cancellation, i -> readCore(i, key, offset, length, false, false, cancellation));
    }
    ObjectRead readCore(StoreIndex idx, ObjectKey key, long offset, int requested, boolean fullSha, boolean keyCheck, Cancellation cancellation) throws IOException {
        StoreIndex.Row row;
        try { row = idx.find(key); } catch (ClassCastException | IllegalArgumentException | ArithmeticException e) { return bad(ReadState.CORRUPT, "Invalid index row"); }
        if (row == null) return bad(ReadState.ABSENT, null);
        if (row.pack() < 1 || row.offset() < 64 || row.length() < 1 || row.length() > Format.MAX_PAYLOAD || row.sha().length != 32) return bad(ReadState.CORRUPT, "Invalid index row");
        if (requested < 0 && row.length() > Format.WHOLE_LIMIT && !fullSha) throw new StoreException("OBJECT_STORE_LIMIT", "Whole read exceeds 64 MiB; use ranges");
        Path path = packPath(row.pack());
        if (!Files.exists(path)) {
            StoreIndex.Row retry = idx.find(key);
            if (retry != null && (retry.pack() != row.pack() || retry.offset() != row.offset())) return readCore(idx, key, offset, requested, fullSha, keyCheck, cancellation);
            return bad(ReadState.LOST, "Pack file missing");
        }
        try (FileChannel file = FileChannel.open(path, READ)) {
            Format.Entry entry;
            try { entry = Format.entry(Format.read(file, row.offset(), 96), options.kind()); }
            catch (StoreException e) { return bad(ReadState.CORRUPT, e.code()); }
            if (entry == null || entry.tombstone() || !entry.key().equals(key) || entry.length() != row.length() || !Arrays.equals(entry.sha(), row.sha())) return bad(ReadState.CORRUPT, "Header differs from index");
            ObjectInfo info = new ObjectInfo(key, entry.length(), entry.origin(), Instant.ofEpochMilli(entry.time()));
            if (fullSha) {
                Maintenance.Sound sound = Maintenance.sound(this, file, row.offset(), entry, keyCheck, cancellation);
                if (!sound.valid() || keyCheck && !sound.identity()) return bad(ReadState.CORRUPT, "Entry integrity or identity failed");
                return new ObjectRead(ReadState.PRESENT, info, null, null);
            }
            int length = requested < 0 ? (int)entry.length() : requested;
            if (offset > entry.length() || length > entry.length() - offset) throw new IllegalArgumentException("Range extends past object");
            byte[] result = new byte[length]; int copied = 0;
            long first = offset / Format.BLOCK, last = length == 0 ? first - 1 : (offset + length - 1) / Format.BLOCK;
            for (long block = first; block <= last; block++) {
                cancellation.check(); long blockAt = block * Format.BLOCK;
                int count = (int)Math.min(Format.BLOCK, entry.length() - blockAt);
                byte[] bytes = Format.read(file, row.offset() + 96 + blockAt, count);
                byte[] check = Format.read(file, row.offset() + 96 + entry.length() + 4 * block, 4);
                if (bytes.length != count || check.length != 4 || Format.le(check).getInt() != (int)Format.crc(bytes)) return bad(ReadState.CORRUPT, "Block CRC mismatch");
                int from = (int)Math.max(0, offset - blockAt), take = Math.min(count - from, length - copied);
                System.arraycopy(bytes, from, result, copied, take); copied += take;
            }
            if (requested < 0 && entry.length() <= 1 << 20 && !Arrays.equals(Format.hash("SHA-256", result), entry.sha())) return bad(ReadState.CORRUPT, "SHA-256 mismatch");
            return new ObjectRead(ReadState.PRESENT, info, result, null);
        } catch (NoSuchFileException e) { return bad(ReadState.LOST, "Pack retired during read; retry"); }
    }
    static ObjectRead bad(ReadState state, String problem) { return new ObjectRead(state, null, null, problem); }
    @Override public CompletableFuture<List<PutResult>> put(List<PutObject> batch, boolean commitNow, Cancellation cancellation) {
        List<PutObject> owned = List.copyOf(batch);
        long bytes = 0; for (PutObject p : owned) bytes += p.data().length;
        if (owned.size() > 4096 || bytes > 256L << 20) return CompletableFuture.failedFuture(new StoreException("OBJECT_STORE_LIMIT", "Put batch exceeds 4096 objects or 256 MiB"));
        return write(() -> {
            List<PutResult> results = new ArrayList<>();
            for (PutObject p : owned) {
                Refusal refusal = verifier.check(p.key(), p.data(), p.declaredLength());
                if (refusal != null) { results.add(new PutResult(p.key(), PutOutcome.REFUSED, refusal.code())); continue; }
                if (cancellation.isCanceled()) { results.add(new PutResult(p.key(), PutOutcome.CANCELED, null)); continue; }
                if (index.find(p.key()) != null) { results.add(new PutResult(p.key(), PutOutcome.ALREADY_PRESENT, null)); continue; }
                History.Entry history = p.key().type() == 6 ? History.parse(p.data(), p.key()) : null;
                if (history != null && index.find(history.document()) == null) { results.add(new PutResult(p.key(), PutOutcome.REFUSED, "OBJECT_HISTORY_DOCUMENT_MISSING")); continue; }
                append(p.key(), p.declaredLength(), p.origin(), false, p.data(), null, history);
                results.add(new PutResult(p.key(), PutOutcome.STORED, null));
            }
            return List.copyOf(results);
        });
    }
    /** Each operation commits before its future completes. Small writes currently commit individually. */
    void append(ObjectKey key, long length, Origin origin, boolean tombstone, byte[] bytes, Path source, History.Entry history) throws IOException {
        long size = Format.entrySize(length); int number = activePack(size);
        Object[] pack = database.query("SELECT committed_length,live_bytes FROM packs WHERE seq=?", number).getFirst();
        long start = (long)pack[0], live = (long)pack[1]; long sequence = index.next;
        Format.Entry entry;
        try (FileChannel file = FileChannel.open(packPath(number), READ, WRITE)) {
            MessageDigest sha = Format.digest("SHA-256"); byte[] table = new byte[Format.blocks(length) * 4];
            try {
                atWrite(WritePoint.ENTRY_STARTED, key);
                if (source == null) {
                    byte[] content = tombstone ? new byte[0] : bytes;
                    if (writeProbe == null) Format.write(file, start + 96, content);
                    else if (content.length > 0) {
                        int first = Math.max(1, content.length / 2);
                        Format.write(file, start + 96, Arrays.copyOfRange(content, 0, first));
                        atWrite(WritePoint.PAYLOAD_PARTIAL, key);
                        Format.write(file, start + 96 + first, Arrays.copyOfRange(content, first, content.length));
                    }
                    sha.update(content);
                    for (int block = 0; block < Format.blocks(length); block++) Format.le(table).putInt(block * 4, (int)Format.crc(content, block * Format.BLOCK, Math.min(Format.BLOCK, content.length - block * Format.BLOCK)));
                } else {
                    ObjectCheck check = verifier.begin(key, length);
                    try (FileChannel input = openStaged(source)) {
                        if (stagedLength(input) != length) throw new StoreException("OBJECT_SIZE_MISMATCH", "Staged length changed");
                        for (int block = 0; block < Format.blocks(length); block++) {
                            byte[] data = readStaged(input, (long)block * Format.BLOCK, (int)Math.min(Format.BLOCK, length - (long)block * Format.BLOCK));
                            Refusal r = check.append(data); if (r != null) throw new StoreException(r.code(), r.message());
                            sha.update(data); Format.le(table).putInt(block * 4, (int)Format.crc(data)); Format.write(file, start + 96 + (long)block * Format.BLOCK, data);
                            if (block == 0) atWrite(WritePoint.PAYLOAD_PARTIAL, key);
                        }
                        Refusal r = check.complete(); if (r != null) throw new StoreException(r.code(), r.message());
                    }
                }
                atWrite(WritePoint.PAYLOAD_WRITTEN, key);
                entry = new Format.Entry(key, length, sha.digest(), now(), origin, sequence, tombstone);
                Format.write(file, start + 96 + length, table);
                atWrite(WritePoint.TABLE_WRITTEN, key);
                Format.write(file, start, entry.header());
                atWrite(WritePoint.HEADER_WRITTEN, key);
                file.force(true);
                atWrite(WritePoint.PACK_SYNCED, key);
                Format.Entry committed = entry;
                database.transaction(false, () -> {
                    StoreIndex.Row previous = index.find(key);
                    long newLive = live + size;
                    if (tombstone && previous != null) {
                        index.remove(previous);
                        if (previous.pack() == number) newLive -= Format.entrySize(previous.length());
                        else {
                            Object[] p = database.query("SELECT state,committed_length,live_bytes FROM packs WHERE seq=?", previous.pack()).getFirst();
                            index.pack(previous.pack(), Math.toIntExact((long)p[0]), (long)p[1], (long)p[2] - Format.entrySize(previous.length()));
                        }
                    } else if (!tombstone) index.insert(new StoreIndex.Row(key, number, start, length, committed.sha()), history);
                    index.next = sequence + 1; index.pack(number, 0, start + size, newLive); index.save();
                    atWrite(WritePoint.INDEX_UPDATED, key);
                });
            } catch (Throwable error) {
                try { file.truncate(start); file.force(true); index.load(); }
                catch (Throwable cut) { error.addSuppressed(cut); writeFailure = new StoreException("OBJECT_STORE_WRITE_FAILED", "Rollback truncation failed; reopen store", error); }
                if (source != null && error instanceof StoreException s && !s.code().equals("OBJECT_STORE_WRITE_FAILED")) throw s;
                throw translate(error, "OBJECT_STORE_WRITE_FAILED");
            }
            // Outside rollback scope: after commit, the entry must never be truncated.
            atWrite(WritePoint.INDEX_COMMITTED, key);
        }
    }
    private static FileChannel openStaged(Path path) {
        try { return FileChannel.open(path, READ); }
        catch (IOException e) { throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot open staged file", e); }
    }
    private static long stagedLength(FileChannel file) {
        try { return file.size(); }
        catch (IOException e) { throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot inspect staged file", e); }
    }
    private static byte[] readStaged(FileChannel file, long offset, int count) {
        try {
            byte[] bytes = Format.read(file, offset, count);
            if (bytes.length != count) throw new StoreException("OBJECT_SIZE_MISMATCH", "Staged file is short");
            return bytes;
        } catch (IOException e) { throw new StoreException("OBJECT_STAGING_REJECTED", "Cannot read staged file", e); }
    }
    int activePack(long entrySize) throws IOException {
        List<Object[]> active = database.query("SELECT seq,committed_length,live_bytes FROM packs WHERE state=0");
        if (!active.isEmpty()) {
            Object[] p = active.getFirst(); long committed = (long)p[1];
            if (committed == 64 || committed + entrySize <= options.maxPackBytes()) return Math.toIntExact((long)p[0]);
        }
        int next = allocatePack(); Path path = packPath(next);
        try (FileChannel file = FileChannel.open(path, CREATE_NEW, WRITE)) { Format.write(file, 0, Format.pack(next, index.id, now(), options.kind())); file.force(true); }
        Format.syncDirectory(packs);
        database.transaction(true, () -> {
            if (!active.isEmpty()) { Object[] p = active.getFirst(); index.pack(Math.toIntExact((long)p[0]), 1, (long)p[1], (long)p[2]); }
            index.pack(next, 0, 64, 0);
        });
        return next;
    }
    @Override public CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation cancellation) {
        List<ObjectKey> owned = List.copyOf(keys);
        if (owned.size() > 4096) return CompletableFuture.failedFuture(new StoreException("OBJECT_STORE_LIMIT", "Delete batch exceeds 4096"));
        return write(() -> {
            List<DeleteResult> result = new ArrayList<>();
            for (ObjectKey key : owned) {
                cancellation.check();
                if (key.type() == 5 || key.type() == 6) result.add(new DeleteResult(key, DeleteOutcome.REFUSED, "OBJECT_DELETE_REFUSED"));
                else if (index.find(key) == null) result.add(new DeleteResult(key, DeleteOutcome.ABSENT, null));
                else { append(key, 0, Origin.UNKNOWN, true, null, null, null); result.add(new DeleteResult(key, DeleteOutcome.DELETED, null)); }
            }
            return List.copyOf(result);
        });
    }
    @Override public CompletableFuture<List<PutResult>> appendHistory(History.Observation observation, Cancellation cancellation) {
        return put(History.objects(observation), true, cancellation);
    }
    @Override public CompletableFuture<List<History.Entry>> readHistory(History.Query query, Cancellation cancellation) {
        return reading(cancellation, idx -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM history WHERE 1=1"); List<Object> args = new ArrayList<>();
            if (query.product() != null) { sql.append(" AND product=?"); args.add(query.product()); }
            if (query.region() != null) { sql.append(" AND region=?"); args.add(query.region()); }
            if (query.kind() != null) { sql.append(" AND kind=?"); args.add(query.kind()); }
            if (query.after() != null) {
                sql.append(" AND (observed_at>? OR (observed_at=? AND record>?))");
                args.add(query.after().observedAt().toEpochMilli()); args.add(query.after().observedAt().toEpochMilli()); args.add(query.after().record().bytes());
            }
            sql.append(" ORDER BY observed_at,record LIMIT ?"); args.add(query.limit());
            List<History.Entry> result = new ArrayList<>(); History.Entry previous = query.after();
            for (Object[] row : idx.db.query(sql.toString(), args.toArray())) {
                cancellation.check(); History.Entry e = StoreIndex.history(row);
                if (previous != null && (e.observedAt().isBefore(previous.observedAt()) || e.observedAt().equals(previous.observedAt()) && e.record().compareTo(previous.record()) <= 0)) throw new StoreException("OBJECT_STORE_INDEX_UNREADABLE", "History page does not advance");
                result.add(e); previous = e;
            }
            return List.copyOf(result);
        });
    }
    public CompletableFuture<Stats> stats() {
        return reading(Cancellation.NONE, idx -> {
            idx.db.exec("BEGIN");
            try {
                idx.load(); Object[] p = idx.db.query("SELECT count(*),coalesce(sum(committed_length),0),coalesce(sum(live_bytes),0) FROM packs WHERE state<2").getFirst();
                return new Stats((long)p[0], idx.objectCount, idx.historyCount, (long)p[1], (long)p[2], Instant.ofEpochMilli(idx.created));
            } finally { idx.db.exec("ROLLBACK"); }
        });
    }
    @Override public CompletableFuture<ObjectStaging> stage(ObjectKey key, long declaredLength, Origin origin, Cancellation cancellation) {
        return write(() -> { cancellation.check(); return Staging.open(this, key, declaredLength, origin, cancellation); });
    }
    @Override public CompletableFuture<ScanReport> scan(Cancellation cancellation) {
        return write(() -> Maintenance.scan(this, cancellation));
    }
    @Override public CompletableFuture<VerifyReport> verify(boolean full, Cancellation cancellation) {
        return reading(cancellation, idx -> Maintenance.verify(this, idx, full, cancellation));
    }
    @Override public CompletableFuture<CompactReport> compact(double fraction, int maxPacks, Cancellation cancellation) {
        if (!(fraction >= 0 && fraction <= 1) || maxPacks < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid compaction bounds"));
        return write(() -> Maintenance.compact(this, fraction, maxPacks, cancellation));
    }
    @Override public CompletableFuture<ScrubReport> scrub(boolean full, Cancellation cancellation) {
        return reading(cancellation, idx -> {
            String problem = idx.fullCheck(cancellation) ? null : "Index integrity or totals failed";
            return problem;
        }).thenCompose(problem -> {
            if (problem == null || readOnly) return verify(full, cancellation).thenApply(report -> new ScrubReport(problem, false, report));
            return scan(cancellation).thenCompose(ignored -> verify(full, cancellation)).thenApply(report -> new ScrubReport(problem, true, report));
        });
    }
    /** Drain accepted work, mark clean with FULL durability, checkpoint, then release ownership. Never blocks caller. */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lifecycle) {
            if (closing != null) return closing;
            accepting = false; writer.shutdown(); readers.shutdown();
            closing = Async.submit(OPENERS, () -> {
                try {
                    while (!writer.awaitTermination(1, TimeUnit.SECONDS)) { }
                    while (!readers.awaitTermination(1, TimeUnit.SECONDS)) { }
                    for (Staging staged : List.copyOf(stagings.values())) staged.release(false);
                    if (database != null && writeFailure == null) {
                        database.transaction(true, () -> { index.clean = 1; index.save(); });
                        database.exec("PRAGMA wal_checkpoint(TRUNCATE)");
                    }
                    return null;
                } finally { release(); }
            });
            return closing;
        }
    }
    private void release() {
        writer.shutdown(); readers.shutdown();
        try { if (database != null) database.close(); }
        finally { if (lock != null) lock.close(); }
    }
}
