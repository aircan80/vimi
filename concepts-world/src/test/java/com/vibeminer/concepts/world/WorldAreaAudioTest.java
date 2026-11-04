package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Authored relationship rows only; no game table, sound or file is read. */
final class WorldAreaAudioTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, int length, String target, boolean verified) {}

    private static Field reference(String name, String target) {
        return new Field(name, 1, target, true);
    }

    private static Field array(String name, String target) {
        return new Field(name, 2, target, true);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            for (int i = 0; i < field.length(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.length() == 1 ? field.name() : field.name() + "[" + i + "]",
                                "integer",
                                false,
                                null,
                                null));
            }
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            TableSchema.ColumnKind.INT,
                            32,
                            true,
                            field.length(),
                            field.target() == null
                                    ? null
                                    : new TableSchema.ForeignKey(field.target(), "ID"),
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            field.verified(),
                            WowLocale.parse("enUS"),
                            null,
                            null));
        }
        var entry =
                new TableTypes.Entry(
                        id,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(
                                rows.size(), rows.size(), 0, 0, "fixture", "0FC0F0D5"),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of("0FC0F0D5"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static List<MemoryTables.FakeTable> fixture(boolean verifiedMusic) {
        return List.of(
                table(
                        1,
                        "AreaTable",
                        List.of(
                                reference("ZoneMusic", "ZoneMusic"),
                                reference("AmbienceID", "SoundAmbience")),
                        // Zone 10 records both; subzone 11 overrides ambience only.
                        List.of(row(10, "5", "7"), row(11, "0", "8"), row(12, "0", "0"))),
                table(
                        2,
                        "ZoneMusic",
                        List.of(
                                new Field("Sounds", 2, "SoundEntries", verifiedMusic),
                                array("SilenceIntervalMin", null),
                                array("SilenceIntervalMax", null)),
                        List.of(row(5, "100", "0", "1000", "2000", "4000", "8000"))),
                table(
                        3,
                        "SoundAmbience",
                        List.of(array("AmbienceID", "SoundKit")),
                        List.of(row(7, "200", "201"), row(8, "300", "301"))),
                table(
                        4,
                        "SoundKitEntry",
                        List.of(
                                reference("SoundKitID", "SoundKit"),
                                reference("FileDataID", "FileData")),
                        List.of(
                                row(1, "100", "9001"),
                                row(2, "100", "9002"),
                                row(3, "300", "9301"),
                                row(4, "201", "0"))));
    }

    private static BuildTables open(List<MemoryTables.FakeTable> fixture) {
        return new MemoryTables()
                .add(BUILD, "enUS", fixture)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static WorldCurrentArea.Result area(long id, WorldCurrentArea.Index index) {
        return WorldCurrentArea.resolve(0, id, index);
    }

    private static final WorldCurrentArea.Index INDEX =
            WorldCurrentArea.Index.of(
                    List.of(
                            new WorldService.Area(10, "Recorded zone", 0, 0),
                            new WorldService.Area(11, "Recorded subzone", 10, 0),
                            new WorldService.Area(12, "Silent zone", 0, 0)));

    @Test
    void subzoneRelationshipsOverrideTheirZoneIndependentlyForMusicAndAmbience() {
        var tables = open(fixture(true));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(11, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            var music = plan.music().track();
            assertEquals(WorldAreaAudio.State.RECORDED, plan.music().state());
            assertEquals(10, music.areaId(), "music comes from the parent zone");
            assertEquals(5, music.relationId());
            assertEquals(100, music.soundKitId());
            assertEquals(List.of(9001L, 9002L), music.fileDataIds());
            assertEquals(1000, music.silenceMinMillis());
            assertEquals(4000, music.silenceMaxMillis());
            var ambience = plan.ambience().track();
            assertEquals(11, ambience.areaId(), "ambience comes from the subzone");
            assertEquals(300, ambience.soundKitId());
            assertEquals(List.of(9301L), ambience.fileDataIds());
            assertTrue(plan.issues().contains(WorldAreaAudio.LIMITS));
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void missingRelationshipsAreReportedHonestlyPerVariant() {
        var tables = open(fixture(true));
        try {
            var night =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(10, INDEX),
                            WorldAreaAudio.Variant.NIGHT,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.NONE_RECORDED, night.music().state());
            assertTrue(night.music().message().contains("night"));
            // Kit 201 only records a zero FileDataID entry.
            assertEquals(WorldAreaAudio.State.UNAVAILABLE, night.ambience().state());
            assertTrue(night.ambience().message().contains("no readable file"));
            var silent =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(12, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.NONE_RECORDED, silent.music().state());
            assertEquals(WorldAreaAudio.State.NONE_RECORDED, silent.ambience().state());
            var unknown =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(99, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.UNAVAILABLE, unknown.music().state());
            assertNull(unknown.ambience().track());
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void unverifiedRelationshipShapesAreUnavailableWithoutBlockingOtherAudio() {
        var tables = open(fixture(false));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(11, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.UNAVAILABLE, plan.music().state());
            assertTrue(plan.music().message().contains("unreviewed"));
            assertEquals(WorldAreaAudio.State.RECORDED, plan.ambience().state());
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void storageFailuresShowNoLocalPaths() {
        var files =
                (com.vibeminer.files.BuildFiles)
                        java.lang.reflect.Proxy.newProxyInstance(
                                WorldAreaAudioTest.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.BuildFiles.class},
                                (proxy, method, args) -> {
                                    throw new java.io.UncheckedIOException(
                                            new java.io.IOException(
                                                    "/home/someone/.cache/vimi/files/index.db:"
                                                            + " locked"));
                                });
        var file = WorldAreaAudio.read(files, 9001, Cancellation.NONE);
        assertEquals(WorldAreaAudio.FileState.UNAVAILABLE, file.state());
        assertEquals("Audio file #9001: source unavailable", file.issue());
    }

    @Test
    void absentBuildFilesAreUnavailableRatherThanSilent() {
        var file = WorldAreaAudio.read(null, 9001, Cancellation.NONE);
        assertEquals(WorldAreaAudio.FileState.UNAVAILABLE, file.state());
        assertNull(file.bytes());
    }
}
