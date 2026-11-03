package com.vibeminer.formats.generated;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.db2.NativeRowIssueOrigin;
import com.vibeminer.formats.internal.Json;

/** Immutable graph-bound evidence, exposed only after graph validation. Null means UNKNOWN. */
public record NativeIssueCertificate(
        String format,
        String policyId,
        String producerId,
        String sourceIdentitySha256,
        String graphIdentity,
        String resolvedBlockSha256,
        String readerOptionsSha256,
        String actualColumnsSha256,
        int flattenedColumnCount,
        long emittedRowCount) {
    public static final String FORMAT = "native-row-issue-certificate-v1";

    static NativeIssueCertificate create(
            GeneratedTableSource source,
            boolean scoped,
            String graph,
            Db2Types.Header header,
            long count) {
        return new NativeIssueCertificate(
                FORMAT,
                NativeRowIssueOrigin.POLICY_ID,
                NativeRowIssueOrigin.PRODUCER_ID,
                source.baseHash(scoped),
                graph,
                source.definition(),
                source.readerOptions(),
                NativeIssueIdentity.columns(header.columns()),
                header.columns().size(),
                count);
    }

    void validate(
            GeneratedTableSource source,
            boolean scoped,
            String graph,
            Db2Types.Header header,
            long count) {
        var expected = create(source, scoped, graph, header, count);
        if (!equals(expected)
                || Json.bytes(this).length > 4096
                || flattenedColumnCount < 0
                || emittedRowCount < 0) {
            throw new IllegalArgumentException("Invalid native issue certificate");
        }
    }
}
