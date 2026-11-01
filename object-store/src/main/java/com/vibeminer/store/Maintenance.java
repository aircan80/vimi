package com.vibeminer.store;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.vibeminer.store.StoreTypes.CompactReport;
import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.ReadState;
import com.vibeminer.store.StoreTypes.ScanReport;
import com.vibeminer.store.StoreTypes.VerifyReport;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Disk-backed replay/copy metadata with bounded Java pages, readers and payload buffers. */
final class Maintenance {
    private Maintenance() {}

    record Sound(boolean valid, boolean identity, History.Entry history) {}

    record Resources(String operation, int openPackHandles, int javaRows) {}

    private static void resources(ObjectStore store, String operation, int handles, int rows) {
        if (store.maintenanceResourcesProbe != null) {
            store.maintenanceResourcesProbe.accept(new Resources(operation, handles, rows));
        }
    }

    static Sound sound(
            ObjectStore s,
            FileChannel file,
            long offset,
            Format.Entry e,
            boolean identity,
            Cancellation cancel)
            throws IOException {
        MessageDigest sha = Format.digest("SHA-256");
        ObjectCheck check =
                identity && !e.tombstone() ? s.verifier.begin(e.key(), e.length()) : null;
        byte[] history =
                e.key().type() == 6 && e.length() <= 1136 ? new byte[(int) e.length()] : null;
        boolean verified = true;
        for (int block = 0; block < Format.blocks(e.length()); block++) {
            cancel.check();
            long at = (long) block * Format.BLOCK;
            int count = (int) Math.min(Format.BLOCK, e.length() - at);
            byte[] bytes = Format.read(file, offset + 96 + at, count);
            byte[] crc = Format.read(file, offset + 96 + e.length() + 4L * block, 4);
            if (bytes.length != count
                    || crc.length != 4
                    || Format.le(crc).getInt() != (int) Format.crc(bytes)) {
                return new Sound(false, false, null);
            }
            sha.update(bytes);
            if (check != null && check.append(bytes) != null) {
                verified = false;
            }
            if (history != null) {
                System.arraycopy(bytes, 0, history, (int) at, count);
            }
        }
        if (check != null && check.complete() != null) {
            verified = false;
        }
        return new Sound(
                MessageDigest.isEqual(sha.digest(), e.sha()),
                verified,
                history == null ? null : History.parse(history, e.key()));
    }

    static void preflight(ObjectStore s, PackFiles files) throws IOException {
        byte[] id = null;
        for (var item : files.entrySet()) {
            try (FileChannel file = FileChannel.open(item.getValue(), READ)) {
                Format.Pack pack = Format.pack(Format.read(file, 0, 64), item.getKey(), s.kind());
                if (pack == null) {
                    continue;
                }
                if (id != null && !Arrays.equals(id, pack.id())) {
                    throw new StoreException(
                            "OBJECT_STORE_FOREIGN_PACK", "Packs have different store ids");
                }
                id = pack.id();
            }
            preflightTail(s, item.getValue(), 64);
        }
    }

    static void preflightTail(ObjectStore s, Path path, long at) throws IOException {
        try (FileChannel file = FileChannel.open(path, READ)) {
            while (at < file.size()) {
                Format.Entry e = Format.entry(Format.read(file, at, 96), s.kind());
                if (e == null || e.size() > file.size() - at) {
                    break;
                }
                at += e.size();
            }
        }
    }

    private static void apply(
            StoreIndex index, int pack, long at, Format.Entry e, History.Entry history) {
        StoreIndex.Row previous = index.find(e.key());
        if (e.tombstone()) {
            if (previous != null) {
                index.remove(previous);
            }
        } else if (previous == null) {
            index.insert(new StoreIndex.Row(e.key(), pack, at, e.length(), e.sha()), history);
        }
    }

    static long[] recover(ObjectStore s, int pack, long start, long live, List<String> problems)
            throws IOException {
        long at = start, recovered = 0;
        try (FileChannel file = FileChannel.open(s.packPath(pack), READ, WRITE)) {
            while (at < file.size()) {
                Format.Entry e = Format.entry(Format.read(file, at, 96), s.kind());
                if (e == null || e.size() > file.size() - at) {
                    break;
                }
                Sound sound = sound(s, file, at, e, true, Cancellation.NONE);
                if (!sound.valid()
                        || !sound.identity()
                        || e.key().type() == 6
                                && (sound.history() == null
                                        || s.index.find(sound.history().document()) == null)) {
                    break;
                }
                long offset = at;
                s.database.transaction(
                        false,
                        () -> {
                            apply(s.index, pack, offset, e, sound.history());
                            s.index.next = Math.max(s.index.next, e.sequence() + 1);
                            s.index.pack(
                                    pack,
                                    0,
                                    offset + e.size(),
                                    liveBytes(s.index, pack, file, offset + e.size()));
                            s.index.save();
                        });
                at += e.size();
                recovered++;
            }
            long truncated = file.size() - at;
            s.io.truncate(file, s.packPath(pack), at);
            s.io.sync(file, s.packPath(pack));
            long end = at;
            s.database.transaction(
                    true,
                    () -> {
                        s.index.pack(pack, 0, end, liveBytes(s.index, pack, file, end));
                        s.index.save();
                    });
            if (truncated > 0) {
                problem(problems, "Truncated tail of pack " + pack + " by " + truncated + " bytes");
            }
            return new long[] {recovered, truncated};
        }
    }

    // Tombstones remain live so rebuilding after compaction cannot resurrect deleted keys.
    private static long liveBytes(StoreIndex index, int pack, FileChannel file, long end) {
        long bytes =
                index.db.scalar(
                        "SELECT coalesce(sum(96+length+4*((length+65535)/65536)),0) FROM objects WHERE pack=?",
                        pack);
        Kind kind = Kind.values()[(int) index.db.scalar("PRAGMA application_id")];
        try {
            for (long at = 64; at < end; ) {
                Format.Entry e = Format.entry(Format.read(file, at, 96), kind);
                if (e == null || e.size() > end - at) {
                    break;
                }
                if (e.tombstone()) {
                    bytes += 96;
                }
                at += e.size();
            }
        } catch (IOException e) {
            throw new StoreException("OBJECT_STORE_WRITE_FAILED", "Cannot measure pack", e);
        }
        return bytes;
    }

    static void problem(List<String> problems, String message) {
        if (problems.size() < 99) {
            problems.add(message);
        } else if (problems.size() == 99) {
            problems.add("Additional problems omitted");
        }
    }

    private static void advance(
            ObjectStore s,
            NativeSqlite db,
            PackReaders<FileChannel> readers,
            int pack,
            long at,
            List<String> problems,
            boolean insert)
            throws IOException {
        Format.Entry entry = null;
        long end = at;
        try (var lease = readers.acquire(pack)) {
            if (lease == null) {
                throw new java.nio.file.NoSuchFileException(s.packPath(pack).toString());
            }
            FileChannel file = lease.handle();
            if (at != file.size()) {
                entry = Format.entry(Format.read(file, at, 96), s.kind());
                if (entry == null || entry.size() > file.size() - at) {
                    problem(problems, "Torn header in pack " + pack + " at " + at);
                    entry = null;
                } else {
                    end = at + entry.size();
                }
            }
            resources(s, "replay", readers.state().openHandles(), 1);
        }
        if (insert) {
            db.exec(
                    "INSERT INTO temp.replay_heads VALUES(?,?,?,?,?,?)",
                    pack,
                    at,
                    end,
                    entry == null ? null : entry.sequence(),
                    entry == null ? null : entry.header(),
                    end);
        } else {
            db.exec(
                    "UPDATE temp.replay_heads SET at=?,end=?,seq=?,header=?,valid_end=? WHERE pack=?",
                    at,
                    end,
                    entry == null ? null : entry.sequence(),
                    entry == null ? null : entry.header(),
                    end,
                    pack);
        }
    }

    static ScanReport replay(ObjectStore s, StoreIndex index, PackFiles files, Cancellation cancel)
            throws IOException {
        List<String> problems = new ArrayList<>();
        long entries = 0,
                duplicates = 0,
                tombstones = 0,
                corrupt = 0,
                unverifiable = 0,
                next = 1,
                packs = 0;
        try (MaintenanceSpool spool = MaintenanceSpool.replay(index.db);
                PackReaders<FileChannel> readers =
                        new PackReaders<>(
                                s.options.packHandles(),
                                pack -> FileChannel.open(files.get(pack), READ))) {
            NativeSqlite metadata = spool.database();
            // Replay heads are sorted on disk, not retained as one Java object/descriptor per pack.
            for (var item : files.entrySet()) {
                cancel.check();
                try (var lease = readers.acquire(item.getKey())) {
                    if (lease == null) {
                        throw new java.nio.file.NoSuchFileException(item.getValue().toString());
                    }
                    Format.Pack header =
                            Format.pack(
                                    Format.read(lease.handle(), 0, 64), item.getKey(), s.kind());
                    if (header == null) {
                        problem(problems, "Invalid pack header " + item.getKey());
                        continue;
                    }
                    if (!Arrays.equals(index.id, header.id())) {
                        throw new StoreException("OBJECT_STORE_FOREIGN_PACK", "Foreign store id");
                    }
                }
                packs++;
                advance(s, index.db, readers, item.getKey(), 64, problems, true);
            }
            while (true) {
                cancel.check();
                var heads =
                        metadata.query(
                                "SELECT pack,at,end,header FROM temp.replay_heads WHERE header IS NOT NULL ORDER BY seq,pack LIMIT 1");
                if (heads.isEmpty()) {
                    break;
                }
                Object[] head = heads.getFirst();
                int pack = Math.toIntExact((long) head[0]);
                long at = (long) head[1], end = (long) head[2];
                Format.Entry entry = Format.entry((byte[]) head[3], s.kind());
                entries++;
                next = Math.max(next, Math.addExact(entry.sequence(), 1));
                Sound sound;
                try (var lease = readers.acquire(pack)) {
                    if (lease == null) {
                        throw new java.nio.file.NoSuchFileException(s.packPath(pack).toString());
                    }
                    resources(s, "replay", readers.state().openHandles(), 1);
                    sound = sound(s, lease.handle(), at, entry, true, cancel);
                }
                if (!sound.valid()) {
                    corrupt++;
                    problem(problems, "Corrupt entry " + pack + ":" + at);
                } else if (!sound.identity()
                        || entry.key().type() == 6
                                && !entry.tombstone()
                                && sound.history() == null) {
                    unverifiable++;
                    problem(problems, "Unverifiable entry " + pack + ":" + at);
                } else {
                    if (entry.tombstone()) {
                        tombstones++;
                    } else if (index.find(entry.key()) != null) {
                        duplicates++;
                    }
                    apply(index, pack, at, entry, sound.history());
                }
                s.atMaintenance(MaintenancePoint.REPLAY_ENTRY);
                advance(s, index.db, readers, pack, end, problems, false);
            }
            int cursor = 0;
            while (true) {
                var page =
                        index.db.query(
                                "SELECT pack,valid_end FROM temp.replay_heads WHERE pack>? ORDER BY pack LIMIT "
                                        + MaintenanceSpool.PAGE_ROWS,
                                cursor);
                if (page.isEmpty()) {
                    break;
                }
                resources(s, "replay", readers.state().openHandles(), page.size());
                for (Object[] row : page) {
                    cancel.check();
                    int pack = Math.toIntExact((long) row[0]);
                    long end = (long) row[1];
                    try (var lease = readers.acquire(pack)) {
                        if (lease == null) {
                            throw new java.nio.file.NoSuchFileException(
                                    s.packPath(pack).toString());
                        }
                        index.pack(pack, 1, end, liveBytes(index, pack, lease.handle(), end));
                    }
                    cursor = pack;
                }
            }
            index.next = next;
            index.save();
            long dangling =
                    index.db.scalar(
                            "SELECT count(*) FROM history h LEFT JOIN objects o ON o.k=CAST(x'0500'||h.document AS BLOB) WHERE o.k IS NULL");
            if (dangling > 0) {
                problem(problems, "History rows with missing documents: " + dangling);
            }
            return new ScanReport(
                    packs,
                    entries,
                    index.objectCount,
                    duplicates,
                    tombstones,
                    corrupt,
                    unverifiable,
                    problems);
        }
    }

    static ScanReport scan(ObjectStore s, Cancellation cancel) throws IOException {
        cancel.check();
        var files = s.diskPacks();
        preflight(s, files);
        cancel.check();
        s.sessionHashes.clear();
        MaintenanceSpool.configure(s.database);
        final ScanReport[] result = new ScanReport[1];
        s.database.transaction(
                true,
                () -> {
                    for (Object[] p :
                            s.database.query(
                                    "SELECT seq,committed_length,live_bytes FROM packs WHERE state=0")) {
                        s.index.pack(Math.toIntExact((long) p[0]), 1, (long) p[1], (long) p[2]);
                    }
                });
        s.atMaintenance(MaintenancePoint.SCAN_SEALED);
        cancel.check();
        Throwable failedScan = null;
        try {
            s.database.transaction(
                    true,
                    () -> {
                        s.database.exec(
                                "CREATE TEMP TABLE saved_reads(k BLOB PRIMARY KEY,last_read INTEGER) WITHOUT ROWID");
                        s.database.exec(
                                "INSERT INTO temp.saved_reads SELECT k,last_read FROM objects WHERE last_read IS NOT NULL");
                        s.database.exec("DELETE FROM objects");
                        s.database.exec("DELETE FROM history");
                        s.database.exec("DELETE FROM packs");
                        s.atMaintenance(MaintenancePoint.SCAN_CLEARED);
                        s.index.objectCount =
                                s.index.objectDigest =
                                        s.index.historyCount = s.index.historyDigest = 0;
                        try {
                            result[0] = replay(s, s.index, files, cancel);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        s.atMaintenance(MaintenancePoint.SCAN_REPLAYED);
                        cancel.check();
                        s.database.exec(
                                "UPDATE objects SET last_read=(SELECT last_read FROM saved_reads r WHERE r.k=objects.k) WHERE k IN (SELECT k FROM saved_reads)");
                        s.database.exec("DROP TABLE saved_reads");
                    });
        } catch (Throwable error) {
            failedScan = error;
            throw error;
        } finally {
            s.reloadIndex(failedScan);
        }
        s.sessionHashes.clear();
        s.atMaintenance(MaintenancePoint.SCAN_COMMITTED);
        s.database.uncancelled(() -> s.database.exec("PRAGMA wal_checkpoint(PASSIVE)"));
        s.atMaintenance(MaintenancePoint.SCAN_CHECKPOINTED);
        return result[0];
    }

    static VerifyReport verify(ObjectStore s, StoreIndex index, boolean full, Cancellation cancel)
            throws IOException {
        long checked = 0, bytes = 0, corruptCount = 0, lostCount = 0;
        List<ObjectKey> corrupt = new ArrayList<>(), lost = new ArrayList<>();
        byte[] cursor = new byte[0];
        while (true) {
            cancel.check();
            var page =
                    index.db.query(
                            "SELECT k,pack,offset,length,sha256 FROM objects WHERE k>? ORDER BY k LIMIT 1024",
                            cursor);
            if (page.isEmpty()) {
                break;
            }
            for (Object[] raw : page) {
                cancel.check();
                ObjectKey key;
                try {
                    key = ObjectKey.fromIndex((byte[]) raw[0]);
                } catch (ClassCastException | IllegalArgumentException malformed) {
                    throw new StoreException(
                            "OBJECT_STORE_INDEX_UNREADABLE",
                            "Verify encountered an invalid index key",
                            malformed);
                }
                if (Arrays.compareUnsigned(cursor, key.indexBytes()) >= 0) {
                    throw new StoreException(
                            "OBJECT_STORE_INDEX_UNREADABLE", "Verify cursor did not advance");
                }
                cursor = key.indexBytes();
                checked++;
                StoreIndex.Row row;
                try {
                    row = StoreIndex.Row.from(raw);
                } catch (IllegalArgumentException malformed) {
                    var current =
                            index.db.query(
                                    "SELECT k,pack,offset,length,sha256 FROM objects WHERE k=?",
                                    key.indexBytes());
                    if (!current.isEmpty() && Arrays.deepEquals(raw, current.getFirst())) {
                        corruptCount++;
                        if (corrupt.size() < 1000) {
                            corrupt.add(key);
                        }
                    }
                    continue;
                }
                bytes += row.length();
                ObjectRead read = s.readCore(index, row.key(), 0, -1, true, full, cancel);
                StoreIndex.Row current = index.find(row.key());
                if (current == null
                        || current.pack() != row.pack()
                        || current.offset() != row.offset()) {
                    continue;
                }
                if (read.state() == ReadState.CORRUPT) {
                    corruptCount++;
                    if (corrupt.size() < 1000) {
                        corrupt.add(row.key());
                    }
                }
                if (read.state() == ReadState.LOST) {
                    lostCount++;
                    if (lost.size() < 1000) {
                        lost.add(row.key());
                    }
                }
            }
        }
        return new VerifyReport(checked, bytes, corruptCount, lostCount, corrupt, lost);
    }

    /** Copy and verify the very same blocks; do not validate then reread unchecked bytes. */
    private static boolean copyEntry(
            ObjectStore s,
            FileChannel input,
            long from,
            Format.Entry entry,
            FileChannel output,
            Path targetPath,
            long to,
            Cancellation cancel)
            throws IOException {
        MessageDigest sha = Format.digest("SHA-256");
        for (int block = 0; block < Format.blocks(entry.length()); block++) {
            cancel.check();
            long offset = (long) block * Format.BLOCK;
            int count = (int) Math.min(Format.BLOCK, entry.length() - offset);
            byte[] bytes = Format.read(input, from + 96 + offset, count);
            byte[] crc = Format.read(input, from + 96 + entry.length() + 4L * block, 4);
            if (bytes.length != count
                    || crc.length != 4
                    || Format.le(crc).getInt() != (int) Format.crc(bytes)) {
                return false;
            }
            sha.update(bytes);
            s.io.write(output, targetPath, to + 96 + offset, bytes);
            s.io.write(output, targetPath, to + 96 + entry.length() + 4L * block, crc);
            if (block == 0) {
                s.atMaintenance(MaintenancePoint.COMPACT_COPYING);
            }
        }
        if (!MessageDigest.isEqual(sha.digest(), entry.sha())) {
            return false;
        }
        s.io.write(output, targetPath, to, entry.header());
        return true;
    }

    static CompactReport compact(ObjectStore s, double fraction, int maxPacks, Cancellation cancel)
            throws IOException {
        if (maxPacks == 0) {
            return new CompactReport(0, 0, 0, 0, 0);
        }
        cancel.check();
        int count = 0, attempted = 0;
        long before = 0, after = 0, moved = 0, skipped = 0;
        try (MaintenanceSpool candidates = MaintenanceSpool.candidates(s.database)) {
            long candidateCursor = 0;
            candidatesLoop:
            while (true) {
                var page =
                        candidates
                                .database()
                                .query(
                                        "SELECT pack,length,live,ordinal FROM temp.compaction_candidates WHERE ordinal>? ORDER BY ordinal LIMIT 1",
                                        candidateCursor);
                if (page.isEmpty()) {
                    break;
                }
                resources(s, "compaction", 0, page.size());
                for (Object[] p : page) {
                    candidateCursor = (long) p[3];
                    if (attempted >= maxPacks) {
                        break candidatesLoop;
                    }
                    cancel.check();
                    int source = Math.toIntExact((long) p[0]);
                    long length = (long) p[1];
                    if (s.corruptPacks.get(source)
                            || length - 64 - (long) p[2] < fraction * (length - 64)) {
                        continue;
                    }
                    attempted++;
                    int target = s.allocatePack();
                    Path targetPath = s.packPath(target);
                    boolean switched = false;
                    Throwable failedCopy = null;
                    long movedRows = 0;
                    long end = 64;
                    try (MaintenanceSpool moves = MaintenanceSpool.moves(s.database)) {
                        s.database.transaction(true, () -> s.index.pack(target, 2, 64, 0));
                        try {
                            s.atMaintenance(MaintenancePoint.COMPACT_TARGET_LISTED);
                            cancel.check();
                            boolean bad = false, corrupt = false;
                            try (FileChannel input = FileChannel.open(s.packPath(source), READ);
                                    FileChannel output =
                                            FileChannel.open(targetPath, CREATE_NEW, WRITE)) {
                                resources(s, "compaction", 2, 1);
                                s.io.write(
                                        output,
                                        targetPath,
                                        0,
                                        Format.pack(target, s.index.id, s.now(), s.kind()));
                                for (long at = 64; at < length; ) {
                                    cancel.check();
                                    Format.Entry e =
                                            Format.entry(Format.read(input, at, 96), s.kind());
                                    if (e == null || e.size() > length - at) {
                                        bad = true;
                                        break;
                                    }
                                    StoreIndex.Row row = s.index.find(e.key());
                                    boolean live =
                                            !e.tombstone()
                                                    && row != null
                                                    && row.pack() == source
                                                    && row.offset() == at;
                                    if (e.tombstone() || live) {
                                        if (!copyEntry(
                                                s, input, at, e, output, targetPath, end, cancel)) {
                                            bad = corrupt = true;
                                            break;
                                        }
                                        if (live) {
                                            moves.database()
                                                    .exec(
                                                            "INSERT INTO temp.compaction_moves(k,offset) VALUES(?,?)",
                                                            e.key().indexBytes(),
                                                            end);
                                            movedRows++;
                                        }
                                        end += e.size();
                                    }
                                    at += e.size();
                                }
                                if (!bad) {
                                    s.io.sync(output, targetPath);
                                    s.io.syncDirectory(s.packs);
                                }
                            }
                            if (bad) {
                                if (corrupt) {
                                    skipped++;
                                    s.corruptPacks.set(source);
                                }
                                continue;
                            }
                            s.atMaintenance(MaintenancePoint.COMPACT_COPIED);
                            s.atMaintenance(MaintenancePoint.COMPACT_BEFORE_SWITCH);
                            cancel.check();
                            long finalEnd = end;
                            s.database.transaction(
                                    true,
                                    () -> {
                                        byte[] cursor = null;
                                        while (true) {
                                            List<Object[]> movedPage =
                                                    cursor == null
                                                            ? moves.database()
                                                                    .query(
                                                                            "SELECT k,offset FROM temp.compaction_moves ORDER BY k LIMIT "
                                                                                    + MaintenanceSpool
                                                                                            .PAGE_ROWS)
                                                            : moves.database()
                                                                    .query(
                                                                            "SELECT k,offset FROM temp.compaction_moves WHERE k>? ORDER BY k LIMIT "
                                                                                    + MaintenanceSpool
                                                                                            .PAGE_ROWS,
                                                                            cursor);
                                            if (movedPage.isEmpty()) {
                                                break;
                                            }
                                            resources(s, "compaction", 0, movedPage.size());
                                            for (Object[] movedRow : movedPage) {
                                                ObjectKey key =
                                                        ObjectKey.fromIndex((byte[]) movedRow[0]);
                                                StoreIndex.Row old = s.index.find(key);
                                                StoreIndex.Row row =
                                                        new StoreIndex.Row(
                                                                key,
                                                                target,
                                                                (long) movedRow[1],
                                                                old.length(),
                                                                old.sha());
                                                s.index.objectDigest ^= old.mix() ^ row.mix();
                                                s.database.exec(
                                                        "UPDATE objects SET pack=?,offset=? WHERE k=?",
                                                        target,
                                                        row.offset(),
                                                        row.key().indexBytes());
                                                cursor = key.indexBytes();
                                            }
                                        }
                                        s.index.pack(target, 1, finalEnd, finalEnd - 64);
                                        s.index.pack(source, 3, length, (long) p[2]);
                                        s.index.save();
                                        s.atMaintenance(MaintenancePoint.COMPACT_SWITCHING);
                                        cancel.check();
                                    });
                            switched = true;
                            s.atMaintenance(MaintenancePoint.COMPACT_SWITCHED);
                            s.packReaders.invalidate(source);
                            s.io.delete(s.packPath(source));
                            // Catch a stale-row acquisition between retirement and unlink.
                            s.packReaders.invalidate(source);
                            s.io.syncDirectory(s.packs);
                            s.atMaintenance(MaintenancePoint.COMPACT_OLD_DELETED);
                            s.database.uncancelled(
                                    () -> s.database.exec("DELETE FROM packs WHERE seq=?", source));
                            s.atMaintenance(MaintenancePoint.COMPACT_SOURCE_UNLISTED);
                        } catch (Throwable error) {
                            failedCopy = error;
                            // Both committed and unknown outcomes need their files intact until
                            // reopen resolves
                            // state.
                            if (error instanceof NativeSqlite.TransactionFailure transaction
                                    && transaction.outcome != NativeSqlite.Outcome.ROLLED_BACK) {
                                switched = true;
                            }
                            throw error;
                        } finally {
                            if (!switched) {
                                try {
                                    try {
                                        s.io.delete(targetPath);
                                        s.database.uncancelled(
                                                () ->
                                                        s.database.exec(
                                                                "DELETE FROM packs WHERE seq=?",
                                                                target));
                                    } catch (Throwable cleanup) {
                                        if (failedCopy != null) {
                                            failedCopy.addSuppressed(cleanup);
                                        } else {
                                            throw cleanup;
                                        }
                                    }
                                } finally {
                                    s.reloadIndex(failedCopy);
                                }
                            }
                        }
                        count++;
                        before += length;
                        after += end;
                        moved += movedRows;
                    }
                }
            }
        }
        return new CompactReport(count, before, after, moved, skipped);
    }
}
