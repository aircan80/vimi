package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.NativeIdReceiptFingerprint;
import com.vibeminer.formats.tables.TableTypes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Actual bounded native table reads only; the enclosing root source operation owns lifetime. */
final class WorldLiquidTables {
    enum Reason {
        TABLE_UNAVAILABLE,
        TABLE_PROFILE,
        ROW_NOT_OBSERVED,
        ROW_ISSUE,
        SOURCE_CHANGED
    }

    static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        private transient Observed actualContext;

        Observed actualContext() {
            return actualContext;
        }

        private Unavailable(Reason reason) {
            super(reason.name(), null);
            this.reason = reason;
        }
    }

    static final class CleanupFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private CleanupFailure(Throwable cause) {
            super("Liquid capture cleanup failed", cause);
        }
    }

    enum Profile {
        TYPE("LiquidType", 1371380, "D1ECEEC9", "6613BED3", "MaterialID", 4096, true),
        MATERIAL("LiquidMaterial", 1132538, "98E5D7AA", "8C379329", "LVF", 256, false);

        String definitionSha256() {
            return this == TYPE
                    ? "d6907c30630ef2518583e4e4dbc0fa8d42e54a400f952dc6c11ce1a5eae6ea5e"
                    : "f6c6a777d685939f1e2a172b914742328e62375a6f40c0403469b34bef38e774";
        }

        final String name, layout, tableHash, value;
        final long id;
        final int maxRows;
        final boolean unsigned;

        Profile(
                String name,
                long id,
                String layout,
                String tableHash,
                String value,
                int maxRows,
                boolean unsigned) {
            this.name = name;
            this.id = id;
            this.layout = layout;
            this.tableHash = tableHash;
            this.value = value;
            this.maxRows = maxRows;
            this.unsigned = unsigned;
        }
    }

    /** Detached selected observations, not catalog membership or independently positive inputs. */
    static final class Observed {
        private final Profile profile;
        private final NativeIdReceiptFingerprint.CanonicalStamp stamp;
        private final String key, receiptSha;
        private final long length;
        private final Map<Long, Integer> values;
        private final Map<Long, int[]> extras;

        private Observed(
                Profile profile,
                NativeIdReceiptFingerprint.CanonicalStamp stamp,
                String key,
                String receiptSha,
                long length,
                Map<Long, Integer> values,
                Map<Long, int[]> extras) {
            this.profile = profile;
            this.stamp = stamp;
            this.key = key;
            this.receiptSha = receiptSha;
            this.length = length;
            this.values = Map.copyOf(values);
            this.extras = Map.copyOf(extras);
        }

        long sourceId() {
            return profile.id;
        }

        String sourceKey() {
            return key;
        }

        String receiptSha() {
            return receiptSha;
        }

        long sourceLength() {
            return length;
        }

        int value(long id) {
            Integer value = values.get(id);
            if (value == null) {
                throw unavailable(Reason.ROW_NOT_OBSERVED);
            }
            return value;
        }

        Set<Long> ids() {
            return values.keySet();
        }

        /**
         * A LiquidType row's SoundBank, the client's liquid category: 0 water, 1 ocean, 2 magma, 3
         * slime (other values are kept as read).
         */
        int soundBank(long id) {
            return extra(id, 0);
        }

        /** A LiquidType row's MinimapStaticCol, the client's flat colour for the liquid. */
        int minimapColour(long id) {
            return extra(id, 1);
        }

        private int extra(long id, int index) {
            if (profile != Profile.TYPE) {
                throw new IllegalStateException("Only LiquidType rows carry a category and colour");
            }
            int[] row = extras.get(id);
            if (row == null) {
                throw unavailable(Reason.ROW_NOT_OBSERVED);
            }
            return row[index];
        }
    }

    private record Context(
            CapturedRowsTypes.Receipt receipt,
            NativeIdReceiptFingerprint.CanonicalStamp stamp,
            int idColumn,
            int valueColumn,
            int bankColumn,
            int colourColumn) {}

    private final BuildTables tables;
    private final BuildFiles files;
    private final com.vibeminer.builds.BuildTypes.BuildRef build;
    private final String locale;

    WorldLiquidTables(BuildTables tables, BuildFiles files) {
        this(tables, files, files.build(), files.locale());
    }

    WorldLiquidTables(
            BuildTables tables,
            BuildFiles files,
            com.vibeminer.builds.BuildTypes.BuildRef build,
            String locale) {
        this.tables = Objects.requireNonNull(tables);
        this.files = Objects.requireNonNull(files);
        this.build = Objects.requireNonNull(build);
        this.locale = Objects.requireNonNull(locale);
    }

    Observed observe(Profile profile, Set<Long> selected, Cancellation token) {
        var trace = new Observed[1];
        try {
            return observeActual(profile, selected, token, trace);
        } catch (Unavailable refusal) {
            refusal.actualContext = trace[0];
            throw cleanOutcome(refusal);
        } finally {
            trace[0] = null;
        }
    }

    private Observed observeActual(
            Profile profile, Set<Long> selected, Cancellation token, Observed[] trace) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(selected);
        Objects.requireNonNull(token);
        if (selected.size() > (profile == Profile.TYPE ? 1024 : 256)
                || selected.stream()
                        .anyMatch(
                                id ->
                                        id == null
                                                || id < 0
                                                || id > (profile == Profile.TYPE ? 65535 : 255))) {
            throw new IllegalArgumentException("Liquid selection bound");
        }
        try (var rows = open(profile, token)) {
            var context = qualify(rows, profile, token);
            var receipt = context.receipt();
            validateFile(
                    profile,
                    receipt.table().contentKey(),
                    receipt.completion().consumedBytes(),
                    token);
            trace[0] =
                    new Observed(
                            profile,
                            context.stamp(),
                            receipt.table().contentKey(),
                            NativeIdReceiptFingerprint.sha256(receipt),
                            receipt.completion().consumedBytes(),
                            Map.of(),
                            Map.of());
            var values = new HashMap<Long, Integer>();
            var extras = new HashMap<Long, int[]>();
            long ordinal = 0;
            while (ordinal < receipt.rowCount()) {
                var page = page(rows, ordinal, token);
                long next = ordinal + 1;
                if (!page.identity().equals(receipt.graphIdentity())
                        || page.firstOrdinal() != ordinal
                        || page.rows().size() != 1
                        || !Objects.equals(
                                page.nextOrdinal(), next < receipt.rowCount() ? next : null)) {
                    throw new IllegalStateException("Noncontiguous native liquid page");
                }
                var row = page.rows().getFirst();
                if (row.ordinal() != ordinal) {
                    throw new IllegalStateException("Liquid row ordinal differs");
                }
                if (selected.contains(row.row().id())) {
                    if (values.containsKey(row.row().id())) {
                        throw unavailable(Reason.ROW_ISSUE);
                    }
                    if (row.row().textIssues() == null
                            || !row.row().textIssues().isEmpty()
                            || !page.cellIssues().isEmpty()) {
                        throw unavailable(Reason.ROW_ISSUE);
                    }
                    var raw = row.row().values();
                    if (raw.size() != receipt.columns().size()) {
                        throw new IllegalStateException("Liquid row shape differs");
                    }
                    long id = integer(raw.get(context.idColumn()));
                    long value = integer(raw.get(context.valueColumn()));
                    if (id != row.row().id()
                            || id < 0
                            || id > Integer.MAX_VALUE
                            || value < (profile.unsigned ? 0 : -128)
                            || value > (profile.unsigned ? 255 : 127)) {
                        throw unavailable(Reason.ROW_ISSUE);
                    }
                    values.put(id, (int) value);
                    if (profile == Profile.TYPE) {
                        long bank = integer(raw.get(context.bankColumn()));
                        long colour = integer(raw.get(context.colourColumn()));
                        if (bank < 0
                                || bank > 255
                                || colour < Integer.MIN_VALUE
                                || colour > Integer.MAX_VALUE) {
                            throw unavailable(Reason.ROW_ISSUE);
                        }
                        extras.put(id, new int[] {(int) bank, (int) colour});
                    }
                }
                ordinal = next;
            }
            eof(rows, receipt, token);
            if (values.size() != selected.size()) {
                throw unavailable(Reason.ROW_NOT_OBSERVED);
            }
            validateFile(
                    profile,
                    receipt.table().contentKey(),
                    receipt.completion().consumedBytes(),
                    token);
            return new Observed(
                    profile,
                    context.stamp(),
                    receipt.table().contentKey(),
                    NativeIdReceiptFingerprint.sha256(receipt),
                    receipt.completion().consumedBytes(),
                    values,
                    extras);
        }
    }

    /** Fresh capture + exact stamp + actual source binding. No reused row/schema owner. */
    void revalidate(Observed observed, Cancellation token) {
        try {
            revalidateActual(observed, token);
        } catch (Unavailable refusal) {
            throw cleanOutcome(refusal);
        }
    }

    private void revalidateActual(Observed observed, Cancellation token) {
        try (var rows = open(observed.profile, token)) {
            var context = qualify(rows, observed.profile, token);
            eof(rows, context.receipt(), token);
            if (!observed.stamp.equals(context.stamp())) {
                throw unavailable(Reason.SOURCE_CHANGED);
            }
            validateFile(observed.profile, observed.key, observed.length, token);
        }
    }

    private CapturedRows open(Profile p, Cancellation token) {
        token.check();
        var capture =
                WorldSourceFutures.await(
                        tables.capturePreparedLocal(TableTypes.Ref.forName(p.name), token));
        try {
            token.check();
            if (capture.state() == CapturedRowsTypes.CaptureState.CANCELED) {
                throw new java.util.concurrent.CancellationException();
            }
            if (capture.rows() == null) {
                switch (capture.state()) {
                    case UNSUPPORTED, NOT_PREPARED, SOURCE_UNAVAILABLE, SCHEMA_UNSUPPORTED ->
                            throw unavailable(Reason.TABLE_UNAVAILABLE);
                    default -> throw new IllegalStateException("Native liquid capture failed");
                }
            }
            if (capture.state() != CapturedRowsTypes.CaptureState.CAPTURED) {
                throw new IllegalStateException("Contradictory liquid capture");
            }
            var actual = capture.rows();
            return new CapturedRows() {
                public CapturedRowsTypes.Receipt receipt() {
                    return actual.receipt();
                }

                public java.util.concurrent.CompletableFuture<CapturedRowsTypes.Page>
                        readOrdinalPage(long first, int count, Cancellation stop) {
                    return actual.readOrdinalPage(first, count, stop);
                }

                public java.util.concurrent.CompletableFuture<CapturedRowsTypes.Page>
                        readOrdinalPage(
                                long first,
                                int count,
                                CapturedRowsTypes.ReadLimits limits,
                                Cancellation stop) {
                    return actual.readOrdinalPage(first, count, limits, stop);
                }

                public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                    return actual.closeAsync();
                }

                @Override
                public void close() {
                    try {
                        WorldSourceFutures.await(actual.closeAsync());
                    } catch (RuntimeException | Error failure) {
                        throw new CleanupFailure(failure);
                    }
                }
            };
        } catch (RuntimeException | Error failure) {
            if (capture.rows() != null) {
                try {
                    capture.rows().close();
                } catch (RuntimeException | Error cleanup) {
                    var fault = new CleanupFailure(failure);
                    fault.addSuppressed(cleanup);
                    throw fault;
                }
            }
            throw failure;
        }
    }

    private Context qualify(CapturedRows rows, Profile p, Cancellation token) {
        token.check();
        var r = rows.receipt();
        // All hashing/preallocation bounds are checked by the shared native linkage helper.
        if (r.issueChannelEvidence().state()
                != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        try {
            WorldMapProofs.validateNative(r);
        } catch (UnsupportedOperationException profile) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        var c = r.completion();
        var h = r.header();
        var e = r.table();
        if (!r.build().equals(build)
                || !r.locale().equals(locale)
                || e.fileDataId() != p.id
                || !p.name.equals(e.name())
                || e.state() != TableTypes.State.READY
                || !e.checked()
                || e.counts() == null
                || !"WDC5".equals(h.format())
                || h.schemaVersion() != 5
                || !p.layout.equalsIgnoreCase(h.layoutHash())
                || !p.tableHash.equalsIgnoreCase(h.tableHash())
                || !p.definitionSha256().equals(r.definitionSha256())
                || !p.layout.equalsIgnoreCase(e.counts().layoutHash())
                || !"WDC5".equals(e.counts().format())
                || r.rowCount() < 0
                || r.rowCount() > p.maxRows
                || c.physicalRows() < 0
                || c.copyRows() < 0
                || c.declaredRecords() != c.physicalRows()
                || h.declaredRecords() != c.physicalRows()
                || h.declaredCopyRecords() != c.copyRows()
                || c.physicalRows() + c.copyRows() != r.rowCount()
                || e.counts().readableRows() != r.rowCount()
                || e.counts().declaredRows() != r.rowCount()
                || c.excludedEncryptedSections() != 0
                || c.excludedPhysicalRecords() != 0
                || c.excludedCopyRecords() != 0
                || e.counts().excludedRecords() != 0
                || e.counts().excludedSections() != 0
                || !"complete".equals(c.scope())
                || c.consumedBytes() < 1) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        var schema = r.schema();
        if (!schema.locale().code().equals(locale)
                || schema.match() != TableSchema.SchemaMatch.LAYOUT
                || schema.layoutHashes().size() != 1
                || !schema.layoutHashes().getFirst().equalsIgnoreCase(p.layout)) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        int id = column(r, "ID", 32, false, TableSchema.KeyStorage.NONINLINE, null);
        int value =
                column(
                        r,
                        p.value,
                        8,
                        p.unsigned,
                        TableSchema.KeyStorage.NONE,
                        p == Profile.TYPE
                                ? new TableSchema.ForeignKey("LiquidMaterial", "ID")
                                : null);
        column(r, "Flags", 32, false, TableSchema.KeyStorage.NONE, null);
        // LiquidType also gives each liquid its category and flat colour (#171).
        int bank =
                p == Profile.TYPE
                        ? column(r, "SoundBank", 8, true, TableSchema.KeyStorage.NONE, null)
                        : -1;
        int colour =
                p == Profile.TYPE
                        ? column(
                                r, "MinimapStaticCol", 32, false, TableSchema.KeyStorage.NONE, null)
                        : -1;
        return new Context(
                r, NativeIdReceiptFingerprint.canonicalStamp(r), id, value, bank, colour);
    }

    private static int column(
            CapturedRowsTypes.Receipt r,
            String name,
            int bits,
            boolean unsigned,
            TableSchema.KeyStorage id,
            TableSchema.ForeignKey foreign) {
        var matches = r.schema().columns().stream().filter(c -> c.name().equals(name)).toList();
        if (matches.size() != 1) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        var c = matches.getFirst();
        if (c.kind() != TableSchema.ColumnKind.INT
                || !Objects.equals(c.bits(), bits)
                || !Objects.equals(c.unsigned(), unsigned)
                || c.arrayLength() != 1
                || c.id() != id
                || c.relation() != TableSchema.KeyStorage.NONE
                || !Objects.equals(c.foreignKey(), foreign)
                || !c.verifiedName()
                || c.locale() != null
                || c.columnComment() != null
                || c.fieldComment() != null) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        int found = -1;
        for (int i = 0; i < r.columns().size(); i++) {
            var decoded = r.columns().get(i);
            if (!decoded.name().equals(name)) {
                continue;
            }
            if (found != -1
                    || !decoded.kind().equals("integer")
                    || decoded.isId() != (id != TableSchema.KeyStorage.NONE)
                    || decoded.interpretation() != null
                    || decoded.narrowing() != null) {
                throw unavailable(Reason.TABLE_PROFILE);
            }
            found = i;
        }
        if (found < 0) {
            throw unavailable(Reason.TABLE_PROFILE);
        }
        return found;
    }

    private static CapturedRowsTypes.Page page(CapturedRows rows, long first, Cancellation token) {
        CapturedRowsTypes.Page p;
        try {
            p =
                    WorldSourceFutures.await(
                            rows.readOrdinalPage(first, 1, WorldMapProofs.READ_LIMITS, token));
        } catch (RuntimeException failure) {
            if (WorldSourceRefusals.defaultStrictUnsupported(failure)) {
                throw unavailable(Reason.TABLE_UNAVAILABLE);
            }
            throw failure;
        }
        token.check();
        switch (p.state()) {
            case READ -> {
                return p;
            }
            case CANCELED -> throw new java.util.concurrent.CancellationException();
            case LIMIT_EXCEEDED -> throw unavailable(Reason.TABLE_PROFILE);
            case CLOSED, INVALIDATED -> throw unavailable(Reason.SOURCE_CHANGED);
            default -> throw new IllegalStateException("Native liquid page failed");
        }
    }

    private static void eof(CapturedRows rows, CapturedRowsTypes.Receipt r, Cancellation token) {
        var p = page(rows, r.rowCount(), token);
        if (!p.identity().equals(r.graphIdentity())
                || p.firstOrdinal() != r.rowCount()
                || !p.rows().isEmpty()
                || p.nextOrdinal() != null
                || !p.cellIssues().isEmpty()) {
            throw new IllegalStateException("Native liquid EOF differs");
        }
    }

    private void validateFile(Profile p, String key, long length, Cancellation token) {
        if (!build.equals(files.build()) || !locale.equals(files.locale())) {
            throw unavailable(Reason.SOURCE_CHANGED);
        }
        // Identity facts only: never load listfile names or sniff formats for a liquid table.
        var stat = WorldSourceFutures.await(files.statContent(FileTypes.FileRef.id(p.id), token));
        token.check();
        if (stat.state() != FileTypes.FileLookupState.FOUND
                || stat.file() == null
                || stat.file().fileDataId() != p.id
                || !Objects.equals(key, stat.file().contentKey())
                || stat.file().size() != null && stat.file().size() != length) {
            throw unavailable(Reason.SOURCE_CHANGED);
        }
    }

    private static long integer(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException bad) {
            throw unavailable(Reason.ROW_ISSUE);
        }
    }

    private static RuntimeException cleanOutcome(Unavailable refusal) {
        if (refusal.getCause() != null || refusal.getSuppressed().length != 0) {
            return new CleanupFailure(refusal);
        }
        return refusal;
    }

    private static Unavailable unavailable(Reason reason) {
        return new Unavailable(reason);
    }
}
