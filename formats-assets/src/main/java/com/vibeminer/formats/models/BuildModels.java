package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileHoleException;
import com.vibeminer.files.FileReadException;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.BuildModelTypes.ModelRead;
import com.vibeminer.formats.models.BuildModelTypes.ReadState;
import com.vibeminer.formats.models.BuildModelTypes.Reference;
import com.vibeminer.formats.models.BuildModelTypes.ReferenceKind;
import com.vibeminer.formats.models.BuildModelTypes.ReferenceResolution;
import com.vibeminer.formats.models.BuildModelTypes.ReferenceState;
import com.vibeminer.formats.models.BuildModelTypes.SkinRead;
import com.vibeminer.formats.models.M2Types.Chunk;
import com.vibeminer.formats.models.M2Types.GeometrySkin;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Skin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Model/SKIN reads through layer4. Reference lookup is bounded and descriptive; no filename
 * guesses.
 */
public final class BuildModels {
    public static final int MAX_CONCURRENT_STATS = 8;
    private final BuildFiles files;
    private final Limits limits;

    public BuildModels(BuildFiles files) {
        this(files, new Limits());
    }

    public BuildModels(BuildFiles files, Limits limits) {
        this.files = Objects.requireNonNull(files);
        this.limits = limits == null ? new Limits() : limits;
        M2Input.checkLimits(this.limits);
    }

    public CompletableFuture<ModelRead> readModel(FileRef model, Cancellation token) {
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileStat stat = files.stat(model, token).join();
                    BuildFile file = stat.file();
                    if (stat.state() != FileLookupState.FOUND || file == null) {
                        return new ModelRead(
                                stateOf(stat.state()),
                                file == null ? model.fileDataId() : file.fileDataId(),
                                file,
                                null,
                                List.of(),
                                null,
                                "The model is " + stat.state() + ".",
                                null,
                                null,
                                null);
                    }
                    if (file.size() != null && file.size() > limits.maxSourceBytes()) {
                        return new ModelRead(
                                ReadState.REJECTED,
                                file.fileDataId(),
                                file,
                                null,
                                List.of(),
                                "MODEL_LIMIT",
                                "The model is "
                                        + number(file.size())
                                        + " bytes; models read at most "
                                        + number(limits.maxSourceBytes())
                                        + ".",
                                null,
                                null,
                                null);
                    }
                    FileOutcome<Parsed> read =
                            readFile(
                                    file.fileDataId(),
                                    source -> {
                                        Model parsed =
                                                M2ModelReader.read(source, limits, token).join();
                                        return new Parsed(parsed, extraIds(source, parsed, token));
                                    },
                                    token);
                    if (read.state != ReadState.READ) {
                        return new ModelRead(
                                read.state,
                                file.fileDataId(),
                                file,
                                null,
                                List.of(),
                                read.code,
                                read.message,
                                read.time,
                                read.keys,
                                null);
                    }
                    List<Reference> references =
                            resolve(declared(read.value.model, read.value.extra), token);
                    return new ModelRead(
                            ReadState.READ,
                            file.fileDataId(),
                            file,
                            read.value.model,
                            references,
                            null,
                            null,
                            null,
                            null,
                            read.chunks);
                });
    }

    /**
     * Reads one bone file that the read model declares in its BFID chunk. Undeclared IDs are
     * refused before any file access; the file's size is checked before it is opened.
     */
    public CompletableFuture<BuildModelTypes.BoneFileRead> readBoneFile(
            ModelRead read, long fileDataId, Cancellation token) {
        Objects.requireNonNull(read);
        if (read.state() != ReadState.READ || read.model() == null) {
            throw new IllegalArgumentException("Bone files require a model read with READ state");
        }
        if (fileDataId <= 0 || !read.model().boneFileDataIds().contains(fileDataId)) {
            throw new IllegalArgumentException("The model does not declare this bone file");
        }
        int modelBones = read.model().bones().size();
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileStat stat = files.stat(FileRef.id(fileDataId), token).join();
                    BuildFile file = stat.file();
                    if (stat.state() != FileLookupState.FOUND || file == null) {
                        return new BuildModelTypes.BoneFileRead(
                                stateOf(stat.state()),
                                fileDataId,
                                null,
                                null,
                                null,
                                "The bone file is " + stat.state() + ".");
                    }
                    if (file.size() != null && file.size() > M2BoneFile.MAX_BYTES) {
                        return new BuildModelTypes.BoneFileRead(
                                ReadState.REJECTED,
                                fileDataId,
                                file.contentKey(),
                                null,
                                "MODEL_LIMIT",
                                "The bone file is "
                                        + number(file.size())
                                        + " bytes; bone files read at most "
                                        + number(M2BoneFile.MAX_BYTES)
                                        + ".");
                    }
                    if (modelBones == 0) {
                        return new BuildModelTypes.BoneFileRead(
                                ReadState.REJECTED,
                                fileDataId,
                                file.contentKey(),
                                null,
                                "MODEL_UNSUPPORTED",
                                "The model stores no bones for its bone file to name.");
                    }
                    FileOutcome<M2BoneFile.Bones> outcome =
                            readFile(
                                    fileDataId,
                                    source -> M2BoneFile.read(source, modelBones, token).join(),
                                    token);
                    // A short or resized source says nothing about the file; it can be retried.
                    boolean changed =
                            outcome.state == ReadState.REJECTED
                                    && "MODEL_SOURCE_CHANGED".equals(outcome.code);
                    return new BuildModelTypes.BoneFileRead(
                            changed ? ReadState.UNAVAILABLE : outcome.state,
                            fileDataId,
                            file.contentKey(),
                            outcome.value,
                            outcome.code,
                            outcome.message);
                });
    }

    /** Re-reads the model source for {@link M2IdleAnimation#assessStand} facts. */
    public CompletableFuture<BuildModelTypes.StandRead> readStandFacts(
            ModelRead read, Cancellation token) {
        Objects.requireNonNull(read);
        if (read.state() != ReadState.READ || read.model() == null || read.fileDataId() == null) {
            throw new IllegalArgumentException("Stand facts require a model read with READ state");
        }
        Model model = read.model();
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileOutcome<M2IdleAnimation.StandFacts> outcome =
                            readFile(
                                    read.fileDataId(),
                                    source ->
                                            M2IdleAnimation.assessStand(source, model, token)
                                                    .join(),
                                    token);
                    return new BuildModelTypes.StandRead(
                            outcome.state, outcome.value, outcome.code, outcome.message);
                });
    }

    /**
     * Re-reads the model source for its evaluable Stand through {@link M2IdleAnimation#read}, which
     * refuses external skeletons and bone files.
     */
    public CompletableFuture<BuildModelTypes.IdleRead> readIdle(
            ModelRead read, Cancellation token) {
        return readIdleCore(read, M2IdleAnimation::read, token);
    }

    /**
     * Re-reads the model source for the base rig's Stand through {@link
     * M2IdleAnimation#readBaseRig}: bone files (BFID) are not applied; external skeletons are
     * refused.
     */
    public CompletableFuture<BuildModelTypes.IdleRead> readIdleBaseRig(
            ModelRead read, Cancellation token) {
        return readIdleCore(read, M2IdleAnimation::readBaseRig, token);
    }

    private interface IdleReader {
        CompletableFuture<M2IdleAnimation.Idle> read(
                AssetSource source, Model model, Cancellation token);
    }

    private CompletableFuture<BuildModelTypes.IdleRead> readIdleCore(
            ModelRead read, IdleReader reader, Cancellation token) {
        Objects.requireNonNull(read);
        if (read.state() != ReadState.READ || read.model() == null || read.fileDataId() == null) {
            throw new IllegalArgumentException("Idle reads require a model read with READ state");
        }
        Model model = read.model();
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileOutcome<M2IdleAnimation.Idle> outcome =
                            readFile(
                                    read.fileDataId(),
                                    source -> reader.read(source, model, token).join(),
                                    token);
                    return new BuildModelTypes.IdleRead(
                            outcome.state, outcome.value, outcome.code, outcome.message);
                });
    }

    public CompletableFuture<SkinRead<Skin>> readSkin(
            ModelRead model, int profile, Cancellation token) {
        return readSkinCore(
                model,
                profile,
                (source, m) -> M2SkinReader.read(source, m, limits, token).join(),
                token);
    }

    public CompletableFuture<SkinRead<GeometrySkin>> readGeometrySkin(
            ModelRead model, int profile, Cancellation token) {
        return readSkinCore(
                model,
                profile,
                (source, m) -> M2GeometrySkinReader.read(source, m, limits, token).join(),
                token);
    }

    /**
     * Static native positions only; this does not admit rig, pose, visibility or material
     * semantics.
     */
    public CompletableFuture<SkinRead<M2Types.RestSkin>> readRestSkin(
            ModelRead model, int profile, Cancellation token) {
        return readSkinCore(
                model,
                profile,
                (source, parsed) -> M2RestSkinReader.read(source, parsed, limits, token).join(),
                token);
    }

    /** Native rest-surface draw declarations only, including external-rig models. */
    public CompletableFuture<SkinRead<M2Types.RestDrawSkin>> readRestDrawSkin(
            ModelRead model, int profile, Cancellation token) {
        return readSkinCore(
                model,
                profile,
                (source, m) -> M2RestDrawSkinReader.read(source, m, limits, token).join(),
                token);
    }

    private <T> CompletableFuture<SkinRead<T>> readSkinCore(
            ModelRead read,
            int profile,
            BiFunction<AssetSource, Model, T> parse,
            Cancellation token) {
        Objects.requireNonNull(read);
        if (read.state() != ReadState.READ || read.model() == null) {
            throw new IllegalArgumentException("Skins require a model read with READ state");
        }
        return AssetTasks.run(
                () -> {
                    token.check();
                    Model model = read.model();
                    Reference reference =
                            read.references().stream()
                                    .filter(
                                            r ->
                                                    r.kind() == ReferenceKind.SKIN
                                                            && r.ordinal() == profile)
                                    .findFirst()
                                    .orElse(null);
                    if (reference == null
                            || reference.fileDataId() == null
                            || reference.fileDataId() == 0) {
                        return new SkinRead<>(
                                ReadState.NOT_DECLARED,
                                profile,
                                null,
                                null,
                                null,
                                null,
                                !model.isChunked()
                                        ? "Legacy model declares no skin files (no SFID)."
                                        : profile < 0 || profile >= model.viewCount()
                                                ? "Model has "
                                                        + model.viewCount()
                                                        + " main skin profiles; "
                                                        + profile
                                                        + " is not one."
                                                : "No file declared for skin profile "
                                                        + profile
                                                        + ".",
                                null,
                                null,
                                null);
                    }
                    FileOutcome<T> result =
                            readFile(
                                    reference.fileDataId(),
                                    source -> parse.apply(source, model),
                                    token);
                    return new SkinRead<>(
                            result.state,
                            profile,
                            reference.fileDataId(),
                            reference.file(),
                            result.state == ReadState.READ ? result.value : null,
                            result.code,
                            result.message,
                            result.time,
                            result.keys,
                            result.state == ReadState.READ ? result.chunks : null);
                });
    }

    private record FileOutcome<T>(
            ReadState state,
            T value,
            String code,
            String message,
            Instant time,
            List<String> keys,
            List<String> chunks) {}

    private <T> FileOutcome<T> readFile(
            long id, Function<AssetSource, T> parse, Cancellation token) {
        FileOpen opened = files.openRead(FileRef.id(id), token).join();
        if (opened.stream() == null) {
            return new FileOutcome<>(
                    stateOf(opened.state()),
                    null,
                    opened.code(),
                    opened.message(),
                    opened.observedAt(),
                    null,
                    null);
        }
        try (FileReadStream stream = opened.stream()) {
            if (!stream.holes().isEmpty()) {
                List<String> keys =
                        stream.holes().stream().map(FileHole::keyName).distinct().sorted().toList();
                long holeBytes = 0;
                for (FileHole hole : stream.holes()) {
                    holeBytes = Math.addExact(holeBytes, hole.length());
                }
                boolean all = holeBytes >= stream.length();
                return new FileOutcome<>(
                        all ? ReadState.PROTECTED : ReadState.PARTIAL,
                        null,
                        all ? "MODEL_SOURCE_PROTECTED" : "MODEL_SOURCE_PARTIAL",
                        (all ? "Every byte" : "Part")
                                + " of file needs TACT key "
                                + String.join(", ", keys)
                                + ".",
                        null,
                        keys,
                        null);
            }
            try {
                return new FileOutcome<>(
                        ReadState.READ,
                        parse.apply(AssetSources.file(stream)),
                        null,
                        null,
                        null,
                        null,
                        stream.chunkKeys());
            } catch (RuntimeException e) {
                Throwable cause = AssetTasks.unwrap(e);
                if (cause instanceof ModelFormatException refused) {
                    return new FileOutcome<>(
                            ReadState.REJECTED,
                            null,
                            refused.code(),
                            refused.getMessage(),
                            null,
                            null,
                            null);
                }
                if (cause instanceof FileHoleException hole) {
                    return new FileOutcome<>(
                            ReadState.PROTECTED,
                            null,
                            "MODEL_SOURCE_PROTECTED",
                            hole.getMessage(),
                            null,
                            List.of(hole.hole().keyName()),
                            null);
                }
                if (cause instanceof FileReadException failed) {
                    return new FileOutcome<>(
                            stateOf(failed.state()),
                            null,
                            failed.code(),
                            failed.getMessage(),
                            failed.observedAt(),
                            null,
                            null);
                }
                throw e;
            }
        }
    }

    private record Extra(Long physics, List<Long> recursive, List<Long> geometry) {}

    private record Parsed(Model model, Extra extra) {}

    private Extra extraIds(AssetSource source, Model model, Cancellation token) {
        Long physics = null;
        Chunk pf = find(model, "PFID");
        if (pf != null) {
            if (pf.byteLength() != 4) {
                throw M2Input.error("INVALID", "PFID must contain exactly one physics file ID.");
            }
            physics = ids(source, pf, token).getFirst();
        }
        return new Extra(
                physics,
                extraList(source, model, "RPID", token),
                extraList(source, model, "GPID", token));
    }

    private List<Long> extraList(AssetSource source, Model model, String id, Cancellation token) {
        Chunk chunk = find(model, id);
        if (chunk == null) {
            return List.of();
        }
        if (chunk.byteLength() % 4 != 0) {
            throw M2Input.error("INVALID", "The " + id + " dependency chunk has a partial ID.");
        }
        if (chunk.byteLength() / 4 > limits.maxMetadataEntries()) {
            throw M2Input.error(
                    "LIMIT", "Model " + id + " entries exceed geometry metadata limit.");
        }
        return ids(source, chunk, token);
    }

    private static List<Long> ids(AssetSource source, Chunk chunk, Cancellation token) {
        List<Long> ids = new ArrayList<>();
        for (long at = 0; at < chunk.byteLength(); ) {
            token.check();
            int count = (int) Math.min(65536, chunk.byteLength() - at);
            byte[] bytes = source.readAt(chunk.offset() + at, count, token).join();
            if (bytes.length != count) {
                throw M2Input.error("INVALID", "The " + chunk.id() + " chunk ended unexpectedly.");
            }
            for (int i = 0; i < bytes.length; i += 4) {
                ids.add(M2Input.u32(bytes, i));
            }
            at += count;
        }
        return ids;
    }

    private record Declaration(Reference reference, FileRef lookup) {}

    private static void byId(
            List<Declaration> out,
            ReferenceKind kind,
            int ordinal,
            long id,
            ReferenceState zero,
            Long type,
            Integer animation,
            Integer variation) {
        Reference reference =
                new Reference(
                        kind,
                        ordinal,
                        id,
                        null,
                        id == 0 ? ReferenceResolution.NONE : ReferenceResolution.FILE_DATA_ID,
                        id == 0 ? zero : ReferenceState.UNKNOWN_FILE,
                        null,
                        null,
                        type,
                        animation,
                        variation);
        out.add(new Declaration(reference, id == 0 ? null : FileRef.id(id)));
    }

    private static List<Declaration> declared(Model model, Extra extra) {
        List<Declaration> out = new ArrayList<>();
        if (model.isChunked()) {
            for (int i = 0; i < model.skinFileDataIds().size(); i++) {
                byId(
                        out,
                        ReferenceKind.SKIN,
                        i,
                        model.skinFileDataIds().get(i),
                        ReferenceState.NO_FILE_DECLARED,
                        null,
                        null,
                        null);
            }
            for (int i = 0; i < model.lodSkinFileDataIds().size(); i++) {
                byId(
                        out,
                        ReferenceKind.LOD_SKIN,
                        i,
                        model.lodSkinFileDataIds().get(i),
                        ReferenceState.NO_FILE_DECLARED,
                        null,
                        null,
                        null);
            }
        } else {
            for (int i = 0; i < model.viewCount(); i++) {
                out.add(
                        new Declaration(
                                new Reference(
                                        ReferenceKind.SKIN,
                                        i,
                                        null,
                                        null,
                                        ReferenceResolution.NONE,
                                        ReferenceState.NOT_DECLARED,
                                        null,
                                        "Legacy model has no SFID; skin files are not declared.",
                                        null,
                                        null,
                                        null),
                                null));
            }
        }
        for (var texture : model.textures()) {
            ReferenceState zero =
                    texture.type() == 0
                            ? ReferenceState.NO_FILE_DECLARED
                            : ReferenceState.RESOLVED_ELSEWHERE;
            if (texture.fileDataId() != null) {
                byId(
                        out,
                        ReferenceKind.TEXTURE,
                        texture.ordinal(),
                        texture.fileDataId(),
                        zero,
                        texture.type(),
                        null,
                        null);
                continue;
            }
            boolean byName = texture.type() == 0 && !texture.filename().isBlank();
            out.add(
                    new Declaration(
                            new Reference(
                                    ReferenceKind.TEXTURE,
                                    texture.ordinal(),
                                    null,
                                    byName ? texture.filename() : null,
                                    ReferenceResolution.NONE,
                                    byName ? ReferenceState.UNRESOLVED_BY_NAME : zero,
                                    null,
                                    null,
                                    texture.type(),
                                    null,
                                    null),
                            byName ? FileRef.forPath(texture.filename()) : null));
        }
        if (model.skeletonFileDataId() != 0 || find(model, "SKID") != null) {
            byId(
                    out,
                    ReferenceKind.SKELETON,
                    0,
                    model.skeletonFileDataId(),
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    null,
                    null);
        }
        for (int i = 0; i < model.boneFileDataIds().size(); i++) {
            byId(
                    out,
                    ReferenceKind.BONE_FILE,
                    i,
                    model.boneFileDataIds().get(i),
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    null,
                    null);
        }
        for (int i = 0; i < model.animationFiles().size(); i++) {
            var a = model.animationFiles().get(i);
            byId(
                    out,
                    ReferenceKind.ANIMATION_FILE,
                    i,
                    a.fileDataId(),
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    a.animationId(),
                    a.variation());
        }
        if (extra.physics != null) {
            byId(
                    out,
                    ReferenceKind.PHYSICS_FILE,
                    0,
                    extra.physics,
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    null,
                    null);
        }
        for (int i = 0; i < extra.recursive.size(); i++) {
            byId(
                    out,
                    ReferenceKind.RECURSIVE_PARTICLE_MODEL,
                    i,
                    extra.recursive.get(i),
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    null,
                    null);
        }
        for (int i = 0; i < extra.geometry.size(); i++) {
            byId(
                    out,
                    ReferenceKind.GEOMETRY_PARTICLE_MODEL,
                    i,
                    extra.geometry.get(i),
                    ReferenceState.NO_EXTERNAL_FILE,
                    null,
                    null,
                    null);
        }
        return out;
    }

    private List<Reference> resolve(List<Declaration> declared, Cancellation token) {
        Reference[] resolved =
                declared.stream().map(Declaration::reference).toArray(Reference[]::new);
        AtomicInteger next = new AtomicInteger();
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CONCURRENT_STATS, declared.size()); i++) {
            workers.add(
                    AssetTasks.run(
                            () -> {
                                int n;
                                while ((n = next.getAndIncrement()) < declared.size()) {
                                    token.check();
                                    Declaration d = declared.get(n);
                                    if (d.lookup != null) {
                                        resolved[n] = stat(d.reference, d.lookup, token);
                                    }
                                }
                                return null;
                            }));
        }
        CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).join();
        return List.copyOf(Arrays.asList(resolved));
    }

    private Reference stat(Reference ref, FileRef lookup, Cancellation token) {
        FileStat stat;
        try {
            stat = files.stat(lookup, token).join();
        } catch (RuntimeException e) {
            Throwable cause = AssetTasks.unwrap(e);
            if (cause instanceof CancellationException
                    || cause instanceof IllegalStateException
                            && cause.getMessage() != null
                            && cause.getMessage()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains("closed")) {
                throw e;
            }
            return new Reference(
                    ref.kind(),
                    ref.ordinal(),
                    ref.fileDataId(),
                    ref.name(),
                    ref.resolution(),
                    ReferenceState.CHECK_FAILED,
                    null,
                    cause.getMessage(),
                    ref.textureType(),
                    ref.animationId(),
                    ref.variation());
        }
        boolean byName = lookup.path() != null;
        ReferenceState state =
                switch (stat.state()) {
                    case FOUND -> ReferenceState.FOUND;
                    case NOT_IN_ENCODING -> ReferenceState.NOT_IN_ENCODING;
                    case NOT_IN_LOCALE -> ReferenceState.NOT_IN_LOCALE;
                    default ->
                            byName
                                    ? ReferenceState.UNRESOLVED_BY_NAME
                                    : ReferenceState.UNKNOWN_FILE;
                };
        ReferenceResolution resolution =
                !byName
                        ? ReferenceResolution.FILE_DATA_ID
                        : stat.file() == null
                                ? ReferenceResolution.NONE
                                : switch (stat.file().pathSource()) {
                                    case ROOT_HASH -> ReferenceResolution.ROOT_NAME;
                                    case LISTFILE -> ReferenceResolution.LISTFILE_NAME;
                                    default -> ReferenceResolution.NONE;
                                };
        return new Reference(
                ref.kind(),
                ref.ordinal(),
                ref.fileDataId() != null
                        ? ref.fileDataId()
                        : stat.file() == null ? null : stat.file().fileDataId(),
                ref.name(),
                resolution,
                state,
                stat.file(),
                null,
                ref.textureType(),
                ref.animationId(),
                ref.variation());
    }

    private static Chunk find(Model model, String id) {
        return model.chunks().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    private static String number(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    private static ReadState stateOf(FileLookupState state) {
        return switch (state) {
            case NOT_IN_LOCALE -> ReadState.NOT_IN_LOCALE;
            case NOT_IN_ENCODING -> ReadState.NOT_IN_ENCODING;
            default -> ReadState.UNKNOWN_FILE;
        };
    }

    private static ReadState stateOf(FileReadState state) {
        return state == FileReadState.COMPLETE
                ? ReadState.CORRUPT
                : ReadState.valueOf(state.name());
    }
}
