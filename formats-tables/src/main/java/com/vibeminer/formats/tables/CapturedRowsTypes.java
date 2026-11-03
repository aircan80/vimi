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
            Membership membership) {
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
