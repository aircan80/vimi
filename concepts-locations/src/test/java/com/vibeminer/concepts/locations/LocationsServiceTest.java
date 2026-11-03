package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LocationsServiceTest {
    private static LocationInput input(LocationTypes.Source source, AtomicInteger calls) {
        return new LocationInput(
                source,
                new LocationInput.Reader() {
                    public LocationTypes.Locations locations(
                            LocationTypes.Entity entity, Cancellation token) {
                        calls.incrementAndGet();
                        token.check();
                        return switch (source.provider()) {
                            case QUESTIE ->
                                    LocationAdapters.questieLocations(
                                            entity,
                                            Map.of(
                                                    "spawns",
                                                    Map.of("12", List.of(List.of(10, 20)))),
                                            source);
                            case POLESTAR ->
                                    LocationAdapters.polestarLocations(
                                            entity,
                                            List.of(
                                                    Map.of(
                                                            "kind",
                                                            entity.kind()
                                                                            == LocationTypes
                                                                                    .EntityKind.NPC
                                                                    ? "npc"
                                                                    : "object",
                                                            "id",
                                                            entity.id(),
                                                            "map",
                                                            0,
                                                            "n",
                                                            1,
                                                            "x",
                                                            1,
                                                            "y",
                                                            2)),
                                            source);
                            case EG_LINK ->
                                    LocationAdapters.egLocations(
                                            entity,
                                            Map.of(
                                                    "positions",
                                                    List.of(
                                                            Map.of(
                                                                    "instance",
                                                                    0,
                                                                    "worldX",
                                                                    1,
                                                                    "worldY",
                                                                    2))),
                                            source);
                        };
                    }

                    public LocationTypes.Starters starters(long quest, Cancellation token) {
                        token.check();
                        return new LocationTypes.Starters(
                                LocationTypes.State.READY,
                                quest,
                                List.of(
                                        new LocationTypes.Starter(
                                                quest,
                                                LocationAdaptersTest.NPC,
                                                new LocationTypes.Evidence(
                                                        source, "quest_start", "1", "id"))),
                                List.of());
                    }
                });
    }

    @Test
    void constructionIsColdAndPriorityOrdersWithoutDiscardingOtherProviderLocations() {
        var calls = new AtomicInteger();
        try (var service =
                new LocationsService(
                        List.of(
                                input(LocationAdaptersTest.EG, calls),
                                input(LocationAdaptersTest.POLESTAR, calls),
                                input(LocationAdaptersTest.QUESTIE, calls)))) {
            assertEquals(0, calls.get());
            var result = service.locations(LocationAdaptersTest.NPC, Cancellation.NONE).join();
            assertEquals(3, result.locations().size());
            assertEquals(
                    List.of(
                            LocationTypes.Provider.QUESTIE,
                            LocationTypes.Provider.POLESTAR,
                            LocationTypes.Provider.EG_LINK),
                    result.locations().stream()
                            .map(p -> p.evidence().source().provider())
                            .toList());
            service.locations(LocationAdaptersTest.NPC, Cancellation.NONE).join();
            assertEquals(3, calls.get());
        }
    }

    @Test
    void providerFailureRetainsOtherPointsAndDoesNotPublishExceptionDetails() {
        var bad =
                new LocationInput(
                        LocationAdaptersTest.QUESTIE,
                        new LocationInput.Reader() {
                            public LocationTypes.Locations locations(
                                    LocationTypes.Entity entity, Cancellation token) {
                                throw new IllegalStateException("private fixture path");
                            }

                            public LocationTypes.Starters starters(long quest, Cancellation token) {
                                throw new IllegalStateException("private fixture path");
                            }
                        });
        try (var service =
                new LocationsService(
                        List.of(bad, input(LocationAdaptersTest.POLESTAR, new AtomicInteger())))) {
            var result = service.locations(LocationAdaptersTest.NPC, Cancellation.NONE).join();
            assertEquals(1, result.locations().size());
            assertEquals(LocationTypes.State.PARTIAL, result.state());
            assertFalse(result.issues().toString().contains("private fixture"));
        }
    }

    @Test
    void sameQuestStarterFromTwoProvidersMergesEvidenceAndKeepsLocationAlternatives() {
        var calls = new AtomicInteger();
        try (var service =
                new LocationsService(
                        List.of(
                                input(LocationAdaptersTest.QUESTIE, calls),
                                input(LocationAdaptersTest.POLESTAR, calls)))) {
            var result = service.pickups(783, Cancellation.NONE).join();
            assertEquals(1, result.pickups().size());
            assertEquals(2, result.pickups().getFirst().evidence().size());
            assertEquals(2, result.pickups().getFirst().locations().locations().size());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void itemStarterRemainsKnownButCannotTurnItsDropsIntoPickupMarkers() {
        var input =
                new LocationInput(
                        LocationAdaptersTest.QUESTIE,
                        new LocationInput.Reader() {
                            public LocationTypes.Locations locations(
                                    LocationTypes.Entity entity, Cancellation token) {
                                throw new AssertionError("Item coordinates must not be looked up");
                            }

                            public LocationTypes.Starters starters(long quest, Cancellation token) {
                                return LocationAdapters.questieStarters(
                                        quest,
                                        Map.of(
                                                "startedBy",
                                                List.of(List.of(), List.of(), List.of(42)),
                                                "sourceItemId",
                                                99),
                                        LocationAdaptersTest.QUESTIE);
                            }
                        });
        try (var service = new LocationsService(List.of(input))) {
            var result = service.pickups(1, Cancellation.NONE).join();
            assertEquals(1, result.pickups().size());
            assertEquals(
                    LocationTypes.EntityKind.ITEM, result.pickups().getFirst().entity().kind());
            assertEquals(
                    LocationTypes.State.UNSUPPORTED,
                    result.pickups().getFirst().locations().state());
            assertTrue(result.pickups().getFirst().locations().locations().isEmpty());
        }
    }

    @Test
    void mismatchedEntityQuestOrSourceEvidenceCannotSeedMarkers() {
        var source = LocationAdaptersTest.QUESTIE;
        var input =
                new LocationInput(
                        source,
                        new LocationInput.Reader() {
                            public LocationTypes.Locations locations(
                                    LocationTypes.Entity entity, Cancellation token) {
                                return LocationAdapters.questieLocations(
                                        LocationAdaptersTest.OBJECT,
                                        Map.of("spawns", Map.of("12", List.of(List.of(1, 2)))),
                                        source);
                            }

                            public LocationTypes.Starters starters(long quest, Cancellation token) {
                                return new LocationTypes.Starters(
                                        LocationTypes.State.READY,
                                        quest,
                                        List.of(
                                                new LocationTypes.Starter(
                                                        quest + 1,
                                                        LocationAdaptersTest.NPC,
                                                        new LocationTypes.Evidence(
                                                                source, "quest_start", "1", "id"))),
                                        List.of());
                            }
                        });
        try (var service = new LocationsService(List.of(input))) {
            assertTrue(
                    service.locations(LocationAdaptersTest.NPC, Cancellation.NONE)
                            .join()
                            .locations()
                            .isEmpty());
            assertTrue(service.pickups(1, Cancellation.NONE).join().pickups().isEmpty());
        }
    }

    @Test
    void emptyInputsAreUnknownAndInvalidQueriesAreRejected() {
        try (var service = new LocationsService(List.of())) {
            assertEquals(
                    LocationTypes.State.UNKNOWN,
                    service.locations(LocationAdaptersTest.NPC, Cancellation.NONE).join().state());
            assertEquals(
                    LocationTypes.State.UNKNOWN,
                    service.pickups(783, Cancellation.NONE).join().state());
            assertThrows(
                    IllegalArgumentException.class, () -> service.pickups(0, Cancellation.NONE));
        }
        var one = input(LocationAdaptersTest.QUESTIE, new AtomicInteger());
        assertThrows(IllegalArgumentException.class, () -> new LocationsService(List.of(one, one)));
    }

    @Test
    void closeCancelsAcceptedWorkAndRejectsLaterQueriesWithoutClosingBorrowedInputs()
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var input =
                new LocationInput(
                        LocationAdaptersTest.QUESTIE,
                        new LocationInput.Reader() {
                            public LocationTypes.Locations locations(
                                    LocationTypes.Entity entity, Cancellation token)
                                    throws Exception {
                                entered.complete(null);
                                while (!release.isDone()) {
                                    token.check();
                                    Thread.sleep(5);
                                }
                                return LocationAdapters.unknown(entity, "Authored no locations");
                            }

                            public LocationTypes.Starters starters(long quest, Cancellation token) {
                                throw new AssertionError("Unexpected starter query");
                            }
                        });
        var service = new LocationsService(List.of(input));
        var answer = service.locations(LocationAdaptersTest.NPC, Cancellation.NONE);
        entered.get(5, TimeUnit.SECONDS);
        service.closeAsync().get(5, TimeUnit.SECONDS);
        release.complete(null);
        assertThrows(java.util.concurrent.CancellationException.class, answer::join);
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.locations(LocationAdaptersTest.NPC, Cancellation.NONE).join());
        service.closeAsync().join();
    }
}
