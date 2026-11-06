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
     * rather than null, which changes the recorded reference of those displays.
     */
    public static final String POLICY = "display-static-materials-env-unlit-bindcards-v5";

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
    private Set<Long> geosetDisplays;

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
        return submit(token, request -> load(displayId, request));
    }

    @Override
    public CompletableFuture<DisplayModelTypes.Idle> idle(long displayId, Cancellation token) {
        checkDisplay(displayId);
        return submit(token, request -> loadIdle(displayId, request));
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

    private DisplayModelTypes.Idle loadIdle(long displayId, Cancellation token) {
        var issues = new ArrayList<String>();
        DisplayModelTypes.Reference reference = null;
        try {
            reference = resolve(displayId, token, issues);
            if (reference == null) {
                return DisplayModelTypes.Idle.unavailable(
                        "Display-to-model relationship is unavailable.");
            }
            var models = new BuildModels(files);
            var read =
                    models.readModel(FileTypes.FileRef.id(reference.modelFileDataId()), token)
                            .join();
            if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
                return idleFailed(reference, "Model read is " + read.state());
            }
            var skin = models.readRestDrawSkin(read, 0, token).join();
            if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
                return idleFailed(reference, "Model surface is " + skin.state());
            }
            var m = read.model();
            var geometry = skin.skin().surface();
            var surface = restSurface(m, geometry, token);
            var opened =
                    files.openRead(FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
            if (opened.stream() == null) {
                return idleFailed(reference, "Model source is unavailable");
            }
            M2IdleAnimation.Idle idle;
            try (var stream = opened.stream()) {
                if (opened.state() != FileTypes.FileReadState.COMPLETE
                        || !stream.holes().isEmpty()) {
                    return idleFailed(reference, "Model source is incomplete");
                }
                try {
                    idle = M2IdleAnimation.read(AssetSources.file(stream), m, token).join();
                } catch (RuntimeException failure) {
                    token.check();
                    var cause = AssetTasks.unwrap(failure);
                    if (cause instanceof ModelFormatException format
                            && format.code().equals("MODEL_UNSUPPORTED")) {
                        return new DisplayModelTypes.Idle(
                                DisplayModelTypes.IdleState.UNSUPPORTED,
                                reference,
                                null,
                                format.getMessage());
                    }
                    return idleFailed(
                            reference,
                            "Idle sequence could not be read"
                                    + message(cause == null ? null : cause.getMessage()));
                }
            }
            try {
                var animation =
                        DisplayIdleAnimations.admit(
                                m,
                                geometry.vertexLookup(),
                                idle,
                                surface.mesh(),
                                surface.normals(),
                                token);
                return new DisplayModelTypes.Idle(
                        DisplayModelTypes.IdleState.AVAILABLE,
                        reference,
                        animation,
                        "Stand idle · "
                                + animation.durationMillis()
                                + " ms loop · "
                                + animation.boneCount()
                                + " bones evaluated in software.");
            } catch (DisplayIdleAnimations.Unsupported unsupported) {
                return new DisplayModelTypes.Idle(
                        DisplayModelTypes.IdleState.UNSUPPORTED,
                        reference,
                        null,
                        unsupported.getMessage());
            }
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (IllegalArgumentException unavailable) {
            token.check();
            return idleFailed(reference, unavailable.getMessage());
        }
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
            var pool = new TexturePool(reference, receipts, token, issues);
            var bindings = new ArrayList<MeshAppearance.Surface>();
            var billboards = new ArrayList<MeshAppearance.RigidBillboard>();
            for (var batch : skin.skin().batches()) {
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
            if (sourceFactsComplete && pool.complete) {
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
    private Boolean displayGeosets(long id, Cancellation token, List<String> issues) {
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
        return geosetDisplays.contains(id);
    }

    private Set<Long> readGeosetDisplays(Cancellation token, List<String> issues) {
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
            return Set.of();
        }
        var data = tables.read(GEOSET_TABLE, GEOSET_ROWS, token, issues);
        if (data.entry().state() != com.vibeminer.formats.tables.TableTypes.State.READY) {
            throw new IllegalArgumentException(GEOSET_TABLE + " is partial");
        }
        data.reference("CreatureDisplayInfoID", "CreatureDisplayInfo");
        var found = new java.util.HashSet<Long>();
        for (var row : data.rows()) {
            found.add(data.number(row, "CreatureDisplayInfoID"));
        }
        return Set.copyOf(found);
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
        final DisplayModelTypes.Reference reference;
        final Map<Long, String> receipts;
        final Cancellation token;
        final List<String> issues;
        long pixels;
        boolean complete = true;

        TexturePool(
                DisplayModelTypes.Reference reference,
                Map<Long, String> receipts,
                Cancellation token,
                List<String> issues) {
            this.reference = reference;
            this.receipts = receipts;
            this.token = token;
            this.issues = issues;
        }

        Integer texture(M2Types.Texture texture) {
            long fid = textureId(texture, reference.textureVariations());
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
            textures.add(new MeshAppearance.Texture(image.width(), image.height(), argb));
            decoded.put(fid, index);
            receipts.put(fid, stat.file().contentKey());
            return index;
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
                || !Boolean.FALSE.equals(reference.displayGeosets())
                || mesh.parts().stream().anyMatch(p -> p.id() != 0)) {
            // The World explorer differs: it draws only the default x00 geosets when no geoset
            // data is recorded and refuses the model otherwise (WorldTileCreatures).
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
