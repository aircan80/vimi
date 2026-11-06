package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.TactKeySource;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.generated.TactKeyStatus;
import com.vibeminer.formats.internal.Json;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Loads only a build's own ID-joined TactKey/TACTID rows; no third-party/default keys. */
public final class BuildTactKeys {
    public static final long TACT_KEY_FILE_DATA_ID = 1302850,
            TACT_KEY_LOOKUP_FILE_DATA_ID = 1302851;
    public static final int MAX_ROWS = 65536, PAGE_ROWS = 500;

    public enum Outcome {
        READ,
        NOT_READABLE,
        NOT_NEEDED,
        MISSING_COLUMNS,
        STALE,
        TOO_MANY_ROWS,
        NOT_PREPARED,
        UNSUPPORTED,
        SOURCE_INVALID,
        RESOURCE_LIMIT
    }

    public record Table(TableTypes.Entry table, Outcome outcome) {}

    public static final class KeySet implements TactKeySource {
        private final Map<Long, byte[]> keys;
        private final TactKeyStatus status;
        private final List<String> names;
        private final Table keyTable, lookupTable;
        private final int skippedZero, unmatched, unknown, malformed;
        private final List<String> conflicts;

        KeySet(
                Map<Long, byte[]> keys,
                Table keyTable,
                Table lookupTable,
                int skippedZero,
                int unmatched,
                int unknown,
                List<String> conflicts,
                int malformed) {
            var owned = new HashMap<Long, byte[]>();
            var fingerprints = new HashMap<String, String>();
            keys.forEach(
                    (name, key) -> {
                        owned.put(name, key.clone());
                        fingerprints.put(nameOf(name), Json.sha256(key));
                    });
            this.keys = Map.copyOf(owned);
            status = new TactKeyStatus(fingerprints);
            names = fingerprints.keySet().stream().sorted().toList();
            this.keyTable = keyTable;
            this.lookupTable = lookupTable;
            this.skippedZero = skippedZero;
            this.unmatched = unmatched;
            this.unknown = unknown;
            this.conflicts = List.copyOf(conflicts);
            this.malformed = malformed;
        }

        @Override
        public byte[] find(long name) {
            byte[] key = keys.get(name);
            return key == null ? null : key.clone();
        }

        public TactKeySource keys() {
            return this;
        }

        public TactKeyStatus status() {
            return status;
        }

        public String fingerprintOf(String name) {
            return status.fingerprintOf(name);
        }

        public List<String> names() {
            return names;
        }

        public int count() {
            return keys.size();
        }

        public Table keyTable() {
            return keyTable;
        }

        public Table lookupTable() {
            return lookupTable;
        }

        public int skippedZero() {
            return skippedZero;
        }

        public int unmatched() {
            return unmatched;
        }

        public int unknown() {
            return unknown;
        }

        public List<String> conflicts() {
            return conflicts;
        }

        public int malformed() {
            return malformed;
        }
    }

    private BuildTactKeys() {}

    public static String nameOf(long value) {
        return String.format(java.util.Locale.ROOT, "%016X", value);
    }

    record Read(
            Table table, Map<Long, byte[]> rows, java.util.Set<Long> malformedIds, int malformed) {}

    public static CompletableFuture<KeySet> load(
            BuildTables tables, TableTypes.Priority priority, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    token.check();
                    var keySchema =
                            schema(tables, TACT_KEY_FILE_DATA_ID, "TactKey", priority, token);
                    var lookupSchema =
                            schema(
                                    tables,
                                    TACT_KEY_LOOKUP_FILE_DATA_ID,
                                    "TactKeyLookup",
                                    priority,
                                    token);
                    boolean needed =
                            TableQueries.isReadable(keySchema.table().state())
                                    && TableQueries.isReadable(lookupSchema.table().state());
                    var keyRead = read(tables, keySchema, "Key", 16, needed, priority, token);
                    var lookupRead =
                            read(tables, lookupSchema, "TACTID", 8, needed, priority, token);
                    return join(keyRead, lookupRead);
                },
                command -> Thread.ofVirtual().start(command));
    }

    /**
     * Joins TactKey blocks a client hotfix cache delivered (in order) into a fully read key set
     * under exactly the TactKey join rule: all-zero keys are skipped first, a name already in
     * conflict stays out, and a name seen with different bytes, whether from the TactKey tables or
     * another block, is dropped and listed in {@link KeySet#conflicts()}. A key set whose TactKey
     * tables were not read is returned unchanged. Never logs key bytes.
     */
    public static KeySet withDelivered(
            KeySet found,
            List<com.vibeminer.formats.hotfix.HotfixRowDecoder.OptionalKey> delivered) {
        if (delivered.isEmpty()
                || found.keyTable().outcome() != Outcome.READ
                || found.lookupTable().outcome() != Outcome.READ) {
            return found;
        }
        var keys = new HashMap<Long, byte[]>(found.keys);
        var conflicts = new HashSet<String>(found.conflicts());
        int skipped = found.skippedZero();
        // Clones taken from the blocks; zeroed once the key set has made its own copies.
        var copies = new java.util.ArrayList<byte[]>();
        for (var block : delivered) {
            byte[] key = block.key();
            copies.add(key);
            boolean zero = true;
            for (byte b : key) {
                zero &= b == 0;
            }
            if (zero) {
                skipped++;
                continue;
            }
            String name = nameOf(block.name());
            if (conflicts.contains(name)) {
                continue;
            }
            byte[] existing = keys.putIfAbsent(block.name(), key);
            if (existing != null && !Arrays.equals(existing, key)) {
                keys.remove(block.name());
                conflicts.add(name);
            }
        }
        var joined =
                new KeySet(
                        keys,
                        found.keyTable(),
                        found.lookupTable(),
                        skipped,
                        found.unmatched(),
                        found.unknown(),
                        conflicts.stream().sorted().toList(),
                        found.malformed());
        for (byte[] copy : copies) {
            Arrays.fill(copy, (byte) 0);
        }
        return joined;
    }

    /** Prepared local graphs only; unavailable build keys do not prevent use of community keys. */
    public static CompletableFuture<KeySet> loadPreparedLocal(
            BuildTables tables, Cancellation token) {
        return PreparedTactKeys.load(tables, token);
    }

    static KeySet join(Read keyRead, Read lookupRead) {
        int malformed = keyRead.malformed() + lookupRead.malformed();
        if (keyRead.table().outcome() != Outcome.READ
                || lookupRead.table().outcome() != Outcome.READ) {
            return new KeySet(
                    Map.of(), keyRead.table(), lookupRead.table(), 0, 0, 0, List.of(), malformed);
        }
        var byId = new HashMap<Long, byte[]>();
        int skipped = 0;
        for (var row : keyRead.rows().entrySet()) {
            boolean zero = true;
            for (byte b : row.getValue()) {
                zero &= b == 0;
            }
            if (zero) {
                skipped++;
            } else {
                byId.put(row.getKey(), row.getValue());
            }
        }
        int unmatched = 0, unknown = 0;
        var joined = new HashMap<Long, byte[]>();
        var conflicts = new HashSet<Long>();
        for (var row : lookupRead.rows().entrySet()) {
            byte[] key = byId.remove(row.getKey());
            if (key == null) {
                if (!keyRead.rows().containsKey(row.getKey())
                        && !keyRead.malformedIds().contains(row.getKey())) {
                    if (keyRead.table().table().state() == TableTypes.State.PARTIAL) {
                        unknown++;
                    } else {
                        unmatched++;
                    }
                }
                continue;
            }
            long name = ByteBuffer.wrap(row.getValue()).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (conflicts.contains(name)) {
                continue;
            }
            byte[] previous = joined.putIfAbsent(name, key);
            if (previous != null && !Arrays.equals(previous, key)) {
                joined.remove(name);
                conflicts.add(name);
            }
        }
        for (long id : byId.keySet()) {
            if (!lookupRead.rows().containsKey(id) && !lookupRead.malformedIds().contains(id)) {
                if (lookupRead.table().table().state() == TableTypes.State.PARTIAL) {
                    unknown++;
                } else {
                    unmatched++;
                }
            }
        }
        return new KeySet(
                joined,
                keyRead.table(),
                lookupRead.table(),
                skipped,
                unmatched,
                unknown,
                conflicts.stream().map(BuildTactKeys::nameOf).sorted().toList(),
                malformed);
    }

    private static TableTypes.SchemaResult schema(
            BuildTables tables,
            long id,
            String name,
            TableTypes.Priority priority,
            Cancellation token) {
        var byId = tables.schema(TableTypes.Ref.id(id), priority, token).join();
        if (TableQueries.isReadable(byId.table().state())) {
            return byId;
        }
        var byName = tables.schema(TableTypes.Ref.forName(name), priority, token).join();
        return TableQueries.isReadable(byName.table().state()) && byName.table().fileDataId() != id
                ? byName
                : byId;
    }

    private static Read empty(TableTypes.Entry entry, Outcome outcome) {
        return new Read(new Table(entry, outcome), Map.of(), java.util.Set.of(), 0);
    }

    private static Read read(
            BuildTables tables,
            TableTypes.SchemaResult schema,
            String column,
            int width,
            boolean needed,
            TableTypes.Priority priority,
            Cancellation token) {
        var entry = schema.table();
        if (!TableQueries.isReadable(entry.state())) {
            return empty(entry, Outcome.NOT_READABLE);
        }
        if (!needed) {
            return empty(entry, Outcome.NOT_NEEDED);
        }
        if (schema.columns() == null) {
            return empty(entry, Outcome.MISSING_COLUMNS);
        }
        int[] indexes = new int[width];
        for (int i = 0; i < width; i++) {
            int found = -1;
            String wanted = column + "[" + i + "]";
            for (int c = 0; c < schema.columns().size(); c++) {
                if (schema.columns().get(c).name().equalsIgnoreCase(wanted)) {
                    found = c;
                    break;
                }
            }
            if (found < 0) {
                return empty(entry, Outcome.MISSING_COLUMNS);
            }
            indexes[i] = found;
        }
        boolean signed =
                schema.schema() != null
                        && schema.schema().columns().stream()
                                .anyMatch(
                                        c ->
                                                c.name().equalsIgnoreCase(column)
                                                        && c.kind() == TableSchema.ColumnKind.INT
                                                        && Integer.valueOf(8).equals(c.bits())
                                                        && Boolean.FALSE.equals(c.unsigned()));
        for (int attempt = 0; attempt < 2; attempt++) {
            var rows = new HashMap<Long, byte[]>();
            var bad = new HashSet<Long>();
            int malformed = 0, total = 0;
            long after = -1;
            String identity = null;
            boolean stale = false;
            for (; ; ) {
                token.check();
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                TableTypes.Ref.id(entry.fileDataId()),
                                                after,
                                                PAGE_ROWS,
                                                List.of(),
                                                identity),
                                        priority,
                                        token)
                                .join();
                if (page.state() == TableTypes.PageState.STALE) {
                    stale = true;
                    break;
                }
                if (page.state() != TableTypes.PageState.OK) {
                    return empty(page.table(), Outcome.NOT_READABLE);
                }
                entry = page.table();
                identity = page.identity();
                for (var row : page.rows()) {
                    if (++total > MAX_ROWS) {
                        return empty(entry, Outcome.TOO_MANY_ROWS);
                    }
                    long id = row.row().id();
                    if (bad.contains(id)) {
                        malformed++;
                        continue;
                    }
                    byte[] bytes = bytes(row.row().values(), indexes, signed);
                    if (bytes == null) {
                        malformed++;
                        bad.add(id);
                        rows.remove(id);
                        continue;
                    }
                    byte[] previous = rows.putIfAbsent(id, bytes);
                    if (previous != null && !Arrays.equals(previous, bytes)) {
                        rows.remove(id);
                        bad.add(id);
                        malformed += 2;
                    }
                }
                if (page.nextAfter() == null) {
                    break;
                }
                after = page.nextAfter();
            }
            if (!stale) {
                return new Read(new Table(entry, Outcome.READ), rows, bad, malformed);
            }
        }
        return empty(entry, Outcome.STALE);
    }

    static byte[] bytes(List<String> values, int[] indexes, boolean signed) {
        byte[] bytes = new byte[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            if (indexes[i] >= values.size()) {
                return null;
            }
            String value = values.get(indexes[i]);
            if (!value.matches("0|[1-9][0-9]{0,2}|-[1-9][0-9]{0,2}")) {
                return null;
            }
            int n = Integer.parseInt(value);
            if (n < 0 && (!signed || n < -128) || n > 255) {
                return null;
            }
            bytes[i] = (byte) n;
        }
        return bytes;
    }
}
