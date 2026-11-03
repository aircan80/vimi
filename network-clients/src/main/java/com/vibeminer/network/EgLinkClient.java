package com.vibeminer.network;

import java.util.concurrent.CompletableFuture;

/** Fixed GitHub-only EG Link retrieval; callers pin raw files to a full commit. */
public interface EgLinkClient {
    CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkHead(Cancellation cancellation);

    CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkFile(
            String revision, String path, Cancellation cancellation);
}
