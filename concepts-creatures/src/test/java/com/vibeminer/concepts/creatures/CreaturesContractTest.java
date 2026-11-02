package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPageState;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class CreaturesContractTest {
    static final TableTypes.Priority PRIORITY = TableTypes.Priority.INTERACTIVE;

    static Stream<BuildCreatures> implementations() {
        var real =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                CreatureWorld.creature(10, "Hogger"),
                                                CreatureWorld.creature(20, "  "),
                                                CreatureWorld.creature(30, ""),
                                                CreatureWorld.creature(50, "hog rider"))))
                        .open();
        var fake =
                new MemoryCreatures()
                        .add(
                                CreatureWorld.BUILD,
                                "enUS",
                                List.of(
                                        new MemoryCreatures.FakeCreature(
                                                10,
                                                new CreatureName(
                                                        CreatureNameState.VALID, "Hogger", null)),
                                        new MemoryCreatures.FakeCreature(
                                                20,
                                                new CreatureName(
                                                        CreatureNameState.VALID, "  ", null)),
                                        new MemoryCreatures.FakeCreature(
                                                30,
                                                new CreatureName(
                                                        CreatureNameState.EMPTY, "", null)),
                                        new MemoryCreatures.FakeCreature(
                                                50,
                                                new CreatureName(
                                                        CreatureNameState.VALID,
                                                        "hog rider",
                                                        null))))
                        .forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .creatures();
        return Stream.of(real, fake);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void literalMatchingAndEmptyNames(BuildCreatures handle) {
        try (handle) {
            var all = handle.list(new CreatureQuery(), PRIORITY, Cancellation.NONE).join();
            assertEquals(
                    List.of(10L, 20L, 30L, 50L), all.entries().stream().map(c -> c.id()).toList());
            assertEquals(CreatureNameState.EMPTY, all.entries().get(2).name().state());
            assertEquals("  ", all.entries().get(1).name().value());
            assertEquals(
                    List.of(10L, 50L),
                    handle
                            .list(new CreatureQuery("HOG"), PRIORITY, Cancellation.NONE)
                            .join()
                            .entries()
                            .stream()
                            .map(c -> c.id())
                            .toList());
            assertEquals(
                    1,
                    handle.list(new CreatureQuery("  "), PRIORITY, Cancellation.NONE)
                            .join()
                            .entries()
                            .size());
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void queryBoundCursorAndExactOutcomes(BuildCreatures handle) {
        try (handle) {
            var query = new CreatureQuery("", null, 2, null);
            var page = handle.list(query, PRIORITY, Cancellation.NONE).join();
            assertNotNull(page.next());
            assertEquals(
                    List.of(30L, 50L),
                    handle
                            .list(
                                    new CreatureQuery("", null, 2, page.next()),
                                    PRIORITY,
                                    Cancellation.NONE)
                            .join()
                            .entries()
                            .stream()
                            .map(c -> c.id())
                            .toList());
            assertEquals(
                    CreaturesPageState.INVALID_CURSOR,
                    handle.list(
                                    new CreatureQuery("", null, 3, page.next()),
                                    PRIORITY,
                                    Cancellation.NONE)
                            .join()
                            .state());
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    handle.list(new CreatureQuery("", 99L, 2, null), PRIORITY, Cancellation.NONE)
                            .join()
                            .exact());
            assertEquals(
                    CreatureResultState.ABSENT,
                    handle.get(99, PRIORITY, Cancellation.NONE).join().state());
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void canceledAndDisposedCalls(BuildCreatures handle) {
        var token = new Cancellation();
        token.cancel();
        assertThrows(CancellationException.class, () -> handle.get(10, PRIORITY, token).join());
        handle.close();
        assertThrows(
                IllegalStateException.class,
                () -> handle.get(10, PRIORITY, Cancellation.NONE).join());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void invalidQueriesReject(BuildCreatures handle) {
        try (handle) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            handle.list(
                                    new CreatureQuery("", null, 0, null),
                                    PRIORITY,
                                    Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            handle.list(
                                    new CreatureQuery("x", 10L, 2, null),
                                    PRIORITY,
                                    Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> handle.list(new CreatureQuery("\u0000"), PRIORITY, Cancellation.NONE));
        }
    }
}
