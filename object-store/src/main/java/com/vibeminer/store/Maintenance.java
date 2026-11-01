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
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.PriorityQueue;

/** Pack replay uses one entry per pack in its merge queue and bounded payload buffers. */
final class Maintenance {
    private Maintenance() {}

    record Sound(boolean valid, boolean identity, History.Entry history) {}

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

    static void preflight(ObjectStore s, NavigableMap<Integer, Path> files) throws IOException {
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
                problems.add("Truncated tail of pack " + pack + " by " + truncated + " bytes");
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

    private static final class Walk implements AutoCloseable {
        final int pack;
        final FileChannel file;
        long at = 64, end = 64;
        Format.Entry entry;

        Walk(int pack, Path path) throws IOException {
            this.pack = pack;
            file = FileChannel.open(path, READ);
        }

        void advance(ObjectStore s, List<String> problems) throws IOException {
            if (at == file.size()) {
                entry = null;
                return;
            }
            entry = Format.entry(Format.read(file, at, 96), s.kind());
            if (entry == null || entry.size() > file.size() - at) {
                problems.add("Torn header in pack " + pack + " at " + at);
                entry = null;
            } else {
                end = at + entry.size();
            }
        }

        public void close() throws IOException {
            file.close();
        }
    }

    static ScanReport replay(
            ObjectStore s, StoreIndex index, NavigableMap<Integer, Path> files, Cancellation cancel)
            throws IOException {
        List<Walk> walks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        PriorityQueue<Walk> queue =
                new PriorityQueue<>(
                        Comparator.<Walk>comparingLong(w -> w.entry.sequence())
                                .thenComparingInt(w -> w.pack));
        long entries = 0, duplicates = 0, tombstones = 0, corrupt = 0, unverifiable = 0, next = 1;
        try {
            for (var item : files.entrySet()) {
                cancel.check();
                Walk w = new Walk(item.getKey(), item.getValue());
                walks.add(w);
                Format.Pack p = Format.pack(Format.read(w.file, 0, 64), w.pack, s.kind());
                if (p == null) {
                    problems.add("Invalid pack header " + w.pack);
                    w.close();
                    walks.removeLast();
                    continue;
                }
                if (!Arrays.equals(index.id, p.id())) {
                    throw new StoreException("OBJECT_STORE_FOREIGN_PACK", "Foreign store id");
                }
                w.advance(s, problems);
                if (w.entry != null) {
                    queue.add(w);
                }
            }
            while (!queue.isEmpty()) {
                cancel.check();
                Walk w = queue.remove();
                Format.Entry e = w.entry;
                entries++;
                next = Math.max(next, Math.addExact(e.sequence(), 1));
                Sound sound = sound(s, w.file, w.at, e, true, cancel);
                if (!sound.valid()) {
                    corrupt++;
                    if (problems.size() < 100) {
                        problems.add("Corrupt entry " + w.pack + ":" + w.at);
                    }
                } else if (!sound.identity()
                        || e.key().type() == 6 && !e.tombstone() && sound.history() == null) {
                    unverifiable++;
                    if (problems.size() < 100) {
                        problems.add("Unverifiable entry " + w.pack + ":" + w.at);
                    }
                } else {
                    if (e.tombstone()) {
                        tombstones++;
                    } else if (index.find(e.key()) != null) {
                        duplicates++;
                    }
                    apply(index, w.pack, w.at, e, sound.history());
                }
                s.atMaintenance(MaintenancePoint.REPLAY_ENTRY);
                w.at = w.end;
                w.advance(s, problems);
                if (w.entry != null) {
                    queue.add(w);
                }
            }
            for (Walk w : walks) {
                index.pack(w.pack, 1, w.end, liveBytes(index, w.pack, w.file, w.end));
            }
            index.next = next;
            index.save();
            long dangling =
                    index.db.scalar(
                            "SELECT count(*) FROM history h LEFT JOIN objects o ON o.k=CAST(x'0500'||h.document AS BLOB) WHERE o.k IS NULL");
            if (dangling > 0) {
                problems.add("History rows with missing documents: " + dangling);
            }
            return new ScanReport(
                    walks.size(),
                    entries,
                    index.objectCount,
                    duplicates,
                    tombstones,
                    corrupt,
                    unverifiable,
                    problems);
        } finally {
            for (Walk w : walks) {
                w.close();
            }
        }
    }

    static ScanReport scan(ObjectStore s, Cancellation cancel) throws IOException {
        cancel.check();
        var files = s.diskPacks();
        preflight(s, files);
        cancel.check();
        s.sessionHashes.clear();
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
                                "CREATE TEMP TABLE saved_reads AS SELECT k,last_read FROM objects WHERE last_read IS NOT NULL");
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
                                "UPDATE objects SET last_read=(SELECT last_read FROM saved_reads r WHERE r.k=objects.k)");
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
        int count = 0, attempted = 0;
        long before = 0, after = 0, moved = 0, skipped = 0;
        var candidates =
                s.database.query(
                        "SELECT seq,committed_length,live_bytes FROM packs WHERE state=1 AND committed_length-64-live_bytes>0 ORDER BY committed_length-64-live_bytes DESC");
        for (Object[] p : candidates) {
            if (attempted >= maxPacks) {
                break;
            }
            cancel.check();
            int source = Math.toIntExact((long) p[0]);
            long length = (long) p[1];
            if (s.corruptPacks.contains(source)
                    || length - 64 - (long) p[2] < fraction * (length - 64)) {
                continue;
            }
            attempted++;
            int target = s.allocatePack();
            Path targetPath = s.packPath(target);
            boolean switched = false;
            Throwable failedCopy = null;
            List<StoreIndex.Row> rows = new ArrayList<>();
            long end = 64;
            s.database.transaction(true, () -> s.index.pack(target, 2, 64, 0));
            try {
                s.atMaintenance(MaintenancePoint.COMPACT_TARGET_LISTED);
                cancel.check();
                boolean bad = false, corrupt = false;
                try (FileChannel input = FileChannel.open(s.packPath(source), READ);
                        FileChannel output = FileChannel.open(targetPath, CREATE_NEW, WRITE)) {
                    s.io.write(
                            output,
                            targetPath,
                            0,
                            Format.pack(target, s.index.id, s.now(), s.kind()));
                    for (long at = 64; at < length; ) {
                        cancel.check();
                        Format.Entry e = Format.entry(Format.read(input, at, 96), s.kind());
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
                            if (!copyEntry(s, input, at, e, output, targetPath, end, cancel)) {
                                bad = corrupt = true;
                                break;
                            }
                            if (live) {
                                rows.add(
                                        new StoreIndex.Row(
                                                e.key(), target, end, e.length(), e.sha()));
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
                        s.corruptPacks.add(source);
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
                            for (StoreIndex.Row row : rows) {
                                StoreIndex.Row old = s.index.find(row.key());
                                s.index.objectDigest ^= old.mix() ^ row.mix();
                                s.database.exec(
                                        "UPDATE objects SET pack=?,offset=? WHERE k=?",
                                        target,
                                        row.offset(),
                                        row.key().indexBytes());
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
                // Both committed and unknown outcomes need their files intact until reopen resolves
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
                                    () -> s.database.exec("DELETE FROM packs WHERE seq=?", target));
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
            moved += rows.size();
        }
        return new CompactReport(count, before, after, moved, skipped);
    }
}
