package com.vibeminer.concepts.creatures;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplaySlot;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSourceValue;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSummary;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.SingleText;
import com.vibeminer.formats.definitions.TableSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** The reviewed sixteen-field profile; no hard-coded Creature layout hash. */
final class CreatureLayout {
    static final String TABLE = "Creature";
    static final String UNSUPPORTED = "CREATURES_SCHEMA_UNSUPPORTED",
            INVALID = "CREATURES_PROJECTION_INVALID";

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        Failure(String code, String why) {
            super(why);
            this.code = code;
        }
    }

    private record Spec(String name, String kind, Integer bits, Boolean unsigned, int array) {}

    private record Fact(String name, int index, int bits, boolean unsigned) {}

    private static final List<Spec> SPECS =
            List.of(
                    new Spec("Name_lang", "string", null, null, 1),
                    new Spec("NameAlt_lang", "string", null, null, 1),
                    new Spec("Title_lang", "string", null, null, 1),
                    new Spec("TitleAlt_lang", "string", null, null, 1),
                    new Spec("Classification", "integer", 8, false, 1),
                    new Spec("CreatureType", "integer", 8, true, 1),
                    new Spec("CreatureFamily", "integer", 16, true, 1),
                    new Spec("StartAnimState", "integer", 8, false, 1),
                    new Spec("DisplayID", "integer", 32, false, 4),
                    new Spec("DisplayProbability", "float", null, null, 4));
    private final int count, name;
    private final List<Fact> facts;
    private final int[] displays, probabilities;

    private CreatureLayout(
            int count, int name, List<Fact> facts, int[] displays, int[] probabilities) {
        this.count = count;
        this.name = name;
        this.facts = List.copyOf(facts);
        this.displays = displays;
        this.probabilities = probabilities;
    }

    static CreatureLayout resolve(List<Db2Types.Column> columns, TableSchema schema) {
        if (schema == null) {
            throw unsupported("No definition resolves");
        }
        var picked = new HashMap<String, int[]>();
        for (var spec : SPECS) {
            var fields =
                    schema.columns().stream().filter(f -> f.name().equals(spec.name())).toList();
            if (fields.size() != 1) {
                throw unsupported(spec.name() + " is not one definition field");
            }
            var field = fields.getFirst();
            String family =
                    switch (field.kind()) {
                        case INT -> "integer";
                        case FLOAT -> "float";
                        default -> "string";
                    };
            if (!field.verifiedName()
                    || field.arrayLength() != spec.array()
                    || !family.equals(spec.kind())
                    || !Objects.equals(field.bits(), spec.bits())
                    || !Objects.equals(field.unsigned(), spec.unsigned())) {
                throw unsupported(spec.name() + " has unsupported kind/width/sign/cardinality");
            }
            int[] selected = new int[spec.array()];
            for (int i = 0; i < selected.length; i++) {
                selected[i] =
                        column(
                                columns,
                                spec.array() == 1 ? spec.name() : spec.name() + "[" + i + "]",
                                spec.kind());
            }
            picked.put(spec.name(), selected);
        }
        var facts = new ArrayList<Fact>();
        for (var spec : SPECS.subList(4, 8)) {
            facts.add(
                    new Fact(
                            spec.name(), picked.get(spec.name())[0], spec.bits(), spec.unsigned()));
        }
        return new CreatureLayout(
                columns.size(),
                picked.get("Name_lang")[0],
                facts,
                picked.get("DisplayID"),
                picked.get("DisplayProbability"));
    }

    private static int column(List<Db2Types.Column> columns, String name, String kind) {
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                if (found != -1) {
                    throw unsupported("Duplicate " + name);
                }
                found = i;
            }
        }
        if (found < 0
                || columns.get(found).isId()
                || columns.get(found).interpretation() != null
                || !columns.get(found).kind().equals(kind)) {
            throw unsupported("Missing plain " + kind + " " + name);
        }
        return found;
    }

    CreatureName nameOf(Db2Types.Row row) {
        check(row);
        var issues =
                row.textIssues() == null
                        ? List.<Db2Types.TextIssue>of()
                        : row.textIssues().stream().filter(i -> i.columnIndex() == name).toList();
        if (issues.size() > 1
                || issues.stream().anyMatch(i -> !i.code().equals("DB2_TEXT_ENCODING_INVALID"))) {
            throw invalid("Unexpected primary-name issue");
        }
        if (issues.size() == 1) {
            return new CreatureName(CreatureNameState.INVALID, null, issues.getFirst().code());
        }
        String text = row.values().get(name);
        return new CreatureName(
                text.isEmpty() ? CreatureNameState.EMPTY : CreatureNameState.VALID, text, null);
    }

    CreatureSummary summary(Db2Types.Row row) {
        var named = nameOf(row);
        List<CreatureSourceValue> values = new ArrayList<>();
        for (var fact : facts) {
            String text = row.values().get(fact.index());
            Long n = integer(text);
            long minimum = fact.unsigned() ? 0 : -(1L << (fact.bits() - 1)),
                    maximum =
                            fact.unsigned()
                                    ? (1L << fact.bits()) - 1
                                    : (1L << (fact.bits() - 1)) - 1;
            if (n == null || n < minimum || n > maximum) {
                throw invalid(fact.name() + " is not canonical for its width");
            }
            values.add(new CreatureSourceValue(fact.name(), fact.index(), text));
        }
        List<CreatureDisplaySlot> slots = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            Long number = integer(row.values().get(displays[slot]));
            if (number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw invalid("DisplayID[" + slot + "] is not int32");
            }
            String text = row.values().get(probabilities[slot]);
            float chance;
            try {
                chance = Float.parseFloat(text);
            } catch (NumberFormatException e) {
                throw invalid("DisplayProbability[" + slot + "] is not float32");
            }
            if (!Float.isFinite(chance) || !SingleText.format(chance).equals(text)) {
                throw invalid("DisplayProbability[" + slot + "] is not canonical finite float32");
            }
            int id = number.intValue();
            slots.add(
                    new CreatureDisplaySlot(
                            slot,
                            id,
                            chance,
                            id > 0
                                    ? CreatureSlotState.DECLARED
                                    : id == 0
                                            ? CreatureSlotState.NO_DECLARED_DISPLAY
                                            : CreatureSlotState.INVALID_DISPLAY_REFERENCE));
        }
        return new CreatureSummary(
                row.id(), row.isCopy(), row.copySourceId(), named, values, slots);
    }

    private void check(Db2Types.Row row) {
        if (row.id() < 0
                || row.id() > 0xffffffffL
                || row.values().size() != count
                || row.isCopy() != (row.copySourceId() != null)
                || row.copySourceId() != null
                        && (row.copySourceId() < 0 || row.copySourceId() > 0xffffffffL)
                || row.textIssues() != null
                        && row.textIssues().stream()
                                .anyMatch(
                                        i ->
                                                i.columnIndex() < 0
                                                        || i.columnIndex()
                                                                >= row.values().size())) {
            throw invalid("Row does not line up with its header");
        }
    }

    static Long integer(String text) {
        if (text == null || !text.matches("0|[1-9][0-9]*|-[1-9][0-9]*")) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String classificationLabel(String value) {
        return value != null
                        && value.length() == 1
                        && value.charAt(0) >= '0'
                        && value.charAt(0) <= '6'
                ? List.of("Normal", "Elite", "Rare elite", "World boss", "Rare", "Trivial", "Minus")
                        .get(value.charAt(0) - '0')
                : null;
    }

    static Failure unsupported(String why) {
        return new Failure(
                UNSUPPORTED, "Creature definition does not qualify sixteen named fields: " + why);
    }

    static Failure invalid(String why) {
        return new Failure(INVALID, "Creature row does not fit its fields: " + why);
    }
}
