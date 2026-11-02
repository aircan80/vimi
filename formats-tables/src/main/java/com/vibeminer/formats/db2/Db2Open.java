package com.vibeminer.formats.db2;

import java.time.Instant;
import java.util.List;

/** Owns an opened table's decoded source; dispose after every lazy row read has completed. */
public record Db2Open(
        Db2FileSource.OpenState state,
        Db2Table table,
        Db2FileSource source,
        String code,
        String message,
        Instant observedAt,
        List<Db2Types.Hole> holes,
        List<String> chunkKeys)
        implements AutoCloseable {
    public Db2Open {
        holes = List.copyOf(holes);
        chunkKeys = List.copyOf(chunkKeys);
    }

    @Override
    public void close() {
        if (source != null) {
            source.close();
        }
    }
}
