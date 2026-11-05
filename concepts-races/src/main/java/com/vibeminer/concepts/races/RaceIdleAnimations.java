package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explains, per race model, why its Stand idle is not offered. Runs on the race component worker.
 *
 * <p>Every reason is collected rather than only the first: an external skeleton (SKID), the BFID
 * bone files (each read and validated through {@link com.vibeminer.formats.models.M2BoneFile}), and
 * the model-resident Stand with every check of the mount idle reader except its external-file gate.
 * Bone files hold per-bone matrices, but which file the client applies for an appearance and how
 * those matrices combine with Stand keys are not reviewed, so a model declaring any is refused
 * rather than approximated. Results whose sources were all read are cached per model content; a
 * source that could not be read is reassessed.
 */
final class RaceIdleAnimations {
    static final int MAX_BONE_FILES = 64;
    private static final int MAX_CACHED = 32;
    private final RaceMedia media;
    private final BuildFiles files;
    private final Map<String, RaceMediaTypes.Idle> cached =
            new LinkedHashMap<>(16, 0.75f, true) {
                @java.io.Serial private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RaceMediaTypes.Idle> eldest) {
                    return size() > MAX_CACHED;
                }
            };

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
        var known = cached.get(key);
        if (known != null) {
            return new RaceMediaTypes.Idle(
                    known.state(), variant, known.reasons(), known.facts(), known.message());
        }
        var models = new BuildModels(files);
        var read = models.readModel(FileTypes.FileRef.id(variant.modelFileDataId()), token).join();
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
        var assessed =
                explain(
                        model.skeletonFileDataId(),
                        !model.bones().isEmpty(),
                        model.boneFileDataIds(),
                        id -> models.readBoneFile(read, id, token).join(),
                        () -> models.readStandFacts(read, token).join(),
                        token);
        var reasons = assessed.reasons();
        var facts = assessed.facts();
        boolean complete = assessed.complete();
        var result =
                new RaceMediaTypes.Idle(
                        RaceTypes.State.UNSUPPORTED,
                        variant,
                        reasons,
                        facts,
                        String.join(" ", reasons));
        if (complete) {
            cached.put(key, result);
        }
        return result;
    }

    /** Every blocking reason and supporting fact; {@code complete} when all sources were read. */
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
        if (reasons.isEmpty()) {
            reasons.add(
                    "The model stores its Stand keys itself, but Stand playback is not yet"
                            + " implemented for race previews.");
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
