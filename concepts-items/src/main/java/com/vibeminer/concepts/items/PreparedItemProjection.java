package com.vibeminer.concepts.items;

import com.vibeminer.concepts.items.PreparedItemTypes.Agreement;
import com.vibeminer.concepts.items.PreparedItemTypes.Binding;
import com.vibeminer.concepts.items.PreparedItemTypes.CellState;
import com.vibeminer.concepts.items.PreparedItemTypes.Compilation;
import com.vibeminer.concepts.items.PreparedItemTypes.CopyMetadata;
import com.vibeminer.concepts.items.PreparedItemTypes.FactGroup;
import com.vibeminer.concepts.items.PreparedItemTypes.FactId;
import com.vibeminer.concepts.items.PreparedItemTypes.IdColumn;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueChannel;
import com.vibeminer.concepts.items.PreparedItemTypes.ItemView;
import com.vibeminer.concepts.items.PreparedItemTypes.Omission;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceEvidence;
import com.vibeminer.concepts.items.PreparedItemTypes.RowState;
import com.vibeminer.concepts.items.PreparedItemTypes.ScalarKind;
import com.vibeminer.concepts.items.PreparedItemTypes.Selection;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceCell;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceFacts;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceRow;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceSchema;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceStamp;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure sourced item facts over independently supplied halves; never acquires or prepares data. */
public final class PreparedItemProjection {
    public static final String POLICY_REVISION = "prepared-item-facts-v1",
            ID_POLICY = "item-row-id-unsigned32-decoder-v1";
    public static final int MAX_PROFILE_BYTES = 65536, MAX_BINDINGS = 64, MAX_TEXT = 8192;

    private PreparedItemProjection() {}

    public static Compilation compile(SourceSchema input) {
        return PreparedItemCompiler.compile(input);
    }

    public static Compilation compile(SourceSchema input, Selection selection) {
        if (!input.selection().equals(selection)) {
            throw new IllegalArgumentException("Source selection differs");
        }
        return compile(input);
    }

    public static SourceFacts projectSource(SourceProfile profile, SourceRow input) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(input);
        if (input.state() != RowState.PRESENT) {
            return absent(
                    profile, input.itemId(), input.state(), input.issueChannel(), input.evidence());
        }
        var row = input.row();
        if (row.id() != input.itemId() || row.id() < 0 || row.id() > 0xffffffffL) {
            return invalid(profile, input.itemId(), "ITEM_ROW_ID_MISMATCH");
        }
        if (row.values().size() != profile.columnCount()) {
            if (row.values().size() > profile.columnCount()) {
                return invalid(profile, input.itemId(), "ITEM_ROW_SHAPE_AMBIGUOUS");
            }
            for (int missing = row.values().size(); missing < profile.columnCount(); missing++) {
                final int column = missing;
                if (input.overrides().stream().noneMatch(issue -> issue.columnIndex() == column)) {
                    return invalid(profile, input.itemId(), "ITEM_ROW_SHAPE_AMBIGUOUS");
                }
            }
        }
        for (var column : profile.idColumns()) {
            if (column.columnIndex() >= row.values().size()
                    || !Long.toString(row.id()).equals(row.values().get(column.columnIndex()))) {
                return invalid(profile, input.itemId(), "ITEM_ID_CELL_MISMATCH");
            }
        }
        if (input.overrides().stream()
                .anyMatch(value -> value.columnIndex() >= profile.columnCount())) {
            return invalid(profile, input.itemId(), "ITEM_OVERRIDE_COLUMN");
        }
        var channel = row.textIssues() == null ? IssueChannel.UNKNOWN : input.issueChannel();
        if (input.overrides().stream().anyMatch(value -> value.columnIndex() == -1)) {
            channel = IssueChannel.LIMITED;
        }
        if (row.textIssues() != null
                && row.textIssues().stream()
                        .anyMatch(
                                value ->
                                        value.columnIndex() < 0
                                                || value.columnIndex() >= profile.columnCount())) {
            channel = IssueChannel.LIMITED;
        }
        var copy = copy(row.isCopy(), row.copySourceId());
        var cells = new ArrayList<SourceCell>();
        for (var binding : profile.bindings()) {
            String raw =
                    binding.columnIndex() < row.values().size()
                            ? ItemLayout.cell(row, binding.columnIndex())
                            : null;
            CellState state = CellState.VALUE;
            String value = null;
            var issues = new ArrayList<String>();
            String rawEvidence = prefix(raw);
            var override =
                    input.overrides().stream()
                            .filter(v -> v.columnIndex() == binding.columnIndex())
                            .min(java.util.Comparator.comparingInt(v -> restriction(v.state())))
                            .orElse(null);
            if (override != null) {
                state = override.state();
                issues.add(override.code());
            } else if (raw == null) {
                state = CellState.UNKNOWN;
                issues.add("ITEM_CELL_UNAVAILABLE");
            } else if (ItemLayout.issue(row, binding.columnIndex())) {
                state = CellState.UNKNOWN;
                for (var issue : row.textIssues()) {
                    if (issue.columnIndex() != binding.columnIndex()) {
                        continue;
                    }
                    if (issue.code().equals("DB2_TEXT_ENCODING_INVALID")) {
                        state = CellState.INVALID;
                    }
                    if (issues.size() < 8) {
                        String boundedCode = prefix(issue.code());
                        issues.add(
                                boundedCode == null || boundedCode.isBlank()
                                        ? "ITEM_SOURCE_ISSUE_UNKNOWN"
                                        : boundedCode);
                    }
                    if (!issue.rawBytesHex().isEmpty()
                            && (rawEvidence == null || rawEvidence.equals(prefix(raw)))) {
                        rawEvidence = prefix("raw-bytes:" + prefix(issue.rawBytesHex()));
                    }
                }
                if (state == CellState.INVALID && !issues.contains("DB2_TEXT_ENCODING_INVALID")) {
                    if (issues.size() == 8) {
                        issues.remove(7);
                    }
                    issues.add("DB2_TEXT_ENCODING_INVALID");
                }
            } else if ((binding.kind() == ScalarKind.TEXT || binding.relation())
                    && channel != IssueChannel.KNOWN) {
                state = CellState.UNKNOWN;
                issues.add("ITEM_ISSUE_CHANNEL_UNKNOWN");
            } else if (raw.length() > MAX_TEXT) {
                state = CellState.LIMITED;
                issues.add("ITEM_CELL_TEXT_LIMIT");
            } else {
                try {
                    if (binding.kind() == ScalarKind.TEXT) {
                        utf8(raw);
                        if (binding.key().fact() == FactId.NAME) {
                            var name = ItemLayout.projectedName(raw, false);
                            state =
                                    name.state() == ItemNameState.EMPTY
                                            ? CellState.KNOWN_EMPTY
                                            : CellState.VALUE;
                        } else {
                            state = raw.isEmpty() ? CellState.KNOWN_EMPTY : CellState.VALUE;
                        }
                        value = state == CellState.VALUE ? raw : null;
                    } else {
                        if (!ItemLayout.canonical(raw)) {
                            throw new IllegalArgumentException("Invalid recorded integer");
                        }
                        var n = new BigInteger(raw);
                        int width = binding.bits();
                        var min =
                                binding.unsigned()
                                        ? BigInteger.ZERO
                                        : BigInteger.ONE.shiftLeft(width - 1).negate();
                        var max =
                                BigInteger.ONE
                                        .shiftLeft(binding.unsigned() ? width : width - 1)
                                        .subtract(BigInteger.ONE);
                        if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
                            throw new IllegalArgumentException("Recorded integer exceeds schema");
                        }
                        value = n.toString();
                    }
                } catch (IllegalArgumentException | CharacterCodingException invalid) {
                    state = CellState.INVALID;
                    issues.add("ITEM_CELL_INVALID");
                }
            }
            cells.add(
                    new SourceCell(
                            binding.key(),
                            state,
                            binding.kind(),
                            value,
                            rawEvidence,
                            row.id(),
                            binding.columnIndex(),
                            copy,
                            issues));
        }
        var issues =
                row.values().size() == profile.columnCount()
                        ? List.<String>of()
                        : List.of("ITEM_ROW_SHAPE_PARTIAL");
        return new SourceFacts(
                input.itemId(),
                profile.stamp(),
                profile.state(),
                RowState.PRESENT,
                channel,
                cells,
                profile.omissions(),
                issues,
                input.evidence());
    }

    public static SourceFacts unobservedSource(
            long itemId,
            SourceStamp stamp,
            SourceState sourceState,
            RowState rowState,
            PresenceEvidence evidence,
            Selection selection) {
        PreparedItemTypes.id(itemId);
        Objects.requireNonNull(selection);
        Objects.requireNonNull(evidence);
        if (rowState == RowState.PRESENT) {
            throw new IllegalArgumentException("Unobserved source has no present row");
        }
        PreparedItemTypes.presence(rowState, evidence);
        return new SourceFacts(
                itemId,
                stamp,
                sourceState,
                rowState,
                IssueChannel.UNKNOWN,
                List.of(),
                List.of(),
                List.of("ITEM_SOURCE_UNOBSERVED"),
                evidence);
    }

    public static ItemView combine(
            long itemId, SourceFacts item, SourceFacts sparse, Selection selection) {
        PreparedItemTypes.id(itemId);
        Objects.requireNonNull(item);
        Objects.requireNonNull(sparse);
        Objects.requireNonNull(selection);
        if (item.itemId() != itemId
                || sparse.itemId() != itemId
                || item.stamp().kind() != SourceKind.ITEM
                || sparse.stamp().kind() != SourceKind.ITEM_SPARSE
                || !item.stamp().build().equals(sparse.stamp().build())
                || !item.stamp().locale().equals(sparse.stamp().locale())) {
            throw new IllegalArgumentException("Source halves differ");
        }
        var groups = new ArrayList<FactGroup>();
        for (var fact : FactId.values()) {
            if (!selection.families().contains(fact.family())) {
                continue;
            }
            var left = item.cells().stream().filter(c -> c.key().fact() == fact).toList();
            var right = sparse.cells().stream().filter(c -> c.key().fact() == fact).toList();
            var cells = new ArrayList<SourceCell>(left);
            cells.addAll(right);
            if (cells.isEmpty()) {
                continue;
            }
            var sources = EnumSet.noneOf(SourceKind.class);
            if (!left.isEmpty()) {
                sources.add(SourceKind.ITEM);
            }
            if (!right.isEmpty()) {
                sources.add(SourceKind.ITEM_SPARSE);
            }
            boolean l = known(left), r = known(right);
            Agreement agreement;
            if (!left.isEmpty() && !l || !right.isEmpty() && !r) {
                agreement = Agreement.INDETERMINATE;
            } else if (l && r) {
                agreement =
                        !comparable(left, right)
                                ? Agreement.INDETERMINATE
                                : equalVectors(left, right) ? Agreement.AGREE : Agreement.DISAGREE;
            } else {
                agreement = Agreement.SINGLE_KNOWN_SOURCE;
            }
            groups.add(new FactGroup(fact, cells, agreement, sources));
        }
        SourceCell name =
                sparse.cells().stream()
                        .filter(c -> c.key().fact() == FactId.NAME)
                        .findFirst()
                        .orElse(null);
        return new ItemView(itemId, item, sparse, groups, name, List.of(), POLICY_REVISION);
    }

    private static int restriction(CellState state) {
        return switch (state) {
            case INVALID -> 0;
            case PROTECTED -> 1;
            case LIMITED -> 2;
            default -> 3;
        };
    }

    private static boolean known(List<SourceCell> cells) {
        return !cells.isEmpty()
                && cells.stream()
                        .allMatch(
                                c ->
                                        c.state() == CellState.VALUE
                                                || c.state() == CellState.KNOWN_EMPTY);
    }

    private static boolean comparable(List<SourceCell> left, List<SourceCell> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).kind() != right.get(i).kind()
                    || left.get(i).key().arraySlot() != right.get(i).key().arraySlot()
                    || !left.get(i).key().semantics().equals(right.get(i).key().semantics())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalVectors(List<SourceCell> left, List<SourceCell> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).key().arraySlot() != right.get(i).key().arraySlot()
                    || left.get(i).kind() != right.get(i).kind()
                    || left.get(i).state() != right.get(i).state()
                    || !Objects.equals(left.get(i).value(), right.get(i).value())) {
                return false;
            }
        }
        return true;
    }

    private static SourceFacts invalid(SourceProfile profile, long id, String code) {
        var facts =
                absent(
                        profile,
                        id,
                        RowState.INVALID,
                        IssueChannel.UNKNOWN,
                        PresenceEvidence.unknown());
        return new SourceFacts(
                id,
                profile.stamp(),
                SourceState.INVALID,
                RowState.INVALID,
                IssueChannel.UNKNOWN,
                facts.cells(),
                profile.omissions(),
                List.of(code),
                PresenceEvidence.unknown());
    }

    private static SourceFacts absent(
            SourceProfile profile,
            long id,
            RowState rowState,
            IssueChannel channel,
            PresenceEvidence evidence) {
        var state =
                switch (rowState) {
                    case MISSING -> CellState.MISSING;
                    case PROTECTED -> CellState.PROTECTED;
                    case INVALID -> CellState.INVALID;
                    case LIMITED -> CellState.LIMITED;
                    default -> CellState.UNKNOWN;
                };
        var cells =
                profile.bindings().stream()
                        .map(
                                binding ->
                                        new SourceCell(
                                                binding.key(),
                                                state,
                                                binding.kind(),
                                                null,
                                                null,
                                                null,
                                                binding.columnIndex(),
                                                new CopyMetadata(null, null, CellState.UNKNOWN),
                                                List.of()))
                        .toList();
        return new SourceFacts(
                id,
                profile.stamp(),
                profile.state(),
                rowState,
                channel,
                cells,
                profile.omissions(),
                List.of(),
                evidence);
    }

    private static CopyMetadata copy(boolean copy, Long source) {
        if (!copy) {
            return new CopyMetadata(
                    false, null, source == null ? CellState.VALUE : CellState.INVALID);
        }
        return source == null
                ? new CopyMetadata(true, null, CellState.UNKNOWN)
                : source < 0 || source > 0xffffffffL
                        ? new CopyMetadata(true, null, CellState.INVALID)
                        : new CopyMetadata(true, source, CellState.VALUE);
    }

    static long estimate(
            SourceStamp stamp,
            Selection selection,
            List<IdColumn> ids,
            List<Binding> bindings,
            List<Omission> omissions) {
        long total = 2048;
        for (String value :
                java.util.Arrays.asList(
                        stamp.locale(),
                        stamp.tableName(),
                        stamp.contentKey(),
                        stamp.layoutHash(),
                        stamp.definitionSha256(),
                        stamp.blockIdentity(),
                        stamp.readerRevision(),
                        stamp.readerOptions(),
                        stamp.rowEncoding(),
                        stamp.graphIdentity(),
                        stamp.effectiveKeysFingerprint(),
                        selection.policyRevision())) {
            if (value != null) {
                total += 64L + 4L * value.length();
            }
        }
        total += selection.families().size() * 64L;
        for (var id : ids) {
            total += 128L + 4L * id.name().length();
        }
        for (var omission : omissions) {
            total += 128L + 4L * omission.code().length();
        }
        for (var binding : bindings) {
            total +=
                    512L
                            + 4L * binding.key().field().length()
                            + 4L * binding.key().semantics().length();
            if (binding.referenceTable() != null) {
                total += 4L * binding.referenceTable().length();
            }
            if (binding.referenceColumn() != null) {
                total += 4L * binding.referenceColumn().length();
            }
        }
        return total;
    }

    static byte[] utf8(String value) throws CharacterCodingException {
        var encoded =
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value));
        var bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    static String prefix(String value) {
        if (value == null) {
            return null;
        }
        var out = new StringBuilder();
        for (int at = 0; at < value.length() && out.length() < 128; ) {
            int cp = value.codePointAt(at);
            if (Character.isSurrogate(value.charAt(at)) && Character.charCount(cp) == 1) {
                out.append('?');
            } else if (out.length() + Character.charCount(cp) <= 128) {
                out.appendCodePoint(cp);
            } else {
                break;
            }
            at += Character.charCount(cp);
        }
        return out.toString();
    }

    static String hash(Bytes writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid profile metadata", invalid);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    @FunctionalInterface
    interface Bytes {
        void write(DataOutputStream out) throws IOException;
    }

    static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = utf8(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
