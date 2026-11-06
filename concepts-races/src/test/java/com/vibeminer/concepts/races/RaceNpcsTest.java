package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.junit.jupiter.api.Test;

/** Humanoid NPC appearance resolution over authored in-memory tables (#385). No game data. */
final class RaceNpcsTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, String target) {}

    private static Field number(String name) {
        return new Field(name, null);
    }

    private static Field reference(String name, String target) {
        return new Field(name, target);
    }

    private static Db2Types.Row row(long id, long... values) {
        var text = new ArrayList<String>();
        for (long value : values) {
            text.add(Long.toString(value));
        }
        return new Db2Types.Row(id, false, null, text, List.of());
    }

    /** The reviewed layout of each table (RaceAppearance.supports). */
    private static String layout(String name) {
        return switch (name) {
            case "CreatureDisplayInfoExtra" -> "4D9FE25C";
            case "CreatureDisplayInfoOption" -> "2F331C33";
            case "NPCModelItemSlotDisplayInfo" -> "C2057F5B";
            case "TextureFileData" -> "BD7C74C2";
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        String layout = layout(name);
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
                            true,
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

    /**
     * NPC appearance 50: race 1, sex 0, class 1, bake material 900 (file 9000); choices option 11
     * -> 111 and 12 -> 121; items 1 (slot 4 -> display 400) and 2 (slot 10 -> display 410).
     * Appearance 51 has no bake; 52 records two choices for one option; 53 two files for its bake.
     */
    private static List<MemoryTables.FakeTable> tables() {
        return List.of(
                table(
                        1,
                        "CreatureDisplayInfoExtra",
                        List.of(
                                reference("DisplayRaceID", "ChrRaces"),
                                number("DisplaySexID"),
                                number("DisplayClassID"),
                                number("BakeMaterialResourcesID")),
                        List.of(
                                row(50, 1, 0, 1, 900),
                                row(51, 1, 0, 1, 0),
                                row(52, 1, 0, 1, 900),
                                row(53, 1, 1, 1, 901),
                                row(54, 1, 1, 1, 900))),
                table(
                        2,
                        "CreatureDisplayInfoOption",
                        List.of(
                                reference("ChrCustomizationOptionID", "ChrCustomizationOption"),
                                reference("ChrCustomizationChoiceID", "ChrCustomizationChoice"),
                                reference(
                                        "CreatureDisplayInfoExtraID", "CreatureDisplayInfoExtra")),
                        List.of(
                                row(1, 11, 111, 50),
                                row(2, 12, 121, 50),
                                row(3, 11, 111, 52),
                                row(4, 11, 112, 52))),
                table(
                        3,
                        "NPCModelItemSlotDisplayInfo",
                        List.of(
                                reference("ItemDisplayInfoID", "ItemDisplayInfo"),
                                number("ItemSlot"),
                                reference("NpcModelID", "CreatureDisplayInfoExtra")),
                        List.of(
                                row(1, 400, 4, 50),
                                row(2, 410, 10, 50),
                                row(3, 420, 300, 50),
                                row(4, 430, 6, 50),
                                row(5, 431, 6, 50))),
                table(
                        4,
                        "TextureFileData",
                        // The row ID is the file; UsageType 0 is the diffuse bake (9001 is not).
                        List.of(number("UsageType"), number("MaterialResourcesID")),
                        List.of(
                                row(9000, 0, 900),
                                row(9001, 2, 900),
                                row(9010, 0, 901),
                                row(9011, 0, 901))));
    }

    private static final RaceMediaTypes.Variant MALE =
            new RaceMediaTypes.Variant(1, 0, 49, 149, 1490);

    private static RaceNpcs.Resolution resolve(long id, List<RaceMediaTypes.Variant> variants) {
        try (var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", tables())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new RacesService(tables);
            return RaceNpcs.resolve(
                    service,
                    service.relations,
                    id,
                    race -> variants,
                    Cancellation.NONE,
                    new ArrayList<>());
        }
    }

    @Test
    void manyNpcsShareOneIndexPerRelationInsteadOfScanningPerNpc() {
        try (var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", tables())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new RacesService(tables);
            for (long id : new long[] {50, 52, 50}) {
                RaceNpcs.resolve(
                        service,
                        service.relations,
                        id,
                        race -> List.of(MALE),
                        Cancellation.NONE,
                        new ArrayList<>());
            }
            // Options, item slots and texture files: one index each, built once.
            assertEquals(3, service.relations.builds());
        }
    }

    @Test
    void anNpcResolvesToItsModelChoicesItemsAndPreBakedTexture() {
        var resolution = resolve(50, List.of(MALE));
        assertNull(resolution.refusal());
        var spec = resolution.spec();
        assertEquals(1, spec.raceId());
        assertEquals(0, spec.sex());
        assertEquals(1, spec.classId());
        assertEquals(MALE, spec.variant());
        assertEquals(Map.of(11L, 111L, 12L, 121L), spec.choices());
        assertEquals(List.of(new RaceNpcs.Item(4, 400), new RaceNpcs.Item(10, 410)), spec.items());
        assertEquals(900, spec.bakeMaterialResourcesId());
        assertEquals(9000, spec.bakeFileDataId());
        // Slot 300 is outside the profile and slot 6 records two items: left out and counted.
        assertEquals(2, spec.omittedItems());
    }

    @Test
    void onlyAMissingTextureModelOrChoiceIsAStatedRefusal() {
        assertEquals(
                "the extended display info is not recorded", resolve(99, List.of(MALE)).refusal());
        assertEquals("there is no pre-baked NPC texture", resolve(51, List.of(MALE)).refusal());
        assertEquals("the customization choices are unknown", resolve(52, List.of(MALE)).refusal());
        assertEquals(
                "there is no race model for the recorded race and sex",
                resolve(53, List.of(MALE)).refusal());
        var female = new RaceMediaTypes.Variant(2, 1, 59, 159, 1590);
        assertEquals(
                "there is no single pre-baked NPC texture", resolve(53, List.of(female)).refusal());
        assertEquals(
                "there is no single race model for the recorded sex",
                resolve(54, List.of(female, new RaceMediaTypes.Variant(3, 1, 69, 169, 1690)))
                        .refusal());
    }
}
