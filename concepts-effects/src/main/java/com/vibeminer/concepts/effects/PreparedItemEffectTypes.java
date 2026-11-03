package com.vibeminer.concepts.effects;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.util.List;
import java.util.Objects;

/** Detached pure projection contracts; no captured handles, comparison DTOs or I/O. */
public final class PreparedItemEffectTypes {
    private PreparedItemEffectTypes() {}

    public enum SourceKind {
        BRIDGE(ItemEffectProfiles.ITEM_X_ITEM_EFFECT, "00CB674F"),
        RETAIL_EFFECT(ItemEffectProfiles.RETAIL_ITEM_EFFECT, "4002A5B1"),
        DIRECT_EFFECT(ItemEffectProfiles.CLASSIC_ITEM_EFFECT, "4002A5B1");
        private final ItemEffectProfiles.Profile profile;
        private final String tableHash;

        SourceKind(ItemEffectProfiles.Profile profile, String tableHash) {
            this.profile = profile;
            this.tableHash = tableHash;
        }

        public ItemEffectProfiles.Profile profile() {
            return profile;
        }

        public String tableHash() {
            return tableHash;
        }
    }

    public enum RelationMode {
        BRIDGE,
        DIRECT
    }

    public enum SourceState {
        READABLE,
        PARTIAL,
        UNAVAILABLE,
        PROTECTED,
        UNSUPPORTED,
        INCOMPATIBLE,
        INVALID,
        LIMITED
    }

    public enum RowState {
        PRESENT,
        UNKNOWN,
        PROTECTED,
        INVALID,
        LIMITED
    }

    public enum IssueChannel {
        KNOWN,
        UNKNOWN,
        LIMITED
    }

    public enum CellState {
        VALUE,
        UNKNOWN,
        PROTECTED,
        UNSUPPORTED,
        INVALID,
        LIMITED
    }

    public enum IdentityAdmission {
        ADMITTED,
        UNKNOWN,
        INVALID,
        LIMITED
    }

    public enum FieldAdmission {
        ADMITTED,
        PARTIAL,
        UNSUPPORTED,
        INVALID,
        LIMITED
    }

    public enum TargetState {
        OBSERVED,
        UNKNOWN,
        PROTECTED,
        INVALID,
        LIMITED,
        UNRESOLVABLE_REFERENCE
    }

    public enum EvidenceClass {
        STRICT_NUMERIC,
        RAW_PHYSICAL_INTEGER,
        UNVERIFIED_CANDIDATE
    }

    /** Provenance only; does not grant semantic admission or certify membership. */
    public record IssueEvidence(
            String state, String policy, String producer, String bindingDigest) {
        public IssueEvidence {
            text(state, 128);
            optional(policy, 128);
            optional(producer, 128);
            optional(bindingDigest, 512);
        }
    }

    public record SourceStamp(
            SourceKind kind,
            BuildRef build,
            String locale,
            long fileDataId,
            String tableName,
            String contentKey,
            String layoutHash,
            String definitionSha256,
            String blockIdentity,
            String graphIdentity,
            String readerRevision,
            String readerOptions,
            String rowEncoding,
            String effectiveKeysFingerprint,
            IssueEvidence issueEvidence) {
        public SourceStamp {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(build);
            Objects.requireNonNull(issueEvidence);
            id(fileDataId);
            text(locale, 128);
            text(tableName, 128);
            for (var value :
                    new String[] {
                        contentKey,
                        layoutHash,
                        definitionSha256,
                        blockIdentity,
                        graphIdentity,
                        readerRevision,
                        readerOptions,
                        rowEncoding,
                        effectiveKeysFingerprint
                    }) {
                optional(value, 512);
            }
        }
    }

    public record SourceSchema(
            SourceStamp stamp,
            Db2Types.Header header,
            TableSchema schema,
            List<Db2Types.Column> columns,
            SourceState state) {
        public SourceSchema {
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(state);
            columns = columns == null ? null : bounded(columns, 11);
        }
    }

    public record Omission(String field, CellState state, String code) {
        public Omission {
            optional(field, 128);
            Objects.requireNonNull(state);
            text(code, 128);
        }
    }

    public record Binding(
            SourceKind source,
            long fileDataId,
            String field,
            int columnIndex,
            int bits,
            boolean unsigned,
            boolean relation,
            EvidenceClass evidence,
            Db2Types.Interpretation interpretation,
            String fingerprint) {
        public Binding {
            Objects.requireNonNull(source);
            id(fileDataId);
            text(field, 128);
            text(fingerprint, 128);
            Objects.requireNonNull(evidence);
            if (columnIndex < 0
                    || columnIndex > 10
                    || bits < 1
                    || bits > 32
                    || (evidence == EvidenceClass.RAW_PHYSICAL_INTEGER)
                            != (interpretation != null)) {
                throw new IllegalArgumentException("Invalid effect binding");
            }
            if (interpretation != null) {
                text(interpretation.policy(), 128);
                text(interpretation.warning(), 512);
            }
        }
    }

    /** Compact compiler output; never retains schema/header/column inputs. */
    public record SourceProfile(
            SourceStamp stamp,
            SourceState state,
            String identityFingerprint,
            int columnCount,
            List<Binding> bindings,
            List<Omission> omissions) {
        public SourceProfile {
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(state);
            text(identityFingerprint, 128);
            if (columnCount < 1 || columnCount > 11) {
                throw new IllegalArgumentException("Invalid effect column count");
            }
            bindings = bounded(bindings, 11);
            omissions = bounded(omissions, 32);
            if (bindings.size() != columnCount) {
                throw new IllegalArgumentException("Effect bindings must cover source columns");
            }
            for (int i = 0; i < bindings.size(); i++) {
                var binding = bindings.get(i);
                if (binding.columnIndex() != i
                        || binding.source() != stamp.kind()
                        || binding.fileDataId() != stamp.fileDataId()) {
                    throw new IllegalArgumentException("Effect binding source mismatch");
                }
            }
        }
    }

    public record Compilation(
            IdentityAdmission identity,
            FieldAdmission fields,
            SourceProfile profile,
            List<Omission> omissions) {
        public Compilation {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(fields);
            omissions = bounded(omissions, 32);
            if ((identity == IdentityAdmission.ADMITTED) != (profile != null)) {
                throw new IllegalArgumentException("Effect profile admission mismatch");
            }
        }
    }

    public record PresenceEvidence(String identity, String code) {
        public PresenceEvidence {
            optional(identity, 512);
            optional(code, 128);
            if ((identity == null) != (code == null)) {
                throw new IllegalArgumentException("Incomplete protection evidence");
            }
        }

        public static PresenceEvidence unknown() {
            return new PresenceEvidence(null, null);
        }

        public boolean protectedRow() {
            return identity != null;
        }
    }

    public record IssueOverride(int columnIndex, CellState state, String code) {
        public IssueOverride {
            Objects.requireNonNull(state);
            text(code, 128);
            if (columnIndex < -1
                    || columnIndex > 10
                    || state == CellState.VALUE
                    || state == CellState.UNSUPPORTED
                    || columnIndex == -1
                            && state != CellState.UNKNOWN
                            && state != CellState.LIMITED) {
                throw new IllegalArgumentException("Invalid restrictive effect override");
            }
        }
    }

    public record SourceRow(
            long rowId,
            RowState state,
            Db2Types.Row row,
            IssueChannel issueChannel,
            List<IssueOverride> overrides,
            PresenceEvidence presence) {
        public SourceRow {
            id(rowId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(issueChannel);
            Objects.requireNonNull(presence);
            overrides = bounded(overrides, 1024);
            if ((state == RowState.PRESENT) != (row != null)
                    || state == RowState.PROTECTED && !presence.protectedRow()) {
                throw new IllegalArgumentException("Effect row state/evidence mismatch");
            }
        }
    }

    public record CopyMetadata(Boolean copy, Long sourceId, CellState state) {
        public CopyMetadata {
            Objects.requireNonNull(state);
            if (sourceId != null) {
                id(sourceId);
            }
        }
    }

    public record Cell(
            Binding binding,
            CellState state,
            String value,
            String rawEvidence,
            List<String> issues) {
        public Cell {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(state);
            optional(value, 8192);
            optional(rawEvidence, 8192);
            issues = bounded(issues, 8);
            for (var issue : issues) {
                text(issue, 128);
            }
            if ((state == CellState.VALUE) != (value != null)
                    || state == CellState.VALUE
                            && binding.evidence() != EvidenceClass.STRICT_NUMERIC) {
                throw new IllegalArgumentException("Effect semantic value/evidence mismatch");
            }
        }
    }

    public record SourceFacts(
            SourceStamp stamp,
            long rowId,
            RowState state,
            IdentityAdmission identity,
            CopyMetadata copy,
            List<Cell> cells,
            List<Omission> omissions) {
        public SourceFacts {
            Objects.requireNonNull(stamp);
            id(rowId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(copy);
            cells = bounded(cells, 11);
            omissions = bounded(omissions, 32);
            for (int i = 0; i < cells.size(); i++) {
                var binding = cells.get(i).binding();
                if (binding.source() != stamp.kind()
                        || binding.fileDataId() != stamp.fileDataId()
                        || binding.columnIndex() != i) {
                    throw new IllegalArgumentException("Effect cell source/ordering mismatch");
                }
            }
        }
    }

    public record BindingPair(String key, Binding left, Binding right) {
        public BindingPair {
            text(key, 128);
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            if (left.evidence() != EvidenceClass.STRICT_NUMERIC
                    || right.evidence() != EvidenceClass.STRICT_NUMERIC
                    || left.source() != right.source()
                    || !left.field().equals(right.field())
                    || !left.fingerprint().equals(right.fingerprint())) {
                throw new IllegalArgumentException("Unassessed effect binding pair");
            }
        }
    }

    public record PairPlan(
            SourceKind kind,
            IdentityAdmission identity,
            List<BindingPair> assessed,
            List<Omission> omissions) {
        public PairPlan {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(identity);
            assessed = bounded(assessed, 11);
            omissions = bounded(omissions, 32);
            if (identity != IdentityAdmission.ADMITTED && !assessed.isEmpty()) {
                throw new IllegalArgumentException("Unqualified effect pair");
            }
        }
    }

    /** Coordinator must bind this detached observation to its sealed endpoint/session. */
    public record TargetObservation(
            long requestedId, TargetState state, SourceFacts facts, PresenceEvidence presence) {
        public TargetObservation {
            Objects.requireNonNull(state);
            Objects.requireNonNull(presence);
            if (requestedId < Integer.MIN_VALUE
                    || requestedId > 0xffff_ffffL
                    || (state == TargetState.OBSERVED) != (facts != null)
                    || state == TargetState.PROTECTED && !presence.protectedRow()) {
                throw new IllegalArgumentException("Invalid explicit effect target");
            }
        }
    }

    public record LinkIdentity(RelationMode mode, long parentItemId, long ownLinkRowId) {
        public LinkIdentity {
            Objects.requireNonNull(mode);
            id(ownLinkRowId);
            if (parentItemId < 1 || parentItemId > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Effect parent must be positive signed32");
            }
        }
    }

    public record RelationFacts(
            LinkIdentity identity,
            SourceFacts link,
            TargetObservation target,
            List<Omission> omissions) {
        public RelationFacts {
            Objects.requireNonNull(link);
            Objects.requireNonNull(target);
            omissions = bounded(omissions, 32);
        }
    }

    static void id(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Unsigned32 ID required");
        }
    }

    static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Invalid bounded effect text");
        }
    }

    static void optional(String value, int limit) {
        if (value != null && value.length() > limit) {
            throw new IllegalArgumentException("Effect text limit");
        }
    }

    static <T> List<T> bounded(List<T> values, int limit) {
        Objects.requireNonNull(values);
        if (values.size() > limit) {
            throw new IllegalArgumentException("Effect collection limit");
        }
        return List.copyOf(values);
    }
}
