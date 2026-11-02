package com.vibeminer.concepts.items;

import static com.vibeminer.concepts.items.ItemsWorld.BUILD;
import static com.vibeminer.concepts.items.ItemsWorld.PRIORITY;
import static com.vibeminer.concepts.items.ItemsWorld.TOKEN;
import static com.vibeminer.concepts.items.ItemsWorld.row;
import static com.vibeminer.concepts.items.ItemsWorld.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemStatCalculatorTest {
    private static ItemFactValue value(String column, String text) {
        return new ItemFactValue("ItemSparse", column, 0, 100, text, null);
    }

    private static ItemFact fact(String key, ItemFactValue... values) {
        return new ItemFact(key, ItemFactState.VALUE, ItemFactAgreement.SINGLE, List.of(values));
    }

    private static ItemResult result(String socket, String curve) {
        var facts =
                new ItemFactSet(
                        ItemFactsState.READY,
                        ItemSparseState.PRESENT,
                        List.of(
                                fact("itemLevel", value("ItemLevel", "60")),
                                fact("quality", value("OverallQualityID", "4")),
                                fact("inventoryType", value("InventoryType", "1")),
                                fact("subclass", value("SubclassID", "4")),
                                fact("playerLevelCurve", value("PlayerLevelCurveID", curve)),
                                fact(
                                        "statType",
                                        value("StatModifier_bonusStat[2]", "32"),
                                        value("StatModifier_bonusStat[0]", "7"),
                                        value("StatModifier_bonusStat[1]", "4")),
                                new ItemFact(
                                        "statAmount",
                                        ItemFactState.UNSUPPORTED,
                                        ItemFactAgreement.SINGLE,
                                        List.of()),
                                fact(
                                        "statPercentEditor",
                                        value("StatPercentEditor[0]", "5000"),
                                        value("StatPercentEditor[1]", "2500"),
                                        value("StatPercentEditor[2]", "7500")),
                                fact(
                                        "statSocketShare",
                                        value("StatPercentageOfSocket[0]", socket),
                                        value("StatPercentageOfSocket[1]", "0"),
                                        value("StatPercentageOfSocket[2]", "0"))),
                        null,
                        null);
        return new ItemResult(
                ItemResultState.FOUND,
                new ItemDetail(null, null, null, null, facts, null, List.of(), null),
                null,
                null,
                null,
                null,
                null);
    }

    private static MemoryTables data() {
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                table(
                                        1,
                                        "RandPropPoints",
                                        List.of(
                                                new Db2Types.Column(
                                                        "EpicF[0]", "float", false, null, null)),
                                        List.of(row(60, "40")))));
    }

    private static MemoryFiles files() {
        var files = new MemoryFiles();
        int id = 1;
        for (String name :
                List.of("StaminaMultByILvl", "CombatRatingsMultByILvl", "ItemSocketCostPerLevel")) {
            String text =
                    name.equals("ItemSocketCostPerLevel")
                            ? "Level SocketCost\n60 3\n"
                            : "Level ArmorMultiplier WeaponMultiplier TrinketMultiplier JewelryMultiplier\n60 "
                                    + (name.equals("StaminaMultByILvl") ? "2" : "1.5")
                                    + " 1 1 1\n";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            files.add(
                    BUILD,
                    "enUS",
                    new FileTypes.BuildFile(
                            id++,
                            "GameTables/" + name + ".txt",
                            null,
                            (long) bytes.length,
                            "a".repeat(32),
                            null,
                            null,
                            FileTypes.FileStorage.STORED,
                            false),
                    bytes,
                    List.of());
        }
        return files;
    }

    @Test
    void calculatesBudgetMultipliersAndBankersSocketPenaltyInLayerSix() {
        try (var tables = data().forBuild(BUILD, "enUS", TOKEN).join().tables()) {
            var calculator =
                    new ItemStatCalculator(tables, files(), new MemoryGeneratedStore(), "fixture");
            assertTrue(calculator.prepare(TOKEN).isEmpty());
            var attributes = calculator.calculate(result("0.5", "0"), PRIORITY, TOKEN);
            assertEquals(ItemAttributes.State.READY, attributes.state());
            assertEquals(
                    List.of("+45 Critical Strike Rating", "+36 Stamina", "+10 Strength"),
                    attributes.attributes().stream().map(ItemAttributes.Attribute::text).toList());
            assertTrue(
                    attributes.attributes().get(1).sources().stream()
                            .anyMatch(
                                    source ->
                                            source.table().equals("RandPropPoints")
                                                    && source.rowId() == 60));
            assertTrue(
                    attributes.attributes().get(1).sources().stream()
                            .anyMatch(
                                    source ->
                                            source.table()
                                                    .equals(
                                                            "GameTables/ItemSocketCostPerLevel.txt")));
            assertEquals(attributes, calculator.calculate(result("0.5", "0"), PRIORITY, TOKEN));
        }
    }

    @Test
    void missingInputsAndScalingStayExplicitRatherThanInventingAmounts() {
        try (var tables = data().forBuild(BUILD, "enUS", TOKEN).join().tables()) {
            var calculator = new ItemStatCalculator(tables, new MemoryFiles(), null, "fixture");
            assertFalse(calculator.prepare(TOKEN).isEmpty());
            var partial = calculator.calculate(result("0", "0"), PRIORITY, TOKEN);
            assertEquals(ItemAttributes.State.PARTIAL, partial.state());
            assertEquals(
                    List.of("+10 Strength"),
                    partial.attributes().stream().map(ItemAttributes.Attribute::text).toList());
            assertEquals(
                    ItemAttributes.State.REQUIRES_CALCULATION,
                    calculator.calculate(result("0", "123"), PRIORITY, TOKEN).state());
        }
    }
}
