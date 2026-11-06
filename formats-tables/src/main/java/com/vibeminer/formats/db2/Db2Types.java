package com.vibeminer.formats.db2;

import java.util.List;

/** Immutable reader contracts. IDs are unsigned32 represented by long. */
public final class Db2Types {
    private Db2Types() {}

    public record RawIntegerRule(
            String expectedDefinitionSha256,
            String tableHash,
            String layoutHash,
            int fieldOrdinal,
            String fieldName,
            int storageKind,
            int physicalBits,
            int candidateBits,
            boolean candidateUnsigned,
            boolean narrowsWhenConstant) {}

    public record Limits(
            long maxDecodedBytes,
            long maxRows,
            int maxFields,
            int maxSections,
            int maxRecordBytes,
            int maxAuxiliaryBytes,
            int maxStringBytes,
            long maxIndexBytes,
            int maxColumns,
            int maxCopyDepth,
            boolean allowPartialEncryptedSections,
            boolean allowRawTextEvidence,
            long maxRawTextEvidenceBytes,
            long maxRelationshipBytes,
            int maxRelationshipEntries,
            String definitionSha256,
            List<RawIntegerRule> verifiedRawIntegerRules) {
        public Limits() {
            this(
                    256L << 20,
                    2000000,
                    256,
                    128,
                    65536,
                    32 << 20,
                    65536,
                    256L << 20,
                    512,
                    64,
                    false,
                    false,
                    16L << 20,
                    16L << 20,
                    2000000,
                    null,
                    null);
        }

        public List<RawIntegerRule> rules() {
            return verifiedRawIntegerRules == null ? List.of() : verifiedRawIntegerRules;
        }

        public Limits {
            verifiedRawIntegerRules =
                    verifiedRawIntegerRules == null ? null : List.copyOf(verifiedRawIntegerRules);
            if (maxDecodedBytes < 204
                    || maxRows < 0
                    || maxRows > Integer.MAX_VALUE
                    || maxFields < 1
                    || maxSections < 0
                    || maxRecordBytes < 1
                    || maxAuxiliaryBytes < 0
                    || maxStringBytes < 1
                    || maxIndexBytes < 0
                    || maxColumns < 1
                    || maxCopyDepth < 1
                    || maxRawTextEvidenceBytes < 0
                    || maxRelationshipBytes < 0
                    || maxRelationshipEntries < 0) {
                throw new IllegalArgumentException("Invalid DB2 limits");
            }
        }
    }

    /** Policy of a column whose base rows keep the physical integer. */
    public static final String RAW_PHYSICAL_POLICY = "raw-physical-integer-v1";

    /** Warning of a raw column kept only because its provider name is unverified. */
    public static final String UNVERIFIED_RAW_WARNING =
            "Unverified provider field: values preserve the physical integer without narrowing to the candidate schema. Names, relationships and semantic meaning are unverified.";

    public record Interpretation(
            String policy,
            int physicalBits,
            boolean physicalSigned,
            int candidateBits,
            boolean candidateUnsigned,
            String warning) {
        public Interpretation {
            java.util.Objects.requireNonNull(policy);
            java.util.Objects.requireNonNull(warning);
        }
    }

    public record Narrowing(int storedBits, int usedBits) {}

    public record Column(
            String name,
            String kind,
            boolean isId,
            Interpretation interpretation,
            Narrowing narrowing) {
        public Column {
            java.util.Objects.requireNonNull(name);
            java.util.Objects.requireNonNull(kind);
        }
    }

    public record TextIssue(int columnIndex, String code, String rawBytesHex) {
        public TextIssue {
            java.util.Objects.requireNonNull(code);
            java.util.Objects.requireNonNull(rawBytesHex);
        }
    }

    public record Row(
            long id,
            boolean isCopy,
            Long copySourceId,
            List<String> values,
            List<TextIssue> textIssues) {
        public Row {
            values = List.copyOf(values);
            textIssues = textIssues == null ? null : List.copyOf(textIssues);
        }
    }

    public record Header(
            long schemaVersion,
            String tableHash,
            String layoutHash,
            long declaredRecords,
            long declaredCopyRecords,
            int recordSize,
            List<Column> columns,
            String format) {
        public Header {
            if (schemaVersion < 0 || schemaVersion > 0xffffffffL) {
                throw new IllegalArgumentException("Unsigned32 schema version required");
            }
            java.util.Objects.requireNonNull(tableHash);
            java.util.Objects.requireNonNull(layoutHash);
            java.util.Objects.requireNonNull(format);
            columns = List.copyOf(columns);
        }
    }

    public record Completion(
            long declaredRecords,
            long physicalRows,
            long copyRows,
            int excludedEncryptedSections,
            long excludedPhysicalRecords,
            long excludedCopyRecords,
            String scope,
            long consumedBytes,
            long textIssueCount) {
        public Completion {
            java.util.Objects.requireNonNull(scope);
        }
    }

    public record Hole(long offset, long length, String name) {}

    public record Section(
            int index,
            int recordCount,
            int copyCount,
            String key,
            boolean isEncrypted,
            int protectedCopyCount,
            int unresolvedCopyCount,
            boolean isDecrypted) {
        public Section {
            java.util.Objects.requireNonNull(key);
        }
    }

    public sealed interface Lookup permits Found, Protected, Absent, NotInReadableSections {}

    public record Found(Row row) implements Lookup {}

    public record Protected(int section, String key, long protectedId) implements Lookup {}

    public record Absent() implements Lookup {}

    public record NotInReadableSections() implements Lookup {}

    public record OrdinalLookup(long ordinal, Lookup lookup) {}
}
