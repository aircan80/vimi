package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact sample/starting-clothing profiles; no guessed texture variants. */
final class RaceAppearancePlan {
    record Canvas(int type, int width, int height) {}

    record Layer(
            int type,
            long fileDataId,
            int x,
            int y,
            int width,
            int height,
            int blend,
            int order,
            String source) {}

    record Item(long itemId, int inventoryType, long displayId, List<Integer> geosets) {
        Item {
            geosets = List.copyOf(geosets);
        }
    }

    /** A texture plan; {@code npc} marks a humanoid NPC's, which is drawn whole or not at all. */
    record Plan(
            List<Canvas> canvases,
            List<Layer> layers,
            List<Item> items,
            RaceAppearanceProfiles.Profile profile,
            boolean npc) {
        Plan {
            canvases = List.copyOf(canvases);
            layers = List.copyOf(layers);
            items = List.copyOf(items);
            java.util.Objects.requireNonNull(profile, "profile");
        }

        Plan(
                List<Canvas> canvases,
                List<Layer> layers,
                List<Item> items,
                RaceAppearanceProfiles.Profile profile) {
            this(canvases, layers, items, profile, false);
        }
    }

    private final RacesService owner;
    private final RaceMedia media;
    private final BuildTables tables;
    private final Map<String, List<Db2Types.Row>> queries = new HashMap<>();

    /**
     * Whether the last {@link #build} skipped something only because a row or page could not be
     * read now (an UNKNOWN row, a changed qualifier source or an unreadable page). Such a plan is
     * correct to show but must be built again for the next request.
     */
    private boolean unsettled;

    boolean unsettled() {
        return unsettled;
    }

    /** A race without a playable-race customization record (an NPC race or a mount). */
    static final String NOT_PLAYABLE =
            "Textures for this race are not supported yet: it has no playable-race customization"
                    + " record.";

    /** A model whose every surface uses a material setup outside the reviewed profile. */
    static final String UNREVIEWED_SURFACES =
            "Textures for this race are not supported yet: its model's surfaces use material"
                    + " setups that are not reviewed.";

    /**
     * A customization refusal restated for the texture path: the playable-race refusal gains its
     * texture wording; any other refusal is returned as it is.
     */
    static RacesService.Projection asTextureRefusal(RacesService.Projection refused) {
        return RaceCustomization.NOT_PLAYABLE.equals(refused.getMessage())
                ? new RacesService.Projection(RaceTypes.State.UNSUPPORTED, NOT_PLAYABLE)
                : refused;
    }

    /** A texture section or layer that lies outside the canvas it is drawn into. */
    static final String OUTSIDE_CANVAS =
            "Textures for this race are not supported yet: a recorded texture section lies outside"
                    + " its canvas.";

    /**
     * Race and ChrModel pairs whose composed textures were reviewed and found wrong, each with the
     * reason shown instead. Every other pair is composed, and the deeper gates (the playable-race
     * bit, the reviewed draw-binding profile) still refuse what they cannot draw.
     */
    private static final Map<Long, String> REFUSED_PAIRS = Map.of();

    /** Why a race and ChrModel pair's textures are refused before any read, or null. */
    static String refusedPair(long raceId, long chrModelId) {
        return REFUSED_PAIRS.get(raceId << 32 | chrModelId);
    }

    RaceAppearancePlan(RacesService owner, RaceMedia media, BuildTables tables) {
        this.owner = owner;
        this.media = media;
        this.tables = tables;
    }

    Plan build(
            long raceId,
            long classId,
            RaceMediaTypes.Variant variant,
            Cancellation token,
            List<String> issues) {
        return build(raceId, classId, variant, token, issues, null);
    }

    Plan build(
            long raceId,
            long classId,
            RaceMediaTypes.Variant variant,
            Cancellation token,
            List<String> issues,
            RaceCustomization.Selection sample) {
        return build(raceId, classId, variant, token, issues, sample, null);
    }

    /**
     * What a humanoid NPC wears instead of a starting loadout (#385): its slot items and its
     * pre-baked body texture, which replaces every body (type 1) layer.
     */
    record Npc(List<RaceNpcs.Item> items, long bakeFileDataId) {
        Npc {
            items = List.copyOf(items);
        }
    }

    /**
     * {@code NPCModelItemSlotDisplayInfo.ItemSlot} to item InventoryType, by the order wowdev lists
     * {@code CreatureDisplayInfoExtra}'s former inline NPC item columns (wowdev
     * DB/CreatureDisplayInfoExtra, Wrath columns 9 to 19: Helm, Shoulder, Shirt, Cuirass, Belt,
     * Legs, Boots, Wrist, Gloves, Tabard, Cape); "In Legion+ NPC armor has been moved to
     * DB/NpcModelItemSlotDisplayInfo.db2". Not documented for the slot table itself: to be checked
     * on real data before NPCs draw (#385). Slots past the table (held items) are attachments.
     */
    static final int[] NPC_SLOT_INVENTORY = {1, 3, 4, 5, 6, 7, 8, 9, 10, 19, 16};

    /**
     * Largest side of an NPC's texture canvases and decoded images (#385 design budget: at most 256
     * x 256 per NPC body), so a district of NPCs stays small. Canvases and layer rectangles are
     * scaled down proportionally; texture coordinates are relative, so the mapping is unchanged.
     */
    static final int NPC_TEXTURE_SIDE = 256;

    /** Item slots whose geometry is not drawn yet (cape 16, tabard 19): counted as omitted. */
    static boolean undrawnNpcSlot(int inventory) {
        return inventory == 16 || inventory == 19;
    }

    /**
     * An NPC's items left undrawn: item models (helms, shoulders, held items), displays the build
     * does not record, and slots not drawn yet ({@link #undrawnNpcSlot}).
     */
    record NpcOmissions(int attachments, int missing, int undrawn) {}

    /**
     * Counts what an NPC's items leave undrawn, independently of any remembered appearance: slots
     * past {@link #NPC_SLOT_INVENTORY} (held items) and displays with item models are attachments,
     * displays recorded as absent are missing, and capes and tabards are undrawn. A display that
     * cannot be read now is unreadable ({@link RacesService.Unreadable}), never an omission.
     */
    NpcOmissions npcOmissions(Npc npc, Cancellation token, List<String> issues) {
        var display = schema("ItemDisplayInfo", token, issues, "GeosetGroupOverride");
        display.field("ModelResourcesID", true, 2);
        int attachments = 0, missing = 0, undrawn = 0;
        for (var item : npc.items()) {
            token.check();
            if (item.slot() >= NPC_SLOT_INVENTORY.length) {
                attachments++;
                continue;
            }
            var info = owner.row(display, item.itemDisplayInfoId(), token, issues);
            if (info == null) {
                missing++;
            } else if (display.number(info, "ModelResourcesID[0]") != 0
                    || display.number(info, "ModelResourcesID[1]") != 0) {
                attachments++;
            } else if (undrawnNpcSlot(NPC_SLOT_INVENTORY[item.slot()])) {
                undrawn++;
            }
        }
        return new NpcOmissions(attachments, missing, undrawn);
    }

    Plan build(
            long raceId,
            long classId,
            RaceMediaTypes.Variant variant,
            Cancellation token,
            List<String> issues,
            RaceCustomization.Selection sample,
            Npc npc) {
        unsettled = false;
        if (sample != null) {
            sample.requireContext(media, raceId, classId, variant.chrModelId());
        }
        String refused = refusedPair(raceId, variant.chrModelId());
        if (refused != null) {
            throw new RacesService.Projection(RaceTypes.State.UNSUPPORTED, refused);
        }
        var models = schema("ChrModel", token, issues, "CharComponentTextureLayoutID");
        var model = get(models, variant.chrModelId(), token);
        if (model == null) {
            throw models.unsupported("Recorded model row is unavailable");
        }
        long layout = models.number(model, "CharComponentTextureLayoutID");
        if (raceId == 2
                && (layout != variant.chrModelId() + 102
                        || variant.sex() != variant.chrModelId() - 3)) {
            throw models.unsupported(
                    "Orc model/layout/sex differs from the reviewed static profile");
        }
        if (raceId == 6) {
            models.field("Sex", true);
            if (layout != variant.chrModelId() + 102
                    || variant.sex() != variant.chrModelId() - 11
                    || models.number(model, "Sex") != variant.sex()) {
                throw models.unsupported(
                        "Tauren model/layout/sex/fallback differs from the isolated profile");
            }
            requireCompatibilityZero(models, model, "ModelFallbackChrModelID", 12);
            requireCompatibilityZero(models, model, "TextureFallbackChrModelID", 13);
        }
        var profile = RaceAppearanceProfiles.Profile.LEGACY;
        if (raceId == 3) {
            models.field("Sex", true);
            if (layout != variant.chrModelId() + 102
                    || variant.sex() != variant.chrModelId() - 5
                    || models.number(model, "Sex") != variant.sex()) {
                throw models.unsupported(
                        "Dwarf model/layout/sex differs from the reviewed profile");
            }
            requireCompatibilityZero(models, model, "ModelFallbackChrModelID", 12);
            requireCompatibilityZero(models, model, "TextureFallbackChrModelID", 13);
            profile =
                    variant.sex() == 0
                            ? RaceAppearanceProfiles.Profile.DWARF_MALE
                            : RaceAppearanceProfiles.Profile.DWARF_FEMALE;
        }
        if (raceId == 4) {
            models.field("Sex", true);
            if (layout != variant.chrModelId() + 102
                    || variant.sex() != variant.chrModelId() - 7
                    || models.number(model, "Sex") != variant.sex()) {
                throw models.unsupported(
                        "Night Elf model/layout/sex differs from the reviewed profile");
            }
            requireCompatibilityZero(models, model, "ModelFallbackChrModelID", 12);
            requireCompatibilityZero(models, model, "TextureFallbackChrModelID", 13);
            profile =
                    variant.sex() == 0
                            ? RaceAppearanceProfiles.Profile.NIGHT_ELF_MALE
                            : RaceAppearanceProfiles.Profile.NIGHT_ELF_FEMALE;
        }
        var materials = media.data("ChrModelMaterial", 2048, token, issues);
        fields(
                materials,
                "CharComponentTextureLayoutsID",
                "TextureType",
                "Width",
                "Height",
                "Flags");
        var layers = media.data("ChrModelTextureLayer", 10000, token, issues);
        fields(
                layers,
                "CharComponentTextureLayoutsID",
                "TextureType",
                "Layer",
                "BlendMode",
                "TextureSectionTypeBitMask",
                "TextureSectionTypeBitMask2",
                "Flags");
        layers.field("ChrModelTextureTargetID", true, 2);
        var sections = media.data("CharComponentTextureSections", 4096, token, issues);
        fields(
                sections,
                "CharComponentTextureLayoutID",
                "SectionType",
                "X",
                "Y",
                "Width",
                "Height");
        var canvases = new ArrayList<Canvas>();
        for (var row : materials.rows) {
            if (materials.number(row, "CharComponentTextureLayoutsID") == layout
                    && materials.number(row, "Flags") == 0) {
                int type = integer(materials, row, "TextureType"),
                        width = integer(materials, row, "Width"),
                        height = integer(materials, row, "Height");
                if (width < 1 || height < 1 || width > 2048 || height > 2048) {
                    throw materials.unsupported("Texture canvas exceeds preview bounds");
                }
                if (canvases.stream().anyMatch(c -> c.type() == type)) {
                    throw materials.unsupported("Ambiguous texture canvas");
                }
                canvases.add(new Canvas(type, width, height));
            }
        }
        if (canvases.size() > 16
                || canvases.stream()
                                .mapToLong(canvas -> (long) canvas.width() * canvas.height())
                                .sum()
                        > 8_388_608L) {
            throw materials.unsupported("Texture canvas memory exceeds preview bounds");
        }
        RaceCustomization.Selection selectedSample;
        try {
            selectedSample =
                    sample != null
                            ? sample
                            : new RaceCustomization(media)
                                    .select(raceId, classId, variant.chrModelId(), token, issues);
        } catch (RacesService.Projection customizationRefused) {
            throw asTextureRefusal(customizationRefused);
        }
        var elements = media.data("ChrCustomizationElement", 200000, token, issues);
        fields(
                elements,
                "ChrCustomizationChoiceID",
                "RelatedChrCustomizationChoiceID",
                "ChrCustomizationMaterialID");
        var customization =
                schema(
                        "ChrCustomizationMaterial",
                        token,
                        issues,
                        "ChrModelTextureTargetID",
                        "MaterialResourcesID");
        var recipe = new ArrayList<Layer>();
        for (var element : elements.rows) {
            token.check();
            if (!selectedSample
                    .choices()
                    .contains(elements.number(element, "ChrCustomizationChoiceID"))) {
                continue;
            }
            long related = elements.number(element, "RelatedChrCustomizationChoiceID");
            if (related != 0 && !selectedSample.choices().contains(related)) {
                continue;
            }
            long id = elements.number(element, "ChrCustomizationMaterialID");
            if (id == 0) {
                continue;
            }
            var material = get(customization, id, token);
            if (material == null) {
                note(issues, "Some sample customization material rows are unavailable.");
                continue;
            }
            long target = customization.number(material, "ChrModelTextureTargetID");
            var matches =
                    layers.rows.stream()
                            .filter(
                                    row ->
                                            layers.number(row, "CharComponentTextureLayoutsID")
                                                            == layout
                                                    && layers.number(
                                                                    row,
                                                                    "ChrModelTextureTargetID[0]")
                                                            == target)
                            .toList();
            if (matches.size() != 1) {
                note(issues, "Some customization texture targets are ambiguous or unavailable.");
                continue;
            }
            var layer = matches.getFirst();
            int type = integer(layers, layer, "TextureType"),
                    blend = integer(layers, layer, "BlendMode");
            if (layers.number(layer, "Flags") != 0
                    || layers.number(layer, "ChrModelTextureTargetID[1]") != 0
                    || layers.number(layer, "TextureSectionTypeBitMask2") != -1
                    || (blend != 1 && blend != 15)) {
                note(
                        issues,
                        "Some customization texture operations are outside the reviewed static profile.");
                continue;
            }
            var canvas =
                    canvases.stream()
                            .filter(value -> value.type() == type)
                            .findFirst()
                            .orElse(null);
            if (canvas == null) {
                continue;
            }
            long fid =
                    texture(
                            customization.number(material, "MaterialResourcesID"),
                            raceId,
                            classId,
                            variant.sex(),
                            token,
                            issues);
            if (fid == 0) {
                continue;
            }
            long mask = layers.number(layer, "TextureSectionTypeBitMask");
            if (mask == -1) {
                recipe.add(
                        new Layer(
                                type,
                                fid,
                                0,
                                0,
                                canvas.width(),
                                canvas.height(),
                                blend,
                                integer(layers, layer, "Layer"),
                                "Customization material #" + id));
            } else {
                for (var section : sections.rows) {
                    if (sections.number(section, "CharComponentTextureLayoutID") == layout) {
                        int sectionType = integer(sections, section, "SectionType");
                        if (sectionType >= 0
                                && sectionType < 32
                                && (mask & (1L << sectionType)) != 0) {
                            recipe.add(
                                    layer(
                                            type,
                                            fid,
                                            blend,
                                            integer(layers, layer, "Layer"),
                                            sections,
                                            section,
                                            "Customization material #" + id));
                        }
                    }
                }
            }
        }
        // An NPC's bake covers the whole body, so the selected base skin is not needed.
        if (npc == null
                && RaceAppearanceProfiles.nightElf(profile)
                && recipe.stream().noneMatch(layer -> wholeBodyBase(layer, canvases))) {
            throw models.unsupported(
                    "Known eligible full base skin is unavailable for this preview");
        }
        var items = new ArrayList<Item>();
        if (npc != null) {
            npcClothing(npc, items, token, issues);
            // The client's pre-baked NPC texture already composites skin, face, underwear and
            // armour: it is the whole body canvas, and no other body layer is drawn.
            var body =
                    canvases.stream()
                            .filter(canvas -> canvas.type() == 1)
                            .findFirst()
                            .orElseThrow(() -> models.unsupported("No body texture canvas"));
            recipe.removeIf(layer -> layer.type() == 1);
            recipe.add(
                    new Layer(
                            1,
                            npc.bakeFileDataId(),
                            0,
                            0,
                            body.width(),
                            body.height(),
                            1,
                            Integer.MIN_VALUE,
                            "NPC bake #" + npc.bakeFileDataId()));
            scaleForNpc(canvases, recipe);
        } else {
            try {
                clothing(
                        raceId,
                        classId,
                        variant.sex(),
                        layout,
                        sections,
                        recipe,
                        items,
                        token,
                        issues);
            } catch (RacesService.Projection unavailable) {
                unsettled |= unavailable instanceof RacesService.Unreadable;
                note(issues, "Starting clothing is partial: " + unavailable.getMessage());
            }
        }
        recipe.sort(
                Comparator.comparingInt(Layer::order)
                        .thenComparing(Layer::source)
                        .thenComparingLong(Layer::fileDataId));
        if (recipe.size() > 64) {
            throw new RacesService.Projection(
                    RaceTypes.State.UNSUPPORTED, "Texture recipe exceeds preview bounds.");
        }
        requireInsideCanvases(canvases, recipe);
        return new Plan(canvases, recipe, items, profile, npc != null);
    }

    /**
     * Scales every canvas wider or taller than {@link #NPC_TEXTURE_SIDE} down to it, keeping its
     * aspect, and each of its layers' rectangles with it (outward to whole texels).
     */
    private static void scaleForNpc(List<Canvas> canvases, List<Layer> recipe) {
        for (int c = 0; c < canvases.size(); c++) {
            var canvas = canvases.get(c);
            int side = Math.max(canvas.width(), canvas.height());
            if (side <= NPC_TEXTURE_SIDE) {
                continue;
            }
            int width = (int) Math.max(1, (long) canvas.width() * NPC_TEXTURE_SIDE / side);
            int height = (int) Math.max(1, (long) canvas.height() * NPC_TEXTURE_SIDE / side);
            canvases.set(c, new Canvas(canvas.type(), width, height));
            for (int i = 0; i < recipe.size(); i++) {
                var layer = recipe.get(i);
                if (layer.type() != canvas.type()) {
                    continue;
                }
                int x0 = (int) ((long) layer.x() * width / canvas.width());
                int y0 = (int) ((long) layer.y() * height / canvas.height());
                int x1 =
                        (int)
                                Math.min(
                                        width,
                                        Math.ceilDiv(
                                                (long) (layer.x() + layer.width()) * width,
                                                canvas.width()));
                int y1 =
                        (int)
                                Math.min(
                                        height,
                                        Math.ceilDiv(
                                                (long) (layer.y() + layer.height()) * height,
                                                canvas.height()));
                recipe.set(
                        i,
                        new Layer(
                                layer.type(),
                                layer.fileDataId(),
                                Math.min(x0, width - 1),
                                Math.min(y0, height - 1),
                                Math.max(1, x1 - x0),
                                Math.max(1, y1 - y0),
                                layer.blend(),
                                layer.order(),
                                layer.source()));
            }
        }
    }

    /**
     * The NPC's slot items as clothing geometry. Item models (a display with ModelResourcesID:
     * helms, shoulders, held items) are counted as attachments and not drawn. Robes are never
     * inferred from the slot: a chest whose display sets the robe group (GeosetGroup[2] above 0,
     * which RaceClothingGeometry applies to group 13, the robe/kilt) is treated as a robe
     * (InventoryType 20); any other chest as InventoryType 5. Capes and tabards are left out
     * ({@link #undrawnNpcSlot}). A display that cannot be read now is unreadable, so the NPC is
     * asked for again rather than drawn without it.
     */
    private void npcClothing(Npc npc, List<Item> items, Cancellation token, List<String> issues) {
        var display = schema("ItemDisplayInfo", token, issues, "GeosetGroupOverride");
        display.field("GeosetGroup", true, 6);
        display.field("ModelResourcesID", true, 2);
        for (var item : npc.items()) {
            token.check();
            if (item.slot() >= NPC_SLOT_INVENTORY.length) {
                continue; // a held item: only its model, counted by npcOmissions
            }
            if (undrawnNpcSlot(NPC_SLOT_INVENTORY[item.slot()])) {
                note(issues, "NPC capes and tabards are not drawn yet.");
                continue;
            }
            var info = owner.row(display, item.itemDisplayInfoId(), token, issues);
            if (info == null) {
                note(issues, "Some NPC item displays are not recorded.");
                continue;
            }
            var groups = new ArrayList<Integer>();
            for (int n = 0; n < 6; n++) {
                int value = integer(display, info, "GeosetGroup[" + n + "]");
                if (value < 0 || value > 98) {
                    throw display.unsupported("Clothing geoset variant exceeds reviewed bounds");
                }
                groups.add(value);
            }
            int inventory = NPC_SLOT_INVENTORY[item.slot()];
            if (inventory == 5 && groups.get(2) > 0) {
                inventory = 20; // the display itself sets the robe group
            }
            boolean simple = display.number(info, "GeosetGroupOverride") == 0;
            if (!simple) {
                note(issues, "Some NPC clothing geoset overrides are not evaluated.");
            }
            items.add(
                    new Item(0, inventory, item.itemDisplayInfoId(), simple ? groups : List.of()));
        }
    }

    static boolean wholeBodyBase(Layer layer, List<Canvas> canvases) {
        return layer.type() == 1
                && layer.blend() == 1
                && layer.x() == 0
                && layer.y() == 0
                && canvases.stream()
                        .anyMatch(
                                canvas ->
                                        canvas.type() == 1
                                                && canvas.width() == layer.width()
                                                && canvas.height() == layer.height());
    }

    private void clothing(
            long raceId,
            long classId,
            int sex,
            long layout,
            RacesService.Data sections,
            List<Layer> recipe,
            List<Item> items,
            Cancellation token,
            List<String> issues) {
        var races = media.data("ChrRaces", 512, token, issues);
        races.field("PlayableRaceBit", true);
        var race =
                races.rows.stream()
                        .filter(row -> row.id() == raceId)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new RacesService.Projection(
                                                RaceTypes.State.UNSUPPORTED, NOT_PLAYABLE));
        int bit = integer(races, race, "PlayableRaceBit");
        var loadouts = media.data("CharacterLoadout", 1024, token, issues);
        fields(loadouts, "ChrClassID", "Purpose");
        loadouts.field("Racemask", true, 2);
        var matches =
                loadouts.rows.stream()
                        .filter(
                                row ->
                                        loadouts.number(row, "ChrClassID") == classId
                                                && loadouts.number(row, "Purpose") == 9
                                                && hasRace(loadouts, row, "Racemask", bit))
                        .toList();
        if (matches.size() != 1) {
            note(issues, "A unique recorded new-character loadout is unavailable.");
            return;
        }
        var links = schema("CharacterLoadoutItem", token, issues, "CharacterLoadoutID", "ItemID");
        var itemSchema = schema("Item", token, issues, "InventoryType");
        var modified =
                schema(
                        "ItemModifiedAppearance",
                        token,
                        issues,
                        "ItemID",
                        "ItemAppearanceModifierID",
                        "ItemAppearanceID");
        var appearance = schema("ItemAppearance", token, issues, "ItemDisplayInfoID");
        var display = schema("ItemDisplayInfo", token, issues, "GeosetGroupOverride");
        display.field("GeosetGroup", true, 6);
        display.field("ModelResourcesID", true, 2);
        var resources =
                schema(
                        "ItemDisplayInfoMaterialRes",
                        token,
                        issues,
                        "ItemDisplayInfoID",
                        "ComponentSection",
                        "MaterialResourcesID");
        for (var link : query(links, "CharacterLoadoutID", matches.getFirst().id(), token)) {
            long itemId = links.number(link, "ItemID");
            var item = get(itemSchema, itemId, token);
            if (item == null) {
                note(issues, "Some starting item rows are unavailable.");
                continue;
            }
            int inventory = integer(itemSchema, item, "InventoryType");
            int priority =
                    switch (inventory) {
                        case 4, 7 -> 10;
                        case 8 -> 11;
                        case 5, 20 -> 13;
                        case 6 -> 18;
                        case 9 -> 19;
                        case 10 -> 20;
                        default -> -1;
                    };
            if (priority < 0) {
                if (inventory != 0) {
                    note(
                            issues,
                            "Separate equipment attachments are not rendered in the static clothing preview.");
                }
                continue;
            }
            var mods =
                    query(modified, "ItemID", itemId, token).stream()
                            .filter(row -> modified.number(row, "ItemAppearanceModifierID") == 0)
                            .toList();
            if (mods.size() != 1) {
                note(issues, "Some starting items have no unique default client appearance.");
                continue;
            }
            var app = get(appearance, modified.number(mods.getFirst(), "ItemAppearanceID"), token);
            if (app == null) {
                continue;
            }
            long displayId = appearance.number(app, "ItemDisplayInfoID");
            var info = get(display, displayId, token);
            if (info == null) {
                continue;
            }
            if (display.number(info, "GeosetGroupOverride") != 0) {
                note(issues, "Some starting clothing geoset overrides are not evaluated.");
            }
            var groups = new ArrayList<Integer>();
            for (int n = 0; n < 6; n++) {
                int value = integer(display, info, "GeosetGroup[" + n + "]");
                if (value < 0 || value > 98) {
                    throw display.unsupported("Clothing geoset variant exceeds reviewed bounds");
                }
                groups.add(value);
            }
            if (display.number(info, "ModelResourcesID[0]") != 0
                    || display.number(info, "ModelResourcesID[1]") != 0) {
                note(issues, "Attached item meshes are not rendered.");
            }
            boolean simpleGeometry =
                    display.number(info, "GeosetGroupOverride") == 0
                            && display.number(info, "ModelResourcesID[0]") == 0
                            && display.number(info, "ModelResourcesID[1]") == 0;
            items.add(new Item(itemId, inventory, displayId, simpleGeometry ? groups : List.of()));
            for (var resource : query(resources, "ItemDisplayInfoID", displayId, token)) {
                long sectionType = resources.number(resource, "ComponentSection");
                if (!RaceClothingGeometry.allowsSection(inventory, sectionType)) {
                    note(
                            issues,
                            "Some clothing component sections are outside the reviewed slot profile.");
                    continue;
                }
                var found =
                        sections.rows.stream()
                                .filter(
                                        row ->
                                                sections.number(row, "CharComponentTextureLayoutID")
                                                                == layout
                                                        && sections.number(row, "SectionType")
                                                                == sectionType)
                                .toList();
                if (found.size() != 1) {
                    note(issues, "Some clothing atlas sections are ambiguous or unavailable.");
                    continue;
                }
                long fid =
                        texture(
                                resources.number(resource, "MaterialResourcesID"),
                                raceId,
                                classId,
                                sex,
                                token,
                                issues);
                if (fid == 0) {
                    continue;
                }
                recipe.add(
                        layer(
                                1,
                                fid,
                                15,
                                100000 + priority * 100,
                                sections,
                                found.getFirst(),
                                "Starting item #" + itemId + " display #" + displayId));
            }
        }
    }

    // This is a compatibility-zero gate for these exact Tauren/Dwarf/Night Elf profiles, not
    // general name
    // admission.
    // The selected Classic layout declares these two logical signed32 slots at flattened12/13.
    private static void requireCompatibilityZero(
            RacesService.Data models, Db2Types.Row model, String name, int ordinal) {
        var fields =
                models.schema.columns().stream()
                        .filter(field -> field.name().equals(name))
                        .toList();
        if (!models.name.equals("ChrModel")
                || !models.schema.layoutHashes().equals(List.of("03FAB755"))
                || fields.size() != 1
                || models.index(name) != ordinal) {
            throw models.unsupported(
                    "Character compatibility slot differs from the reviewed layout");
        }
        var field = fields.getFirst();
        var column = models.columns.get(ordinal);
        if (field.kind() != com.vibeminer.formats.definitions.TableSchema.ColumnKind.INT
                || !Integer.valueOf(32).equals(field.bits())
                || !Boolean.FALSE.equals(field.unsigned())
                || field.arrayLength() != 1
                || field.id() != com.vibeminer.formats.definitions.TableSchema.KeyStorage.NONE
                || field.relation() != com.vibeminer.formats.definitions.TableSchema.KeyStorage.NONE
                || !column.kind().equals("integer")
                || column.isId()
                || column.interpretation() != null
                || column.narrowing() != null
                || !models.text(model, name).equals("0")) {
            throw models.unsupported(
                    "Character compatibility slot must retain its exact recorded zero");
        }
    }

    private long texture(
            long resource,
            long raceId,
            long classId,
            int sex,
            Cancellation token,
            List<String> issues) {
        var textures = schema("TextureFileData", token, issues, "UsageType", "MaterialResourcesID");
        var components =
                schema(
                        "ComponentTextureFileData",
                        token,
                        issues,
                        "GenderIndex",
                        "ClassID",
                        "RaceID");
        record Candidate(long id, int score) {}
        var candidates = new ArrayList<Candidate>();
        var diffuse =
                query(textures, "MaterialResourcesID", resource, token).stream()
                        .filter(row -> textures.number(row, "UsageType") == 0)
                        .toList();
        for (var row : diffuse) {
            if (row.id() < 1 || row.id() > 0xffffffffL) {
                continue;
            }
            var qualification =
                    tables.get(
                                    TableTypes.Ref.forName(components.name),
                                    row.id(),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            boolean sameSource =
                    qualification.table() != null
                            && components.entry.contentKey() != null
                            && java.util.Objects.equals(
                                    components.entry.contentKey(),
                                    qualification.table().contentKey())
                            && qualification.table().counts() != null
                            && qualification.table().counts().layoutHash() != null
                            && components
                                    .schema
                                    .layoutHashes()
                                    .contains(qualification.table().counts().layoutHash());
            if (!sameSource
                    || (qualification.outcome() != TableTypes.RowOutcome.FOUND
                            && qualification.outcome() != TableTypes.RowOutcome.ABSENT)
                    || (qualification.outcome() == TableTypes.RowOutcome.FOUND
                            && qualification.row() == null)) {
                unsettled |=
                        !sameSource || qualification.outcome() == TableTypes.RowOutcome.UNKNOWN;
                note(
                        issues,
                        "Some texture qualifiers are unknown, protected or changed; only proven alternatives are used.");
                continue;
            }
            var component =
                    qualification.outcome() == TableTypes.RowOutcome.FOUND
                            ? qualification.row().row()
                            : null;
            int score = 32;
            if (component != null) {
                long gender = components.number(component, "GenderIndex"),
                        race = components.number(component, "RaceID"),
                        clazz = components.number(component, "ClassID");
                if ((gender != sex && gender != 3 && !(gender == 2 && diffuse.size() == 1))
                        || (race != 0 && race != raceId)
                        || (clazz != 0 && clazz != classId)) {
                    continue;
                }
                score =
                        (gender == sex ? 0 : 8)
                                + (clazz == classId && classId != 0 ? 0 : 4)
                                + (race == raceId ? 0 : 2);
            }
            candidates.add(new Candidate(row.id(), score));
        }
        int best = candidates.stream().mapToInt(Candidate::score).min().orElse(Integer.MAX_VALUE);
        var matches = candidates.stream().filter(candidate -> candidate.score() == best).toList();
        if (matches.size() != 1) {
            note(
                    issues,
                    "Some texture resources have no unique best matching race/voice/class diffuse file.");
            return 0;
        }
        return matches.getFirst().id();
    }

    private RacesService.Data schema(
            String name, Cancellation token, List<String> issues, String... fields) {
        var result = owner.schema(name, token, issues);
        fields(result, fields);
        return result;
    }

    private static void fields(RacesService.Data data, String... names) {
        RaceAppearance.requireProfile(data);
        for (String name : names) {
            data.field(name, true);
        }
    }

    private Db2Types.Row get(RacesService.Data data, long id, Cancellation token) {
        var row =
                tables.get(
                                TableTypes.Ref.forName(data.name),
                                id,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        unsettled |= row.outcome() == TableTypes.RowOutcome.UNKNOWN;
        return row.outcome() == TableTypes.RowOutcome.FOUND && row.row() != null
                ? row.row().row()
                : null;
    }

    private List<Db2Types.Row> query(
            RacesService.Data data, String field, long value, Cancellation token) {
        String key = data.name + "/" + field + "/" + value;
        var previous = queries.get(key);
        if (previous != null) {
            return previous;
        }
        var page =
                tables.page(
                                new TableTypes.PageRequest(
                                        TableTypes.Ref.forName(data.name),
                                        -1,
                                        256,
                                        List.of(
                                                new TableTypes.Filter(
                                                        field,
                                                        TableTypes.FilterOp.EQUALS,
                                                        Long.toString(value),
                                                        null)),
                                        null),
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (page.state() != TableTypes.PageState.OK) {
            throw new RacesService.Unreadable(
                    data.name + ": filtered texture relationship is " + page.state());
        }
        if (page.nextAfter() != null) {
            throw data.unsupported(
                    "Filtered texture relationship is unavailable or exceeds bounds");
        }
        var rows = page.rows().stream().map(TableTypes.Row::row).toList();
        if (queries.size() < 512) {
            queries.put(key, rows);
        }
        return rows;
    }

    private static boolean hasRace(
            RacesService.Data data, Db2Types.Row row, String field, int bit) {
        return RaceCustomization.hasRace(
                data.number(row, field + "[0]"), data.number(row, field + "[1]"), bit);
    }

    /**
     * Refuses a plan any of whose layers lies outside its canvas, or whose canvas would take more
     * layers than the composer admits, so composition never fails on its bounds.
     */
    static void requireInsideCanvases(List<Canvas> canvases, List<Layer> layers) {
        for (var canvas : canvases) {
            if (canvas.width() < 1
                    || canvas.height() < 1
                    || canvas.width() > 2048
                    || canvas.height() > 2048) {
                throw new RacesService.Projection(RaceTypes.State.UNSUPPORTED, OUTSIDE_CANVAS);
            }
            int count = 0;
            for (var layer : layers) {
                if (layer.type() != canvas.type()) {
                    continue;
                }
                count++;
                if (layer.x() < 0
                        || layer.y() < 0
                        || layer.width() < 1
                        || layer.height() < 1
                        || layer.x() > canvas.width() - layer.width()
                        || layer.y() > canvas.height() - layer.height()) {
                    throw new RacesService.Projection(RaceTypes.State.UNSUPPORTED, OUTSIDE_CANVAS);
                }
            }
            if (count > 64) {
                throw new RacesService.Projection(
                        RaceTypes.State.UNSUPPORTED,
                        "Textures for this race are not supported yet: a canvas takes more than 64"
                                + " texture layers.");
            }
        }
    }

    private static Layer layer(
            int type,
            long fid,
            int blend,
            int order,
            RacesService.Data data,
            Db2Types.Row row,
            String source) {
        return new Layer(
                type,
                fid,
                integer(data, row, "X"),
                integer(data, row, "Y"),
                integer(data, row, "Width"),
                integer(data, row, "Height"),
                blend,
                order,
                source);
    }

    private static int integer(RacesService.Data data, Db2Types.Row row, String field) {
        try {
            return Math.toIntExact(data.number(row, field));
        } catch (ArithmeticException invalid) {
            throw data.unsupported("Integer exceeds preview bounds: " + field);
        }
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }
}
