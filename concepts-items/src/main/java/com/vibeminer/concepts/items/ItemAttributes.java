package com.vibeminer.concepts.items;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Base item attribute projection. Allocation percentages are never treated as flat stats. */
public record ItemAttributes(State state, List<Attribute> attributes, List<String> issues) {
    public enum State {
        READY,
        PARTIAL,
        REQUIRES_CALCULATION,
        UNAVAILABLE
    }

    public record Attribute(
            int slot,
            long statId,
            String name,
            BigInteger amount,
            String text,
            List<ItemFactValue> sources) {
        public Attribute {
            sources = List.copyOf(sources);
        }
    }

    public ItemAttributes {
        attributes = List.copyOf(attributes);
        issues = List.copyOf(issues);
    }

    private static final Map<Long, String> NAMES =
            Map.ofEntries(
                    Map.entry(0L, "Mana"),
                    Map.entry(1L, "Health"),
                    Map.entry(3L, "Agility"),
                    Map.entry(4L, "Strength"),
                    Map.entry(5L, "Intellect"),
                    Map.entry(6L, "Spirit"),
                    Map.entry(7L, "Stamina"),
                    Map.entry(12L, "Defense Rating"),
                    Map.entry(13L, "Dodge Rating"),
                    Map.entry(14L, "Parry Rating"),
                    Map.entry(15L, "Block Rating"),
                    Map.entry(16L, "Melee Hit Rating"),
                    Map.entry(17L, "Ranged Hit Rating"),
                    Map.entry(18L, "Spell Hit Rating"),
                    Map.entry(19L, "Melee Critical Strike Rating"),
                    Map.entry(20L, "Ranged Critical Strike Rating"),
                    Map.entry(21L, "Spell Critical Strike Rating"),
                    Map.entry(28L, "Melee Haste Rating"),
                    Map.entry(29L, "Ranged Haste Rating"),
                    Map.entry(30L, "Spell Haste Rating"),
                    Map.entry(31L, "Hit Rating"),
                    Map.entry(32L, "Critical Strike Rating"),
                    Map.entry(35L, "Resilience Rating"),
                    Map.entry(36L, "Haste Rating"),
                    Map.entry(37L, "Expertise Rating"),
                    Map.entry(38L, "Attack Power"),
                    Map.entry(39L, "Ranged Attack Power"),
                    Map.entry(41L, "Spell Healing"),
                    Map.entry(42L, "Spell Damage"),
                    Map.entry(43L, "Mana Regeneration"),
                    Map.entry(44L, "Armor Penetration Rating"),
                    Map.entry(45L, "Spell Power"),
                    Map.entry(46L, "Health Regeneration"),
                    Map.entry(47L, "Spell Penetration"),
                    Map.entry(48L, "Block Value"),
                    Map.entry(49L, "Mastery Rating"),
                    Map.entry(71L, "Agility or Strength or Intellect"),
                    Map.entry(72L, "Agility or Strength"),
                    Map.entry(73L, "Agility or Intellect"),
                    Map.entry(74L, "Strength or Intellect"));

    public static String nameOf(long statId) {
        return NAMES.get(statId);
    }

    public static ItemAttributes from(ItemFactSet facts) {
        if (facts == null || facts.state() != ItemFactsState.READY) {
            return new ItemAttributes(
                    State.UNAVAILABLE, List.of(), List.of("Item facts are unavailable"));
        }
        var type = fact(facts, "statType");
        var amount = fact(facts, "statAmount");
        if (type == null || type.state() != ItemFactState.VALUE) {
            return new ItemAttributes(
                    State.UNAVAILABLE, List.of(), List.of("Stat types are unavailable"));
        }
        if (amount == null) {
            return new ItemAttributes(
                    State.UNAVAILABLE, List.of(), List.of("Stat amount metadata is unavailable"));
        }
        if (amount.state() == ItemFactState.UNSUPPORTED) {
            return new ItemAttributes(
                    State.REQUIRES_CALCULATION,
                    List.of(),
                    List.of(
                            "This layout stores stat allocations. Flat attributes require a build-specific item-level budget and socket/scaling calculation."));
        }
        if (amount.state() != ItemFactState.VALUE) {
            return new ItemAttributes(
                    State.UNAVAILABLE, List.of(), List.of("Stat amounts are " + amount.state()));
        }
        var types = slots(type.values());
        var amounts = slots(amount.values());
        var lines = new ArrayList<Attribute>();
        var issues = new ArrayList<String>();
        if (types == null || amounts == null) {
            return new ItemAttributes(
                    State.UNAVAILABLE, List.of(), List.of("Ambiguous stat array slots"));
        }
        for (var entry : types.entrySet()) {
            int slot = entry.getKey();
            var t = entry.getValue();
            var a = amounts.get(slot);
            try {
                long id = Long.parseLong(t.value());
                if (id == -1) {
                    continue;
                }
                if (a == null || !a.table().equals(t.table()) || a.rowId() != t.rowId()) {
                    issues.add("Stat slot " + slot + " has no matching amount");
                    continue;
                }
                var value = new BigInteger(a.value());
                if (value.signum() == 0) {
                    continue;
                }
                String name = NAMES.get(id);
                if (name == null) {
                    issues.add("Unknown stat type " + id + " in slot " + slot);
                    continue;
                }
                lines.add(
                        new Attribute(
                                slot,
                                id,
                                name,
                                value,
                                (value.signum() > 0 ? "+" : "") + value + " " + name,
                                List.of(t, a)));
            } catch (NumberFormatException error) {
                issues.add("Invalid stat value in slot " + slot);
            }
        }
        return new ItemAttributes(issues.isEmpty() ? State.READY : State.PARTIAL, lines, issues);
    }

    private static ItemFact fact(ItemFactSet facts, String key) {
        return facts.facts().stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }

    private static Map<Integer, ItemFactValue> slots(List<ItemFactValue> values) {
        var result = new LinkedHashMap<Integer, ItemFactValue>();
        for (var value : values) {
            int slot = 0;
            int bracket = value.column().indexOf('[');
            try {
                if (bracket >= 0) {
                    slot =
                            Integer.parseInt(
                                    value.column()
                                            .substring(bracket + 1, value.column().length() - 1));
                }
            } catch (RuntimeException invalid) {
                return null;
            }
            if (slot < 0 || result.putIfAbsent(slot, value) != null) {
                return null;
            }
        }
        return result;
    }
}
