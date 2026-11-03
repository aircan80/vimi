package com.vibeminer.network;

import java.util.concurrent.CompletableFuture;

/** GitHub-only published Polestar data; raw paths require an immutable repository commit. */
public interface PolestarClient {
    CompletableFuture<NetworkTypes.PolestarReply> fetchPolestarHead(Cancellation cancellation);

    CompletableFuture<NetworkTypes.PolestarReply> fetchPolestarFile(
            String revision, String path, Cancellation cancellation);
}
