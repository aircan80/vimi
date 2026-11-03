package com.vibeminer.formats.db2;

import static com.vibeminer.formats.db2.Db2Input.fail;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.db2.Db2Reader.Binding;
import com.vibeminer.formats.db2.Db2Reader.Copy;
import com.vibeminer.formats.db2.Db2Reader.Location;
import com.vibeminer.formats.db2.Db2Reader.Relations;
import com.vibeminer.formats.db2.Db2Reader.Resolution;
import com.vibeminer.formats.db2.Db2Reader.Section;
import com.vibeminer.formats.db2.Db2Reader.Storage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Immutable structural indexes, with independent lazy row reads and explicit protected identities.
 */
public final class Db2Table {
    private final Db2Input input;
    private final boolean sparse, partial;
    private final int alignment, recordSize, declaredRecords;
    private final List<Section> sections;
    private final List<Binding> bindings;
    private final Storage[] storage;
    private final Db2Types.Limits limits;
    private final Relations[] relations;
    private final List<Location> rows;
    private final Map<Long, Integer> rowIndex;
    private final int[] rowStart, copyStart;
    private final List<Copy> copies;
    private final List<Resolution> resolutions;
    private final Map<Long, Integer> copyIndex, protectedIds;
    private final Db2Types.Header header;
    private final NativeRowIssueOrigin nativeIssueOrigin;
    private final List<Db2Types.Section> sectionInfo;
    private final Db2Types.Completion completion;
    private final int unresolvedCopies;
    private final int[] ordinalCopies;

    public NativeRowIssueOrigin nativeIssueOrigin() {
        return nativeIssueOrigin;
    }

    Db2Table(
            Db2Input input,
            boolean sparse,
            int alignment,
            List<Section> sections,
            List<Binding> bindings,
            Storage[] storage,
            int recordSize,
            int declaredRecords,
            Db2Types.Limits limits,
            Relations[] relations,
            List<Location> rows,
            Map<Long, Integer> rowIndex,
            int[] rowStart,
            List<Copy> copies,
            List<Resolution> resolutions,
            Map<Long, Integer> copyIndex,
            int[] copyStart,
            Map<Long, Integer> protectedIds,
            boolean partial,
            Db2Types.Header header,
            List<Db2Types.Section> sectionInfo,
            Db2Types.Completion completion,
            int unresolvedCopies,
            NativeRowIssueOrigin nativeIssueOrigin) {
        this.input = input;
        this.sparse = sparse;
        this.alignment = alignment;
        this.sections = List.copyOf(sections);
        this.bindings = bindings;
        this.storage = storage;
        this.recordSize = recordSize;
        this.declaredRecords = declaredRecords;
        this.limits = limits;
        this.relations = relations;
        this.rows = List.copyOf(rows);
        this.rowIndex = Map.copyOf(rowIndex);
        this.rowStart = rowStart;
        this.copies = List.copyOf(copies);
        this.resolutions = List.copyOf(resolutions);
        this.copyIndex = Map.copyOf(copyIndex);
        this.copyStart = copyStart;
        this.protectedIds = Map.copyOf(protectedIds);
        this.partial = partial;
        this.header = header;
        this.nativeIssueOrigin = java.util.Objects.requireNonNull(nativeIssueOrigin);
        this.sectionInfo = List.copyOf(sectionInfo);
        this.completion = completion;
        this.unresolvedCopies = unresolvedCopies;
        ordinalCopies =
                java.util.stream.IntStream.range(0, copies.size())
                        .filter(c -> !resolutions.get(c).protectedCopy())
                        .toArray();
    }

    public Db2Types.Header header() {
        return header;
    }

    public List<Db2Types.Section> sections() {
        return sectionInfo;
    }

    public Db2Types.Completion completion() {
        return completion;
    }

    public int unresolvedCopies() {
        return unresolvedCopies;
    }

    public long readableRowCount() {
        return rows.size() + (long) ordinalCopies.length;
    }

    public CompletableFuture<Db2Types.Lookup> find(long id, Cancellation token) {
        return async(() -> lookup(id, new Budget(), token));
    }

    public CompletableFuture<Db2Types.OrdinalLookup> findWithOrdinal(long id, Cancellation token) {
        return async(
                () -> {
                    var found = lookup(id, new Budget(), token);
                    long ordinal = -1;
                    if (found instanceof Db2Types.Found) {
                        Integer row = rowIndex.get(id);
                        if (row != null) {
                            ordinal = row;
                        } else {
                            int copy = copyIndex.get(id);
                            for (int i = 0; i < ordinalCopies.length; i++) {
                                if (ordinalCopies[i] == copy) {
                                    ordinal = rows.size() + (long) i;
                                    break;
                                }
                            }
                        }
                    }
                    return new Db2Types.OrdinalLookup(ordinal, found);
                });
    }

    public CompletableFuture<List<Db2Types.Row>> readOrdinals(
            long first, int count, Cancellation token) {
        if (first < 0 || count < 0 || first > readableRowCount()) {
            throw new IllegalArgumentException("Invalid row interval");
        }
        return async(
                () -> {
                    var result = new ArrayList<Db2Types.Row>();
                    var budget = new Budget();
                    long end = Math.min(readableRowCount(), first + count);
                    for (long i = first; i < end; i++) {
                        result.add(
                                i < rows.size()
                                        ? decode(
                                                (int) i,
                                                rows.get((int) i).id(),
                                                null,
                                                budget,
                                                token)
                                        : decodeCopy(
                                                ordinalCopies[(int) (i - rows.size())],
                                                budget,
                                                token));
                    }
                    return List.copyOf(result);
                });
    }

    /** Sequential bounded full-read session; one aggregate evidence budget spans every page. */
    public com.vibeminer.files.AsyncCursor<Db2Types.Row> rows(Cancellation token) {
        return new com.vibeminer.files.AsyncCursor<>() {
            final Budget budget = new Budget();
            final java.util.concurrent.atomic.AtomicBoolean reading =
                    new java.util.concurrent.atomic.AtomicBoolean();
            long next;
            volatile boolean closed;

            @Override
            public CompletableFuture<List<Db2Types.Row>> next(
                    int limit, Cancellation cancellation) {
                if (limit < 1 || limit > 65536) {
                    throw new IllegalArgumentException("Row cursor limit is1..65536");
                }
                if (!reading.compareAndSet(false, true)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Concurrent row cursor read"));
                }
                var combined = new Cancellation();
                var outerRegistration = token.onCancel(combined::cancel);
                var innerRegistration = cancellation.onCancel(combined::cancel);
                return async(
                        () -> {
                            try {
                                token.check();
                                cancellation.check();
                                if (closed) {
                                    return List.of();
                                }
                                var result = new ArrayList<Db2Types.Row>();
                                long end = Math.min(readableRowCount(), next + limit);
                                for (; next < end; next++) {
                                    token.check();
                                    cancellation.check();
                                    result.add(
                                            next < rows.size()
                                                    ? decode(
                                                            (int) next,
                                                            rows.get((int) next).id(),
                                                            null,
                                                            budget,
                                                            combined)
                                                    : decodeCopy(
                                                            ordinalCopies[
                                                                    (int) (next - rows.size())],
                                                            budget,
                                                            combined));
                                }
                                return List.copyOf(result);
                            } finally {
                                reading.set(false);
                                outerRegistration.close();
                                innerRegistration.close();
                            }
                        });
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    public CompletableFuture<List<Db2Types.Row>> readAll(Cancellation token) {
        return readOrdinals(0, Db2Reader.count(readableRowCount(), Integer.MAX_VALUE), token);
    }

    public CompletableFuture<List<Db2Types.Row>> readSection(int section, Cancellation token) {
        if (section < 0 || section >= sections.size()) {
            throw new IllegalArgumentException("Invalid section");
        }
        if (sections.get(section).hidden) {
            throw fail("ENCRYPTED", "Section is unavailable");
        }
        return async(
                () -> {
                    var result = new ArrayList<Db2Types.Row>();
                    var budget = new Budget();
                    for (int row = rowStart[section]; row < rowStart[section + 1]; row++) {
                        result.add(decode(row, rows.get(row).id(), null, budget, token));
                    }
                    for (int copy = copyStart[section]; copy < copyStart[section + 1]; copy++) {
                        if (!resolutions.get(copy).protectedCopy()) {
                            result.add(decodeCopy(copy, budget, token));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    public CompletableFuture<Db2Types.Completion> consume(
            Db2Consumer consumer, Cancellation token) {
        return async(
                () -> {
                    consumer.header(header, token).join();
                    var budget = new Budget();
                    for (int row = 0; row < rows.size(); row++) {
                        consumer.row(decode(row, rows.get(row).id(), null, budget, token), token)
                                .join();
                        token.check();
                    }
                    for (int copy : ordinalCopies) {
                        consumer.row(decodeCopy(copy, budget, token), token).join();
                        token.check();
                    }
                    return new Db2Types.Completion(
                            completion.declaredRecords(),
                            completion.physicalRows(),
                            completion.copyRows(),
                            completion.excludedEncryptedSections(),
                            completion.excludedPhysicalRecords(),
                            completion.excludedCopyRecords(),
                            completion.scope(),
                            completion.consumedBytes(),
                            budget.issues);
                });
    }

    public Map<Long, String> protectedIds(int max) {
        if (protectedIds.size() > max) {
            return null;
        }
        var result = new HashMap<Long, String>();
        protectedIds.forEach((id, section) -> result.put(id, sectionInfo.get(section).key()));
        for (int c = 0; c < copies.size(); c++) {
            if (resolutions.get(c).protectedCopy()) {
                if (result.size() >= max) {
                    return null;
                }
                result.put(
                        copies.get(c).target(),
                        sectionInfo.get(protectedIds.get(resolutions.get(c).protectedId())).key());
            }
        }
        return Map.copyOf(result);
    }

    private Db2Types.Lookup lookup(long id, Budget budget, Cancellation token) {
        token.check();
        if (id < 0 || id > 0xffffffffL) {
            throw new IllegalArgumentException("Unsigned32 ID required");
        }
        Integer row = rowIndex.get(id);
        if (row != null) {
            return new Db2Types.Found(decode(row, id, null, budget, token));
        }
        Integer copy = copyIndex.get(id);
        if (copy != null) {
            var r = resolutions.get(copy);
            if (r.protectedCopy()) {
                return protectedLookup(r.protectedId());
            }
            return new Db2Types.Found(decodeCopy(copy, budget, token));
        }
        if (protectedIds.containsKey(id)) {
            return protectedLookup(id);
        }
        return partial ? new Db2Types.NotInReadableSections() : new Db2Types.Absent();
    }

    private Db2Types.Protected protectedLookup(long id) {
        int s = protectedIds.get(id);
        return new Db2Types.Protected(s, sectionInfo.get(s).key(), id);
    }

    private Db2Types.Row decodeCopy(int copy, Budget budget, Cancellation token) {
        var resolution = resolutions.get(copy);
        if (resolution.failure() != null) {
            throw fail(resolution.failure(), "Broken or over-depth copy chain");
        }
        var entry = copies.get(copy);
        return decode(resolution.row(), entry.target(), entry.source(), budget, token);
    }

    private static final class Budget {
        long bytes, issues;
    }

    private Db2Types.Row decode(
            int row, long id, Long copySource, Budget budget, Cancellation token) {
        token.check();
        var location = rows.get(row);
        byte[] record = input.read(location.offset(), location.length(), token);
        var values = new ArrayList<String>();
        var issues = new ArrayList<Db2Types.TextIssue>();
        int cursor = 0;
        for (var binding : bindings) {
            token.check();
            var field = binding.field();
            if (field.nonInline()) {
                if (field.isId()) {
                    values.add(Long.toString(id));
                    continue;
                }
                var map = relations[location.section()];
                if (map == null || !map.present[location.local()]) {
                    issues.add(new Db2Types.TextIssue(values.size(), "DB2_RELATION_ABSENT", ""));
                    values.add("");
                } else {
                    values.add(
                            field.column().verifiedName()
                                    ? Db2Numbers.relationship(map.values[location.local()], field)
                                    : Long.toString(map.values[location.local()]));
                }
                continue;
            }
            var st = storage[binding.storage()];
            for (int element = 0; element < field.arrayLength(); element++) {
                long raw;
                String value;
                if (sparse && Db2Reader.isString(field)) {
                    int first = cursor;
                    while (cursor < record.length && record[cursor] != 0) {
                        if (cursor - first >= limits.maxStringBytes()) {
                            throw fail("LIMIT", "Sparse string too large");
                        }
                        if ((cursor & 255) == 0) {
                            token.check();
                        }
                        cursor++;
                    }
                    if (cursor == record.length) {
                        throw fail("INVALID", "Unterminated sparse string");
                    }
                    var bytes = Arrays.copyOfRange(record, first, cursor++);
                    try {
                        value = Db2Input.utf8(bytes);
                    } catch (Db2FormatException failure) {
                        if (!limits.allowRawTextEvidence()) {
                            throw failure;
                        }
                        issues.add(
                                new Db2Types.TextIssue(
                                        values.size(),
                                        "DB2_TEXT_ENCODING_INVALID",
                                        HexFormat.of().formatHex(bytes)));
                        value = "";
                    }
                    values.add(value);
                    continue;
                }
                if (sparse) {
                    int width = st.noneWidth();
                    raw = Db2Numbers.bits(record, cursor * 8L, width);
                    cursor += width / 8;
                } else {
                    raw = Db2Numbers.raw(record, st, field, element, id);
                }
                if (Db2Reader.isString(field)) {
                    if (st.noneWidth() != 32) {
                        throw fail("UNSUPPORTED", "Relative string requires32 bits");
                    }
                    value =
                            raw == 0
                                    ? ""
                                    : stringAt(
                                            (location.global() - (long) declaredRecords)
                                                            * recordSize
                                                    + st.fieldOffset
                                                    + 4L * element
                                                    + (int) raw,
                                            token);
                } else {
                    value = field.isId() ? Long.toString(id) : Db2Numbers.number(raw, st, field);
                    if (copySource == null && field.relationStorage()) {
                        var map = relations[location.section()];
                        if (map != null && map.present[location.local()]) {
                            long parent = map.values[location.local()];
                            boolean agrees;
                            if (field.column().verifiedName()) {
                                agrees = Db2Numbers.relationship(parent, field).equals(value);
                            } else if (Db2Numbers.usesRaw(st, field)) {
                                agrees =
                                        st.kind == 5
                                                ? parent
                                                        == (Db2Numbers.signed(raw, st.width)
                                                                & 0xffffffffL)
                                                : raw >= 0 && raw <= 0xffffffffL && parent == raw;
                            } else {
                                agrees = parent == (Long.parseLong(value) & 0xffffffffL);
                            }
                            if (!agrees) {
                                throw fail(
                                        "SCHEMA_MISMATCH",
                                        "Relationship accelerator disagrees with row");
                            }
                        }
                    }
                }
                values.add(value);
            }
        }
        if (sparse && cursor != record.length) {
            if (record.length != ((cursor + alignment - 1) & ~(alignment - 1))) {
                throw fail("UNSUPPORTED", "Sparse suffix not minimal alignment padding");
            }
            for (int i = cursor; i < record.length; i++) {
                if (record[i] != 0) {
                    throw fail("UNSUPPORTED", "Nonzero sparse padding");
                }
            }
        }
        for (var issue : issues) {
            token.check();
            long bytes = issue.rawBytesHex().length() / 2;
            if (bytes > limits.maxRawTextEvidenceBytes() - budget.bytes) {
                throw fail("LIMIT", "Aggregate text evidence exceeds bound");
            }
            budget.bytes += bytes;
            budget.issues++;
        }
        return new Db2Types.Row(
                id, copySource != null, copySource, values, issues.isEmpty() ? null : issues);
    }

    private String stringAt(long offset, Cancellation token) {
        if (offset < 0) {
            throw fail("INVALID", "String before concatenated tables");
        }
        long first = 0;
        for (var section : sections) {
            if (offset >= first + section.strings) {
                first += section.strings;
                continue;
            }
            if (section.hidden) {
                throw fail("ENCRYPTED", "String in unavailable section");
            }
            long within = offset - first, available = section.strings - within;
            var bytes = new ByteArrayOutputStream();
            while (available > 0) {
                int count =
                        (int)
                                Math.min(
                                        64,
                                        Math.min(
                                                available,
                                                limits.maxStringBytes() - bytes.size() + 1L));
                if (count <= 0) {
                    throw fail("LIMIT", "String exceeds bound");
                }
                var part =
                        input.read(
                                section.offset + section.count * (long) recordSize + within,
                                count,
                                token);
                for (byte b : part) {
                    if (b == 0) {
                        return Db2Input.utf8(bytes.toByteArray());
                    }
                    if (bytes.size() >= limits.maxStringBytes()) {
                        throw fail("LIMIT", "String exceeds bound");
                    }
                    bytes.write(b);
                }
                within += count;
                available -= count;
            }
            throw fail("INVALID", "String not terminated in section");
        }
        throw fail("INVALID", "String outside declared tables");
    }

    private static <T> CompletableFuture<T> async(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, command -> Thread.ofVirtual().start(command));
    }
}
