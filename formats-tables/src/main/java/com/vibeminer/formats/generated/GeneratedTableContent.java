package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Provisional decoded content; owner always closes after store, including failed/already-present
 * writes.
 */
public final class GeneratedTableContent {
    private final Db2Types.Header header;
    private final List<Db2Types.Section> sections;
    private final Db2Types.Completion completion;
    private final List<String> keysUsed;
    private final Rows rows;
    private final AutoCloseable owner;
    private final List<String> holeKeys;
    private final List<String> chunkKeys;
    private final com.vibeminer.formats.db2.NativeRowIssueOrigin origin;

    @FunctionalInterface
    public interface Rows {
        CompletableFuture<List<Db2Types.Row>> read(long first, int count, Cancellation token);
    }

    public GeneratedTableContent(
            Db2Types.Header header,
            List<Db2Types.Section> sections,
            Db2Types.Completion completion,
            List<String> keysUsed,
            Rows rows,
            AutoCloseable owner,
            List<String> holeKeys,
            List<String> chunkKeys) {
        this(header, sections, completion, keysUsed, rows, owner, holeKeys, chunkKeys, null);
    }

    private GeneratedTableContent(
            Db2Types.Header header,
            List<Db2Types.Section> sections,
            Db2Types.Completion completion,
            List<String> keysUsed,
            Rows rows,
            AutoCloseable owner,
            List<String> holeKeys,
            List<String> chunkKeys,
            com.vibeminer.formats.db2.NativeRowIssueOrigin origin) {
        this.header = header;
        this.sections = List.copyOf(sections);
        this.completion = completion;
        this.keysUsed = List.copyOf(keysUsed);
        this.rows = rows;
        this.owner = owner;
        this.holeKeys = holeKeys == null ? List.of() : List.copyOf(holeKeys);
        this.chunkKeys = chunkKeys == null ? List.of() : List.copyOf(chunkKeys);
        this.origin = origin;
    }

    public Db2Types.Header header() {
        return header;
    }

    public List<Db2Types.Section> sections() {
        return sections;
    }

    public Db2Types.Completion completion() {
        return completion;
    }

    public List<String> keysUsed() {
        return keysUsed;
    }

    public Rows rows() {
        return rows;
    }

    public AutoCloseable owner() {
        return owner;
    }

    public List<String> holeKeys() {
        return holeKeys;
    }

    public List<String> chunkKeys() {
        return chunkKeys;
    }

    public static GeneratedTableContent nativeDb2(
            GeneratedTableSource source,
            com.vibeminer.formats.db2.Db2Table table,
            List<String> keysUsed,
            AutoCloseable owner,
            List<String> holeKeys,
            List<String> chunkKeys) {
        var origin = table.nativeIssueOrigin();
        if (source.issuePolicy() != GeneratedTableSource.IssuePolicy.NATIVE_V1
                || !source.definition().equals(origin.resolvedBlockSha256())
                || !source.readerOptions().equals(origin.readerOptionsSha256())
                || !origin.matches(table.header())) {
            throw new IllegalArgumentException("Native origin does not match requested source");
        }
        return new GeneratedTableContent(
                table.header(),
                table.sections(),
                table.completion(),
                keysUsed,
                table::readOrdinals,
                owner,
                holeKeys,
                chunkKeys,
                origin);
    }

    void validateSource(GeneratedTableSource source) {
        if ((source.issuePolicy() == GeneratedTableSource.IssuePolicy.NATIVE_V1) != (origin != null)
                || origin != null
                        && (!source.definition().equals(origin.resolvedBlockSha256())
                                || !source.readerOptions().equals(origin.readerOptionsSha256())
                                || !origin.matches(header))) {
            throw new IllegalArgumentException("Source policy and content origin disagree");
        }
    }

    void validateRow(Db2Types.Row row) {
        if (origin == null) {
            return;
        }
        if (row.values().size() != origin.flattenedColumnCount()
                || row.id() < 0
                || row.id() > 0xffffffffL
                || row.isCopy() != (row.copySourceId() != null)
                || row.copySourceId() != null
                        && (row.copySourceId() < 0 || row.copySourceId() > 0xffffffffL)) {
            throw new com.vibeminer.formats.Db2FormatException(
                    "DB2_INVALID", "Invalid native row shape");
        }
        if (row.textIssues() != null) {
            for (var issue : row.textIssues()) {
                if (issue.columnIndex() < 0
                        || issue.columnIndex() >= origin.flattenedColumnCount()) {
                    throw new com.vibeminer.formats.Db2FormatException(
                            "DB2_INVALID", "Invalid native issue location");
                }
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GeneratedTableContent that)) {
            return false;
        }
        return java.util.Objects.equals(header, that.header)
                && java.util.Objects.equals(sections, that.sections)
                && java.util.Objects.equals(completion, that.completion)
                && java.util.Objects.equals(keysUsed, that.keysUsed)
                && java.util.Objects.equals(rows, that.rows)
                && java.util.Objects.equals(owner, that.owner)
                && java.util.Objects.equals(holeKeys, that.holeKeys)
                && java.util.Objects.equals(chunkKeys, that.chunkKeys);
    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash = 31 * hash + java.util.Objects.hashCode(header);
        hash = 31 * hash + java.util.Objects.hashCode(sections);
        hash = 31 * hash + java.util.Objects.hashCode(completion);
        hash = 31 * hash + java.util.Objects.hashCode(keysUsed);
        hash = 31 * hash + java.util.Objects.hashCode(rows);
        hash = 31 * hash + java.util.Objects.hashCode(owner);
        hash = 31 * hash + java.util.Objects.hashCode(holeKeys);
        hash = 31 * hash + java.util.Objects.hashCode(chunkKeys);
        return hash;
    }

    public boolean encrypted() {
        return !holeKeys.isEmpty() || !chunkKeys.isEmpty();
    }
}
