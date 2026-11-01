package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.Reply;
import com.vibeminer.network.NetworkTypes.Request;
import com.vibeminer.network.NetworkTypes.Sink;
import java.util.concurrent.CompletableFuture;

/** Layer 1 byte-fetch boundary. Implementations never select a store or interpret game data. */
public interface CdnClient {
    CompletableFuture<Reply> fetch(CdnRoute route, Request request, Cancellation cancellation);

    CompletableFuture<Reply> fetchTo(
            CdnRoute route, Request request, Sink sink, Cancellation cancellation);

    default CompletableFuture<Reply> fetch(CdnRoute route, Request request) {
        return fetch(route, request, Cancellation.NONE);
    }
}
