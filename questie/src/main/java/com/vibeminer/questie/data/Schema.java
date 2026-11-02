package com.vibeminer.questie.data;

import com.vibeminer.questie.QuestieTypes.EntityKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reviewed against QuestieDB a37877c1490027ee48e5722bf602adf34f95a6dc src/meta. */
final class Schema {
    static final String PROFILE = "questiedb-cbor-v2-4-schema-1";
    final EntityKind kind;
    final String[] names;
    final String[] types;
    final Map<Integer, Long> constants = new LinkedHashMap<>();
    final Map<Integer, String> unknownNames = new LinkedHashMap<>();
    final Map<Integer, String> unknownTypes = new LinkedHashMap<>();

    Schema(EntityKind kind, String declaration) {
        this.kind = kind;
        names =
                switch (kind) {
                    case ITEM ->
                            "name npcDrops objectDrops itemDrops startQuest questRewards flags foodType itemLevel requiredLevel ammoType class subClass vendors relatedQuests teachesSpell"
                                    .split(" ");
                    case NPC ->
                            "name minLevelHealth maxLevelHealth minLevel maxLevel rank spawns waypoints zoneID questStarts questEnds factionID friendlyToFaction subName npcFlags"
                                    .split(" ");
                    case OBJECT ->
                            "name questStarts questEnds spawns zoneID factionID waypoints"
                                    .split(" ");
                    case QUEST ->
                            "name startedBy finishedBy requiredLevel questLevel requiredRaces requiredClasses objectivesText triggerEnd objectives sourceItemId preQuestGroup preQuestSingle childQuests inGroupWith exclusiveTo zoneOrSort requiredSkill requiredMinRep requiredMaxRep requiredSourceItems nextQuestInChain questFlags specialFlags parentQuest reputationReward breadcrumbForQuestId breadcrumbs extraObjectives requiredSpell requiredSpecialization requiredMaxLevel availableUntilCompleted availableStartingWith requiredRanks disabledByQuest"
                                    .split(" ");
                };
        Set<Integer> tables =
                switch (kind) {
                    case ITEM -> Set.of(2, 3, 4, 6, 14, 15);
                    case NPC -> Set.of(7, 8, 10, 11);
                    case OBJECT -> Set.of(2, 3, 4, 7);
                    case QUEST ->
                            Set.of(
                                    2, 3, 8, 9, 10, 12, 13, 14, 15, 16, 18, 19, 20, 21, 26, 28, 29,
                                    35);
                };
        Set<Integer> strings =
                switch (kind) {
                    case NPC -> Set.of(1, 13, 14);
                    default -> Set.of(1);
                };
        types = new String[names.length];
        for (int i = 1; i <= names.length; i++) {
            types[i - 1] = tables.contains(i) ? "table" : strings.contains(i) ? "string" : "number";
        }
        Map<Integer, String> declaredNames = declaration(declaration, "names");
        Map<Integer, String> declaredTypes = declaration(declaration, "types");
        for (int i = 1; i <= names.length; i++) {
            if (!names[i - 1].equals(declaredNames.get(i))
                    || !types[i - 1].equals(declaredTypes.get(i))) {
                throw new IllegalArgumentException("Unsupported schema: " + kind + " field " + i);
            }
        }
        for (var entry : declaredNames.entrySet()) {
            int index = entry.getKey();
            if (index > names.length) {
                if (index > 52
                        || !Set.of("table", "string", "number")
                                .contains(declaredTypes.get(index))) {
                    throw new IllegalArgumentException("Unsupported extended schema");
                }
                unknownNames.put(index, entry.getValue());
                unknownTypes.put(index, declaredTypes.get(index));
            }
        }
        var block =
                Pattern.compile("constantValues\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL)
                        .matcher(declaration);
        if (block.find()) {
            var entries = Pattern.compile("\\[(\\d+)]\\s*=\\s*(-?\\d+)").matcher(block.group(1));
            while (entries.find()) {
                constants.put(Integer.parseInt(entries.group(1)), Long.parseLong(entries.group(2)));
            }
        }
        if (!constants.isEmpty()
                && (kind != EntityKind.NPC || !constants.equals(Map.of(2, 0L, 3, 1L)))) {
            throw new IllegalArgumentException("Unsupported schema constants");
        }
    }

    private static Map<Integer, String> declaration(String text, String name) {
        var block =
                Pattern.compile("(?m)^\\s*" + name + "\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL)
                        .matcher(text);
        if (!block.find()) {
            throw new IllegalArgumentException("Missing schema declaration: " + name);
        }
        var result = new LinkedHashMap<Integer, String>();
        var entries =
                Pattern.compile("\\[(\\d+)]\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(block.group(1));
        while (entries.find()) {
            if (result.put(Integer.parseInt(entries.group(1)), entries.group(2)) != null) {
                throw new IllegalArgumentException("Duplicate schema index");
            }
        }
        return result;
    }

    String name(int index) {
        return index <= names.length
                ? names[index - 1]
                : "unknown_" + index + "_" + unknownNames.getOrDefault(index, "field");
    }

    String type(int index) {
        return index <= types.length ? types[index - 1] : unknownTypes.get(index);
    }

    List<String> localeFields() {
        return switch (kind) {
            case QUEST -> List.of("name", "objectivesText");
            case NPC -> List.of("name", "subName");
            default -> List.of("name");
        };
    }

    Object normalize(int index, Object value) {
        if (constants.containsKey(index)) {
            return constants.get(index);
        }
        String type = type(index);
        if ("number".equals(type)) {
            if (value == null) {
                return 0L;
            }
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("Non-numeric " + name(index));
            }
            return value;
        }
        if ("string".equals(type)) {
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("Non-string " + name(index));
            }
            if (kind == EntityKind.NPC && index == 13) {
                if (value == null || value.equals("")) {
                    return null;
                }
                return value.equals("A") || value.equals("H") ? value : "AH";
            }
            return value;
        }
        if ("table".equals(type)) {
            if (value != null && !(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Non-table " + name(index));
            }
            boolean empty =
                    value == null
                            || value instanceof List<?> list && list.isEmpty()
                            || value instanceof Map<?, ?> map && map.isEmpty();
            if (kind == EntityKind.QUEST && Set.of(2, 3, 10).contains(index) && empty) {
                return List.of();
            }
            if (empty) {
                return null;
            }
            if (kind == EntityKind.QUEST
                    && Set.of(18, 19, 20).contains(index)
                    && zero(Importer.slot(value, 1))
                    && zero(Importer.slot(value, 2))) {
                return null;
            }
        }
        return value;
    }

    private static boolean zero(Object n) {
        return n == null || n instanceof Number number && number.doubleValue() == 0;
    }
}
