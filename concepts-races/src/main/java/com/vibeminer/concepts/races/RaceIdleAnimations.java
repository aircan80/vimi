package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.models.DisplayIdleAnimations;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Offers, or explains why it cannot offer, the selected race model's Stand idle. Runs on the race
 * component worker.
 *
 * <p>A model that keeps its bones itself (no external skeleton, SKID) reads its Stand keys through
 * {@link com.vibeminer.formats.models.M2IdleAnimation#readBaseRig} and binds them to the static
 * rest surface through the same bone-flag admission as Mounts ({@link DisplayIdleAnimations}). Bone
 * files (BFID) are not applied: each one reshapes the face (and sometimes the hands) for one
 * customization choice ({@code ChrCustomizationElement} to {@code ChrCustomizationBoneSet}), and
 * the static preview shows the base rig too, so the Stand plays on the base rig and a fact names
 * the customization choice whose bone file is left out. A model that keeps its bones only in an
 * external skeleton (SKID) plays the skeleton's own Stand through {@link
 * com.vibeminer.formats.models.M2IdleAnimation#readSkeleton}, with the skeleton's bone files left
 * out the same way. A model with bones of its own beside a skeleton is refused, with its
 * model-resident Stand facts. Results whose sources were all read are cached per model content key
 * and revalidated against the skeleton's and the SKIN's content keys; a source that could not be
 * read, or that changed during the assessment, is reassessed; that reassessment reuses the model
 * read and its model-resident Stand facts, which depend only on the model's content. At most
 * {@value #MAX_CACHED_ANIMATIONS} prepared animations are kept.
 */
final class RaceIdleAnimations {
    private static final int MAX_CACHED = 32;
    static final int MAX_CACHED_ANIMATIONS = 4;
    private final RaceMedia media;
    private final BuildFiles files;

    /** A complete result and the content keys of the skeleton and SKIN it was built from. */
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
     * functions of the model bytes, so an assessment that must be repeated because the SKIN could
     * not be read reuses them and re-reads only those other sources.
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
        // The bone-file fact depends on the ChrModel, so one M2 shared by two ChrModels is two
        // entries.
        String key =
                variant.chrModelId()
                        + "\n"
                        + variant.modelFileDataId()
                        + "\n"
                        + stat.file().contentKey();
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
        long skeleton = model.skeletonFileDataId();
        boolean modelBones = !model.bones().isEmpty();
        var boneFiles = new java.util.LinkedHashSet<Long>();
        model.boneFileDataIds().stream().filter(id -> id != 0).forEach(boneFiles::add);
        M2IdleAnimation.Idle[] tracks = {null};
        // Set when the skeleton could not be read: the result is unavailable, not a refusal.
        String[] skeletonUnread = {null};
        long[] parentSkeleton = {0};
        boolean[] factComplete = {true};
        Supplier<BuildModelTypes.StandRead> standRead;
        if (skeleton == 0) {
            standRead =
                    () -> {
                        var idle = models.readIdleBaseRig(read, token).join();
                        tracks[0] = idle.idle();
                        return standOf(idle);
                    };
        } else if (!modelBones) {
            standRead =
                    () -> {
                        var idle = models.readIdleSkeleton(read, token).join();
                        if (idle.contentKey() != null) {
                            sourceKeys.put(skeleton, idle.contentKey());
                        }
                        idle.boneFileDataIds().stream()
                                .filter(id -> id != 0)
                                .forEach(boneFiles::add);
                        var state = idle.read().state();
                        if (state != BuildModelTypes.ReadState.READ
                                && state != BuildModelTypes.ReadState.REJECTED) {
                            skeletonUnread[0] =
                                    "The external skeleton FileDataID "
                                            + skeleton
                                            + " could not be read: "
                                            + (idle.read().message() == null
                                                    ? state
                                                    : idle.read().message());
                        }
                        if (state == BuildModelTypes.ReadState.READ) {
                            parentSkeleton[0] = idle.parentFileDataId();
                        }
                        tracks[0] = idle.read().idle();
                        return standOf(idle.read());
                    };
        } else {
            standRead =
                    () -> {
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
                    };
        }
        var assessed =
                explain(
                        skeleton,
                        modelBones,
                        () -> {
                            if (boneFiles.isEmpty()) {
                                return null;
                            }
                            var fact =
                                    boneFileFact(
                                            variant.chrModelId(),
                                            variant.modelFileDataId(),
                                            List.copyOf(boneFiles),
                                            token);
                            factComplete[0] = fact.complete();
                            return fact.text();
                        },
                        standRead,
                        token);
        if (!sources.containsKey(key)) {
            sources.put(key, new ModelSources(read, null));
        }
        var reasons = new ArrayList<>(assessed.reasons());
        var facts = new ArrayList<>(assessed.facts());
        if (parentSkeleton[0] != 0) {
            facts.add("Parent skeleton FileDataID " + parentSkeleton[0] + " (SKPD) is not read.");
        }
        // A fact whose tables could not be read now is asked again rather than cached.
        boolean complete = assessed.complete() && factComplete[0];
        PreviewAnimation animation = null;
        String unread = skeletonUnread[0];
        if (unread == null && reasons.isEmpty()) {
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
                                    skin.skin(),
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

    /** Whether the recorded skeleton and SKIN still have the content they were assessed with. */
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

    /**
     * Pure assessment over the model's declarations and the supplied Stand read. A model that keeps
     * its bones only in an external skeleton (SKID) reads the skeleton's Stand; a model with bones
     * of its own beside a skeleton is refused, with its model-resident Stand facts. Bone files are
     * never a reason: {@code boneFileFact}, asked after the Stand read and null when no bone file
     * is declared, only says that none is applied.
     */
    static Assessment explain(
            long skeletonFileDataId,
            boolean modelBones,
            Supplier<String> boneFileFact,
            Supplier<BuildModelTypes.StandRead> standRead,
            Cancellation token) {
        var reasons = new ArrayList<String>();
        var facts = new ArrayList<String>();
        if (skeletonFileDataId != 0 && modelBones) {
            reasons.add(
                    "The model names external skeleton FileDataID "
                            + skeletonFileDataId
                            + " (SKID) and also stores bones of its own; which rig its Stand moves"
                            + " is not reviewed.");
        }
        token.check();
        boolean complete =
                stand(
                        standRead.get(),
                        skeletonFileDataId != 0 && !modelBones
                                ? "in external skeleton FileDataID " + skeletonFileDataId
                                : "in the model",
                        reasons,
                        facts);
        String bones = boneFileFact.get();
        if (bones != null) {
            facts.add(bones);
        }
        return new Assessment(List.copyOf(reasons), List.copyOf(facts), complete);
    }

    /** A bone-file fact; {@code complete} is false when its tables could not be read now. */
    record Fact(String text, boolean complete) {}

    /**
     * Says which customization choice of {@code chrModelId} selects a bone file for model {@code
     * modelFileDataId}; bone files are not applied. A bone file reshapes the face (and sometimes
     * the hands) for one customization choice ({@code
     * ChrCustomizationElement.ChrCustomizationBoneSetID} to {@code ChrCustomizationBoneSet}); the
     * Stand plays on the base rig, which is also the shape the static preview shows. Unreadable
     * tables only make the fact less specific.
     */
    private Fact boneFileFact(
            long chrModelId, long modelFileDataId, List<Long> boneFiles, Cancellation token) {
        var issues = new ArrayList<String>();
        BoneFileChoice choice;
        boolean whole;
        try {
            var options = media.data("ChrCustomizationOption", 4096, token, issues);
            var choices = media.data("ChrCustomizationChoice", 40000, token, issues);
            var elements = media.data("ChrCustomizationElement", 200000, token, issues);
            var sets = media.data("ChrCustomizationBoneSet", 40000, token, issues);
            options.field("ChrModelID", true);
            choices.field("ChrCustomizationOptionID", true);
            elements.field("ChrCustomizationChoiceID", true);
            elements.field("ChrCustomizationBoneSetID", true);
            sets.field("BoneFileDataID", true);
            sets.field("ModelFileDataID", true);
            whole =
                    options.complete()
                            && choices.complete()
                            && elements.complete()
                            && sets.complete();
            var optionIds = new java.util.HashSet<Long>();
            var optionNames = new java.util.HashMap<Long, String>();
            for (var option : options.rows) {
                if (options.number(option, "ChrModelID") == chrModelId) {
                    optionIds.add(option.id());
                    optionNames.put(option.id(), optionName(options, option));
                }
            }
            var choiceOption = new java.util.HashMap<Long, Long>();
            for (var row : choices.rows) {
                long option = choices.number(row, "ChrCustomizationOptionID");
                if (optionIds.contains(option)) {
                    choiceOption.put(row.id(), option);
                }
            }
            var setFiles = new java.util.HashMap<Long, Long>();
            for (var row : sets.rows) {
                // A bone set names the model it reshapes; a set for another model never applies.
                if (sets.number(row, "ModelFileDataID") == modelFileDataId) {
                    setFiles.put(row.id(), sets.number(row, "BoneFileDataID"));
                }
            }
            var selecting = new TreeMap<Long, Long>();
            for (var row : elements.rows) {
                token.check();
                long set = elements.number(row, "ChrCustomizationBoneSetID");
                long id = elements.number(row, "ChrCustomizationChoiceID");
                if (set != 0 && choiceOption.containsKey(id) && setFiles.containsKey(set)) {
                    selecting.merge(id, setFiles.get(set), Math::min);
                }
            }
            choice = boneFileChoice(choiceOption, optionNames, selecting);
        } catch (RacesService.Projection unreadable) {
            return new Fact(
                    boneFileFact(
                            boneFiles.size(),
                            null,
                            "which customization choice selects them could not be read: "
                                    + unreadable.getMessage(),
                            true),
                    !(unreadable instanceof RacesService.Unreadable));
        }
        return new Fact(boneFileFact(boneFiles.size(), choice, null, whole), true);
    }

    private static String optionName(RacesService.Data options, Db2Types.Row option) {
        try {
            options.field("Name_lang", false);
            String name = options.text(option, "Name_lang");
            return name.isBlank() ? null : name;
        } catch (RacesService.Projection unreadable) {
            return null;
        }
    }

    /**
     * The choice a bone file fact names: among the options whose choices select a bone file, the
     * lowest option ID; within it, its first recorded (lowest-ID) choice, as the Races sample
     * picks. {@code selecting} maps each selecting choice to its (lowest) bone file.
     */
    record BoneFileChoice(
            long optionId,
            String optionName,
            long firstChoiceId,
            long boneFileDataId,
            int selectingChoices) {}

    static BoneFileChoice boneFileChoice(
            Map<Long, Long> choiceOption,
            Map<Long, String> optionNames,
            Map<Long, Long> selecting) {
        if (selecting.isEmpty()) {
            return null;
        }
        long option = selecting.keySet().stream().mapToLong(choiceOption::get).min().orElseThrow();
        long first =
                choiceOption.entrySet().stream()
                        .filter(entry -> entry.getValue() == option)
                        .mapToLong(Map.Entry::getKey)
                        .min()
                        .orElseThrow();
        return new BoneFileChoice(
                option,
                optionNames.get(option),
                first,
                selecting.getOrDefault(first, 0L),
                selecting.size());
    }

    /**
     * @param whole false when a customization table was read only in part, so the absence of a
     *     selecting choice is not established
     */
    static String boneFileFact(int declared, BoneFileChoice choice, String unread, boolean whole) {
        var text =
                new StringBuilder("Bone files (BFID): ")
                        .append(declared)
                        .append(" declared, none applied; Stand plays on the base rig, and the")
                        .append(" face keeps the base rig's shape, as in the static preview. ");
        if (unread != null) {
            text.append("Bone files are customization choices; ").append(unread);
        } else if (choice == null && !whole) {
            text.append(
                    "Bone files are customization choices; the customization tables could not be"
                            + " fully read, so which choice selects one is not known.");
        } else if (choice == null) {
            text.append("No customization choice of this model selects one.");
        } else {
            String option =
                    choice.optionName() == null
                            ? "option " + choice.optionId()
                            : "'" + choice.optionName() + "' (option " + choice.optionId() + ")";
            text.append(choice.selectingChoices()).append(" customization choices select one; ");
            if (choice.boneFileDataId() != 0) {
                text.append("the first recorded ")
                        .append(option)
                        .append(" choice, ")
                        .append(choice.firstChoiceId())
                        .append(", selects bone file ")
                        .append(choice.boneFileDataId())
                        .append(", which is not applied.");
            } else {
                text.append("the first recorded ")
                        .append(option)
                        .append(" choice, ")
                        .append(choice.firstChoiceId())
                        .append(", selects none.");
            }
        }
        return text.toString();
    }

    /** Adds model-resident Stand facts; returns false if the source could not be read. */
    private static boolean stand(
            BuildModelTypes.StandRead stand,
            String stored,
            List<String> reasons,
            List<String> facts) {
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
                    "Stand (plays sequence "
                            + standFacts.sequenceIndex()
                            + ", "
                            + standFacts.durationMillis()
                            + " ms) is stored "
                            + stored
                            + ": "
                            + standFacts.keyCount()
                            + " step/linear keys on "
                            + standFacts.animatedBones()
                            + " bones.");
        }
        return stand.state() == BuildModelTypes.ReadState.READ
                || stand.state() == BuildModelTypes.ReadState.REJECTED;
    }
}
