package com.vibeminer.formats.tables;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Layer5 client-table messages; all collection fields are immutable snapshots. */
public final class TableTypes {
    private TableTypes() {}

    public enum Priority {
        INTERACTIVE,
        BACKGROUND
    }

    public record Ref(Long fileDataId, String name) {
        public Ref {
            if ((fileDataId == null) == (name == null)
                    || fileDataId != null && (fileDataId < 1 || fileDataId > 0xffffffffL)
                    || name != null && name.isBlank()) {
                throw new IllegalArgumentException("One positive FileDataID or name required");
            }
        }

        public static Ref id(long id) {
            return new Ref(id, null);
        }

        public static Ref forName(String name) {
            return new Ref(null, name);
        }
    }

    public enum State {
        NOT_CHECKED,
        READY,
        PARTIAL,
        ENCRYPTED,
        NOT_STORED,
        NEEDS_NETWORK,
        NOT_IN_BUILD,
        NOT_IN_LOCALE,
        NOT_IN_ENCODING,
        NO_DEFINITION,
        DEFINITION_UNUSABLE,
        UNSUPPORTED,
        TOO_LARGE,
        REJECTED,
        CORRUPT,
        MISSING,
        UNAVAILABLE,
        WRONG_KEY,
        STORE_FAILED
    }

    public record Reason(
            String code,
            String message,
            Instant observedAt,
            TableDefinitions.State definitionState,
            com.vibeminer.formats.db2.Db2FileSource.OpenState db2OpenState,
            List<String> keyNames) {
        public Reason {
            keyNames = List.copyOf(keyNames);
        }
    }

    public record Counts(
            long declaredRows,
            long readableRows,
            int excludedSections,
            long excludedRecords,
            String format,
            String layoutHash) {}

    public record Entry(
            long fileDataId,
            String name,
            State state,
            boolean checked,
            Reason reason,
            Counts counts,
            String contentKey) {}

    public record ListFilter(String name, Set<State> states) {
        public static final ListFilter ALL = new ListFilter(null, null);

        public ListFilter {
            states = states == null ? null : Set.copyOf(states);
        }
    }

    public record SchemaResult(Entry table, List<Db2Types.Column> columns, TableSchema schema) {
        public SchemaResult {
            columns = columns == null ? null : List.copyOf(columns);
        }
    }

    public record Row(long ordinal, Db2Types.Row row) {}

    public enum PageState {
        OK,
        NOT_READABLE,
        STALE,
        INVALID_CURSOR,
        BAD_FILTER
    }

    public enum FilterOp {
        EQUALS,
        BETWEEN
    }

    public record Filter(String column, FilterOp op, String value, String upper) {}

    public record PageRequest(
            Ref table, long after, int limit, List<Filter> filters, String identity) {
        public PageRequest {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public PageRequest(Ref table) {
            this(table, -1, 50, List.of(), null);
        }
    }

    public record Page(
            Entry table,
            PageState state,
            List<Row> rows,
            Long nextAfter,
            String identity,
            long scanned,
            String code,
            String message) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    public enum RowOutcome {
        FOUND,
        ABSENT,
        PROTECTED,
        UNKNOWN
    }

    public record RowResult(Entry table, RowOutcome outcome, Row row, String protectedKey) {}

    public record FollowRequest(Ref table, long id, String column, long after, int limit) {
        public FollowRequest(Ref table, long id, String column) {
            this(table, id, column, -1, 50);
        }
    }

    public enum FollowOutcome {
        FOUND,
        ABSENT,
        UNKNOWN,
        KEY_OUT_OF_RANGE,
        NOT_A_REFERENCE,
        SOURCE_ROW,
        UNINDEXED
    }

    public record FollowResult(
            FollowOutcome outcome,
            RowResult source,
            TableSchema.ForeignKey reference,
            Entry target,
            List<Row> rows,
            Long nextAfter,
            String code,
            String message,
            String protectedKey) {
        public FollowResult {
            rows = List.copyOf(rows);
        }
    }

    public record ReferencesRequest(Ref table, long id, int limitPerSource) {
        public ReferencesRequest(Ref table, long id) {
            this(table, id, 50);
        }
    }

    public enum ReferenceScope {
        COMPLETE,
        PARTIAL,
        NOT_CHECKED
    }

    public record ReferenceSource(
            Entry table,
            String column,
            TableSchema.ForeignKey reference,
            ReferenceScope scope,
            List<Long> rowIds,
            boolean truncated,
            String code) {
        public ReferenceSource {
            rowIds = List.copyOf(rowIds);
        }
    }

    public enum ReferencesOutcome {
        SEARCHED,
        NO_REFERENCES,
        SOURCE_ROW
    }

    public record ReferencesResult(
            ReferencesOutcome outcome,
            RowResult row,
            List<ReferenceSource> sources,
            List<Entry> unresolved) {
        public ReferencesResult {
            sources = List.copyOf(sources);
            unresolved = List.copyOf(unresolved);
        }
    }

    public enum PreloadStage {
        OPENING,
        DOWNLOADING,
        PREPARING
    }

    public enum PreloadOutcome {
        COMPLETED,
        INCOMPLETE,
        CANCELED,
        FAILED
    }

    public record PreloadProgress(
            PreloadStage stage,
            long filesDone,
            long filesTotal,
            long filesFailed,
            long bytesDone,
            long bytesTotal,
            long cdnBytes,
            int tablesDone,
            int tablesTotal,
            int tablesFailed) {}

    public record PreloadResult(
            PreloadOutcome outcome,
            PreloadProgress progress,
            List<Entry> tables,
            FilesOpenState openState,
            String code,
            String message) {
        public PreloadResult {
            tables = List.copyOf(tables);
        }
    }

    public record OpenResult(
            BuildRef build,
            String locale,
            FilesOpenState state,
            BuildTables tables,
            String code,
            String message,
            Instant observedAt) {}
}
