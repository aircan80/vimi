package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongFunction;

/**
 * Resolves a humanoid NPC appearance ({@code CreatureDisplayInfoExtra}) to what drawing it needs
 * (#385): the race model variant for the recorded race and sex, the recorded customization choices
 * ({@code CreatureDisplayInfoOption}), the equipped item displays ({@code
 * NPCModelItemSlotDisplayInfo}) and the client's own pre-baked texture ({@code
 * BakeMaterialResourcesID} through {@code TextureFileData}), which already composites skin, face
 * and armour. It refuses, with a stated reason, only what drawing cannot do without: the pre-baked
 * texture, the race model (ChrModel) and known customization choices. Item records it cannot use
 * exactly are left out and counted ({@link Spec#omittedItems}); the bake already shows their
 * texture, only their geometry is missing. Nothing is guessed.
 */
public final class RaceNpcs {
    /** One equipped item display in an NPC item slot. */
    public record Item(int slot, long itemDisplayInfoId) {}

    /** What one humanoid NPC appearance draws. */
    public record Spec(
            long extendedDisplayInfoId,
            long raceId,
            int sex,
            long classId,
            RaceMediaTypes.Variant variant,
            Map<Long, Long> choices,
            List<Item> items,
            long bakeMaterialResourcesId,
            long bakeFileDataId,
            int omittedItems) {
        public Spec {
            Objects.requireNonNull(variant);
            choices = Map.copyOf(choices);
            items = List.copyOf(items);
        }
    }

    /** A spec, or why there is none. */
    public record Resolution(Spec spec, String refusal) {
        public Resolution {
            if ((spec == null) == (refusal == null)) {
                throw new IllegalArgumentException("Inconsistent NPC resolution");
            }
        }

        static Resolution refused(String reason) {
            return new Resolution(null, reason);
        }
    }

    /** Bounds on the rows read for one NPC (they are filtered by the NPC's ID). */
    static final int MAX_OPTIONS = 256, MAX_ITEMS = 64, MAX_BAKE_FILES = 16;

    private RaceNpcs() {}

    /**
     * Resolves one {@code CreatureDisplayInfoExtra} row. {@code variants} lists the race's model
     * variants ({@link RaceMedia#variants}); a table that cannot be read propagates as the races
     * component's own unavailable result.
     */
    static Resolution resolve(
            RacesService tables,
            long extendedDisplayInfoId,
            LongFunction<List<RaceMediaTypes.Variant>> variants,
            Cancellation token,
            List<String> issues) {
        var extras = tables.schema("CreatureDisplayInfoExtra", token, issues);
        extras.reference("DisplayRaceID", "ChrRaces");
        extras.field("DisplaySexID", true);
        extras.field("BakeMaterialResourcesID", true);
        var extra = tables.row(extras, extendedDisplayInfoId, token, issues);
        if (extra == null) {
            return Resolution.refused("the extended display info is not recorded");
        }
        long race = extras.number(extra, "DisplayRaceID");
        long sex = extras.number(extra, "DisplaySexID");
        long classId = extras.has("DisplayClassID") ? extras.number(extra, "DisplayClassID") : 0;
        long bake = extras.number(extra, "BakeMaterialResourcesID");
        if (race <= 0 || sex < 0 || sex > 1) {
            return Resolution.refused("there is no race model for the recorded race and sex");
        }
        if (bake <= 0) {
            return Resolution.refused("there is no pre-baked NPC texture");
        }
        int omitted = 0;
        if (extras.has("NPCItemDisplay")) {
            // Older layouts list items inline; only the separate slot table is reviewed, so inline
            // items are left out and counted.
            int length =
                    extras.schema.columns().stream()
                            .filter(column -> column.name().equals("NPCItemDisplay"))
                            .findFirst()
                            .orElseThrow()
                            .arrayLength();
            extras.field("NPCItemDisplay", true, length);
            for (int i = 0; i < length; i++) {
                String column = length == 1 ? "NPCItemDisplay" : "NPCItemDisplay[" + i + "]";
                if (extras.number(extra, column) != 0) {
                    omitted++;
                }
            }
        }
        RaceMediaTypes.Variant variant = null;
        for (var candidate : variants.apply(race)) {
            if (candidate.sex() == sex) {
                if (variant != null) {
                    return Resolution.refused("there is no single race model for the recorded sex");
                }
                variant = candidate;
            }
        }
        if (variant == null) {
            return Resolution.refused("there is no race model for the recorded race and sex");
        }
        // Customization choices: one per option; two different choices for one option refuse.
        var options =
                tables.read(
                        "CreatureDisplayInfoOption",
                        MAX_OPTIONS,
                        token,
                        issues,
                        List.of(equals("CreatureDisplayInfoExtraID", extendedDisplayInfoId)));
        options.reference("CreatureDisplayInfoExtraID", "CreatureDisplayInfoExtra");
        options.reference("ChrCustomizationOptionID", "ChrCustomizationOption");
        options.reference("ChrCustomizationChoiceID", "ChrCustomizationChoice");
        var choices = new TreeMap<Long, Long>();
        for (var row : options.rows) {
            if (options.number(row, "CreatureDisplayInfoExtraID") != extendedDisplayInfoId) {
                continue;
            }
            long option = options.number(row, "ChrCustomizationOptionID");
            long choice = options.number(row, "ChrCustomizationChoiceID");
            Long before = choices.put(option, choice);
            if (option <= 0 || choice <= 0 || before != null && before != choice) {
                return Resolution.refused("the customization choices are unknown");
            }
        }
        // Equipped items: one display per slot.
        var slots =
                tables.read(
                        "NPCModelItemSlotDisplayInfo",
                        MAX_ITEMS,
                        token,
                        issues,
                        List.of(equals("NpcModelID", extendedDisplayInfoId)));
        slots.reference("NpcModelID", "CreatureDisplayInfoExtra");
        slots.reference("ItemDisplayInfoID", "ItemDisplayInfo");
        slots.field("ItemSlot", true);
        var bySlot = new TreeMap<Integer, Long>();
        for (var row : slots.rows) {
            if (slots.number(row, "NpcModelID") != extendedDisplayInfoId) {
                continue;
            }
            long slot = slots.number(row, "ItemSlot");
            long display = slots.number(row, "ItemDisplayInfoID");
            if (slot < 0 || slot > 255 || display <= 0) {
                omitted++; // an item slot outside the reviewed profile
                continue;
            }
            Long before = bySlot.putIfAbsent((int) slot, display);
            if (before != null && before != display) {
                // Two different items for one slot: neither is chosen; counted once below.
                bySlot.put((int) slot, -1L);
            }
        }
        var items = new ArrayList<Item>();
        for (var entry : bySlot.entrySet()) {
            if (entry.getValue() > 0) {
                items.add(new Item(entry.getKey(), entry.getValue()));
            } else {
                omitted++;
            }
        }
        // The pre-baked texture: exactly one file for the material resource.
        var bakes =
                tables.read(
                        "TextureFileData",
                        MAX_BAKE_FILES,
                        token,
                        issues,
                        List.of(equals("MaterialResourcesID", bake)));
        bakes.field("MaterialResourcesID", true);
        bakes.reference("FileDataID", "FileData");
        long file = 0;
        for (var row : bakes.rows) {
            if (bakes.number(row, "MaterialResourcesID") != bake) {
                continue;
            }
            long candidate = bakes.number(row, "FileDataID");
            if (candidate <= 0 || file != 0 && file != candidate) {
                return Resolution.refused("there is no single pre-baked NPC texture");
            }
            file = candidate;
        }
        if (file == 0) {
            return Resolution.refused("there is no pre-baked NPC texture");
        }
        return new Resolution(
                new Spec(
                        extendedDisplayInfoId,
                        race,
                        (int) sex,
                        classId,
                        variant,
                        choices,
                        items,
                        bake,
                        file,
                        omitted),
                null);
    }

    private static TableTypes.Filter equals(String column, long value) {
        return new TableTypes.Filter(
                column, TableTypes.FilterOp.EQUALS, Long.toString(value), null);
    }
}
