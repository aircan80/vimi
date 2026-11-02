package com.vibeminer.formats.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.MemoryObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.access.ObservationSource;
import com.vibeminer.access.SourceTypes;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FetchedDefinitionsTest {
    static final class Time extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void identicalBytesKeepFirstRevisionAndDateAcrossHistoryReloadWhileChecksAdvance() {
        var clock = new Time();
        var calls = new AtomicInteger();
        byte[] bytes =
                "COLUMNS\nint ID\n\nBUILD 1.2.3.4\n$id,noninline$ID<u32>"
                        .getBytes(StandardCharsets.UTF_8);
        var source =
                new MemoryObjectSource(
                        List.of(
                                new ObservationSource() {
                                    @Override
                                    public Set<SourceTypes.ObservationKind> kinds() {
                                        return Set.of(SourceTypes.ObservationKind.DEFINITION);
                                    }

                                    @Override
                                    public CompletableFuture<SourceTypes.Observation> fetch(
                                            SourceTypes.ObservationRequest request,
                                            Cancellation token) {
                                        int n = calls.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new SourceTypes.Observation(
                                                        request.kind(),
                                                        request.product(),
                                                        request.region(),
                                                        URI.create(
                                                                ObservationAdapters.definitionUrl(
                                                                        (n == 1 ? "a" : "b")
                                                                                .repeat(40),
                                                                        request.name())),
                                                        clock.instant(),
                                                        bytes));
                                    }
                                }));
        var definitions =
                new FetchedDefinitions(
                        source, clock, Duration.ofHours(24), Duration.ofMinutes(15), 1, 1000);
        var first =
                definitions
                        .ensure(
                                "11223344",
                                "Numbers",
                                d -> false,
                                s -> CompletableFuture.completedFuture(null),
                                Cancellation.NONE,
                                false)
                        .join();
        Instant fetched = first.definition().fetchedAt();
        assertTrue(first.asked());
        clock.now = clock.now.plusSeconds(60);
        assertTrue(
                !definitions
                        .ensure(
                                "11223344",
                                "Numbers",
                                d -> false,
                                s -> CompletableFuture.completedFuture(null),
                                Cancellation.NONE,
                                false)
                        .join()
                        .asked());
        clock.now = clock.now.plusSeconds(900);
        var second =
                definitions
                        .ensure(
                                "11223344",
                                "Numbers",
                                d -> false,
                                s -> CompletableFuture.completedFuture(null),
                                Cancellation.NONE,
                                false)
                        .join();
        assertEquals(fetched, second.definition().fetchedAt());
        assertEquals("a".repeat(40), second.definition().revision());
        assertEquals(clock.now, second.definition().checkedAt());
        var reopened =
                new FetchedDefinitions(
                                source,
                                clock,
                                Duration.ofHours(24),
                                Duration.ofMinutes(15),
                                1,
                                1000)
                        .cached("11223344", "Numbers", Cancellation.NONE)
                        .join();
        assertEquals(second.definition(), reopened);
        assertEquals(2, calls.get());
        assertEquals(
                clock.now.plus(Duration.ofMinutes(15)),
                definitions
                        .lastCheck("11223344", "Numbers", d -> false, Cancellation.NONE)
                        .join()
                        .nextCheckAfter());
    }
}
