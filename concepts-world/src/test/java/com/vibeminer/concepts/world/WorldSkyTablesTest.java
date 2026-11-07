package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorldSkyTablesTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    /** Name, array length, float or integer. */
    private record Field(String name, int length, boolean decimal) {}

    private static final List<Field> LIGHT =
            List.of(
                    new Field("GameCoords", 3, true),
                    new Field("GameFalloffStart", 1, true),
                    new Field("GameFalloffEnd", 1, true),
                    new Field("ContinentID", 1, false),
                    new Field("LightParamsID", 8, false));
    private static final List<Field> PARAMS = List.of(new Field("Flags", 1, false));
    private static final List<Field> DATA =
            List.of(
                    new Field("LightParamID", 1, false),
                    new Field("Time", 1, false),
                    new Field("SkyTopColor", 1, false),
                    new Field("SkyMiddleColor", 1, false),
                    new Field("SkyBand1Color", 1, false),
                    new Field("SkyBand2Color", 1, false),
                    new Field("SkySmogColor", 1, false),
                    new Field("SkyFogColor", 1, false),
                    new Field("DirectColor", 1, false),
                    new Field("AmbientColor", 1, false),
                    new Field("FogEnd", 1, true),
                    new Field("FogScaler", 1, true));
    private static final List<Field> MAP = List.of(new Field("TimeOfDayOverride", 1, false));

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id,
            String name,
            List<Field> fields,
            List<Db2Types.Row> rows,
            TableTypes.State state,
            String layout) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            for (int slot = 0; slot < field.length(); slot++) {
                columns.add(
                        new Db2Types.Column(
                                field.length() == 1
                                        ? field.name()
                                        : field.name() + "[" + slot + "]",
                                field.decimal() ? "float" : "integer",
                                false,
                                null,
                                null));
            }
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.decimal()
                                    ? TableSchema.ColumnKind.FLOAT
                                    : TableSchema.ColumnKind.INT,
                            field.decimal() ? null : 32,
                            field.decimal() ? null : false,
                            field.length(),
                            null,
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            true,
                            WowLocale.parse("enUS"),
                            null,
                            null));
        }
        int declared = state == TableTypes.State.PARTIAL ? rows.size() + 1 : rows.size();
        var entry =
                new TableTypes.Entry(
                        id,
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(
                                declared,
                                rows.size(),
                                declared - rows.size(),
                                0,
                                "fixture",
                                layout),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of(layout),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        return table(
                id, name, fields, rows, TableTypes.State.READY, WorldSkyTables.LAYOUTS.get(name));
    }

    private static Db2Types.Row light(long id, String... cells) {
        return row(id, cells);
    }

    private static List<MemoryTables.FakeTable> tables(TableTypes.State lightState) {
        return new ArrayList<>(
                List.of(
                        table(
                                1,
                                "Light",
                                LIGHT,
                                List.of(
                                        light(
                                                1, "0", "0", "0", "0", "0", "0", "100", "0", "0",
                                                "0", "0", "0", "0", "0"),
                                        light(
                                                2, "1000", "0", "0", "100", "300", "0", "200", "7",
                                                "0", "0", "0", "0", "0", "0"),
                                        light(
                                                3, "0", "0", "0", "0", "0", "1", "999", "0", "0",
                                                "0", "0", "0", "0", "0"),
                                        light(
                                                4, "x", "0", "0", "0", "0", "1", "100", "0", "0",
                                                "0", "0", "0", "0", "0")),
                                lightState,
                                WorldSkyTables.LAYOUTS.get("Light")),
                        table(2, "LightParams", PARAMS, List.of(row(100, "0"), row(200, "0"))),
                        table(
                                3,
                                "LightData",
                                DATA,
                                List.of(
                                        row(
                                                10, "100", "0", "0", "0", "0", "0", "0", "0", "0",
                                                "0", "0", "0.25"),
                                        row(
                                                11,
                                                "200",
                                                "0",
                                                "13404415",
                                                "0",
                                                "0",
                                                "0",
                                                "0",
                                                "8421504",
                                                "16769216",
                                                "4210784",
                                                "1500",
                                                "0.5"),
                                        row(
                                                12, "200", "2880", "0", "0", "0", "0", "0", "0",
                                                "0", "0", "0", "0"),
                                        row(
                                                13, "555", "0", "0", "0", "0", "0", "0", "0", "0",
                                                "0", "0", "0"))),
                        table(4, "Map", MAP, List.of(row(0, "-1"), row(1, "900")))));
    }

    private static BuildTables open(List<MemoryTables.FakeTable> tables) {
        return new MemoryTables()
                .add(BUILD, "enUS", tables)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void readsVerifiedFieldsAndCountsWhatItSkips() {
        try (var tables = open(tables(TableTypes.State.PARTIAL))) {
            var sky = WorldSky.load(tables, Cancellation.NONE);
            var issues = String.join("\n", sky.sourceIssues());
            assertTrue(issues.contains("Light is partial: 4 of 5 rows are readable."), issues);
            assertTrue(issues.contains("1 Light rows are unreadable"), issues);
            assertTrue(issues.contains("1 Light rows name a LightParams row that is absent"));
            assertTrue(issues.contains("1 LightData rows are unreadable"), issues);
            assertTrue(issues.contains("1 LightData rows name a LightParams row that is absent"));

            var near = sky.at(0, 1000, 0, 0, 600);
            assertEquals(WorldSky.State.PARTIAL, near.state());
            assertEquals(2, near.contributions().getFirst().lightId());
            assertEquals(0xCC88FF, near.sample().skyTop());
            assertEquals(0x808080, near.sample().skyFog());
            assertEquals(new WorldSky.Lighting(0xFFE0C0, 0x404060), near.sample().lighting());
            assertEquals(1, near.sample().lightShare(), 0);
            assertEquals(1500, near.sample().fogEnd(), 0);
            assertEquals(List.of(11L), near.contributions().getFirst().dataRows());

            var fixed = sky.at(1, 0, 0, 0, 0);
            assertTrue(fixed.timeFixedByMap());
            assertEquals(900, fixed.time());
        }
    }

    @Test
    void unreviewedLayoutsAndMissingFieldsStopTheLoad() {
        var changed = tables(TableTypes.State.READY);
        changed.set(2, table(3, "LightData", DATA, List.of(), TableTypes.State.READY, "00000000"));
        try (var tables = open(changed)) {
            var failure =
                    assertThrows(
                            WorldSkyTables.Unavailable.class,
                            () -> WorldSky.load(tables, Cancellation.NONE));
            assertTrue(failure.getMessage().contains("LightData has a layout"));
        }
        var missing = tables(TableTypes.State.READY);
        missing.set(2, table(3, "LightData", DATA.subList(0, DATA.size() - 1), List.of()));
        try (var tables = open(missing)) {
            var failure =
                    assertThrows(
                            WorldSkyTables.Unavailable.class,
                            () -> WorldSky.load(tables, Cancellation.NONE));
            assertTrue(failure.getMessage().contains("FogScaler"), failure.getMessage());
        }
    }

    /** The authored tables with one LightData field (and its cells) removed. */
    private static List<MemoryTables.FakeTable> withoutDataField(String field) {
        int at = DATA.stream().map(Field::name).toList().indexOf(field);
        var fields = new ArrayList<>(DATA);
        fields.remove(at);
        var rows = new ArrayList<Db2Types.Row>();
        for (var row : tables(TableTypes.State.READY).get(2).rows()) {
            var values = new ArrayList<>(row.values());
            values.remove(at);
            rows.add(row(row.id(), values.toArray(String[]::new)));
        }
        var changed = tables(TableTypes.State.READY);
        changed.set(2, table(3, "LightData", fields, rows));
        return changed;
    }

    @Test
    void aSchemaWithoutLightFieldsKeepsSkyAndFogWithoutLight() {
        try (var tables = open(withoutDataField("AmbientColor"))) {
            var sky = WorldSky.load(tables, Cancellation.NONE);
            assertTrue(
                    sky.sourceIssues().stream()
                            .anyMatch(
                                    i ->
                                            i.startsWith("Direct and ambient light are unavailable")
                                                    && i.contains("AmbientColor")),
                    String.join("\n", sky.sourceIssues()));

            var near = sky.at(0, 1000, 0, 0, 600);
            assertEquals(WorldSky.State.PARTIAL, near.state());
            assertEquals(List.of(11L), near.contributions().getFirst().dataRows());
            assertEquals(0xCC88FF, near.sample().skyTop());
            assertEquals(0x808080, near.sample().skyFog());
            assertEquals(1500, near.sample().fogEnd(), 0);
            assertTrue(near.sample().hasFog());
            assertFalse(near.sample().hasLight());
            assertNull(near.sample().lighting());
        }
    }

    @Test
    void aRowWithABadLightValueKeepsItsSkyAndFog() {
        var changed = tables(TableTypes.State.READY);
        var rows = new ArrayList<Db2Types.Row>();
        for (var row : changed.get(2).rows()) {
            var values = new ArrayList<>(row.values());
            if (row.id() == 11) {
                values.set(8, "16777216"); // DirectColor past 0xFFFFFF
            }
            rows.add(row(row.id(), values.toArray(String[]::new)));
        }
        changed.set(2, table(3, "LightData", DATA, rows));
        try (var tables = open(changed)) {
            var sky = WorldSky.load(tables, Cancellation.NONE);
            var issues = String.join("\n", sky.sourceIssues());
            assertTrue(
                    issues.contains("1 LightData rows have unreadable direct or ambient light"),
                    issues);
            // Only the authored bad time (row 12) is skipped; row 11 is kept.
            assertTrue(issues.contains("1 LightData rows are unreadable and skipped."), issues);

            var near = sky.at(0, 1000, 0, 0, 600);
            assertEquals(List.of(11L), near.contributions().getFirst().dataRows());
            assertEquals(0xCC88FF, near.sample().skyTop());
            assertEquals(1500, near.sample().fogEnd(), 0);
            assertFalse(near.sample().hasLight());
            // Another key's light is untouched.
            assertTrue(sky.at(0, 0, 0, 0, 600).sample().hasLight());
        }
    }

    @Test
    void aMissingMapTableOnlyLosesFixedTimes() {
        var withoutMap = tables(TableTypes.State.READY);
        withoutMap.removeLast();
        try (var tables = open(withoutMap)) {
            var sky = WorldSky.load(tables, Cancellation.NONE);
            assertTrue(sky.sourceIssues().stream().anyMatch(i -> i.startsWith("Map fixed times")));
            assertEquals(0, sky.at(1, 0, 0, 0, 0).time());
        }
    }

    @Test
    void aWorkspaceSharesOneSkyLoadAndRetriesAFailedOne() throws Exception {
        var good = open(tables(TableTypes.State.READY));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        // The first table read fails; every later one is the authored tables'.
        var flaky =
                (BuildTables)
                        java.lang.reflect.Proxy.newProxyInstance(
                                WorldSkyTablesTest.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) ->
                                        method.getName().equals("schema")
                                                        && calls.getAndIncrement() == 0
                                                ? java.util.concurrent.CompletableFuture
                                                        .failedFuture(
                                                                new IllegalStateException("busy"))
                                                : method.invoke(good, args));
        var world = new WorldService(flaky, null);
        try {
            var failed = world.skyIndex(new Cancellation());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> failed.get(10, java.util.concurrent.TimeUnit.SECONDS));
            // The failure is not cached, and a caller that is already cancelled cannot fail the
            // shared load: it belongs to the workspace.
            var caller = new Cancellation();
            caller.cancel();
            var loaded = world.skyIndex(caller).get(10, java.util.concurrent.TimeUnit.SECONDS);
            // The authored tables are partial by design; the load itself succeeded.
            assertTrue(loaded.at(0, 0, 0, 0, 0).state() != WorldSky.State.UNAVAILABLE);
            assertSame(
                    loaded,
                    world.skyIndex(new Cancellation())
                            .get(10, java.util.concurrent.TimeUnit.SECONDS),
                    "a successful load is shared");
        } finally {
            world.closeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS);
            good.closeAsync().join();
        }
    }
}
