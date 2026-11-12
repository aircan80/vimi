package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2SkeletonFile;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import com.vibeminer.rendering.models.TextureComposer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Reviewed current character composition profiles; optional unsupported shapes remain explicit. */
final class RaceAppearance {
    private final RaceMedia media;
    private final BuildFiles files;
    private final GeneratedStore generated;
    private final RaceAppearancePlan plans;
    private final RacesService service;

    /** The handle's persistent NPC appearance index, or null without one. */
    private final RaceNpcIndex index;

    static final int MAX_REMEMBERED = 6;
    static final long MAX_REMEMBERED_BYTES = 160L << 20;

    /** A complete result and the content keys of the model and layer files it was composed from. */
    private record Remembered(
            RaceAppearanceTypes.Result result, java.util.Map<Long, String> sources) {}

    /**
     * Complete composed appearances by request, most recently used last. Only results whose every
     * layer was read (or that were restored from such a derivative) are kept. Their other inputs
     * are this handle's build-scoped tables, and a hit is used only while the model and every layer
     * file still have the content keys that named its derivative; incomplete or failed results are
     * prepared again on the next request. Guarded by itself: prepare runs on the query worker and
     * render on the appearance worker.
     */
    private final java.util.LinkedHashMap<String, Remembered> remembered =
            new java.util.LinkedHashMap<>(16, 0.75f, true);

    private long rememberedBytes;

    RaceAppearance(
            RacesService owner,
            RaceMedia media,
            BuildTables tables,
            BuildFiles files,
            GeneratedStore generated) {
        this.media = media;
        this.files = files;
        this.generated = generated;
        this.service = owner;
        this.index = owner.npcIndex;
        plans = new RaceAppearancePlan(owner, media, tables);
    }

    boolean available() {
        return files != null && generated != null;
    }

    /**
     * What a complete NPC appearance is indexed under ({@link RaceNpcIndex}): its extended display
     * ID and the omission counts its answer carries.
     */
    record Indexing(long extendedDisplayInfoId, int omittedItems, int omittedAttachments) {}

    record Prepared(
            RaceMediaTypes.Preview base,
            RaceAppearancePlan.Plan plan,
            GeneratedKey key,
            List<String> issues,
            RaceAppearanceTypes.Result finished,
            String request,
            java.util.Map<Long, String> sources,
            long raceId,
            RaceAppearancePlan.Npc npc,
            Indexing indexing) {
        Prepared {
            issues = List.copyOf(issues);
            sources = sources == null ? java.util.Map.of() : java.util.Map.copyOf(sources);
        }

        Prepared(
                RaceMediaTypes.Preview base,
                RaceAppearancePlan.Plan plan,
                GeneratedKey key,
                List<String> issues,
                RaceAppearanceTypes.Result finished,
                String request,
                java.util.Map<Long, String> sources,
                long raceId,
                RaceAppearancePlan.Npc npc) {
            this(base, plan, key, issues, finished, request, sources, raceId, npc, null);
        }

        Prepared(
                RaceMediaTypes.Preview base,
                RaceAppearancePlan.Plan plan,
                GeneratedKey key,
                List<String> issues,
                RaceAppearanceTypes.Result finished,
                String request,
                java.util.Map<Long, String> sources) {
            this(base, plan, key, issues, finished, request, sources, 0, null);
        }
    }

    /** The remembered-result key of one selection; picks are ordered by option ID. */
    static String request(
            long raceId, long classId, long modelId, java.util.Map<Long, Long> picks) {
        return raceId + ":" + classId + ":" + modelId + ":" + new java.util.TreeMap<>(picks);
    }

    /** A remembered result whose source files are unchanged, or null. */
    private RaceAppearanceTypes.Result remembered(String request, Cancellation token) {
        Remembered entry;
        synchronized (remembered) {
            entry = remembered.get(request);
        }
        if (entry == null) {
            return null;
        }
        for (var source : entry.sources().entrySet()) {
            var stat = files.stat(FileTypes.FileRef.id(source.getKey()), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || !source.getValue().equals(stat.file().contentKey())) {
                forget(request, entry);
                return null;
            }
        }
        return entry.result();
    }

    private void forget(String request, Remembered entry) {
        synchronized (remembered) {
            if (remembered.remove(request, entry)) {
                rememberedBytes -= bytes(entry.result().appearance());
            }
        }
    }

    void remember(Prepared prepared, RaceAppearanceTypes.Result result) {
        remember(prepared, result, 0, null);
    }

    /** As above, with the model's skeleton among the sources when {@code skeleton} is not 0. */
    private void remember(
            Prepared prepared,
            RaceAppearanceTypes.Result result,
            long skeleton,
            String skeletonContent) {
        long bytes = bytes(result.appearance());
        if (prepared.request() == null
                || prepared.sources().isEmpty()
                || bytes > MAX_REMEMBERED_BYTES) {
            return;
        }
        var sources = new java.util.TreeMap<>(prepared.sources());
        if (skeleton != 0) {
            sources.put(skeleton, skeletonContent);
        }
        var entry = new Remembered(result, sources);
        synchronized (remembered) {
            var replaced = remembered.put(prepared.request(), entry);
            if (replaced != null) {
                rememberedBytes -= bytes(replaced.result().appearance());
            }
            rememberedBytes += bytes;
            var eldest = remembered.entrySet().iterator();
            while ((remembered.size() > MAX_REMEMBERED || rememberedBytes > MAX_REMEMBERED_BYTES)
                    && eldest.hasNext()) {
                var next = eldest.next();
                if (next.getValue() == entry) {
                    continue;
                }
                rememberedBytes -= bytes(next.getValue().result().appearance());
                eldest.remove();
            }
        }
    }

    /** Drops every remembered result; called once the workers have terminated. */
    void forget() {
        synchronized (remembered) {
            remembered.clear();
            rememberedBytes = 0;
        }
    }

    /** Remembered results, for tests. */
    int rememberedCount() {
        synchronized (remembered) {
            return remembered.size();
        }
    }

    /** Approximate retained heap of one composed appearance: texels and vertex arrays. */
    static long bytes(MeshAppearance appearance) {
        long total = 0;
        for (var texture : appearance.textures()) {
            total += 4L * texture.width() * texture.height();
        }
        var mesh = appearance.mesh();
        // Positions, normals, UVs, and indices.
        return total + 32L * mesh.vertexCount() + 12L * mesh.triangleCount();
    }

    /** The LOD0 draw SKIN and, when it was read, the textured sample's rest surface. */
    record DrawSurface(
            BuildModelTypes.SkinRead<M2Types.RestDrawSkin> skin, RaceRestSurface surface) {}

    /**
     * Derives the textured sample's surface. Package-visible so tests prove a race idle matches the
     * surface this sample shows.
     */
    static DrawSurface drawSurface(
            BuildModels models, BuildModelTypes.ModelRead model, Cancellation token) {
        var skin = models.readRestDrawSkin(model, 0, token).join();
        if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
            return new DrawSurface(skin, null);
        }
        return new DrawSurface(
                skin, RaceRestSurface.of(model.model(), skin.skin().surface(), token));
    }

    Prepared prepare(long raceId, long classId, long modelId, Cancellation token) {
        return prepare(raceId, classId, modelId, token, java.util.Map.of());
    }

    Prepared prepare(
            long raceId,
            long classId,
            long modelId,
            Cancellation token,
            java.util.Map<Long, Long> requested) {
        return prepare(raceId, classId, modelId, token, requested, null);
    }

    /**
     * A humanoid NPC's appearance (#385): its race model with its recorded customization choices,
     * its slot items as clothing geometry and its pre-baked body texture ({@link
     * RaceAppearancePlan.Npc}). What its items leave undrawn is counted by {@link #npcOmissions}.
     */
    Prepared prepareNpc(RaceNpcs.Spec spec, Cancellation token) {
        return prepareNpc(spec, token, null);
    }

    /** As above; a complete appearance is then written to the index under {@code indexing}. */
    Prepared prepareNpc(RaceNpcs.Spec spec, Cancellation token, Indexing indexing) {
        var prepared =
                prepare(
                        spec.raceId(),
                        spec.classId(),
                        spec.variant().chrModelId(),
                        token,
                        spec.choices(),
                        new RaceAppearancePlan.Npc(spec.items(), spec.bakeFileDataId()));
        return indexing == null || prepared.finished() != null
                ? prepared
                : new Prepared(
                        prepared.base(),
                        prepared.plan(),
                        prepared.key(),
                        prepared.issues(),
                        null,
                        prepared.request(),
                        prepared.sources(),
                        prepared.raceId(),
                        prepared.npc(),
                        indexing);
    }

    /**
     * An NPC's appearance from the index ({@link RaceNpcIndex}), its composite restored: null when
     * there is no entry, anything it was built from changed, or the composite cannot be restored.
     */
    RaceNpcs.Appearance indexed(long extendedDisplayInfoId, Cancellation token) {
        if (index == null || files == null || generated == null) {
            return null;
        }
        var entry = index.find(extendedDisplayInfoId, token);
        if (entry == null) {
            return null;
        }
        var restored = restore(entry.composite(), entry.modelFileDataId(), token);
        if (restored == null) {
            return null;
        }
        var parts =
                restored.appearance().mesh().parts().stream()
                        .map(com.vibeminer.rendering.models.PreviewMesh.Part::id)
                        .collect(java.util.stream.Collectors.toSet());
        if (!parts.containsAll(entry.visible())) {
            return null;
        }
        token.check();
        return new RaceNpcs.Appearance(
                new RaceAppearanceTypes.Result(
                        RaceTypes.State.PARTIAL,
                        restored.appearance(),
                        entry.visible(),
                        entry.items(),
                        entry.issues(),
                        true,
                        null),
                entry.omittedItems(),
                entry.omittedAttachments(),
                null,
                false);
    }

    /**
     * Writes a complete NPC appearance to the index: its prepared sources with the content keys the
     * composite's key named, its skeleton, and the attached item files.
     */
    private void index(
            Prepared prepared,
            RaceAppearanceTypes.Result result,
            long skeleton,
            String skeletonContent,
            Set<Long> attached,
            Cancellation token) {
        var indexing = prepared.indexing();
        if (index == null
                || indexing == null
                || prepared.key() == null
                || prepared.sources().isEmpty()
                || skeleton != 0 && skeletonContent == null) {
            return;
        }
        var known = new java.util.TreeMap<>(prepared.sources());
        if (skeleton != 0) {
            known.put(skeleton, skeletonContent);
        }
        index.write(
                indexing.extendedDisplayInfoId(),
                prepared.key(),
                prepared.base().variant().modelFileDataId(),
                result,
                indexing.omittedItems(),
                indexing.omittedAttachments(),
                known,
                attached,
                token);
    }

    /** What the NPC's items leave undrawn (item models and unread displays). */
    RaceAppearancePlan.NpcOmissions npcOmissions(RaceNpcs.Spec spec, Cancellation token) {
        return plans.npcOmissions(
                new RaceAppearancePlan.Npc(spec.items(), spec.bakeFileDataId()),
                token,
                new ArrayList<>());
    }

    private Prepared prepare(
            long raceId,
            long classId,
            long modelId,
            Cancellation token,
            java.util.Map<Long, Long> requested,
            RaceAppearancePlan.Npc npc) {
        var issues = new ArrayList<String>();
        String request =
                request(raceId, classId, modelId, requested)
                        + (npc == null ? "" : ":npc:" + npc.bakeFileDataId() + ":" + npc.items());
        try {
            if (files == null || generated == null) {
                return prepareFailure(
                        issues, "Texture preparation is unavailable for this workspace.");
            }
            String refused = RaceAppearancePlan.refusedPair(raceId, modelId);
            if (refused != null) {
                // Refused before any read, so a refused race's GPU preview is not held back.
                return prepareFailure(issues, refused);
            }
            var known = remembered(request, token);
            if (known != null) {
                return new Prepared(null, null, null, issues, known, request, null);
            }
            RaceCustomization.Selection selection;
            try {
                selection =
                        npc == null
                                ? new RaceCustomization(media)
                                        .select(raceId, classId, modelId, token, issues, requested)
                                : new RaceCustomization(media)
                                        .npc(raceId, classId, modelId, requested, token, issues);
            } catch (RacesService.Projection customizationRefused) {
                // Stated as a texture refusal here; the untextured preview keeps its own wording.
                throw RaceAppearancePlan.asTextureRefusal(customizationRefused);
            }
            var base = media.preview(raceId, classId, modelId, token, selection);
            boolean unread = media.unreadParts();
            base.issues().stream()
                    .filter(issue -> !issue.startsWith("Static native rest positions"))
                    .filter(issue -> requested.isEmpty() || !issue.startsWith("Sample look uses"))
                    .forEach(issues::add);
            if (npc != null) {
                issues.add("The NPC's recorded customization choices are shown.");
            } else if (!requested.isEmpty()) {
                issues.add(
                        "Requested known eligible choices and recorded defaults are shown; unknown unlock requirements are omitted.");
            }
            if (base.mesh() == null || base.variant() == null) {
                if (npc != null && media.modelUnreadable()) {
                    // The race model or its skin cannot be read now: asked again, not refused.
                    throw new RacesService.Unreadable(base.message());
                }
                return prepareFailure(issues, base.message());
            }
            var plan = plans.build(raceId, classId, base.variant(), token, issues, selection, npc);
            // A plan or sample that skipped a source only because it could not be read now is
            // shown but not remembered: without recorded sources the next request prepares again.
            unread |= plans.unsettled();
            if (npc != null && unread) {
                // An NPC is drawn whole or asked for again; never with what could not be read.
                throw new RacesService.Unreadable("Some NPC appearance rows cannot be read yet.");
            }
            base =
                    new RaceMediaTypes.Preview(
                            base.state(),
                            base.variant(),
                            base.mesh(),
                            RaceClothingGeometry.apply(
                                    base.mesh(), base.visibleParts(), plan.items()),
                            base.issues(),
                            base.restored(),
                            base.message());
            var stat =
                    files.stat(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
                return prepareFailure(issues, "The model root entry is unavailable.");
            }
            var sources = new java.util.TreeMap<Long, String>();
            sources.put(base.variant().modelFileDataId(), stat.file().contentKey());
            StringBuilder identity =
                    new StringBuilder(
                                    npc == null
                                            ? "race-static-appearance-v2\n"
                                            : "npc-baked-appearance-v4-attachments\n")
                            .append(MeshAppearanceCodec.REVISION)
                            .append('\n')
                            .append(M2RestDrawSkinReader.REVISION)
                            .append('\n')
                            .append(Blp2Decoder.REVISION)
                            .append(
                                    "\nstraight-alpha-source-over-nearest-v1\nhuman-orc-dwarf-night-elf-tauren-clothing-geosets-v6\n")
                            .append(files.build())
                            .append('\n')
                            .append(files.locale())
                            .append('\n')
                            .append(stat.file().contentKey())
                            .append('\n')
                            .append(
                                    "choice-vector-v1\nmostly-neutral-refused-v1\nenvironment-first-stage-v2\n")
                            .append(selection.byOption())
                            .append('\n')
                            .append(plan)
                            .append('\n')
                            .append(base.visibleParts().stream().sorted().toList());
            for (long fid :
                    plan.layers().stream()
                            .map(RaceAppearancePlan.Layer::fileDataId)
                            .distinct()
                            .sorted()
                            .toList()) {
                token.check();
                var source = files.stat(FileTypes.FileRef.id(fid), token).join();
                if (source.state() == FileTypes.FileLookupState.FOUND && source.file() != null) {
                    sources.put(fid, source.file().contentKey());
                }
                identity.append('\n')
                        .append(fid)
                        .append(':')
                        .append(source.state())
                        .append(':')
                        .append(source.file() == null ? "" : source.file().contentKey());
            }
            var key = appearanceKey(identity.toString(), plan.npc());
            return new Prepared(
                    base, plan, key, issues, null, request, unread ? null : sources, raceId, npc);
        } catch (RacesService.Projection unavailable) {
            if (npc != null && unavailable instanceof RacesService.Unreadable unreadable) {
                throw unreadable; // asked for again (RaceNpcs.Appearance#retry), not refused
            }
            return prepareFailure(issues, unavailable.getMessage());
        }
    }

    private static Prepared prepareFailure(List<String> issues, String message) {
        return new Prepared(null, null, null, issues, failed(issues, message), null, null);
    }

    RaceAppearanceTypes.Result render(Prepared prepared, Cancellation token) {
        if (prepared.finished() != null) {
            return prepared.finished();
        }
        var issues = new ArrayList<>(prepared.issues());
        var base = prepared.base();
        var plan = prepared.plan();
        var key = prepared.key();
        try {
            var restored = restore(key, base.variant().modelFileDataId(), token);
            if (restored != null) {
                if (restored.assumedWeights()) {
                    note(issues, RaceAppearancePlan.ASSUMED_WEIGHTS);
                }
                if (restored.environmentNotDrawn()) {
                    note(issues, RaceAppearancePlan.ENVIRONMENT_NOT_DRAWN);
                }
                var visible = base.visibleParts();
                if (prepared.npc() != null) {
                    // The attached items are part of the saved composite; the helmet's hiding
                    // rows are read again (cheap table reads).
                    visible =
                            RaceNpcAttachments.visible(
                                    visible,
                                    RaceNpcAttachments.hiddenGroups(
                                            service,
                                            prepared.npc().items(),
                                            prepared.raceId(),
                                            base.variant().sex(),
                                            token,
                                            issues),
                                    restored.appearance().mesh());
                    npcFacts(restored.appearance(), issues);
                }
                var result = result(restored.appearance(), visible, plan, issues, true);
                token.check();
                remember(prepared, result, restored.skeleton(), restored.skeletonContent());
                index(
                        prepared,
                        result,
                        restored.skeleton(),
                        restored.skeletonContent(),
                        Set.of(),
                        token);
                return result;
            }
            var images = new BlpImages(generated);
            var decoded = new HashMap<Long, MeshAppearance.Texture>();
            boolean complete = true;
            long decodedPixels = 0;
            for (long fid :
                    plan.layers().stream()
                            .map(RaceAppearancePlan.Layer::fileDataId)
                            .distinct()
                            .toList()) {
                token.check();
                var read =
                        images.decodeLargestWithin(
                                        files,
                                        FileTypes.FileRef.id(fid),
                                        plan.npc() ? RaceAppearancePlan.NPC_TEXTURE_SIDE : 2048,
                                        token)
                                .join();
                if (read.image() == null && plan.npc()) {
                    // An NPC without its bake (or any of its layers) would draw an untextured body.
                    String state =
                            read.state() + (read.fileState() == null ? "" : " " + read.fileState());
                    if (retryable(read)) {
                        throw new RacesService.Unreadable(
                                "NPC texture #" + fid + " cannot be read yet (" + state + ").");
                    }
                    return failed(
                            issues,
                            "NPC texture #"
                                    + fid
                                    + " is "
                                    + state
                                    + "; no incomplete NPC is drawn.");
                }
                if (read.image() == null) {
                    complete = false;
                    note(
                            issues,
                            "Texture #"
                                    + fid
                                    + " is "
                                    + read.state()
                                    + "; available layers are retained.");
                    continue;
                }
                var image = read.image();
                long imagePixels = (long) image.width() * image.height();
                if (imagePixels > 8_388_608L - decodedPixels) {
                    if (plan.npc()) {
                        return failed(issues, "NPC textures exceed the bounded texture budget.");
                    }
                    complete = false;
                    note(
                            issues,
                            "Additional diffuse images exceed the bounded texture preview budget.");
                    continue;
                }
                decodedPixels += imagePixels;
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
                decoded.put(fid, new MeshAppearance.Texture(image.width(), image.height(), argb));
            }
            if (!plan.npc()
                    && RaceAppearanceProfiles.nightElf(plan.profile())
                    && plan.layers().stream()
                            .noneMatch(
                                    layer ->
                                            RaceAppearancePlan.wholeBodyBase(layer, plan.canvases())
                                                    && decoded.containsKey(layer.fileDataId()))) {
                return failed(
                        issues,
                        "The base skin texture is unavailable; no incomplete body is presented.");
            }
            var textures = new ArrayList<MeshAppearance.Texture>();
            var textureTypes = new HashMap<Integer, Integer>();
            for (var canvas : plan.canvases()) {
                var layers = new ArrayList<TextureComposer.Layer>();
                for (var layer : plan.layers()) {
                    if (layer.type() == canvas.type() && decoded.containsKey(layer.fileDataId())) {
                        layers.add(
                                new TextureComposer.Layer(
                                        decoded.get(layer.fileDataId()),
                                        layer.x(),
                                        layer.y(),
                                        layer.width(),
                                        layer.height(),
                                        layer.blend() == 1
                                                ? TextureComposer.Blend.COPY
                                                : TextureComposer.Blend.SOURCE_OVER));
                    }
                }
                if (!layers.isEmpty()) {
                    textureTypes.put(canvas.type(), textures.size());
                    textures.add(
                            TextureComposer.compose(
                                    canvas.width(), canvas.height(), layers, token::isCanceled));
                }
            }
            if (textures.isEmpty()) {
                // For an NPC every layer decoded above, so this cannot happen there; for a player
                // preview it is the stated outcome of layers that are all unavailable.
                return failed(issues, "No reviewed diffuse texture layers are currently readable.");
            }
            var models = new BuildModels(files);
            var model =
                    models.readModel(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            if (model.state() != BuildModelTypes.ReadState.READ || model.model() == null) {
                if (plan.npc() && retryable(model.state())) {
                    throw new RacesService.Unreadable(
                            "The NPC model cannot be read yet (" + model.state() + ").");
                }
                return failed(issues, "The model could not be read for texture coordinates.");
            }
            var drawn = drawSurface(models, model, token);
            var skin = drawn.skin();
            if (drawn.surface() == null) {
                if (plan.npc() && skin != null && retryable(skin.state())) {
                    throw new RacesService.Unreadable(
                            "The NPC model surface cannot be read yet (" + skin.state() + ").");
                }
                return failed(
                        issues, "The model draw declarations are unavailable: " + skin.message());
            }
            var surface = drawn.surface();
            var mesh = surface.mesh();
            float[] uv = surface.uv(), normals = surface.normals();
            M2Appearance.ModelFacts facts = null;
            // A model that keeps its sequences in an external skeleton: their flags say where its
            // weight keys are. Not readable whole now, the weights stay unproven this time.
            boolean[] skeletonLater = {false};
            long skeletonId = model.model().skeletonFileDataId();
            String skeletonContent = null;
            if (skeletonId != 0) {
                var skeletonStat = files.stat(FileTypes.FileRef.id(skeletonId), token).join();
                if (skeletonStat.state() == FileTypes.FileLookupState.FOUND
                        && skeletonStat.file() != null) {
                    skeletonContent = skeletonStat.file().contentKey();
                } else {
                    // Without its content key, a skeleton that appears later could not
                    // invalidate this composite: it is shown but not saved or remembered.
                    complete = false;
                }
            }
            var skeleton = weightSkeleton(model.model(), skeletonLater, token);
            if (skeletonLater[0]) {
                if (plan.npc()) {
                    throw new RacesService.Unreadable(
                            "The NPC model's skeleton cannot be read whole yet.");
                }
                complete = false;
            }
            var open =
                    files.openRead(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            boolean holes = false;
            if (open.stream() != null) {
                try (var stream = open.stream()) {
                    holes = !stream.holes().isEmpty();
                    if (open.state() == FileTypes.FileReadState.COMPLETE && !holes) {
                        facts =
                                M2Appearance.readModel(
                                                AssetSources.file(stream),
                                                model.model(),
                                                skeleton,
                                                token)
                                        .join();
                    }
                }
            }
            if (facts == null) {
                // Without the model's static weight facts, weighted batches would be skipped
                // silently: an NPC is asked for again (or refused), never drawn partly, and a
                // player preview is shown but not saved or remembered.
                if (plan.npc()) {
                    if (holes || retryable(open.state())) {
                        throw new RacesService.Unreadable(
                                "The NPC model cannot be read whole yet (" + open.state() + ").");
                    }
                    return failed(issues, "The NPC model is " + open.state() + ".");
                }
                complete = false;
            }
            var bindings = new ArrayList<MeshAppearance.Surface>();
            var seen = new java.util.HashSet<Integer>();
            boolean assumeExternal = assumesExternalWeights(plan);
            boolean assumedWeights = false;
            boolean environmentNotDrawn = false;
            var sectionBatches = new HashMap<Integer, Integer>();
            for (var batch : skin.skin().batches()) {
                sectionBatches.merge(batch.sectionIndex(), 1, Integer::sum);
            }
            for (var batch : skin.skin().batches()) {
                boolean contextual = plan.profile() != RaceAppearanceProfiles.Profile.LEGACY;
                if (contextual
                        && !RaceAppearanceProfiles.reviewedDraw(
                                plan.profile(),
                                model.model(),
                                batch,
                                facts,
                                sectionBatches.get(batch.sectionIndex()),
                                assumeExternal)) {
                    continue;
                }
                // The static character preview only, like the weight assumption: an NPC (and so a
                // World creature) is unchanged.
                boolean firstStage =
                        !contextual
                                && !plan.npc()
                                && RaceAppearanceProfiles.environmentFirstStage(batch);
                if (!firstStage
                                && (batch.textureCount() != 1
                                        || !contextual && batch.shaderId() != 0)
                        || batch.colorIndex() != -1) {
                    continue;
                }
                var m = model.model();
                var material = m.materials().get(batch.materialIndex());
                if (material.blendMode() > 1 || material.flags() != 0 && material.flags() != 4) {
                    continue;
                }
                int transform = m.textureTransforms().get(batch.textureTransformComboIndex());
                if (transform != 65535) {
                    continue;
                }
                int weight = m.textureWeights().get(batch.textureWeightComboIndex());
                if (weight != 65535
                        && !RaceAppearanceProfiles.opaqueWeight(facts, weight, assumeExternal)) {
                    continue;
                }
                var texture = m.textures().get(m.textureCombos().get(batch.textureComboIndex()));
                Integer mapped = textureTypes.get((int) texture.type());
                if (mapped == null || !seen.add(batch.sectionIndex())) {
                    continue;
                }
                bindings.add(RaceAppearanceProfiles.surface(batch, material, texture, mapped));
                environmentNotDrawn |= firstStage;
                if (weight != 65535 && !facts.staticWeights().get(weight)) {
                    assumedWeights = true;
                }
            }
            if (bindings.isEmpty()) {
                // A lasting fact of this model and plan (no draw fits the reviewed static
                // profile): a stated refusal for players and NPCs alike, not a read failure.
                return failed(issues, RaceAppearancePlan.UNREVIEWED_SURFACES);
            }
            if (neutralShare(mesh, base.visibleParts(), bindings) >= NEUTRAL_LIMIT) {
                // Nearly the whole model would be drawn untextured (a dragonriding drake's body
                // and its scale colour, for one): refused, not shown as if textured.
                return failed(issues, RaceAppearancePlan.MOSTLY_UNREVIEWED_SURFACES);
            }
            if (assumedWeights) {
                note(issues, RaceAppearancePlan.ASSUMED_WEIGHTS);
            }
            if (environmentNotDrawn) {
                note(issues, RaceAppearancePlan.ENVIRONMENT_NOT_DRAWN);
            }
            var appearance = new MeshAppearance(mesh, uv, normals, bindings, textures);
            var visible = base.visibleParts();
            Set<Long> attachedFiles = Set.of();
            if (prepared.npc() != null) {
                var attached =
                        RaceNpcAttachments.attach(
                                service,
                                files,
                                generated,
                                model.model(),
                                appearance,
                                visible,
                                prepared.raceId(),
                                base.variant().sex(),
                                prepared.npc().items(),
                                token,
                                issues);
                appearance = attached.appearance();
                visible = attached.visible();
                // An item or texture not readable yet: shown, but not saved or remembered.
                complete &= attached.complete();
                attachedFiles = attached.files();
                attached.notes().forEach(text -> note(issues, text));
                npcFacts(appearance, issues);
            }
            if (complete) {
                save(
                        key,
                        base.variant().modelFileDataId(),
                        skin.fileDataId(),
                        skin.file().contentKey(),
                        skeletonId,
                        skeletonContent,
                        appearance,
                        assumedWeights,
                        environmentNotDrawn,
                        token,
                        issues);
            }
            var result = result(appearance, visible, plan, issues, false);
            if (complete) {
                token.check();
                remember(prepared, result, skeletonId, skeletonContent);
                index(prepared, result, skeletonId, skeletonContent, attachedFiles, token);
            }
            return result;
        } catch (RacesService.Projection unavailable) {
            if (plan.npc() && unavailable instanceof RacesService.Unreadable unreadable) {
                throw unreadable;
            }
            return failed(issues, unavailable.getMessage());
        }
    }

    /**
     * The model's external skeleton (SKID) for its weight proof, or null: none is named, the file
     * is not readable whole now ({@code later[0]} is then set), or it is not a valid skeleton.
     */
    private M2SkeletonFile.Skeleton weightSkeleton(
            M2Types.Model model, boolean[] later, Cancellation token) {
        long id = model.skeletonFileDataId();
        if (id == 0) {
            return null;
        }
        var open = files.openRead(FileTypes.FileRef.id(id), token).join();
        if (open.stream() == null) {
            later[0] = retryable(open.state());
            return null;
        }
        try (var stream = open.stream()) {
            if (open.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                later[0] = true;
                return null;
            }
            return M2SkeletonFile.read(AssetSources.file(stream), token).join();
        } catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof java.util.concurrent.CancellationException canceled) {
                throw canceled;
            }
            if (failure.getCause() instanceof com.vibeminer.formats.models.ModelFormatException) {
                return null;
            }
            throw failure;
        }
    }

    /** The NPC page facts every NPC appearance carries. */
    static final String WEAPONS_NOT_DRAWN = "Weapons are server-side and not drawn.";

    private static void npcFacts(MeshAppearance appearance, List<String> issues) {
        if (appearance.mesh().parts().stream()
                .anyMatch(part -> part.id() >= RaceNpcAttachments.FIRST_PART)) {
            note(issues, "Item models are attached at the head and shoulders in the rest pose.");
        }
        note(issues, WEAPONS_NOT_DRAWN);
    }

    /** Names the external-weight assumption in the key of every composite that may make it. */
    static final String ASSUMED_WEIGHTS_IDENTITY = "\nexternal-weights-assumed-opaque-v2";

    /**
     * The generated-store key of a composite. A preview composite may draw a weight track assumed
     * opaque, so its key names that assumption: a composite saved without it is never restored
     * here, nor one saved under it by code that does not make it. Every key also names the skeleton
     * weight proof.
     */
    static GeneratedKey appearanceKey(String identity, boolean npc) {
        return GeneratedKey.hash(
                (identity + SKELETON_WEIGHTS_IDENTITY + (npc ? "" : ASSUMED_WEIGHTS_IDENTITY))
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Names the weight proof read through the model's external skeleton, in every composite's key:
     * composites saved before it are reassessed, NPCs' too.
     */
    static final String SKELETON_WEIGHTS_IDENTITY = "\nweights-through-skeleton-v1";

    /**
     * Whether this render may treat a texture-weight track whose keys are stored outside the model
     * as full opacity (see {@link RaceAppearanceProfiles#opaqueWeight}). Only the static character
     * preview states that assumption; an NPC plan, and so a World creature, never does.
     */
    static boolean assumesExternalWeights(RaceAppearancePlan.Plan plan) {
        return !plan.npc();
    }

    /**
     * A model, skin or file read that cannot complete now but may later: a download is needed, the
     * store is unavailable or failed, or only part of the file is present yet. A file this build
     * does not list, a corrupt or unsupported one, or a protected one is not retried.
     */
    static boolean retryable(BuildModelTypes.ReadState state) {
        return switch (state) {
            case NEEDS_NETWORK, UNAVAILABLE, STORE_FAILED, PARTIAL -> true;
            default -> false;
        };
    }

    static boolean retryable(FileTypes.FileReadState state) {
        return state != null
                && switch (state) {
                    case NEEDS_NETWORK, UNAVAILABLE, STORE_FAILED, PARTIAL -> true;
                    default -> false;
                };
    }

    /**
     * A texture that cannot be read now but may be later (the store failed or is unavailable, or
     * the file needs a download), as opposed to one this build does not have or cannot decode.
     */
    static boolean retryable(BlpImageTypes.Result read) {
        return read.state() == BlpImageTypes.State.FILE_FAILED
                && read.fileState() != null
                && switch (read.fileState()) {
                    case NEEDS_NETWORK, UNAVAILABLE, STORE_FAILED -> true;
                    default -> false;
                };
    }

    /**
     * The share of visible drawn indices at or above which a composite is refused: nearly all of
     * the model would keep neutral shading.
     */
    static final double NEUTRAL_LIMIT = 0.9;

    /** Of the visible drawn indices, the share no reviewed binding textures (neutral shading). */
    static double neutralShare(
            com.vibeminer.rendering.models.PreviewMesh mesh,
            Set<Integer> visible,
            java.util.Collection<MeshAppearance.Surface> surfaces) {
        var bound =
                surfaces.stream()
                        .map(MeshAppearance.Surface::sectionIndex)
                        .collect(java.util.stream.Collectors.toSet());
        var parts = mesh.parts();
        long all = 0, neutral = 0;
        for (int index = 0; index < parts.size(); index++) {
            if (!visible.contains(parts.get(index).id())) {
                continue;
            }
            all += parts.get(index).indexCount();
            if (!bound.contains(index)) {
                neutral += parts.get(index).indexCount();
            }
        }
        return all == 0 ? 0 : (double) neutral / all;
    }

    private static RaceAppearanceTypes.Result result(
            MeshAppearance appearance,
            Set<Integer> visible,
            RaceAppearancePlan.Plan plan,
            List<String> issues,
            boolean restored) {
        var boundSections =
                appearance.surfaces().stream()
                        .map(MeshAppearance.Surface::sectionIndex)
                        .collect(java.util.stream.Collectors.toSet());
        var parts = appearance.mesh().parts();
        if (java.util.stream.IntStream.range(0, parts.size())
                .anyMatch(
                        index ->
                                visible.contains(parts.get(index).id())
                                        && !boundSections.contains(index))) {
            note(
                    issues,
                    "Some surfaces retain neutral shading because their draw bindings are outside the reviewed diffuse profile.");
        }
        note(
                issues,
                "Diffuse source files: "
                        + plan.layers().stream()
                                .map(RaceAppearancePlan.Layer::fileDataId)
                                .distinct()
                                .sorted()
                                .toList());
        note(
                issues,
                "Static diffuse sample and recorded new-character clothing only; animation, lighting shaders and separate equipment attachments are not evaluated.");
        return new RaceAppearanceTypes.Result(
                RaceTypes.State.PARTIAL,
                appearance,
                visible,
                plan.items().stream()
                        .map(
                                item ->
                                        new RaceAppearanceTypes.Item(
                                                item.itemId(),
                                                item.inventoryType(),
                                                item.displayId()))
                        .toList(),
                issues,
                restored,
                null);
    }

    private static RaceAppearanceTypes.Result failed(List<String> issues, String message) {
        return new RaceAppearanceTypes.Result(
                RaceTypes.State.UNAVAILABLE, null, Set.of(), List.of(), issues, false, message);
    }

    private static final int MAGIC = 0x56414331;

    /** A composite whose draws include a weight track assumed opaque (see {@link #render}). */
    private static final int ASSUMED_MAGIC = 0x56414332;

    /** As {@link #MAGIC} and {@link #ASSUMED_MAGIC}, for a model with an external skeleton. */
    private static final int SKELETON_MAGIC = 0x56414333, SKELETON_ASSUMED_MAGIC = 0x56414334;

    /**
     * Added to any of the magics above for a composite that leaves out some surfaces'
     * environment-reflection stage ({@code VAC5} to {@code VAC8}), so its page fact is restored.
     */
    private static final int ENVIRONMENT_MAGIC_OFFSET = 4;

    /** A restored composite; {@code skeleton} is 0 for a model without an external one. */
    private record Restored(
            MeshAppearance appearance,
            boolean assumedWeights,
            boolean environmentNotDrawn,
            long skeleton,
            String skeletonContent) {}

    @SuppressWarnings("try")
    private Restored restore(GeneratedKey key, long modelId, Cancellation token) {
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var size = generated.readRange(key, 0, 0, lower).join();
            if (size.state() != GeneratedTypes.ReadState.PRESENT
                    || size.length() < 40
                    || size.length() > 64L + MeshAppearanceCodec.MAX_BYTES) {
                return null;
            }
            var saved = generated.read(key, lower).join();
            token.check();
            if (saved.state() != GeneratedTypes.ReadState.PRESENT) {
                return null;
            }
            var bytes = ByteBuffer.wrap(saved.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            int stored = bytes.getInt();
            boolean environment =
                    stored > SKELETON_ASSUMED_MAGIC
                            && stored <= SKELETON_ASSUMED_MAGIC + ENVIRONMENT_MAGIC_OFFSET;
            int magic = environment ? stored - ENVIRONMENT_MAGIC_OFFSET : stored;
            boolean skeletal = magic == SKELETON_MAGIC || magic == SKELETON_ASSUMED_MAGIC;
            if (magic != MAGIC && magic != ASSUMED_MAGIC && !skeletal
                    || bytes.getLong() != modelId) {
                return null;
            }
            long skin = bytes.getLong();
            byte[] content = new byte[16];
            bytes.get(content);
            long skeleton = 0;
            String skeletonContent = null;
            if (skeletal) {
                skeleton = bytes.getLong();
                byte[] skeletonKey = new byte[16];
                bytes.get(skeletonKey);
                skeletonContent = HexFormat.of().formatHex(skeletonKey);
                if (skeleton < 1 || skeleton > 0xffffffffL) {
                    return null;
                }
                // The weights were proven through this skeleton: a changed one is reassessed.
                var skeletonStat = files.stat(FileTypes.FileRef.id(skeleton), token).join();
                if (skeletonStat.state() != FileTypes.FileLookupState.FOUND
                        || skeletonStat.file() == null
                        || !skeletonStat.file().contentKey().equals(skeletonContent)) {
                    return null;
                }
            }
            int length = bytes.getInt();
            if (length != bytes.remaining() || skin < 1 || skin > 0xffffffffL) {
                return null;
            }
            var stat = files.stat(FileTypes.FileRef.id(skin), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || !stat.file().contentKey().equals(HexFormat.of().formatHex(content))) {
                return null;
            }
            byte[] payload = new byte[length];
            bytes.get(payload);
            return new Restored(
                    MeshAppearanceCodec.decode(payload),
                    magic == ASSUMED_MAGIC || magic == SKELETON_ASSUMED_MAGIC,
                    environment,
                    skeleton,
                    skeletonContent);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException invalid) {
            token.check();
            return null;
        }
    }

    @SuppressWarnings("try")
    private void save(
            GeneratedKey key,
            long modelId,
            long skinId,
            String content,
            long skeleton,
            String skeletonContent,
            MeshAppearance appearance,
            boolean assumedWeights,
            boolean environmentNotDrawn,
            Cancellation token,
            List<String> issues) {
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            byte[] payload = MeshAppearanceCodec.encode(appearance);
            int header = skeleton == 0 ? 40 : 64;
            var bytes = ByteBuffer.allocate(header + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(
                            (skeleton == 0
                                            ? assumedWeights ? ASSUMED_MAGIC : MAGIC
                                            : assumedWeights
                                                    ? SKELETON_ASSUMED_MAGIC
                                                    : SKELETON_MAGIC)
                                    + (environmentNotDrawn ? ENVIRONMENT_MAGIC_OFFSET : 0))
                    .putLong(modelId)
                    .putLong(skinId)
                    .put(HexFormat.of().parseHex(content));
            if (skeleton != 0) {
                bytes.putLong(skeleton).put(HexFormat.of().parseHex(skeletonContent));
            }
            bytes.putInt(payload.length).put(payload);
            generated.delete(List.of(key), lower).join();
            generated.put(List.of(new GeneratedTypes.Put(key, bytes.array())), lower).join();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            note(issues, "The composed appearance derivative could not be saved.");
        }
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }

    static boolean supports(String table, List<String> layouts) {
        String expected =
                switch (table) {
                    case "ChrRaces" -> "4F44C796";
                    case "ChrModel" -> "03FAB755";
                    case "ChrCustomizationOption" -> "DCC2A86E";
                    case "ChrCustomizationChoice" -> "9559C358";
                    case "ChrCustomizationElement" -> "6483C37E";
                    case "ChrCustomizationGeoset" -> "BF55F2FB";
                    case "ChrCustomizationReq" -> "CA154412";
                    case "ChrCustomizationReqChoice" -> "F925BC6F";
                    case "ChrCustomizationMaterial" -> "BE9767E9";
                    case "ChrModelMaterial" -> "22469480";
                    case "ChrModelTextureLayer" -> "D0583FB4";
                    case "CharComponentTextureSections" -> "2173BA71";
                    case "TextureFileData" -> "BD7C74C2";
                    case "ComponentTextureFileData" -> "B32B030A";
                    case "CharacterLoadout" -> "713CE8BB";
                    case "CharacterLoadoutItem" -> "0C7A1862";
                    case "Item" -> "9A2A4834";
                    case "ItemModifiedAppearance" -> "03A6C979";
                    case "ItemAppearance" -> "481C4281";
                    case "ItemDisplayInfo" -> "9F3AB8A9";
                    case "ItemDisplayInfoMaterialRes" -> "AA462C0E";
                    // Humanoid NPC appearances (#385).
                    case "CreatureDisplayInfoExtra" -> "4D9FE25C";
                    case "CreatureDisplayInfoOption" -> "2F331C33";
                    case "NPCModelItemSlotDisplayInfo" -> "C2057F5B";
                    default -> null;
                };
        return expected != null && layouts.contains(expected);
    }

    static void requireProfile(RacesService.Data data) {
        if (!supports(data.name, data.schema.layoutHashes())) {
            throw data.unsupported("Unreviewed static appearance layout");
        }
    }

    static boolean reviewedPhysicalInteger(
            String table,
            List<String> layouts,
            String name,
            com.vibeminer.formats.definitions.TableSchema.SchemaColumn field,
            com.vibeminer.formats.db2.Db2Types.Column column) {
        var raw = column.interpretation();
        return table.equals("ChrCustomizationGeoset")
                && layouts.contains("BF55F2FB")
                && name.equals("GeosetType")
                && field.arrayLength() == 1
                && java.util.Objects.equals(field.bits(), 8)
                && Boolean.FALSE.equals(field.unsigned())
                && column.narrowing() == null
                && raw != null
                && raw.policy().equals("raw-physical-integer-v1")
                && raw.physicalBits() == 32
                && !raw.physicalSigned()
                && raw.candidateBits() == 8
                && !raw.candidateUnsigned();
    }

    static boolean reviewedField(String table, List<String> layouts, String field) {
        return switch (table) {
            case "ChrRaces" -> layouts.contains("4F44C796") && field.equals("PlayableRaceBit");
            case "ChrCustomizationOption" ->
                    layouts.contains("DCC2A86E") && field.equals("Requirement");
            case "ChrCustomizationChoice" ->
                    layouts.contains("9559C358")
                            && Set.of("ChrCustomizationReqID", "ChrCustomizationVisReqID")
                                    .contains(field);
            case "ChrCustomizationReq" ->
                    layouts.contains("CA154412")
                            && Set.of(
                                            "ReqType",
                                            "ClassMask",
                                            "RaceMasks",
                                            "RegionGroupMask",
                                            "ReqAchievementID",
                                            "ReqQuestID",
                                            "OverrideArchive",
                                            "ReqItemModifiedAppearanceID")
                                    .contains(field);
            case "ChrCustomizationElement" ->
                    layouts.contains("6483C37E") && field.equals("ChrCustomizationMaterialID");
            case "ChrCustomizationReqChoice" ->
                    layouts.contains("F925BC6F") && field.equals("ChrCustomizationReqID");
            case "ChrModel" ->
                    layouts.contains("03FAB755") && field.equals("CharComponentTextureLayoutID");
            case "ChrModelMaterial" ->
                    layouts.contains("22469480")
                            && Set.of(
                                            "CharComponentTextureLayoutsID",
                                            "TextureType",
                                            "Width",
                                            "Height",
                                            "Flags")
                                    .contains(field);
            case "ChrModelTextureLayer" ->
                    layouts.contains("D0583FB4")
                            && Set.of(
                                            "CharComponentTextureLayoutsID",
                                            "ChrModelTextureTargetID",
                                            "TextureType",
                                            "Layer",
                                            "BlendMode",
                                            "TextureSectionTypeBitMask",
                                            "TextureSectionTypeBitMask2",
                                            "Flags")
                                    .contains(field);
            case "CharComponentTextureSections" ->
                    layouts.contains("2173BA71")
                            && Set.of(
                                            "CharComponentTextureLayoutID",
                                            "SectionType",
                                            "X",
                                            "Y",
                                            "Width",
                                            "Height")
                                    .contains(field);
            case "ChrCustomizationMaterial" ->
                    layouts.contains("BE9767E9")
                            && Set.of("ChrModelTextureTargetID", "MaterialResourcesID")
                                    .contains(field);
            case "TextureFileData" ->
                    layouts.contains("BD7C74C2")
                            && Set.of("UsageType", "MaterialResourcesID").contains(field);
            case "ComponentTextureFileData" ->
                    layouts.contains("B32B030A")
                            && Set.of("GenderIndex", "ClassID", "RaceID").contains(field);
            case "CharacterLoadout" ->
                    layouts.contains("713CE8BB")
                            && Set.of("ChrClassID", "Purpose", "Racemask").contains(field);
            case "CharacterLoadoutItem" ->
                    layouts.contains("0C7A1862")
                            && Set.of("CharacterLoadoutID", "ItemID").contains(field);
            case "Item" -> layouts.contains("9A2A4834") && field.equals("InventoryType");
            case "ItemModifiedAppearance" ->
                    layouts.contains("03A6C979")
                            && Set.of("ItemID", "ItemAppearanceModifierID", "ItemAppearanceID")
                                    .contains(field);
            case "ItemAppearance" ->
                    layouts.contains("481C4281") && field.equals("ItemDisplayInfoID");
            case "ItemDisplayInfo" ->
                    layouts.contains("9F3AB8A9")
                            && Set.of("GeosetGroup", "GeosetGroupOverride", "ModelResourcesID")
                                    .contains(field);
            case "ItemDisplayInfoMaterialRes" ->
                    layouts.contains("AA462C0E")
                            && Set.of(
                                            "ComponentSection",
                                            "MaterialResourcesID",
                                            "ItemDisplayInfoID")
                                    .contains(field);
            default -> false;
        };
    }
}
