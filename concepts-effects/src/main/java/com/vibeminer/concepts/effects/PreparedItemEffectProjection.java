package com.vibeminer.concepts.effects;

import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Binding;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.BindingPair;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Cell;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CopyMetadata;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.EvidenceClass;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.FieldAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueChannel;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.LinkIdentity;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Omission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PairPlan;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RowState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceProfile;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceRow;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceSchema;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetObservation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetState;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure captured-row policies; never opens, acquires, indexes or prepares a source. */
public final class PreparedItemEffectProjection {
    public static final String POLICY_REVISION = "prepared-item-effects-v1";
    public static final String ID_POLICY = "effect-own-row-unsigned32-v1";
    public static final int MAX_PROFILE_BYTES = 65536, MAX_TEXT = 8192;

    private PreparedItemEffectProjection() {}

    public static Compilation compile(SourceSchema input) {
        Objects.requireNonNull(input);
        if (!readable(input.state())
                || input.header() == null
                || input.schema() == null
                || input.columns() == null) {
            return failure(IdentityAdmission.UNKNOWN, "EFFECT_SOURCE_UNAVAILABLE");
        }
        var stamp = input.stamp();
        var profile = stamp.kind().profile();
        var header = input.header();
        if (!matches(stamp.contentKey(), "[0-9a-f]{32}")
                || !matches(stamp.graphIdentity(), "[0-9a-f]{32}")
                || stamp.fileDataId() != profile.fileDataId()
                || !stamp.tableName().equals(profile.table())
                || !stamp.kind().tableHash().equalsIgnoreCase(header.tableHash())
                || !profile.layout().equalsIgnoreCase(header.layoutHash())
                || !profile.layout().equalsIgnoreCase(stamp.layoutHash())
                || WowLocale.tryParse(stamp.locale()) == null
                || !input.schema().locale().code().equals(stamp.locale())
                || !GeneratedTableSource.READER_REVISION.equals(stamp.readerRevision())
                || !GeneratedTableSource.ROW_ENCODING.equals(stamp.rowEncoding())
                || !matches(stamp.definitionSha256(), "[0-9a-f]{64}")
                || stamp.blockIdentity() == null
                || stamp.blockIdentity().isBlank()
                || stamp.readerOptions() == null
                || stamp.readerOptions().isBlank()
                || !header.columns().equals(input.columns())
                || input.schema().layoutHashes().stream()
                        .noneMatch(profile.layout()::equalsIgnoreCase)
                || input.schema().match() != TableSchema.SchemaMatch.BUILD
                        && input.schema().match() != TableSchema.SchemaMatch.LAYOUT) {
            return failure(IdentityAdmission.INVALID, "EFFECT_SOURCE_IDENTITY_INVALID");
        }
        var entry =
                new TableTypes.Entry(
                        stamp.fileDataId(),
                        stamp.tableName(),
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(
                                header.declaredRecords(),
                                0,
                                0,
                                0,
                                header.format(),
                                header.layoutHash()),
                        stamp.contentKey());
        if (!ItemEffectProfiles.qualifies(
                profile, new TableTypes.SchemaResult(entry, input.columns(), input.schema()))) {
            return failure(IdentityAdmission.INVALID, "EFFECT_PROFILE_UNSUPPORTED");
        }
        String identity =
                hash(
                        ID_POLICY,
                        stamp.kind().name(),
                        Long.toString(stamp.fileDataId()),
                        stamp.kind().tableHash(),
                        stamp.build().product(),
                        stamp.locale(),
                        stamp.readerRevision(),
                        stamp.rowEncoding());
        var bindings = new ArrayList<Binding>();
        var omissions = new ArrayList<Omission>();
        for (int i = 0; i < input.columns().size(); i++) {
            var column = input.columns().get(i);
            var field = input.schema().columns().get(i);
            var evidence =
                    column.interpretation() != null
                            ? EvidenceClass.RAW_PHYSICAL_INTEGER
                            : !field.verifiedName()
                                    ? EvidenceClass.UNVERIFIED_CANDIDATE
                                    : EvidenceClass.STRICT_NUMERIC;
            String fingerprint =
                    hash(
                            POLICY_REVISION,
                            identity,
                            profile.layout(),
                            stamp.definitionSha256(),
                            stamp.blockIdentity(),
                            stamp.readerOptions(),
                            column.name(),
                            Integer.toString(i),
                            Integer.toString(field.bits()),
                            Boolean.toString(field.unsigned()),
                            field.id().name(),
                            field.relation().name(),
                            evidence.name(),
                            String.valueOf(column.narrowing()));
            bindings.add(
                    new Binding(
                            stamp.kind(),
                            stamp.fileDataId(),
                            column.name(),
                            i,
                            field.bits(),
                            field.unsigned(),
                            field.relation() != TableSchema.KeyStorage.NONE,
                            evidence,
                            column.interpretation(),
                            fingerprint));
            if (evidence != EvidenceClass.STRICT_NUMERIC) {
                omissions.add(
                        new Omission(
                                column.name(),
                                CellState.UNSUPPORTED,
                                evidence == EvidenceClass.RAW_PHYSICAL_INTEGER
                                        ? "EFFECT_RAW_PHYSICAL_UNASSESSED"
                                        : "EFFECT_UNVERIFIED_UNASSESSED"));
            }
        }
        long compactBytes = 2048;
        for (var value : new String[] {stamp.toString(), identity}) {
            compactBytes += value.getBytes(StandardCharsets.UTF_8).length;
        }
        for (var binding : bindings) {
            compactBytes += 128L + binding.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        for (var omission : omissions) {
            compactBytes += 64L + omission.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        if (compactBytes > MAX_PROFILE_BYTES) {
            return failure(IdentityAdmission.LIMITED, "EFFECT_COMPACT_PROFILE_LIMIT");
        }
        return new Compilation(
                IdentityAdmission.ADMITTED,
                omissions.isEmpty() ? FieldAdmission.ADMITTED : FieldAdmission.PARTIAL,
                new SourceProfile(
                        stamp,
                        input.state(),
                        identity,
                        input.columns().size(),
                        bindings,
                        omissions),
                omissions);
    }

    public static SourceFacts projectSource(SourceProfile profile, SourceRow input) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(input);
        if (input.state() != RowState.PRESENT) {
            return new SourceFacts(
                    profile.stamp(),
                    input.rowId(),
                    input.state(),
                    input.state() == RowState.LIMITED
                            ? IdentityAdmission.LIMITED
                            : input.state() == RowState.INVALID
                                    ? IdentityAdmission.INVALID
                                    : IdentityAdmission.UNKNOWN,
                    new CopyMetadata(null, null, CellState.UNKNOWN),
                    List.of(),
                    List.of(new Omission(null, rowState(input.state()), "EFFECT_ROW_UNAVAILABLE")));
        }
        var row = input.row();
        if (row.id() != input.rowId() || row.values().size() > profile.columnCount()) {
            return invalid(profile, input, "EFFECT_ROW_SHAPE_OR_ID_INVALID");
        }
        if (row.textIssues() != null && row.textIssues().size() > 4096) {
            return new SourceFacts(
                    profile.stamp(),
                    input.rowId(),
                    RowState.LIMITED,
                    IdentityAdmission.LIMITED,
                    new CopyMetadata(null, null, CellState.UNKNOWN),
                    List.of(),
                    List.of(new Omission(null, CellState.LIMITED, "EFFECT_ISSUE_LIMIT")));
        }
        for (var override : input.overrides()) {
            if (override.columnIndex() >= profile.columnCount()) {
                return invalid(profile, input, "EFFECT_OVERRIDE_COLUMN_INVALID");
            }
        }
        for (int missing = row.values().size(); missing < profile.columnCount(); missing++) {
            final int at = missing;
            if (input.overrides().stream().noneMatch(v -> v.columnIndex() == at)) {
                return invalid(profile, input, "EFFECT_ROW_SHAPE_AMBIGUOUS");
            }
        }
        if (!row.values().isEmpty()
                && !Long.toString(input.rowId()).equals(row.values().getFirst())) {
            return invalid(profile, input, "EFFECT_ID_CELL_MISMATCH");
        }
        IssueChannel channel =
                row.textIssues() == null ? IssueChannel.UNKNOWN : input.issueChannel();
        if (!profile.stamp().issueEvidence().state().equals("VERIFIED_NATIVE")) {
            channel = IssueChannel.UNKNOWN;
        }
        if (input.overrides().stream().anyMatch(v -> v.columnIndex() == -1)
                || row.textIssues() != null
                        && row.textIssues().stream()
                                .anyMatch(
                                        v ->
                                                v.columnIndex() < 0
                                                        || v.columnIndex()
                                                                >= profile.columnCount())) {
            channel = IssueChannel.LIMITED;
        }
        var cells = new ArrayList<Cell>();
        for (var binding : profile.bindings()) {
            cells.add(cell(binding, input, channel));
        }
        var first = cells.getFirst();
        IdentityAdmission identity =
                first.state() == CellState.VALUE
                        ? IdentityAdmission.ADMITTED
                        : first.state() == CellState.LIMITED
                                ? IdentityAdmission.LIMITED
                                : first.state() == CellState.INVALID
                                        ? IdentityAdmission.INVALID
                                        : IdentityAdmission.UNKNOWN;
        Long source = row.copySourceId();
        boolean copyValid =
                row.isCopy()
                        ? source != null
                                && source >= 0
                                && source <= 0xffff_ffffL
                                && source != row.id()
                        : source == null;
        var copy =
                copyValid
                        ? new CopyMetadata(row.isCopy(), source, CellState.VALUE)
                        : new CopyMetadata(row.isCopy(), null, CellState.INVALID);
        var omissions = new ArrayList<>(profile.omissions());
        if (!copyValid) {
            omissions.add(new Omission(null, CellState.INVALID, "EFFECT_COPY_METADATA_INVALID"));
        }
        return new SourceFacts(
                profile.stamp(), input.rowId(), RowState.PRESENT, identity, copy, cells, omissions);
    }

    private static Cell cell(Binding binding, SourceRow input, IssueChannel channel) {
        int index = binding.columnIndex();
        var row = input.row();
        String raw = index < row.values().size() ? row.values().get(index) : null;
        CellState state = null;
        var issues = new ArrayList<String>();
        String rawEvidence = prefix(raw);
        for (var override : input.overrides()) {
            if (override.columnIndex() == index) {
                state = restrictive(state, override.state());
                note(issues, override.code());
            }
        }
        if (row.textIssues() != null) {
            for (var issue : row.textIssues()) {
                if (issue.columnIndex() == index) {
                    state =
                            restrictive(
                                    state,
                                    "DB2_TEXT_ENCODING_INVALID".equals(issue.code())
                                            ? CellState.INVALID
                                            : CellState.UNKNOWN);
                    note(issues, issue.code());
                    if (issue.rawBytesHex() != null && !issue.rawBytesHex().isEmpty()) {
                        rawEvidence = prefix("raw-bytes:" + prefix(issue.rawBytesHex()));
                    }
                }
            }
        }
        if (state == null && raw == null) {
            state = CellState.UNKNOWN;
        }
        if (state == null
                && (index == 0
                        || binding.relation()
                        || binding.source() == SourceKind.BRIDGE && (index == 1 || index == 2)
                        || binding.source() == SourceKind.DIRECT_EFFECT && index == 10)
                && channel != IssueChannel.KNOWN) {
            state = channel == IssueChannel.LIMITED ? CellState.LIMITED : CellState.UNKNOWN;
            note(issues, "EFFECT_ISSUE_CHANNEL_UNKNOWN");
        }
        if (state == null && raw.length() > MAX_TEXT) {
            state = CellState.LIMITED;
        }
        if (state == null && binding.evidence() != EvidenceClass.STRICT_NUMERIC) {
            state = CellState.UNSUPPORTED;
        }
        String value = null;
        if (state == null) {
            long min = index == 0 || binding.unsigned() ? 0 : -(1L << (binding.bits() - 1));
            long max =
                    index == 0
                            ? 0xffff_ffffL
                            : binding.unsigned()
                                    ? (1L << binding.bits()) - 1
                                    : (1L << (binding.bits() - 1)) - 1;
            Long parsed = canonical(raw, min, max);
            if (parsed == null) {
                state = CellState.INVALID;
            } else {
                state = CellState.VALUE;
                value = Long.toString(parsed);
            }
        }
        return new Cell(binding, state, value, rawEvidence, issues);
    }

    public static PairPlan pair(SourceProfile left, SourceProfile right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        var assessed = new ArrayList<BindingPair>();
        var omissions = new ArrayList<Omission>();
        if (!readable(left.state())
                || !readable(right.state())
                || left.columnCount() != right.columnCount()
                || !left.stamp().build().product().equals(right.stamp().build().product())
                || !left.stamp().locale().equals(right.stamp().locale())
                || left.stamp().fileDataId() != right.stamp().fileDataId()
                || !Objects.equals(left.stamp().tableName(), right.stamp().tableName())
                || !Objects.equals(left.stamp().layoutHash(), right.stamp().layoutHash())
                || !Objects.equals(left.stamp().readerRevision(), right.stamp().readerRevision())
                || !Objects.equals(left.stamp().rowEncoding(), right.stamp().rowEncoding())
                || left.stamp().kind() != right.stamp().kind()
                || !left.identityFingerprint().equals(right.identityFingerprint())) {
            return new PairPlan(
                    left.stamp().kind(),
                    IdentityAdmission.INVALID,
                    List.of(),
                    List.of(
                            new Omission(
                                    null, CellState.UNSUPPORTED, "EFFECT_IDENTITY_INCOMPATIBLE")));
        }
        for (int i = 1; i < left.bindings().size(); i++) {
            var a = left.bindings().get(i);
            var b = right.bindings().get(i);
            // ParentItemID is part of the link key; the direct own ID is not an input delta.
            if (a.field().equals("ParentItemID")) {
                continue;
            }
            if (!Objects.equals(left.stamp().definitionSha256(), right.stamp().definitionSha256())
                    || !Objects.equals(left.stamp().blockIdentity(), right.stamp().blockIdentity())
                    || !Objects.equals(left.stamp().readerOptions(), right.stamp().readerOptions())
                    || !a.field().equals(b.field())
                    || a.bits() != b.bits()
                    || a.unsigned() != b.unsigned()
                    || a.relation() != b.relation()
                    || a.evidence() != EvidenceClass.STRICT_NUMERIC
                    || b.evidence() != EvidenceClass.STRICT_NUMERIC
                    || !a.fingerprint().equals(b.fingerprint())) {
                omissions.add(
                        new Omission(
                                a.field(), CellState.UNSUPPORTED, "EFFECT_FIELD_PAIR_UNASSESSED"));
            } else {
                assessed.add(
                        new BindingPair("effect." + a.source().name() + "." + a.field(), a, b));
            }
        }
        return new PairPlan(left.stamp().kind(), IdentityAdmission.ADMITTED, assessed, omissions);
    }

    public static RelationFacts relate(
            SourceFacts link, TargetObservation target, RelationMode mode) {
        Objects.requireNonNull(link);
        Objects.requireNonNull(target);
        Objects.requireNonNull(mode);
        SourceKind expected =
                mode == RelationMode.BRIDGE ? SourceKind.BRIDGE : SourceKind.DIRECT_EFFECT;
        if (link.stamp().kind() != expected) {
            throw new IllegalArgumentException("Effect relation mode/source mismatch");
        }
        var omissions = new ArrayList<Omission>();
        int parentColumn = mode == RelationMode.BRIDGE ? 2 : 10;
        var parent = field(link, parentColumn);
        Long parentId =
                parent == null || parent.state() != CellState.VALUE
                        ? null
                        : canonical(parent.value(), 1, Integer.MAX_VALUE);
        LinkIdentity identity =
                link.identity() == IdentityAdmission.ADMITTED && parentId != null
                        ? new LinkIdentity(mode, parentId, link.rowId())
                        : null;
        if (identity == null) {
            omissions.add(new Omission(null, CellState.UNKNOWN, "EFFECT_LINK_IDENTITY_UNASSESSED"));
        }
        var reference = mode == RelationMode.DIRECT ? null : field(link, 1);
        Long requested =
                mode == RelationMode.DIRECT
                        ? Long.valueOf(link.rowId())
                        : reference != null && reference.state() == CellState.VALUE
                                ? canonical(reference.value(), Integer.MIN_VALUE, Integer.MAX_VALUE)
                                : null;
        if (requested == null) {
            if (target.requestedId() != 0
                    || target.state() != TargetState.UNKNOWN
                            && target.state() != TargetState.LIMITED) {
                throw new IllegalArgumentException("Target claim without admitted reference");
            }
            omissions.add(
                    new Omission("ItemEffectID", CellState.UNKNOWN, "EFFECT_REFERENCE_UNASSESSED"));
        } else {
            if (target.requestedId() != requested) {
                throw new IllegalArgumentException("Effect target reference mismatch");
            }
            if (mode == RelationMode.BRIDGE
                            && requested <= 0
                            && target.state() != TargetState.UNRESOLVABLE_REFERENCE
                    || mode == RelationMode.BRIDGE
                            && requested > 0
                            && target.state() == TargetState.UNRESOLVABLE_REFERENCE
                    || mode == RelationMode.DIRECT
                            && target.state() != TargetState.OBSERVED
                            && link.identity() == IdentityAdmission.ADMITTED) {
                throw new IllegalArgumentException("Effect target state/reference mismatch");
            }
        }
        if (target.state() == TargetState.OBSERVED) {
            var facts = target.facts();
            if (facts.identity() != IdentityAdmission.ADMITTED
                    || facts.state() != RowState.PRESENT
                    || facts.rowId() != target.requestedId()
                    || !facts.stamp().build().equals(link.stamp().build())
                    || !facts.stamp().locale().equals(link.stamp().locale())
                    || facts.stamp().kind()
                            != (mode == RelationMode.DIRECT
                                    ? SourceKind.DIRECT_EFFECT
                                    : SourceKind.RETAIL_EFFECT)
                    || mode == RelationMode.DIRECT && !facts.equals(link)) {
                throw new IllegalArgumentException("Effect target source/identity mismatch");
            }
        }
        return new RelationFacts(identity, link, target, omissions);
    }

    private static Cell field(SourceFacts row, int index) {
        return index < row.cells().size() ? row.cells().get(index) : null;
    }

    private static CellState restrictive(CellState a, CellState b) {
        return a == null || rank(b) < rank(a) ? b : a;
    }

    private static int rank(CellState state) {
        return switch (state) {
            case LIMITED -> 0;
            case PROTECTED -> 1;
            case INVALID -> 2;
            default -> 3;
        };
    }

    private static void note(List<String> issues, String code) {
        if (issues.size() < 8) {
            issues.add(
                    code == null || code.isBlank() || code.length() > 128
                            ? "EFFECT_SOURCE_ISSUE"
                            : code);
        }
    }

    private static String prefix(String value) {
        return value == null ? null : value.substring(0, Math.min(MAX_TEXT, value.length()));
    }

    private static boolean readable(SourceState state) {
        return state == SourceState.READABLE || state == SourceState.PARTIAL;
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static Long canonical(String value, long min, long max) {
        if (value == null || value.length() > 20) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= min && parsed <= max && Long.toString(parsed).equals(value)
                    ? parsed
                    : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static CellState rowState(RowState state) {
        return switch (state) {
            case PROTECTED -> CellState.PROTECTED;
            case INVALID -> CellState.INVALID;
            case LIMITED -> CellState.LIMITED;
            default -> CellState.UNKNOWN;
        };
    }

    private static SourceFacts invalid(SourceProfile profile, SourceRow input, String code) {
        return new SourceFacts(
                profile.stamp(),
                input.rowId(),
                RowState.INVALID,
                IdentityAdmission.INVALID,
                new CopyMetadata(null, null, CellState.UNKNOWN),
                List.of(),
                List.of(new Omission(null, CellState.INVALID, code)));
    }

    private static Compilation failure(IdentityAdmission state, String code) {
        return new Compilation(
                state,
                FieldAdmission.UNSUPPORTED,
                null,
                List.of(new Omission(null, CellState.UNSUPPORTED, code)));
    }

    private static String hash(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
