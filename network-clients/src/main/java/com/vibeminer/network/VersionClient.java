package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.VersionReply;
import java.util.concurrent.CompletableFuture;

/** Layer 1 byte-fetch boundary. Implementations never select a store or interpret game data. */
public interface VersionClient {
    CompletableFuture<VersionReply> fetch(
            String product, String region, DocumentKind kind, Cancellation cancellation);
}
