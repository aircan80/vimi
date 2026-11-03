package com.vibeminer.network;

import java.util.concurrent.CompletableFuture;

/** Bounded retrieval of the fixed public WoW TACT key list. */
@FunctionalInterface
public interface TactKeyClient {
    CompletableFuture<NetworkTypes.TactKeyReply> fetchTactKeys(Cancellation cancellation);
}
