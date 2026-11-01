package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.DefinitionReply;
import java.util.concurrent.CompletableFuture;

/** Layer 1 byte-fetch boundary. Implementations never select a store or interpret game data. */
public interface DefinitionClient {
    CompletableFuture<DefinitionReply> fetch(String tableName, Cancellation cancellation);
}
