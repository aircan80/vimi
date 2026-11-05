package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.models.DisplayIdleAnimations;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Offers, or explains why it cannot offer, the selected race model's Stand idle. Runs on the race
 * component worker.
 *
 * <p>A model that names no external skeleton (SKID) and no bone files (BFID), and stores its Stand
 * keys itself, reads them through {@link com.vibeminer.formats.models.M2IdleAnimation#read} and
 * binds them to the static rest surface through the same bone-flag admission as Mounts ({@link
 * DisplayIdleAnimations}). Otherwise every reason is collected rather than only the first: the
 * SKID, the BFID bone files (each read and validated through {@link
 * com.vibeminer.formats.models.M2BoneFile}), and the model-resident Stand with every check of the
 * mount idle reader except its external-file gate. Bone files hold per-bone matrices, but which
 * file the client applies for an appearance and how those matrices combine with Stand keys are not
 * reviewed, so a model declaring any is refused rather than approximated. Results whose sources
 * were all read are cached per model content key and revalidated against each bone file's and the
 * SKIN's content key; a source that could not be read, or that changed during the assessment, is
 * reassessed; that reassessment reuses the model read and its model-resident Stand facts, which
 * depend only on the model's content. At most {@value #MAX_CACHED_ANIMATIONS} prepared animations
 * are kept.
 */
final class RaceIdleAnimations {
    static final int MAX_BONE_FILES = 64;
    private static final int MAX_CACHED = 32;
    static final int MAX_CACHED_ANIMATIONS = 4;
    private final RaceMedia media;
    private final BuildFiles files;

    /** A complete result and the content keys of the bone files it was built from. */
    private record Cached(RaceMediaTypes.Idle result, Map<Long, String> sourceKeys) {}

    private final Map<String, Cached> cached =
            new LinkedHashMap<>(16, 0.75f, true) {
                @java.io.Serial private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            };

    static final int MAX_CACHED_SOURCES = 4;

    /**
     * The model read and its model-resident Stand facts, per model content key. Both are pure
     * functions of the model bytes, so an assessment that must be repeated because a bone file or
     * SKIN could not be read reuses them and re-reads only those other sources.
     */
    private record ModelSources(BuildModelTypes.ModelRead read, BuildModelTypes.StandRead stand) {}

    private final Map<String, ModelSources> sources = RaceMedia.lru(MAX_CACHED_SOURCES);

    /** Drops every cached result and model source; called once the workers have terminated. */
    void forget() {
        cached.clear();
        sources.clear();
    }

    /** Cached results and model sources, for tests. */
    int cachedCount() {
        return cached.size() + sources.size();
    }

    RaceIdleAnimations(RaceMedia media, BuildFiles files) {
        this.media = media;
        this.files = files;
    }

    RaceMediaTypes.Idle assess(long raceId, long chrModelId, Cancellation token) {
        var variant =
                media.variants(raceId, token).variants().stream()
                        .filter(value -> value.chrModelId() == chrModelId)
                        .findFirst()
                        .orElse(null);
        if (variant == null) {
            return RaceMediaTypes.Idle.unavailable(null, "The selected race model is unavailable.");
        }
        if (files == null) {
            return RaceMediaTypes.Idle.unavailable(
                    variant, "Build files are unavailable for this workspace.");
        }
        var stat = files.stat(FileTypes.FileRef.id(variant.modelFileDataId()), token).join();
        if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
            return RaceMediaTypes.Idle.unavailable(variant, "Model file is " + stat.state() + ".");
        }
        String key = variant.modelFileDataId() + "\n" + stat.file().contentKey();
        var hit = cached.get(key);
        if (hit != null && unchanged(hit.sourceKeys(), token)) {
            var known = hit.result();
            return new RaceMediaTypes.Idle(
                    known.state(),
                    variant,
                    known.animation(),
                    known.reasons(),
                    known.facts(),
                    known.message());
        }
        cached.remove(key);
        var models = new BuildModels(files);
        var reused = sources.get(key);
        var read =
                reused != null
                        ? reused.read()
                        : models.readModel(FileTypes.FileRef.id(variant.modelFileDataId()), token)
                                .join();
        if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
            return RaceMediaTypes.Idle.unavailable(
                    variant,
                    read.message() == null ? "Model is " + read.state() + "." : read.message());
        }
        if (read.file() == null || !key.endsWith("\n" + read.file().contentKey())) {
            return RaceMediaTypes.Idle.unavailable(
                    variant, "The model file changed while it was assessed.");
        }
        var model = read.model();
        var sourceKeys = new TreeMap<Long, String>();
        boolean local =
                model.skeletonFileDataId() == 0
                        && model.boneFileDataIds().stream().allMatch(id -> id == 0);
        M2IdleAnimation.Idle[] tracks = {null};
        var assessed =
                explain(
                        model.skeletonFileDataId(),
                        !model.bones().isEmpty(),
                        model.boneFileDataIds(),
                        id -> {
                            var bone = models.readBoneFile(read, id, token).join();
                            if (bone.contentKey() != null) {
                                sourceKeys.put(id, bone.contentKey());
                            }
                            return bone;
                        },
                        local
                                ? () -> {
                                    var idle = models.readIdle(read, token).join();
                                    tracks[0] = idle.idle();
                                    return standOf(idle);
                                }
                                : () -> {
                                    if (reused != null && reused.stand() != null) {
                                        return reused.stand();
                                    }
                                    var stand = models.readStandFacts(read, token).join();
                                    var state = stand.state();
                                    if (state == BuildModelTypes.ReadState.READ
                                            || state == BuildModelTypes.ReadState.REJECTED) {
                                        sources.put(key, new ModelSources(read, stand));
                                    }
                                    return stand;
                                },
                        token);
        if (!sources.containsKey(key)) {
            sources.put(key, new ModelSources(read, null));
        }
        var reasons = new ArrayList<>(assessed.reasons());
        var facts = new ArrayList<>(assessed.facts());
        boolean complete = assessed.complete();
        PreviewAnimation animation = null;
        String unread = null;
        if (reasons.isEmpty()) {
            if (tracks[0] == null) {
                throw new IllegalStateException("An admitted Stand carries no tracks");
            }
            var skin = models.readRestSkin(read, 0, token).join();
            if (skin.fileDataId() != null && skin.file() != null) {
                sourceKeys.put(skin.fileDataId(), skin.file().contentKey());
            }
            if (skin.state() == BuildModelTypes.ReadState.READ && skin.skin() != null) {
                var surface = RaceRestSurface.of(model, skin.skin(), token);
                try {
                    animation =
                            DisplayIdleAnimations.admit(
                                    model,
                                    skin.skin().vertexLookup(),
                                    tracks[0],
                                    surface.mesh(),
                                    surface.normals(),
                                    token);
                    facts.add(
                            "Stand is bound to "
                                    + animation.vertexCount()
                                    + " rest vertices of SKIN FileDataID "
                                    + skin.fileDataId()
                                    + "; "
                                    + animation.boneCount()
                                    + " bones are evaluated in software.");
                } catch (DisplayIdleAnimations.Unsupported refused) {
                    reasons.add(refused.getMessage());
                } catch (IllegalArgumentException refused) {
                    reasons.add(
                            "Stand could not be bound to the rest surface: "
                                    + refused.getMessage());
                }
            } else if (skin.state() == BuildModelTypes.ReadState.NOT_DECLARED) {
                // A declaration fact of the model, not a missing source: refuse and cache.
                reasons.add(
                        "The model declares no LOD0 SKIN, so there is no rest surface to animate"
                                + (skin.message() == null ? "." : ": " + skin.message()));
            } else if (skin.state() == BuildModelTypes.ReadState.REJECTED) {
                reasons.add(
                        "The model's rest surface"
                                + skinName(skin.fileDataId())
                                + " could not be admitted: "
                                + (skin.message() == null ? skin.state() : skin.message()));
            } else {
                unread =
                        "The model's rest surface"
                                + skinName(skin.fileDataId())
                                + " could not be read: "
                                + (skin.message() == null ? skin.state() : skin.message());
            }
        }
        // The Stand, bone and SKIN reads reopen files; a model replaced meanwhile is not reported.
        var after = files.stat(FileTypes.FileRef.id(variant.modelFileDataId()), token).join();
        if (after.file() == null || !key.endsWith("\n" + after.file().contentKey())) {
            return RaceMediaTypes.Idle.unavailable(
                    variant, "The model file changed while it was assessed.");
        }
        if (unread != null) {
            return RaceMediaTypes.Idle.unavailable(variant, unread);
        }
        var result =
                animation != null
                        ? new RaceMediaTypes.Idle(
                                RaceTypes.State.READY,
                                variant,
                                animation,
                                List.of(),
                                facts,
                                "Stand idle · "
                                        + animation.durationMillis()
                                        + " ms loop · "
                                        + animation.boneCount()
                                        + " bones evaluated in software.")
                        : new RaceMediaTypes.Idle(
                                RaceTypes.State.UNSUPPORTED,
                                variant,
                                null,
                                reasons,
                                facts,
                                String.join(" ", reasons));
        if (complete && unchanged(sourceKeys, token)) {
            cache(key, new Cached(result, Map.copyOf(sourceKeys)));
        }
        return result;
    }

    private static String skinName(Long fileDataId) {
        return fileDataId == null ? "" : " (SKIN FileDataID " + fileDataId + ")";
    }

    /** Caches a complete result, keeping only the most recently used prepared animations. */
    private void cache(String key, Cached entry) {
        cached.put(key, entry);
        int animations = 0;
        for (var value : cached.values()) {
            if (value.result().animation() != null) {
                animations++;
            }
        }
        var eldest = cached.entrySet().iterator();
        while (animations > MAX_CACHED_ANIMATIONS && eldest.hasNext()) {
            if (eldest.next().getValue().result().animation() != null) {
                eldest.remove();
                animations--;
            }
        }
    }

    /** Stand facts of an evaluable read, with refusals reported as {@code assessStand} does. */
    static BuildModelTypes.StandRead standOf(BuildModelTypes.IdleRead read) {
        if (read.state() == BuildModelTypes.ReadState.READ) {
            var idle = read.idle();
            int animated =
                    (int)
                            idle.bones().stream()
                                    .filter(M2IdleAnimation.BoneTracks::animated)
                                    .count();
            return new BuildModelTypes.StandRead(
                    BuildModelTypes.ReadState.READ,
                    new M2IdleAnimation.StandFacts(
                            idle.sequenceIndex(),
                            idle.durationMillis(),
                            idle.keyCount(),
                            animated,
                            null),
                    null,
                    null);
        }
        if (read.state() == BuildModelTypes.ReadState.REJECTED
                && ("MODEL_UNSUPPORTED".equals(read.code()) || "MODEL_LIMIT".equals(read.code()))) {
            return new BuildModelTypes.StandRead(
                    BuildModelTypes.ReadState.READ,
                    new M2IdleAnimation.StandFacts(-1, 0, 0, 0, read.message()),
                    null,
                    null);
        }
        return new BuildModelTypes.StandRead(read.state(), null, read.code(), read.message());
    }

    /** Whether every recorded bone file and SKIN still has the content it was assessed with. */
    private boolean unchanged(Map<Long, String> sourceKeys, Cancellation token) {
        for (var entry : sourceKeys.entrySet()) {
            var stat = files.stat(FileTypes.FileRef.id(entry.getKey()), token).join();
            if (stat.file() == null || !entry.getValue().equals(stat.file().contentKey())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every blocking reason and supporting fact; {@code complete} when all sources were read. No
     * reasons means the model-resident Stand may be bound to the rest surface.
     */
    record Assessment(List<String> reasons, List<String> facts, boolean complete) {}

    /** Pure assessment over the model's declarations and the supplied source reads. */
    static Assessment explain(
            long skeletonFileDataId,
            boolean modelBones,
            List<Long> declaredBoneFiles,
            Function<Long, BuildModelTypes.BoneFileRead> boneFile,
            Supplier<BuildModelTypes.StandRead> standRead,
            Cancellation token) {
        var reasons = new ArrayList<String>();
        var facts = new ArrayList<String>();
        boolean complete = true;
        if (skeletonFileDataId != 0) {
            reasons.add(
                    "The model names external skeleton FileDataID "
                            + skeletonFileDataId
                            + " (SKID), which is not read.");
        }
        var boneFiles = declaredBoneFiles.stream().filter(id -> id != 0).toList();
        if (boneFiles.size() > MAX_BONE_FILES) {
            reasons.add(
                    "The model declares "
                            + boneFiles.size()
                            + " external bone files (BFID); at most "
                            + MAX_BONE_FILES
                            + " are assessed.");
        } else if (!boneFiles.isEmpty()) {
            complete &= boneFiles(boneFile, boneFiles, reasons, facts, token);
        }
        if (skeletonFileDataId != 0 && !modelBones) {
            facts.add("Bones are stored only in the external skeleton; Stand is not read.");
        } else {
            token.check();
            complete &= stand(standRead.get(), reasons, facts);
        }
        return new Assessment(List.copyOf(reasons), List.copyOf(facts), complete);
    }

    /** Adds model-resident Stand facts; returns false if the source could not be read. */
    private static boolean stand(
            BuildModelTypes.StandRead stand, List<String> reasons, List<String> facts) {
        if (stand.state() != BuildModelTypes.ReadState.READ) {
            boolean refused = stand.state() == BuildModelTypes.ReadState.REJECTED;

            reasons.add(
                    "The model's Stand sequence could not be "
                            + (refused ? "admitted: " : "re-read: ")
                            + (stand.message() == null ? stand.state() : stand.message()));
        } else if (stand.stand().refusal() != null) {
            reasons.add("Stand: " + stand.stand().refusal());
        } else {
            var standFacts = stand.stand();
            facts.add(
                    "Stand (sequence "
                            + standFacts.sequenceIndex()
                            + ", "
                            + standFacts.durationMillis()
                            + " ms) is stored in the model: "
                            + standFacts.keyCount()
                            + " step/linear keys on "
                            + standFacts.animatedBones()
                            + " bones.");
        }
        return stand.state() == BuildModelTypes.ReadState.READ
                || stand.state() == BuildModelTypes.ReadState.REJECTED;
    }

    /** Reads every declared bone file; returns false if any source could not be read. */
    private static boolean boneFiles(
            Function<Long, BuildModelTypes.BoneFileRead> read,
            List<Long> ids,
            List<String> reasons,
            List<String> facts,
            Cancellation token) {
        int valid = 0, fewest = Integer.MAX_VALUE, most = 0;
        var covered = new BitSet();
        var unread = new TreeMap<String, Integer>();
        String rejected = null;
        int rejectedCount = 0;
        for (long id : ids) {
            token.check();
            var bone = read.apply(id);
            switch (bone.state()) {
                case READ -> {
                    valid++;
                    int count = bone.bones().count();
                    fewest = Math.min(fewest, count);
                    most = Math.max(most, count);
                    for (int index : bone.bones().bones()) {
                        covered.set(index);
                    }
                }
                case REJECTED -> {
                    rejectedCount++;
                    if (rejected == null) {
                        rejected = "FileDataID " + id + ": " + bone.message();
                    }
                }
                default -> unread.merge(bone.state().name(), 1, Integer::sum);
            }
        }
        var detail = new StringBuilder();
        if (valid > 0) {
            detail.append(valid)
                    .append(" read and validated (")
                    .append(fewest == most ? fewest + "" : fewest + "–" + most)
                    .append(" bone matrices each, ")
                    .append(covered.cardinality())
                    .append(" distinct bones)");
            facts.add(
                    "Bone files: "
                            + valid
                            + " of "
                            + ids.size()
                            + " validated; they name bones "
                            + covered.nextSetBit(0)
                            + "–"
                            + (covered.length() - 1)
                            + ".");
        }
        if (rejectedCount > 0) {
            detail.append(detail.isEmpty() ? "" : "; ")
                    .append(rejectedCount)
                    .append(" refused (")
                    .append(rejected)
                    .append(')');
        }
        for (var entry : unread.entrySet()) {
            detail.append(detail.isEmpty() ? "" : "; ")
                    .append(entry.getValue())
                    .append(" not readable (")
                    .append(entry.getKey())
                    .append(')');
        }
        reasons.add(
                "The model declares "
                        + ids.size()
                        + " external bone files (BFID): "
                        + detail
                        + ". Which file the client applies for an appearance, and how its"
                        + " matrices combine with Stand keys, are not reviewed.");
        return unread.isEmpty();
    }
}
