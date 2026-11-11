package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2RestSkinReader;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.PreviewMeshCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs on the race component worker; queries only exact build-local foreign-key relationships. Only
 * {@link #data} is also used by the voice worker.
 */
@SuppressWarnings("try")
final class RaceMedia {
    private final RacesService owner;
    private final BuildTables tables;
    private final BuildFiles files;
    private final GeneratedStore generated;

    private record TableCopy(RacesService.Data data, List<String> issues) {}

    private final Map<String, TableCopy> cached = new HashMap<>();

    static final int MAX_CACHED_VARIANTS = 64, MAX_CACHED_PREVIEWS = 8;

    /** Fully resolved variant lists; they derive only from this handle's build-scoped tables. */
    private final Map<Long, RaceMediaTypes.Variants> variantLists = lru(MAX_CACHED_VARIANTS);

    /** A sample preview and the content key of the model file its surface was derived from. */
    private record KnownPreview(RaceMediaTypes.Preview preview, String content) {}

    /**
     * Sample previews that carry a surface, keyed by race, class and model. The visible parts
     * derive from this handle's build-scoped tables; a hit is used only while the model file keeps
     * the content key its surface was derived from. A preview without a surface is never kept.
     */
    private final Map<String, KnownPreview> previews = lru(MAX_CACHED_PREVIEWS);

    /**
     * Whether the last derived preview's visible parts omitted customization choices only because a
     * table page could not be read now; such a preview is not remembered.
     */
    private boolean unreadParts;

    /** Drops every remembered result; called once the workers have terminated. */
    synchronized void forget() {
        cached.clear();
        variantLists.clear();
        previews.clear();
    }

    synchronized int retained() {
        return cached.size() + variantLists.size() + previews.size();
    }

    boolean unreadParts() {
        return unreadParts;
    }

    /**
     * Whether the last derived preview failed only because its model or skin could not be read now
     * ({@link RaceAppearance#retryable(BuildModelTypes.ReadState)}), so asking again may succeed.
     */
    private boolean modelUnreadable;

    boolean modelUnreadable() {
        return modelUnreadable;
    }

    /** Whether a row lookup of the current variant assessment had an UNKNOWN outcome. */
    private boolean unknownRows;

    static <K, V> Map<K, V> lru(int bound) {
        return new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @java.io.Serial private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > bound;
            }
        };
    }

    RaceMedia(RacesService owner, BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this.owner = owner;
        this.tables = tables;
        this.files = files;
        this.generated = generated;
    }

    RaceMediaTypes.Variants variants(long raceId, Cancellation token) {
        var hit = variantLists.get(raceId);
        if (hit != null) {
            return hit;
        }
        unknownRows = false;
        var assessed = assessVariants(raceId, token);
        token.check();
        // Partial tables are a fact of this build; an UNKNOWN row lookup is asked again.
        if ((assessed.state() == RaceTypes.State.READY
                        || assessed.state() == RaceTypes.State.PARTIAL)
                && !unknownRows) {
            variantLists.put(raceId, assessed);
        }
        return assessed;
    }

    private RaceMediaTypes.Variants assessVariants(long raceId, Cancellation token) {
        var result = new ArrayList<RaceMediaTypes.Variant>();
        var issues = new ArrayList<String>();
        try {
            var joins = data("ChrRaceXChrModel", 1024, token, issues);
            joins.reference("ChrRacesID", "ChrRaces");
            joins.reference("ChrModelID", "ChrModel");
            var models = owner.schema("ChrModel", token, issues);
            models.reference("DisplayID", "CreatureDisplayInfo");
            models.field("Sex", true);
            var displays = owner.schema("CreatureDisplayInfo", token, issues);
            displays.reference("ModelID", "CreatureModelData");
            var modelData = owner.schema("CreatureModelData", token, issues);
            modelData.field("FileDataID", true);
            // Definition evidence is advisory: its failures refuse a label, never a variant.
            var definitionNotes = new ArrayList<String>();
            RacesService.Data races = null;
            com.vibeminer.formats.db2.Db2Types.Row race = null;
            try {
                races = owner.schema("ChrRaces", token, definitionNotes);
                if (RaceModelDefinitions.pairProfile(races)) {
                    race = get(races, raceId, token, definitionNotes);
                }
            } catch (java.util.concurrent.CancellationException canceled) {
                throw canceled;
            } catch (RuntimeException unavailable) {
                token.check();
                races = null;
                race = null;
            }
            for (var row : joins.rows) {
                token.check();
                if (joins.number(row, "ChrRacesID") != raceId) {
                    continue;
                }
                long modelId = joins.number(row, "ChrModelID");
                var model = get(models, modelId, token, issues);
                if (model == null) {
                    continue;
                }
                long displayId = models.number(model, "DisplayID");
                var display = get(displays, displayId, token, issues);
                if (display == null) {
                    continue;
                }
                long modelDataId = displays.number(display, "ModelID");
                var asset = get(modelData, modelDataId, token, issues);
                if (asset == null) {
                    continue;
                }
                long fid = modelData.number(asset, "FileDataID");
                if (fid <= 0 || fid > 0xffffffffL) {
                    issue(issues, "A race model has no valid file reference.");
                    continue;
                }
                int sex = (int) models.number(model, "Sex");
                result.add(
                        new RaceMediaTypes.Variant(
                                modelId,
                                sex,
                                displayId,
                                modelDataId,
                                fid,
                                RaceModelDefinitions.assess(races, raceId, race, sex, displayId)));
            }
            result.sort(
                    java.util.Comparator.comparingInt(RaceMediaTypes.Variant::sex)
                            .thenComparingLong(RaceMediaTypes.Variant::chrModelId));
            return new RaceMediaTypes.Variants(
                    issues.isEmpty() ? RaceTypes.State.READY : RaceTypes.State.PARTIAL,
                    result,
                    issues,
                    result.isEmpty()
                            ? "No readable model variants are recorded for this race."
                            : null);
        } catch (RacesService.Projection unavailable) {
            return new RaceMediaTypes.Variants(
                    unavailable.state, result, issues, unavailable.getMessage());
        }
    }

    RaceMediaTypes.Preview preview(long raceId, long classId, long chrModelId, Cancellation token) {
        String key = raceId + ":" + classId + ":" + chrModelId;
        var hit = previews.remove(key);
        if (hit != null && files != null) {
            long model = hit.preview().variant().modelFileDataId();
            var stat = files.stat(FileTypes.FileRef.id(model), token).join();
            if (stat.state() == FileTypes.FileLookupState.FOUND
                    && stat.file() != null
                    && hit.content().equals(stat.file().contentKey())) {
                previews.put(key, hit);
                return hit.preview();
            }
        }
        var derived = derive(raceId, classId, chrModelId, token, null);
        token.check();
        var result = derived.preview();
        if (result.mesh() != null && result.variant() != null && derived.content() != null) {
            previews.put(key, derived);
        }
        return result;
    }

    RaceMediaTypes.Preview preview(
            long raceId,
            long classId,
            long chrModelId,
            Cancellation token,
            RaceCustomization.Selection selection) {
        return derive(raceId, classId, chrModelId, token, selection).preview();
    }

    /** The preview and, once its surface exists, the model content key it was derived from. */
    private KnownPreview derive(
            long raceId,
            long classId,
            long chrModelId,
            Cancellation token,
            RaceCustomization.Selection selection) {
        unreadParts = false;
        modelUnreadable = false;
        if (selection != null) {
            selection.requireContext(this, raceId, classId, chrModelId);
        }
        var variants = variants(raceId, token);
        var variant =
                variants.variants().stream()
                        .filter(value -> value.chrModelId() == chrModelId)
                        .findFirst()
                        .orElse(null);
        var issues = new ArrayList<>(variants.issues());
        if (variant == null) {
            return new KnownPreview(
                    failed(null, issues, "The selected race model is unavailable."), null);
        }
        if (files == null) {
            return new KnownPreview(
                    failed(variant, issues, "Build files are unavailable for this workspace."),
                    null);
        }
        var stat = files.stat(FileTypes.FileRef.id(variant.modelFileDataId()), token).join();
        if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
            return new KnownPreview(failed(variant, issues, "Model file is " + stat.state()), null);
        }
        String content = stat.file().contentKey();
        GeneratedKey key =
                GeneratedKey.hash(
                        ("race-rest-preview-v1\n"
                                        + M2RestSkinReader.REVISION
                                        + "\n"
                                        + files.build()
                                        + "\n"
                                        + files.locale()
                                        + "\n"
                                        + content)
                                .getBytes(StandardCharsets.UTF_8));
        PreviewMesh mesh = restore(key, variant, content, token);
        boolean restored = mesh != null;
        if (mesh == null) {
            var models = new BuildModels(files);
            var read =
                    models.readModel(FileTypes.FileRef.id(variant.modelFileDataId()), token).join();
            if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
                modelUnreadable = RaceAppearance.retryable(read.state());
                return new KnownPreview(
                        failed(
                                variant,
                                issues,
                                read.message() == null
                                        ? "Model is " + read.state()
                                        : read.message()),
                        null);
            }
            var skin = models.readRestSkin(read, 0, token).join();
            if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
                modelUnreadable = RaceAppearance.retryable(skin.state());
                return new KnownPreview(
                        failed(
                                variant,
                                issues,
                                skin.message() == null
                                        ? "Model surface is " + skin.state()
                                        : skin.message()),
                        null);
            }
            mesh = RaceRestSurface.of(read.model(), skin.skin(), token).mesh();
            save(
                    key,
                    variant,
                    skin.fileDataId(),
                    content,
                    skin.file().contentKey(),
                    mesh,
                    token,
                    issues);
        }
        var parts = visibleParts(raceId, chrModelId, classId, mesh, token, issues, selection);
        issue(
                issues,
                "Static native rest positions are shown. Animation, texture compositing and starting equipment are not evaluated.");
        return new KnownPreview(
                new RaceMediaTypes.Preview(
                        RaceTypes.State.PARTIAL, variant, mesh, parts, issues, restored, null),
                unreadParts ? null : content);
    }

    private Set<Integer> visibleParts(
            long raceId,
            long chrModelId,
            long classId,
            PreviewMesh mesh,
            Cancellation token,
            List<String> issues,
            RaceCustomization.Selection selection) {
        var visible = new HashSet<>(defaultParts(mesh));
        try {
            var options = data("ChrCustomizationOption", 4096, token, issues);
            var choices = data("ChrCustomizationChoice", 40000, token, issues);
            var elements = data("ChrCustomizationElement", 200000, token, issues);
            var geosets = data("ChrCustomizationGeoset", 40000, token, issues);
            RaceAppearance.requireProfile(elements);
            RaceAppearance.requireProfile(geosets);
            for (String field : List.of("ChrModelID", "OrderIndex")) {
                options.field(field, true);
            }
            choices.reference("ChrCustomizationOptionID", "ChrCustomizationOption");
            elements.reference("ChrCustomizationChoiceID", "ChrCustomizationChoice");
            elements.reference("ChrCustomizationGeosetID", "ChrCustomizationGeoset");
            elements.field("RelatedChrCustomizationChoiceID", true);
            geosets.field("GeosetType", true);
            geosets.field("GeosetID", true);
            var sample =
                    selection != null
                            ? selection
                            : new RaceCustomization(this)
                                    .select(raceId, classId, chrModelId, token, issues);
            var selected = sample.choices();
            var byId = new HashMap<Long, com.vibeminer.formats.db2.Db2Types.Row>();
            geosets.rows.forEach(row -> byId.put(row.id(), row));
            var show = new HashSet<Integer>();
            var groups = new HashSet<Integer>();
            for (var element : elements.rows) {
                long choice = elements.number(element, "ChrCustomizationChoiceID");
                if (!selected.contains(choice)) {
                    continue;
                }
                long geoset = elements.number(element, "ChrCustomizationGeosetID");
                if (geoset == 0) {
                    continue;
                }
                var row = byId.get(geoset);
                if (row == null) {
                    issue(issues, "Some customization geosets are unavailable.");
                    continue;
                }
                var part =
                        customizationPart(
                                geosets.number(row, "GeosetType"), geosets.number(row, "GeosetID"));
                if (part == null) {
                    issue(issues, "Some customization geosets are outside the reviewed range.");
                    continue;
                }
                long related = elements.number(element, "RelatedChrCustomizationChoiceID");
                if (selected.contains(choice) && (related == 0 || selected.contains(related))) {
                    groups.addAll(part.groups());
                    show.addAll(part.show());
                }
            }
            visible = new HashSet<>(applyCustomizationParts(visible, groups, show));
            issue(
                    issues,
                    "Sample look uses the first known eligible recorded customization choice; unknown unlock requirements are omitted.");
        } catch (RacesService.Projection unavailable) {
            unreadParts = unavailable instanceof RacesService.Unreadable;
            issue(issues, "Customization selection unavailable: " + unavailable.getMessage());
        }
        return Set.copyOf(visible);
    }

    /**
     * ChrCustomizationGeoset GeosetID -1: the choice empties its group. Checked on 70862: rows
     * 213-221 and 232-240 (type 17), 6710, 12028 and 12039 (type 14) carry it, and choice 28271
     * (Breechcloth, model 89) uses 12028. It is never read as variant 99 of the group below.
     */
    static final int HIDE_GROUP = -1;

    /**
     * What one ChrCustomizationGeoset row does: the groups it takes over and the parts it shows.
     */
    record CustomizationPart(Set<Integer> groups, Set<Integer> show) {
        CustomizationPart {
            groups = Set.copyOf(groups);
            show = Set.copyOf(show);
        }
    }

    /**
     * A ChrCustomizationGeoset row as parts, or null when it is outside the reviewed range: the
     * type must be 0..99 and the GeosetID 0..99 or {@link #HIDE_GROUP}. Some rows hold huge
     * text-like values (12368, 12370, 12445 and 12449 on 70862); they are ignored, never folded
     * into another group.
     */
    static CustomizationPart customizationPart(long type, long geosetId) {
        if (type < 0 || type > 99 || geosetId < HIDE_GROUP || geosetId > 99) {
            return null;
        }
        return new CustomizationPart(
                Set.of((int) type),
                geosetId == HIDE_GROUP ? Set.of() : Set.of((int) (type * 100 + geosetId)));
    }

    /** Head swap group: 01 is a recessed cap, 02 upward are full alternative faces. */
    static final int FACE_GROUP = 32;

    /**
     * Reviewed character default surface convention, overridden only by recorded customization
     * choices: section 0 and every group's 01 part, except group 12, 17 and 35, which show only
     * when chosen.
     *
     * <p>Group 32 holds alternatives, so exactly one of its parts is drawn: the lowest variant from
     * 02 upward, or 01 when the model has nothing else. Checked on 70862 across 125 character
     * skins: 57 have more than one 32xx part. 3201 is a small cap (63-174 vertices, centred behind
     * the face) and 3202 the full face (about 1,050-1,300 vertices); 3203 and 3204 on human and
     * 3203 on Kul Tiran and the 200/201 models are coincident full-size alternatives. Every face
     * customization (Face Shape on 1/2, Eyes on 53/54, Nose on 200/201) picks from 3202 upward, so
     * 3202 is the face the client starts from. Only Snout on model 89 also offers 3201, where it is
     * a full face, and a selection there replaces the group anyway.
     */
    static Set<Integer> defaultParts(PreviewMesh mesh) {
        var visible = new HashSet<Integer>();
        int face = -1;
        for (var part : mesh.parts()) {
            int id = part.id(), group = id / 100, variant = id % 100;
            if (group == FACE_GROUP) {
                if (variant >= 1 && (face < 0 || rankFace(variant) < rankFace(face))) {
                    face = variant;
                }
            } else if ((id == 0 || variant == 1) && group != 17 && group != 35 && group != 12) {
                visible.add(id);
            }
        }
        if (face > 0) {
            visible.add(FACE_GROUP * 100 + face);
        }
        return Set.copyOf(visible);
    }

    /** The cap (01) ranks after every full face; full faces rank by variant. */
    private static int rankFace(int variant) {
        return variant == 1 ? Integer.MAX_VALUE : variant;
    }

    /** Only active groups override defaults; base section0 is body/scalp, never a hairstyle. */
    static Set<Integer> applyCustomizationParts(
            Set<Integer> defaults, Set<Integer> groups, Set<Integer> selected) {
        var visible = new HashSet<>(defaults);
        visible.removeIf(id -> id > 0 && groups.contains(id / 100));
        visible.addAll(selected);
        return Set.copyOf(visible);
    }

    static boolean reviewedField(String table, List<String> layouts, String field) {
        return switch (table) {
            case "ChrCustomizationOption" ->
                    layouts.contains("DCC2A86E")
                            && Set.of("ChrModelID", "OrderIndex").contains(field);
            case "ChrCustomizationChoice" ->
                    layouts.contains("9559C358")
                            && Set.of("ChrCustomizationOptionID", "UiOrderIndex").contains(field);
            case "ChrCustomizationElement" ->
                    layouts.contains("6483C37E")
                            && Set.of(
                                            "ChrCustomizationChoiceID",
                                            "ChrCustomizationGeosetID",
                                            "RelatedChrCustomizationChoiceID",
                                            // Checked on 70862: every non-zero value names a
                                            // ChrCustomizationBoneSet row.
                                            "ChrCustomizationBoneSetID")
                                    .contains(field);
            case "ChrCustomizationGeoset" ->
                    layouts.contains("BF55F2FB")
                            && Set.of("GeosetType", "GeosetID").contains(field);
            case "EmotesTextSound" ->
                    layouts.contains("3ECA1BE0")
                            && Set.of("EmotesTextID", "RaceID", "ClassID", "SexID", "SoundID")
                                    .contains(field);
            case "EmotesText" -> layouts.contains("CCDBFBAF") && field.equals("Name");
            case "SoundKitEntry" ->
                    layouts.contains("8F82FF7D")
                            && Set.of("SoundKitID", "FileDataID").contains(field);
            default -> false;
        };
    }

    /** Shared by the projection and voice workers; the first read of a table holds the lock. */
    synchronized RacesService.Data data(
            String name, int bound, Cancellation token, List<String> issues) {
        var found = cached.get(name);
        if (found != null) {
            found.issues().forEach(note -> issue(issues, note));
            return found.data();
        }
        var notes = new ArrayList<String>();
        var result = owner.read(name, bound, token, notes);
        token.check();
        cached.put(name, new TableCopy(result, List.copyOf(notes)));
        notes.forEach(note -> issue(issues, note));
        return result;
    }

    private com.vibeminer.formats.db2.Db2Types.Row get(
            RacesService.Data schema, long id, Cancellation token, List<String> issues) {
        var result =
                tables.get(
                                TableTypes.Ref.forName(schema.name),
                                id,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND || result.row() == null) {
            unknownRows |=
                    result.outcome() != TableTypes.RowOutcome.ABSENT
                            && result.outcome() != TableTypes.RowOutcome.PROTECTED;
            issue(issues, schema.name + " #" + id + " is " + result.outcome());
            return null;
        }
        return result.row().row();
    }

    private static RaceMediaTypes.Preview failed(
            RaceMediaTypes.Variant variant, List<String> issues, String message) {
        return new RaceMediaTypes.Preview(
                RaceTypes.State.UNAVAILABLE, variant, null, Set.of(), issues, false, message);
    }

    private static void issue(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }

    private PreviewMesh restore(
            GeneratedKey key, RaceMediaTypes.Variant variant, String content, Cancellation token) {
        if (generated == null) {
            return null;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var stored = generated.read(key, lower).join();
            token.check();
            if (stored.state() != GeneratedTypes.ReadState.PRESENT) {
                return null;
            }
            byte[] bytes = stored.bytes();
            if (bytes.length < 56) {
                throw new IllegalArgumentException("Truncated preview");
            }
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (input.getInt() != 0x56525043 || input.getLong() != variant.modelFileDataId()) {
                throw new IllegalArgumentException("Preview binding differs");
            }
            long skin = input.getLong();
            byte[] modelKey = new byte[16], skinKey = new byte[16];
            input.get(modelKey);
            input.get(skinKey);
            int length = input.getInt();
            if (!HexFormat.of().formatHex(modelKey).equals(content)
                    || length != input.remaining()) {
                throw new IllegalArgumentException("Preview content differs");
            }
            var stat = files.stat(FileTypes.FileRef.id(skin), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || !stat.file().contentKey().equals(HexFormat.of().formatHex(skinKey))) {
                return null;
            }
            byte[] mesh = new byte[length];
            input.get(mesh);
            return PreviewMeshCodec.decode(mesh);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException invalid) {
            token.check();
            return null;
        }
    }

    private void save(
            GeneratedKey key,
            RaceMediaTypes.Variant variant,
            long skin,
            String content,
            String skinContent,
            PreviewMesh mesh,
            Cancellation token,
            List<String> issues) {
        if (generated == null) {
            return;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            byte[] geometry = PreviewMeshCodec.encode(mesh);
            var output = ByteBuffer.allocate(56 + geometry.length).order(ByteOrder.LITTLE_ENDIAN);
            output.putInt(0x56525043)
                    .putLong(variant.modelFileDataId())
                    .putLong(skin)
                    .put(HexFormat.of().parseHex(content))
                    .put(HexFormat.of().parseHex(skinContent))
                    .putInt(geometry.length)
                    .put(geometry);
            generated.delete(List.of(key), lower).join();
            generated.put(List.of(new GeneratedTypes.Put(key, output.array())), lower).join();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            issue(
                    issues,
                    "Static preview could not be cached; original model inputs remain available.");
        }
    }
}
