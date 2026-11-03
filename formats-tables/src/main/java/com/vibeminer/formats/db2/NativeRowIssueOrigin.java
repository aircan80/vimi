package com.vibeminer.formats.db2;

import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.generated.ResolvedDefinition;

/** Bounded capability minted only by the audited native reader construction path. */
public final class NativeRowIssueOrigin {
    public static final String POLICY_ID = "native-row-issues-v1";
    public static final String PRODUCER_ID = "java-db2-native-producer-v1";
    private final String resolvedBlockSha256, readerOptionsSha256, actualColumnsSha256;
    private final long schemaVersion;
    private final String tableHash, layoutHash, format;
    private final int flattenedColumnCount;

    NativeRowIssueOrigin(Block block, Db2Types.Limits limits, Db2Types.Header header) {
        resolvedBlockSha256 = ResolvedDefinition.hash(block);
        readerOptionsSha256 = NativeIssueIdentity.readerOptions(limits);
        actualColumnsSha256 = NativeIssueIdentity.columns(header.columns());
        schemaVersion = header.schemaVersion();
        tableHash = header.tableHash();
        layoutHash = header.layoutHash();
        format = header.format();
        flattenedColumnCount = header.columns().size();
    }

    public String resolvedBlockSha256() {
        return resolvedBlockSha256;
    }

    public String readerOptionsSha256() {
        return readerOptionsSha256;
    }

    public String actualColumnsSha256() {
        return actualColumnsSha256;
    }

    public int flattenedColumnCount() {
        return flattenedColumnCount;
    }

    public boolean matches(Db2Types.Header header) {
        return schemaVersion == header.schemaVersion()
                && tableHash.equals(header.tableHash())
                && layoutHash.equals(header.layoutHash())
                && format.equals(header.format())
                && flattenedColumnCount == header.columns().size()
                && actualColumnsSha256.equals(NativeIssueIdentity.columns(header.columns()));
    }
}
