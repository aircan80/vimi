package com.vimi.gui;

import com.vibeminer.network.Cancellation;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.network.SourceUrls;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** Authored public-source boundary for deterministic native composition checks. */
final class OfflineSourceFixtures {
    private OfflineSourceFixtures() {}

    static CompletableFuture<NetworkTypes.TactKeyReply> publicKeys(Cancellation token) {
        token.check();
        return CompletableFuture.completedFuture(new NetworkTypes.TactKeyReply(
                SourceUrls.TACT_KEYS, Instant.EPOCH,
                "FFFFFFFFFFFFFFFF 00112233445566778899AABBCCDDEEFF\n".getBytes(StandardCharsets.US_ASCII)));
    }
}
