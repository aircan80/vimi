package com.vibeminer.concepts.comparison.effects;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.Side;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.SourceRef;
import com.vibeminer.concepts.effects.PreparedItemEffectProjection;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Cell;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CopyMetadata;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.LinkIdentity;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Omission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PresenceEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RowState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetObservation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Private session stages. Caller supplies the charged3MiB projector/codec working allowance. */
public final class EffectsStageCodec {
    public static final int MAX_SOURCE_META = 128 << 10;
    public static final long MAX_STAGE_RETAINED = 2L << 20, MAX_PROJECT_WORK = 3L << 20;
    private static final String META = "effects-source-stage-v1",
            TARGET = "effects-target-stage-v1";
    private static final int MAGIC = 0x45465331;
    private final EffectsSnapshot snapshot;

    public EffectsStageCodec(EffectsSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    /** Conservative bound checked before encoding/copying a caller-supplied row. */
    public long estimateSource(SourceFacts facts) {
        long bytes = 256;
        for (var cell : facts.cells()) {
            bytes += 32L + stringBytes(cell.value()) + stringBytes(cell.rawEvidence());
            for (var issue : cell.issues()) {
                bytes += stringBytes(issue);
            }
        }
        for (var omission : facts.omissions()) {
            bytes += 16L + stringBytes(omission.field()) + stringBytes(omission.code());
        }
        return bytes;
    }

    public Record encodeSource(SourceRef ref, SourceFacts facts) {
        var profile = snapshot.profile(ref);
        if (profile == null || !profile.stamp().equals(facts.stamp())) {
            throw new IllegalArgumentException("Effects facts differ from source dictionary");
        }
        validateFacts(profile, facts);
        long estimate = estimateSource(facts);
        if (estimate > MAX_SOURCE_META) {
            throw new EffectsSnapshot.Limit("Effects source metadata limit");
        }
        for (var cell : facts.cells()) {
            if (!profile.bindings().get(cell.binding().columnIndex()).equals(cell.binding())) {
                throw new IllegalArgumentException("Effects facts binding mismatch");
            }
        }
        try {
            var bytes = new ByteArrayOutputStream((int) estimate);
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                string(out, ref.session());
                out.writeByte(ref.index());
                string(out, EffectsSnapshot.hash(facts.stamp().toString()));
                out.writeLong(facts.rowId());
                out.writeByte(facts.state().ordinal());
                out.writeByte(facts.identity().ordinal());
                var copy = facts.copy();
                out.writeByte(copy.copy() == null ? 0 : copy.copy() ? 2 : 1);
                out.writeBoolean(copy.sourceId() != null);
                if (copy.sourceId() != null) {
                    out.writeLong(copy.sourceId());
                }
                out.writeByte(copy.state().ordinal());
                out.writeByte(facts.cells().size());
                for (var cell : facts.cells()) {
                    out.writeByte(cell.binding().columnIndex());
                    out.writeByte(cell.state().ordinal());
                    string(out, cell.value());
                    string(out, cell.rawEvidence());
                    out.writeByte(cell.issues().size());
                    for (var issue : cell.issues()) {
                        string(out, issue);
                    }
                }
                out.writeByte(facts.omissions().size());
                for (var omission : facts.omissions()) {
                    string(out, omission.field());
                    out.writeByte(omission.state().ordinal());
                    string(out, omission.code());
                }
            }
            if (bytes.size() > estimate || bytes.size() > MAX_SOURCE_META) {
                throw new IllegalStateException("Effects metadata estimate underestimated");
            }
            var record =
                    new Record(
                            ownKey(facts.rowId()),
                            Map.of(
                                    "m" + ref.index(),
                                    meta(
                                            META,
                                            Base64.getEncoder()
                                                    .encodeToString(bytes.toByteArray()))),
                            List.of());
            validateRetained(record);
            return record;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public SourceRef sourceRef(Record record) {
        if (record.fields().size() != 1 || !record.evidence().isEmpty()) {
            throw new IllegalArgumentException("Expected one effect source stage");
        }
        String field = record.fields().keySet().iterator().next();
        if (!field.matches("m[0-3]")) {
            throw new IllegalArgumentException("Invalid effect source stage key");
        }
        return snapshot.sourceAt(field.charAt(1) - '0').ref();
    }

    public SourceFacts decodeSource(Record record) {
        var ref = sourceRef(record);
        var facts = decode(ref, record.fields().get("m" + ref.index()));
        if (!record.key().equals(ownKey(facts.rowId())) && !record.key().equals(routeKey(facts))) {
            throw new IllegalArgumentException("Effects stage key differs from facts");
        }
        return facts;
    }

    private SourceFacts decode(SourceRef ref, ComparisonTypes.Field field) {
        if (field == null
                || !field.semantics().equals(META)
                || field.kind() != ComparisonTypes.ValueKind.TEXT
                || field.state() != ComparisonTypes.ValueState.VALUE
                || field.aspect() != ComparisonTypes.Aspect.INTERPRETATION
                || !field.evidence().isEmpty()
                || field.value().length() > (MAX_SOURCE_META + 2) / 3 * 4) {
            throw new IllegalArgumentException("Invalid effect source metadata");
        }
        byte[] bytes = Base64.getDecoder().decode(field.value());
        if (bytes.length > MAX_SOURCE_META) {
            throw new EffectsSnapshot.Limit("Effects source metadata limit");
        }
        var profile = snapshot.profile(ref);
        if (profile == null) {
            throw new IllegalArgumentException("Unqualified effect source dictionary");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC
                    || !ref.session().equals(string(in, 128))
                    || in.readUnsignedByte() != ref.index()
                    || !EffectsSnapshot.hash(profile.stamp().toString()).equals(string(in, 128))) {
                throw new IllegalArgumentException(
                        "Effects metadata belongs to another source/session");
            }
            long id = in.readLong();
            var state = item(RowState.values(), in.readUnsignedByte());
            var identity = item(IdentityAdmission.values(), in.readUnsignedByte());
            int copyFlag = in.readUnsignedByte();
            if (copyFlag > 2) {
                throw new IllegalArgumentException("Invalid effect copy marker");
            }
            Long copyId = in.readBoolean() ? Long.valueOf(in.readLong()) : null;
            var copy =
                    new CopyMetadata(
                            copyFlag == 0 ? null : copyFlag == 2,
                            copyId,
                            item(CellState.values(), in.readUnsignedByte()));
            int count = in.readUnsignedByte();
            if (count > profile.columnCount()) {
                throw new IllegalArgumentException("Effects cell count limit");
            }
            var cells = new ArrayList<Cell>();
            for (int at = 0; at < count; at++) {
                int index = in.readUnsignedByte();
                if (index != at) {
                    throw new IllegalArgumentException("Effects cell ordering differs");
                }
                var cellState = item(CellState.values(), in.readUnsignedByte());
                String value = string(in, 8192), raw = string(in, 8192);
                int issueCount = in.readUnsignedByte();
                if (issueCount > 8) {
                    throw new IllegalArgumentException("Effects issue count limit");
                }
                var issues = new ArrayList<String>();
                for (int i = 0; i < issueCount; i++) {
                    issues.add(string(in, 128));
                }
                var binding = profile.bindings().get(index);
                if (cellState == CellState.VALUE && !validInteger(binding, index, value)) {
                    throw new IllegalArgumentException("Invalid staged effect integer");
                }
                cells.add(new Cell(binding, cellState, value, raw, issues));
            }
            int omissionCount = in.readUnsignedByte();
            if (omissionCount > 32) {
                throw new IllegalArgumentException("Effects omission count limit");
            }
            var omissions = new ArrayList<Omission>();
            for (int i = 0; i < omissionCount; i++) {
                omissions.add(
                        new Omission(
                                string(in, 128),
                                item(CellState.values(), in.readUnsignedByte()),
                                string(in, 128)));
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("Trailing effects metadata");
            }
            var facts =
                    new SourceFacts(profile.stamp(), id, state, identity, copy, cells, omissions);
            if (identity == IdentityAdmission.ADMITTED
                    && (cells.isEmpty()
                            || cells.getFirst().state() != CellState.VALUE
                            || !Long.toString(id).equals(cells.getFirst().value()))) {
                throw new IllegalArgumentException("Staged effect identity differs from own cell");
            }
            validateFacts(profile, facts);
            return facts;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Truncated effect source stage", invalid);
        }
    }

    /** Null excludes an unqualified relation, after own-ID sorting has checked duplicates. */
    public Record routeBridge(Record source) {
        var ref = sourceRef(source);
        if (ref.kind() != SourceKind.BRIDGE) {
            throw new IllegalArgumentException("Expected bridge source");
        }
        var facts = decodeSource(source);
        if (identity(facts) == null) {
            snapshot.noteOmission("UNQUALIFIED_LINK");
            return null;
        }
        return new Record(routeKey(facts), source.fields(), List.of());
    }

    /** Called once per link by the streaming join, never collecting an effect's fan-out. */
    public Record candidate(Side side, Record linkRecord, Record effectRecord) {
        var relationRef = snapshot.relationRef(side);
        if (!sourceRef(linkRecord).equals(relationRef)) {
            throw new IllegalArgumentException("Effect link belongs to another endpoint/source");
        }
        var link = decodeSource(linkRecord);
        var key = identity(link);
        if (key == null) {
            snapshot.noteOmission("UNQUALIFIED_LINK");
            return null;
        }
        var mode = snapshot.mode(side);
        SourceFacts effect = null;
        if (mode == RelationMode.DIRECT) {
            if (effectRecord != null) {
                throw new IllegalArgumentException("Direct effect has no joined source");
            }
            effect = link;
        } else if (effectRecord != null) {
            var effectRef = sourceRef(effectRecord);
            if (effectRef.side() != side || effectRef.kind() != SourceKind.RETAIL_EFFECT) {
                throw new IllegalArgumentException(
                        "Effect target belongs to another endpoint/source");
            }
            var observed = decodeSource(effectRecord);
            if (observed.identity() == IdentityAdmission.ADMITTED
                    && observed.state() == RowState.PRESENT) {
                effect = observed;
            }
        }
        Long requested = mode == RelationMode.DIRECT ? Long.valueOf(link.rowId()) : reference(link);
        if (effectRecord != null && (requested == null || requested <= 0)) {
            throw new IllegalArgumentException("Joined target without usable reference");
        }
        TargetState targetState =
                requested == null
                        ? TargetState.UNKNOWN
                        : requested <= 0 && mode == RelationMode.BRIDGE
                                ? TargetState.UNRESOLVABLE_REFERENCE
                                : effect == null ? TargetState.UNKNOWN : TargetState.OBSERVED;
        var target =
                new TargetObservation(
                        requested == null ? 0 : requested,
                        targetState,
                        targetState == TargetState.OBSERVED ? effect : null,
                        PresenceEvidence.unknown());
        PreparedItemEffectProjection.relate(link, target, mode);
        var fields = new LinkedHashMap<>(linkRecord.fields());
        if (effect != null && mode == RelationMode.BRIDGE) {
            fields.putAll(effectRecord.fields());
        }
        fields.put("t", meta(TARGET, targetState.name() + ":" + target.requestedId()));
        var result = new Record(linkKey(key), fields, List.of());
        validateRetained(result);
        return result;
    }

    /** Final paired field mask, applied only after both endpoint captures/modes are sealed. */
    public Record project(Side side, Record candidate) {
        var scope = snapshot.scope();
        if (scope.identity() != IdentityAdmission.ADMITTED) {
            return null;
        }
        if (candidate.fields().size() > 3 || !candidate.evidence().isEmpty()) {
            throw new IllegalArgumentException("Invalid effect candidate shape");
        }
        var linkRef = snapshot.relationRef(side);
        var link = decode(linkRef, candidate.fields().get("m" + linkRef.index()));
        var identity = identity(link);
        if (identity == null || !candidate.key().equals(linkKey(identity))) {
            throw new IllegalArgumentException("Effect candidate identity differs");
        }
        SourceFacts effect = null;
        SourceRef targetRef = null;
        if (scope.mode() == RelationMode.DIRECT) {
            effect = link;
        } else {
            int targetIndex = side == Side.A ? 1 : 3;
            if (candidate.fields().containsKey("m" + targetIndex)) {
                targetRef = snapshot.sourceAt(targetIndex).ref();
                effect = decode(targetRef, candidate.fields().get("m" + targetIndex));
            }
        }
        var targetMeta = candidate.fields().get("t");
        if (targetMeta == null
                || !targetMeta.semantics().equals(TARGET)
                || targetMeta.state() != ComparisonTypes.ValueState.VALUE
                || targetMeta.kind() != ComparisonTypes.ValueKind.TEXT
                || targetMeta.aspect() != ComparisonTypes.Aspect.INTERPRETATION
                || !targetMeta.evidence().isEmpty()
                || targetMeta.value().length() > 64) {
            throw new IllegalArgumentException("Invalid effect target state");
        }
        String[] parts = targetMeta.value().split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid effect target state");
        }
        var targetState = TargetState.valueOf(parts[0]);
        long requested = Long.parseLong(parts[1]);
        if (!Long.toString(requested).equals(parts[1])) {
            throw new IllegalArgumentException("Noncanonical effect target reference");
        }
        var target =
                new TargetObservation(requested, targetState, effect, PresenceEvidence.unknown());
        PreparedItemEffectProjection.relate(link, target, scope.mode());
        int expectedFields = 2 + (scope.mode() == RelationMode.BRIDGE && effect != null ? 1 : 0);
        if (candidate.fields().size() != expectedFields) {
            throw new IllegalArgumentException("Extra effect candidate fields");
        }
        var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
        for (var spec : scope.assessed()) {
            var binding = side == Side.A ? spec.a() : spec.b();
            var facts = spec.kind() == linkRef.kind() ? link : effect;
            Cell cell =
                    facts == null || binding.columnIndex() >= facts.cells().size()
                            ? null
                            : facts.cells().get(binding.columnIndex());
            if (cell != null && !cell.binding().equals(binding)) {
                throw new IllegalArgumentException(
                        "Effect candidate binding differs from paired mask");
            }
            var evidence =
                    new ComparisonTypes.Evidence(
                            binding.source().profile().table(),
                            facts == null ? "unread-target" : Long.toString(facts.rowId()),
                            binding.field(),
                            cell == null ? null : cell.rawEvidence());
            fields.put(
                    spec.key(),
                    new ComparisonTypes.Field(
                            spec.key(),
                            ComparisonTypes.ValueKind.INTEGER,
                            cell == null
                                    ? ComparisonTypes.ValueState.UNKNOWN
                                    : ComparisonTypes.ValueState.valueOf(cell.state().name()),
                            cell == null ? null : cell.value(),
                            ComparisonTypes.Aspect.CONTENT,
                            List.of(evidence)));
        }
        if (scope.targetAssessed()) {
            fields.put(
                    "effect.target.availability",
                    new ComparisonTypes.Field(
                            "effect-target-observation-v1",
                            ComparisonTypes.ValueKind.TEXT,
                            ComparisonTypes.ValueState.VALUE,
                            targetState.name(),
                            ComparisonTypes.Aspect.AVAILABILITY,
                            List.of()));
        }
        var result =
                new Record(
                        candidate.key(),
                        fields,
                        List.of(
                                new ComparisonTypes.Evidence(
                                        linkRef.kind().profile().table(),
                                        Long.toString(link.rowId()),
                                        "copy",
                                        link.copy().toString())));
        validateRetained(result);
        return result;
    }

    public static String ownKey(long id) {
        if (id < 0 || id > 0xffff_ffffL) {
            throw new IllegalArgumentException("Unsigned effect ID required");
        }
        return String.format(java.util.Locale.ROOT, "%020d", id);
    }

    public static String linkKey(LinkIdentity identity) {
        return (identity.mode() == RelationMode.BRIDGE ? "B" : "D")
                + ownKey(identity.parentItemId())
                + ownKey(identity.ownLinkRowId());
    }

    public static Long reference(SourceFacts facts) {
        var cell = facts.cells().size() > 1 ? facts.cells().get(1) : null;
        return cell == null || cell.state() != CellState.VALUE ? null : Long.valueOf(cell.value());
    }

    public static String routeKey(SourceFacts facts) {
        if (facts.stamp().kind() != SourceKind.BRIDGE) {
            throw new IllegalArgumentException("Only bridge facts have route keys");
        }
        var identity = identity(facts);
        if (identity == null) {
            throw new IllegalArgumentException("Unqualified link has no route key");
        }
        Long ref = reference(facts);
        return (ref == null || ref <= 0 ? "0" : "1")
                + ownKey(ref == null ? 0 : ref <= 0 ? ref - Integer.MIN_VALUE : ref)
                + linkKey(identity);
    }

    private static LinkIdentity identity(SourceFacts facts) {
        if (facts.identity() != IdentityAdmission.ADMITTED) {
            return null;
        }
        int column = facts.stamp().kind() == SourceKind.BRIDGE ? 2 : 10;
        if (facts.cells().size() <= column) {
            return null;
        }
        var parent = facts.cells().get(column);
        if (parent.state() != CellState.VALUE) {
            return null;
        }
        long id = Long.parseLong(parent.value());
        return id < 1 || id > Integer.MAX_VALUE
                ? null
                : new LinkIdentity(
                        facts.stamp().kind() == SourceKind.BRIDGE
                                ? RelationMode.BRIDGE
                                : RelationMode.DIRECT,
                        id,
                        facts.rowId());
    }

    private static void validateFacts(
            com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceProfile profile,
            SourceFacts facts) {
        if (facts.state() == RowState.PRESENT && facts.cells().size() != profile.columnCount()
                || facts.state() != RowState.PRESENT
                        && (!facts.cells().isEmpty()
                                || facts.identity() == IdentityAdmission.ADMITTED)) {
            throw new IllegalArgumentException("Invalid effect facts shape/state");
        }
        for (var cell : facts.cells()) {
            int index = cell.binding().columnIndex();
            boolean critical =
                    index == 0
                            || cell.binding().relation()
                            || profile.stamp().kind() == SourceKind.BRIDGE
                                    && (index == 1 || index == 2)
                            || profile.stamp().kind() == SourceKind.DIRECT_EFFECT && index == 10;
            if (cell.state() == CellState.VALUE
                    && (!validInteger(cell.binding(), index, cell.value())
                            || critical
                                    && !profile.stamp()
                                            .issueEvidence()
                                            .state()
                                            .equals("VERIFIED_NATIVE"))) {
                throw new IllegalArgumentException("Unadmitted effect source value");
            }
        }
        if (facts.identity() == IdentityAdmission.ADMITTED
                && (facts.cells().isEmpty()
                        || facts.cells().getFirst().state() != CellState.VALUE
                        || !Long.toString(facts.rowId())
                                .equals(facts.cells().getFirst().value()))) {
            throw new IllegalArgumentException("Effect own identity disagrees");
        }
    }

    private static boolean validInteger(
            com.vibeminer.concepts.effects.PreparedItemEffectTypes.Binding binding,
            int index,
            String value) {
        if (value == null || value.length() > 20) {
            return false;
        }
        try {
            long parsed = Long.parseLong(value);
            long min = index == 0 || binding.unsigned() ? 0 : -(1L << (binding.bits() - 1));
            long max =
                    index == 0
                            ? 0xffff_ffffL
                            : binding.unsigned()
                                    ? (1L << binding.bits()) - 1
                                    : (1L << (binding.bits() - 1)) - 1;
            return parsed >= min && parsed <= max && Long.toString(parsed).equals(value);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static ComparisonTypes.Field meta(String semantics, String value) {
        return new ComparisonTypes.Field(
                semantics,
                ComparisonTypes.ValueKind.TEXT,
                ComparisonTypes.ValueState.VALUE,
                value,
                ComparisonTypes.Aspect.INTERPRETATION,
                List.of());
    }

    private static long stringBytes(String value) {
        return 4L + (value == null ? 0 : 4L * value.length());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        try {
            var encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid UTF16 in effects evidence", invalid);
        }
    }

    private static String string(DataInputStream in, int maxChars) throws IOException {
        int count = in.readInt();
        if (count == -1) {
            return null;
        }
        if (count < 0 || count > 4L * maxChars || count > in.available()) {
            throw new IllegalArgumentException("Invalid bounded effects string");
        }
        byte[] bytes = in.readNBytes(count);
        try {
            String value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (value.length() > maxChars) {
                throw new IllegalArgumentException("Effects string character limit");
            }
            return value;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid effect UTF8", invalid);
        }
    }

    private static <T> T item(T[] values, int ordinal) {
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown effects stage code");
        }
        return values[ordinal];
    }

    private static void validateRetained(Record record) {
        if (SortedComparisonInput.retainedBytes(record) > MAX_STAGE_RETAINED) {
            throw new EffectsSnapshot.Limit("Effects stage retained limit");
        }
    }
}
