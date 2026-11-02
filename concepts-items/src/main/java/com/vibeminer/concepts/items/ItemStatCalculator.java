package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.Files;
import com.vibeminer.formats.gametables.GameTableText;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base-source item attributes. Unknown scaling inputs are explicit; no GUI or media dependencies.
 */
final class ItemStatCalculator {
    static final String POLICY = "base-item-budget-v1";
    private final BuildTables tables;
    private final Files files;
    private final GeneratedStore generated;
    private final String inputs;
    private final Map<String, GameTableText> gameTables = new HashMap<>();
    private final List<String> issues = new ArrayList<>();
    private boolean prepared;

    ItemStatCalculator(BuildTables tables, Files files, GeneratedStore generated, String inputs) {
        this.tables = tables;
        this.files = files;
        this.generated = generated;
        this.inputs = inputs;
    }

    synchronized List<String> prepare(Cancellation token) {
        if (prepared) {
            return List.copyOf(issues);
        }
        var budget =
                tables.schema(
                                TableTypes.Ref.forName("RandPropPoints"),
                                TableTypes.Priority.BACKGROUND,
                                token)
                        .join();
        if (!TableQueries.isReadable(budget.table().state())) {
            issues.add("RandPropPoints: " + budget.table().state());
            prepared = true;
            return List.copyOf(issues);
        }
        var opened = files.forBuild(tables.build(), tables.locale(), token).join();
        if (opened.files() == null) {
            issues.add("Stat GameTables: " + opened.state());
            prepared = true;
            return List.copyOf(issues);
        }
        try (var handle = opened.files()) {
            for (String name :
                    List.of(
                            "StaminaMultByILvl",
                            "CombatRatingsMultByILvl",
                            "ItemSocketCostPerLevel")) {
                token.check();
                String path = "GameTables/" + name + ".txt";
                var key =
                        GeneratedKey.hash(
                                (POLICY
                                                + "\n"
                                                + inputs
                                                + "\n"
                                                + tables.build()
                                                + "\n"
                                                + tables.locale()
                                                + "\n"
                                                + path)
                                        .getBytes(StandardCharsets.UTF_8));
                byte[] bytes = null;
                var storedToken = new com.vibeminer.generated.Cancellation();
                var registration = token.onCancel(storedToken::cancel);
                try {
                    if (generated != null) {
                        var cached = generated.read(key, storedToken).join();
                        if (cached.state() == GeneratedTypes.ReadState.PRESENT) {
                            bytes = cached.bytes();
                        } else if (cached.state() == GeneratedTypes.ReadState.CORRUPT) {
                            generated.delete(List.of(key), storedToken).join();
                        }
                    }
                    if (bytes == null) {
                        var read = handle.get(FileTypes.FileRef.forPath(path), token).join();
                        if (read.state() != FileTypes.FileReadState.COMPLETE
                                || read.bytes() == null) {
                            issues.add(name + ": " + read.state());
                            continue;
                        }
                        bytes = read.bytes();
                    }
                    try {
                        var parsed = GameTableText.parse(bytes);
                        gameTables.put(name, parsed);
                        if (generated != null) {
                            generated
                                    .put(List.of(new GeneratedTypes.Put(key, bytes)), storedToken)
                                    .join();
                        }
                    } catch (IllegalArgumentException invalid) {
                        issues.add(name + ": unsupported GameTable text");
                    }
                } finally {
                    registration.close();
                }
            }
        }
        token.check();
        prepared = true;
        return List.copyOf(issues);
    }

    synchronized ItemAttributes calculate(
            ItemResult result, TableTypes.Priority priority, Cancellation token) {
        if (result.detail() == null) {
            return unavailable("Item details are " + result.state());
        }
        var direct = result.detail().attributes();
        if (direct.state() != ItemAttributes.State.REQUIRES_CALCULATION) {
            return direct;
        }
        var facts = result.detail().facts();
        try {
            var allocationFact = fact(facts, "statPercentEditor");
            if (allocationFact != null
                    && allocationFact.state() == ItemFactState.VALUE
                    && allocationFact.values().stream()
                            .allMatch(value -> value.value().equals("0"))) {
                return new ItemAttributes(ItemAttributes.State.READY, List.of(), List.of());
            }
            long level = scalar(facts, "itemLevel"),
                    quality = scalar(facts, "quality"),
                    slot = scalar(facts, "inventoryType"),
                    subclass = scalar(facts, "subclass");
            for (String key :
                    List.of(
                            "playerLevelCurve",
                            "itemLevelOffsetCurve",
                            "itemLevelOffset",
                            "itemSquishEra")) {
                var fact = fact(facts, key);
                if (fact != null
                        && fact.state() == ItemFactState.VALUE
                        && scalar(facts, key) != 0) {
                    return unavailable(
                            "Base attributes require unresolved scaling context: " + key);
                }
            }
            int bucket = bucket(slot, subclass);
            if (bucket < 0) {
                return unavailable("Unsupported item budget slot");
            }
            String family =
                    switch ((int) quality) {
                        case 2 -> "GoodF";
                        case 3, 7 -> "SuperiorF";
                        case 4, 5, 6 -> "EpicF";
                        default -> null;
                    };
            if (family == null) {
                return unavailable("No reviewed budget for this item quality");
            }
            var schema =
                    tables.schema(TableTypes.Ref.forName("RandPropPoints"), priority, token).join();
            if (!TableQueries.isReadable(schema.table().state()) || schema.columns() == null) {
                return unavailable("RandPropPoints is " + schema.table().state());
            }
            var budgetRow =
                    tables.get(TableTypes.Ref.forName("RandPropPoints"), level, priority, token)
                            .join();
            if (budgetRow.outcome() != TableTypes.RowOutcome.FOUND || budgetRow.row() == null) {
                return unavailable("No readable stat budget at base item level " + level);
            }
            String budgetColumn = family + "[" + bucket + "]";
            int column = -1;
            for (int i = 0; i < schema.columns().size(); i++) {
                if (schema.columns().get(i).name().equals(budgetColumn)
                        && schema.columns().get(i).kind().equals("float")
                        && schema.columns().get(i).interpretation() == null) {
                    column = i;
                }
            }
            if (column < 0 || column >= budgetRow.row().row().values().size()) {
                return unavailable("Unsupported stat budget schema");
            }
            String budgetText = budgetRow.row().row().values().get(column);
            double budget = Float.parseFloat(budgetText);
            if (!Double.isFinite(budget) || budget <= 0) {
                return unavailable("Invalid or empty item budget");
            }
            var types = slots(facts, "statType");
            var allocations = slots(facts, "statPercentEditor");
            var sockets = slots(facts, "statSocketShare");
            var attributes = new ArrayList<ItemAttributes.Attribute>();
            var problems = new ArrayList<String>();
            for (var entry : types.entrySet()) {
                token.check();
                int index = entry.getKey();
                var type = entry.getValue();
                long id = Long.parseLong(type.value());
                if (id == -1) {
                    continue;
                }
                var allocation = allocations.get(index);
                var socket = sockets.get(index);
                if (allocation == null
                        || socket == null
                        || !allocation.table().equals(type.table())
                        || allocation.rowId() != type.rowId()
                        || !socket.table().equals(type.table())
                        || socket.rowId() != type.rowId()) {
                    problems.add("Unpaired stat allocation in slot " + index);
                    continue;
                }
                long weight = Long.parseLong(allocation.value());
                if (weight == 0) {
                    continue;
                }
                String name = ItemAttributes.nameOf(id);
                if (name == null) {
                    problems.add("Unknown stat type " + id);
                    continue;
                }
                if (weight < 0) {
                    problems.add("Negative allocation unsupported in slot " + index);
                    continue;
                }
                double socketShare = Float.parseFloat(socket.value());
                if (!Double.isFinite(socketShare) || socketShare < 0) {
                    problems.add("Invalid socket share in slot " + index);
                    continue;
                }
                var sources = new ArrayList<ItemFactValue>();
                sources.add(type);
                sources.add(allocation);
                sources.add(socket);
                sources.add(
                        new ItemFactValue(
                                "RandPropPoints", budgetColumn, column, level, budgetText, null));
                double penalty = 0;
                if (socketShare != 0) {
                    var cost = cell("ItemSocketCostPerLevel", level, "SocketCost");
                    if (cost == null) {
                        problems.add("Socket budget unavailable for " + name);
                        continue;
                    }
                    sources.add(source("ItemSocketCostPerLevel", cost));
                    penalty = Math.rint(socketShare * cost.value());
                }
                double value = weight * budget * 0.0001 - penalty;
                if (id == 7 || rating(id)) {
                    String table = id == 7 ? "StaminaMultByILvl" : "CombatRatingsMultByILvl";
                    String category = category(slot);
                    if (category == null) {
                        problems.add("Unreviewed multiplier category for " + name);
                        continue;
                    }
                    var multiplier = cell(table, level, category + "Multiplier", category);
                    if (multiplier == null || multiplier.value() < 0) {
                        problems.add(table + " input unavailable for " + name);
                        continue;
                    }
                    sources.add(source(table, multiplier));
                    if (multiplier.value() != 0) {
                        value *= multiplier.value();
                    }
                }
                if (!Double.isFinite(value) || Math.abs(value) > Integer.MAX_VALUE) {
                    problems.add("Stat amount outside supported bounds");
                    continue;
                }
                long rounded = Math.round(value);
                if (rounded == 0) {
                    continue;
                }
                attributes.add(
                        new ItemAttributes.Attribute(
                                index,
                                id,
                                name,
                                BigInteger.valueOf(rounded),
                                (rounded > 0 ? "+" : "") + rounded + " " + name,
                                sources));
            }
            return new ItemAttributes(
                    problems.isEmpty() ? ItemAttributes.State.READY : ItemAttributes.State.PARTIAL,
                    attributes,
                    problems);
        } catch (IllegalArgumentException invalid) {
            return unavailable("Stat inputs unavailable or invalid: " + invalid.getMessage());
        }
    }

    private GameTableText.Cell cell(String table, long row, String... names) {
        var source = gameTables.get(table);
        return source == null ? null : source.cell(row, names);
    }

    private static ItemFactValue source(String table, GameTableText.Cell cell) {
        return new ItemFactValue(
                "GameTables/" + table + ".txt",
                cell.column(),
                cell.index(),
                cell.row(),
                cell.text(),
                null);
    }

    private static ItemAttributes unavailable(String message) {
        return new ItemAttributes(
                ItemAttributes.State.REQUIRES_CALCULATION, List.of(), List.of(message));
    }

    private static ItemFact fact(ItemFactSet facts, String key) {
        return facts == null
                ? null
                : facts.facts().stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }

    private static long scalar(ItemFactSet facts, String key) {
        var fact = fact(facts, key);
        if (fact == null || fact.state() != ItemFactState.VALUE) {
            throw new IllegalArgumentException(key);
        }
        var values = fact.values().stream().map(ItemFactValue::value).distinct().toList();
        if (values.size() != 1) {
            throw new IllegalArgumentException("Ambiguous " + key);
        }
        return Long.parseLong(values.getFirst());
    }

    private static Map<Integer, ItemFactValue> slots(ItemFactSet facts, String key) {
        var fact = fact(facts, key);
        if (fact == null || fact.state() != ItemFactState.VALUE) {
            throw new IllegalArgumentException(key);
        }
        var result = new java.util.LinkedHashMap<Integer, ItemFactValue>();
        for (var value : fact.values()) {
            int bracket = value.column().indexOf('['),
                    slot =
                            bracket < 0
                                    ? 0
                                    : Integer.parseInt(
                                            value.column()
                                                    .substring(
                                                            bracket + 1,
                                                            value.column().length() - 1));
            if (result.putIfAbsent(slot, value) != null) {
                throw new IllegalArgumentException("Ambiguous " + key + " slot");
            }
        }
        return result;
    }

    private static boolean rating(long id) {
        return id >= 12 && id <= 37 || id == 49;
    }

    private static int bucket(long slot, long subclass) {
        return switch ((int) slot) {
            case 1, 4, 5, 7, 15, 17, 20, 25 -> 0;
            case 3, 6, 8, 10, 12 -> 1;
            case 2, 9, 11, 14, 16, 23 -> 2;
            case 13, 21, 22 -> 3;
            case 26 -> subclass == 19 ? 3 : 0;
            case 28 -> 4;
            default -> -1;
        };
    }

    private static String category(long slot) {
        return switch ((int) slot) {
            case 2, 11 -> "Jewelry";
            case 12 -> "Trinket";
            case 13, 15, 17, 21, 22, 25, 26 -> "Weapon";
            case 14, 23 -> null;
            default -> "Armor";
        };
    }
}
