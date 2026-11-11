package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.items.ItemModelFiles;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Item models an NPC wears (helmet, shoulders), placed at the character model's attachment points
 * in the rest pose with their own textures, and the character groups a helmet hides.
 *
 * <p>Each item batch becomes its own part ({@link #FIRST_PART}+), so it can be reported or left out
 * by itself. Only the reviewed static profile is drawn: one texture, opaque or alpha-keyed, no
 * texture transform, an item texture ({@code ModelMaterialResourcesID}) or a file the model names.
 * Weapons and shields are server-side NPC equipment and are not in client data.
 */
final class RaceNpcAttachments {
    /** Part IDs of attached item surfaces start here, above every character geoset. */
    static final int FIRST_PART = 20000;

    /** NPC item slots with models: 0 the head, 1 the shoulders. */
    static final int HEAD_SLOT = 0, SHOULDER_SLOT = 1;

    /** M2 attachment IDs: the helmet, the right and left shoulder. */
    static final int HEAD = 11, SHOULDER_RIGHT = 5, SHOULDER_LEFT = 6;

    /** What was attached; {@code notes} are the page facts. */
    record Attached(
            MeshAppearance appearance, Set<Integer> visible, int items, List<String> notes) {}

    /** One model to place: its resource, texture resource, attachment and position index. */
    private record Piece(int slot, long resource, long material, int attachment, int position) {}

    private RaceNpcAttachments() {}

    /**
     * Character groups the NPC's helmet hides for its race ({@code HelmetGeosetData} rows of the
     * display's {@code HelmetGeosetVis} for this sex and race); other races' rows are left alone.
     */
    static Set<Integer> hiddenGroups(
            RacesService service,
            List<RaceNpcs.Item> items,
            long raceId,
            int sex,
            Cancellation token,
            List<String> issues) {
        var hidden = new java.util.TreeSet<Integer>();
        for (var item : items) {
            if (item.slot() != HEAD_SLOT) {
                continue;
            }
            var display = service.schema("ItemDisplayInfo", token, issues);
            display.field("HelmetGeosetVis", true, 2);
            var row = service.row(display, item.itemDisplayInfoId(), token, issues);
            if (row == null) {
                continue;
            }
            long vis = display.number(row, "HelmetGeosetVis[" + (sex == 1 ? 1 : 0) + "]");
            if (vis <= 0) {
                continue;
            }
            try {
                var data = service.schema("HelmetGeosetData", token, issues);
                for (var hide :
                        RaceNpcs.related(
                                service.relations,
                                data,
                                "HelmetGeosetVisDataID",
                                vis,
                                256,
                                token)) {
                    long group = data.number(hide, "HideGeosetGroup");
                    if (data.number(hide, "RaceID") == raceId && group >= 0 && group < 100) {
                        hidden.add((int) group);
                    }
                }
            } catch (RacesService.Projection | IllegalArgumentException unreadable) {
                if (unreadable instanceof RacesService.Unreadable later) {
                    throw later;
                }
                note(issues, "Helmet hiding rows are not readable: " + unreadable.getMessage());
            }
        }
        return hidden;
    }

    /** The NPC's visible character parts without the groups its helmet hides, plus its items. */
    static Set<Integer> visible(Set<Integer> parts, Set<Integer> hidden, PreviewMesh mesh) {
        var visible = new HashSet<Integer>();
        for (int id : parts) {
            // Group 0's part 0 is the body; its other parts are hairstyles.
            if (id == 0 || !hidden.contains(id / 100)) {
                visible.add(id);
            }
        }
        for (var part : mesh.parts()) {
            if (part.id() >= FIRST_PART) {
                visible.add(part.id());
            }
        }
        return Set.copyOf(visible);
    }

    static Attached attach(
            RacesService service,
            BuildFiles files,
            GeneratedStore generated,
            M2Types.Model character,
            MeshAppearance base,
            Set<Integer> parts,
            long raceId,
            int sex,
            List<RaceNpcs.Item> items,
            Cancellation token,
            List<String> issues) {
        var notes = new ArrayList<String>();
        var pieces = new ArrayList<Piece>();
        for (var item : items) {
            if (item.slot() != HEAD_SLOT && item.slot() != SHOULDER_SLOT) {
                continue;
            }
            var display = service.schema("ItemDisplayInfo", token, issues);
            display.field("ModelResourcesID", true, 2);
            display.field("ModelMaterialResourcesID", true, 2);
            var row = service.row(display, item.itemDisplayInfoId(), token, issues);
            if (row == null) {
                continue;
            }
            long[] resource = {
                display.number(row, "ModelResourcesID[0]"),
                display.number(row, "ModelResourcesID[1]")
            };
            long[] material = {
                display.number(row, "ModelMaterialResourcesID[0]"),
                display.number(row, "ModelMaterialResourcesID[1]")
            };
            if (item.slot() == HEAD_SLOT && resource[0] > 0) {
                pieces.add(new Piece(HEAD_SLOT, resource[0], material[0], HEAD, -1));
            } else if (item.slot() == SHOULDER_SLOT) {
                if (resource[0] > 0) {
                    pieces.add(
                            new Piece(SHOULDER_SLOT, resource[0], material[0], SHOULDER_LEFT, 0));
                }
                if (resource[1] > 0) {
                    pieces.add(
                            new Piece(SHOULDER_SLOT, resource[1], material[1], SHOULDER_RIGHT, 1));
                }
            }
        }
        var hidden = hiddenGroups(service, items, raceId, sex, token, issues);
        if (pieces.isEmpty()) {
            return new Attached(base, visible(parts, hidden, base.mesh()), 0, notes);
        }
        float[] basePositions = base.mesh().positions();
        var positions = new java.util.ArrayList<float[]>();
        positions.add(basePositions);
        var uvs = new ArrayList<float[]>();
        uvs.add(base.uv());
        var normals = new ArrayList<float[]>();
        normals.add(base.normals());
        var indices = new ArrayList<int[]>();
        indices.add(base.mesh().indices());
        var meshParts = new ArrayList<>(base.mesh().parts());
        var surfaces = new ArrayList<>(base.surfaces());
        var textures = new ArrayList<>(base.textures());
        int vertexBase = basePositions.length / 3, indexBase = base.mesh().indices().length;
        int attached = 0;
        boolean environment = false;
        var leftOut = new TreeMap<String, Integer>();
        var models = new BuildModels(files);
        var images = new BlpImages(generated);
        for (var piece : pieces) {
            token.check();
            String refusal = null;
            try {
                var point =
                        character.attachments().stream()
                                .filter(a -> a.id() == piece.attachment())
                                .findFirst()
                                .orElse(null);
                if (point == null) {
                    refusal = "the character model has no attachment point for it";
                } else {
                    var ref =
                            ItemModelFiles.files(
                                    service.tables(),
                                    service.relations,
                                    piece.resource(),
                                    TableTypes.Priority.INTERACTIVE,
                                    token);
                    Long file =
                            piece.position() >= 0
                                    ? ItemModelFiles.componentFile(
                                            ref.files(), raceId, sex, piece.position())
                                    : first(ref.files(), raceId, sex);
                    if (file == null) {
                        refusal = "no model file for this race and sex";
                    } else {
                        var read = models.readModel(FileTypes.FileRef.id(file), token).join();
                        if (read.state() != BuildModelTypes.ReadState.READ
                                || read.model() == null) {
                            refusal = "its model is " + read.state();
                        } else {
                            var drawn = RaceAppearance.drawSurface(models, read, token);
                            if (drawn.surface() == null) {
                                refusal = "its surface is " + drawn.skin().state();
                            } else {
                                var item = drawn.surface();
                                var m = read.model();
                                // The item texture, or a file the model names, per texture slot.
                                var bound =
                                        new ArrayList<int[]>(); // {section, textureIndex, flags,
                                // alphaKey}
                                var byFile = new java.util.HashMap<Long, Integer>();
                                for (var batch : drawn.skin().skin().batches()) {
                                    // One texture with shader 0, or the base stage of an
                                    // environment-effect draw (its reflection is not drawn).
                                    boolean firstStage =
                                            RaceAppearanceProfiles.environmentFirstStage(batch);
                                    if (!firstStage
                                                    && (batch.textureCount() != 1
                                                            || batch.shaderId() != 0)
                                            || batch.colorIndex() != -1) {
                                        continue;
                                    }
                                    environment |= firstStage;
                                    var mat = m.materials().get(batch.materialIndex());
                                    if (mat.blendMode() > 1) {
                                        continue;
                                    }
                                    if (m.textureTransforms()
                                                    .get(batch.textureTransformComboIndex())
                                            != 65535) {
                                        continue;
                                    }
                                    var texture =
                                            m.textures()
                                                    .get(
                                                            m.textureCombos()
                                                                    .get(
                                                                            batch
                                                                                    .textureComboIndex()));
                                    long textureFile =
                                            texture.type() == 2
                                                    ? materialFile(
                                                            service,
                                                            piece.material(),
                                                            token,
                                                            issues)
                                                    : texture.type() == 0
                                                                    && texture.fileDataId() != null
                                                            ? texture.fileDataId()
                                                            : 0;
                                    if (textureFile <= 0) {
                                        continue;
                                    }
                                    Integer index = byFile.get(textureFile);
                                    if (index == null) {
                                        var decoded =
                                                images.decodeLargestWithin(
                                                                files,
                                                                FileTypes.FileRef.id(textureFile),
                                                                512,
                                                                token)
                                                        .join();
                                        if (decoded.image() == null || textures.size() >= 16) {
                                            continue;
                                        }
                                        index = textures.size();
                                        textures.add(texture(decoded.image()));
                                        byFile.put(textureFile, index);
                                    }
                                    bound.add(
                                            new int[] {
                                                batch.sectionIndex(),
                                                index,
                                                (int) texture.flags(),
                                                mat.blendMode()
                                            });
                                }
                                if (bound.isEmpty()) {
                                    refusal = "none of its surfaces fits the reviewed profile";
                                } else if (meshParts.size() + bound.size() > PreviewMesh.MAX_PARTS
                                        || vertexBase + item.mesh().positions().length / 3
                                                > PreviewMesh.MAX_VERTICES) {
                                    refusal = "it exceeds the preview bounds";
                                } else {
                                    float[] placed = item.mesh().positions().clone();
                                    for (int i = 0; i < placed.length; i += 3) {
                                        placed[i] += point.position().x();
                                        placed[i + 1] += point.position().y();
                                        placed[i + 2] += point.position().z();
                                    }
                                    positions.add(placed);
                                    uvs.add(item.uv());
                                    normals.add(item.normals());
                                    int[] itemIndices = item.mesh().indices().clone();
                                    for (int i = 0; i < itemIndices.length; i++) {
                                        itemIndices[i] += vertexBase;
                                    }
                                    indices.add(itemIndices);
                                    for (int[] b : bound) {
                                        var section = item.mesh().parts().get(b[0]);
                                        int sectionIndex = meshParts.size();
                                        meshParts.add(
                                                new PreviewMesh.Part(
                                                        FIRST_PART + sectionIndex,
                                                        indexBase + section.firstIndex(),
                                                        section.indexCount()));
                                        surfaces.add(
                                                new MeshAppearance.Surface(
                                                        sectionIndex,
                                                        b[1],
                                                        (b[2] & 1) != 0,
                                                        (b[2] & 2) != 0,
                                                        b[3] == 1));
                                    }
                                    vertexBase += placed.length / 3;
                                    indexBase += itemIndices.length;
                                    attached++;
                                }
                            }
                        }
                    }
                }
            } catch (RacesService.Projection unavailable) {
                refusal = unavailable.getMessage();
            }
            if (refusal != null) {
                leftOut.merge(
                        (piece.slot() == HEAD_SLOT ? "helmet" : "shoulder") + ": " + refusal,
                        1,
                        Integer::sum);
            }
        }
        if (attached > 0) {
            notes.add(
                    attached
                            + " item model"
                            + (attached == 1 ? "" : "s")
                            + " attached (head, shoulders).");
        }
        leftOut.forEach((reason, count) -> notes.add("Item model left out (" + reason + ")."));
        if (environment) {
            notes.add(RaceAppearancePlan.ENVIRONMENT_NOT_DRAWN);
        }
        if (attached == 0) {
            return new Attached(base, visible(parts, hidden, base.mesh()), 0, notes);
        }
        var mesh = new PreviewMesh(concat(positions), concatInts(indices), meshParts);
        var appearance = new MeshAppearance(mesh, concat(uvs), concat(normals), surfaces, textures);
        return new Attached(appearance, visible(parts, hidden, mesh), attached, notes);
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }

    /** A helmet's file: helmets record position -1 (no side) on 70862; 0 is accepted too. */
    private static Long first(
            List<com.vibeminer.concepts.items.ItemResourceFile> files, long raceId, int sex) {
        Long file = ItemModelFiles.componentFile(files, raceId, sex, -1);
        return file != null ? file : ItemModelFiles.componentFile(files, raceId, sex, 0);
    }

    /** The diffuse (UsageType 0) file of a material resource, or 0. */
    private static long materialFile(
            RacesService service, long material, Cancellation token, List<String> issues) {
        if (material <= 0) {
            return 0;
        }
        var data = service.schema("TextureFileData", token, issues);
        data.field("UsageType", true);
        data.field("MaterialResourcesID", true);
        long file = 0;
        for (var row :
                RaceNpcs.related(
                        service.relations, data, "MaterialResourcesID", material, 16, token)) {
            if (data.number(row, "UsageType") == 0) {
                if (file != 0 && file != row.id()) {
                    return 0;
                }
                file = row.id();
            }
        }
        return file;
    }

    private static MeshAppearance.Texture texture(
            com.vibeminer.formats.images.Blp2Types.Image image) {
        byte[] rgba = image.rgba();
        int[] argb = new int[image.width() * image.height()];
        for (int i = 0; i < argb.length; i++) {
            argb[i] =
                    (rgba[i * 4 + 3] & 255) << 24
                            | (rgba[i * 4] & 255) << 16
                            | (rgba[i * 4 + 1] & 255) << 8
                            | (rgba[i * 4 + 2] & 255);
        }
        return new MeshAppearance.Texture(image.width(), image.height(), argb);
    }

    private static float[] concat(List<float[]> arrays) {
        int size = arrays.stream().mapToInt(a -> a.length).sum();
        float[] out = new float[size];
        int at = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, out, at, a.length);
            at += a.length;
        }
        return out;
    }

    private static int[] concatInts(List<int[]> arrays) {
        int size = arrays.stream().mapToInt(a -> a.length).sum();
        int[] out = new int[size];
        int at = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, out, at, a.length);
            at += a.length;
        }
        return out;
    }
}
