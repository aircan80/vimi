package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RacesServiceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, boolean text, String target) {}

    private static Field number(String name) {
        return new Field(name, false, null);
    }

    private static Field text(String name) {
        return new Field(name, true, null);
    }

    private static Field reference(String name, String target) {
        return new Field(name, false, target);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        return table(id, name, fields, rows, "0FC0F0D5");
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows, String layout) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            columns.add(
                    new Db2Types.Column(
                            field.name(), field.text() ? "string" : "integer", false, null, null));
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.text()
                                    ? TableSchema.ColumnKind.LOCSTRING
                                    : TableSchema.ColumnKind.INT,
                            field.text() ? null : 32,
                            field.text() ? null : true,
                            1,
                            field.target() == null
                                    ? null
                                    : new TableSchema.ForeignKey(field.target(), "ID"),
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            !name.equals("ChrRaceRacialAbility"),
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
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "fixture", layout),
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

    @Test
    void mediaVariantsFollowRecordedForeignKeysAndKeepReadablePartialJoins() {
        var media =
                List.of(
                        table(
                                101,
                                "ChrRaceXChrModel",
                                List.of(
                                        reference("ChrRacesID", "ChrRaces"),
                                        reference("ChrModelID", "ChrModel")),
                                List.of(row(1, "1", "10"), row(2, "1", "11"), row(3, "2", "12"))),
                        table(
                                102,
                                "ChrModel",
                                List.of(
                                        reference("DisplayID", "CreatureDisplayInfo"),
                                        number("Sex")),
                                List.of(row(10, "20", "1"))),
                        table(
                                103,
                                "CreatureDisplayInfo",
                                List.of(reference("ModelID", "CreatureModelData")),
                                List.of(row(20, "30"))),
                        table(
                                104,
                                "CreatureModelData",
                                List.of(number("FileDataID")),
                                List.of(row(30, "1234"))));
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", media)
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var variants = service.modelVariants(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.PARTIAL, variants.state());
            assertEquals(1, variants.variants().size());
            var variant = variants.variants().getFirst();
            assertEquals(
                    new RaceMediaTypes.Variant(10, 1, 20, 30, 1234, variant.definition()), variant);
            assertEquals(RaceMediaTypes.DefinitionState.UNREADABLE, variant.definition().state());
            assertEquals("Female", variant.label());
            assertFalse(variants.issues().isEmpty());
            var unavailable = service.preview(1, 1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, unavailable.state());
            assertTrue(unavailable.message().contains("Build files"));
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, idle.state());
            assertEquals(10, idle.variant().chrModelId());
            assertTrue(idle.message().contains("Build files"));
            var unknown = service.idle(1, 99, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, unknown.state());
            assertTrue(unknown.message().contains("unavailable"));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static List<MemoryTables.FakeTable> voiceFixture() {
        return List.of(
                table(
                        201,
                        "EmotesText",
                        List.of(text("Name")),
                        List.of(
                                row(329, "JOKE"),
                                row(305, "CHARGE"),
                                row(77, "FLIRT"),
                                row(50, "WAVE")),
                        "CCDBFBAF"),
                table(
                        202,
                        "EmotesTextSound",
                        List.of(
                                reference("EmotesTextID", "EmotesText"),
                                reference("RaceID", "ChrRaces"),
                                reference("ClassID", "ChrClasses"),
                                number("SexID"),
                                reference("SoundID", "SoundEntries")),
                        List.of(
                                row(1, "329", "1", "0", "0", "100"),
                                row(2, "305", "1", "2", "0", "101"),
                                row(3, "329", "1", "0", "1", "102"),
                                row(4, "329", "2", "0", "0", "103"),
                                row(5, "329", "3", "0", "0", "104"),
                                row(6, "329", "3", "1", "0", "105"),
                                row(7, "305", "3", "0", "0", "0"),
                                row(8, "999", "3", "0", "0", "106"),
                                row(9, "77", "3", "0", "0", "107"),
                                row(10, "50", "3", "0", "0", "108"),
                                row(11, "305", "3", "2", "0", "0"),
                                row(12, "329", "4", "0", "0", "109"),
                                row(13, "329", "4", "5", "0", "109")),
                        "3ECA1BE0"),
                table(
                        203,
                        "SoundKitEntry",
                        List.of(reference("SoundKitID", "SoundKit"), number("FileDataID")),
                        List.of(
                                row(10, "100", "200"),
                                row(11, "101", "201"),
                                row(12, "102", "202"),
                                row(13, "103", "203"),
                                row(14, "104", "204"),
                                row(15, "104", "0"),
                                row(16, "105", "205"),
                                row(17, "108", "208"),
                                row(18, "109", "209")),
                        "8F82FF7D"));
    }

    @Test
    void voicesUseExactRaceSexAndClassWithGenericFallbackAndModernSoundAdapter() {
        var tables = open(voiceFixture());
        var service = new RacesService(tables);
        try {
            var generic = service.voices(1, 1, 0, Cancellation.NONE).join();
            assertEquals(
                    List.of(
                            new RaceVoiceTypes.Clip(
                                    10,
                                    329,
                                    "Joke",
                                    100,
                                    200,
                                    new RaceVoiceTypes.Source("JOKE", 1, 1, 0, 0))),
                    generic.clips());
            assertEquals(List.of(), generic.refusals());
            var specialized = service.voices(1, 2, 0, Cancellation.NONE).join();
            assertEquals(
                    List.of("Charge", "Joke"),
                    specialized.clips().stream().map(RaceVoiceTypes.Clip::action).toList());
            assertEquals(
                    202,
                    service.voices(1, 1, 1, Cancellation.NONE)
                            .join()
                            .clips()
                            .getFirst()
                            .fileDataId());
            assertEquals(
                    RaceTypes.State.UNAVAILABLE,
                    service.voice(1, 1, 0, 12, Cancellation.NONE).join().state());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void voicesShowRecordedSourceChainAndRefuseUnrecordedOrAmbiguousLinks() {
        var tables = open(voiceFixture());
        var memory = new com.vibeminer.files.MemoryFiles();
        for (long fid : new long[] {204, 205}) {
            memory.add(
                    BUILD,
                    "enUS",
                    new com.vibeminer.files.FileTypes.BuildFile(
                            fid,
                            null,
                            com.vibeminer.files.FileTypes.FilePathSource.NONE,
                            32L,
                            Long.toString(fid).repeat(11).substring(0, 32),
                            com.vibeminer.files.FileTypes.FileFormat.UNKNOWN,
                            com.vibeminer.files.FileTypes.FileFormatSource.NONE,
                            com.vibeminer.files.FileTypes.FileStorage.STORED,
                            false),
                    new byte[32],
                    List.of());
        }
        var files = memory.forBuild(BUILD, "enUS", Cancellation.NONE).join().files();
        var service =
                new RacesService(tables, files, new com.vibeminer.generated.MemoryGeneratedStore());
        try {
            var admitted = service.voice(3, 2, 0, 14, Cancellation.NONE).join();
            assertEquals(
                    "Voice preview requires bounded Ogg Vorbis input",
                    admitted.message(),
                    "An admitted clip reaches the decoder");
            var single = service.voices(3, 2, 0, Cancellation.NONE).join();
            assertEquals(
                    List.of(14L),
                    single.clips().stream().map(RaceVoiceTypes.Clip::entryId).toList());
            assertEquals(
                    "EmotesText #329 JOKE → EmotesTextSound #5 (race 3, sex 0, every class)"
                            + " → SoundKit #104 → SoundKitEntry #14 → FileDataID 204",
                    single.clips().getFirst().chain());
            assertEquals(
                    List.of(
                            new RaceVoiceTypes.Refusal(
                                    "SoundKitEntry #15 (Joke)",
                                    "No valid FileDataID is recorded for this entry."),
                            new RaceVoiceTypes.Refusal(
                                    "EmotesTextSound #7 (Charge)",
                                    "No SoundKit is recorded for this link."),
                            new RaceVoiceTypes.Refusal(
                                    "EmotesTextSound #11 (Charge)",
                                    "No SoundKit is recorded for this link."),
                            new RaceVoiceTypes.Refusal(
                                    "EmotesTextSound #8 → EmotesText #999",
                                    "The linked EmotesText row is not recorded in this build."),
                            new RaceVoiceTypes.Refusal(
                                    "SoundKit #107 (Flirt)",
                                    "No SoundKitEntry rows are recorded for this SoundKit.")),
                    single.refusals());
            assertTrue(
                    single.issues().stream()
                            .anyMatch(issue -> issue.startsWith("1 recorded voice links use")));
            assertEquals(
                    "Selected voice clip is unavailable.",
                    service.voice(3, 2, 0, 15, Cancellation.NONE).join().message());

            var ambiguous = service.voices(3, 1, 0, Cancellation.NONE).join();
            assertEquals(List.of(), ambiguous.clips());
            assertEquals(
                    new RaceVoiceTypes.Refusal(
                            "EmotesText #329 JOKE → EmotesTextSound #5, #6",
                            "Links name different SoundKits #104, #105 for this race, sex and"
                                    + " class; the runtime choice is not evaluated."),
                    ambiguous.refusals().getFirst());
            assertEquals(
                    "No reviewed voice clips are readable for this race and voice.",
                    ambiguous.message());
            assertEquals(
                    "Selected voice clip is unavailable.",
                    service.voice(3, 1, 0, 16, Cancellation.NONE).join().message(),
                    "An ambiguous clip is refused before its readable file is opened");
            var repeated = service.voices(4, 5, 0, Cancellation.NONE).join();
            assertEquals(List.of(), repeated.refusals());
            assertEquals(
                    "EmotesText #329 JOKE → EmotesTextSound #13 (race 4, sex 0, class 5)"
                            + " → SoundKit #109 → SoundKitEntry #18 → FileDataID 209",
                    repeated.clips().getFirst().chain());
            for (var refusal : ambiguous.refusals()) {
                assertFalse(refusal.link().contains("/") || refusal.reason().contains("/"));
            }
        } finally {
            service.closeAsync().join();
            files.close();
            tables.closeAsync().join();
        }
    }

    @Test
    void voicePcmRestoreUsesBuildLocaleAndContentBindingWithoutOpeningOriginal() {
        var tables = open(voiceFixture());
        var file =
                new com.vibeminer.files.FileTypes.BuildFile(
                        200,
                        null,
                        com.vibeminer.files.FileTypes.FilePathSource.NONE,
                        32L,
                        "d".repeat(32),
                        com.vibeminer.files.FileTypes.FileFormat.UNKNOWN,
                        com.vibeminer.files.FileTypes.FileFormatSource.NONE,
                        com.vibeminer.files.FileTypes.FileStorage.STORED,
                        false);
        var files =
                new com.vibeminer.files.MemoryFiles()
                        .add(BUILD, "enUS", file, new byte[32], List.of())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .files();
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var key =
                com.vibeminer.generated.GeneratedKey.hash(
                        (com.vibeminer.formats.audio.VoiceDecoder.REVISION
                                        + "\n"
                                        + BUILD
                                        + "\nenUS\n"
                                        + "d".repeat(32))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] pcm =
                com.vibeminer.formats.audio.VoiceDecoder.encodePcm(
                        new com.vibeminer.formats.audio.PcmClip(22050, 1, new byte[20]));
        generated
                .put(
                        List.of(new com.vibeminer.generated.GeneratedTypes.Put(key, pcm)),
                        com.vibeminer.generated.Cancellation.NONE)
                .join();
        var service = new RacesService(tables, files, generated);
        try {
            var result = service.voice(1, 1, 0, 10, Cancellation.NONE).join();
            assertTrue(result.restored());
            assertEquals(20, result.clip().bytes().length);
            generated.delete(List.of(key), com.vibeminer.generated.Cancellation.NONE).join();
            generated
                    .put(
                            List.of(
                                    new com.vibeminer.generated.GeneratedTypes.Put(
                                            key, new byte[17])),
                            com.vibeminer.generated.Cancellation.NONE)
                    .join();
            var broken = service.voice(1, 1, 0, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, broken.state());
            assertTrue(broken.issues().stream().anyMatch(issue -> issue.contains("repair")));
        } finally {
            service.closeAsync().join();
            files.close();
            tables.closeAsync().join();
        }
    }

    private static List<MemoryTables.FakeTable> fixture() {
        return List.of(
                table(
                        1,
                        "ChrRaces",
                        List.of(
                                text("Name_lang"),
                                text("LoreDescription_lang"),
                                number("UiDisplayOrder"),
                                reference("FactionID", "FactionTemplate")),
                        List.of(
                                row(1, "Human", "Human lore", "1", "10"),
                                row(95, "Recorded race", "Other lore", "2", "0"),
                                row(99, "Internal", "", "99", "0"))),
                table(
                        2,
                        "CharBaseInfo",
                        List.of(
                                reference("RaceID", "ChrRaces"),
                                reference("ClassID", "ChrClasses")),
                        List.of(
                                row(1, "1", "1"),
                                row(2, "1", "2"),
                                row(3, "95", "1"),
                                row(4, "1", "1"))),
                table(
                        3,
                        "ChrClasses",
                        List.of(text("Name_lang")),
                        List.of(row(1, "Warrior"), row(2, "Mage"))),
                table(
                        4,
                        "ChrRaceRacialAbility",
                        List.of(
                                text("Name_lang"),
                                text("Description_lang"),
                                number("Icon"),
                                number("Order"),
                                reference("ChrRacesID", "ChrRaces")),
                        List.of(
                                row(
                                        101,
                                        "Diplomacy",
                                        "Reputation gains increased.",
                                        "132328",
                                        "0",
                                        "1"),
                                row(102, "Other racial", "Readable description.", "0", "1", "95"))),
                table(
                        5,
                        "FactionTemplate",
                        List.of(reference("Faction", "Faction")),
                        List.of(row(10, "20"))),
                table(6, "Faction", List.of(text("Name_lang")), List.of(row(20, "Human faction"))));
    }

    private static com.vibeminer.formats.tables.BuildTables open(
            List<MemoryTables.FakeTable> tables) {
        return new MemoryTables()
                .add(BUILD, "enUS", tables)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void catalogKeepsInternalRecordsSeparateFromRecordedClassCombinations() {
        try (var tables = open(fixture());
                var races = new RacesService(tables)) {
            var result = races.catalog(Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, result.state());
            assertEquals(3, result.races().size());
            assertEquals(2, result.races().getFirst().recordedClasses());
            assertEquals(0, result.races().getLast().recordedClasses());
            var detail = races.overview(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, detail.state());
            assertEquals("Human lore", detail.lore());
            assertEquals("Human faction", detail.faction());
            assertEquals(2, detail.classes().size());
            assertEquals("Diplomacy", detail.abilities().getFirst().name());
            assertEquals(132328L, detail.abilities().getFirst().iconFileDataId());
        }
    }

    @Test
    void partialRowsAndUnresolvedClassNamesRemainUsable() {
        var all = new ArrayList<>(fixture());
        var source = all.get(2);
        var e = source.entry();
        var partial =
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(2, 1, 1, 1, "fixture", "0FC0F0D5"),
                        e.contentKey());
        all.set(
                2,
                new MemoryTables.FakeTable(
                        partial,
                        source.columns(),
                        List.of(source.rows().getFirst()),
                        source.schema(),
                        Map.of(),
                        false));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var result = races.overview(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.PARTIAL, result.state());
            assertEquals(2, result.classes().size());
            assertTrue(result.classes().stream().anyMatch(item -> item.name().equals("Class #2")));
            assertEquals(1, result.abilities().size());
        }
    }

    @Test
    void missingRacialTableDoesNotDiscardRaceAndClassFacts() {
        var all =
                fixture().stream()
                        .filter(table -> !table.entry().name().equals("ChrRaceRacialAbility"))
                        .toList();
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var result = races.overview(1, Cancellation.NONE).join();
            assertEquals("Human", result.race().name());
            assertEquals(2, result.classes().size());
            assertEquals(RaceTypes.State.PARTIAL, result.state());
            assertTrue(result.abilities().isEmpty());
        }
    }

    @Test
    void missingPartialRaceRowRetainsRecordedRaceIdAndKnownClasses() {
        var all = new ArrayList<>(fixture());
        var source = all.getFirst();
        var entry = source.entry();
        var partial =
                new TableTypes.Entry(
                        entry.fileDataId(),
                        entry.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(3, 2, 1, 1, "fixture", "0FC0F0D5"),
                        entry.contentKey());
        all.set(
                0,
                new MemoryTables.FakeTable(
                        partial,
                        source.columns(),
                        source.rows().subList(1, 3),
                        source.schema(),
                        Map.of(),
                        false));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals("Race #1", overview.race().name());
            assertEquals(2, overview.classes().size());
            assertEquals(1, overview.abilities().size());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
        }
    }

    @Test
    void unknownUnverifiedRacialLayoutDoesNotGuessNamesOrJoin() {
        var all = new ArrayList<>(fixture());
        var source = all.get(3);
        var schema = source.schema();
        all.set(
                3,
                new MemoryTables.FakeTable(
                        source.entry(),
                        source.columns(),
                        source.rows(),
                        new TableSchema(
                                schema.columns(),
                                List.of("DEADBEEF"),
                                schema.match(),
                                schema.locale(),
                                null),
                        Map.of(),
                        true));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals("Human", overview.race().name());
            assertEquals(2, overview.classes().size());
            assertTrue(overview.abilities().isEmpty());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
        }
    }

    @Test
    void excessiveClassLinksRemainBoundedAndExplicit() {
        var all = new ArrayList<>(fixture());
        var links = new ArrayList<Db2Types.Row>();
        for (int id = 1; id <= 200; id++) {
            links.add(row(id, "1", Integer.toString(id)));
        }
        all.set(
                1,
                table(
                        2,
                        "CharBaseInfo",
                        List.of(
                                reference("RaceID", "ChrRaces"),
                                reference("ClassID", "ChrClasses")),
                        links));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals(128, overview.classes().size());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
            assertTrue(
                    overview.issues().stream().anyMatch(issue -> issue.contains("display bounds")));
        }
    }

    @Test
    void borrowedTablesRemainOpenAfterComponentCloseAndCanceledQueriesCanRetry() {
        try (var tables = open(fixture())) {
            var races = new RacesService(tables);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> races.catalog(token).join());
            assertFalse(races.catalog(Cancellation.NONE).join().races().isEmpty());
            races.close();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> races.catalog(Cancellation.NONE).join());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("ChrRaces"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    /** {@code model} (a chunked 274 model) with a BFID chunk naming {@code ids}; no game data. */
    private static byte[] withBoneFiles(byte[] model, long... ids) {
        var b =
                java.nio.ByteBuffer.allocate(model.length + 8 + ids.length * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(model).put("BFID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(ids.length * 4);
        for (long id : ids) {
            b.putInt((int) id);
        }
        return b.array();
    }

    /** Race 1 → ChrModel 10 → display 20 → model data 30 → M2 FileDataID 1234. */
    private static com.vibeminer.formats.tables.BuildTables idleTables() {
        return idleTables(1);
    }

    /** Race 1 → ChrModel 10+i → display 20+i → model data 30+i → M2 FileDataID 1234+i. */
    private static com.vibeminer.formats.tables.BuildTables idleTables(int models) {
        var joins = new ArrayList<Db2Types.Row>();
        var chrModels = new ArrayList<Db2Types.Row>();
        var displays = new ArrayList<Db2Types.Row>();
        var assets = new ArrayList<Db2Types.Row>();
        for (int i = 0; i < models; i++) {
            joins.add(row(1 + i, "1", Integer.toString(10 + i)));
            chrModels.add(row(10 + i, Integer.toString(20 + i), "0"));
            displays.add(row(20 + i, Integer.toString(30 + i)));
            assets.add(row(30 + i, Integer.toString(1234 + i)));
        }
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                table(
                                        101,
                                        "ChrRaceXChrModel",
                                        List.of(
                                                reference("ChrRacesID", "ChrRaces"),
                                                reference("ChrModelID", "ChrModel")),
                                        joins),
                                table(
                                        102,
                                        "ChrModel",
                                        List.of(
                                                reference("DisplayID", "CreatureDisplayInfo"),
                                                number("Sex")),
                                        chrModels),
                                table(
                                        103,
                                        "CreatureDisplayInfo",
                                        List.of(reference("ModelID", "CreatureModelData")),
                                        displays),
                                table(
                                        104,
                                        "CreatureModelData",
                                        List.of(number("FileDataID")),
                                        assets)))
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void boneFileModelPlaysTheBaseRigStandWithoutOpeningItsBoneFiles() throws Exception {
        var tables = idleTables();
        byte[] skin = standSkin();
        byte[] plain = modelWithStand(0x200);
        // Bone files 701 and 702 are declared but not stored: nothing asks for them.
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), withBoneFiles(plain, 701, 702))
                        .add(900, "2".repeat(32), skin);
        var service = new RacesService(tables, files, null);
        try {
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            assertTrue(idle.reasons().isEmpty());
            assertEquals(1000, idle.animation().durationMillis());
            assertEquals(3, idle.facts().size(), idle.facts().toString());
            String fact = idle.facts().get(1);
            assertTrue(
                    fact.startsWith(
                            "Bone files (BFID): 2 declared, none applied; Stand plays on the base"
                                    + " rig"),
                    fact);
            // These tables record no customization: the fact says so rather than refusing.
            assertTrue(fact.contains("could not be read"), fact);
            assertTrue(idle.facts().get(2).startsWith("Stand is bound to 4 rest vertices"));
            // The base rig's Stand is the Stand of the same model without bone files.
            var plainFiles =
                    new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                            .add(1234, "1".repeat(32), plain)
                            .add(900, "2".repeat(32), skin);
            var reference = new RacesService(tables, plainFiles, null);
            try {
                var expected = reference.idle(1, 10, Cancellation.NONE).join();
                assertEquals(RaceTypes.State.READY, expected.state(), expected.message());
                var preview = reference.preview(1, 0, 10, Cancellation.NONE).join();
                for (int at : new int[] {0, 250, 500, 999}) {
                    assertTrue(
                            java.util.Arrays.equals(
                                    expected.animation()
                                            .pose(preview.mesh(), at)
                                            .mesh()
                                            .positions(),
                                    idle.animation().pose(preview.mesh(), at).mesh().positions()),
                            "same pose at " + at);
                }
            } finally {
                reference.closeAsync().join();
            }
            // A complete result is cached; a changed model is reassessed.
            int opens = files.opens.get();
            assertTrue(
                    service.idle(1, 10, Cancellation.NONE).join().animation() == idle.animation());
            assertEquals(opens, files.opens.get());
            files.add(1234, "5".repeat(32), withBoneFiles(plain, 701, 702));
            service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(files.opens.get() > opens, "Changed model is reassessed");
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static byte[] chunk(String id, byte[] payload) {
        return java.nio.ByteBuffer.allocate(8 + payload.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /**
     * An authored skeleton: one Stand (1000 ms, keys in the skeleton); bone 0 a root translating
     * (0, 0, 0) to (0, 0, 4), bone 1 its child; a BFID chunk naming bone files 801 and 802.
     */
    private static byte[] skeleton() {
        return skeleton(2, 0, 0, true);
    }

    /**
     * An authored skeleton with {@code bones} bones (bone 0 a root translating (0, 0, 0) to (0, 0,
     * 4), every other bone a child of bone 0), one sequence with ID {@code sequenceId} (1000 ms,
     * keys in the skeleton), a BFID chunk naming bone files 801 and 802 and, when {@code parent} is
     * not 0, an SKPD chunk naming it; without {@code ownRig} it has no SKS1 or SKB1 chunk.
     */
    private static byte[] skeleton(int bones, int sequenceId, long parent, boolean ownRig) {
        var s = java.nio.ByteBuffer.allocate(32 + 64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        s.putInt(8, 1).putInt(12, 32);
        s.putShort(32, (short) sequenceId).putInt(32 + 4, 1000).putInt(32 + 12, 0x20);
        var b =
                java.nio.ByteBuffer.allocate(16 + bones * 88 + 48)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, bones).putInt(4, 16);
        for (int bone = 0; bone < bones; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, -1).putInt(row + 4, 512).putShort(row + 8, (short) (bone == 0 ? -1 : 0));
            for (int track : new int[] {16, 36, 56}) {
                b.putShort(row + track + 2, (short) -1);
            }
        }
        int data = 16 + bones * 88, row = 16 + 16;
        b.putShort(row, (short) 1).putInt(row + 4, 1).putInt(row + 8, data);
        b.putInt(row + 12, 1).putInt(row + 16, data + 8);
        b.putInt(data, 2).putInt(data + 4, data + 16);
        b.putInt(data + 8, 2).putInt(data + 12, data + 24);
        b.putInt(data + 16, 0).putInt(data + 20, 1000);
        b.putFloat(data + 24 + 20, 4);
        var ids = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ids.putInt(801).putInt(802);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(chunk("SKL1", new byte[16]));
        if (parent != 0) {
            var p = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            p.putInt(8, (int) parent);
            out.writeBytes(chunk("SKPD", p.array()));
        }
        if (ownRig) {
            out.writeBytes(chunk("SKS1", s.array()));
            out.writeBytes(chunk("SKB1", b.array()));
        }
        out.writeBytes(chunk("BFID", ids.array()));
        return out.toByteArray();
    }

    /**
     * The authored 274 model with no bones of its own, every vertex rigidly bound to bone 0, and a
     * SKID chunk naming skeleton 4242.
     */
    private static byte[] skeletonModel() throws Exception {
        byte[] original = golden("authored-274.m2");
        var parsed =
                com.vibeminer.formats.models.M2ModelReader.read(
                                com.vibeminer.formats.AssetSource.bytes(original))
                        .join();
        int base = Math.toIntExact(parsed.baseOffset());
        int body = Math.toIntExact(parsed.baseBytes());
        if (base + body != original.length) {
            throw new AssertionError("The authored body must end the file");
        }
        // A one-entry bone lookup (slot 0 names skeleton bone 0, see standSkin()), then SKID.
        var b =
                java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(original, original.length + 16))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(base - 4, body + 4);
        b.putInt(base + 120, 1).putInt(base + 124, body);
        int vertices = base + Math.toIntExact(b.getInt(base + 64));
        for (int v = 0; v < b.getInt(base + 60); v++) {
            b.putInt(vertices + v * 48 + 12, 255).putInt(vertices + v * 48 + 16, 0);
        }
        b.position(original.length + 4);
        b.put("SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(4242);
        return b.array();
    }

    @Test
    void skeletonModelPlaysTheSkeletonsStandWithoutItsBoneFiles() throws Exception {
        var tables = idleTables();
        byte[] skin = standSkin();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), skeletonModel())
                        .add(900, "2".repeat(32), skin);
        var service = new RacesService(tables, files, null);
        try {
            // The skeleton is not stored yet: the Stand is unavailable, not refused, and the
            // result is not cached.
            var absent = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, absent.state());
            assertEquals(
                    "The external skeleton FileDataID 4242 could not be read: The skeleton file is"
                            + " UNKNOWN_FILE.",
                    absent.message());
            files.add(4242, "3".repeat(32), skeleton());
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            assertEquals(2, idle.animation().boneCount());
            assertEquals(
                    "Stand (plays sequence 0, 1000 ms) is stored in external skeleton FileDataID"
                            + " 4242: 2 step/linear keys on 1 bones.",
                    idle.facts().get(0));
            // The skeleton's bone files are named but neither opened nor applied.
            assertTrue(
                    idle.facts().get(1).startsWith("Bone files (BFID): 2 declared, none applied"),
                    idle.facts().get(1));
            var preview = service.preview(1, 0, 10, Cancellation.NONE).join();
            assertTrue(idle.animation().matches(preview.mesh()), preview.message());
            float[] still = preview.mesh().positions();
            float[] posed = idle.animation().pose(preview.mesh(), 500).mesh().positions();
            for (int i = 2; i < still.length; i += 3) {
                assertEquals(still[i] + 2, posed[i], 1e-5);
            }
            // Cached; a changed skeleton is reassessed.
            int opens = files.opens.get();
            assertTrue(
                    service.idle(1, 10, Cancellation.NONE).join().animation() == idle.animation());
            assertEquals(opens, files.opens.get());
            files.add(4242, "4".repeat(32), skeleton());
            service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(files.opens.get() > opens, "Changed skeleton is reassessed");
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    /** Runs {@code check} on the idle of an authored skeleton model whose skeleton is given. */
    private static void withSkeletonModel(
            byte[] skeleton,
            java.util.function.BiConsumer<RacesService, com.vibeminer.formats.AssetFilesFixture>
                    check)
            throws Exception {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), skeletonModel())
                        .add(900, "2".repeat(32), standSkin())
                        .add(4242, "3".repeat(32), skeleton);
        var service = new RacesService(tables, files, null);
        try {
            check.accept(service, files);
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void skeletonOverTheBoneLimitIsRefusedCachedAndRevalidated() throws Exception {
        int bones = com.vibeminer.formats.models.M2IdleAnimation.MAX_BONES + 1;
        withSkeletonModel(
                skeleton(bones, 0, 0, true),
                (service, files) -> {
                    var idle = service.idle(1, 10, Cancellation.NONE).join();
                    assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
                    assertTrue(
                            idle.message().contains("The skeleton has " + bones + " bones"),
                            idle.message());
                    int opens = files.opens.get();
                    assertEquals(
                            idle.message(),
                            service.idle(1, 10, Cancellation.NONE).join().message());
                    assertEquals(opens, files.opens.get());
                    files.add(4242, "4".repeat(32), skeleton(2, 0, 0, true));
                    var renewed = service.idle(1, 10, Cancellation.NONE).join();
                    assertTrue(files.opens.get() > opens, "Changed skeleton is reassessed");
                    assertEquals(RaceTypes.State.READY, renewed.state(), renewed.message());
                });
    }

    @Test
    void skeletonParentIsNamedButNotRead() throws Exception {
        // Bones kept only in the parent: refused with the parent-specific reason.
        withSkeletonModel(
                skeleton(2, 0, 777, false),
                (service, files) -> {
                    var idle = service.idle(1, 10, Cancellation.NONE).join();
                    assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
                    assertTrue(
                            idle.message()
                                    .contains(
                                            "keeps its bones or sequences in its parent skeleton"),
                            idle.message());
                });
        // Own bones and Stand beside a parent: plays, and says the parent is not read.
        withSkeletonModel(
                skeleton(2, 0, 777, true),
                (service, files) -> {
                    var idle = service.idle(1, 10, Cancellation.NONE).join();
                    assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
                    assertTrue(
                            idle.facts()
                                    .contains("Parent skeleton FileDataID 777 (SKPD) is not read."),
                            idle.facts().toString());
                });
    }

    @Test
    void aRefusedSkeletonStillNamesItsParent() throws Exception {
        // Own bones beside a parent, but no Stand: refused, and the parent is still named.
        withSkeletonModel(
                skeleton(2, 4, 777, true),
                (service, files) -> {
                    var idle = service.idle(1, 10, Cancellation.NONE).join();
                    assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
                    assertTrue(
                            idle.facts()
                                    .contains("Parent skeleton FileDataID 777 (SKPD) is not read."),
                            idle.facts().toString());
                });
    }

    @Test
    void unreadableCustomizationTablesAreAskedAgainRatherThanCached() throws Exception {
        var raw = sharedModelTables(false, 1234);
        var counting = new CountingTables(raw);
        counting.failAllPagesOf = "ChrCustomizationBoneSet";
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), withBoneFiles(modelWithStand(0x200), 701, 702))
                        .add(900, "2".repeat(32), standSkin());
        var service = new RacesService(counting.proxy(), files, null);
        try {
            var first = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, first.state(), first.message());
            assertTrue(
                    first.facts().get(1).contains("could not be read: ChrCustomizationBoneSet"),
                    first.facts().get(1));
            // Not cached: the next request reads the model and the table again.
            int opens = files.opens.get();
            service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(files.opens.get() > opens, "An unreadable fact is reassessed");
            // Once the table reads, the fact names the choice and the result is cached.
            counting.failAllPagesOf = null;
            var readable = service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(
                    readable.facts().get(1).contains("selects bone file 701"),
                    readable.facts().get(1));
            opens = files.opens.get();
            assertEquals(readable.facts(), service.idle(1, 10, Cancellation.NONE).join().facts());
            assertEquals(opens, files.opens.get());
        } finally {
            service.closeAsync().join();
            raw.closeAsync().join();
        }
    }

    @Test
    void skeletonWithoutAStandIsRefused() throws Exception {
        withSkeletonModel(
                skeleton(2, 4, 0, true),
                (service, files) -> {
                    var idle = service.idle(1, 10, Cancellation.NONE).join();
                    assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
                    assertEquals(
                            List.of(
                                    "Stand: The skeleton records no Stand sequence (ID 0, variation"
                                            + " 0)."),
                            idle.reasons());
                });
    }

    /**
     * Race 1 → ChrModels 10 and 11, both on M2 FileDataID 1234, with customization options whose
     * choices select bone sets 5 (bone file 701) and 6 (bone file 702) for model {@code setModel}.
     * {@code partialSets} declares one more bone-set row than it can read.
     */
    private static com.vibeminer.formats.tables.BuildTables sharedModelTables(
            boolean partialSets, long setModel) {
        var setTable =
                table(
                        109,
                        "ChrCustomizationBoneSet",
                        List.of(number("BoneFileDataID"), number("ModelFileDataID")),
                        List.of(
                                row(5, "701", Long.toString(setModel)),
                                row(6, "702", Long.toString(setModel))));
        if (partialSets) {
            var entry = setTable.entry();
            setTable =
                    new MemoryTables.FakeTable(
                            new TableTypes.Entry(
                                    entry.fileDataId(),
                                    entry.name(),
                                    entry.state(),
                                    entry.checked(),
                                    entry.reason(),
                                    new TableTypes.Counts(3, 2, 0, 0, "fixture", "0FC0F0D5"),
                                    entry.contentKey()),
                            setTable.columns(),
                            setTable.rows(),
                            setTable.schema(),
                            setTable.protectedIds(),
                            setTable.generated());
        }
        var sets = setTable;
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                table(
                                        101,
                                        "ChrRaceXChrModel",
                                        List.of(
                                                reference("ChrRacesID", "ChrRaces"),
                                                reference("ChrModelID", "ChrModel")),
                                        List.of(row(1, "1", "10"), row(2, "1", "11"))),
                                table(
                                        102,
                                        "ChrModel",
                                        List.of(
                                                reference("DisplayID", "CreatureDisplayInfo"),
                                                number("Sex")),
                                        List.of(row(10, "20", "0"), row(11, "21", "1"))),
                                table(
                                        103,
                                        "CreatureDisplayInfo",
                                        List.of(reference("ModelID", "CreatureModelData")),
                                        List.of(row(20, "30"), row(21, "30"))),
                                table(
                                        104,
                                        "CreatureModelData",
                                        List.of(number("FileDataID")),
                                        List.of(row(30, "1234"))),
                                table(
                                        106,
                                        "ChrCustomizationOption",
                                        List.of(text("Name_lang"), number("ChrModelID")),
                                        List.of(row(100, "Face", "10"), row(110, "Jaw", "11"))),
                                table(
                                        107,
                                        "ChrCustomizationChoice",
                                        List.of(number("ChrCustomizationOptionID")),
                                        List.of(
                                                row(1000, "100"),
                                                row(1001, "100"),
                                                row(1100, "110"))),
                                table(
                                        108,
                                        "ChrCustomizationElement",
                                        List.of(
                                                number("ChrCustomizationChoiceID"),
                                                number("ChrCustomizationBoneSetID")),
                                        List.of(row(1, "1000", "5"), row(2, "1100", "6"))),
                                sets))
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void oneModelSharedByTwoChrModelsKeepsEachModelsBoneFileFact() throws Exception {
        var tables = sharedModelTables(false, 1234);
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), withBoneFiles(modelWithStand(0x200), 701, 702))
                        .add(900, "2".repeat(32), standSkin());
        var service = new RacesService(tables, files, null);
        try {
            var face = service.idle(1, 10, Cancellation.NONE).join();
            var jaw = service.idle(1, 11, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, face.state(), face.message());
            assertEquals(RaceTypes.State.READY, jaw.state(), jaw.message());
            assertTrue(
                    face.facts()
                            .get(1)
                            .endsWith(
                                    "1 customization choices select one; the first recorded 'Face'"
                                            + " (option 100) choice, 1000, selects bone file 701, which is"
                                            + " not applied."),
                    face.facts().get(1));
            assertTrue(
                    jaw.facts()
                            .get(1)
                            .endsWith(
                                    "1 customization choices select one; the first recorded 'Jaw'"
                                            + " (option 110) choice, 1100, selects bone file 702, which is"
                                            + " not applied."),
                    jaw.facts().get(1));
            // Each stays its own cached result.
            assertEquals(face.facts(), service.idle(1, 10, Cancellation.NONE).join().facts());
            assertEquals(jaw.facts(), service.idle(1, 11, Cancellation.NONE).join().facts());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void partlyReadCustomizationTablesDoNotClaimThatNoChoiceSelectsABoneFile() throws Exception {
        // The readable bone sets are for another model; one row of the table is unreadable.
        var tables = sharedModelTables(true, 9999);
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), withBoneFiles(modelWithStand(0x200), 701))
                        .add(900, "2".repeat(32), standSkin());
        var service = new RacesService(tables, files, null);
        try {
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            String fact = idle.facts().get(1);
            assertTrue(fact.contains("could not be fully read"), fact);
            assertFalse(fact.contains("No customization choice"), fact);
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
        // The same tables read whole say that no choice selects one.
        var whole = sharedModelTables(false, 9999);
        var again = new RacesService(whole, files, null);
        try {
            var idle = again.idle(1, 10, Cancellation.NONE).join();
            assertTrue(
                    idle.facts()
                            .get(1)
                            .endsWith("No customization choice of this model selects one."),
                    idle.facts().get(1));
        } finally {
            again.closeAsync().join();
            whole.closeAsync().join();
        }
    }

    @Test
    void boneFileModelWithAnUnsupportedStandIsStillRefused() throws Exception {
        var tables = idleTables();
        byte[] model;
        try (var in = RacesServiceTest.class.getResourceAsStream("/golden/authored-274.m2")) {
            model = in.readAllBytes();
        }
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), withBoneFiles(model, 701));
        var service = new RacesService(tables, files, null);
        try {
            // The authored model stores no bones: the base rig has nothing to animate.
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
            assertEquals(List.of("Stand: The model has no bones to animate."), idle.reasons());
            assertTrue(idle.facts().getFirst().startsWith("Bone files (BFID): 1 declared"));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static byte[] golden(String name) throws Exception {
        try (var in = RacesServiceTest.class.getResourceAsStream("/golden/" + name)) {
            return in.readAllBytes();
        }
    }

    /**
     * The authored 274 model with one Stand sequence (1,000 ms, keys in the model) and one bone
     * with {@code boneFlags} whose translation moves linearly from (0, 0, 0) to (0, 0, 4); every
     * vertex is rigidly bound to that bone. No game data.
     */
    private static byte[] modelWithStand(int boneFlags) throws Exception {
        byte[] original = golden("authored-274.m2");
        var parsed =
                com.vibeminer.formats.models.M2ModelReader.read(
                                com.vibeminer.formats.AssetSource.bytes(original))
                        .join();
        int base = Math.toIntExact(parsed.baseOffset());
        int body = Math.toIntExact(parsed.baseBytes());
        if (base + body != original.length) {
            throw new AssertionError("The authored body must end the file");
        }
        int sequences = body, bones = sequences + 64, track = bones + 88;
        int lookup = track + 48, extra = 64 + 88 + 48 + 4;
        var b =
                java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(original, original.length + extra))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(base - 4, body + extra);
        b.putInt(base + 28, 1).putInt(base + 32, sequences);
        b.putInt(base + 44, 1).putInt(base + 48, bones);
        // A one-entry bone lookup: palette slot 0 names bone 0 (see standSkin()).
        b.putInt(base + 120, 1).putInt(base + 124, lookup);
        int sequence = base + sequences;
        b.putShort(sequence, (short) 0).putInt(sequence + 4, 1000).putInt(sequence + 12, 0x20);
        int bone = base + bones;
        b.putInt(bone, -1).putInt(bone + 4, boneFlags).putShort(bone + 8, (short) -1);
        for (int offset : new int[] {16, 36, 56}) {
            b.putShort(bone + offset + 2, (short) -1);
        }
        int outerTimes = track, outerValues = track + 8, times = track + 16, values = track + 24;
        b.putShort(bone + 16, (short) 1);
        b.putInt(bone + 20, 1).putInt(bone + 24, outerTimes);
        b.putInt(bone + 28, 1).putInt(bone + 32, outerValues);
        b.putInt(base + outerTimes, 2).putInt(base + outerTimes + 4, times);
        b.putInt(base + outerValues, 2).putInt(base + outerValues + 4, values);
        b.putInt(base + times, 0).putInt(base + times + 4, 1000);
        b.position(base + values);
        b.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(4);
        int vertices = base + Math.toIntExact(b.getInt(base + 64));
        for (int v = 0; v < b.getInt(base + 60); v++) {
            b.putInt(vertices + v * 48 + 12, 255).putInt(vertices + v * 48 + 16, 0);
        }
        return b.array();
    }

    /**
     * The authored SKIN as a client skin for {@link #modelWithStand}: its one section names bone 0
     * through palette slot 0 of the model's bone lookup, as the vertices do directly.
     */
    private static byte[] standSkin() throws Exception {
        byte[] skin = golden("authored-274.skin");
        var b = java.nio.ByteBuffer.wrap(skin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int properties = b.getInt(24), count = b.getInt(20), section = b.getInt(32);
        for (int i = 0; i < count; i++) {
            b.putInt(properties + i * 4, 0);
        }
        b.putShort(section + 12, (short) 1).putShort(section + 14, (short) 0);
        return skin;
    }

    @Test
    void localStandIsPreparedForTheStaticRestSurfaceAndCached() throws Exception {
        var tables = idleTables();
        byte[] skin = standSkin();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), modelWithStand(0x200))
                        .add(900, "2".repeat(32), skin);
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var service = new RacesService(tables, files, generated);
        try {
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            assertTrue(idle.reasons().isEmpty());
            assertEquals(10, idle.variant().chrModelId());
            var animation = idle.animation();
            assertEquals(1000, animation.durationMillis());
            assertEquals(1, animation.boneCount());
            assertEquals(
                    "Stand idle · 1000 ms loop · 1 bones evaluated in software.", idle.message());
            assertEquals(
                    List.of(
                            "Stand (plays sequence 0, 1000 ms) is stored in the model: 2 step/linear keys"
                                    + " on 1 bones.",
                            "Stand is bound to 4 rest vertices of SKIN FileDataID 900; 1 bones are"
                                    + " evaluated in software."),
                    idle.facts());
            // The animation matches the static mesh the service previews.
            var preview = service.preview(1, 0, 10, Cancellation.NONE).join();
            assertTrue(preview.mesh() != null, preview.message());
            assertTrue(animation.matches(preview.mesh()), "READY idle fits service.preview()");
            float[] still = preview.mesh().positions();
            float[] posed = animation.pose(preview.mesh(), 500).mesh().positions();
            for (int i = 2; i < still.length; i += 3) {
                assertEquals(still[i] + 2, posed[i], 1e-5);
            }
            // A repeated preview is the remembered one; a new handle restores the derived surface
            // from the generated store, and that fits too.
            assertTrue(service.preview(1, 0, 10, Cancellation.NONE).join() == preview);
            var fresh = new RacesService(tables, files, generated);
            try {
                var restored = fresh.preview(1, 0, 10, Cancellation.NONE).join();
                assertTrue(restored.restored(), "A new handle restores the preview");
                assertTrue(animation.matches(restored.mesh()));
            } finally {
                fresh.closeAsync().join();
            }
            // The textured sample's surface, derived by RaceAppearance from the draw SKIN, fits
            // in positions and normals.
            var models = new com.vibeminer.formats.models.BuildModels(files);
            var read =
                    models.readModel(
                                    com.vibeminer.files.FileTypes.FileRef.id(1234),
                                    Cancellation.NONE)
                            .join();
            var drawn = RaceAppearance.drawSurface(models, read, Cancellation.NONE).surface();
            var textured =
                    new com.vibeminer.rendering.models.MeshAppearance(
                            drawn.mesh(), drawn.uv(), drawn.normals(), List.of(), List.of());
            assertTrue(animation.matches(textured), "READY idle fits the textured sample");
            // A complete result is reused without reopening; a new SKIN invalidates it.
            int opens = files.opens.get();
            var cached = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(opens, files.opens.get());
            assertTrue(cached.animation() == animation);
            files.add(900, "3".repeat(32), skin);
            var renewed = service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(files.opens.get() > opens, "Changed SKIN is reassessed");
            assertEquals(RaceTypes.State.READY, renewed.state());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void localStandOutsideTheBoneFlagProfileKeepsItsReason() throws Exception {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), modelWithStand(0x1))
                        .add(900, "2".repeat(32), standSkin());
        var service = new RacesService(tables, files, null);
        try {
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNSUPPORTED, idle.state());
            assertEquals(null, idle.animation());
            assertEquals(
                    List.of(
                            "Bone 0 moves rendered vertices with billboard or parent-inheritance"
                                    + " flags 0x1 that idle evaluation does not implement."),
                    idle.reasons());
            assertEquals(idle.reasons().getFirst(), idle.message());
        } finally {
            service.closeAsync().join();
        }
        // A SKIN that is not stored is unavailable, so the next request reads it again.
        var noSkin =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), modelWithStand(0));
        service = new RacesService(tables, noSkin, null);
        try {
            var missing = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, missing.state(), missing.message());
            assertTrue(
                    missing.message()
                            .startsWith(
                                    "The model's rest surface (SKIN FileDataID 900) could not be read"),
                    missing.message());
            int opens = noSkin.opens.get();
            service.idle(1, 10, Cancellation.NONE).join();
            assertTrue(noSkin.opens.get() > opens, "Unavailable results are reassessed");
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void atMostFourPreparedAnimationsStayCached() throws Exception {
        var tables = idleTables(5);
        var files = new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS");
        byte[] model = modelWithStand(0);
        for (int i = 0; i < 5; i++) {
            files.add(1234 + i, Integer.toString(i + 1).repeat(32), model);
        }
        files.add(900, "a".repeat(32), standSkin());
        var service = new RacesService(tables, files, null);
        try {
            for (int i = 0; i < 5; i++) {
                var idle = service.idle(1, 10 + i, Cancellation.NONE).join();
                assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            }
            // The four most recent are reused without opening anything.
            int opens = files.opens.get();
            for (int i = 4; i >= 1; i--) {
                service.idle(1, 10 + i, Cancellation.NONE).join();
            }
            assertEquals(opens, files.opens.get());
            // The least recently used animation was evicted and is prepared again.
            var again = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, again.state());
            assertTrue(files.opens.get() > opens, "The fifth animation is not kept");
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void aModelWithoutAnLod0SkinIsRefusedAndCached() throws Exception {
        var tables = idleTables();
        byte[] model = modelWithStand(0);
        // The authored SFID chunk starts the file; its first entry names LOD0 SKIN 900.
        var b = java.nio.ByteBuffer.wrap(model).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(900, b.getInt(8));
        b.putInt(8, 0);
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), model);
        var service = new RacesService(tables, files, null);
        try {
            var idle = service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNSUPPORTED, idle.state(), idle.message());
            assertTrue(
                    idle.message().startsWith("The model declares no LOD0 SKIN"), idle.message());
            int opens = files.opens.get();
            service.idle(1, 10, Cancellation.NONE).join();
            assertEquals(opens, files.opens.get());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    /** Counts page and row reads of the wrapped tables; no other behavior changes. */
    private static final class CountingTables implements java.lang.reflect.InvocationHandler {
        final com.vibeminer.formats.tables.BuildTables delegate;
        final java.util.concurrent.atomic.AtomicInteger
                pages = new java.util.concurrent.atomic.AtomicInteger(),
                rows = new java.util.concurrent.atomic.AtomicInteger();
        volatile String failPagesOf;

        /** Every page of this table, filtered or not, is unreadable while set. */
        volatile String failAllPagesOf;

        CountingTables(com.vibeminer.formats.tables.BuildTables delegate) {
            this.delegate = delegate;
        }

        com.vibeminer.formats.tables.BuildTables proxy() {
            return (com.vibeminer.formats.tables.BuildTables)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {com.vibeminer.formats.tables.BuildTables.class},
                            this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            if (method.getName().equals("page")) {
                pages.incrementAndGet();
                var request = (TableTypes.PageRequest) args[0];
                if (request.table().equals(TableTypes.Ref.forName(String.valueOf(failPagesOf)))
                                && !request.filters().isEmpty()
                        || request.table()
                                .equals(TableTypes.Ref.forName(String.valueOf(failAllPagesOf)))) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new TableTypes.Page(
                                    null,
                                    TableTypes.PageState.NOT_READABLE,
                                    List.of(),
                                    null,
                                    null,
                                    0,
                                    "FIXTURE",
                                    "Authored unreadable page"));
                }
            }
            if (method.getName().equals("get")) {
                rows.incrementAndGet();
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    @Test
    void repeatedVoiceCatalogsAreReusedAndUnreadableEntryPagesAreAskedAgain() {
        var raw = open(voiceFixture());
        var counting = new CountingTables(raw);
        var service = new RacesService(counting.proxy());
        try {
            var first = service.voices(1, 2, 0, Cancellation.NONE).join();
            int pages = counting.pages.get();
            var again = service.voices(1, 2, 0, Cancellation.NONE).join();
            assertTrue(again == first, "The repeated catalog is the remembered one");
            assertEquals(pages, counting.pages.get());
            // Playing a clip reuses the catalog too; another sex or class is its own catalog.
            service.voice(1, 2, 0, 12, Cancellation.NONE).join();
            assertEquals(pages, counting.pages.get());
            var other = service.voices(1, 1, 1, Cancellation.NONE).join();
            assertEquals(202, other.clips().getFirst().fileDataId());
            assertTrue(counting.pages.get() > pages);
        } finally {
            service.closeAsync().join();
        }
        counting.failPagesOf = "SoundKitEntry";
        service = new RacesService(counting.proxy());
        try {
            var partial = service.voices(1, 2, 0, Cancellation.NONE).join();
            assertTrue(
                    partial.issues().stream()
                            .anyMatch(issue -> issue.startsWith("Some voice entry rows")),
                    partial.issues().toString());
            int pages = counting.pages.get();
            counting.failPagesOf = null;
            var complete = service.voices(1, 2, 0, Cancellation.NONE).join();
            assertTrue(counting.pages.get() > pages, "An unreadable page is asked again");
            assertEquals(
                    List.of("Charge", "Joke"),
                    complete.clips().stream().map(RaceVoiceTypes.Clip::action).toList());
        } finally {
            service.closeAsync().join();
            raw.closeAsync().join();
        }
    }

    @Test
    void repeatedPreviewsReuseTheSurfaceAndVariantsUntilTheModelChanges() throws Exception {
        var raw = idleTables();
        var counting = new CountingTables(raw);
        byte[] skin = standSkin();
        var files = new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS");
        var service = new RacesService(counting.proxy(), files, null);
        try {
            // A model that is not stored is not remembered.
            var missing = service.preview(1, 0, 10, Cancellation.NONE).join();
            assertEquals(null, missing.mesh());
            files.add(1234, "1".repeat(32), modelWithStand(0)).add(900, "2".repeat(32), skin);
            var first = service.preview(1, 0, 10, Cancellation.NONE).join();
            assertTrue(first.mesh() != null, first.message());
            int opens = files.opens.get(), rows = counting.rows.get();
            assertTrue(service.preview(1, 0, 10, Cancellation.NONE).join() == first);
            assertEquals(
                    List.of(first.variant()),
                    service.modelVariants(1, Cancellation.NONE).join().variants());
            assertEquals(opens, files.opens.get());
            assertEquals(rows, counting.rows.get(), "Variant rows are not looked up again");
            // New model content derives the surface again.
            files.add(1234, "3".repeat(32), modelWithStand(0));
            var renewed = service.preview(1, 0, 10, Cancellation.NONE).join();
            assertTrue(renewed != first);
            assertTrue(files.opens.get() > opens, "Changed model is read again");
        } finally {
            service.closeAsync().join();
            raw.closeAsync().join();
        }
    }

    /** An authored one-triangle appearance; {@code large} holds the 8 Mi texel maximum. */
    private static RaceAppearanceTypes.Result composed(boolean large) {
        var mesh =
                new com.vibeminer.rendering.models.PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        List.of(new com.vibeminer.rendering.models.PreviewMesh.Part(0, 0, 3)));
        int side = large ? 2048 : 2;
        var textures = new ArrayList<com.vibeminer.rendering.models.MeshAppearance.Texture>();
        for (int i = 0; i < (large ? 2 : 1); i++) {
            textures.add(
                    new com.vibeminer.rendering.models.MeshAppearance.Texture(
                            side, side, new int[side * side]));
        }
        var appearance =
                new com.vibeminer.rendering.models.MeshAppearance(
                        mesh, new float[6], List.of(), textures);
        return new RaceAppearanceTypes.Result(
                RaceTypes.State.PARTIAL, appearance, Set.of(0), List.of(), List.of(), false, null);
    }

    private static RaceAppearance.Prepared prepared(String request, Map<Long, String> sources) {
        return new RaceAppearance.Prepared(null, null, null, List.of(), null, request, sources);
    }

    @Test
    void completeAppearancesAreRememberedWhileTheirSourcesKeepTheirContent() {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), new byte[] {1})
                        .add(500, "2".repeat(32), new byte[] {2});
        var service = new RacesService(tables);
        var appearance =
                new RaceAppearance(
                        service,
                        new RaceMedia(service, tables, files, null),
                        tables,
                        files,
                        new com.vibeminer.generated.MemoryGeneratedStore());
        try {
            String request = RaceAppearance.request(1, 2, 1, Map.of());
            var result = composed(false);
            appearance.remember(
                    prepared(request, Map.of(1234L, "1".repeat(32), 500L, "2".repeat(32))), result);
            var hit = appearance.prepare(1, 2, 1, Cancellation.NONE);
            assertTrue(hit.finished() == result, "A repeated request is answered at once");
            // Picks name another request; the remembered default stays separate.
            assertTrue(RaceAppearance.request(1, 2, 1, Map.of(30L, 7L)).endsWith("{30=7}"));
            // A layer with new content is not answered from memory, and is forgotten.
            files.add(500, "3".repeat(32), new byte[] {3});
            var changed = appearance.prepare(1, 2, 1, Cancellation.NONE);
            assertTrue(changed.finished() != result);
            assertEquals(0, appearance.rememberedCount());
            // Results without recorded sources, and failures, are never remembered.
            appearance.remember(prepared(request, Map.of()), result);
            assertEquals(0, appearance.rememberedCount());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void rememberedAppearancesStayWithinTheirCountAndByteBounds() {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), new byte[] {1});
        var service = new RacesService(tables);
        var appearance =
                new RaceAppearance(
                        service, new RaceMedia(service, tables, files, null), tables, files, null);
        try {
            var sources = Map.of(1234L, "1".repeat(32));
            for (int i = 0; i < RaceAppearance.MAX_REMEMBERED + 2; i++) {
                appearance.remember(prepared("r" + i, sources), composed(false));
            }
            assertEquals(RaceAppearance.MAX_REMEMBERED, appearance.rememberedCount());
            // At most four of the largest appearances (32 MiB of texels each) fit the byte bound.
            long large = RaceAppearance.bytes(composed(true).appearance());
            assertEquals(4, RaceAppearance.MAX_REMEMBERED_BYTES / large);
            for (int i = 0; i < RaceAppearance.MAX_REMEMBERED; i++) {
                appearance.remember(prepared("large" + i, sources), composed(true));
            }
            assertEquals(4, appearance.rememberedCount());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    /** A stored composed derivative in the layout {@code RaceAppearance} saves and restores. */
    private static void storeDerivative(
            com.vibeminer.generated.GeneratedStore store,
            com.vibeminer.generated.GeneratedKey key,
            long model,
            long skin,
            String skinContent,
            com.vibeminer.rendering.models.MeshAppearance appearance) {
        storeDerivative(store, key, model, skin, skinContent, appearance, false);
    }

    /** With {@code assumedWeights}, the derivative drew a weight track assumed opaque. */
    private static void storeDerivative(
            com.vibeminer.generated.GeneratedStore store,
            com.vibeminer.generated.GeneratedKey key,
            long model,
            long skin,
            String skinContent,
            com.vibeminer.rendering.models.MeshAppearance appearance,
            boolean assumedWeights) {
        byte[] payload = com.vibeminer.rendering.models.MeshAppearanceCodec.encode(appearance);
        var bytes =
                java.nio.ByteBuffer.allocate(40 + payload.length)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(assumedWeights ? 0x56414332 : 0x56414331)
                        .putLong(model)
                        .putLong(skin)
                        .put(java.util.HexFormat.of().parseHex(skinContent))
                        .putInt(payload.length)
                        .put(payload);
        store.put(List.of(new com.vibeminer.generated.GeneratedTypes.Put(key, bytes.array())))
                .join();
    }

    @Test
    void everySavedHeaderRestoresExactlyItsFacts() {
        // VAC1 plain, VAC2 assumed weights, VAC3/VAC4 the same with a skeleton, and VAC5 to
        // VAC8 each of those leaving out an environment-reflection stage.
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), new byte[] {1})
                        .add(900, "2".repeat(32), new byte[] {2})
                        .add(555, "5".repeat(32), new byte[] {5});
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var service = new RacesService(tables);
        var appearance =
                new RaceAppearance(
                        service,
                        new RaceMedia(service, tables, files, generated),
                        tables,
                        files,
                        generated);
        try {
            var stored = composed(false).appearance();
            var key =
                    com.vibeminer.generated.GeneratedKey.hash(
                            "headers".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var base =
                    new RaceMediaTypes.Preview(
                            RaceTypes.State.PARTIAL,
                            new RaceMediaTypes.Variant(1, 0, 1, 1, 1234),
                            stored.mesh(),
                            Set.of(0),
                            List.of(),
                            true,
                            null);
            var plan =
                    new RaceAppearancePlan.Plan(
                            List.of(new RaceAppearancePlan.Canvas(1, 2, 2)),
                            List.of(
                                    new RaceAppearancePlan.Layer(
                                            1, 1234, 0, 0, 2, 2, 1, 0, "Authored base")),
                            List.of(),
                            RaceAppearanceProfiles.Profile.LEGACY);
            var prepared =
                    new RaceAppearance.Prepared(
                            base,
                            plan,
                            key,
                            List.of(),
                            null,
                            RaceAppearance.request(1, 0, 1, Map.of()),
                            null);
            for (int version = 1; version <= 8; version++) {
                boolean assumed = version % 2 == 0;
                boolean skeletal = version == 3 || version == 4 || version == 7 || version == 8;
                boolean environment = version >= 5;
                byte[] payload = com.vibeminer.rendering.models.MeshAppearanceCodec.encode(stored);
                var bytes =
                        java.nio.ByteBuffer.allocate((skeletal ? 64 : 40) + payload.length)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .putInt(0x56414330 + version)
                                .putLong(1234)
                                .putLong(900)
                                .put(java.util.HexFormat.of().parseHex("2".repeat(32)));
                if (skeletal) {
                    bytes.putLong(555).put(java.util.HexFormat.of().parseHex("5".repeat(32)));
                }
                bytes.putInt(payload.length).put(payload);
                generated.delete(List.of(key)).join();
                generated
                        .put(
                                List.of(
                                        new com.vibeminer.generated.GeneratedTypes.Put(
                                                key, bytes.array())))
                        .join();
                var result = appearance.render(prepared, Cancellation.NONE);
                assertTrue(result.restored(), "VAC" + version + " " + result.message());
                assertEquals(
                        assumed,
                        result.issues().contains(RaceAppearancePlan.ASSUMED_WEIGHTS),
                        "VAC" + version);
                assertEquals(
                        environment,
                        result.issues().contains(RaceAppearancePlan.ENVIRONMENT_NOT_DRAWN),
                        "VAC" + version);
            }
            // Past VAC8 is not a header this code writes: not restored.
            generated.delete(List.of(key)).join();
            byte[] payload = com.vibeminer.rendering.models.MeshAppearanceCodec.encode(stored);
            generated
                    .put(
                            List.of(
                                    new com.vibeminer.generated.GeneratedTypes.Put(
                                            key,
                                            java.nio.ByteBuffer.allocate(40 + payload.length)
                                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                    .putInt(0x56414339)
                                                    .putLong(1234)
                                                    .putLong(900)
                                                    .put(
                                                            java.util.HexFormat.of()
                                                                    .parseHex("2".repeat(32)))
                                                    .putInt(payload.length)
                                                    .put(payload)
                                                    .array())))
                    .join();
            assertFalse(appearance.render(prepared, Cancellation.NONE).restored());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void renderRemembersRestoredAppearancesOnlyWhenTheirSourcesAreSettled() {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), new byte[] {1})
                        .add(900, "2".repeat(32), new byte[] {2});
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var service = new RacesService(tables);
        var appearance =
                new RaceAppearance(
                        service,
                        new RaceMedia(service, tables, files, generated),
                        tables,
                        files,
                        generated);
        try {
            var stored = composed(false).appearance();
            var key =
                    com.vibeminer.generated.GeneratedKey.hash(
                            "authored-derivative"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            storeDerivative(generated, key, 1234, 900, "2".repeat(32), stored);
            var base =
                    new RaceMediaTypes.Preview(
                            RaceTypes.State.PARTIAL,
                            new RaceMediaTypes.Variant(1, 0, 1, 1, 1234),
                            stored.mesh(),
                            Set.of(0),
                            List.of(),
                            true,
                            null);
            var plan =
                    new RaceAppearancePlan.Plan(
                            List.of(new RaceAppearancePlan.Canvas(1, 2, 2)),
                            List.of(
                                    new RaceAppearancePlan.Layer(
                                            1, 1234, 0, 0, 2, 2, 1, 0, "Authored base")),
                            List.of(),
                            RaceAppearanceProfiles.Profile.LEGACY);
            String request = RaceAppearance.request(1, 0, 1, Map.of());
            // A plan that skipped a source it could not read now carries no sources.
            var unsettled =
                    new RaceAppearance.Prepared(base, plan, key, List.of(), null, request, null);
            var shown = appearance.render(unsettled, Cancellation.NONE);
            assertTrue(shown.restored(), shown.message());
            assertEquals(0, appearance.rememberedCount());
            // A settled plan's restored derivative is remembered and answers the next prepare.
            var settled =
                    new RaceAppearance.Prepared(
                            base,
                            plan,
                            key,
                            List.of(),
                            null,
                            request,
                            Map.of(1234L, "1".repeat(32)));
            var restored = appearance.render(settled, Cancellation.NONE);
            assertTrue(restored.restored(), restored.message());
            assertEquals(1, appearance.rememberedCount());
            assertTrue(appearance.prepare(1, 0, 1, Cancellation.NONE).finished() == restored);
            assertFalse(restored.issues().contains(RaceAppearancePlan.ASSUMED_WEIGHTS));
            // A derivative that drew a weight track assumed opaque says so when restored too.
            generated.delete(List.of(key)).join();
            storeDerivative(generated, key, 1234, 900, "2".repeat(32), stored, true);
            var assumed = appearance.render(unsettled, Cancellation.NONE);
            assertTrue(assumed.restored(), assumed.message());
            assertTrue(
                    assumed.issues().contains(RaceAppearancePlan.ASSUMED_WEIGHTS),
                    assumed.issues().toString());
            // A marked derivative for another model or another SKIN is not restored either.
            for (int variant = 0; variant < 2; variant++) {
                generated.delete(List.of(key)).join();
                storeDerivative(
                        generated,
                        key,
                        variant == 0 ? 999 : 1234,
                        900,
                        (variant == 0 ? "2" : "4").repeat(32),
                        stored,
                        true);
                assertFalse(appearance.render(unsettled, Cancellation.NONE).restored());
            }
            // Keys name the weight proof (and a preview's, the assumption): a derivative saved
            // under the key the same identity had before is restored by neither.
            generated.delete(List.of(key)).join();
            storeDerivative(generated, key, 1234, 900, "2".repeat(32), stored);
            for (boolean npc : new boolean[] {false, true}) {
                var renamedKey = RaceAppearance.appearanceKey("authored-derivative", npc);
                assertNotEquals(key, renamedKey);
                var renamed =
                        new RaceAppearance.Prepared(
                                base, plan, renamedKey, List.of(), null, request, null);
                assertFalse(appearance.render(renamed, Cancellation.NONE).restored());
            }
            assertNotEquals(
                    RaceAppearance.appearanceKey("authored-derivative", true),
                    RaceAppearance.appearanceKey("authored-derivative", false));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void everyPairIsComposedExceptThoseReviewedAndRefused() {
        // Composition is attempted for every race and ChrModel pair; the deeper gates decide.
        // No pair is refused at this gate now: Undead compose since their skin is sampled.
        for (long[] pair : new long[][] {{95, 218}, {1, 1}, {22, 43}, {5, 9}, {5, 10}}) {
            assertNull(RaceAppearancePlan.refusedPair(pair[0], pair[1]));
        }
    }

    @Test
    void closingTheHandleDropsEveryRememberedResult() throws Exception {
        var tables = idleTables();
        var files =
                new com.vibeminer.formats.AssetFilesFixture(BUILD, "enUS")
                        .add(1234, "1".repeat(32), modelWithStand(0))
                        .add(900, "2".repeat(32), standSkin());
        var service = new RacesService(tables, files, null);
        try {
            assertTrue(service.preview(1, 0, 10, Cancellation.NONE).join().mesh() != null);
            assertEquals(
                    RaceTypes.State.READY, service.idle(1, 10, Cancellation.NONE).join().state());
            assertTrue(service.retained() > 0);
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
        assertEquals(0, service.retained());
    }
}
