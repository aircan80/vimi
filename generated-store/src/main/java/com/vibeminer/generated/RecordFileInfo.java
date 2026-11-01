package com.vibeminer.generated;

/** Sizes of the file this writer built; an existing manifest wins if manifestWasPresent is true. */
public record RecordFileInfo(
        GeneratedKey manifest,
        long count,
        long uncompressedBytes,
        long compressedBytes,
        long idIndexBytes,
        long manifestBytes,
        int blocks,
        int idChunks,
        int segments,
        boolean manifestWasPresent) {}
