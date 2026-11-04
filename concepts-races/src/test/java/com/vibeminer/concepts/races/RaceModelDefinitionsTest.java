package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.races.RaceMediaTypes.Definition;
import com.vibeminer.concepts.races.RaceMediaTypes.DefinitionState;
import com.vibeminer.concepts.races.RaceMediaTypes.Quality;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Authored ChrRaces layouts only; no game assets or real build records. */
final class RaceModelDefinitionsTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final String DISPLAYS = "CreatureDisplayInfo";

    private record Field(String name, String target, boolean verified) {}

    private static Field reference(String name, String target) {
        return new Field(name, target, true);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            columns.add(new Db2Types.Column(field.name(), "integer", false, null, null));
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            TableSchema.ColumnKind.INT,
                            32,
                            true,
                            1,
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

    private static final List<Field> PAIR =
            List.of(
                    reference("MaleDisplayID", DISPLAYS),
                    reference("FemaleDisplayID", DISPLAYS),
                    reference("HighResMaleDisplayID", DISPLAYS),
                    reference("HighResFemaleDisplayID", DISPLAYS));

    /**
     * Race 1 has male model 10 (display 20) and female model 11 (display 21); {@code races} is an
     * optional ChrRaces fixture.
     */
    private static List<RaceMediaTypes.Variant> variants(MemoryTables.FakeTable races) {
        var media = new ArrayList<MemoryTables.FakeTable>();
        media.add(
                table(
                        101,
                        "ChrRaceXChrModel",
                        List.of(
                                reference("ChrRacesID", "ChrRaces"),
                                reference("ChrModelID", "ChrModel")),
                        List.of(row(1, "1", "10"), row(2, "1", "11"))));
        media.add(
                table(
                        102,
                        "ChrModel",
                        List.of(reference("DisplayID", DISPLAYS), new Field("Sex", null, true)),
                        List.of(row(10, "20", "0"), row(11, "21", "1"))));
        media.add(
                table(
                        103,
                        DISPLAYS,
                        List.of(reference("ModelID", "CreatureModelData")),
                        List.of(row(20, "30"), row(21, "31"))));
        media.add(
                table(
                        104,
                        "CreatureModelData",
                        List.of(new Field("FileDataID", null, true)),
                        List.of(row(30, "1234"), row(31, "1235"))));
        if (races != null) {
            media.add(races);
        }
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", media)
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var result = service.modelVariants(1, Cancellation.NONE).join();
            // Definition refusals never discard or degrade the recorded variants.
            assertEquals(RaceTypes.State.READY, result.state(), result.issues().toString());
            assertEquals(2, result.variants().size());
            assertEquals(20, result.variants().get(0).displayId());
            assertEquals(21, result.variants().get(1).displayId());
            // Each preview reassesses the definition from the same sources.
            for (var variant : result.variants()) {
                var preview = service.preview(1, 1, variant.chrModelId(), Cancellation.NONE).join();
                assertEquals(variant, preview.variant());
            }
            return result.variants();
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static MemoryTables.FakeTable races(List<Field> fields, Db2Types.Row... rows) {
        return table(105, "ChrRaces", fields, List.of(rows));
    }

    private static void refused(Definition definition, DefinitionState state, String reason) {
        assertEquals(state, definition.state(), definition.message());
        assertNull(definition.quality());
        assertNull(definition.source());
        assertTrue(definition.message().contains(reason), definition.message());
        assertTrue(definition.label().startsWith("not stated · "));
    }

    @Test
    void explicitPairNamesTheRecordedHighResolutionAndStandardDisplaysWithTheirSourceField() {
        var found = variants(races(PAIR, row(1, "22", "21", "20", "23")));
        var male = found.get(0).definition();
        assertEquals(DefinitionState.SELECTED, male.state());
        assertEquals(Quality.HD, male.quality());
        assertEquals("ChrRaces #1 HighResMaleDisplayID", male.source());
        assertEquals(22, male.standardDisplayId());
        assertEquals(20, male.highDisplayId());
        assertEquals("HD · ChrRaces #1 HighResMaleDisplayID", male.label());
        assertEquals("Male · HD", found.get(0).label());
        var female = found.get(1).definition();
        assertEquals(Quality.SD, female.quality());
        assertEquals("ChrRaces #1 FemaleDisplayID", female.source());
        assertEquals(23, female.highDisplayId());
        assertEquals("Female · SD", found.get(1).label());
    }

    @Test
    void layoutWithoutPairFieldsStatesNothingInsteadOfGuessing() {
        var found = variants(races(List.of(new Field("Flags", null, true)), row(1, "0")));
        for (var variant : found) {
            refused(variant.definition(), DefinitionState.UNSUPPORTED_PROFILE, "no SD/HD");
            assertEquals(variant.sex() == 0 ? "Male" : "Female", variant.label());
        }
    }

    @Test
    void singleFieldOrUnverifiedFieldIsNotAPair() {
        var single =
                variants(
                        races(
                                List.of(
                                        reference("MaleDisplayID", DISPLAYS),
                                        reference("FemaleDisplayID", DISPLAYS)),
                                row(1, "20", "21")));
        refused(
                single.get(0).definition(),
                DefinitionState.UNSUPPORTED_PROFILE,
                "records only MaleDisplayID");
        var unlinked =
                variants(
                        races(
                                List.of(
                                        reference("MaleDisplayID", DISPLAYS),
                                        reference("FemaleDisplayID", DISPLAYS),
                                        new Field("HighResMaleDisplayID", null, true),
                                        reference("HighResFemaleDisplayID", DISPLAYS)),
                                row(1, "22", "21", "20", "23")));
        refused(
                unlinked.get(0).definition(),
                DefinitionState.UNSUPPORTED_PROFILE,
                "Unverified relationship HighResMaleDisplayID");
        assertEquals(DefinitionState.SELECTED, unlinked.get(1).definition().state());
        var unnamed =
                variants(
                        races(
                                List.of(
                                        reference("MaleDisplayID", DISPLAYS),
                                        reference("FemaleDisplayID", DISPLAYS),
                                        new Field("HighResMaleDisplayID", DISPLAYS, false),
                                        reference("HighResFemaleDisplayID", DISPLAYS)),
                                row(1, "22", "21", "20", "23")));
        refused(
                unnamed.get(0).definition(),
                DefinitionState.UNSUPPORTED_PROFILE,
                "Unsupported field HighResMaleDisplayID");
    }

    @Test
    void zeroLinkIsNoRecordedPairAndKeepsTheRecordedSide() {
        var found = variants(races(PAIR, row(1, "20", "21", "0", "0")));
        var male = found.get(0).definition();
        refused(male, DefinitionState.NO_RECORDED_PAIR, "no HighResMaleDisplayID");
        assertEquals(20, male.standardDisplayId());
        assertEquals(0, male.highDisplayId());
    }

    @Test
    void sameDisplayInBothFieldsIsAmbiguous() {
        var found = variants(races(PAIR, row(1, "20", "21", "20", "24")));
        refused(found.get(0).definition(), DefinitionState.AMBIGUOUS, "cannot be distinguished");
        assertEquals(DefinitionState.SELECTED, found.get(1).definition().state());
    }

    @Test
    void displayOutsideAnAmbiguousPairIsUnmatchedNotAmbiguous() {
        var found = variants(races(PAIR, row(1, "40", "41", "40", "41")));
        refused(found.get(0).definition(), DefinitionState.UNMATCHED, "matches neither");
        refused(found.get(1).definition(), DefinitionState.UNMATCHED, "matches neither");
    }

    @Test
    void displayIdOutsideTheUnsignedRangeIsUnreadableNotAMissingPair() {
        var found = variants(races(PAIR, row(1, "20", "21", "4294967296", "23")));
        refused(found.get(0).definition(), DefinitionState.UNREADABLE, "32-bit range");
        assertEquals(0, found.get(0).definition().highDisplayId());
    }

    @Test
    void displayOutsideTheRecordedPairIsUnmatched() {
        var found = variants(races(PAIR, row(1, "40", "41", "42", "43")));
        refused(found.get(0).definition(), DefinitionState.UNMATCHED, "matches neither");
        refused(found.get(1).definition(), DefinitionState.UNMATCHED, "(41)");
    }

    @Test
    void missingTableOrRaceRowIsUnreadableAndRetryable() {
        for (var variant : variants(null)) {
            refused(variant.definition(), DefinitionState.UNREADABLE, "ChrRaces is unavailable");
        }
        var missing = variants(races(PAIR, row(2, "20", "21", "22", "23")));
        refused(missing.get(0).definition(), DefinitionState.UNREADABLE, "ChrRaces #1");
        var cell = variants(races(PAIR, row(1, "20", "21", "x", "23")));
        refused(cell.get(0).definition(), DefinitionState.UNREADABLE, "HighResMaleDisplayID");
    }

    @Test
    void definitionRecordRejectsAQualityWithoutItsSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Definition(DefinitionState.SELECTED, Quality.HD, null, 1, 2, "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Definition(DefinitionState.AMBIGUOUS, Quality.SD, "s", 1, 1, "x"));
        assertEquals(
                DefinitionState.UNSUPPORTED_PROFILE,
                new RaceMediaTypes.Variant(1, 0, 1, 1, 1).definition().state());
    }
}
