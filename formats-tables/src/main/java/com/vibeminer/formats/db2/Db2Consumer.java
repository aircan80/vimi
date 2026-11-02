package com.vibeminer.formats.db2;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Callbacks remain provisional until framing and separate upstream authentication complete. */
public interface Db2Consumer {
    CompletableFuture<Void> header(Db2Types.Header header, Cancellation token);

    CompletableFuture<Void> row(Db2Types.Row row, Cancellation token);
}
