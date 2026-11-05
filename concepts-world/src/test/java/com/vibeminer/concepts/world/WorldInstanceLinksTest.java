package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WorldInstanceLinksTest {
    @Test
    void onlyDungeonAndRaidRowsQualifyAndKeepTheirRecordedArea() {
        assertNull(WorldInstanceLinks.qualify(0, "Continent", 0L, 0L, -1L, 0.0, 0.0));
        assertNull(WorldInstanceLinks.qualify(30, "Battleground", 3L, 0L, -1L, 0.0, 0.0));
        assertNull(WorldInstanceLinks.qualify(31, "Unreadable", null, 0L, 1L, 5.0, 5.0));
        var raid = WorldInstanceLinks.qualify(249, "Lair", 2L, 2159L, 1L, -4753.3, -3752.4);
        assertEquals(WorldInstanceLinks.Kind.RAID, raid.kind());
        assertEquals(2159L, raid.areaId());
        assertEquals(new WorldInstanceLinks.Entrance(1, -4753.3, -3752.4), raid.entrance());
        assertNull(raid.entranceIssue());
        assertEquals("Lair · raid", raid.toString());
        var dungeon = WorldInstanceLinks.qualify(36, " ", 1L, 0L, 0L, -11207.8, 1681.2);
        assertEquals(WorldInstanceLinks.Kind.DUNGEON, dungeon.kind());
        assertEquals("Map #36", dungeon.name());
        assertNull(dungeon.areaId(), "AreaTableID 0 is no recorded area");
    }

    @Test
    void absentOrUnusableEntrancesSayWhyInsteadOfGuessing() {
        String[][] cases = {
            {null, "1", "1", "unreadable"},
            {"-1", "1", "1", "unset"},
            {"249", "1", "1", "instance itself"},
            {"1", null, "1", "unreadable"},
            {"1", "NaN", "1", "not a number"},
            {"1", "0", "0", "0, 0"},
            {"1", "17067", "1", "outside the map grid"},
            {"1", "1", "-17067", "outside the map grid"}
        };
        for (var c : cases) {
            var link =
                    WorldInstanceLinks.qualify(
                            249,
                            "Lair",
                            2L,
                            2159L,
                            c[0] == null ? null : Long.valueOf(c[0]),
                            c[1] == null ? null : Double.valueOf(c[1]),
                            c[2] == null ? null : Double.valueOf(c[2]));
            assertNull(link.entrance(), c[3]);
            assertTrue(link.entranceIssue().contains(c[3]), link.entranceIssue());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldInstanceLinks.Link(
                                5, "x", WorldInstanceLinks.Kind.RAID, null, null, null));
    }

    @Test
    void entrancesOnAMapAreSortedNearestFirst() {
        var far = WorldInstanceLinks.qualify(10, "Far", 1L, 0L, 1L, 1000.0, 0.0);
        var near = WorldInstanceLinks.qualify(11, "Near", 2L, 0L, 1L, 10.0, 0.0);
        var other = WorldInstanceLinks.qualify(12, "Elsewhere", 1L, 0L, 0L, 5.0, 0.0);
        var none = WorldInstanceLinks.qualify(13, "None", 1L, 0L, -1L, 0.0, 0.0);
        var links = new WorldInstanceLinks(List.of(far, near, other, none), List.of());
        assertEquals(List.of(near, far), links.entrancesOn(1, 0, 0));
        assertEquals(List.of(other), links.entrancesOn(0, 0, 0));
        assertEquals(10, WorldInstanceLinks.distance(near, 0, 0), 1e-9);
        assertEquals(none, links.link(13).orElseThrow());
        assertTrue(links.link(99).isEmpty());
        assertEquals(List.of("why"), WorldInstanceLinks.unavailable("why").issues());
    }

    @Test
    void worldServiceReadsMapRowsWithTheVerifiedCorpseArray() {
        try (var service = new WorldService(tables(true), files())) {
            var links = service.instanceLinks(Cancellation.NONE).join();
            assertEquals(2, links.links().size(), "the continent row is not an instance");
            var lair = links.link(249).orElseThrow();
            assertEquals(
                    new WorldInstanceLinks.Entrance(1, -4753.3125, -3752.4211), lair.entrance());
            assertEquals(2159L, lair.areaId());
            var chasm = links.link(389).orElseThrow();
            assertEquals(WorldInstanceLinks.Kind.DUNGEON, chasm.kind());
            assertTrue(chasm.entranceIssue().contains("unset"));
            assertTrue(service.instanceLinks(Cancellation.NONE).join() == links, "read once");
        }
        try (var service = new WorldService(tables(false), files())) {
            var lair = service.instanceLinks(Cancellation.NONE).join().link(249).orElseThrow();
            assertNull(lair.entrance(), "an unverified Corpse column is not read");
            assertTrue(lair.entranceIssue().contains("unreadable"));
        }
    }

    private static final List<String> NAMES =
            List.of(
                    "ID",
                    "MapName_lang",
                    "Corpse[0]",
                    "Corpse[1]",
                    "InstanceType",
                    "AreaTableID",
                    "CorpseMapID");

    private static BuildTables tables(boolean corpseVerified) {
        var columns = new ArrayList<Db2Types.Column>();
        for (var n : NAMES) {
            columns.add(new Db2Types.Column(n, "integer", n.equals("ID"), null, null));
        }
        var fields = new ArrayList<TableSchema.SchemaColumn>();
        for (var n :
                List.of(
                        "ID",
                        "MapName_lang",
                        "Corpse",
                        "InstanceType",
                        "AreaTableID",
                        "CorpseMapID")) {
            var kind =
                    n.equals("MapName_lang")
                            ? TableSchema.ColumnKind.LOCSTRING
                            : n.equals("Corpse")
                                    ? TableSchema.ColumnKind.FLOAT
                                    : TableSchema.ColumnKind.INT;
            boolean integer = kind == TableSchema.ColumnKind.INT;
            fields.add(
                    new TableSchema.SchemaColumn(
                            n,
                            kind,
                            integer ? 32 : null,
                            integer ? false : null,
                            n.equals("Corpse") ? 2 : 1,
                            null,
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            !n.equals("Corpse") || corpseVerified,
                            WowLocale.EN_US,
                            null,
                            null));
        }
        var schema =
                new TableSchema(
                        fields,
                        List.of("L"),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.EN_US,
                        null);
        var entry =
                new TableTypes.Entry(
                        1,
                        "Map",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(3, 3, 3, 3, "WDC5", "L"),
                        null);
        var rows =
                List.of(
                        row(1, "Kalimdor", "0", "0", "0", "0", "-1"),
                        row(249, "Lair", "-4753.3125", "-3752.4211", "2", "2159", "1"),
                        row(389, "Chasm", "1816.7559", "-4423.3716", "1", "2437", "-1"));
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("schema")) {
                                return CompletableFuture.completedFuture(
                                        new TableTypes.SchemaResult(entry, columns, schema));
                            }
                            if (method.getName().equals("page")) {
                                return CompletableFuture.completedFuture(
                                        new TableTypes.Page(
                                                entry,
                                                TableTypes.PageState.OK,
                                                rows,
                                                null,
                                                "test",
                                                rows.size(),
                                                null,
                                                null));
                            }
                            throw new AssertionError("Unexpected table call " + method.getName());
                        });
    }

    private static TableTypes.Row row(long id, String... values) {
        var all = new ArrayList<String>();
        all.add(Long.toString(id));
        all.addAll(List.of(values));
        return new TableTypes.Row(id, new Db2Types.Row(id, false, null, all, List.of()));
    }

    private static BuildFiles files() {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) -> {
                            throw new AssertionError("Instance links read no files");
                        });
    }
}
