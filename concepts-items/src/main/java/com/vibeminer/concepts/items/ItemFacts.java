package com.vibeminer.concepts.items;

import com.vibeminer.formats.db2.Db2Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed source-cell projection. Values are never scaled, inferred, replaced or computed. */
public final class ItemFacts {
    public static final String POLICY_REVISION = "item-facts-v1",
            LABEL_REVISION = "item-labels-v1",
            LIMIT_CODE = "ITEMS_FACTS_LIMIT";
    public static final int MAXIMUM_VALUES = 16;

    public record Key(String key, List<String> fields) {
        public Key {
            fields = List.copyOf(fields);
        }
    }

    public static final List<Key> KEYS =
            List.of(
                    new Key("description", List.of("Description_lang")),
                    new Key("display1", List.of("Display1_lang")),
                    new Key("display2", List.of("Display2_lang")),
                    new Key("display3", List.of("Display3_lang")),
                    new Key("icon", List.of("IconFileDataID")),
                    new Key("quality", List.of("OverallQualityID")),
                    new Key("itemLevel", List.of("ItemLevel")),
                    new Key("itemSquishEra", List.of("ItemSquishEraID")),
                    new Key("contentTuning", List.of("ContentTuningID")),
                    new Key("playerLevelCurve", List.of("PlayerLevelToItemLevelCurveID")),
                    new Key("itemLevelOffsetCurve", List.of("ItemLevelOffsetCurveID")),
                    new Key("itemLevelOffset", List.of("ItemLevelOffsetItemLevel")),
                    new Key("class", List.of("ClassID")),
                    new Key("subclass", List.of("SubclassID")),
                    new Key("inventoryType", List.of("InventoryType")),
                    new Key("requiredLevel", List.of("RequiredLevel")),
                    new Key("requiredSkill", List.of("RequiredSkill")),
                    new Key("requiredSkillRank", List.of("RequiredSkillRank")),
                    new Key("requiredAbility", List.of("RequiredAbility")),
                    new Key("minFaction", List.of("MinFactionID")),
                    new Key("minReputation", List.of("MinReputation")),
                    new Key("allowableClass", List.of("AllowableClass")),
                    new Key("allowableRaces", List.of("AllowableRaces", "AllowableRace")),
                    new Key("bonding", List.of("Bonding")),
                    new Key("stackable", List.of("Stackable")),
                    new Key("maxCount", List.of("MaxCount")),
                    new Key("containerSlots", List.of("ContainerSlots")),
                    new Key("buyPrice", List.of("BuyPrice")),
                    new Key("sellPrice", List.of("SellPrice")),
                    new Key("vendorStackCount", List.of("VendorStackCount")),
                    new Key("durationInInventory", List.of("DurationInInventory")),
                    new Key("statType", List.of("StatModifier_bonusStat")),
                    new Key("statAmount", List.of("StatModifier_bonusAmount")),
                    new Key("statPercentEditor", List.of("StatPercentEditor")),
                    new Key("statSocketShare", List.of("StatPercentageOfSocket")),
                    new Key("socketType", List.of("SocketType")),
                    new Key(
                            "socketBonus",
                            List.of("Socket_match_enchantment_ID", "Socket_match_enchantment_id")),
                    new Key("gemProperties", List.of("Gem_properties", "GemProperties")),
                    new Key("damageType", List.of("DamageType")),
                    new Key("itemDelay", List.of("ItemDelay")),
                    new Key("dmgVariance", List.of("DmgVariance")),
                    new Key("itemRange", List.of("ItemRange")),
                    new Key("flags", List.of("Flags")),
                    new Key("zoneBound", List.of("ZoneBound")),
                    new Key("instanceBound", List.of("InstanceBound")),
                    new Key("requiredHoliday", List.of("RequiredHoliday")),
                    new Key("priceVariance", List.of("PriceVariance")),
                    new Key("priceRandomValue", List.of("PriceRandomValue")));

    private ItemFacts() {}

    private static List<ItemLabel> labels(String... names) {
        var list = new ArrayList<ItemLabel>();
        for (int i = 0; i < names.length; i++) {
            list.add(new ItemLabel(Integer.toString(i), names[i]));
        }
        return List.copyOf(list);
    }

    public static final ItemLabelLists LABEL_LISTS =
            new ItemLabelLists(
                    LABEL_REVISION,
                    labels(
                            "Poor",
                            "Common",
                            "Uncommon",
                            "Rare",
                            "Epic",
                            "Legendary",
                            "Artifact",
                            "Heirloom",
                            "WoW Token"),
                    labels(
                            "Not equippable",
                            "Head",
                            "Neck",
                            "Shoulder",
                            "Shirt",
                            "Chest",
                            "Waist",
                            "Legs",
                            "Feet",
                            "Wrist",
                            "Hands",
                            "Finger",
                            "Trinket",
                            "One-hand",
                            "Shield",
                            "Ranged",
                            "Back",
                            "Two-hand",
                            "Bag",
                            "Tabard",
                            "Robe",
                            "Main hand",
                            "Off hand",
                            "Held in off-hand",
                            "Ammo",
                            "Thrown",
                            "Ranged right",
                            "Quiver",
                            "Relic",
                            "Profession tool",
                            "Profession gear"),
                    labels(
                            "No binding",
                            "Binds when picked up",
                            "Binds when equipped",
                            "Binds when used",
                            "Quest item"),
                    labels("On use", "On equip", "Chance on hit"));

    private static Map<String, String> map(List<ItemLabel> labels) {
        var result = new LinkedHashMap<String, String>();
        for (var label : labels) {
            result.put(label.value(), label.label());
        }
        return Map.copyOf(result);
    }

    public static final Map<String, String> QUALITY = map(LABEL_LISTS.quality()),
            INVENTORY_TYPE = map(LABEL_LISTS.inventoryType()),
            BONDING = map(LABEL_LISTS.bonding()),
            TRIGGER = map(LABEL_LISTS.trigger());

    private static String label(String key, String value) {
        return switch (key) {
            case "quality" -> QUALITY.get(value);
            case "inventoryType" -> INVENTORY_TYPE.get(value);
            case "bonding" -> BONDING.get(value);
            default -> null;
        };
    }

    public static ItemSparseState sparseState(ItemNameState state) {
        return switch (state) {
            case MISSING_SPARSE -> ItemSparseState.MISSING;
            case SPARSE_PROTECTED -> ItemSparseState.PROTECTED;
            case SPARSE_UNKNOWN -> ItemSparseState.UNKNOWN;
            default -> ItemSparseState.PRESENT;
        };
    }

    public static ItemFactSet project(
            List<Db2Types.Column> itemColumns,
            List<Db2Types.Column> sparseColumns,
            Db2Types.Row item,
            Db2Types.Row sparse,
            ItemSparseState sparseState) {
        if ((sparse == null) != (sparseState != ItemSparseState.PRESENT)
                || sparse != null && sparseColumns == null) {
            throw new IllegalArgumentException("Sparse row and state disagree");
        }
        if (item.values().size() != itemColumns.size()
                || sparse != null && sparse.values().size() != sparseColumns.size()) {
            return new ItemFactSet(
                    ItemFactsState.SOURCE_INVALID,
                    sparseState,
                    List.of(),
                    ItemsSourceException.SOURCE_INVALID,
                    "Source row differs from header");
        }
        var facts = new ArrayList<ItemFact>();
        for (var key : KEYS) {
            var left = cells(itemColumns, item, ItemLayout.ITEM, key);
            var sparseFields = sparseColumns == null ? null : columns(sparseColumns, key.fields());
            var right =
                    sparse == null
                            ? List.<ItemFactValue>of()
                            : cells(sparseColumns, sparse, ItemLayout.SPARSE, key);
            if (left.size() + right.size() > MAXIMUM_VALUES) {
                return new ItemFactSet(
                        ItemFactsState.LIMIT_EXCEEDED,
                        sparseState,
                        List.of(),
                        LIMIT_CODE,
                        "Fact source cells exceed bound");
            }
            ItemFactState state;
            if (left.isEmpty() && sparseFields != null && sparseFields.isEmpty()) {
                state = ItemFactState.UNSUPPORTED;
            } else if (left.isEmpty() && sparse == null) {
                state =
                        switch (sparseState) {
                            case MISSING -> ItemFactState.MISSING;
                            case PROTECTED -> ItemFactState.PROTECTED;
                            default -> ItemFactState.UNKNOWN;
                        };
            } else if (invalid(item, left) || sparse != null && invalid(sparse, right)) {
                state = ItemFactState.INVALID_TEXT;
            } else {
                state = ItemFactState.VALUE;
            }
            var agreement =
                    left.isEmpty() || right.isEmpty()
                            ? ItemFactAgreement.SINGLE
                            : left.size() != right.size()
                                    ? ItemFactAgreement.SEPARATE
                                    : left.stream()
                                                    .map(ItemFactValue::value)
                                                    .toList()
                                                    .equals(
                                                            right.stream()
                                                                    .map(ItemFactValue::value)
                                                                    .toList())
                                            ? ItemFactAgreement.AGREE
                                            : ItemFactAgreement.DISAGREE;
            var values = new ArrayList<ItemFactValue>();
            if (state == ItemFactState.VALUE || state == ItemFactState.INVALID_TEXT) {
                values.addAll(left);
                values.addAll(right);
            }
            facts.add(new ItemFact(key.key(), state, agreement, values));
        }
        return new ItemFactSet(ItemFactsState.READY, sparseState, facts, null, null);
    }

    static List<String> fieldNames(String key) {
        return KEYS.stream()
                .filter(value -> value.key().equals(key))
                .findFirst()
                .map(Key::fields)
                .orElse(List.of());
    }

    static List<Integer> columns(List<Db2Types.Column> columns, List<String> fields) {
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            int bracket = name.indexOf('[');
            if (fields.contains(bracket < 0 ? name : name.substring(0, bracket))) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static List<ItemFactValue> cells(
            List<Db2Types.Column> columns, Db2Types.Row row, String table, Key key) {
        var result = new ArrayList<ItemFactValue>();
        for (int index : columns(columns, key.fields())) {
            boolean bad = ItemLayout.issue(row, index);
            String value = bad ? "" : ItemLayout.cell(row, index);
            result.add(
                    new ItemFactValue(
                            table,
                            columns.get(index).name(),
                            index,
                            row.id(),
                            value,
                            bad ? null : label(key.key(), value)));
        }
        return result;
    }

    private static boolean invalid(Db2Types.Row row, List<ItemFactValue> cells) {
        return cells.stream().anyMatch(cell -> ItemLayout.issue(row, cell.columnIndex()));
    }
}
