package com.vibeminer.concepts.talents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Synthetic Forever TraitTree fixtures; layout hashes are the reviewed profile's. */
final class TraitTreesTest {
    private static final BuildRef FOREVER =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, boolean text, int count) {}

    private static Field number(String name) {
        return new Field(name, false, 1);
    }

    private static Field text(String name) {
        return new Field(name, true, 1);
    }

    private static Db2Types.Row row(long id, Object... values) {
        var strings = new ArrayList<String>();
        for (var value : values) {
            strings.add(String.valueOf(value));
        }
        return new Db2Types.Row(id, false, null, strings, List.of());
    }

    private static MemoryTables.FakeTable table(
            long id,
            String name,
            List<Field> fields,
            List<Db2Types.Row> rows,
            String layout,
            boolean verified,
            TableTypes.State state) {
        var columns = new ArrayList<Db2Types.Column>();
        var definitions = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            definitions.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.text()
                                    ? TableSchema.ColumnKind.LOCSTRING
                                    : TableSchema.ColumnKind.INT,
                            field.text() ? null : 32,
                            field.text() ? null : true,
                            field.count(),
                            null,
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            verified,
                            WowLocale.parse("enUS"),
                            null,
                            null));
            for (int i = 0; i < field.count(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.count() == 1 ? field.name() : field.name() + "[" + i + "]",
                                field.text() ? "string" : "integer",
                                false,
                                null,
                                null));
            }
        }
        var entry =
                new TableTypes.Entry(
                        id,
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "fixture", layout),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        definitions,
                        List.of(layout),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static MemoryTables.FakeTable verified(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        return table(id, name, fields, rows, "12345678", true, TableTypes.State.READY);
    }

    private static MemoryTables.FakeTable trait(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        return table(
                id,
                name,
                fields,
                rows,
                TraitTrees.REVIEWED_LAYOUTS.get(name),
                false,
                TableTypes.State.READY);
    }

    private static Db2Types.Row legacyTalent(long id, long tab, int tier, int column, long spell) {
        var values = new ArrayList<Object>(List.of(tab, tier, column));
        for (int i = 0; i < 9; i++) {
            values.add(i == 0 ? spell : 0);
        }
        values.addAll(List.of(0, 0, 0, 0, 0, 0));
        return row(id, values.toArray());
    }

    /** Node ID, x, y, spell, ranks. Entry = node + 10000 and definition = node + 20000. */
    private static final long[][] NODES = {
        {100, 1620, 2130, 1000, 5},
        {101, 1620, 2730, 1001, 3},
        {102, 1030, 3330, 1002, 1},
        {103, 102800, 5740, 1003, 2},
        {104, 1625, 2130, 1004, 1},
        {200, 5020, 2130, 2000, 5},
        {201, 5020, 2730, 2001, 2},
        {202, 5620, 2130, 2002, 1},
        {300, 9680, 2130, 3000, 5},
        {301, 9680, 2730, 3001, 1}
    };

    private static List<MemoryTables.FakeTable> fixture() {
        var nodes = new ArrayList<Db2Types.Row>();
        var links = new ArrayList<Db2Types.Row>();
        var entries = new ArrayList<Db2Types.Row>();
        var definitions = new ArrayList<Db2Types.Row>();
        var names = new ArrayList<Db2Types.Row>();
        for (var node : NODES) {
            nodes.add(row(node[0], 500, node[1], node[2], 0, 0));
            links.add(row(node[0] + 50000, node[0], node[0] + 10000, 0));
            entries.add(row(node[0] + 10000, node[0] + 20000, node[4]));
            definitions.add(row(node[0] + 20000, "", "", node[3], 0, 0));
            names.add(row(node[3], "Spell name " + node[3]));
        }
        // A choice node with two entries is reported, not guessed.
        links.add(row(60202, 202, 10203, 1));
        // Definition 20300 overrides the spell's name, description and icon.
        definitions.set(8, row(20300, "Override name", "Override text", 3000, 777, 42));
        // A different tree's node shares no group, edges or class.
        nodes.add(row(900, 501, 1020, 2130, 0, 0));
        var groups = List.of(row(1, 500, 0), row(2, 500, 0), row(3, 500, 0), row(4, 500, 0));
        var groupNodes = new ArrayList<Db2Types.Row>();
        long link = 1;
        for (long node : List.of(100L, 101L, 102L, 103L, 104L)) {
            groupNodes.add(row(link++, 1, node, 0));
        }
        groupNodes.add(row(link++, 4, 100, 0));
        for (long node : List.of(200L, 201L, 202L)) {
            groupNodes.add(row(link++, 2, node, 0));
        }
        for (long node : List.of(300L, 301L)) {
            groupNodes.add(row(link++, 3, node, 0));
        }
        var edges =
                List.of(
                        row(1, 1, 100, 101, 2),
                        row(2, 1, 200, 201, 2),
                        row(3, 1, 201, 200, 2),
                        row(4, 1, 201, 200, 0),
                        row(5, 1, 300, 301, 3));
        return new ArrayList<>(
                List.of(
                        verified(
                                1,
                                "ChrClasses",
                                List.of(text("Name_lang"), number("IconFileDataID")),
                                List.of(row(1, "Warrior", 100), row(2, "Paladin", 101))),
                        verified(
                                2,
                                "TalentTab",
                                List.of(
                                        text("Name_lang"),
                                        number("ClassMask"),
                                        number("OrderIndex"),
                                        text("BackgroundFile")),
                                List.of(
                                        row(10, "Fury", 1, 0, "WarriorFury"),
                                        row(11, "Arms", 1, 1, "WarriorArms"),
                                        row(12, "Protection", 1, 2, "WarriorProtection"))),
                        verified(
                                3,
                                "Talent",
                                List.of(
                                        number("TabID"),
                                        number("TierID"),
                                        number("ColumnIndex"),
                                        new Field("SpellRank", false, 9),
                                        new Field("PrereqTalent", false, 3),
                                        new Field("PrereqRank", false, 3)),
                                List.of(
                                        legacyTalent(1, 11, 0, 0, 1000),
                                        legacyTalent(2, 11, 0, 1, 1001),
                                        legacyTalent(3, 11, 1, 0, 9999),
                                        legacyTalent(4, 10, 0, 0, 2000),
                                        legacyTalent(5, 12, 0, 0, 3001))),
                        verified(4, "SpellName", List.of(text("Name_lang")), names),
                        verified(
                                5,
                                "Spell",
                                List.of(text("Description_lang")),
                                List.of(row(1000, "Raw description"))),
                        verified(
                                6,
                                "SpellMisc",
                                List.of(
                                        number("SpellID"),
                                        number("DifficultyID"),
                                        number("SpellIconFileDataID")),
                                List.of(row(1, 1000, 0, 900), row(2, 3000, 0, 901))),
                        verified(
                                7,
                                "SkillRaceClassInfo",
                                List.of(number("SkillID"), number("ClassMask")),
                                List.of(row(1, 26, 1), row(2, 26, 1), row(3, 27, 3))),
                        trait(
                                8,
                                "SkillLineXTraitTree",
                                List.of(
                                        number("SkillLineID"),
                                        number("TraitTreeID"),
                                        number("Variant")),
                                List.of(row(165, 26, 500, 0), row(166, 27, 501, 0))),
                        trait(
                                9,
                                "TraitNode",
                                List.of(
                                        number("TraitTreeID"),
                                        number("PosX"),
                                        number("PosY"),
                                        number("Type"),
                                        number("Flags")),
                                nodes),
                        trait(
                                10,
                                "TraitNodeXTraitNodeEntry",
                                List.of(
                                        number("TraitNodeID"),
                                        number("TraitNodeEntryID"),
                                        number("Index")),
                                links),
                        trait(
                                11,
                                "TraitNodeEntry",
                                List.of(number("TraitDefinitionID"), number("MaxRanks")),
                                entries),
                        trait(
                                12,
                                "TraitDefinition",
                                List.of(
                                        text("OverrideName_lang"),
                                        text("OverrideDescription_lang"),
                                        number("SpellID"),
                                        number("OverrideIcon"),
                                        number("OverridesSpellID")),
                                definitions),
                        trait(
                                13,
                                "TraitNodeGroup",
                                List.of(number("TraitTreeID"), number("Flags")),
                                groups),
                        trait(
                                14,
                                "TraitNodeGroupXTraitNode",
                                List.of(
                                        number("TraitNodeGroupID"),
                                        number("TraitNodeID"),
                                        number("Index")),
                                groupNodes),
                        trait(
                                15,
                                "TraitEdge",
                                List.of(
                                        number("VisualStyle"),
                                        number("LeftTraitNodeID"),
                                        number("RightTraitNodeID"),
                                        number("Type")),
                                edges)));
    }

    private static com.vibeminer.formats.tables.BuildTables handle(
            BuildRef build, List<MemoryTables.FakeTable> tables) {
        return new MemoryTables()
                .add(build, "enUS", tables)
                .forBuild(build, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static TalentTypes.ClassTrees warrior(List<MemoryTables.FakeTable> tables) {
        try (var handle = handle(FOREVER, tables);
                var service = new TalentsService(handle, "1.60.1.70755")) {
            return service.trees(1, Cancellation.NONE).join();
        }
    }

    private static List<MemoryTables.FakeTable> replace(
            List<MemoryTables.FakeTable> tables,
            String name,
            UnaryOperator<MemoryTables.FakeTable> change) {
        var result = new ArrayList<MemoryTables.FakeTable>();
        for (var table : tables) {
            result.add(table.entry().name().equals(name) ? change.apply(table) : table);
        }
        return result;
    }

    private static TalentTypes.Talent talent(TalentTypes.Tree tree, long id) {
        return tree.talents().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
    }

    @Test
    void foreverTraitTreeReplacesLegacyRowsWithRecordedNodesRanksAndEdges() {
        try (var handle = handle(FOREVER, fixture());
                var service = new TalentsService(handle, "1.60.1.70755")) {
            var catalog = service.catalog(Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, catalog.state());
            assertEquals(
                    List.of("Warrior"),
                    catalog.classes().stream().map(TalentTypes.PlayerClass::name).toList());
            var result = service.trees(1, Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, result.state());
            assertEquals(TalentTypes.Model.TRAIT_TREE, result.model());
            // Names follow shared spells, not legacy OrderIndex; trees follow recorded X order.
            assertEquals(
                    List.of("Arms", "Fury", "Protection"),
                    result.trees().stream().map(TalentTypes.Tree::name).toList());
            assertEquals(
                    List.of(1L, 2L, 3L),
                    result.trees().stream().map(TalentTypes.Tree::id).toList());
            var arms = result.trees().getFirst();
            assertFalse(
                    arms.talents().stream().anyMatch(t -> t.rankSpells().contains(9999L)),
                    "stale legacy rows are not read for Forever");
            var first = talent(arms, 100);
            assertEquals(0, first.row());
            assertEquals(1, first.column());
            assertEquals(List.of(1000L), first.rankSpells());
            assertEquals(5, first.maxRanks());
            assertEquals("Spell name 1000", first.name());
            assertEquals("Raw description", first.description());
            assertEquals(900L, first.iconFileDataId());
            assertEquals(10100L, first.origin().traitNodeEntryId());
            assertEquals(1620L, first.origin().rawPosX());
            var second = talent(arms, 101);
            assertEquals(1, second.row());
            assertEquals(
                    List.of(
                            new TalentTypes.Prerequisite(
                                    100, null, TalentTypes.PrerequisiteKind.TRAIT_SUFFICIENT)),
                    second.prerequisites());
            var nudged = talent(arms, 102);
            assertEquals(2, nudged.row());
            assertEquals(0, nudged.column());
            assertEquals(
                    List.of(103L, 104L),
                    arms.unplaced().stream().map(u -> u.talent().id()).toList());
            assertTrue(arms.unplaced().get(0).reason().contains("102800, 5740"));
            assertTrue(arms.unplaced().get(1).reason().contains("TraitNode 100"));

            var fury = result.trees().get(1);
            assertEquals(List.of(200L, 201L), fury.talents().stream().map(t -> t.id()).toList());
            assertEquals(List.of(), talent(fury, 200).prerequisites(), "upward reverse link");
            assertEquals(
                    List.of(200L),
                    talent(fury, 201).prerequisites().stream()
                            .map(TalentTypes.Prerequisite::talentId)
                            .toList());
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("choice or tiered")));
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("both directions")));

            var protection = result.trees().get(2);
            var overridden = talent(protection, 300);
            assertEquals("Override name", overridden.name());
            assertEquals("Override text", overridden.description());
            assertEquals(777L, overridden.iconFileDataId());
            assertEquals(42L, overridden.origin().overridesSpellId());
            assertTrue(overridden.origin().descriptionOverridden());
            assertEquals(
                    TalentTypes.PrerequisiteKind.TRAIT_REQUIRED,
                    talent(protection, 301).prerequisites().getFirst().kind());
            assertNull(talent(protection, 301).prerequisites().getFirst().rankIndex());

            assertEquals(
                    TalentTypes.State.UNAVAILABLE,
                    service.trees(2, Cancellation.NONE).join().state());
        }
    }

    @Test
    void otherClassicProductsAndForeverBuildsWithoutTraitTablesKeepTheLegacyReader() {
        var era = new BuildRef("wow_classic_era", "a".repeat(32), "b".repeat(32), "us");
        try (var handle = handle(era, fixture());
                var service = new TalentsService(handle, "1.15.7.60000")) {
            var result = service.trees(1, Cancellation.NONE).join();
            assertEquals(TalentTypes.Model.LEGACY_TALENT, result.model());
        }
        var legacyOnly =
                fixture().stream()
                        .filter(t -> !t.entry().name().equals("SkillLineXTraitTree"))
                        .toList();
        var result = warrior(legacyOnly);
        assertEquals(TalentTypes.Model.LEGACY_TALENT, result.model());
    }

    @Test
    void unreadableOrUnreviewedTraitTablesFailWithoutLegacySubstitution() {
        var encrypted =
                warrior(
                        replace(
                                fixture(),
                                "SkillLineXTraitTree",
                                t ->
                                        table(
                                                8,
                                                t.entry().name(),
                                                List.of(
                                                        number("SkillLineID"),
                                                        number("TraitTreeID"),
                                                        number("Variant")),
                                                t.rows(),
                                                t.entry().counts().layoutHash(),
                                                false,
                                                TableTypes.State.ENCRYPTED)));
        assertEquals(TalentTypes.State.UNAVAILABLE, encrypted.state());
        assertEquals("TALENTS_TRAIT_TABLE_ENCRYPTED", encrypted.code());

        var unreviewed =
                warrior(
                        replace(
                                fixture(),
                                "TraitNode",
                                t ->
                                        table(
                                                9,
                                                t.entry().name(),
                                                List.of(
                                                        number("TraitTreeID"),
                                                        number("PosX"),
                                                        number("PosY"),
                                                        number("Type"),
                                                        number("Flags")),
                                                t.rows(),
                                                "0BADF00D",
                                                false,
                                                TableTypes.State.READY)));
        assertEquals(TalentTypes.State.UNSUPPORTED, unreviewed.state());
        assertEquals("TALENTS_TRAIT_LAYOUT_UNREVIEWED", unreviewed.code());
        assertTrue(unreviewed.trees().isEmpty());
    }

    @Test
    void unmatchedLegacyTabsGiveNeutralNamesInsteadOfGuessing() {
        var result =
                warrior(
                        replace(
                                fixture(),
                                "Talent",
                                t ->
                                        verified(
                                                3,
                                                "Talent",
                                                List.of(
                                                        number("TabID"),
                                                        number("TierID"),
                                                        number("ColumnIndex"),
                                                        new Field("SpellRank", false, 9),
                                                        new Field("PrereqTalent", false, 3),
                                                        new Field("PrereqRank", false, 3)),
                                                List.of(legacyTalent(1, 11, 0, 0, 1000)))));
        assertEquals(
                List.of("Talent tree 1", "Talent tree 2", "Talent tree 3"),
                result.trees().stream().map(TalentTypes.Tree::name).toList());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("neutral names")));
    }

    @Test
    void overlappingTreeGroupsAreNotTurnedIntoALayout() {
        var tables =
                replace(
                        fixture(),
                        "TraitNodeGroupXTraitNode",
                        t -> {
                            var rows = new ArrayList<>(t.rows());
                            rows.add(row(999, 2, 100, 0));
                            return trait(
                                    14,
                                    t.entry().name(),
                                    List.of(
                                            number("TraitNodeGroupID"),
                                            number("TraitNodeID"),
                                            number("Index")),
                                    rows);
                        });
        var result = warrior(tables);
        assertEquals(TalentTypes.State.UNSUPPORTED, result.state());
        assertEquals("TALENTS_TRAIT_PARTITION_UNSUPPORTED", result.code());
    }

    @Test
    void gridPlacementIgnoresParkedOutliersAndRejectsOffGridOffsets() {
        assertEquals(1020L, TraitTrees.origin(List.of(102800L, 1020L, 1620L, 2820L), 4));
        assertEquals(0, TraitTrees.cell(10, 4));
        assertEquals(3, TraitTrees.cell(1800, 4));
        assertNull(TraitTrees.cell(2400, 4));
        assertNull(TraitTrees.cell(300, 4));
        assertNull(TraitTrees.cell(-600, 4));
    }
}
