package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Authored relationship rows only; no game table, sound or file is read. */
final class WorldAreaAudioTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(
            String name, int length, String target, boolean verified, boolean decimal) {
        Field(String name, int length, String target, boolean verified) {
            this(name, length, target, verified, false);
        }
    }

    private static Field decimal(String name) {
        return new Field(name, 1, null, true, true);
    }

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
        return table(id, name, fields, rows, Map.of());
    }

    private static MemoryTables.FakeTable table(
            long id,
            String name,
            List<Field> fields,
            List<Db2Types.Row> rows,
            Map<Long, String> protectedIds) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            for (int i = 0; i < field.length(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.length() == 1 ? field.name() : field.name() + "[" + i + "]",
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
                protectedIds,
                true);
    }

    private static List<MemoryTables.FakeTable> fixture(boolean verifiedMusic) {
        return fixture(verifiedMusic, true, true, "0.75");
    }

    private static List<MemoryTables.FakeTable> fixture(
            boolean verifiedMusic, boolean verifiedFrequency, boolean soundKits) {
        return fixture(verifiedMusic, verifiedFrequency, soundKits, "0.75");
    }

    /** {@code kit400Volume} is the recorded VolumeFloat text of kit 400. */
    private static List<MemoryTables.FakeTable> fixture(
            boolean verifiedMusic,
            boolean verifiedFrequency,
            boolean soundKits,
            String kit400Volume) {
        var tables = new ArrayList<MemoryTables.FakeTable>();
        tables.add(
                table(
                        1,
                        "AreaTable",
                        List.of(
                                reference("ZoneMusic", "ZoneMusic"),
                                reference("AmbienceID", "SoundAmbience")),
                        // Zone 10 records both; subzone 11 overrides ambience only. Subzone 15 is
                        // encrypted and subzone 16's row is truncated.
                        List.of(row(10, "5", "7"), row(11, "0", "8"), row(12, "0", "0"), row(16)),
                        Map.of(15L, "encrypted")));
        tables.add(
                table(
                        2,
                        "ZoneMusic",
                        List.of(
                                new Field("Sounds", 2, "SoundEntries", verifiedMusic),
                                array("SilenceIntervalMin", null),
                                array("SilenceIntervalMax", null)),
                        List.of(row(5, "100", "0", "1000", "2000", "4000", "8000"))));
        tables.add(
                table(
                        3,
                        "SoundAmbience",
                        List.of(array("AmbienceID", "SoundKit")),
                        List.of(row(7, "200", "201"), row(8, "300", "400"))));
        tables.add(
                table(
                        4,
                        "SoundKitEntry",
                        List.of(
                                reference("SoundKitID", "SoundKit"),
                                reference("FileDataID", "FileData"),
                                new Field("Frequency", 1, null, verifiedFrequency),
                                decimal("Volume")),
                        // File 9001 is recorded twice; its weights add up and its first volume
                        // stays. Kit 400 records an unreadable Frequency and Volume in one row.
                        List.of(
                                row(1, "100", "9001", "3", "0.5"),
                                row(2, "100", "9002", "1", "1"),
                                row(3, "300", "9301", "0", "2"),
                                row(4, "201", "0", "1", "1"),
                                row(5, "100", "9001", "2", "0.25"),
                                row(6, "200", "9201", "1", "1"),
                                row(7, "400", "9401", "2", "1"),
                                row(8, "400", "9402", "x", "NaN"))));
        if (soundKits) {
            tables.add(
                    table(
                            5,
                            "SoundKit",
                            List.of(decimal("VolumeFloat")),
                            // Kit 300 records more than full volume; kit 200 has no row.
                            List.of(row(100, "0.5"), row(300, "2.5"), row(400, kit400Volume))));
        }
        return tables;
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
                            new WorldService.Area(12, "Silent zone", 0, 0),
                            // No AreaTable rows record areas 13 and 14.
                            new WorldService.Area(13, "Unreadable subzone", 10, 0),
                            new WorldService.Area(14, "Unreadable zone", 0, 0),
                            new WorldService.Area(15, "Encrypted subzone", 10, 0),
                            new WorldService.Area(16, "Truncated subzone", 10, 0)));

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
    void tableFailuresShowNoLocalPaths() {
        var tables =
                (BuildTables)
                        java.lang.reflect.Proxy.newProxyInstance(
                                WorldAreaAudioTest.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) ->
                                        java.util.concurrent.CompletableFuture.failedFuture(
                                                new java.io.UncheckedIOException(
                                                        new java.io.IOException(
                                                                "/home/someone/.cache/vimi/"
                                                                        + "tables.db: locked"))));
        var plan =
                WorldAreaAudio.resolve(
                        tables, 0, area(11, INDEX), WorldAreaAudio.Variant.DAY, Cancellation.NONE);
        assertEquals(WorldAreaAudio.State.UNAVAILABLE, plan.music().state());
        assertEquals("Recorded zone music unavailable: source unavailable", plan.music().message());
        assertEquals(
                "Recorded ambience unavailable: source unavailable", plan.ambience().message());
    }

    @Test
    void storageFailuresShowNoLocalPaths() {
        var files =
                (com.vibeminer.files.BuildFiles)
                        java.lang.reflect.Proxy.newProxyInstance(
                                WorldAreaAudioTest.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.BuildFiles.class},
                                (proxy, method, args) ->
                                        // A real source fails its future; join() wraps it.
                                        java.util.concurrent.CompletableFuture.failedFuture(
                                                new java.io.UncheckedIOException(
                                                        new java.io.IOException(
                                                                "/home/someone/.cache/vimi/"
                                                                        + "files/index.db:"
                                                                        + " locked"))));
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

    @Test
    void recordedFrequenciesWeightFilesAndKitVolumeScalesTracks() {
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
            assertEquals(List.of(9001L, 9002L), music.fileDataIds());
            assertEquals(List.of(5, 1), music.fileWeights(), "duplicate entries add weights");
            assertEquals(0.5, music.volume());
            assertEquals(List.of(0.5, 1.0), music.fileVolumes(), "a duplicate keeps its first");
            assertEquals(0.25, music.volume(9001));
            assertEquals(0.5, music.volume(9002));
            var ambience = plan.ambience().track();
            assertEquals(List.of(0), ambience.fileWeights());
            assertEquals(1.0, ambience.volume(), "recorded volume above 1 is clamped");
            assertEquals(List.of(1.0), ambience.fileVolumes(), "entry volume above 1 is clamped");
            assertTrue(
                    plan.issues().stream().noneMatch(i -> i.contains("SoundKit #")),
                    plan.issues().toString());
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void weightedPicksFollowRecordedFrequencies() {
        var track =
                new WorldAreaAudio.Track(
                        WorldAreaAudio.Kind.MUSIC,
                        10,
                        "Authored zone",
                        5,
                        100,
                        List.of(1L, 2L, 3L),
                        List.of(5, 0, 1),
                        1,
                        0,
                        0);
        assertEquals(1, track.pick(0));
        assertEquals(1, track.pick(0.8));
        assertEquals(3, track.pick(0.84));
        assertEquals(3, track.pick(0.999999));
        var counts = new java.util.HashMap<Long, Integer>();
        for (int i = 0; i < 600; i++) {
            counts.merge(track.pick(i / 600.0), 1, Integer::sum);
        }
        assertEquals(500, counts.get(1L));
        assertNull(counts.get(2L), "a zero weight is never picked");
        assertEquals(100, counts.get(3L));
        var silent =
                new WorldAreaAudio.Track(
                        WorldAreaAudio.Kind.AMBIENCE,
                        10,
                        "Authored zone",
                        5,
                        100,
                        List.of(1L, 2L),
                        List.of(0, 0),
                        1,
                        0,
                        0);
        assertEquals(1, silent.pick(0.49));
        assertEquals(2, silent.pick(0.5), "all-zero weights pick uniformly");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldAreaAudio.Track(
                                WorldAreaAudio.Kind.MUSIC,
                                10,
                                "Authored zone",
                                5,
                                100,
                                List.of(1L),
                                List.of(1),
                                1.5,
                                0,
                                0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldAreaAudio.Track(
                                WorldAreaAudio.Kind.MUSIC,
                                10,
                                "Authored zone",
                                5,
                                100,
                                List.of(1L),
                                List.of(1, 1),
                                1,
                                0,
                                0));
    }

    @Test
    void unreviewedFrequencyAndAbsentKitVolumeFallBackVisibly() {
        var tables = open(fixture(true, false, false));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(11, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.RECORDED, plan.music().state());
            var music = plan.music().track();
            assertEquals(List.of(1, 1), music.fileWeights(), "uniform without Frequency");
            assertEquals(1.0, music.volume());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(
                                    i ->
                                            i.contains("Frequency has an unreviewed shape")
                                                    && i.contains("uniformly")),
                    plan.issues().toString());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(i -> i.contains("SoundKit is") && i.contains("full volume")),
                    plan.issues().toString());
        } finally {
            tables.closeAsync().join();
        }
        var missingRow = open(fixture(true));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            missingRow,
                            0,
                            area(10, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            // Zone 10's day ambience is kit 200, which records no SoundKit row.
            assertEquals(WorldAreaAudio.State.RECORDED, plan.ambience().state());
            assertEquals(1.0, plan.ambience().track().volume());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(
                                    i ->
                                            i.contains("SoundKit #200 is")
                                                    && i.contains("full volume")),
                    plan.issues().toString());
        } finally {
            missingRow.closeAsync().join();
        }
    }

    @Test
    void unreadableChainMembersFallBackToTheirParents() {
        var tables = open(fixture(true));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(13, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.RECORDED, plan.music().state());
            assertEquals(10, plan.music().track().areaId());
            assertEquals(WorldAreaAudio.State.RECORDED, plan.ambience().state());
            assertEquals(200, plan.ambience().track().soundKitId());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(i -> i.contains("#13") && i.contains("parent area")),
                    plan.issues().toString());
            // Nothing readable in the chain is still unavailable, not "none recorded".
            var lone =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(14, INDEX),
                            WorldAreaAudio.Variant.DAY,
                            Cancellation.NONE);
            assertEquals(WorldAreaAudio.State.UNAVAILABLE, lone.music().state());
            assertTrue(lone.music().message().contains("#14"), lone.music().message());
            // The fallback is visible in the selection message, not only in the plan issues.
            assertTrue(
                    plan.music().message().contains("from Recorded zone")
                            && plan.music().message().contains("AreaTable #13"),
                    plan.music().message());
            for (long id : new long[] {15, 16}) {
                var skipped =
                        WorldAreaAudio.resolve(
                                tables,
                                0,
                                area(id, INDEX),
                                WorldAreaAudio.Variant.DAY,
                                Cancellation.NONE);
                assertEquals(WorldAreaAudio.State.RECORDED, skipped.music().state());
                assertEquals(10, skipped.music().track().areaId());
                String message = skipped.music().message();
                assertTrue(
                        message.contains("unreadable")
                                && message.contains(
                                        id == 15 ? "AreaTable #15 is PROTECTED" : "truncated"),
                        message);
            }
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void aFullRequestQueueIsReportedAsBusy() throws Exception {
        var gate = new java.util.concurrent.CompletableFuture<Object>();
        var tables =
                (BuildTables)
                        java.lang.reflect.Proxy.newProxyInstance(
                                WorldAreaAudioTest.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) ->
                                        gate.thenApply(
                                                ignored -> {
                                                    throw new IllegalStateException("closed");
                                                }));
        var audio = new WorldAreaAudio(tables, null);
        try {
            // One request runs (blocked on the gate) and eight fill the queue.
            var running =
                    audio.plan(0, area(11, INDEX), WorldAreaAudio.Variant.DAY, Cancellation.NONE);
            long deadline = System.nanoTime() + 10_000_000_000L;
            java.util.concurrent.CompletableFuture<?> rejected = null;
            int queued = 0;
            while (rejected == null && System.nanoTime() < deadline) {
                var next =
                        audio.plan(
                                0, area(11, INDEX), WorldAreaAudio.Variant.DAY, Cancellation.NONE);
                if (next.isCompletedExceptionally()) {
                    rejected = next;
                } else {
                    queued++;
                }
            }
            assertTrue(rejected != null, "the bounded queue never filled");
            assertTrue(queued <= 9, "queue held " + queued);
            var failure =
                    assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            assertTrue(WorldAreaAudio.busy(failure));
            assertTrue(!WorldAreaAudio.busy(new IllegalStateException("closed")));
            assertTrue(!running.isCompletedExceptionally());
        } finally {
            gate.complete(null);
            audio.closeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void anUnreadableRowFrequencyOrVolumeFallsBackForThatKit() {
        var tables = open(fixture(true));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            tables,
                            0,
                            area(11, INDEX),
                            WorldAreaAudio.Variant.NIGHT,
                            Cancellation.NONE);
            var ambience = plan.ambience().track();
            assertEquals(400, ambience.soundKitId());
            assertEquals(List.of(9401L, 9402L), ambience.fileDataIds());
            assertEquals(List.of(1, 1), ambience.fileWeights(), "one unreadable row: uniform");
            assertEquals(List.of(1.0, 1.0), ambience.fileVolumes(), "NaN plays at kit volume");
            assertEquals(0.75, ambience.volume());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(i -> i.contains("#400") && i.contains("Frequency")),
                    plan.issues().toString());
            assertTrue(
                    plan.issues().stream()
                            .anyMatch(i -> i.contains("#400") && i.contains("entry Volume")),
                    plan.issues().toString());
        } finally {
            tables.closeAsync().join();
        }
    }

    @Test
    void invalidKitVolumesPlayAtFullVolumeWithAnIssue() {
        for (String recorded : List.of("NaN", "-0.5", "Infinity", "-Infinity")) {
            var tables = open(fixture(true, true, true, recorded));
            try {
                var plan =
                        WorldAreaAudio.resolve(
                                tables,
                                0,
                                area(11, INDEX),
                                WorldAreaAudio.Variant.NIGHT,
                                Cancellation.NONE);
                assertEquals(1.0, plan.ambience().track().volume(), recorded);
                assertTrue(
                        plan.issues().stream()
                                .anyMatch(
                                        i ->
                                                i.contains("SoundKit #400 has an invalid")
                                                        && i.contains("full volume")),
                        recorded + " " + plan.issues());
            } finally {
                tables.closeAsync().join();
            }
        }
        var silent = open(fixture(true, true, true, "0"));
        try {
            var plan =
                    WorldAreaAudio.resolve(
                            silent,
                            0,
                            area(11, INDEX),
                            WorldAreaAudio.Variant.NIGHT,
                            Cancellation.NONE);
            assertEquals(0.0, plan.ambience().track().volume(), "a recorded zero is kept");
        } finally {
            silent.closeAsync().join();
        }
    }
}
