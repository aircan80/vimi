package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables.FakeTable;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ItemEffectTextTest {
    @Test
    void preservesPlainDescriptionAndDoesNotInventPercentage() {
        String source =
                "Rest for at least one minute to gain an experience bonus. Stacks up to 3 times.";
        var line = ItemEffectText.project("Use", 429961, "Unroll Bag", source);
        assertEquals(EffectsTypes.TextState.READY, line.state());
        assertEquals(source, line.text());
    }

    @Test
    void preservesUnresolvedExpressionsAndRejectsMalformedText() {
        var line =
                ItemEffectText.project("Equip", 7, "Buff", "Experience increased by $429959s0%.");
        assertEquals(EffectsTypes.TextState.UNRESOLVED_TOKENS, line.state());
        assertEquals("Experience increased by $429959s0%.", line.text());
        assertEquals(
                EffectsTypes.TextState.INVALID_TEXT,
                ItemEffectText.project("Use", 1, null, "\ud800").state());
        assertNull(ItemEffectText.project("Use", 1, null, "x".repeat(16385)).text());
    }

    private static FakeTable spell(TableTypes.State state, Map<Long, String> protectedIds) {
        var id =
                new TableSchema.SchemaColumn(
                        "ID",
                        TableSchema.ColumnKind.INT,
                        32,
                        false,
                        1,
                        null,
                        TableSchema.KeyStorage.NONINLINE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        WowLocale.EN_US,
                        null,
                        null);
        var description =
                new TableSchema.SchemaColumn(
                        "Description_lang",
                        TableSchema.ColumnKind.LOCSTRING,
                        null,
                        null,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        WowLocale.EN_US,
                        null,
                        null);
        return new FakeTable(
                new TableTypes.Entry(1140089, "Spell", state, true, null, null, null),
                List.of(
                        EffectsWorld.col("ID", true),
                        new Db2Types.Column("Description_lang", "string", false, null, null)),
                protectedIds.isEmpty()
                        ? List.of(EffectsWorld.row(7, "7", "Rest for one minute."))
                        : List.of(),
                new TableSchema(
                        List.of(id, description),
                        List.of(),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US,
                        null),
                protectedIds,
                true);
    }

    private static EffectsWorld world(FakeTable spell) {
        return new EffectsWorld()
                .with(
                        EffectsWorld.itemTable(List.of(EffectsWorld.item(2))),
                        EffectsWorld.bridgeTable(List.of(EffectsWorld.bridge(504, 7, 2))),
                        EffectsWorld.retailTable(List.of(EffectsWorld.effect(7, 7))),
                        EffectsWorld.nameTable(List.of(EffectsWorld.name(7, "Unroll Bag"))),
                        spell);
    }

    @Test
    void selectedItemReadsAvailableDescriptionFromPartialTable() {
        try (var handle = world(spell(TableTypes.State.PARTIAL, Map.of())).open()) {
            var result = handle.tooltip(2, EffectsWorld.NOW, Cancellation.NONE).join();
            assertEquals(EffectsTypes.PageState.OK, result.state());
            assertEquals(1, result.lines().size());
            assertEquals("Rest for one minute.", result.lines().getFirst().text());
            assertEquals(EffectsTypes.TextState.READY, result.lines().getFirst().state());
            assertEquals("Chance on hit", result.lines().getFirst().trigger());
        }
    }

    @Test
    void cancelledAndClosedTooltipRequestsAreRetired() {
        var handle = world(spell(TableTypes.State.PARTIAL, Map.of())).open();
        try {
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    RuntimeException.class,
                    () -> handle.tooltip(2, EffectsWorld.NOW, token).join());
        } finally {
            handle.close();
        }
        assertThrows(
                IllegalStateException.class,
                () -> handle.tooltip(2, EffectsWorld.NOW, Cancellation.NONE));
    }

    @Test
    void protectedSpellRetainsEffectAndNameWithExplicitOutcome() {
        try (var handle =
                world(spell(TableTypes.State.PARTIAL, Map.of(7L, EffectsWorld.KEY))).open()) {
            var line =
                    handle.tooltip(2, EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .lines()
                            .getFirst();
            assertEquals(EffectsTypes.TextState.PROTECTED, line.state());
            assertEquals("Unroll Bag", line.name());
            assertNull(line.text());
        }
    }
}
