package com.vibeminer.concepts.comparison.items;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.items.PreparedItemProjection;
import com.vibeminer.concepts.items.PreparedItemTypes;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceFacts;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Private session-stage format: dictionary indexes plus values and bounded row/cell evidence. */
public final class ItemsStageCodec {
    public static final long MAX_STAGE_RETAINED = 2L << 20, MAX_PROJECT_WORK = 3L << 20;
    public static final int MAX_SOURCE_META = 128 << 10, MAX_FRAME = 1 << 20;
    private static final String META = "items-stage-meta-v1", CELL = "items-stage-value-v1";
    private final ItemsSnapshot snapshot;

    /** A policy refusal, distinct from malformed input. Integration maps this to LIMITED. */
    public static final class Limit
            extends com.vibeminer.concepts.comparison.ComparisonPolicyLimit {
        private static final long serialVersionUID = 1L;

        public Limit(String message) {
            super(message);
        }
    }

    public ItemsStageCodec(ItemsSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    /** Conservative successful-output bound, available before source row reads/projection. */
    public long maximumSourceRetainedBytes(ItemsSnapshot.SourceRef ref) {
        var p = snapshot.profile(ref);
        long values = 0;
        if (p != null) {
            for (var binding : p.bindings()) {
                values += binding.kind() == PreparedItemTypes.ScalarKind.TEXT ? 4L * 8192 : 4L * 21;
            }
        }
        return Math.min(
                MAX_STAGE_RETAINED,
                8192L
                        + 4L * ((MAX_SOURCE_META + 2L) / 3 * 4)
                        + values
                        + (p == null ? 0 : p.bindings().size() * 1024L));
    }

    /** Estimates stage output conservatively, before any encoding buffer is allocated. */
    public long estimateSource(SourceFacts facts) {
        long meta = metadataEstimate(facts), values = 0;
        for (var cell : facts.cells()) {
            if (cell.value() != null) {
                values += 4L * cell.value().length();
            }
        }
        return 8192L + 4L * ((meta + 2) / 3 * 4) + values + 1024L * facts.cells().size();
    }

    public Record encode(ItemsSnapshot.SourceRef ref, SourceFacts facts) {
        var source = snapshot.source(ref);
        if (!source.stamp().equals(facts.stamp())) {
            throw new IllegalArgumentException("Source facts differ from registered stamp");
        }
        if (metadataEstimate(facts) > MAX_SOURCE_META
                || estimateSource(facts) > MAX_STAGE_RETAINED) {
            throw new Limit("Source stage record exceeds policy");
        }
        var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
        for (var cell : facts.cells()) {
            int index = snapshot.bindingIndex(ref, cell.key());
            var binding = snapshot.binding(index).binding();
            if (cell.columnIndex() != binding.columnIndex() || cell.kind() != binding.kind()) {
                throw new IllegalArgumentException("Source cell differs from binding");
            }
            validateInteger(binding, cell.state(), cell.value());
            var field =
                    new ComparisonTypes.Field(
                            CELL,
                            kind(cell.kind()),
                            valueState(cell.state()),
                            cell.value(),
                            ComparisonTypes.Aspect.CONTENT,
                            List.of());
            if (fields.put("b" + index, field) != null) {
                throw new IllegalArgumentException("Duplicate cell binding");
            }
        }
        var bytes = metadata(ref, facts);
        fields.put(
                "m" + ref.index(),
                new ComparisonTypes.Field(
                        META,
                        ComparisonTypes.ValueKind.TEXT,
                        ComparisonTypes.ValueState.VALUE,
                        Base64.getEncoder().encodeToString(bytes),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        List.of()));
        var record = new Record(key(facts.itemId()), fields, List.of());
        validateStage(record);
        return record;
    }

    public ItemsSnapshot.SourceRef sourceRef(Record record) {
        var refs = refs(record);
        if (refs.size() != 1) {
            throw new IllegalArgumentException("Expected one source stage");
        }
        return refs.getFirst();
    }

    public SourceFacts decodeSource(Record record) {
        var ref = sourceRef(record);
        return decode(record, ref);
    }

    /** Merge already sorted source rows for one endpoint, preserving qualified conflicts. */
    public Record candidate(ItemsSnapshot.Side side, Record item, Record sparse) {
        if (item == null && sparse == null) {
            throw new IllegalArgumentException("No observed item identity");
        }
        String key = item == null ? sparse.key() : item.key();
        id(key);
        var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
        for (var kind : SourceKind.values()) {
            var record = kind == SourceKind.ITEM ? item : sparse;
            var expected = snapshot.ref(side, kind);
            if (record == null) {
                var info = snapshot.source(expected);
                SourceFacts missing;
                if (info.profile() != null) {
                    missing =
                            PreparedItemProjection.projectSource(
                                    info.profile(),
                                    new PreparedItemTypes.SourceRow(
                                            id(key),
                                            PreparedItemTypes.RowState.UNKNOWN,
                                            null,
                                            PreparedItemTypes.IssueChannel.UNKNOWN,
                                            List.of(),
                                            PreparedItemTypes.PresenceEvidence.unknown()));
                } else {
                    missing =
                            PreparedItemProjection.unobservedSource(
                                    id(key),
                                    info.stamp(),
                                    info.state(),
                                    PreparedItemTypes.RowState.UNKNOWN,
                                    PreparedItemTypes.PresenceEvidence.unknown(),
                                    snapshot.selection());
                }
                record = encode(expected, missing);
            } else {
                if (!record.key().equals(key) || !sourceRef(record).equals(expected)) {
                    throw new IllegalArgumentException("Joined source identity differs");
                }
                decodeSource(record);
            }
            for (var field : record.fields().entrySet()) {
                if (fields.put(field.getKey(), field.getValue()) != null) {
                    throw new IllegalArgumentException("Duplicate joined stage field");
                }
            }
        }
        var result = new Record(key, fields, List.of());
        validateStage(result);
        return result;
    }

    /**
     * Bound preflight precedes decoding/projection; null excludes wholly unqualified identities.
     */
    public Record projectCandidate(Record stage) {
        validateStage(stage);
        var refs = refs(stage);
        if (refs.size() != 2 || refs.get(0).side() != refs.get(1).side()) {
            throw new IllegalArgumentException("Candidate must contain one endpoint");
        }
        var side = refs.getFirst().side();
        long itemId = id(stage.key());
        long outputEstimate = 4096, metaEstimate = 0;
        for (var ref : refs) {
            metaEstimate += encodedMetadataBytes(stage, ref) * 3L;
        }
        for (var pair : snapshot.pairScope()) {
            for (var spec : pair.assessed()) {
                var binding = side == ItemsSnapshot.Side.A ? spec.a() : spec.b();
                var value = stage.fields().get("b" + binding.index());
                outputEstimate +=
                        8192L
                                + 4L * spec.key().length()
                                + 4L * binding.binding().key().semantics().length()
                                + (value != null && value.value() != null
                                        ? 4L * value.value().length()
                                        : 0);
            }
        }
        if (outputEstimate > MAX_STAGE_RETAINED
                || outputEstimate + metaEstimate + 262144 > MAX_PROJECT_WORK) {
            throw new Limit("Items final projection exceeds planned workspace");
        }
        var facts = new HashMap<ItemsSnapshot.SourceRef, SourceFacts>();
        boolean qualified = false;
        for (var ref : refs) {
            var source = decode(stage, ref);
            facts.put(ref, source);
            boolean observed =
                    source.rowState() == PreparedItemTypes.RowState.PRESENT
                            || source.rowState() == PreparedItemTypes.RowState.INVALID
                            || source.rowState() == PreparedItemTypes.RowState.PROTECTED;
            qualified |= observed && snapshot.canPublishIds(ref);
        }
        if (!qualified) {
            return null;
        }
        var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
        for (var pair : snapshot.pairScope()) {
            for (var spec : pair.assessed()) {
                var entry = side == ItemsSnapshot.Side.A ? spec.a() : spec.b();
                var source = facts.get(entry.source());
                var cell =
                        source.cells().stream()
                                .filter(c -> c.key().equals(entry.binding().key()))
                                .findFirst()
                                .orElse(null);
                var state =
                        cell == null
                                ? ComparisonTypes.ValueState.UNKNOWN
                                : valueState(cell.state());
                String value = cell == null ? null : cell.value();
                String raw =
                        cell == null
                                ? "source cell unobserved"
                                : evidence(cell, source.presenceEvidence());
                var stamp = snapshot.source(entry.source()).stamp();
                var evidence =
                        List.of(
                                new ComparisonTypes.Evidence(
                                        stamp.tableName(),
                                        stamp.graphIdentity(),
                                        entry.binding().key().field()
                                                + (entry.binding().key().array()
                                                        ? "["
                                                                + entry.binding().key().arraySlot()
                                                                + "]"
                                                        : ""),
                                        "column=" + entry.binding().columnIndex() + ";" + raw),
                                new ComparisonTypes.Evidence(
                                        "presence",
                                        source.presenceEvidence().identity() == null
                                                ? "unknown"
                                                : source.presenceEvidence().identity(),
                                        source.presenceEvidence().code(),
                                        source.presenceEvidence().kind().name()));
                fields.put(
                        spec.key(),
                        new ComparisonTypes.Field(
                                entry.binding().key().semantics(),
                                kind(entry.binding().kind()),
                                state,
                                value,
                                ComparisonTypes.Aspect.CONTENT,
                                evidence));
            }
        }
        var result = new Record(key(itemId), fields, List.of());
        if (retained(result) > MAX_STAGE_RETAINED || wire(result) > MAX_FRAME) {
            throw new Limit("Canonical item record exceeds policy");
        }
        return result;
    }

    private SourceFacts decode(Record record, ItemsSnapshot.SourceRef ref) {
        validateStage(record);
        var source = snapshot.source(ref);
        long id = id(record.key());
        byte[] bytes = decodeBase64(record.fields().get("m" + ref.index()).value());
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x49534331 || in.readInt() != ref.index()) {
                throw invalid();
            }
            var state = tag(in, PreparedItemTypes.SourceState.class);
            var row = tag(in, PreparedItemTypes.RowState.class);
            var channel = tag(in, PreparedItemTypes.IssueChannel.class);
            var presence =
                    new PreparedItemTypes.PresenceEvidence(
                            tag(in, PreparedItemTypes.PresenceKind.class),
                            text(in, 512),
                            text(in, 128));
            var issues = texts(in, 16, 128);
            int omissionsCount = count(in, 64);
            var omissions = new ArrayList<PreparedItemTypes.Omission>();
            var omissionIndices = new HashSet<Integer>();
            for (int o = 0; o < omissionsCount; o++) {
                int index = in.readInt();
                if (index < 0
                        || index >= source.omissions().size()
                        || !omissionIndices.add(index)) {
                    throw invalid();
                }
                omissions.add(source.omissions().get(index));
            }
            int n = count(in, 64);
            var cells = new ArrayList<PreparedItemTypes.SourceCell>();
            var used = new HashSet<Integer>();
            for (int c = 0; c < n; c++) {
                int index = in.readInt();
                var entry = snapshot.binding(index);
                if (!entry.source().equals(ref) || !used.add(index)) {
                    throw invalid();
                }
                var binding = entry.binding();
                var cellState = tag(in, PreparedItemTypes.CellState.class);
                Long rowId = in.readBoolean() ? id : null;
                int copy = in.readUnsignedByte();
                if (copy > 2) {
                    throw invalid();
                }
                Boolean copied = copy == 0 ? null : copy == 2;
                Long copyId = in.readBoolean() ? in.readLong() : null;
                if (copyId != null && (copyId < 0 || copyId > 0xffffffffL)) {
                    throw invalid();
                }
                var copyState = tag(in, PreparedItemTypes.CellState.class);
                String raw = text(in, 128);
                var cellIssues = texts(in, 8, 128);
                var value = record.fields().get("b" + index);
                if (value == null
                        || !value.semantics().equals(CELL)
                        || value.kind() != kind(binding.kind())
                        || value.state() != valueState(cellState)
                        || !value.evidence().isEmpty()) {
                    throw invalid();
                }
                validateInteger(binding, cellState, value.value());
                cells.add(
                        new PreparedItemTypes.SourceCell(
                                binding.key(),
                                cellState,
                                binding.kind(),
                                value.value(),
                                raw,
                                rowId,
                                binding.columnIndex(),
                                new PreparedItemTypes.CopyMetadata(copied, copyId, copyState),
                                cellIssues));
            }
            if (in.available() != 0) {
                throw invalid();
            }
            long actualFields =
                    record.fields().keySet().stream()
                            .filter(
                                    key ->
                                            key.startsWith("b")
                                                    && snapshot.binding(integer(key.substring(1)))
                                                            .source()
                                                            .equals(ref))
                            .count();
            if (actualFields != n) {
                throw invalid();
            }
            return new SourceFacts(
                    id, source.stamp(), state, row, channel, cells, omissions, issues, presence);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid compact item stage", failure);
        }
    }

    private byte[] metadata(ItemsSnapshot.SourceRef ref, SourceFacts facts) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(0x49534331);
                out.writeInt(ref.index());
                tag(out, facts.sourceState());
                tag(out, facts.rowState());
                tag(out, facts.issueChannel());
                tag(out, facts.presenceEvidence().kind());
                text(out, facts.presenceEvidence().identity());
                text(out, facts.presenceEvidence().code());
                texts(out, facts.issues());
                out.writeInt(facts.omissions().size());
                for (var omission : facts.omissions()) {
                    int index = snapshot.source(ref).omissions().indexOf(omission);
                    if (index < 0) {
                        throw invalid();
                    }
                    out.writeInt(index);
                }
                out.writeInt(facts.cells().size());
                for (var cell : facts.cells()) {
                    out.writeInt(snapshot.bindingIndex(ref, cell.key()));
                    tag(out, cell.state());
                    out.writeBoolean(cell.rowId() != null);
                    out.writeByte(cell.copy().copy() == null ? 0 : cell.copy().copy() ? 2 : 1);
                    out.writeBoolean(cell.copy().sourceId() != null);
                    if (cell.copy().sourceId() != null) {
                        out.writeLong(cell.copy().sourceId());
                    }
                    tag(out, cell.copy().state());
                    text(out, cell.rawEvidence());
                    texts(out, cell.issues());
                }
            }
            if (bytes.size() > MAX_SOURCE_META) {
                throw new Limit("Source metadata exceeds policy");
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid source metadata", failure);
        }
    }

    private List<ItemsSnapshot.SourceRef> refs(Record record) {
        var refs = new ArrayList<ItemsSnapshot.SourceRef>();
        for (var name : record.fields().keySet()) {
            if (name.startsWith("m")) {
                int index = integer(name.substring(1));
                if (index < 0 || index >= 4) {
                    throw invalid();
                }
                var side = index < 2 ? ItemsSnapshot.Side.A : ItemsSnapshot.Side.B;
                var kind = index % 2 == 0 ? SourceKind.ITEM : SourceKind.ITEM_SPARSE;
                var ref = snapshot.ref(side, kind);
                refs.add(ref);
            }
        }
        refs.sort(java.util.Comparator.comparingInt(ItemsSnapshot.SourceRef::index));
        if (refs.isEmpty() || refs.size() > 2) {
            throw invalid();
        }
        return refs;
    }

    private void validateStage(Record record) {
        Objects.requireNonNull(record);
        id(record.key());
        if (!record.evidence().isEmpty() || record.fields().size() > 130) {
            throw invalid();
        }
        var refs = refs(record);
        var present = new HashSet<ItemsSnapshot.SourceRef>(refs);
        for (var entry : record.fields().entrySet()) {
            var value = entry.getValue();
            if (value.aspect() != ComparisonTypes.Aspect.CONTENT
                            && value.aspect() != ComparisonTypes.Aspect.INTERPRETATION
                    || !value.evidence().isEmpty()) {
                throw invalid();
            }
            if (entry.getKey().startsWith("m")) {
                if (value.aspect() != ComparisonTypes.Aspect.INTERPRETATION
                        || !value.semantics().equals(META)
                        || value.kind() != ComparisonTypes.ValueKind.TEXT
                        || value.state() != ComparisonTypes.ValueState.VALUE
                        || value.value().length() > ((MAX_SOURCE_META + 2) / 3) * 4) {
                    throw invalid();
                }
            } else if (entry.getKey().startsWith("b")) {
                var binding = snapshot.binding(integer(entry.getKey().substring(1)));
                if (value.aspect() != ComparisonTypes.Aspect.CONTENT
                        || !present.contains(binding.source())
                        || !value.semantics().equals(CELL)
                        || value.kind() != kind(binding.binding().kind())
                        || value.value() != null && value.value().length() > 8192) {
                    throw invalid();
                }
            } else {
                throw invalid();
            }
        }
        if (retained(record) > MAX_STAGE_RETAINED || wire(record) > MAX_FRAME) {
            throw new Limit("Item stage frame exceeds policy");
        }
    }

    private long encodedMetadataBytes(Record stage, ItemsSnapshot.SourceRef ref) {
        var value = stage.fields().get("m" + ref.index());
        if (value == null) {
            throw invalid();
        }
        return (long) value.value().length() / 4 * 3 + 3;
    }

    private static byte[] decodeBase64(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > MAX_SOURCE_META) {
                throw invalid();
            }
            return bytes;
        } catch (IllegalArgumentException bad) {
            throw invalid();
        }
    }

    private static long metadataEstimate(SourceFacts facts) {
        long n =
                1024
                        + 4L * facts.omissions().size()
                        + stringEstimate(facts.presenceEvidence().identity())
                        + stringEstimate(facts.presenceEvidence().code());
        for (String issue : facts.issues()) {
            n += stringEstimate(issue);
        }
        for (var cell : facts.cells()) {
            n += 128 + stringEstimate(cell.rawEvidence());
            for (String issue : cell.issues()) {
                n += stringEstimate(issue);
            }
        }
        return n;
    }

    private static long stringEstimate(String value) {
        return 4L + (value == null ? 0 : 4L * value.length());
    }

    public static long retained(Record record) {
        long n = 1024 + 4L * record.key().length();
        for (var e : record.fields().entrySet()) {
            var f = e.getValue();
            n +=
                    512
                            + 4L * e.getKey().length()
                            + 4L * f.semantics().length()
                            + (f.value() == null ? 0 : 4L * f.value().length());
            for (var evidence : f.evidence()) {
                n +=
                        256
                                + 4L
                                        * (evidence.source().length()
                                                + evidence.identity().length()
                                                + (evidence.field() == null
                                                        ? 0
                                                        : evidence.field().length())
                                                + (evidence.raw() == null
                                                        ? 0
                                                        : evidence.raw().length()));
            }
        }
        return n;
    }

    private static long wire(Record record) {
        long n = 32 + utf8Size(record.key());
        for (var e : record.fields().entrySet()) {
            var f = e.getValue();
            n += 64 + utf8Size(e.getKey()) + utf8Size(f.semantics()) + utf8Size(f.value());
            for (var v : f.evidence()) {
                n +=
                        32
                                + utf8Size(v.source())
                                + utf8Size(v.identity())
                                + utf8Size(v.field())
                                + utf8Size(v.raw());
            }
        }
        return n;
    }

    private static long utf8Size(String value) {
        if (value == null) {
            return 0;
        }
        long n = 0;
        for (int at = 0; at < value.length(); ) {
            int cp = value.codePointAt(at);
            if (Character.isSurrogate(value.charAt(at)) && Character.charCount(cp) == 1) {
                throw invalid();
            }
            n += cp <= 127 ? 1 : cp <= 2047 ? 2 : cp <= 65535 ? 3 : 4;
            at += Character.charCount(cp);
        }
        return n;
    }

    private static void validateInteger(
            PreparedItemTypes.Binding binding, PreparedItemTypes.CellState state, String value) {
        if (state != PreparedItemTypes.CellState.VALUE
                || binding.kind() == PreparedItemTypes.ScalarKind.TEXT) {
            return;
        }
        if (value == null || !value.matches("0|-?[1-9][0-9]{0,19}")) {
            throw invalid();
        }
        var number = new java.math.BigInteger(value);
        int bits = binding.bits();
        var min =
                binding.unsigned()
                        ? java.math.BigInteger.ZERO
                        : java.math.BigInteger.ONE.shiftLeft(bits - 1).negate();
        var max =
                java.math.BigInteger.ONE
                        .shiftLeft(binding.unsigned() ? bits : bits - 1)
                        .subtract(java.math.BigInteger.ONE);
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw invalid();
        }
    }

    private static ComparisonTypes.ValueKind kind(PreparedItemTypes.ScalarKind value) {
        return value == PreparedItemTypes.ScalarKind.TEXT
                ? ComparisonTypes.ValueKind.TEXT
                : ComparisonTypes.ValueKind.INTEGER;
    }

    private static ComparisonTypes.ValueState valueState(PreparedItemTypes.CellState value) {
        return switch (value) {
            case VALUE -> ComparisonTypes.ValueState.VALUE;
            case KNOWN_EMPTY -> ComparisonTypes.ValueState.KNOWN_EMPTY;
            case PROTECTED -> ComparisonTypes.ValueState.PROTECTED;
            case UNSUPPORTED -> ComparisonTypes.ValueState.UNSUPPORTED;
            case INVALID -> ComparisonTypes.ValueState.INVALID;
            case LIMITED -> ComparisonTypes.ValueState.LIMITED;
            default -> ComparisonTypes.ValueState.UNKNOWN;
        };
    }

    private static String evidence(
            PreparedItemTypes.SourceCell cell, PreparedItemTypes.PresenceEvidence presence) {
        String value =
                "state="
                        + cell.state().name()
                        + ";row="
                        + (cell.rowId() == null ? "unknown" : cell.rowId())
                        + ";copy="
                        + cell.copy().copy()
                        + "/"
                        + cell.copy().sourceId()
                        + "/"
                        + cell.copy().state()
                        + ";issues="
                        + cell.issues()
                        + ";raw="
                        + cell.rawEvidence()
                        + ";presence="
                        + presence.kind()
                        + "/"
                        + presence.identity()
                        + "/"
                        + presence.code();
        if (value.length() <= 512) {
            return value;
        }
        int end = 500;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "[truncated]";
    }

    private static int integer(String value) {
        if (!value.matches("0|[1-9][0-9]{0,2}")) {
            throw invalid();
        }
        return Integer.parseInt(value);
    }

    private static long id(String value) {
        if (!value.matches("[0-9]{20}")) {
            throw invalid();
        }
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException bad) {
            throw invalid();
        }
        if (id < 0 || id > 0xffffffffL) {
            throw invalid();
        }
        return id;
    }

    private static String key(long value) {
        if (value < 0 || value > 0xffffffffL) {
            throw invalid();
        }
        return String.format(java.util.Locale.ROOT, "%020d", value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid compact item stage");
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = utf8(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in, int maxChars) throws IOException {
        int n = in.readInt();
        if (n == -1) {
            return null;
        }
        if (n < 0 || n > maxChars * 4) {
            throw invalid();
        }
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) {
            throw invalid();
        }
        String value =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
        if (value.length() > maxChars) {
            throw invalid();
        }
        return value;
    }

    private static byte[] utf8(String value) throws CharacterCodingException {
        var b =
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        return bytes;
    }

    private static void tag(DataOutputStream out, Enum<?> value) throws IOException {
        text(out, value.name());
    }

    private static <T extends Enum<T>> T tag(DataInputStream in, Class<T> type) throws IOException {
        String value = text(in, 64);
        if (value == null) {
            throw invalid();
        }
        return Enum.valueOf(type, value);
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw invalid();
        }
        return n;
    }

    private static void texts(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            text(out, value);
        }
    }

    private static List<String> texts(DataInputStream in, int count, int length)
            throws IOException {
        int n = count(in, count);
        var result = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            String value = text(in, length);
            if (value == null) {
                throw invalid();
            }
            result.add(value);
        }
        return result;
    }
}
