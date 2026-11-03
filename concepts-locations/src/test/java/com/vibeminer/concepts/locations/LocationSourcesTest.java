package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.eglink.EgLinkRepository;
import com.vibeminer.eglink.EgLinkTypes;
import com.vibeminer.network.EgLinkClient;
import com.vibeminer.network.NetworkTypes;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocationSourcesTest {
    @TempDir Path directory;

    @Test
    void selectedEgBuildWithoutNpcDatasetStaysUnknownWithoutAnyNetworkOrFallback()
            throws Exception {
        var calls = new AtomicInteger();
        EgLinkClient client =
                new EgLinkClient() {
                    public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkHead(
                            com.vibeminer.network.Cancellation token) {
                        calls.incrementAndGet();
                        throw new AssertionError("Browse must not acquire data");
                    }

                    public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkFile(
                            String revision,
                            String path,
                            com.vibeminer.network.Cancellation token) {
                        calls.incrementAndGet();
                        throw new AssertionError("Browse must not acquire data");
                    }
                };
        try (var repository = new EgLinkRepository(directory, client)) {
            var build =
                    new EgLinkTypes.Build(
                            "a".repeat(40),
                            "wowf",
                            "1.60.1.70707",
                            List.of(new EgLinkTypes.Dataset("hotfixes", 0, 0, "2026-11-01")));
            var snapshot =
                    new EgLinkTypes.Snapshot("b".repeat(64), build, Instant.EPOCH, 0, 0L, 0L, null);
            var input = LocationSources.egLink(repository, snapshot);
            assertEquals("1.60.1.70707", input.source().build());
            assertEquals("wowf", input.source().product());
            var locations = input.reader().locations(LocationAdaptersTest.NPC, Cancellation.NONE);
            var starters = input.reader().starters(788, Cancellation.NONE);
            assertEquals(LocationTypes.State.UNKNOWN, locations.state());
            assertEquals(LocationTypes.State.UNKNOWN, starters.state());
            assertTrue(locations.locations().isEmpty());
            assertTrue(starters.starters().isEmpty());
            assertEquals(0, calls.get());
        }
    }
}
