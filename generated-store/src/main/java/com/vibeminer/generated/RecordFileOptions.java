package com.vibeminer.generated;

/** C# record-file format 1 defaults and bounds. */
public record RecordFileOptions(
        int maxRecords,
        int maxRecordBytes,
        int maxMetadataBytes,
        int blockBytes,
        int segmentBytes,
        int compressionLevel) {
    public RecordFileOptions {
        if (maxRecords < 0
                || maxRecords > (64 << 20)
                || maxRecordBytes < 0
                || maxRecordBytes > (16 << 20)
                || maxMetadataBytes < 0
                || maxMetadataBytes > (16 << 20)
                || blockBytes < 1
                || blockBytes > (1 << 20)
                || segmentBytes < 1
                || segmentBytes > (32 << 20)
                || compressionLevel < 1
                || compressionLevel > 19) {
            throw new IllegalArgumentException("Invalid record-file options");
        }
    }

    public RecordFileOptions() {
        this(4 << 20, 1 << 20, 16 << 20, 16 << 10, 8 << 20, 9);
    }
}
