package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.effects.EffectsTypes.Effect;
import com.vibeminer.concepts.effects.EffectsTypes.EffectState;
import com.vibeminer.concepts.effects.EffectsTypes.Entry;
import com.vibeminer.concepts.effects.EffectsTypes.Name;
import com.vibeminer.concepts.effects.EffectsTypes.NameState;
import com.vibeminer.concepts.effects.EffectsTypes.PageState;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import com.vibeminer.concepts.effects.EffectsTypes.SourceState;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One public contract against independent effects fake and real service over independent layer5
 * tables.
 */
final class ItemEffectsContractTest {
    private static ItemEffects create(boolean fake) {
        if (!fake) {
            return new EffectsWorld()
                    .with(
                            EffectsWorld.retail()
                                    .toArray(
                                            com.vibeminer.formats.tables.MemoryTables.FakeTable[]
                                                    ::new))
                    .service;
        }
        MemoryItemEffects memory = new MemoryItemEffects();
        List<Entry> entries = new ArrayList<>();
        for (long id : new long[] {500, 501, 502, 503}) {
            long effect = id == 502 ? -1 : 0;
            var row =
                    new TableTypes.Row(
                            id,
                            new com.vibeminer.formats.db2.Db2Types.Row(
                                    id,
                                    id == 503,
                                    id == 503 ? 500L : null,
                                    List.of(Long.toString(id), Long.toString(effect), "1"),
                                    null));
            var observed =
                    effect < 0
                            ? new Effect(EffectState.EFFECT_KEY_OUT_OF_RANGE)
                            : new Effect(
                                    EffectState.OBSERVED_READY,
                                    new TableTypes.Row(0, EffectsWorld.effect(0, 0)),
                                    List.of(),
                                    "0",
                                    null,
                                    null);
            var name =
                    effect < 0
                            ? new Name(NameState.EFFECT_NOT_READY)
                            : new Name(
                                    NameState.READY,
                                    new TableTypes.Row(0, EffectsWorld.name(0, " ")),
                                    " ",
                                    null,
                                    null,
                                    null,
                                    null);
            entries.add(new Entry(row, "1", Long.toString(effect), observed, name));
        }
        Entry second =
                new Entry(
                        new TableTypes.Row(504, EffectsWorld.bridge(504, 7, 2)),
                        "2",
                        "7",
                        new Effect(
                                EffectState.OBSERVED_READY,
                                new TableTypes.Row(7, EffectsWorld.effect(7, 7)),
                                List.of(),
                                "7",
                                null,
                                null),
                        new Name(
                                NameState.READY,
                                new TableTypes.Row(7, EffectsWorld.name(7, "Seven")),
                                "Seven",
                                null,
                                null,
                                null,
                                null));
        return memory.add(
                EffectsWorld.BUILD,
                EffectsWorld.LOCALE,
                List.of(
                        new MemoryItemEffects.FakeItem(1, entries),
                        new MemoryItemEffects.FakeItem(2, List.of(second)),
                        new MemoryItemEffects.FakeItem(0, List.of()),
                        new MemoryItemEffects.FakeItem(2147483648L, List.of())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownBuildAndBadLocaleFollowOpenContract(boolean fake) {
        ItemEffects service = create(fake);
        var other =
                service.forBuild(
                                new BuildRef("wow", "c".repeat(32), "d".repeat(32), "us"),
                                "enUS",
                                Cancellation.NONE)
                        .join();
        assertEquals(FilesOpenState.NEEDS_NETWORK, other.state());
        assertNull(other.effects());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.forBuild(EffectsWorld.BUILD, "xxXX", Cancellation.NONE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyLinkCopyAndZeroKeyIsKeptInLinkOrder(boolean fake) {
        try (var handle =
                create(fake)
                        .forBuild(EffectsWorld.BUILD, EffectsWorld.LOCALE, Cancellation.NONE)
                        .join()
                        .effects()) {
            assertEquals(EffectsWorld.BUILD, handle.build());
            assertEquals("enUS", handle.locale());
            var all = EffectsWorld.all(handle, 1, 3);
            assertEquals(
                    List.of(500L, 501L, 502L, 503L),
                    all.stream().map(e -> e.link().row().id()).toList());
            assertEquals(
                    List.of("0", "0", "-1", "0"), all.stream().map(Entry::itemEffectId).toList());
            assertEquals(
                    List.of(
                            EffectState.OBSERVED_READY,
                            EffectState.OBSERVED_READY,
                            EffectState.EFFECT_KEY_OUT_OF_RANGE,
                            EffectState.OBSERVED_READY),
                    all.stream().map(e -> e.effect().state()).toList());
            assertEquals(" ", all.getFirst().name().text());
            assertNull(all.get(2).name().text());
            assertTrue(all.get(3).link().row().isCopy());
            var other = handle.get(2, new Query(), EffectsWorld.NOW, Cancellation.NONE).join();
            assertEquals("Seven", other.entries().getFirst().name().text());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyItemNeedsNoEffectOrNameAndMissingHighItemsReadNoLinks(boolean fake) {
        try (var handle =
                create(fake)
                        .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .effects()) {
            var empty = handle.get(0, new Query(), EffectsWorld.NOW, Cancellation.NONE).join();
            assertEquals(PageState.OK, empty.state());
            assertTrue(empty.entries().isEmpty());
            assertNull(empty.next());
            assertEquals(SourceState.READY, empty.bridge().state());
            assertEquals(SourceState.NOT_CHECKED, empty.effect().state());
            assertEquals(SourceState.NOT_CHECKED, empty.name().state());
            assertEquals(
                    PageState.MISSING_ITEM,
                    handle.get(9, new Query(), EffectsWorld.NOW, Cancellation.NONE).join().state());
            assertEquals(
                    PageState.ITEM_KEY_OUT_OF_RANGE,
                    handle.get(2147483648L, new Query(), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cursorIsBoundToItemAndLimitAndArgumentsAreValidated(boolean fake) {
        try (var handle =
                create(fake)
                        .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .effects()) {
            var first = handle.get(1, new Query(2), EffectsWorld.NOW, Cancellation.NONE).join();
            assertNotNull(first.next());
            assertEquals(
                    PageState.INVALID_CURSOR,
                    handle.get(1, new Query(3, first.next()), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
            assertEquals(
                    PageState.INVALID_CURSOR,
                    handle.get(2, new Query(2, first.next()), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
            for (int limit : new int[] {0, 17}) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handle.get(1, new Query(limit), EffectsWorld.NOW, Cancellation.NONE));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> handle.get(-1, new Query(), EffectsWorld.NOW, Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> handle.get(1, new Query(2, "bad="), EffectsWorld.NOW, Cancellation.NONE));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canceledCallThrowsAndClosedHandleRejectsLaterCalls(boolean fake) {
        var handle =
                create(fake)
                        .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .effects();
        Cancellation token = new Cancellation();
        token.cancel();
        assertThrows(
                RuntimeException.class,
                () -> handle.get(1, new Query(), EffectsWorld.NOW, token).join());
        handle.close();
        assertThrows(
                IllegalStateException.class,
                () -> handle.get(1, new Query(), EffectsWorld.NOW, Cancellation.NONE));
        handle.close();
    }
}
