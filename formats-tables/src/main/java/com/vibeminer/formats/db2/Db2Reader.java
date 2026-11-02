package com.vibeminer.formats.db2;

import static com.vibeminer.formats.db2.Db2Input.fail;
import static com.vibeminer.formats.db2.Db2Input.h;
import static com.vibeminer.formats.db2.Db2Input.q;
import static com.vibeminer.formats.db2.Db2Input.u;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.definitions.DbdDocument;
import com.vibeminer.formats.definitions.DbdTypes.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bounded WDC3/4/5 structural open. Record bodies are decoded on demand. */
public final class Db2Reader {
    public static final String REVISION = "db2-values-v3";

    private Db2Reader() {}

    static final class Storage {
        int offset, width, additional, kind, p1, p2, p3, fieldBits, fieldOffset;
        long[] palette = new long[0];
        Map<Long, Long> common = Map.of();
        boolean raw, yieldRaw, narrow, collides;

        int noneWidth() {
            int n = 32 - fieldBits;
            return n <= 0 ? p2 : n;
        }

        int physicalBits() {
            return kind == 0 ? noneWidth() : kind == 1 || kind == 5 ? width : 32;
        }
    }

    static final class Section {
        long key, offset, end, recordsEnd;
        int count, strings, idBytes, relations, mapCount, copies;
        boolean hidden;
        long ids, copiesAt, maps, mapIds, relationsAt;
    }

    record Binding(Field field, int storage) {}

    record Location(long offset, int length, int section, int global, int local, long id) {}

    record Copy(long target, long source, int section) {}

    record Resolution(int row, long protectedId, String failure) {
        boolean protectedCopy() {
            return row < 0 && failure == null;
        }
    }

    static final class Relations {
        final long[] values;
        final boolean[] present;

        Relations(int n) {
            values = new long[n];
            present = new boolean[n];
        }
    }

    public static String layoutHash(byte[] header) {
        if (header.length < 4) {
            return null;
        }
        String magic = new String(header, 0, 4, StandardCharsets.US_ASCII);
        int at = magic.equals("WDC5") ? 136 : 4;
        int size = at + 68;
        if (!List.of("WDC3", "WDC4", "WDC5").contains(magic)
                || header.length < size
                || at == 136 && u(header, 4) != 5) {
            return null;
        }
        return hex(u(header, at + 20));
    }

    public static CompletableFuture<Db2Table> open(
            Db2ByteSource source,
            DbdDocument definition,
            String build,
            Db2Types.Limits limits,
            Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> openCore(source, definition, build, limits, token),
                command -> Thread.ofVirtual().start(command));
    }

    public static CompletableFuture<Db2Types.Completion> read(
            Db2ByteSource source,
            DbdDocument definition,
            String build,
            Db2Types.Limits limits,
            Db2Consumer consumer,
            Cancellation token) {
        return open(source, definition, build, limits, token)
                .thenCompose(table -> table.consume(consumer, token));
    }

    public static CompletableFuture<Db2Open> open(
            com.vibeminer.files.BuildFiles files,
            com.vibeminer.files.FileTypes.FileRef file,
            DbdDocument definition,
            String build,
            Db2Types.Limits limits,
            Cancellation token) {
        java.util.Objects.requireNonNull(definition);
        java.util.Objects.requireNonNull(limits);
        return Db2FileSource.open(files, file, token)
                .thenCompose(
                        opened -> {
                            if (opened.source() == null) {
                                return CompletableFuture.completedFuture(
                                        new Db2Open(
                                                opened.state(),
                                                null,
                                                null,
                                                opened.code(),
                                                opened.message(),
                                                opened.observedAt(),
                                                List.of(),
                                                List.of()));
                            }
                            var source = opened.source();
                            if (source.length() > 0
                                    && source.holes().stream()
                                                    .mapToLong(Db2Types.Hole::length)
                                                    .sum()
                                            == source.length()) {
                                source.close();
                                return CompletableFuture.completedFuture(
                                        new Db2Open(
                                                Db2FileSource.OpenState.PROTECTED,
                                                null,
                                                null,
                                                "DB2_SOURCE_PROTECTED",
                                                "Every byte requires an unknown TACT key",
                                                null,
                                                source.holes(),
                                                List.of()));
                            }
                            return open(source, definition, build, limits, token)
                                    .handle(
                                            (table, error) -> {
                                                if (error == null) {
                                                    return new Db2Open(
                                                            Db2FileSource.OpenState.OPEN,
                                                            table,
                                                            source,
                                                            null,
                                                            null,
                                                            null,
                                                            source.holes(),
                                                            source.chunkKeys());
                                                }
                                                source.close();
                                                Throwable cause = error;
                                                while (cause
                                                                instanceof
                                                                java.util.concurrent
                                                                        .CompletionException
                                                        && cause.getCause() != null) {
                                                    cause = cause.getCause();
                                                }
                                                if (cause instanceof Db2SourceException failure) {
                                                    return new Db2Open(
                                                            failure.state(),
                                                            null,
                                                            null,
                                                            failure.code(),
                                                            failure.getMessage(),
                                                            failure.observedAt(),
                                                            source.holes(),
                                                            List.of());
                                                }
                                                if (cause
                                                        instanceof
                                                        com.vibeminer.formats.Db2FormatException
                                                                failure) {
                                                    return new Db2Open(
                                                            Db2FileSource.OpenState.REJECTED,
                                                            null,
                                                            null,
                                                            failure.code(),
                                                            failure.getMessage(),
                                                            null,
                                                            source.holes(),
                                                            List.of());
                                                }
                                                throw new java.util.concurrent.CompletionException(
                                                        cause);
                                            });
                        });
    }

    private static Db2Table openCore(
            Db2ByteSource source,
            DbdDocument definition,
            String build,
            Db2Types.Limits limits,
            Cancellation token) {
        token.check();
        if (source.length() < 4) {
            throw fail("INVALID", "Truncated DB2 header");
        }
        if (source.length() > limits.maxDecodedBytes()) {
            throw fail("LIMIT", "Decoded source exceeds bound");
        }
        var declaredHoles = source.holes();
        List<Db2Types.Hole> snapshot =
                declaredHoles == null || declaredHoles.size() > 128
                        ? null
                        : new ArrayList<>(declaredHoles);
        String selectedFormat = "WDC5";
        if (snapshot != null
                && snapshot.stream().noneMatch(hole -> hole == null || hole.offset() < 4)) {
            var peek = java.nio.ByteBuffer.allocate(4);
            source.readRange(0, peek, token).join();
            token.check();
            if (peek.hasRemaining()) {
                throw fail("INVALID", "Short magic read");
            }
            String seen = new String(peek.array(), StandardCharsets.US_ASCII);
            if (List.of("WDC3", "WDC4", "WDC5").contains(seen)) {
                selectedFormat = seen;
            }
        }
        final String format = selectedFormat;
        int fieldsAt = format.equals("WDC5") ? 136 : 4, headerBytes = fieldsAt + 68;
        if (source.length() < headerBytes) {
            throw fail("INVALID", "Truncated mandatory DB2 header");
        }
        var immutableSource =
                new Db2ByteSource() {
                    @Override
                    public long length() {
                        return source.length();
                    }

                    @Override
                    public List<Db2Types.Hole> holes() {
                        return snapshot;
                    }

                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset,
                            java.nio.ByteBuffer destination,
                            Cancellation cancellation) {
                        return source.readRange(offset, destination, cancellation);
                    }
                };
        var input = new Db2Input(immutableSource, limits);
        byte[] header = input.read(0, headerBytes, token);
        if (!format.equals(new String(header, 0, 4, StandardCharsets.US_ASCII))) {
            throw fail("UNSUPPORTED", "Unsupported DB2 format");
        }
        long schema = 0;
        if (fieldsAt == 136) {
            schema = u(header, 4);
            if (schema != 5) {
                throw fail("UNSUPPORTED", "Only WDC5 schema5 admitted");
            }
            int end = 136;
            while (end > 8 && header[end - 1] == 0) {
                end--;
            }
            for (int i = 8; i < end; i++) {
                if (header[i] == 0) {
                    throw fail("INVALID", "Interior schema NUL");
                }
            }
            Db2Input.utf8(java.util.Arrays.copyOfRange(header, 8, end));
        }
        int records = count(u(header, fieldsAt), limits.maxRows()),
                fields = count(u(header, fieldsAt + 4), limits.maxFields()),
                recordSize = count(u(header, fieldsAt + 8), limits.maxRecordBytes());
        long strings = u(header, fieldsAt + 12),
                tableHash = u(header, fieldsAt + 16),
                layoutHash = u(header, fieldsAt + 20);
        int flags = h(header, fieldsAt + 36), idIndex = h(header, fieldsAt + 38);
        long lookup = u(header, fieldsAt + 48), storageBytes = u(header, fieldsAt + 52);
        int commonBytes = count(u(header, fieldsAt + 56), limits.maxAuxiliaryBytes()),
                paletteBytes = count(u(header, fieldsAt + 60), limits.maxAuxiliaryBytes()),
                sectionCount = count(u(header, fieldsAt + 64), limits.maxSections());
        if (!List.of(0, 4, 5, 6, 7).contains(flags)
                || u(header, fieldsAt + 40) != fields
                || lookup > 1) {
            throw fail("UNSUPPORTED", "Unsupported flags or lookup columns");
        }
        boolean sparse = (flags & 1) != 0;
        boolean empty =
                records == 0
                        && sectionCount == 0
                        && storageBytes == 0
                        && commonBytes == 0
                        && paletteBytes == 0;
        if (!empty && storageBytes != fields * 24L) {
            throw fail("UNSUPPORTED", "Unsupported storage geometry");
        }
        if ((long) commonBytes + paletteBytes > limits.maxAuxiliaryBytes()) {
            throw fail("LIMIT", "Aggregate auxiliary bytes exceed bound");
        }
        if (sparse && (commonBytes != 0 || paletteBytes != 0)) {
            throw fail("UNSUPPORTED", "Sparse auxiliary compression unsupported");
        }
        long descriptorEnd = headerBytes + sectionCount * 40L + fields * (empty ? 4L : 28L),
                auxEnd = descriptorEnd + commonBytes + paletteBytes;
        if (auxEnd > source.length()) {
            throw fail("INVALID", "Descriptors exceed source");
        }
        var metadata =
                input.read(
                        headerBytes, count(descriptorEnd - headerBytes, Integer.MAX_VALUE), token);
        var sections = new ArrayList<Section>();
        long declared = 0, copies = 0, stringSum = 0, excludedRows = 0, excludedCopies = 0;
        int excludedSections = 0;
        for (int i = 0; i < sectionCount; i++) {
            token.check();
            int at = i * 40;
            var s = new Section();
            s.key = q(metadata, at);
            s.offset = u(metadata, at + 8);
            s.count = count(u(metadata, at + 12), limits.maxRows());
            s.strings = count(u(metadata, at + 16), limits.maxDecodedBytes());
            s.recordsEnd = u(metadata, at + 20);
            s.idBytes = count(u(metadata, at + 24), limits.maxDecodedBytes());
            s.relations = count(u(metadata, at + 28), limits.maxDecodedBytes());
            s.mapCount = count(u(metadata, at + 32), limits.maxRows());
            s.copies = count(u(metadata, at + 36), limits.maxRows());
            if (s.count > 0 && recordSize == 0) {
                throw fail("INVALID", "Nonempty zero-width records");
            }
            if (!sparse && (s.recordsEnd != 0 || s.mapCount != 0)) {
                throw fail("UNSUPPORTED", "Fixed offset maps unsupported");
            }
            if (s.idBytes != ((flags & 4) != 0 ? s.count * 4L : 0)) {
                throw fail("INVALID", "ID list disagrees with storage mode");
            }
            if (sparse && (s.strings != 0 || s.mapCount != s.count)) {
                throw fail("UNSUPPORTED", "Sparse requires inline strings and exact maps");
            }
            if (sparse
                    && (s.recordsEnd < s.offset || (s.count == 0) != (s.recordsEnd == s.offset))) {
                throw fail("INVALID", "Sparse record area invalid");
            }
            if (s.relations != 0 && lookup == 0) {
                throw fail("INVALID", "Relationships require lookup column");
            }
            long span =
                    sparse
                            ? s.recordsEnd
                                    - s.offset
                                    + s.idBytes
                                    + s.copies * 8L
                                    + s.mapCount * 10L
                                    + s.relations
                            : s.count * (long) recordSize
                                    + s.strings
                                    + s.idBytes
                                    + s.copies * 8L
                                    + s.relations;
            s.end = s.offset + span;
            if (s.end > input.length || span > 0 && s.offset < auxEnd) {
                throw fail("INVALID", "Section outside source or overlaps descriptors");
            }
            s.hidden =
                    s.key != 0
                            && input.holes.stream()
                                    .anyMatch(
                                            hole ->
                                                    hole.offset() < s.end
                                                            && hole.offset() + hole.length()
                                                                    > s.offset);
            s.ids = sparse ? s.recordsEnd : s.offset + s.count * (long) recordSize + s.strings;
            s.copiesAt = s.ids + s.idBytes;
            s.maps = s.copiesAt + s.copies * 8L;
            long afterMaps = s.maps + s.mapCount * 6L;
            s.mapIds = (flags & 2) != 0 ? afterMaps : afterMaps + s.relations;
            s.relationsAt = (flags & 2) != 0 ? afterMaps + s.mapCount * 4L : afterMaps;
            sections.add(s);
            declared += s.count;
            copies += s.copies;
            stringSum += s.strings;
            if (s.hidden) {
                excludedSections++;
                excludedRows += s.count;
                excludedCopies += s.copies;
            }
        }
        if (declared != records || stringSum != strings) {
            throw fail("INVALID", "Header and section counts disagree");
        }
        if (records + copies > limits.maxRows()) {
            throw fail("LIMIT", "Rows and copies exceed bound");
        }
        if (excludedSections > 0
                && (!limits.allowPartialEncryptedSections() || format.equals("WDC3"))) {
            throw fail("ENCRYPTED", "Keyed sections cannot be read under this policy");
        }
        long charged = records * 96L + copies * 64L + paletteBytes * 2L + commonBytes;
        if (charged > limits.maxIndexBytes()) {
            throw fail("LIMIT", "Index reservation exceeded");
        }
        long packed = u(header, fieldsAt + 44);
        if (packed > recordSize) {
            throw fail("INVALID", "Packed data outside record");
        }
        Storage[] storage =
                empty
                        ? new Storage[0]
                        : storage(
                                metadata, sectionCount, fields, recordSize, packed, limits, token);
        var selected = definition.selectFile(build, hex(layoutHash), token);
        if (selected.blocks().size() != 1) {
            throw fail(
                    selected.blocks().isEmpty() ? "SCHEMA_MISSING" : "SCHEMA_AMBIGUOUS",
                    "Definition selection requires one block");
        }
        var bindings =
                bind(
                        selected.blocks().getFirst().fields(),
                        storage,
                        flags,
                        idIndex,
                        recordSize,
                        empty ? fields : -1,
                        token);
        long relBytes = 0, relEntries = 0, state = 0, scratch = 0;
        boolean hasRelation = bindings.stream().anyMatch(b -> b.field().relationStorage());
        if (!hasRelation && lookup != 0) {
            throw fail("UNSUPPORTED", "Unbound native relationship");
        }
        if (bindings.stream().anyMatch(b -> b.field().relationStorage() && b.field().nonInline())
                && lookup != 1
                && !empty) {
            throw fail("UNSUPPORTED", "External relation needs native lookup");
        }
        for (var s : sections) {
            if (s.relations != 0) {
                if (s.relations < 12 || (s.relations - 12) % 8 != 0) {
                    throw fail("INVALID", "Incomplete relationship block");
                }
                relBytes += s.relations;
                relEntries += (s.relations - 12) / 8;
                state += s.count * 9L + 128;
                scratch = Math.max(scratch, Math.min(65536, s.relations));
            }
            scratch = Math.max(scratch, Math.min(65536, Math.max(s.count * 14L, s.copies * 8L)));
        }
        if (relBytes > limits.maxRelationshipBytes()
                || relEntries > limits.maxRelationshipEntries()) {
            throw fail("LIMIT", "Relationship limits exceeded");
        }
        if (hasRelation) {
            charged += state + relEntries * 8 + records * 16L + scratch;
        }
        if (charged > limits.maxIndexBytes()) {
            throw fail("LIMIT", "Relationship index reservation exceeded");
        }
        int alignment = 4;
        if (sparse) {
            long nominal = 0;
            boolean stringSeen = false;
            for (var b : bindings) {
                if (b.storage() < 0) {
                    continue;
                }
                var st = storage[b.storage()];
                int width = st.noneWidth();
                if (st.kind != 0
                        || st.additional != 0
                        || width % 8 != 0
                        || st.fieldOffset != nominal
                        || isString(b.field()) && width != 32) {
                    throw fail("UNSUPPORTED", "Sparse storage must be ordered byte-aligned NONE");
                }
                if (width == 64 && stringSeen) {
                    throw fail("UNSUPPORTED", "Sparse64 field after string has unknown alignment");
                }
                stringSeen |= isString(b.field());
                nominal += width / 8L * b.field().arrayLength();
                if (width == 64) {
                    alignment = 8;
                }
            }
        }
        byte[] aux = input.read(descriptorEnd, paletteBytes + commonBytes, token);
        int auxAt = 0;
        for (int kind : new int[] {3, 4}) {
            for (var st : storage) {
                if (st.kind == kind) {
                    if (st.additional > paletteBytes - auxAt) {
                        throw fail("INVALID", "Palette slice exceeds block");
                    }
                    st.palette = new long[st.additional / 4];
                    for (int n = 0; n < st.palette.length; n++) {
                        token.check();
                        st.palette[n] = u(aux, auxAt);
                        auxAt += 4;
                    }
                }
            }
        }
        if (auxAt != paletteBytes) {
            throw fail("INVALID", "Unconsumed palette bytes");
        }
        for (var st : storage) {
            if (st.kind == 2) {
                if (st.additional > aux.length - auxAt) {
                    throw fail("INVALID", "Common slice exceeds block");
                }
                charged += st.additional / 8 * 64L;
                if (charged > limits.maxIndexBytes()) {
                    throw fail("LIMIT", "Common index reservation exceeded");
                }
                var map = new HashMap<Long, Long>();
                for (int n = 0; n < st.additional / 8; n++) {
                    token.check();
                    if (map.putIfAbsent(u(aux, auxAt), u(aux, auxAt + 4)) != null) {
                        throw fail("INVALID", "Duplicate common override");
                    }
                    auxAt += 8;
                }
                st.common = Map.copyOf(map);
            }
        }
        if (auxAt != aux.length) {
            throw fail("INVALID", "Unconsumed auxiliary bytes");
        }
        Db2Numbers.select(bindings, storage, limits, hex(tableHash), hex(layoutHash), token);
        var protectedIds = new HashMap<Long, Integer>();
        var listedIds = new HashMap<Long, Integer>();
        long prefix = auxEnd;
        for (int s = 0; s < sections.size(); s++) {
            if (sections.get(s).key != 0 && !format.equals("WDC3")) {
                int n = count(u(input.read(prefix, 4, token), 0), limits.maxRows());
                prefix += 4;
                charged += n * 64L;
                if (charged > limits.maxIndexBytes()) {
                    throw fail("LIMIT", "Encrypted ID index exceeds bound");
                }
                var ids = input.read(prefix, count(n * 4L, Integer.MAX_VALUE), token);
                prefix += n * 4L;
                for (int i = 0; i < n; i++) {
                    token.check();
                    long id = u(ids, i * 4);
                    if (protectedIds.containsKey(id) || listedIds.containsKey(id)) {
                        throw fail("INVALID", "Duplicate keyed identity");
                    }
                    (sections.get(s).hidden ? protectedIds : listedIds).put(id, s);
                }
            }
        }
        var populated =
                sections.stream()
                        .filter(s -> s.end > s.offset)
                        .sorted(Comparator.comparingLong(s -> s.offset))
                        .toList();
        long end = prefix;
        for (var s : populated) {
            if (s.offset < end) {
                throw fail("INVALID", "Overlapping section spans");
            }
            input.padding(end, s.offset, token);
            end = s.end;
        }
        if (end != input.length) {
            throw fail("INVALID", "Undeclared trailing bytes");
        }
        for (var hole : input.holes) {
            if (hole.offset() < prefix
                    || populated.stream()
                            .noneMatch(
                                    s ->
                                            s.key != 0
                                                    && hole.offset() >= s.offset
                                                    && hole.offset() < s.end
                                                    && hole.length() <= s.end - hole.offset())) {
                throw fail("PROJECTION_UNSUPPORTED", "Hole outside one keyed section");
            }
        }
        var rows = new ArrayList<Location>();
        var rowIndex = new HashMap<Long, Integer>();
        var copyRows = new ArrayList<Copy>();
        var copyIndex = new HashMap<Long, Integer>();
        int[] rowStart = new int[sectionCount + 1], copyStart = new int[sectionCount + 1];
        int global = 0;
        for (int si = 0; si < sectionCount; si++) {
            var s = sections.get(si);
            rowStart[si] = rows.size();
            copyStart[si] = copyRows.size();
            if (s.hidden) {
                global += s.count;
                continue;
            }
            long next = s.offset;
            for (int n = 0; n < s.count; n++) {
                token.check();
                long offset = s.offset + n * (long) recordSize;
                int size = recordSize;
                long id;
                if ((flags & 4) != 0) {
                    id = u(input.read(s.ids + n * 4L, 4, token), 0);
                } else {
                    var idBinding =
                            bindings.stream()
                                    .filter(b -> b.field().isId())
                                    .findFirst()
                                    .orElseThrow();
                    id =
                            Db2Numbers.raw(
                                    input.read(offset, recordSize, token),
                                    storage[idBinding.storage()],
                                    idBinding.field(),
                                    0,
                                    0);
                    if (Long.compareUnsigned(id, 0xffffffffL) > 0) {
                        throw fail("INVALID", "Inline ID exceeds unsigned32");
                    }
                }
                if (sparse) {
                    var map = input.read(s.maps + n * 6L, 6, token);
                    offset = u(map, 0);
                    size = h(map, 4);
                    if (u(input.read(s.mapIds + n * 4L, 4, token), 0) != id
                            || size == 0
                            || offset != next
                            || size > s.recordsEnd - next) {
                        throw fail("INVALID", "Sparse maps must cover records contiguously");
                    }
                    if (size > limits.maxRecordBytes()) {
                        throw fail("LIMIT", "Sparse record too large");
                    }
                    next += size;
                }
                if (protectedIds.containsKey(id) || rowIndex.putIfAbsent(id, rows.size()) != null) {
                    throw fail("INVALID", "Duplicate physical identity");
                }
                rows.add(new Location(offset, size, si, global + n, n, id));
            }
            if (sparse && next != s.recordsEnd) {
                if (s.recordsEnd - next < 1 || s.recordsEnd - next > 3 || s.recordsEnd % 4 != 0) {
                    throw fail("INVALID", "Sparse map leaves undeclared bytes");
                }
                input.padding(next, s.recordsEnd, token);
            }
            for (int n = 0; n < s.copies; n++) {
                token.check();
                var pair = input.read(s.copiesAt + n * 8L, 8, token);
                long target = u(pair, 0);
                if (protectedIds.containsKey(target)
                        || copyIndex.putIfAbsent(target, copyRows.size()) != null) {
                    throw fail("INVALID", "Duplicate copy identity");
                }
                copyRows.add(new Copy(target, u(pair, 4), si));
            }
            global += s.count;
        }
        rowStart[sectionCount] = rows.size();
        copyStart[sectionCount] = copyRows.size();
        for (var copy : copyRows) {
            if (rowIndex.containsKey(copy.target())) {
                throw fail("INVALID", "Copy target overlaps physical row");
            }
        }
        if (!format.equals("WDC3")) {
            for (int si = 0; si < sectionCount; si++) {
                var s = sections.get(si);
                if (s.key == 0 || s.hidden) {
                    continue;
                }
                int matched = 0;
                for (int n = rowStart[si]; n < rowStart[si + 1]; n++) {
                    if (!Integer.valueOf(si).equals(listedIds.get(rows.get(n).id()))) {
                        throw fail("INVALID", "Keyed list omits readable row");
                    }
                    matched++;
                }
                for (int n = copyStart[si]; n < copyStart[si + 1]; n++) {
                    if (Integer.valueOf(si).equals(listedIds.get(copyRows.get(n).target()))) {
                        matched++;
                    }
                }
                final int section = si;
                if (matched != listedIds.values().stream().filter(v -> v == section).count()) {
                    throw fail("INVALID", "Keyed list names absent identity");
                }
            }
        }
        var resolutions = new ArrayList<Resolution>();
        int[] protectedCopies = new int[sectionCount], unresolved = new int[sectionCount];
        long resolvedCopies = 0;
        for (var copy : copyRows) {
            token.check();
            long current = copy.source();
            var seen = new HashSet<Long>();
            seen.add(copy.target());
            Resolution resolution = null;
            for (int depth = 0; depth < limits.maxCopyDepth(); depth++) {
                token.check();
                if (!seen.add(current)) {
                    resolution = new Resolution(-1, 0, "INVALID");
                    break;
                }
                if (rowIndex.containsKey(current)) {
                    resolution = new Resolution(rowIndex.get(current), 0, null);
                    break;
                }
                if (protectedIds.containsKey(current)) {
                    resolution = new Resolution(-1, current, null);
                    break;
                }
                Integer ci = copyIndex.get(current);
                if (ci == null) {
                    resolution = new Resolution(-1, 0, "INVALID");
                    break;
                }
                current = copyRows.get(ci).source();
            }
            if (resolution == null) {
                resolution = new Resolution(-1, 0, "LIMIT");
            }
            resolutions.add(resolution);
            if (resolution.failure() != null) {
                unresolved[copy.section()]++;
            } else if (resolution.protectedCopy()) {
                excludedCopies++;
                protectedCopies[copy.section()]++;
            } else {
                resolvedCopies++;
            }
        }
        Relations[] relations =
                relationships(
                        input,
                        sections,
                        bindings,
                        rows,
                        rowIndex,
                        copyRows,
                        resolutions,
                        copyStart,
                        flags,
                        token);
        var columns = Db2Numbers.columns(bindings, storage, relations, limits);
        var infos = new ArrayList<Db2Types.Section>();
        for (int si = 0; si < sectionCount; si++) {
            var s = sections.get(si);
            infos.add(
                    new Db2Types.Section(
                            si,
                            s.count,
                            s.copies,
                            String.format(java.util.Locale.ROOT, "%016X", s.key),
                            s.key != 0,
                            protectedCopies[si],
                            unresolved[si],
                            s.key != 0 && !s.hidden));
        }
        return new Db2Table(
                input,
                sparse,
                alignment,
                sections,
                bindings,
                storage,
                recordSize,
                records,
                limits,
                relations,
                rows,
                rowIndex,
                rowStart,
                copyRows,
                resolutions,
                copyIndex,
                copyStart,
                protectedIds,
                excludedSections > 0,
                new Db2Types.Header(
                        schema,
                        hex(tableHash),
                        hex(layoutHash),
                        records,
                        copies,
                        recordSize,
                        columns,
                        format),
                infos,
                new Db2Types.Completion(
                        records,
                        rows.size(),
                        resolvedCopies,
                        excludedSections,
                        excludedRows,
                        excludedCopies,
                        excludedSections == 0 && excludedCopies == 0
                                ? "complete"
                                : "unencryptedSections",
                        input.length,
                        0),
                java.util.Arrays.stream(unresolved).sum());
    }

    static boolean isString(Field f) {
        return f.column().family().equals("string") || f.column().family().equals("locstring");
    }

    static String hex(long value) {
        return String.format(java.util.Locale.ROOT, "%08X", value);
    }

    static int count(long value, long max) {
        if (value < 0 || value > max || value > Integer.MAX_VALUE) {
            throw fail("LIMIT", "Count exceeds policy");
        }
        return (int) value;
    }

    private static Storage[] storage(
            byte[] metadata,
            int sections,
            int count,
            int recordSize,
            long packed,
            Db2Types.Limits limits,
            Cancellation token) {
        Storage[] result = new Storage[count];
        int fieldsAt = sections * 40, columnsAt = fieldsAt + count * 4;
        for (int i = 0; i < count; i++) {
            token.check();
            int at = columnsAt + i * 24;
            var s = new Storage();
            s.offset = h(metadata, at);
            s.width = h(metadata, at + 2);
            s.additional = count(u(metadata, at + 4), limits.maxAuxiliaryBytes());
            s.kind = count(u(metadata, at + 8), 5);
            s.p1 = (int) u(metadata, at + 12);
            s.p2 = (int) u(metadata, at + 16);
            s.p3 = (int) u(metadata, at + 20);
            s.fieldBits = (short) h(metadata, fieldsAt + i * 4);
            s.fieldOffset = h(metadata, fieldsAt + i * 4 + 2);
            result[i] = s;
            if (s.kind == 2) {
                if (s.additional % 8 != 0 || s.p2 != 0 || s.p3 != 0) {
                    throw fail("UNSUPPORTED", "Invalid common parameters");
                }
            } else if (s.kind == 3 || s.kind == 4) {
                if (s.additional == 0
                        || s.additional % 4 != 0
                        || s.width < 1
                        || s.width > 32
                        || s.offset != packed * 8 + Integer.toUnsignedLong(s.p1)
                        || s.width != s.p2
                        || s.offset + (long) s.width > recordSize * 8L) {
                    throw fail("UNSUPPORTED", "Invalid palette geometry");
                }
            } else if (s.kind == 1 || s.kind == 5) {
                if (s.additional != 0
                        || s.p3 != 0
                        || s.width < 1
                        || s.width > 64
                        || s.offset != packed * 8 + Integer.toUnsignedLong(s.p1)
                        || s.width != s.p2
                        || s.offset + (long) s.width > recordSize * 8L) {
                    throw fail("UNSUPPORTED", "Invalid immediate geometry");
                }
            } else if (s.additional != 0) {
                throw fail("UNSUPPORTED", "Uncompressed auxiliary bytes");
            }
        }
        return result;
    }

    private static List<Binding> bind(
            List<Field> fields,
            Storage[] storage,
            int flags,
            int idIndex,
            int recordSize,
            int emptyCount,
            Cancellation token) {
        if (fields.stream().filter(Field::isId).count() != 1
                || fields.stream().filter(Field::relationStorage).count() > 1) {
            throw fail("UNSUPPORTED", "One ID and at most one relationship required");
        }
        int inlineId = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).isId() && !fields.get(i).nonInline()) {
                inlineId = i;
            }
        }
        for (int i = 0; i < inlineId; i++) {
            if (fields.get(i).relationStorage() && fields.get(i).nonInline()) {
                throw fail("UNSUPPORTED", "External relation before inline ID unsupported");
            }
        }
        var result = new ArrayList<Binding>();
        int physical = 0;
        for (int i = 0; i < fields.size(); i++) {
            token.check();
            var f = fields.get(i);
            if (f.relationStorage()
                    && (f.isId()
                            || !f.column().family().equals("int")
                            || f.arrayLength() != 1
                            || !List.of(8, 16, 32).contains(f.integerBits()))) {
                throw fail("UNSUPPORTED", "Invalid relationship schema");
            }
            if (f.nonInline() && !f.isId() && !f.relationStorage()) {
                throw fail("UNSUPPORTED", "Noninline field lacks role");
            }
            if (f.isId()
                    && (!f.column().family().equals("int")
                            || f.arrayLength() != 1
                            || f.nonInline() != ((flags & 4) != 0)
                            || idIndex != i && !(f.nonInline() && idIndex == 65535))) {
                throw fail("UNSUPPORTED", "ID schema and header disagree");
            }
            if (f.nonInline()) {
                result.add(new Binding(f, -1));
                continue;
            }
            if (emptyCount >= 0) {
                result.add(new Binding(f, -1));
                physical++;
                continue;
            }
            if (physical >= storage.length) {
                throw fail("SCHEMA_MISMATCH", "Definition has excess physical fields");
            }
            var s = storage[physical];
            if (s.kind == 0) {
                int width = s.noneWidth();
                if (width < 1
                        || width > 64
                        || s.fieldOffset * 8L + width * (long) f.arrayLength() > recordSize * 8L
                        || s.offset != s.fieldOffset * 8L
                        || s.width != width * (long) f.arrayLength()) {
                    throw fail("UNSUPPORTED", "Uncompressed geometry invalid");
                }
            }
            if (f.arrayLength() > 1 && s.kind != 0 && s.kind != 4) {
                throw fail("UNSUPPORTED", "Unsupported compressed array");
            }
            if (s.kind == 4 && s.p3 != f.arrayLength() || s.kind == 3 && s.p3 != 0 && s.p3 != 1) {
                throw fail("SCHEMA_MISMATCH", "Palette cardinality disagrees");
            }
            if (isString(f)
                    && (s.kind != 0
                            || f.arrayLength() > 1 && ((flags & 1) != 0 || s.noneWidth() != 32))) {
                throw fail("UNSUPPORTED", "Unsupported string storage");
            }
            if (f.isId() && s.kind == 2) {
                throw fail("UNSUPPORTED", "Common inline ID unsupported");
            }
            result.add(new Binding(f, physical++));
        }
        if (physical != (emptyCount >= 0 ? emptyCount : storage.length)) {
            throw fail("SCHEMA_MISMATCH", "Definition misses physical fields");
        }
        return List.copyOf(result);
    }

    private static Relations[] relationships(
            Db2Input input,
            List<Section> sections,
            List<Binding> fields,
            List<Location> rows,
            Map<Long, Integer> rowIndex,
            List<Copy> copies,
            List<Resolution> resolutions,
            int[] copyStart,
            int flags,
            Cancellation token) {
        var result = new Relations[sections.size()];
        var relation =
                fields.stream().filter(b -> b.field().relationStorage()).findFirst().orElse(null);
        if (relation == null) {
            return result;
        }
        for (int si = 0; si < sections.size(); si++) {
            token.check();
            var s = sections.get(si);
            if (s.hidden || s.relations == 0) {
                continue;
            }
            var header = input.read(s.relationsAt, 12, token);
            int count = count(u(header, 0), s.count + (long) s.copies);
            if (s.relations != 12 + count * 8L) {
                throw fail("INVALID", "Relationship count disagrees with block");
            }
            var extra = new int[s.count];
            for (int c = copyStart[si]; c < copyStart[si + 1]; c++) {
                var r = resolutions.get(c);
                if (r.failure() == null && r.row() >= 0 && rows.get(r.row()).section() == si) {
                    extra[rows.get(r.row()).local()]++;
                }
            }
            var map = new Relations(s.count);
            result[si] = map;
            for (int n = 0; n < count; n++) {
                token.check();
                var pair = input.read(s.relationsAt + 12 + n * 8L, 8, token);
                long parent = u(pair, 0), key = u(pair, 4);
                int local;
                if ((flags & 2) != 0) {
                    Integer row = rowIndex.get(key);
                    local =
                            row == null || rows.get(row).section() != si
                                    ? -1
                                    : rows.get(row).local();
                } else {
                    local = key < s.count ? (int) key : -1;
                }
                if (local < 0 || local >= s.count) {
                    throw fail("INVALID", "Relationship key outside section");
                }
                if (map.present[local]) {
                    if (extra[local]-- == 0) {
                        throw fail("INVALID", "Duplicate relationship key");
                    }
                    if (map.values[local] != parent) {
                        throw fail("SCHEMA_MISMATCH", "Relationship duplicate parents disagree");
                    }
                    continue;
                }
                map.present[local] = true;
                map.values[local] = parent;
                if (relation.field().nonInline() && relation.field().column().verifiedName()) {
                    Db2Numbers.relationship(parent, relation.field());
                }
            }
        }
        return result;
    }
}
