package com.vibeminer.concepts.items;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable pure item-projection contracts; no source handles, comparison types or I/O. */
public final class PreparedItemTypes {
    private PreparedItemTypes() {}

    public enum SourceKind {
        ITEM(841626, "Item", "50238EC2"),
        ITEM_SPARSE(1572924, "ItemSparse", "919BE54E");
        private final long fileDataId;
        private final String tableName, tableHash;

        SourceKind(long id, String name, String hash) {
            fileDataId = id;
            tableName = name;
            tableHash = hash;
        }

        public long fileDataId() {
            return fileDataId;
        }

        public String tableName() {
            return tableName;
        }

        public String tableHash() {
            return tableHash;
        }
    }

    public enum SourceState {
        READABLE,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED,
        INCOMPATIBLE,
        INVALID,
        LIMITED
    }

    public enum FactFamily {
        NAME,
        QUALITY,
        CLASSIFICATION,
        LEVELS,
        FLAGS,
        RAW_DESCRIPTION
    }

    public enum FactId {
        NAME(FactFamily.NAME, "name"),
        QUALITY(FactFamily.QUALITY, "quality"),
        CLASS_ID(FactFamily.CLASSIFICATION, "class"),
        SUBCLASS_ID(FactFamily.CLASSIFICATION, "subclass"),
        INVENTORY_TYPE(FactFamily.CLASSIFICATION, "inventoryType"),
        ITEM_LEVEL(FactFamily.LEVELS, "itemLevel"),
        REQUIRED_LEVEL(FactFamily.LEVELS, "requiredLevel"),
        FLAGS_WORD(FactFamily.FLAGS, "flags"),
        RAW_DESCRIPTION(FactFamily.RAW_DESCRIPTION, "description");
        private final FactFamily family;
        private final String legacyKey;

        FactId(FactFamily family, String key) {
            this.family = family;
            legacyKey = key;
        }

        public FactFamily family() {
            return family;
        }

        public String legacyKey() {
            return legacyKey;
        }
    }

    public enum RowState {
        PRESENT,
        MISSING,
        PROTECTED,
        UNKNOWN,
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
        KNOWN_EMPTY,
        MISSING,
        UNKNOWN,
        PROTECTED,
        UNSUPPORTED,
        INVALID,
        LIMITED
    }

    public enum ScalarKind {
        INTEGER,
        TEXT,
        FLAG_WORD
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

    public enum Agreement {
        AGREE,
        DISAGREE,
        SINGLE_KNOWN_SOURCE,
        INDETERMINATE
    }

    public enum PresenceKind {
        UNKNOWN,
        ABSENCE_CERTIFIED,
        PROTECTION_OBSERVED
    }

    public record Selection(Set<FactFamily> families, String policyRevision) {
        public Selection {
            families = Set.copyOf(families);
            text(policyRevision, 128);
        }

        public static Selection defaults() {
            return new Selection(
                    Set.of(
                            FactFamily.NAME,
                            FactFamily.QUALITY,
                            FactFamily.CLASSIFICATION,
                            FactFamily.LEVELS,
                            FactFamily.FLAGS),
                    PreparedItemProjection.POLICY_REVISION);
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
            String readerRevision,
            String readerOptions,
            String rowEncoding,
            String graphIdentity,
            String effectiveKeysFingerprint) {
        public SourceStamp {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(build);
            text(locale, 16);
            text(tableName, 128);
            if (fileDataId < 1 || fileDataId > 0xffffffffL) {
                throw new IllegalArgumentException("Invalid source FileDataID");
            }
            for (String s :
                    java.util.Arrays.asList(
                            contentKey,
                            layoutHash,
                            definitionSha256,
                            blockIdentity,
                            readerRevision,
                            readerOptions,
                            rowEncoding,
                            graphIdentity,
                            effectiveKeysFingerprint)) {
                if (s != null) {
                    text(s, 512);
                }
            }
        }
    }

    public record SourceSchema(
            SourceStamp stamp,
            Db2Types.Header header,
            TableSchema schema,
            List<Db2Types.Column> columns,
            SourceState state,
            Selection selection) {
        public SourceSchema {
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(state);
            Objects.requireNonNull(selection);
            columns = columns == null ? null : List.copyOf(columns);
        }
    }

    public record Omission(FactFamily family, FactId fact, CellState state, String code) {
        public Omission {
            Objects.requireNonNull(state);
            text(code, 128);
        }
    }

    public record SourceQualifiedKey(
            FactId fact,
            SourceKind source,
            long fileDataId,
            String field,
            boolean array,
            int arraySlot,
            String semantics) {
        public SourceQualifiedKey {
            Objects.requireNonNull(fact);
            Objects.requireNonNull(source);
            text(field, 128);
            text(semantics, 128);
            if (array ? arraySlot < 0 : arraySlot != -1) {
                throw new IllegalArgumentException("Invalid source slot");
            }
        }
    }

    /** Compact admitted metadata only; schema/receipt/column objects are never retained. */
    public record Binding(
            SourceQualifiedKey key,
            int columnIndex,
            ScalarKind kind,
            Integer bits,
            Boolean unsigned,
            boolean relation,
            String referenceTable,
            String referenceColumn) {
        public Binding {
            Objects.requireNonNull(key);
            Objects.requireNonNull(kind);
            if (columnIndex < 0) {
                throw new IllegalArgumentException("Negative source column");
            }
            if (kind != ScalarKind.TEXT
                    && (bits == null || bits < 1 || bits > 64 || unsigned == null)) {
                throw new IllegalArgumentException("Integer domain required");
            }
            if (referenceTable != null) {
                text(referenceTable, 128);
            }
            if (referenceColumn != null) {
                text(referenceColumn, 128);
            }
        }
    }

    public record IdColumn(int columnIndex, String name) {
        public IdColumn {
            if (columnIndex < 0) {
                throw new IllegalArgumentException("Negative ID column");
            }
            text(name, 128);
        }
    }

    public record SourceProfile(
            SourceStamp stamp,
            SourceState state,
            Selection selection,
            String identityFingerprint,
            String fieldFingerprint,
            int columnCount,
            List<IdColumn> idColumns,
            List<Binding> bindings,
            List<Omission> omissions) {
        public SourceProfile {
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(state);
            Objects.requireNonNull(selection);
            text(identityFingerprint, 128);
            text(fieldFingerprint, 128);
            idColumns = List.copyOf(idColumns);
            bindings = List.copyOf(bindings);
            omissions = List.copyOf(omissions);
            if (columnCount < 0
                    || columnCount > 512
                    || idColumns.size() > 1
                    || bindings.size() > 64
                    || omissions.size() > 64) {
                throw new IllegalArgumentException("Profile inventory exceeds bound");
            }
            if (idColumns.stream().anyMatch(id -> id.columnIndex() >= columnCount)
                    || bindings.stream()
                            .anyMatch(
                                    binding ->
                                            binding.columnIndex() >= columnCount
                                                    || binding.key().source() != stamp.kind()
                                                    || binding.key().fileDataId()
                                                            != stamp.fileDataId())) {
                throw new IllegalArgumentException("Profile source/binding mismatch");
            }
            if (PreparedItemProjection.estimate(stamp, selection, idColumns, bindings, omissions)
                    > 65536) {
                throw new IllegalArgumentException("Profile metadata exceeds bound");
            }
        }

        public long estimatedRetainedBytes() {
            return PreparedItemProjection.estimate(
                    stamp, selection, idColumns, bindings, omissions);
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
            omissions = List.copyOf(omissions);
            if ((identity == IdentityAdmission.ADMITTED) != (profile != null)) {
                throw new IllegalArgumentException("Profile identity admission disagrees");
            }
        }
    }

    public record PresenceEvidence(PresenceKind kind, String identity, String code) {
        public PresenceEvidence {
            Objects.requireNonNull(kind);
            if (identity != null) {
                text(identity, 512);
            }
            if (code != null) {
                text(code, 128);
            }
            if (kind != PresenceKind.UNKNOWN && (identity == null || code == null)) {
                throw new IllegalArgumentException("Presence evidence required");
            }
        }

        public static PresenceEvidence unknown() {
            return new PresenceEvidence(PresenceKind.UNKNOWN, null, null);
        }
    }

    public record IssueOverride(int columnIndex, CellState state, String code) {
        public IssueOverride {
            if (columnIndex == -1 && state != CellState.LIMITED && state != CellState.UNKNOWN) {
                throw new IllegalArgumentException(
                        "Only unknown/limited row issue channels allowed");
            }
            if (columnIndex < -1
                    || state != CellState.LIMITED
                            && state != CellState.INVALID
                            && state != CellState.PROTECTED
                            && state != CellState.UNKNOWN) {
                throw new IllegalArgumentException("Invalid restrictive override");
            }
            text(code, 128);
        }
    }

    public record SourceRow(
            long itemId,
            RowState state,
            Db2Types.Row row,
            IssueChannel issueChannel,
            List<IssueOverride> overrides,
            PresenceEvidence evidence) {
        public SourceRow {
            id(itemId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(issueChannel);
            Objects.requireNonNull(evidence);
            overrides = List.copyOf(overrides);
            if (overrides.size() > 1024 || (state == RowState.PRESENT) != (row != null)) {
                throw new IllegalArgumentException("Row state/data disagree");
            }
            presence(state, evidence);
        }
    }

    public record CopyMetadata(Boolean copy, Long sourceId, CellState state) {
        public CopyMetadata {
            Objects.requireNonNull(state);
        }
    }

    public record SourceCell(
            SourceQualifiedKey key,
            CellState state,
            ScalarKind kind,
            String value,
            String rawEvidence,
            Long rowId,
            int columnIndex,
            CopyMetadata copy,
            List<String> issues) {
        public SourceCell {
            Objects.requireNonNull(key);
            Objects.requireNonNull(state);
            Objects.requireNonNull(kind);
            if (rowId != null) {
                id(rowId);
            }
            Objects.requireNonNull(copy);
            issues = List.copyOf(issues);
            if (state == CellState.VALUE ? value == null : value != null) {
                throw new IllegalArgumentException("Known value/state disagree");
            }
            if (value != null && value.length() > 8192
                    || rawEvidence != null && rawEvidence.length() > 128
                    || issues.size() > 8) {
                throw new IllegalArgumentException("Cell text exceeds bound");
            }
        }
    }

    public record SourceFacts(
            long itemId,
            SourceStamp stamp,
            SourceState sourceState,
            RowState rowState,
            IssueChannel issueChannel,
            List<SourceCell> cells,
            List<Omission> omissions,
            List<String> issues) {
        public SourceFacts {
            id(itemId);
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(sourceState);
            Objects.requireNonNull(rowState);
            Objects.requireNonNull(issueChannel);
            cells = List.copyOf(cells);
            omissions = List.copyOf(omissions);
            issues = List.copyOf(issues);
            if (cells.size() > 64 || omissions.size() > 64 || issues.size() > 16) {
                throw new IllegalArgumentException("Source facts exceed bound");
            }
        }
    }

    public record FactGroup(
            FactId fact,
            List<SourceCell> cells,
            Agreement agreement,
            Set<SourceKind> assessedSources) {
        public FactGroup {
            Objects.requireNonNull(fact);
            cells = List.copyOf(cells);
            Objects.requireNonNull(agreement);
            assessedSources = Set.copyOf(assessedSources);
        }
    }

    public record ItemView(
            long itemId,
            SourceFacts item,
            SourceFacts sparse,
            List<FactGroup> groups,
            SourceCell name,
            List<String> issues,
            String policyRevision) {
        public ItemView {
            id(itemId);
            Objects.requireNonNull(item);
            Objects.requireNonNull(sparse);
            groups = List.copyOf(groups);
            issues = List.copyOf(issues);
            text(policyRevision, 128);
        }
    }

    static void id(long value) {
        if (value < 0 || value > 0xffffffffL) {
            throw new IllegalArgumentException("Unsigned32 ItemID required");
        }
    }

    static void presence(RowState state, PresenceEvidence evidence) {
        if (state == RowState.MISSING && evidence.kind() != PresenceKind.ABSENCE_CERTIFIED
                || state == RowState.PROTECTED
                        && evidence.kind() != PresenceKind.PROTECTION_OBSERVED) {
            throw new IllegalArgumentException("Missing/protected row needs specific evidence");
        }
    }

    static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Bounded nonblank metadata required");
        }
    }
}
