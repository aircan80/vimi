package com.vibeminer.formats.tables;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.util.List;
import java.util.Objects;

/** Immutable capture facts; READY and ordinal exhaustion never certify whole-table membership. */
public final class CapturedRowsTypes {
    private CapturedRowsTypes() {}

    public enum CaptureState {
        CAPTURED,
        UNSUPPORTED,
        NOT_PREPARED,
        SOURCE_UNAVAILABLE,
        SCHEMA_UNSUPPORTED,
        CORRUPT,
        CANCELED
    }

    public enum ReadState {
        READ,
        CORRUPT,
        CANCELED,
        CLOSED,
        INVALIDATED
    }

    public enum Retention {
        BEST_EFFORT_INVALIDATING
    }

    public enum Membership {
        NOT_CERTIFIED
    }

    public enum IssueChannelState {
        UNKNOWN,
        VERIFIED_NATIVE
    }

    /** Graph-bound decoder provenance; never a certificate of membership or issue-free cells. */
    public record IssueChannelEvidence(
            IssueChannelState state,
            String policyId,
            String producerId,
            String graphIdentity,
            String sourceIdentitySha256,
            String resolvedBlockSha256,
            String readerOptionsSha256,
            String actualColumnsSha256) {
        public IssueChannelEvidence {
            Objects.requireNonNull(state);
            var values =
                    java.util.Arrays.asList(
                            policyId,
                            producerId,
                            graphIdentity,
                            sourceIdentitySha256,
                            resolvedBlockSha256,
                            readerOptionsSha256,
                            actualColumnsSha256);
            if (state == IssueChannelState.UNKNOWN) {
                if (values.stream().anyMatch(Objects::nonNull)) {
                    throw new IllegalArgumentException(
                            "Unknown issue evidence carries no native claims");
                }
            } else {
                if (!"native-row-issues-v1".equals(policyId)
                        || !"java-db2-native-producer-v1".equals(producerId)
                        || graphIdentity == null
                        || !graphIdentity.matches("[0-9a-f]{32}")) {
                    throw new IllegalArgumentException("Unsupported native issue evidence");
                }
                for (var digest :
                        java.util.Arrays.asList(
                                sourceIdentitySha256,
                                resolvedBlockSha256,
                                readerOptionsSha256,
                                actualColumnsSha256)) {
                    if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("Invalid native evidence digest");
                    }
                }
            }
        }

        public static IssueChannelEvidence unknown() {
            return new IssueChannelEvidence(
                    IssueChannelState.UNKNOWN, null, null, null, null, null, null, null);
        }
    }

    /** Null fingerprint is an explicitly unknown key status, never key bytes. */
    public record KeyFingerprint(String name, String fingerprint) {
        public KeyFingerprint {
            Objects.requireNonNull(name);
        }
    }

    public record Receipt(
            BuildRef build,
            String locale,
            TableTypes.Entry table,
            TableSchema schema,
            List<Db2Types.Column> columns,
            String definitionSha256,
            String blockIdentity,
            String graphIdentity,
            String sourceIdentity,
            String readerOptions,
            String readerRevision,
            String rowEncoding,
            String encodingScope,
            long rowCount,
            Db2Types.Header header,
            Db2Types.Completion completion,
            List<Db2Types.Section> sections,
            List<String> holeKeys,
            List<KeyFingerprint> effectiveKeys,
            Retention retention,
            Membership membership,
            IssueChannelEvidence issueChannelEvidence) {
        /** Existing custom producers remain unknown without explicit validated native evidence. */
        public Receipt(
                BuildRef build,
                String locale,
                TableTypes.Entry table,
                TableSchema schema,
                List<Db2Types.Column> columns,
                String definitionSha256,
                String blockIdentity,
                String graphIdentity,
                String sourceIdentity,
                String readerOptions,
                String readerRevision,
                String rowEncoding,
                String encodingScope,
                long rowCount,
                Db2Types.Header header,
                Db2Types.Completion completion,
                List<Db2Types.Section> sections,
                List<String> holeKeys,
                List<KeyFingerprint> effectiveKeys,
                Retention retention,
                Membership membership) {
            this(
                    build,
                    locale,
                    table,
                    schema,
                    columns,
                    definitionSha256,
                    blockIdentity,
                    graphIdentity,
                    sourceIdentity,
                    readerOptions,
                    readerRevision,
                    rowEncoding,
                    encodingScope,
                    rowCount,
                    header,
                    completion,
                    sections,
                    holeKeys,
                    effectiveKeys,
                    retention,
                    membership,
                    IssueChannelEvidence.unknown());
        }

        public Receipt {
            Objects.requireNonNull(build);
            Objects.requireNonNull(locale);
            Objects.requireNonNull(table);
            Objects.requireNonNull(schema);
            columns = List.copyOf(columns);
            Objects.requireNonNull(definitionSha256);
            Objects.requireNonNull(blockIdentity);
            Objects.requireNonNull(graphIdentity);
            Objects.requireNonNull(sourceIdentity);
            Objects.requireNonNull(readerOptions);
            Objects.requireNonNull(readerRevision);
            Objects.requireNonNull(rowEncoding);
            Objects.requireNonNull(header);
            Objects.requireNonNull(completion);
            sections = List.copyOf(sections);
            holeKeys = List.copyOf(holeKeys);
            effectiveKeys = List.copyOf(effectiveKeys);
            Objects.requireNonNull(retention);
            Objects.requireNonNull(membership);
            Objects.requireNonNull(issueChannelEvidence);
            if (rowCount < 0) {
                throw new IllegalArgumentException("Negative captured row count");
            }
        }
    }

    public record Capture(CaptureState state, CapturedRows rows, String code, String message) {
        public Capture {
            Objects.requireNonNull(state);
            if ((state == CaptureState.CAPTURED) != (rows != null)) {
                throw new IllegalArgumentException("Captured handle/state disagree");
            }
        }

        public static Capture unsupported() {
            return new Capture(
                    CaptureState.UNSUPPORTED,
                    null,
                    "CAPTURE_UNSUPPORTED",
                    "Prepared-local row capture is unsupported");
        }
    }

    /**
     * Null textIssues within a returned row means an unknown issue channel, not known issue-free.
     */
    public enum CellState {
        LIMITED
    }

    /** columnIndex -1 marks a limited issue channel for the whole row, never known issue-free. */
    public record CellIssue(long ordinal, int columnIndex, CellState state, String code) {
        public CellIssue {
            Objects.requireNonNull(state);
            Objects.requireNonNull(code);
            if (ordinal < 0 || columnIndex < -1) {
                throw new IllegalArgumentException("Invalid cell issue location");
            }
        }
    }

    /**
     * Returned raw values obey byte bounds; callers must inspect cellIssues before interpreting a
     * placeholder. The original nullable textIssues channel remains distinct from these limits.
     */
    public record Page(
            String identity,
            long firstOrdinal,
            ReadState state,
            List<TableTypes.Row> rows,
            Long nextOrdinal,
            String code,
            String message,
            List<CellIssue> cellIssues) {
        public Page(
                String identity,
                long firstOrdinal,
                ReadState state,
                List<TableTypes.Row> rows,
                Long nextOrdinal,
                String code,
                String message) {
            this(identity, firstOrdinal, state, rows, nextOrdinal, code, message, List.of());
        }

        public Page {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(state);
            rows = List.copyOf(rows);
            cellIssues = List.copyOf(cellIssues);
            if (state != ReadState.READ
                    && (!rows.isEmpty() || nextOrdinal != null || !cellIssues.isEmpty())) {
                throw new IllegalArgumentException("Failed page carries no rows/EOF claim");
            }
        }
    }
}
