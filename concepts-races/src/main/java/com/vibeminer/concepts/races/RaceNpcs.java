package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.db2.Db2Types;
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
 * BakeMaterialResourcesID} through {@code TextureFileData}, whose row ID is the file), which
 * already composites skin, face and armour. It refuses, with a stated reason, only what drawing
 * cannot do without: the pre-baked texture, the race model (ChrModel) and known customization
 * choices. Item records it cannot use exactly are left out and counted ({@link Spec#omittedItems});
 * the bake already shows their texture, only their geometry is missing.
 *
 * <p>The per-NPC rows are found through indexed reverse relations ({@link RelationIndexes}), one
 * index per table and column for the component, never a scan per NPC. The three NPC tables are
 * pinned to their reviewed layouts ({@link RaceAppearance#supports}).
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

    /**
     * A humanoid NPC's drawable appearance (the race model with the NPC's choices, clothing
     * geometry and pre-baked body texture), the item records and item models left out, or why it is
     * refused. {@code retry} marks a refusal from data that could not be read yet.
     */
    public record Appearance(
            RaceAppearanceTypes.Result result,
            int omittedItems,
            int omittedAttachments,
            String refusal,
            boolean retry) {
        public Appearance {
            if ((result == null) == (refusal == null)) {
                throw new IllegalArgumentException("Inconsistent NPC appearance");
            }
        }

        public static Appearance refused(String reason) {
            return new Appearance(null, 0, 0, reason, false);
        }

        static Appearance unreadable(String reason) {
            return new Appearance(null, 0, 0, reason, true);
        }
    }

    /** Bounds on the rows one NPC may relate to. */
    static final int MAX_OPTIONS = 256, MAX_ITEMS = 64, MAX_BAKE_FILES = 16;

    private RaceNpcs() {}

    /**
     * Resolves one {@code CreatureDisplayInfoExtra} row. {@code variants} lists the race's model
     * variants ({@link RaceMedia#variants}). A table or index the store cannot answer for now
     * propagates as the races component's unreadable result, never as a refusal.
     */
    static Resolution resolve(
            RacesService tables,
            RelationIndexes relations,
            long extendedDisplayInfoId,
            LongFunction<List<RaceMediaTypes.Variant>> variants,
            Cancellation token,
            List<String> issues) {
        var extras = tables.schema("CreatureDisplayInfoExtra", token, issues);
        RaceAppearance.requireProfile(extras);
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
        // The character models are the HD ones, whose texture layout the HD bake follows; the
        // older bake follows the pre-HD layout (its face lands on the HD model's clothing). The
        // HD bake is used whenever the extra records one.
        long hdBake =
                extras.has("HDBakeMaterialResourcesID")
                        ? extras.number(extra, "HDBakeMaterialResourcesID")
                        : 0;
        long bake = hdBake > 0 ? hdBake : extras.number(extra, "BakeMaterialResourcesID");
        if (race <= 0 || sex < 0 || sex > 1) {
            return Resolution.refused("there is no race model for the recorded race and sex");
        }
        if (bake <= 0) {
            return Resolution.refused("there is no pre-baked NPC texture");
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
        var options = tables.schema("CreatureDisplayInfoOption", token, issues);
        RaceAppearance.requireProfile(options);
        options.reference("CreatureDisplayInfoExtraID", "CreatureDisplayInfoExtra");
        options.reference("ChrCustomizationOptionID", "ChrCustomizationOption");
        options.reference("ChrCustomizationChoiceID", "ChrCustomizationChoice");
        var choices = new TreeMap<Long, Long>();
        for (var row :
                related(
                        relations,
                        options,
                        "CreatureDisplayInfoExtraID",
                        extendedDisplayInfoId,
                        MAX_OPTIONS,
                        token)) {
            long option = options.number(row, "ChrCustomizationOptionID");
            long choice = options.number(row, "ChrCustomizationChoiceID");
            Long before = choices.put(option, choice);
            if (option <= 0 || choice <= 0 || before != null && before != choice) {
                return Resolution.refused("the customization choices are unknown");
            }
        }
        // Equipped items: one display per slot; anything else is left out and counted.
        var slots = tables.schema("NPCModelItemSlotDisplayInfo", token, issues);
        RaceAppearance.requireProfile(slots);
        slots.reference("NpcModelID", "CreatureDisplayInfoExtra");
        slots.reference("ItemDisplayInfoID", "ItemDisplayInfo");
        slots.field("ItemSlot", true);
        int omitted = 0;
        var bySlot = new TreeMap<Integer, Long>();
        for (var row :
                related(relations, slots, "NpcModelID", extendedDisplayInfoId, MAX_ITEMS, token)) {
            long slot = slots.number(row, "ItemSlot");
            long display = slots.number(row, "ItemDisplayInfoID");
            if (slot < 0 || slot > 255 || display <= 0) {
                omitted++; // a slot outside the reviewed profile
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
        // The pre-baked texture: the diffuse (UsageType 0) file of the material resource, whose
        // TextureFileData row ID is the file; more than one refuses.
        var textures = tables.schema("TextureFileData", token, issues);
        RaceAppearance.requireProfile(textures);
        textures.field("UsageType", true);
        textures.field("MaterialResourcesID", true);
        long file = 0;
        for (var row :
                related(relations, textures, "MaterialResourcesID", bake, MAX_BAKE_FILES, token)) {
            if (textures.number(row, "UsageType") != 0) {
                continue;
            }
            if (row.id() < 1 || row.id() > 0xffffffffL || file != 0 && file != row.id()) {
                return Resolution.refused("there is no single pre-baked NPC texture");
            }
            file = row.id();
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

    /**
     * The rows of {@code data} whose {@code column} is {@code key}, through the component's reverse
     * relation index. More rows than {@code bound} is outside the reviewed profile.
     */
    static List<Db2Types.Row> related(
            RelationIndexes relations,
            RacesService.Data data,
            String column,
            long key,
            int bound,
            Cancellation token) {
        var lookup =
                relations
                        .lookup(
                                data.name,
                                column,
                                key,
                                bound,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        switch (lookup.state()) {
            case OK -> {}
            case NOT_READABLE, STALE ->
                    throw new RacesService.Unreadable(
                            data.name + "." + column + " relation is " + lookup.state());
            default ->
                    throw data.unsupported(
                            column + " relation is " + lookup.state() + ": " + lookup.message());
        }
        if (lookup.matches() > bound) {
            throw data.unsupported(column + " relates more rows than the reviewed bound");
        }
        if (lookup.table() != null && lookup.table().state() != TableTypes.State.READY) {
            // A partial table may miss this NPC's rows: not known, so outside the profile.
            throw data.unsupported(data.name + " is " + lookup.table().state());
        }
        return lookup.rows().stream().map(TableTypes.Row::row).toList();
    }
}
