package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2BindPoseFacts;
import com.vibeminer.formats.models.M2Combiners;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2RestSkinReader;
import com.vibeminer.formats.models.M2SkeletonFile;
import com.vibeminer.formats.models.M2StandWeights;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.models.ModelFormatException;
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
import java.util.Map;
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
    /**
     * Saved-preview policy. v5 (#361): an absent CreatureGeosetDataID column now resolves to 0
     * rather than null, which changes the recorded reference of those displays. v6: a type-2
     * (object skin) texture of a model with no other replaceable slot takes the display's first
     * texture variation ({@link #objectSkinFromVariation}).
     */
    public static final String POLICY = "display-static-materials-env-unlit-bindcards-v6";

    private final TableProjection tables;
    private final BuildTables rawTables;
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

    /** Bound on CreatureDisplayInfoGeosetData rows read once per component. */
    static final int GEOSET_ROWS = 262_144;

    /** Displays with CreatureDisplayInfoGeosetData rows; read once on the worker, then reused. */
    private Map<Long, Map<Integer, Integer>> geosetDisplays;

    /** Why geosetDisplays could not be read, once tried; null when read or not yet tried. */
    private String geosetDisplaysUnavailable;

    /** Transient (busy or closed) failures of that read so far; bounded by GEOSET_RETRIES. */
    private int geosetDisplaysTransient;

    /** Transient failures of the geoset-table read tolerated before it is refused for good. */
    static final int GEOSET_RETRIES = 3;

    static final String GEOSET_TABLE = "CreatureDisplayInfoGeosetData";

    public DisplayModelService(BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this.tables = new TableProjection(tables);
        this.rawTables = tables;
        this.files = Objects.requireNonNull(files);
        this.generated = generated;
        cache = new DisplayPreviewCache(generated, files);
    }

    @Override
    public CompletableFuture<DisplayModelTypes.Preview> preview(
            long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> load(displayId, false, request));
    }

    /**
     * The World's binding of the display (#361): each section draws texture 1 of its first batch
     * that the World objects' batch rule admits ({@link #worldBatch}), as an opaque or alpha-keyed
     * diffuse surface; a section with none draws its first constant-weight additive batch ({@link
     * #worldAdditiveBatch}) as an unlit additive surface, else its first constant-weight
     * alpha-blended batch ({@link #worldBlendedBatch}) as a blended surface. With a Stand pose, an
     * additive or blended batch whose weight fades is taken at the Stand sample instead ({@link
     * #standAlpha}). {@link #preview} keeps the reviewed material profiles. Saved under its own key
     * ({@link #WORLD_POLICY}).
     */
    @Override
    public CompletableFuture<DisplayModelTypes.Preview> worldPreview(
            long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> load(displayId, true, request));
    }

    /**
     * Reads the display's table relationships on the worker, without its model. A table shape
     * outside the reviewed profile answers null, so the caller asks {@link #worldPreview}, which
     * states that refusal; busy and closed handles still propagate as transient.
     */
    @Override
    public CompletableFuture<DisplayModelTypes.Reference> reference(
            long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(
                token,
                request -> {
                    try {
                        return resolve(displayId, request, new ArrayList<>());
                    } catch (IllegalArgumentException unreviewed) {
                        request.check();
                        return null;
                    }
                });
    }

    @Override
    public CompletableFuture<DisplayModelTypes.Idle> idle(long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> loadStands(displayId, Profiles.PREVIEW, request).preview());
    }

    @Override
    public CompletableFuture<DisplayModelTypes.Idle> worldIdle(long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> loadStands(displayId, Profiles.WORLD, request).world());
    }

    @Override
    public CompletableFuture<List<DisplayModelTypes.Idle>> stands(
            long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(
                token,
                request -> {
                    var both = loadStands(displayId, Profiles.BOTH, request);
                    return List.of(both.preview(), both.world());
                });
    }

    private static void checkDisplay(long displayId) {
        if (displayId <= 0 || displayId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid display ID");
        }
    }

    private <T> CompletableFuture<T> submit(
            Cancellation token, java.util.function.Function<Cancellation, T> work) {
        var result = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new DisplayModelTypes.ServiceUnavailable(
                                "Display previews closed", true, null));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var owner = lifetime.onCancel(request::cancel)) {
                                request.check();
                                result.complete(work.apply(request));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                result.completeExceptionally(
                        new DisplayModelTypes.ServiceUnavailable(
                                "Display previews busy", false, full));
            }
        }
        var published = new CompletableFuture<T>();
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

    /** Which Stand profiles one parse answers. */
    private enum Profiles {
        PREVIEW,
        WORLD,
        BOTH
    }

    @Override
    public CompletableFuture<DisplayModelTypes.BodySize> bodySize(
            long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> readBodySize(displayId, request));
    }

    private DisplayModelTypes.BodySize readBodySize(long id, Cancellation token) {
        var issues = new ArrayList<String>();
        try {
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
            var model = tables.get(models, modelId, token, issues);
            if (model == null) {
                return null;
            }
            double[] box = new double[6];
            for (int i = 0; i < 6; i++) {
                box[i] = models.decimal(model, "GeoBox", i, 6);
            }
            return new DisplayModelTypes.BodySize(
                    box,
                    models.decimal(model, "ModelScale", 0, 1),
                    displays.decimal(display, "CreatureModelScale", 0, 1));
        } catch (java.util.concurrent.CompletionException | IllegalStateException failed) {
            throw tableFailure(failed);
        } catch (java.util.NoSuchElementException unreviewed) {
            throw new IllegalArgumentException("Unreviewed display table shape", unreviewed);
        }
    }

    @Override
    public CompletableFuture<java.util.Map<Integer, DisplayModelTypes.Idle>> worldSequences(
            long displayId, int[] animationIds, Cancellation token) {
        checkDisplay(displayId);
        int[] ids = animationIds.clone();
        return submit(token, request -> loadSequences(displayId, ids, request));
    }

    /**
     * The World answers of several sequences from one parse of the model and its rest surface. Only
     * a creature display whose bones live in the model is read (its base rig, as the World's
     * Stand); anything else answers UNSUPPORTED for every sequence.
     */
    private java.util.Map<Integer, DisplayModelTypes.Idle> loadSequences(
            long displayId, int[] ids, Cancellation token) {
        var out = new java.util.LinkedHashMap<Integer, DisplayModelTypes.Idle>();
        var issues = new ArrayList<String>();
        DisplayModelTypes.Reference reference = resolve(displayId, token, issues);
        java.util.function.Function<
                        DisplayModelTypes.Idle, java.util.Map<Integer, DisplayModelTypes.Idle>>
                all =
                        idle -> {
                            for (int id : ids) {
                                out.put(id, idle);
                            }
                            return out;
                        };
        if (reference == null) {
            return all.apply(
                    DisplayModelTypes.Idle.unavailable(
                            "Display-to-model relationship is unavailable."));
        }
        var models = new BuildModels(files);
        var read =
                models.readModel(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
        if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
            return all.apply(idleFailed(reference, "Model read is " + read.state()));
        }
        var skin = models.readRestDrawSkin(read, 0, token).join();
        if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
            return all.apply(idleFailed(reference, "Model surface is " + skin.state()));
        }
        var m = read.model();
        if (!creatureDisplay(reference) || m.skeletonFileDataId() != 0) {
            return all.apply(
                    new DisplayModelTypes.Idle(
                            DisplayModelTypes.IdleState.UNSUPPORTED,
                            reference,
                            null,
                            "Body sequences are read only for creature displays whose bones live in the model."));
        }
        var geometry = skin.skin().surface();
        var surface = restSurface(m, geometry, token);
        var opened =
                files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
        if (opened.stream() == null) {
            return all.apply(idleFailed(reference, "Model source is unavailable"));
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                return all.apply(idleFailed(reference, "Model source is incomplete"));
            }
            for (int id : ids) {
                token.check();
                try {
                    var idle =
                            M2IdleAnimation.readSequence(AssetSources.file(stream), m, id, token)
                                    .join();
                    out.put(id, admitted(reference, m, geometry, surface, idle, true, token));
                } catch (RuntimeException failure) {
                    token.check();
                    var cause = AssetTasks.unwrap(failure);
                    out.put(
                            id,
                            cause instanceof ModelFormatException format
                                            && format.code().equals("MODEL_UNSUPPORTED")
                                    ? new DisplayModelTypes.Idle(
                                            DisplayModelTypes.IdleState.UNSUPPORTED,
                                            reference,
                                            null,
                                            format.getMessage())
                                    : idleFailed(
                                            reference,
                                            "Sequence "
                                                    + id
                                                    + " could not be read"
                                                    + message(
                                                            cause == null
                                                                    ? null
                                                                    : cause.getMessage())));
                }
            }
        }
        return out;
    }

    /** The preview and World Stand answers of one parse; a profile not asked is null. */
    private record Stands(DisplayModelTypes.Idle preview, DisplayModelTypes.Idle world) {
        static Stands both(DisplayModelTypes.Idle idle) {
            return new Stands(idle, idle);
        }
    }

    private Stands loadStands(long displayId, Profiles profiles, Cancellation token) {
        var issues = new ArrayList<String>();
        DisplayModelTypes.Reference reference = null;
        try {
            reference = resolve(displayId, token, issues);
            if (reference == null) {
                return Stands.both(
                        DisplayModelTypes.Idle.unavailable(
                                "Display-to-model relationship is unavailable."));
            }
            var models = new BuildModels(files);
            var read =
                    models.readModel(FileTypes.FileRef.id(reference.modelFileDataId()), token)
                            .join();
            if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
                return Stands.both(idleFailed(reference, "Model read is " + read.state()));
            }
            var skin = models.readRestDrawSkin(read, 0, token).join();
            if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
                return Stands.both(idleFailed(reference, "Model surface is " + skin.state()));
            }
            var m = read.model();
            var geometry = skin.skin().surface();
            var surface = restSurface(m, geometry, token);
            var stand = readStand(reference, m, token);
            if (stand.idle() == null) {
                return Stands.both(stand.answer());
            }
            var idle = stand.idle();
            return new Stands(
                    profiles == Profiles.WORLD
                            ? null
                            : admitted(reference, m, geometry, surface, idle, false, token),
                    profiles == Profiles.PREVIEW
                            ? null
                            : admitted(reference, m, geometry, surface, idle, true, token));
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (IllegalArgumentException unavailable) {
            token.check();
            return Stands.both(idleFailed(reference, unavailable.getMessage()));
        }
    }

    /** A read Stand, or (idle null) the answer that stands for every profile instead. */
    record StandRead(M2IdleAnimation.Idle idle, DisplayModelTypes.Idle answer) {}

    /**
     * Reads the display's Stand sequence from the model source: the base rig's for a creature
     * display, otherwise with bone files refused ({@link #routeStand}). The pose the previews and
     * the World draw and the World binding's Stand texture weights ({@link #standWeights}) both
     * come from here.
     */
    private StandRead readStand(
            DisplayModelTypes.Reference reference, M2Types.Model m, Cancellation token) {
        var opened =
                files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
        if (opened.stream() == null) {
            return new StandRead(null, idleFailed(reference, "Model source is unavailable"));
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                return new StandRead(null, idleFailed(reference, "Model source is incomplete"));
            }
            try {
                // A creature display (no extended display info) has no customization choice,
                // so no bone file applies and the base rig's Stand plays (#384).
                return new StandRead(
                        routeStand(
                                        reference,
                                        () ->
                                                baseRigStand(
                                                        files, m, AssetSources.file(stream), token),
                                        () ->
                                                M2IdleAnimation.read(
                                                        AssetSources.file(stream), m, token))
                                .join(),
                        null);
            } catch (RuntimeException failure) {
                token.check();
                var cause = AssetTasks.unwrap(failure);
                if (cause instanceof ModelFormatException format
                        && format.code().equals("MODEL_UNSUPPORTED")) {
                    return new StandRead(
                            null,
                            new DisplayModelTypes.Idle(
                                    DisplayModelTypes.IdleState.UNSUPPORTED,
                                    reference,
                                    null,
                                    format.getMessage()));
                }
                return new StandRead(
                        null,
                        idleFailed(
                                reference,
                                "Idle sequence could not be read"
                                        + message(cause == null ? null : cause.getMessage())));
            }
        }
    }

    /**
     * One profile's answer for a read Stand: the preview's strict admission, or the World's ({@link
     * DisplayIdleAnimations#admitWorld}), which lists the vertices it leaves out.
     */
    static DisplayModelTypes.Idle admitted(
            DisplayModelTypes.Reference reference,
            M2Types.Model m,
            M2Types.RestSkin geometry,
            RestSurface surface,
            M2IdleAnimation.Idle idle,
            boolean world,
            Cancellation token) {
        try {
            var admitted =
                    world
                            ? DisplayIdleAnimations.admitWorld(
                                    m, geometry, idle, surface.mesh(), surface.normals(), token)
                            : new DisplayIdleAnimations.WorldStand(
                                    // The preview poses per camera view.
                                    DisplayIdleAnimations.admitFacingCamera(
                                            m,
                                            geometry,
                                            idle,
                                            surface.mesh(),
                                            surface.normals(),
                                            token),
                                    new int[0]);
            var animation = admitted.animation();
            int leftOut = admitted.leftOut().length;
            return new DisplayModelTypes.Idle(
                    DisplayModelTypes.IdleState.AVAILABLE,
                    reference,
                    animation,
                    "Stand idle · "
                            + animation.durationMillis()
                            + " ms loop · "
                            + animation.boneCount()
                            + " bones evaluated in software"
                            + (leftOut == 0
                                    ? "."
                                    : " · "
                                            + leftOut
                                            + " vertices weighted to billboard bones left out."),
                    admitted.leftOut());
        } catch (DisplayIdleAnimations.Unsupported unsupported) {
            return new DisplayModelTypes.Idle(
                    DisplayModelTypes.IdleState.UNSUPPORTED,
                    reference,
                    null,
                    unsupported.getMessage());
        }
    }

    /**
     * Runs {@code baseRig} for a creature display and {@code gated} (bone files refused) for any
     * other display, including one whose extended display info is unread.
     */
    static <T> T routeStand(
            DisplayModelTypes.Reference reference,
            java.util.function.Supplier<T> baseRig,
            java.util.function.Supplier<T> gated) {
        return creatureDisplay(reference) ? baseRig.get() : gated.get();
    }

    /**
     * The base rig's Stand: from the external skeleton file when the model names one, otherwise
     * from the model source.
     */
    static java.util.concurrent.CompletableFuture<M2IdleAnimation.Idle> baseRigStand(
            BuildFiles files,
            M2Types.Model m,
            com.vibeminer.formats.AssetSource modelSource,
            Cancellation token) {
        return m.skeletonFileDataId() != 0
                ? readSkeletonStand(files, m, token)
                : M2IdleAnimation.readBaseRig(modelSource, m, token);
    }

    /**
     * The base rig's Stand from the model's external skeleton file (#384). A skeleton that is not
     * stored completely is unavailable rather than refused.
     */
    static java.util.concurrent.CompletableFuture<M2IdleAnimation.Idle> readSkeletonStand(
            BuildFiles files, M2Types.Model m, Cancellation token) {
        var opened = files.openRead(FileTypes.FileRef.id(m.skeletonFileDataId()), token).join();
        if (opened.stream() == null) {
            throw new IllegalArgumentException("Skeleton source is " + opened.state());
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                throw new IllegalArgumentException("Skeleton source is incomplete");
            }
            var source = AssetSources.file(stream);
            var skeleton = M2SkeletonFile.read(source, token).join();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    M2IdleAnimation.readSkeleton(source, skeleton, m, token).join());
        }
    }

    /** A display without extended (character) appearance: no customization choice applies. */
    static boolean creatureDisplay(DisplayModelTypes.Reference reference) {
        return reference.extendedDisplayInfoId() != null && reference.extendedDisplayInfoId() == 0;
    }

    private static DisplayModelTypes.Idle idleFailed(
            DisplayModelTypes.Reference reference, String message) {
        return new DisplayModelTypes.Idle(
                DisplayModelTypes.IdleState.UNAVAILABLE,
                reference,
                null,
                message == null ? "Idle animation is unavailable." : message);
    }

    record RestSurface(PreviewMesh mesh, float[] normals, float[] uv) {}

    static RestSurface restSurface(M2Types.Model m, M2Types.RestSkin geometry, Cancellation token) {
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
                        geometry.triangleLookup().stream().mapToInt(Integer::intValue).toArray(),
                        geometry.sections().stream()
                                .map(
                                        part ->
                                                new PreviewMesh.Part(
                                                        part.id(),
                                                        (int) part.triangleStart(),
                                                        part.triangleCount()))
                                .toList());
        return new RestSurface(mesh, normals, uv);
    }

    private DisplayModelTypes.Preview load(long displayId, boolean world, Cancellation token) {
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
                            (world
                                            ? WORLD_POLICY
                                                    + "\n"
                                                    + M2StandWeights.REVISION
                                                    + "\n"
                                                    + M2IdleAnimation.REVISION
                                                    + "\n"
                                                    + DisplayIdleAnimations.POLICY
                                                    + "\n"
                                            : "")
                                    + POLICY
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
                                    + M2BindPoseFacts.REVISION
                                    + "\n"
                                    + Blp2Decoder.REVISION
                                    + "\n"
                                    + MeshAppearanceCodec.REVISION
                                    + "\n"
                                    + files.build()
                                    + "\n"
                                    + files.locale()
                                    + "\n"
                                    // The geoset-row answer is not part of the saved preview.
                                    + withoutDisplayGeosets(reference)
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
                if (world) {
                    TableProjection.note(issues, WORLD_NOTE);
                }
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
            var surface = restSurface(m, geometry, token);
            var mesh = surface.mesh();
            float[] normals = surface.normals(), uv = surface.uv();
            var receipts = new HashMap<Long, String>();
            receipts.put(reference.modelFileDataId(), content);
            receipts.put(skin.fileDataId(), skin.file().contentKey());
            M2Appearance.ModelFacts facts = null;
            M2BindPoseFacts.Facts bindFacts = null;
            boolean sourceFactsComplete = true;
            try {
                var opened =
                        files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token)
                                .join();
                if (opened.stream() != null) {
                    try (var stream = opened.stream()) {
                        if (opened.state() == FileTypes.FileReadState.COMPLETE
                                && stream.holes().isEmpty()) {
                            var source = AssetSources.file(stream);
                            try {
                                facts = M2Appearance.readModel(source, m, token).join();
                            } catch (RuntimeException unavailable) {
                                token.check();
                                sourceFactsComplete = false;
                                TableProjection.note(
                                        issues,
                                        "Static opacity facts are unavailable; affected material sections are unresolved.");
                            }
                            if (m.bones().stream().anyMatch(bone -> bone.flags() == 8)) {
                                try {
                                    bindFacts = M2BindPoseFacts.read(source, m, token).join();
                                } catch (RuntimeException unavailable) {
                                    token.check();
                                    sourceFactsComplete = false;
                                    TableProjection.note(
                                            issues,
                                            "Own-track facts for billboard cards are unavailable.");
                                }
                            }
                        } else {
                            sourceFactsComplete = false;
                            TableProjection.note(
                                    issues,
                                    "Material source facts are unavailable; retry can reassess them.");
                        }
                    }
                } else {
                    sourceFactsComplete = false;
                    TableProjection.note(
                            issues,
                            "Material source facts are unavailable; retry can reassess them.");
                }
            } catch (RuntimeException unavailable) {
                token.check();
                facts = null;
                bindFacts = null;
                sourceFactsComplete = false;
                TableProjection.note(
                        issues, "Material source facts are unavailable; retry can reassess them.");
            }
            var counts = new HashMap<Integer, Integer>();
            for (var batch : skin.skin().batches()) {
                counts.merge(batch.sectionIndex(), 1, Integer::sum);
            }
            long additivePasses =
                    skin.skin().batches().stream()
                            .filter(
                                    batch ->
                                            m.materials().get(batch.materialIndex()).blendMode()
                                                    == 4)
                            .count();
            M2Types.GeometrySkin cardGeometry = null;
            if (additivePasses == 1 && bindFacts != null) {
                try {
                    var geometryRead = models.readGeometrySkin(read, 0, token).join();
                    if (geometryRead.state() == BuildModelTypes.ReadState.READ
                            && geometryRead.skin() != null
                            && geometryRead.fileDataId() == skin.fileDataId()
                            && geometryRead.file() != null
                            && geometryRead.file().contentKey().equals(skin.file().contentKey())) {
                        cardGeometry = geometryRead.skin();
                    } else {
                        sourceFactsComplete = false;
                        TableProjection.note(
                                issues, "Billboard skin palette facts are unavailable.");
                    }
                } catch (RuntimeException unavailable) {
                    token.check();
                    sourceFactsComplete = false;
                    TableProjection.note(issues, "Billboard skin palette facts are unavailable.");
                }
            }
            var pool =
                    new TexturePool(
                            reference,
                            objectSkinFromVariation(m.textures()),
                            receipts,
                            token,
                            issues);
            var bindings = new ArrayList<MeshAppearance.Surface>();
            boolean worldFactsMissing = false;
            var billboards = new ArrayList<MeshAppearance.RigidBillboard>();
            if (world) {
                // Fading additive or blended weights are taken at the Stand sample (#384).
                var stand =
                        fading(m, skin.skin().batches(), facts)
                                ? standWeights(reference, m, geometry, surface, token)
                                : StandSample.NONE;
                worldFactsMissing =
                        !worldSurfaces(
                                        m,
                                        skin.skin().batches(),
                                        facts,
                                        stand,
                                        pool,
                                        bindings,
                                        issues,
                                        token)
                                || stand.retry();
                TableProjection.note(issues, WORLD_NOTE);
            }
            for (var batch : world ? List.<M2Types.Batch>of() : skin.skin().batches()) {
                token.check();
                var profile = DisplayMaterialProfiles.admit(m, batch, facts);
                if (counts.get(batch.sectionIndex()) != 1 || profile == null) {
                    TableProjection.note(
                            issues,
                            "Some sections have unsupported material profiles and no qualified texture binding.");
                    continue;
                }
                if (profile.kind() == DisplayMaterialProfiles.Kind.ENVIRONMENT
                        && !usableNormals(geometry, batch.sectionIndex(), normals, token)) {
                    TableProjection.note(
                            issues, "An environment-mapped section has unusable source normals.");
                    continue;
                }
                List<MeshAppearance.RigidBillboard> cards = List.of();
                if (profile.kind() == DisplayMaterialProfiles.Kind.ADDITIVE_CARD) {
                    cards =
                            additivePasses == 1
                                    ? DisplayRigidCards.admit(
                                            m,
                                            geometry,
                                            cardGeometry,
                                            batch.sectionIndex(),
                                            bindFacts,
                                            token)
                                    : null;
                    if (cards == null || cards.isEmpty()) {
                        TableProjection.note(
                                issues,
                                "A glow section has no reviewed rigid bind-pose billboard proof.");
                        continue;
                    }
                }
                var stages = new ArrayList<MeshAppearance.TextureStage>();
                for (int stage = 0; stage < profile.stages(); stage++) {
                    var texture =
                            m.textures()
                                    .get(m.textureCombos().get(batch.textureComboIndex() + stage));
                    Integer index = pool.texture(texture);
                    if (index == null) {
                        break;
                    }
                    stages.add(
                            new MeshAppearance.TextureStage(
                                    index,
                                    profile.coordinate(stage),
                                    (texture.flags() & 1) != 0,
                                    (texture.flags() & 2) != 0));
                }
                if (stages.size() != profile.stages()) {
                    continue;
                }
                bindings.add(
                        new MeshAppearance.Surface(
                                batch.sectionIndex(),
                                new MeshAppearance.Material(
                                        profile.combiner(),
                                        stages,
                                        profile.lighting(),
                                        profile.blend())));
                billboards.addAll(cards);
            }
            var appearance =
                    new MeshAppearance(mesh, uv, normals, bindings, pool.textures, billboards);
            limitations(reference, mesh, issues);
            // The World rule reads material source facts only for additive and blended texture
            // weights, so their absence (or a Stand sample that may still be read) stops a save
            // only when such a batch needed them.
            if ((world ? !worldFactsMissing : sourceFactsComplete) && pool.complete) {
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

    /**
     * The display's model and texture references. A table shape outside the reviewed profile is a
     * stated refusal ({@link IllegalArgumentException}, which every caller turns into an
     * unavailable result with the reason). A busy table queue or a closed table handle is
     * transient: it is raised as {@link DisplayModelTypes.ServiceUnavailable} so callers neither
     * cache it nor count it as a failure. Any other exception propagates unchanged.
     */
    // Package-private for tests: resolves without reading any model file.
    DisplayModelTypes.Reference resolve(long id, Cancellation token, List<String> issues) {
        try {
            return resolveReviewed(id, token, issues);
        } catch (java.util.concurrent.CompletionException | IllegalStateException failed) {
            throw tableFailure(failed);
        } catch (java.util.NoSuchElementException unreviewed) {
            throw new IllegalArgumentException("Unreviewed display table shape", unreviewed);
        }
    }

    private DisplayModelTypes.Reference resolveReviewed(
            long id, Cancellation token, List<String> issues) {
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
                // Only a truly absent column means no geoset data; an unreadable one stays null.
                models.has("CreatureGeosetDataID")
                        ? optional(models, model, "CreatureGeosetDataID", issues)
                        : Long.valueOf(0),
                displayGeosets(id, token, issues));
    }

    /**
     * Whether the display has CreatureDisplayInfoGeosetData rows (its own geoset selections); null
     * when that is not known. The table is read once per component, on the worker thread:
     *
     * <ul>
     *   <li>a table the build does not have (NOT_IN_BUILD, as in older builds) means no rows for
     *       any display, like an absent column;
     *   <li>a table that is present but partial, over {@link #GEOSET_ROWS}, without the reviewed
     *       {@code CreatureDisplayInfoID -> CreatureDisplayInfo} relationship, or failing in an
     *       unexpected way is refused for the component, with the reason noted on every display;
     *   <li>a busy queue or closed handle is not cached; after {@link #GEOSET_RETRIES} of them the
     *       read is refused too, so a stuck table never costs a full read per display;
     *   <li>a cancelled read is never cached.
     * </ul>
     */
    private Map<Integer, Integer> displayGeosets(long id, Cancellation token, List<String> issues) {
        if (geosetDisplays == null && geosetDisplaysUnavailable == null) {
            try {
                geosetDisplays = readGeosetDisplays(token, issues);
            } catch (java.util.concurrent.CancellationException canceled) {
                throw canceled;
            } catch (RuntimeException failed) {
                token.check();
                var classified = tableFailure(failed);
                if (classified instanceof java.util.concurrent.CancellationException canceled) {
                    throw canceled;
                }
                if (classified instanceof DisplayModelTypes.ServiceUnavailable transientFailure
                        && ++geosetDisplaysTransient < GEOSET_RETRIES) {
                    TableProjection.note(
                            issues,
                            "Display geoset selections are unknown for now: "
                                    + transientFailure.getMessage());
                    return null;
                }
                geosetDisplaysUnavailable =
                        classified instanceof IllegalArgumentException refused
                                ? refused.getMessage()
                                : GEOSET_TABLE
                                        + " could not be read: "
                                        + failed.getClass().getSimpleName();
            }
        }
        if (geosetDisplays == null) {
            TableProjection.note(
                    issues, "Display geoset selections are unknown: " + geosetDisplaysUnavailable);
            return null;
        }
        return geosetDisplays.getOrDefault(id, Map.of());
    }

    private Map<Long, Map<Integer, Integer>> readGeosetDisplays(
            Cancellation token, List<String> issues) {
        var present =
                rawTables
                        .schema(
                                com.vibeminer.formats.tables.TableTypes.Ref.forName(GEOSET_TABLE),
                                com.vibeminer.formats.tables.TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (present.table() != null
                && present.table().state()
                        == com.vibeminer.formats.tables.TableTypes.State.NOT_IN_BUILD) {
            return Map.of();
        }
        var data = tables.read(GEOSET_TABLE, GEOSET_ROWS, token, issues);
        if (data.entry().state() != com.vibeminer.formats.tables.TableTypes.State.READY) {
            throw new IllegalArgumentException(GEOSET_TABLE + " is partial");
        }
        data.reference("CreatureDisplayInfoID", "CreatureDisplayInfo");
        var found = new HashMap<Long, Map<Integer, Integer>>();
        for (var row : data.rows()) {
            long display = data.number(row, "CreatureDisplayInfoID");
            long index = data.number(row, "GeosetIndex"), value = data.number(row, "GeosetValue");
            if (index < 0 || index > 254 || value < 0 || value > 99) {
                throw new IllegalArgumentException(
                        GEOSET_TABLE + " records a geoset outside the reviewed profile");
            }
            // GeosetIndex + 1 is the group (wowdev: meshId = (geosetIndex + 1) * 100 + value).
            found.computeIfAbsent(display, d -> new HashMap<>())
                    .merge(
                            (int) index + 1,
                            (int) value,
                            (a, b) ->
                                    a.equals(b)
                                            ? a
                                            : DisplayModelTypes.Reference.CONFLICTING_GEOSET);
        }
        var result = new HashMap<Long, Map<Integer, Integer>>();
        for (var entry : found.entrySet()) {
            result.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    /**
     * Classifies a table call's failure: cancellation and stated refusals unwrapped, a busy queue
     * or closed handle as {@link DisplayModelTypes.ServiceUnavailable}, anything else unchanged.
     */
    static RuntimeException tableFailure(RuntimeException failure) {
        Throwable cause =
                failure instanceof java.util.concurrent.CompletionException
                                && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
        if (cause instanceof java.util.concurrent.CancellationException canceled) {
            return canceled;
        }
        if (cause instanceof IllegalArgumentException refused) {
            return refused;
        }
        if (cause instanceof com.vibeminer.formats.tables.TableWorkScheduler.BusyException) {
            return new DisplayModelTypes.ServiceUnavailable("Display tables busy", false, cause);
        }
        if (cause instanceof IllegalStateException
                && "Table handle closed".equals(cause.getMessage())) {
            return new DisplayModelTypes.ServiceUnavailable("Display tables closed", true, cause);
        }
        if (cause instanceof DisplayModelTypes.ServiceUnavailable unavailable) {
            return unavailable;
        }
        return failure;
    }

    private static DisplayModelTypes.Reference withoutDisplayGeosets(
            DisplayModelTypes.Reference reference) {
        return new DisplayModelTypes.Reference(
                reference.displayId(),
                reference.modelId(),
                reference.modelFileDataId(),
                reference.textureVariations(),
                reference.extendedDisplayInfoId(),
                reference.creatureGeosetDataId());
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

    /**
     * The World binding rule's revision; part of the World binding's saved key only. v5: type-2
     * object skin slots resolve as in {@link #textureId}.
     */
    public static final String WORLD_POLICY = "display-world-batch-rule-v5";

    /** The World binding's stated approximation, noted on every World binding. */
    public static final String WORLD_NOTE =
            "World batch rule: each section draws texture 1 of its first Diffuse_T1 opaque or"
                    + " alpha-keyed batch, else of its first additive one (unlit), else of its"
                    + " first alpha-blended one, whose texture weight stays full or, with a Stand"
                    + " pose, is not zero at the Stand sample (alpha scaled by it); environment and"
                    + " add layers, color tracks, opaque texture weights and transforms, and render"
                    + " flags other than unlit on blended batches are not evaluated.";

    /**
     * Whether a batch is drawn under the World objects' batch rule (#360, {@code
     * WorldExteriorObjects.copyModel}): material layer 0, at least one texture, a combiner whose
     * texture 1 is sampled with UV0 ({@link M2Combiners#firstTextureUsesUv0}, shared with World
     * objects), and blend 0 (opaque) or 1 (alpha key). Color, texture weight, texture transform,
     * priority and render flags are not consulted, as for objects.
     *
     * <p>Four intended differences from World objects ({@link #worldSurfaces}):
     *
     * <ul>
     *   <li>Blend 2 is skipped rather than claiming the section, so a later opaque or alpha-keyed
     *       batch on the section may draw instead; a section with none may draw it in the blended
     *       pass ({@link #worldBlendedBatch}).
     *   <li>Texture types 11 to 13 resolve to the display's monster skin, and type 2 to its first
     *       variation on a model with no other replaceable slot ({@link #objectSkinFromVariation});
     *       objects take type 0 only.
     *   <li>A section with no such batch may draw an additive one ({@link #worldAdditiveBatch});
     *       World objects draw no M2 additive batch.
     *   <li>A batch whose texture does not resolve or decode leaves the section to a later batch;
     *       objects claim the section before decoding.
     * </ul>
     */
    static boolean worldBatch(M2Types.Model model, M2Types.Batch batch) {
        return worldShape(model, batch)
                && model.materials().get(batch.materialIndex()).blendMode() <= 1;
    }

    /** M2 blend mode 4, GxBlend_Add (source alpha, one): the World's additive pass. */
    static final int ADD_BLEND = 4;

    /**
     * Whether a batch is drawn as an additive surface by the World: the shape {@link #worldBatch}
     * requires, blend mode 4 (source colour times alpha added, the World objects' additive pass),
     * and a texture weight that is absent or full in every key ({@code facts}), so a part the
     * client fades in or pulses is not drawn at full strength. {@code facts} may be null (not
     * readable): a batch with a weight track is then not drawn. Colour tracks are not consulted, as
     * for opaque batches.
     */
    static boolean worldAdditiveBatch(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts) {
        return worldSortedBatch(model, batch, facts, ADD_BLEND);
    }

    /** M2 blend mode 2, GxBlend_Alpha (source alpha, one minus source alpha): the blended pass. */
    static final int ALPHA_BLEND = 2;

    /**
     * Whether a batch is drawn as a blended surface by the World: the shape {@link #worldBatch}
     * requires, blend mode 2 (source-alpha over, the World objects' blended pass, which draws M2
     * blend 2 for objects too) and, as for additive batches, a texture weight that is absent or
     * full in every key, since the weight scales the batch's alpha. Lit unless the material's unlit
     * flag (0x1) is set. Colour tracks are not consulted.
     */
    static boolean worldBlendedBatch(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts) {
        return worldSortedBatch(model, batch, facts, ALPHA_BLEND);
    }

    private static boolean worldSortedBatch(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts, int blend) {
        if (!worldShape(model, batch)
                || model.materials().get(batch.materialIndex()).blendMode() != blend) {
            return false;
        }
        return constantWeight(model, batch, facts) == Boolean.TRUE;
    }

    /** Whether the batch's texture weight is absent or full in every key; null without facts. */
    private static Boolean constantWeight(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts) {
        int lookup = batch.textureWeightComboIndex();
        if (lookup < 0 || lookup >= model.textureWeights().size()) {
            return false;
        }
        int weight = model.textureWeights().get(lookup);
        if (weight == 65535) {
            return true;
        }
        if (facts == null || facts.model() != model) {
            return null;
        }
        return weight < facts.staticWeights().size() && facts.staticWeights().get(weight);
    }

    /**
     * Whether a batch the additive or blended pass could take has a weight that is not constant
     * full: only then does the World binding read the Stand sample.
     */
    private static boolean fading(
            M2Types.Model model, List<M2Types.Batch> batches, M2Appearance.ModelFacts facts) {
        for (var batch : batches) {
            if (worldShape(model, batch)) {
                int blend = model.materials().get(batch.materialIndex()).blendMode();
                if ((blend == ADD_BLEND || blend == ALPHA_BLEND)
                        && constantWeight(model, batch, facts) == Boolean.FALSE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The World binding's texture weights at the Stand sample, or {@link #NONE} (weights null): the
     * display has no Stand pose, or it could not be read, with the reason. {@code retry}: the
     * answer may change when read again (a source not stored yet), so the binding is not saved.
     */
    record StandSample(List<M2StandWeights.Weight> weights, String reason, boolean retry) {
        static final StandSample NONE = new StandSample(null, null, false);
    }

    /**
     * Reads the Stand the World draws the display in ({@link #readStand} then the World admission
     * {@link #admitted}, as {@link #worldIdle} answers it) and every texture weight at its sample
     * ({@link M2StandWeights}). A display whose World Stand is not available has no Stand pose: the
     * World draws its rest pose, and fading weights keep the constant-weight rule.
     */
    private StandSample standWeights(
            DisplayModelTypes.Reference reference,
            M2Types.Model m,
            M2Types.RestSkin geometry,
            RestSurface surface,
            Cancellation token) {
        var stand = readStand(reference, m, token);
        if (stand.idle() == null) {
            return new StandSample(
                    null,
                    stand.answer().message(),
                    stand.answer().state() == DisplayModelTypes.IdleState.UNAVAILABLE);
        }
        var world = admitted(reference, m, geometry, surface, stand.idle(), true, token);
        if (world.state() != DisplayModelTypes.IdleState.AVAILABLE) {
            return new StandSample(null, world.message(), false);
        }
        var opened =
                files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
        if (opened.stream() == null) {
            return new StandSample(null, "Model source is unavailable", true);
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                return new StandSample(null, "Model source is incomplete", true);
            }
            return new StandSample(
                    M2StandWeights.read(AssetSources.file(stream), m, stand.idle(), token).join(),
                    null,
                    false);
        } catch (RuntimeException failure) {
            // Retried, never saved, as the pose path treats an UNAVAILABLE Stand and the material
            // path unavailable static opacity facts. A lasting malformation cannot be told apart
            // here: on a complete source the model parse accepted (it checks the weights span),
            // the whole read fails with MODEL_INVALID, the same code as the source differing from
            // the parsed model or changing during the read, which a later read can clear. A
            // malformed track already fails the static opacity facts (retried the same way); one
            // that reaches M2StandWeights becomes that track's refusal, which is saved.
            token.check();
            var cause = AssetTasks.unwrap(failure);
            return new StandSample(
                    null,
                    "Stand texture weights could not be read"
                            + message(cause == null ? null : cause.getMessage()),
                    true);
        }
    }

    /**
     * The 8-bit alpha scale of a batch whose weight is not constant full, at the Stand sample: 0
     * (not drawn) without a Stand pose, for an undecided weight or one that rounds to 0 there; 255
     * for one that rounds to full; otherwise the rounded weight, which scales the texture's alpha.
     * Each case that leaves the batch out is noted.
     */
    static int standAlpha(
            M2Types.Model model, M2Types.Batch batch, StandSample stand, List<String> issues) {
        if (stand.weights() == null) {
            TableProjection.note(
                    issues,
                    "A fading texture weight is not drawn without a Stand pose"
                            + (stand.reason() == null ? "." : ": " + stand.reason()));
            return 0;
        }
        int track = model.textureWeights().get(batch.textureWeightComboIndex());
        if (track >= stand.weights().size()) {
            TableProjection.note(
                    issues, "A texture weight names a track the model does not declare.");
            return 0;
        }
        var weight = stand.weights().get(track);
        if (!weight.resolved()) {
            TableProjection.note(
                    issues,
                    "A fading texture weight is not drawn: at the Stand sample, "
                            + weight.refusal()
                            + ".");
            return 0;
        }
        int alpha = Math.round(weight.value() * 255);
        TableProjection.note(
                issues,
                alpha == 0
                        ? "Stand texture weight: a fading section is hidden at the Stand sample."
                        : alpha == 255
                                ? "Stand texture weight: a fading section is full at the Stand"
                                        + " sample."
                                : "Stand texture weight: a fading section draws with its Stand"
                                        + " weight scaling its alpha.");
        return alpha;
    }

    /** The World rule's shape: layer 0, a UV0 texture 1 and in-range references. */
    private static boolean worldShape(M2Types.Model model, M2Types.Batch batch) {
        if (batch.materialLayer() != 0
                || batch.textureCount() < 1
                || batch.shaderId() < 0
                || batch.shaderId() > 0xffff
                || batch.materialIndex() < 0
                || batch.materialIndex() >= model.materials().size()
                || batch.textureComboIndex() < 0
                || batch.textureComboIndex() >= model.textureCombos().size()
                || model.textureCombos().get(batch.textureComboIndex()) < 0
                || model.textureCombos().get(batch.textureComboIndex())
                        >= model.textures().size()) {
            return false;
        }
        return M2Combiners.firstTextureUsesUv0(batch.textureCount(), batch.shaderId());
    }

    /**
     * Binds, per section, texture 1 of the first batch {@link #worldBatch} admits whose texture
     * resolves (file, the display's monster skin for types 11 to 13, or for type 2 its first
     * variation on a model with no other replaceable slot); a batch whose texture does not resolve
     * leaves the section to a later batch, as World objects do. A section left unbound then takes
     * the first {@link #worldAdditiveBatch} whose texture resolves, as an unlit additive surface,
     * and a section still unbound its first {@link #worldBlendedBatch} whose texture resolves, as a
     * blended surface; so an opaque or alpha-keyed batch always claims its section first, and no
     * additive binding changes. An additive or blended batch whose weight fades is taken at the
     * Stand sample ({@code stand}, {@link #standAlpha}). False when an additive or blended
     * candidate's weight could not be decided (no facts): the binding is then not saved.
     */
    private static boolean worldSurfaces(
            M2Types.Model model,
            List<M2Types.Batch> batches,
            M2Appearance.ModelFacts facts,
            StandSample stand,
            TexturePool pool,
            List<MeshAppearance.Surface> bindings,
            List<String> issues,
            Cancellation token) {
        var bound = new java.util.HashSet<Integer>();
        for (var batch : batches) {
            token.check();
            if (bound.contains(batch.sectionIndex()) || !worldBatch(model, batch)) {
                continue;
            }
            var texture =
                    model.textures().get(model.textureCombos().get(batch.textureComboIndex()));
            Integer index = pool.texture(texture);
            if (index == null) {
                continue;
            }
            bindings.add(
                    new MeshAppearance.Surface(
                            batch.sectionIndex(),
                            index,
                            (texture.flags() & 1) != 0,
                            (texture.flags() & 2) != 0,
                            model.materials().get(batch.materialIndex()).blendMode() == 1));
            bound.add(batch.sectionIndex());
        }
        boolean additive =
                sortedSurfaces(
                        model, batches, facts, stand, pool, bindings, issues, bound, ADD_BLEND,
                        token);
        boolean blended =
                sortedSurfaces(
                        model,
                        batches,
                        facts,
                        stand,
                        pool,
                        bindings,
                        issues,
                        bound,
                        ALPHA_BLEND,
                        token);
        return additive && blended;
    }

    /**
     * Binds each section not yet in {@code bound} to texture 1 of its first batch of the given
     * blend mode ({@link #ADD_BLEND}: unlit additive; {@link #ALPHA_BLEND}: blended, lit unless the
     * material is unlit) whose texture resolves and whose weight is constant full or, with a Stand
     * pose ({@code stand}), not zero at the Stand sample ({@link #standAlpha}): a weight that fades
     * to zero there is hidden in the pose the World draws, and a partial one scales the texture's
     * alpha. Without a Stand pose a fading weight is not drawn. False when a candidate's weight
     * could not be decided.
     */
    private static boolean sortedSurfaces(
            M2Types.Model model,
            List<M2Types.Batch> batches,
            M2Appearance.ModelFacts facts,
            StandSample stand,
            TexturePool pool,
            List<MeshAppearance.Surface> bindings,
            List<String> issues,
            java.util.Set<Integer> bound,
            int blend,
            Cancellation token) {
        boolean decided = true;
        for (var batch : batches) {
            token.check();
            if (bound.contains(batch.sectionIndex())
                    || !worldShape(model, batch)
                    || model.materials().get(batch.materialIndex()).blendMode() != blend) {
                continue;
            }
            var constant = constantWeight(model, batch, facts);
            if (constant == null) {
                decided = false;
                continue;
            }
            int alpha = 255;
            if (!constant) {
                alpha = standAlpha(model, batch, stand, issues);
                if (alpha == 0) {
                    continue;
                }
            }
            var texture =
                    model.textures().get(model.textureCombos().get(batch.textureComboIndex()));
            Integer index = pool.texture(texture);
            if (index != null && alpha < 255) {
                index = pool.scaled(index, alpha);
            }
            if (index == null) {
                continue;
            }
            boolean unlit =
                    blend == ADD_BLEND
                            || (model.materials().get(batch.materialIndex()).flags() & 1) != 0;
            bindings.add(
                    new MeshAppearance.Surface(
                            batch.sectionIndex(),
                            new MeshAppearance.Material(
                                    MeshAppearance.Combiner.DIFFUSE,
                                    List.of(
                                            new MeshAppearance.TextureStage(
                                                    index,
                                                    MeshAppearance.Coordinate.UV0,
                                                    (texture.flags() & 1) != 0,
                                                    (texture.flags() & 2) != 0)),
                                    unlit
                                            ? MeshAppearance.Lighting.UNLIT
                                            : MeshAppearance.Lighting.LIT,
                                    blend == ADD_BLEND
                                            ? MeshAppearance.Blend.ADDITIVE
                                            : MeshAppearance.Blend.BLENDED)));
            bound.add(batch.sectionIndex());
        }
        return decided;
    }

    /**
     * The file a texture slot binds for a display: the exact TXID for type 0, the display's texture
     * variation {@code type - 11} for the monster skin types 11 to 13, and variation 0 for the
     * object skin type 2 when {@code objectSkin} ({@link #objectSkinFromVariation}); 0 (no verified
     * binding) for every other type, including the character skin type 1, which needs character
     * composition (#385).
     */
    static long textureId(M2Types.Texture texture, List<Long> variations, boolean objectSkin) {
        if (texture.type() == 0) {
            return texture.fileDataId() == null ? 0 : texture.fileDataId();
        }
        if (texture.type() >= 11
                && texture.type() <= 13
                && texture.type() - 11 < variations.size()) {
            return variations.get((int) texture.type() - 11);
        }
        if (texture.type() == 2 && objectSkin && !variations.isEmpty()) {
            return variations.getFirst();
        }
        return 0;
    }

    /**
     * Whether a creature display's model (its {@code textures}) takes its object skin (texture type
     * 2) from the display's first texture variation: true when every texture of the model is a file
     * (type 0) or the object skin. Then the variation has no other slot to fill: there is no
     * monster skin slot (types 11 to 13), and no character slot (body skin, hair, cape and the
     * like), whose type 2 is a cape filled from equipment rather than from the display. This is
     * inferred from the tables, not from client code: on 70862 the three such displays that name a
     * variation all belong to models whose only replaceable slot is type 2, and two of them name
     * the texture filed next to the model.
     */
    static boolean objectSkinFromVariation(List<M2Types.Texture> textures) {
        boolean objectSkin = false;
        for (var texture : textures) {
            if (texture.type() == 2) {
                objectSkin = true;
            } else if (texture.type() != 0) {
                return false;
            }
        }
        return objectSkin;
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

    private static boolean usableNormals(
            M2Types.RestSkin geometry, int ordinal, float[] normals, Cancellation token) {
        var section = geometry.sections().get(ordinal);
        int first = Math.toIntExact(section.triangleStart());
        for (int at = first; at < first + section.triangleCount(); at++) {
            if ((at & 4095) == 0) {
                token.check();
            }
            int vertex = geometry.triangleLookup().get(at) * 3;
            double x = normals[vertex], y = normals[vertex + 1], z = normals[vertex + 2];
            if (!Double.isFinite(x * x + y * y + z * z) || x * x + y * y + z * z <= 1e-12) {
                return false;
            }
        }
        return true;
    }

    private final class TexturePool {
        final List<MeshAppearance.Texture> textures = new ArrayList<>();
        final Map<Long, Integer> decoded = new HashMap<>();
        final Map<Long, Integer> scaledCopies = new HashMap<>();
        final DisplayModelTypes.Reference reference;
        final boolean objectSkin;
        final Map<Long, String> receipts;
        final Cancellation token;
        final List<String> issues;
        long pixels;
        boolean complete = true;

        TexturePool(
                DisplayModelTypes.Reference reference,
                boolean objectSkin,
                Map<Long, String> receipts,
                Cancellation token,
                List<String> issues) {
            this.reference = reference;
            this.objectSkin = objectSkin;
            this.receipts = receipts;
            this.token = token;
            this.issues = issues;
        }

        Integer texture(M2Types.Texture texture) {
            long fid = textureId(texture, reference.textureVariations(), objectSkin);
            if (fid <= 0 || fid > 0xffffffffL) {
                return unavailable("Some surface textures have no verified file binding.");
            }
            Integer existing = decoded.get(fid);
            if (existing != null) {
                return existing;
            }
            if (textures.size() >= 16) {
                return unavailable("Additional textures exceed the preview budget.");
            }
            var stat = files.stat(FileTypes.FileRef.id(fid), token).join();
            if (stat.file() == null || stat.state() != FileTypes.FileLookupState.FOUND) {
                return unavailable("Texture #" + fid + " is unavailable.");
            }
            var image = decode(fid, token);
            if (image == null) {
                return unavailable("Texture #" + fid + " is unavailable or unsupported.");
            }
            long count = (long) image.width() * image.height();
            if (count > 8_388_608L - pixels) {
                return unavailable("Additional textures exceed the preview pixel budget.");
            }
            byte[] rgba = image.rgba();
            int[] argb = new int[image.width() * image.height()];
            for (int index = 0; index < argb.length; index++) {
                if ((index & 16383) == 0) {
                    token.check();
                }
                argb[index] =
                        (rgba[index * 4 + 3] & 255) << 24
                                | (rgba[index * 4] & 255) << 16
                                | (rgba[index * 4 + 1] & 255) << 8
                                | rgba[index * 4 + 2] & 255;
            }
            pixels += count;
            int index = textures.size();
            textures.add(MeshAppearance.Texture.adopt(image.width(), image.height(), argb));
            decoded.put(fid, index);
            receipts.put(fid, stat.file().contentKey());
            return index;
        }

        /**
         * Texture {@code index} with its alpha scaled by {@code alpha} / 255 (a Stand texture
         * weight), as one more texture of the binding; one copy per texture and scale. This matches
         * the weight multiplying the drawn alpha up to 8-bit rounding: a texel whose scaled alpha
         * rounds to 0 drops out of the mips' colour weighting (all four of a block: their plain
         * average), so it is invisible or very nearly so.
         */
        Integer scaled(int index, int alpha) {
            Integer existing = scaledCopies.get((long) index << 8 | alpha);
            if (existing != null) {
                return existing;
            }
            if (textures.size() >= 16) {
                return unavailable("Additional textures exceed the preview budget.");
            }
            var source = textures.get(index);
            long count = (long) source.width() * source.height();
            if (count > 8_388_608L - pixels) {
                return unavailable("Additional textures exceed the preview pixel budget.");
            }
            int[] argb = source.argb();
            for (int at = 0; at < argb.length; at++) {
                if ((at & 16383) == 0) {
                    token.check();
                }
                int a = ((argb[at] >>> 24) * alpha + 127) / 255;
                argb[at] = a << 24 | argb[at] & 0xffffff;
            }
            pixels += count;
            int copy = textures.size();
            textures.add(MeshAppearance.Texture.adopt(source.width(), source.height(), argb));
            scaledCopies.put((long) index << 8 | alpha, copy);
            return copy;
        }

        private Integer unavailable(String message) {
            complete = false;
            TableProjection.note(issues, message);
            return null;
        }
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
                "Static bind/rest preview with reviewed material and rigid-card profiles; animation, particles/ribbons and runtime appearance are not evaluated.");
        if (reference.extendedDisplayInfoId() == null || reference.extendedDisplayInfoId() != 0) {
            TableProjection.note(
                    issues,
                    "Extended character appearance is unresolved; native geometry is retained.");
        }
        if (reference.creatureGeosetDataId() == null
                || reference.creatureGeosetDataId() != 0
                || reference.displayGeosets() == null
                || !reference.displayGeosets().isEmpty()
                || mesh.parts().stream().anyMatch(p -> p.id() != 0)) {
            // The World explorer differs: it draws one variant per geoset group (the display's own
            // geoset rows, otherwise x00) and refuses what it cannot decide (WorldTileCreatures).
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
