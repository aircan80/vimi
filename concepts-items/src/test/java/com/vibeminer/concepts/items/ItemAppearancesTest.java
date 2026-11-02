package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemAppearancesTest {
    static List<MemoryTables.FakeTable> chain(List<Db2Types.Row> modified) {
        return new ArrayList<>(
                List.of(
                        ItemsWorld.table(
                                10,
                                "ItemModifiedAppearance",
                                ItemsWorld.columns(
                                        "ID",
                                        "ItemID",
                                        "ItemAppearanceModifierID",
                                        "OrderIndex",
                                        "ItemAppearanceID"),
                                modified),
                        ItemsWorld.table(
                                11,
                                "ItemAppearance",
                                ItemsWorld.columns(
                                        "ID", "DefaultIconFileDataID", "ItemDisplayInfoID"),
                                List.of(ItemsWorld.row(200, "200", "0", "300"))),
                        ItemsWorld.table(
                                12,
                                "ItemDisplayInfo",
                                ItemsWorld.columns(
                                        "ID",
                                        "ModelResourcesID[0]",
                                        "ModelResourcesID[1]",
                                        "ModelMaterialResourcesID[0]",
                                        "ModelMaterialResourcesID[1]"),
                                List.of(ItemsWorld.row(300, "300", "400", "0", "500", "0"))),
                        ItemsWorld.table(
                                13,
                                "ModelFileData",
                                ItemsWorld.columns("ID", "ModelResourcesID"),
                                List.of(ItemsWorld.row(600, "600", "400"))),
                        ItemsWorld.table(
                                14,
                                "TextureFileData",
                                ItemsWorld.columns("ID", "MaterialResourcesID"),
                                List.of(ItemsWorld.row(700, "700", "500"))),
                        ItemsWorld.table(
                                15,
                                "ComponentModelFileData",
                                ItemsWorld.columns(
                                        "ID", "RaceID", "GenderIndex", "ClassID", "PositionIndex"),
                                List.of(ItemsWorld.row(600, "600", "1", "2", "3", "4"))),
                        ItemsWorld.table(
                                16,
                                "ComponentTextureFileData",
                                ItemsWorld.columns("ID", "RaceID", "GenderIndex", "ClassID"),
                                List.of(ItemsWorld.row(700, "700", "5", "6", "7")))));
    }

    static ItemAppearanceSet read(List<MemoryTables.FakeTable> seed, ItemsOptions options) {
        var tables =
                new MemoryTables()
                        .add(ItemsWorld.BUILD, "enUS", seed)
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .tables();
        try (tables) {
            return new ItemAppearanceReader(
                            tables,
                            new RelationIndexes(tables, options.maxRelationIndexEntries()),
                            options,
                            ItemsWorld.PRIORITY,
                            ItemsWorld.TOKEN)
                    .read(1);
        }
    }

    @Test
    void completeModifierZeroUsesSourceOrderAndFullResourceComponentProvenance() {
        var set =
                read(
                        chain(List.of(ItemsWorld.row(100, "100", "1", "0", "9", "200"))),
                        new ItemsOptions());
        assertEquals(ItemAppearancesState.COMPLETE, set.state());
        assertEquals(ItemDefaultAppearanceState.DEFAULT, set.defaultState());
        assertEquals(0, set.defaultIndex());
        var appearance = set.rows().getFirst().appearance();
        assertEquals(ItemRefState.FOUND, appearance.state());
        assertEquals(200, appearance.row().row().id());
        var slot = appearance.display().slots().getFirst();
        assertEquals(600, slot.model().files().getFirst().fileDataId());
        assertEquals(4L, slot.model().files().getFirst().component().positionIndex());
        assertEquals(700, slot.material().files().getFirst().fileDataId());
        assertEquals(5L, slot.material().files().getFirst().component().raceId());
        assertEquals(
                ItemResourceState.NO_RESOURCE, appearance.display().slots().get(1).model().state());
    }

    @Test
    void ambiguousDefaultsAndNoDefaultsAreHonest() {
        var base =
                chain(
                        List.of(
                                ItemsWorld.row(100, "100", "1", "0", "0", "200"),
                                ItemsWorld.row(101, "101", "1", "0", "1", "200")));
        assertEquals(
                ItemDefaultAppearanceState.AMBIGUOUS,
                read(base, new ItemsOptions()).defaultState());
        assertEquals(
                ItemDefaultAppearanceState.NONE,
                read(
                                chain(List.of(ItemsWorld.row(100, "100", "1", "8", "0", "200"))),
                                new ItemsOptions())
                        .defaultState());
    }

    @Test
    void partialOrTruncatedRowsNeverInventDefault() {
        var seed =
                chain(
                        List.of(
                                ItemsWorld.row(100, "100", "1", "0", "0", "200"),
                                ItemsWorld.row(101, "101", "1", "2", "1", "200")));
        var table = seed.getFirst();
        seed.set(
                0,
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                10,
                                table.entry().name(),
                                TableTypes.State.PARTIAL,
                                true,
                                null,
                                table.entry().counts(),
                                "partial"),
                        table.columns(),
                        table.rows(),
                        null,
                        Map.of(102L, "SECRET"),
                        true));
        var partial = read(seed, new ItemsOptions());
        assertEquals(ItemAppearancesState.INCOMPLETE, partial.state());
        assertEquals(ItemDefaultAppearanceState.UNKNOWN, partial.defaultState());
        var capped = read(chain(table.rows()), new ItemsOptions(20000, 200000, 4000000, 1, 32, 8));
        assertEquals(ItemAppearancesState.ROW_LIMIT, capped.state());
        assertEquals(ItemDefaultAppearanceState.UNKNOWN, capped.defaultState());
        assertEquals(2, capped.matches());
    }

    @Test
    void invalidAppearanceKeyIsTypedAndLimitsRefuseIndexes() {
        var invalid =
                read(
                        chain(List.of(ItemsWorld.row(100, "100", "1", "0", "0", "4294967296"))),
                        new ItemsOptions());
        assertEquals(ItemRefState.INVALID_VALUE, invalid.rows().getFirst().appearance().state());
        var limited =
                read(
                        chain(
                                List.of(
                                        ItemsWorld.row(100, "100", "1", "0", "0", "200"),
                                        ItemsWorld.row(101, "101", "1", "1", "1", "200"))),
                        new ItemsOptions(20000, 200000, 1, 16, 32, 8));
        assertEquals(ItemAppearancesState.INDEX_LIMIT, limited.state());
    }

    static MemoryTables.FakeTable armor(
            TableTypes.State state, List<Db2Types.Row> rows, boolean verified) {
        var base =
                ItemsWorld.table(
                        17,
                        "ItemDisplayInfoMaterialRes",
                        ItemsWorld.columns(
                                "ID",
                                "ComponentSection",
                                "ItemDisplayInfoID",
                                "MaterialResourcesID"),
                        rows,
                        state,
                        Map.of());
        var fields =
                List.of(
                        schema("ID", 32, null),
                        schema("ComponentSection", 8, null),
                        schema(
                                "ItemDisplayInfoID",
                                32,
                                new TableSchema.ForeignKey("ItemDisplayInfo", "ID")),
                        schema(
                                "MaterialResourcesID",
                                32,
                                new TableSchema.ForeignKey(
                                        "TextureFileData", "MaterialResourcesID")));
        var schema =
                new TableSchema(
                        fields,
                        List.of("12345678"),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.EN_US,
                        "verified DBD");
        return new MemoryTables.FakeTable(
                base.entry(),
                base.columns(),
                base.rows(),
                verified ? schema : null,
                Map.of(),
                true);
    }

    static TableSchema.SchemaColumn schema(String name, int bits, TableSchema.ForeignKey fk) {
        return new TableSchema.SchemaColumn(
                name,
                TableSchema.ColumnKind.INT,
                bits,
                true,
                1,
                fk,
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                WowLocale.EN_US,
                null,
                null);
    }

    @Test
    void armorMaterialsUseVerifiedForeignKeysAndRawSectionAndComponentProvenance() {
        var seed = chain(List.of(ItemsWorld.row(100, "100", "1", "0", "0", "200")));
        seed.add(
                armor(
                        TableTypes.State.READY,
                        List.of(ItemsWorld.row(800, "800", "11", "300", "500")),
                        true));
        var armor =
                read(seed, new ItemsOptions())
                        .rows()
                        .getFirst()
                        .appearance()
                        .display()
                        .armorMaterials();
        assertEquals(ItemArmorMaterialsState.COMPLETE, armor.state());
        assertEquals(11L, armor.rows().getFirst().componentSection());
        assertEquals(800, armor.rows().getFirst().row().row().id());
        assertEquals(500L, armor.rows().getFirst().materialResourcesId());
        assertEquals(-1, armor.rows().getFirst().material().slot());
        assertEquals(700, armor.rows().getFirst().material().files().getFirst().fileDataId());
    }

    @Test
    void armorRequiresEvidenceAndPreservesPartialAndProtection() {
        var seed = chain(List.of(ItemsWorld.row(100, "100", "1", "0", "0", "200")));
        seed.add(armor(TableTypes.State.READY, List.of(), false));
        assertEquals(
                ItemArmorMaterialsState.UNSUPPORTED,
                read(seed, new ItemsOptions())
                        .rows()
                        .getFirst()
                        .appearance()
                        .display()
                        .armorMaterials()
                        .state());
        seed.set(
                seed.size() - 1,
                armor(
                        TableTypes.State.PARTIAL,
                        List.of(ItemsWorld.row(800, "800", "9", "300", "500")),
                        true));
        var component = seed.get(6);
        seed.set(
                6,
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                16,
                                component.entry().name(),
                                TableTypes.State.PARTIAL,
                                true,
                                null,
                                component.entry().counts(),
                                "partial"),
                        component.columns(),
                        List.of(),
                        null,
                        Map.of(700L, "PROTECTED"),
                        true));
        var armor =
                read(seed, new ItemsOptions())
                        .rows()
                        .getFirst()
                        .appearance()
                        .display()
                        .armorMaterials();
        assertEquals(ItemArmorMaterialsState.INCOMPLETE, armor.state());
        assertEquals(
                ItemRefState.PROTECTED,
                armor.rows().getFirst().material().files().getFirst().component().state());
        assertEquals(
                "PROTECTED",
                armor.rows().getFirst().material().files().getFirst().component().protectedKey());
    }

    @Test
    void armorRowsAndResourceFilesAreBounded() {
        var seed = chain(List.of(ItemsWorld.row(100, "100", "1", "0", "0", "200")));
        seed.add(
                armor(
                        TableTypes.State.READY,
                        List.of(
                                ItemsWorld.row(800, "800", "9", "300", "500"),
                                ItemsWorld.row(801, "801", "10", "300", "500")),
                        true));
        var armor =
                read(seed, new ItemsOptions(20000, 200000, 4000000, 16, 1, 8))
                        .rows()
                        .getFirst()
                        .appearance()
                        .display()
                        .armorMaterials();
        assertEquals(ItemArmorMaterialsState.ROW_LIMIT, armor.state());
        assertEquals(2, armor.matches());
        assertEquals(1, armor.rows().size());
    }
}
