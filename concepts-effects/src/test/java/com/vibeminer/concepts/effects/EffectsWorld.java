package com.vibeminer.concepts.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.MemoryTables.FakeTable;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Authored rows paired with the actual pinned bundled WoWDBDefs reviewed layouts. */
final class EffectsWorld {
    static final BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
    static final String LOCALE = "enUS",
            KEY = "0123456789ABCDEF",
            RETAIL_VERSION = "12.1.5.70448",
            CLASSIC_VERSION = "1.15.9.70003";
    static final TableTypes.Priority NOW = TableTypes.Priority.INTERACTIVE;
    final MemoryTables tables = new MemoryTables();
    final ItemEffectsService service;

    EffectsWorld() {
        this(new EffectsTypes.Options());
    }

    EffectsWorld(EffectsTypes.Options options) {
        service = new ItemEffectsService(tables, options);
    }

    EffectsWorld with(FakeTable... values) {
        tables.add(BUILD, LOCALE, List.of(values));
        return this;
    }

    BuildItemEffects open() {
        return service.forBuild(BUILD, LOCALE, Cancellation.NONE).join().effects();
    }

    static TableSchema schema(String id, String version, String layout) {
        var selected =
                BundledDefinitions.open(Cancellation.NONE)
                        .join()
                        .require(id)
                        .document()
                        .selectFile(version, layout, Cancellation.NONE);
        if (selected.blocks().size() != 1) {
            throw new AssertionError("Expected one bundled block: " + id + " " + selected);
        }
        return TableSchema.fromBlock(
                selected.blocks().getFirst(),
                selected.match().equals("layout") ? SchemaMatch.LAYOUT : SchemaMatch.BUILD,
                WowLocale.EN_US);
    }

    static Db2Types.Column col(String name, boolean id) {
        return new Db2Types.Column(name, "integer", id, null, null);
    }

    static List<Db2Types.Column> columns(ItemEffectProfiles.Profile profile, List<Integer> raw) {
        List<Db2Types.Column> out = new ArrayList<>();
        for (int i = 0; i < profile.columns().size(); i++) {
            out.add(
                    new Db2Types.Column(
                            profile.columns().get(i),
                            profile.isName() && i == 1 ? "string" : "integer",
                            i == 0,
                            raw.contains(i)
                                    ? new Db2Types.Interpretation(
                                            "raw-physical-integer-v1",
                                            32,
                                            false,
                                            16,
                                            i != 3,
                                            "Physical raw evidence.")
                                    : null,
                            null));
        }
        return List.copyOf(out);
    }

    static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), null);
    }

    static Db2Types.Row item(long id) {
        return row(id, Long.toString(id), "2");
    }

    static Db2Types.Row bridge(long id, long effect, long item) {
        return row(id, Long.toString(id), Long.toString(effect), Long.toString(item));
    }

    static Db2Types.Row effect(long id, long spell) {
        return effect(id, spell, null, null);
    }

    static Db2Types.Row effect(
            long id, long spell, List<String> inputs, List<Db2Types.TextIssue> issues) {
        List<String> values = new ArrayList<>();
        values.add(Long.toString(id));
        values.addAll(
                inputs == null
                        ? List.of("1", "2", "3", "4", "5", "6", Long.toString(spell), "8", "9")
                        : inputs);
        return new Db2Types.Row(id, false, null, values, issues);
    }

    static Db2Types.Row classic(long id, long parent, long spell) {
        List<String> values = new ArrayList<>(effect(id, spell).values());
        values.add(Long.toString(parent));
        return new Db2Types.Row(id, false, null, values, null);
    }

    static Db2Types.Row name(long id, String text) {
        return row(id, Long.toString(id), text);
    }

    static FakeTable itemTable(List<Db2Types.Row> rows) {
        return itemTable(rows, TableTypes.State.READY, Map.of());
    }

    static FakeTable itemTable(
            List<Db2Types.Row> rows, TableTypes.State state, Map<Long, String> protectedIds) {
        return new FakeTable(
                new TableTypes.Entry(1001, "Item", state, true, null, null, null),
                List.of(col("ID", true), col("ClassID", false)),
                rows,
                null,
                protectedIds,
                true);
    }

    static FakeTable table(
            ItemEffectProfiles.Profile profile,
            List<Db2Types.Row> rows,
            TableTypes.State state,
            TableSchema schema,
            List<Db2Types.Column> columns,
            Map<Long, String> protectedIds,
            TableTypes.Reason reason) {
        var counts =
                new TableTypes.Counts(
                        rows.size() + (state == TableTypes.State.PARTIAL ? 1 : 0),
                        rows.size(),
                        state == TableTypes.State.PARTIAL ? 1 : 0,
                        state == TableTypes.State.PARTIAL ? 1 : 0,
                        "WDC5",
                        profile.layout());
        var entry =
                new TableTypes.Entry(
                        profile.fileDataId(), profile.table(), state, true, reason, counts, null);
        return new FakeTable(entry, columns, rows, schema, protectedIds, true);
    }

    static FakeTable bridgeTable(List<Db2Types.Row> rows) {
        return table(
                ItemEffectProfiles.ITEM_X_ITEM_EFFECT,
                rows,
                TableTypes.State.READY,
                schema("00cb674f", RETAIL_VERSION, "96F083AD"),
                columns(ItemEffectProfiles.ITEM_X_ITEM_EFFECT, List.of()),
                Map.of(),
                null);
    }

    static FakeTable retailTable(List<Db2Types.Row> rows) {
        return retailTable(rows, TableTypes.State.READY, List.of(), Map.of());
    }

    static FakeTable retailTable(
            List<Db2Types.Row> rows,
            TableTypes.State state,
            List<Integer> raw,
            Map<Long, String> protectedIds) {
        return table(
                ItemEffectProfiles.RETAIL_ITEM_EFFECT,
                rows,
                state,
                schema("4002a5b1", RETAIL_VERSION, "4CA77678"),
                columns(ItemEffectProfiles.RETAIL_ITEM_EFFECT, raw),
                protectedIds,
                null);
    }

    static FakeTable classicTable(List<Db2Types.Row> rows) {
        return table(
                ItemEffectProfiles.CLASSIC_ITEM_EFFECT,
                rows,
                TableTypes.State.READY,
                schema("4002a5b1", CLASSIC_VERSION, "1BF9CF3A"),
                columns(ItemEffectProfiles.CLASSIC_ITEM_EFFECT, List.of()),
                Map.of(),
                null);
    }

    static FakeTable nameTable(List<Db2Types.Row> rows) {
        return nameTable(rows, TableTypes.State.READY, Map.of());
    }

    static FakeTable nameTable(
            List<Db2Types.Row> rows, TableTypes.State state, Map<Long, String> protectedIds) {
        return table(
                ItemEffectProfiles.SPELL_NAME,
                rows,
                state,
                schema("46c66698", RETAIL_VERSION, "782EE721"),
                columns(ItemEffectProfiles.SPELL_NAME, List.of()),
                protectedIds,
                null);
    }

    static List<FakeTable> retail() {
        Db2Types.Row copy = new Db2Types.Row(503, true, 500L, List.of("503", "0", "1"), null);
        return List.of(
                itemTable(List.of(item(0), item(1), item(2), item(2147483648L))),
                bridgeTable(
                        List.of(
                                bridge(500, 0, 1),
                                bridge(501, 0, 1),
                                bridge(502, -1, 1),
                                bridge(504, 7, 2),
                                copy)),
                retailTable(List.of(effect(0, 0), effect(7, 7))),
                nameTable(List.of(name(0, " "), name(7, "Seven"))));
    }

    static List<EffectsTypes.Entry> all(BuildItemEffects effects, long item, int limit) {
        List<EffectsTypes.Entry> out = new ArrayList<>();
        String after = null;
        for (int guard = 0; guard < 10000; guard++) {
            var page =
                    effects.get(item, new EffectsTypes.Query(limit, after), NOW, Cancellation.NONE)
                            .join();
            if (page.state() != EffectsTypes.PageState.OK) {
                throw new AssertionError(page);
            }
            out.addAll(page.entries());
            after = page.next();
            if (after == null) {
                return out;
            }
        }
        throw new AssertionError("Cursor never ended");
    }
}
