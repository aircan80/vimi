package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2Combiners;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2RestSkinReader;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import com.vibeminer.rendering.models.PreviewMesh;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Generic CreatureDisplayInfo static preview, with no mount, NPC or player-customization policy.
 */
@SuppressWarnings("try")
public final class DisplayModelService implements BuildDisplayModels {
    public static final String POLICY = "display-static-diffuse-rest-legacy272-sections-v2";
    private final TableProjection tables;
    private final BuildFiles files;
    private final GeneratedStore generated;
    private final DisplayPreviewCache cache;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon().name("display-model-preview").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Void> closing;

    public DisplayModelService(BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this.tables = new TableProjection(tables);
        this.files = Objects.requireNonNull(files);
        this.generated = generated;
        cache = new DisplayPreviewCache(generated, files);
    }

    @Override
    public CompletableFuture<DisplayModelTypes.Preview> preview(
            long displayId, Cancellation token) {
        if (displayId <= 0 || displayId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid display ID");
        }
        var result = new CompletableFuture<DisplayModelTypes.Preview>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Display previews closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var owner = lifetime.onCancel(request::cancel)) {
                                request.check();
                                result.complete(load(displayId, request));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                result.completeExceptionally(
                        new IllegalStateException("Display previews busy", full));
            }
        }
        var published = new CompletableFuture<DisplayModelTypes.Preview>();
        result.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        published.complete(value);
                                    } else {
                                        published.completeExceptionally(failure);
                                    }
                                }));
        return published;
    }

    private DisplayModelTypes.Preview load(long displayId, Cancellation token) {
        var issues = new ArrayList<String>();
        DisplayModelTypes.Reference reference = null;
        try {
            reference = resolve(displayId, token, issues);
            if (reference == null) {
                return failed(null, issues, "Display-to-model relationship is unavailable.");
            }
            var stat = files.stat(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
                return failed(reference, issues, "Model file is " + stat.state());
            }
            String content = stat.file().contentKey();
            var identity =
                    new StringBuilder(
                            POLICY
                                    + "\n"
                                    + M2ModelReader.REVISION
                                    + "\n"
                                    + M2RestDrawSkinReader.REVISION
                                    + "\n"
                                    + M2RestSkinReader.REVISION
                                    + "\n"
                                    + M2Appearance.READER_REVISION
                                    + "\n"
                                    + M2Appearance.MATERIAL_POLICY
                                    + "\n"
                                    + Blp2Decoder.REVISION
                                    + "\n"
                                    + MeshAppearanceCodec.REVISION
                                    + "\n"
                                    + files.build()
                                    + "\n"
                                    + files.locale()
                                    + "\n"
                                    + reference
                                    + "\n"
                                    + content);
            for (long fid : reference.textureVariations()) {
                if (fid > 0) {
                    var receipt = files.stat(FileTypes.FileRef.id(fid), token).join();
                    identity.append('\n')
                            .append(fid)
                            .append(':')
                            .append(
                                    receipt.file() == null
                                            ? "unavailable"
                                            : receipt.file().contentKey());
                }
            }
            var key = GeneratedKey.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
            var restored = cache.restore(key, reference.modelFileDataId(), content, token);
            if (restored != null) {
                limitations(reference, restored.mesh(), issues);
                return ready(reference, restored, issues, true);
            }
            var models = new BuildModels(files);
            var read =
                    models.readModel(FileTypes.FileRef.id(reference.modelFileDataId()), token)
                            .join();
            if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
                return failed(
                        reference,
                        issues,
                        "Model read is " + read.state() + message(read.message()));
            }
            var skin = models.readRestDrawSkin(read, 0, token).join();
            if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
                return failed(
                        reference,
                        issues,
                        "Model surface is " + skin.state() + message(skin.message()));
            }
            var geometry = skin.skin().surface();
            var m = read.model();
            float[] points = new float[geometry.vertexLookup().size() * 3],
                    normals = new float[points.length],
                    uv = new float[geometry.vertexLookup().size() * 2];
            for (int i = 0; i < geometry.vertexLookup().size(); i++) {
                if ((i & 2047) == 0) {
                    token.check();
                }
                var v = m.vertices().get(geometry.vertexLookup().get(i));
                points[i * 3] = v.position().x();
                points[i * 3 + 1] = v.position().y();
                points[i * 3 + 2] = v.position().z();
                normals[i * 3] = v.normal().x();
                normals[i * 3 + 1] = v.normal().y();
                normals[i * 3 + 2] = v.normal().z();
                uv[i * 2] = v.uv0().x();
                uv[i * 2 + 1] = v.uv0().y();
            }
            var mesh =
                    new PreviewMesh(
                            points,
                            geometry.triangleLookup().stream()
                                    .mapToInt(Integer::intValue)
                                    .toArray(),
                            geometry.sections().stream()
                                    .map(
                                            part ->
                                                    new PreviewMesh.Part(
                                                            part.id(),
                                                            (int) part.triangleStart(),
                                                            part.triangleCount()))
                                    .toList());
            var receipts = new HashMap<Long, String>();
            receipts.put(reference.modelFileDataId(), content);
            receipts.put(skin.fileDataId(), skin.file().contentKey());
            M2Appearance.ModelFacts facts = null;
            var opened =
                    files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
            if (opened.stream() != null) {
                try (var stream = opened.stream()) {
                    if (opened.state() == FileTypes.FileReadState.COMPLETE
                            && stream.holes().isEmpty()) {
                        facts = M2Appearance.readModel(AssetSources.file(stream), m, token).join();
                    }
                }
            }
            var counts = new HashMap<Integer, Integer>();
            for (var batch : skin.skin().batches()) {
                counts.merge(batch.sectionIndex(), 1, Integer::sum);
            }
            var textures = new ArrayList<MeshAppearance.Texture>();
            var decoded = new HashMap<Long, Integer>();
            var bindings = new ArrayList<MeshAppearance.Surface>();
            long pixels = 0;
            boolean complete = true;
            for (var batch : skin.skin().batches()) {
                token.check();
                if (counts.get(batch.sectionIndex()) != 1 || !simpleDraw(m, batch, facts)) {
                    TableProjection.note(
                            issues,
                            "Some sections have unsupported material profiles and no qualified texture binding.");
                    continue;
                }
                var texture = m.textures().get(m.textureCombos().get(batch.textureComboIndex()));
                long fid = textureId(texture, reference.textureVariations());
                if (fid <= 0 || fid > 0xffffffffL) {
                    complete = false;
                    TableProjection.note(
                            issues, "Some surface textures have no verified file binding.");
                    continue;
                }
                Integer index = decoded.get(fid);
                if (index == null) {
                    if (textures.size() >= 16) {
                        complete = false;
                        TableProjection.note(
                                issues, "Additional textures exceed the preview budget.");
                        continue;
                    }
                    var textureStat = files.stat(FileTypes.FileRef.id(fid), token).join();
                    if (textureStat.file() == null
                            || textureStat.state() != FileTypes.FileLookupState.FOUND) {
                        complete = false;
                        TableProjection.note(issues, "Texture #" + fid + " is unavailable.");
                        continue;
                    }
                    var image = decode(fid, token);
                    if (image == null) {
                        complete = false;
                        TableProjection.note(
                                issues, "Texture #" + fid + " is unavailable or unsupported.");
                        continue;
                    }
                    long count = (long) image.width() * image.height();
                    if (count > 8_388_608L - pixels) {
                        complete = false;
                        TableProjection.note(
                                issues, "Additional textures exceed the preview pixel budget.");
                        continue;
                    }
                    pixels += count;
                    byte[] rgba = image.rgba();
                    int[] argb = new int[image.width() * image.height()];
                    for (int i = 0; i < argb.length; i++) {
                        if ((i & 16383) == 0) {
                            token.check();
                        }
                        argb[i] =
                                (rgba[i * 4 + 3] & 255) << 24
                                        | (rgba[i * 4] & 255) << 16
                                        | (rgba[i * 4 + 1] & 255) << 8
                                        | (rgba[i * 4 + 2] & 255);
                    }
                    index = textures.size();
                    textures.add(new MeshAppearance.Texture(image.width(), image.height(), argb));
                    decoded.put(fid, index);
                    receipts.put(fid, textureStat.file().contentKey());
                }
                bindings.add(
                        new MeshAppearance.Surface(
                                batch.sectionIndex(),
                                index,
                                (texture.flags() & 1) != 0,
                                (texture.flags() & 2) != 0,
                                m.materials().get(batch.materialIndex()).blendMode() == 1));
            }
            var appearance = new MeshAppearance(mesh, uv, normals, bindings, textures);
            limitations(reference, mesh, issues);
            if (complete) {
                cache.save(key, appearance, receipts, token, issues);
            }
            return ready(reference, appearance, issues, false);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (IllegalArgumentException unavailable) {
            token.check();
            return failed(reference, issues, unavailable.getMessage());
        }
    }

    private DisplayModelTypes.Reference resolve(long id, Cancellation token, List<String> issues) {
        var displays = tables.schema("CreatureDisplayInfo", token, issues);
        displays.reference("ModelID", "CreatureModelData");
        var display = tables.get(displays, id, token, issues);
        if (display == null) {
            return null;
        }
        long modelId = displays.number(display, "ModelID");
        if (modelId <= 0 || modelId > 0xffffffffL) {
            return null;
        }
        var models = tables.schema("CreatureModelData", token, issues);
        models.reference("FileDataID", "FileData");
        var model = tables.get(models, modelId, token, issues);
        if (model == null) {
            return null;
        }
        long fid = models.number(model, "FileDataID");
        if (fid <= 0 || fid > 0xffffffffL) {
            return null;
        }
        var textures = new ArrayList<Long>();
        if (displays.has("TextureVariationFileDataID")) {
            var field =
                    displays.schema().columns().stream()
                            .filter(f -> f.name().equals("TextureVariationFileDataID"))
                            .findFirst()
                            .orElseThrow();
            int length = field.arrayLength();
            if (length != 3 && length != 4) {
                throw new IllegalArgumentException("Unreviewed creature texture array");
            }
            displays.field("TextureVariationFileDataID", true, length);
            if (field.foreignKey() == null
                    || !field.foreignKey().table().equals("FileData")
                    || !field.foreignKey().column().equals("ID")) {
                throw new IllegalArgumentException("Unverified creature texture reference");
            }
            for (int i = 0; i < length; i++) {
                textures.add(displays.element(display, "TextureVariationFileDataID", i, length));
            }
        }
        return new DisplayModelTypes.Reference(
                id,
                modelId,
                fid,
                textures,
                optional(displays, display, "ExtendedDisplayInfoID", issues),
                optional(models, model, "CreatureGeosetDataID", issues));
    }

    private static Long optional(
            TableProjection.Data data, Db2Types.Row row, String field, List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            return data.number(row, field);
        } catch (IllegalArgumentException unsupported) {
            TableProjection.note(issues, data.name() + "." + field + " is unavailable.");
            return null;
        }
    }

    static long textureId(M2Types.Texture texture, List<Long> variations) {
        if (texture.type() == 0) {
            return texture.fileDataId() == null ? 0 : texture.fileDataId();
        }
        if (texture.type() >= 11
                && texture.type() <= 13
                && texture.type() - 11 < variations.size()) {
            return variations.get((int) texture.type() - 11);
        }
        return 0;
    }

    static boolean simpleDraw(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts) {
        var material = model.materials().get(batch.materialIndex());
        var admission = M2Combiners.admit(batch.textureCount(), batch.shaderId());
        if (admission.profile() == null
                || admission.profile().stages().size() != 1
                || admission.profile().stages().getFirst() != M2Combiners.Coordinate.UV0
                || !Set.of(M2Combiners.Combiner.OPAQUE, M2Combiners.Combiner.MOD)
                        .contains(admission.profile().combiner())
                || batch.textureCount() != 1
                || batch.colorIndex() != -1
                || batch.priority() != 0
                // Offset 6 is a second section reference in the reviewed legacy 272 profile.
                // Modern layouts can use these bits for projection; keep those strict.
                || batch.flags2() != 0
                        && !(model.version() == 272 && batch.flags2() == batch.sectionIndex())
                || batch.materialLayer() != 0
                || batch.flags() != 0 && batch.flags() != 16
                || material.blendMode() > 1
                || material.flags() != 0 && material.flags() != 4) {
            return false;
        }
        // An absent coordinate lookup uses the combiner's verified implicit UV0 convention,
        // matching the existing M2Appearance admission. A present lookup must agree exactly.
        if (!model.textureCoordinates().isEmpty()
                        && (batch.textureCoordinateComboIndex() >= model.textureCoordinates().size()
                                || model.textureCoordinates()
                                                .get(batch.textureCoordinateComboIndex())
                                        != 0)
                || model.textureTransforms().get(batch.textureTransformComboIndex()) != 65535) {
            return false;
        }
        int weight = model.textureWeights().get(batch.textureWeightComboIndex());
        if (weight != 65535
                && (facts == null
                        || weight >= facts.staticWeights().size()
                        || !facts.staticWeights().get(weight))) {
            return false;
        }
        var texture = model.textures().get(model.textureCombos().get(batch.textureComboIndex()));
        return (texture.flags() & ~3L) == 0;
    }

    private Blp2Types.Image decode(long fid, Cancellation token) {
        if (generated != null) {
            return new BlpImages(generated)
                    .decodeLargestWithin(files, FileTypes.FileRef.id(fid), 2048, token)
                    .join()
                    .image();
        }
        var opened = files.openRead(FileTypes.FileRef.id(fid), token).join();
        if (opened.stream() == null) {
            return null;
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                return null;
            }
            return Blp2Decoder.decodeLargestWithin(
                            AssetSources.file(stream), 2048, new Blp2Types.Limits(), token)
                    .join();
        } catch (java.util.concurrent.CompletionException unavailable) {
            token.check();
            return null;
        }
    }

    private static void limitations(
            DisplayModelTypes.Reference reference, PreviewMesh mesh, List<String> issues) {
        TableProjection.note(
                issues,
                "Static rest geometry and simplified diffuse shading; animation, effects and runtime appearance are not evaluated.");
        if (reference.extendedDisplayInfoId() == null || reference.extendedDisplayInfoId() != 0) {
            TableProjection.note(
                    issues,
                    "Extended character appearance is unresolved; native geometry is retained.");
        }
        if (reference.creatureGeosetDataId() == null
                || reference.creatureGeosetDataId() != 0
                || mesh.parts().stream().anyMatch(p -> p.id() != 0)) {
            TableProjection.note(
                    issues,
                    "Surface visibility is unresolved; all recorded model surfaces are shown.");
        }
    }

    private static DisplayModelTypes.Preview ready(
            DisplayModelTypes.Reference reference,
            MeshAppearance appearance,
            List<String> issues,
            boolean restored) {
        if (appearance.surfaces().size() < appearance.mesh().parts().size()) {
            TableProjection.note(
                    issues,
                    "Some sections have no qualified material or texture binding; the preview has partial material coverage.");
        }
        return new DisplayModelTypes.Preview(
                DisplayModelTypes.State.PARTIAL,
                reference,
                appearance,
                appearance.mesh().parts().stream()
                        .map(PreviewMesh.Part::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                issues,
                restored,
                null);
    }

    private static DisplayModelTypes.Preview failed(
            DisplayModelTypes.Reference reference, List<String> issues, String message) {
        return new DisplayModelTypes.Preview(
                DisplayModelTypes.State.UNAVAILABLE,
                reference,
                null,
                Set.of(),
                issues,
                false,
                message);
    }

    private static String message(String message) {
        return message == null ? "" : ": " + message;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable cancelFailure = null;
        try {
            lifetime.cancel();
        } catch (Throwable failure) {
            cancelFailure = failure;
        }
        final Throwable failure = cancelFailure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        if (failure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(failure);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }
}
