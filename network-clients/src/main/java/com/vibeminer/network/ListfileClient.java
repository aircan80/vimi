package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.ListfilePart;
import com.vibeminer.network.NetworkTypes.ListfileReply;
import java.util.concurrent.CompletableFuture;

/** Layer 1 byte-fetch boundary. Implementations never select a store or interpret game data. */
public interface ListfileClient {
    CompletableFuture<ListfileReply> fetchRelease(Cancellation cancellation);

    CompletableFuture<ListfilePart> fetchPart(String tag, int index, Cancellation cancellation);
}
