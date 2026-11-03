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

/** Reviewed Human/Orc sample/starting-clothing texture recipe; no guessed texture variants. */
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

    record Plan(List<Canvas> canvases, List<Layer> layers, List<Item> items) {
        Plan {
            canvases = List.copyOf(canvases);
            layers = List.copyOf(layers);
            items = List.copyOf(items);
        }
    }

    private final RacesService owner;
    private final RaceMedia media;
    private final BuildTables tables;
    private final Map<String, List<Db2Types.Row>> queries = new HashMap<>();

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
        if (!(raceId == 1 && variant.chrModelId() >= 1 && variant.chrModelId() <= 2)
                && !(raceId == 2 && variant.chrModelId() >= 3 && variant.chrModelId() <= 4)) {
            throw new RacesService.Projection(
                    RaceTypes.State.UNSUPPORTED,
                    "Static character textures are reviewed for Human models1/2 and Orc models3/4.");
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
        var sample =
                new RaceCustomization(media)
                        .select(raceId, classId, variant.chrModelId(), token, issues);
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
            if (!sample.choices().contains(elements.number(element, "ChrCustomizationChoiceID"))) {
                continue;
            }
            long related = elements.number(element, "RelatedChrCustomizationChoiceID");
            if (related != 0 && !sample.choices().contains(related)) {
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
        var items = new ArrayList<Item>();
        try {
            clothing(
                    raceId, classId, variant.sex(), layout, sections, recipe, items, token, issues);
        } catch (RacesService.Projection unavailable) {
            note(issues, "Starting clothing is partial: " + unavailable.getMessage());
        }
        recipe.sort(
                Comparator.comparingInt(Layer::order)
                        .thenComparing(Layer::source)
                        .thenComparingLong(Layer::fileDataId));
        if (recipe.size() > 64) {
            throw new RacesService.Projection(
                    RaceTypes.State.UNSUPPORTED, "Texture recipe exceeds preview bounds.");
        }
        return new Plan(canvases, recipe, items);
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
        var race = races.rows.stream().filter(row -> row.id() == raceId).findFirst().orElseThrow();
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
        if (page.state() != TableTypes.PageState.OK || page.nextAfter() != null) {
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
