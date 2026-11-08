package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.f;
import static com.vibeminer.formats.models.M2Input.i16;
import static com.vibeminer.formats.models.M2Input.i32;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;
import static com.vibeminer.formats.models.M2Input.v3;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded reader for one qualified idle sequence: the Stand animation (ID 0, variation 0) whose
 * bone keys are stored in the model itself. An alias Stand plays the sequence its alias chain ends
 * at. External skeletons, external bone or animation files and curve interpolation are refused as
 * unsupported rather than approximated. Bone flags are left to consumers, which know which bones
 * move rendered vertices.
 */
public final class M2IdleAnimation {
    public static final String REVISION = "m2-idle-local-stand-v1";
    public static final int STAND = 0;
    public static final int MAX_BONES = 256;
    public static final int MAX_SEQUENCES = 4096;
    public static final int MAX_GLOBAL_SEQUENCES = 1024;
    public static final int MAX_KEYS_PER_TRACK = 4096;
    public static final int MAX_TOTAL_KEYS = 262_144;
    public static final long MAX_DURATION_MILLIS = 600_000;

    private static final int SEQUENCE_IN_MODEL = 0x20;
    private static final int SEQUENCE_ALIAS = 0x40;
    private static final int SKS1_HEADER = 32, SKB1_HEADER = 16;

    private M2IdleAnimation() {}

    public enum Interpolation {
        STEP,
        LINEAR
    }

    /** One key slice; times are milliseconds, values are packed with {@link #components()}. */
    public static final class Track {
        private final Interpolation interpolation;
        private final int globalSequence;
        private final int components;
        private final int[] times;
        private final float[] values;

        Track(
                Interpolation interpolation,
                int globalSequence,
                int components,
                int[] times,
                float[] values) {
            this.interpolation = Objects.requireNonNull(interpolation);
            this.globalSequence = globalSequence;
            this.components = components;
            this.times = times;
            this.values = values;
        }

        public Interpolation interpolation() {
            return interpolation;
        }

        /** -1 for sequence time; otherwise the global sequence index whose clock drives it. */
        public int globalSequence() {
            return globalSequence;
        }

        public int components() {
            return components;
        }

        public int keyCount() {
            return times.length;
        }

        public int[] times() {
            return times.clone();
        }

        /** Translation/scale as xyz; rotation as normalized xyzw quaternions. */
        public float[] values() {
            return values.clone();
        }
    }

    /** Own tracks of one bone; an absent track is an identity component for this sequence. */
    public record BoneTracks(Track translation, Track rotation, Track scale) {
        public boolean animated() {
            return translation != null || rotation != null || scale != null;
        }
    }

    public static final class Idle {
        private final M2Types.Model model;
        private final List<M2Types.Bone> rig;
        private final int sequenceIndex;
        private final long durationMillis;
        private final long[] globalSequenceMillis;
        private final List<BoneTracks> bones;
        private final int keys;

        private Idle(
                M2Types.Model model,
                List<M2Types.Bone> rig,
                int sequenceIndex,
                long durationMillis,
                long[] globalSequenceMillis,
                List<BoneTracks> bones,
                int keys) {
            this.model = model;
            this.rig = List.copyOf(rig);
            this.sequenceIndex = sequenceIndex;
            this.durationMillis = durationMillis;
            this.globalSequenceMillis = globalSequenceMillis;
            this.bones = List.copyOf(bones);
            this.keys = keys;
        }

        public M2Types.Model model() {
            return model;
        }

        /**
         * The bones the tracks belong to: {@link M2Types.Model#bones()}, or the external skeleton's
         * bones for a skeleton model. Vertex bone indices name these bones.
         */
        public List<M2Types.Bone> rig() {
            return rig;
        }

        /** The sequence whose keys play: Stand itself, or the end of its alias chain. */
        public int sequenceIndex() {
            return sequenceIndex;
        }

        public long durationMillis() {
            return durationMillis;
        }

        public long[] globalSequenceMillis() {
            return globalSequenceMillis.clone();
        }

        /** Same order and size as {@link #rig()}. */
        public List<BoneTracks> bones() {
            return bones;
        }

        public int keyCount() {
            return keys;
        }

        public boolean animated() {
            return bones.stream().anyMatch(BoneTracks::animated);
        }
    }

    public static CompletableFuture<Idle> read(
            AssetSource source, M2Types.Model model, Cancellation token) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(token);
        return AssetTasks.run(() -> readCore(source, model, token, true, true));
    }

    /**
     * As {@link #read}, but plays the base rig's Stand when the model names bone files (BFID). Bone
     * files are character customization options: the client applies one only through a
     * customization choice ({@code ChrCustomizationElement} to {@code ChrCustomizationBoneSet}), so
     * an appearance without a choice, such as a creature display, plays the rig without them.
     * External skeletons are still refused.
     */
    public static CompletableFuture<Idle> readBaseRig(
            AssetSource source, M2Types.Model model, Cancellation token) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(token);
        return AssetTasks.run(() -> readCore(source, model, token, true, false));
    }

    /**
     * Reads the base rig's Stand of a model whose bones live in an external skeleton (#384): the
     * skeleton's own bones (SKB1), sequences and global sequences (SKS1), with track arrays inside
     * the SKB1 chunk. A parent skeleton (SKPD) is not read: the skeleton must carry its own bones
     * and its own Stand keys. Bone files are not applied, as for {@link #readBaseRig}; every other
     * check of {@link #read} applies.
     *
     * @param source the skeleton file that {@code skeleton} was read from
     */
    public static CompletableFuture<Idle> readSkeleton(
            AssetSource source,
            M2SkeletonFile.Skeleton skeleton,
            M2Types.Model model,
            Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(skeleton);
        Objects.requireNonNull(model);
        Objects.requireNonNull(token);
        return AssetTasks.run(() -> readSkeletonCore(source, skeleton, model, token));
    }

    private static Idle readSkeletonCore(
            AssetSource source,
            M2SkeletonFile.Skeleton skeleton,
            M2Types.Model model,
            Cancellation token) {
        token.check();
        if (!model.isChunked() || model.version() != 272 && model.version() != 274) {
            throw error("UNSUPPORTED", "Idle animation requires a chunked 272/274 model.");
        }
        if (model.skeletonFileDataId() == 0) {
            throw new IllegalArgumentException("The model names no external skeleton");
        }
        if (!model.bones().isEmpty()) {
            throw error(
                    "UNSUPPORTED",
                    "The model stores bones of its own beside its external skeleton.");
        }
        var rig = skeleton.bones();
        var sks1 = skeleton.chunk("SKS1");
        var skb1 = skeleton.chunk("SKB1");
        if (rig.isEmpty() || sks1 == null || skb1 == null) {
            throw error(
                    "UNSUPPORTED",
                    skeleton.parentFileDataId() != 0
                            ? "The skeleton keeps its bones or sequences in its parent skeleton,"
                                    + " which idle evaluation does not read."
                            : "The skeleton has no bones or sequences to animate.");
        }
        if (rig.size() > MAX_BONES) {
            throw error(
                    "UNSUPPORTED",
                    "The skeleton has "
                            + rig.size()
                            + " bones; idle evaluation admits at most "
                            + MAX_BONES
                            + " directly indexed bones.");
        }
        if (skeleton.sequences().size() > MAX_SEQUENCES
                || skeleton.globalSequenceMillis().size() > MAX_GLOBAL_SEQUENCES) {
            throw error("LIMIT", "The skeleton's sequence declarations exceed idle policy.");
        }
        var input = new M2Input(source, new M2Types.Limits(), token, "skeleton");
        if (input.length != skeleton.sourceBytes()) {
            throw error("INVALID", "Animation source differs from the parsed skeleton.");
        }
        byte[] sequenceHead = input.at(sks1.offset(), 16);
        byte[] boneHead = input.at(skb1.offset(), 8);
        if (u32(sequenceHead, 8) != skeleton.sequences().size()
                || u32(sequenceHead, 0) != skeleton.globalSequenceMillis().size()
                || u32(boneHead, 0) != rig.size()) {
            throw error("INVALID", "Animation source header differs from the parsed skeleton.");
        }
        var sequenceRows = new ArrayList<byte[]>(skeleton.sequences().size());
        input.records(
                input.span(
                        "skeleton sequences",
                        skeleton.sequences().size(),
                        u32(sequenceHead, 12),
                        64,
                        MAX_SEQUENCES,
                        sks1.offset(),
                        sks1.byteLength(),
                        SKS1_HEADER),
                (row, i) -> sequenceRows.add(row));
        var rows = new ArrayList<byte[]>(rig.size());
        input.records(
                input.span(
                        "skeleton bones",
                        rig.size(),
                        u32(boneHead, 4),
                        88,
                        MAX_BONES,
                        skb1.offset(),
                        skb1.byteLength(),
                        SKB1_HEADER),
                (row, i) -> rows.add(row));
        long[] globalMillis =
                skeleton.globalSequenceMillis().stream().mapToLong(Long::longValue).toArray();
        return stand(
                input,
                new Section(skb1.offset(), skb1.byteLength(), SKB1_HEADER),
                model,
                rig,
                sequenceRows,
                globalMillis,
                rows,
                "skeleton",
                token);
    }

    /**
     * Facts about the model-resident Stand sequence, read with every check of {@link #read} except
     * the external skeleton and bone-file gate. It never yields tracks, so its result cannot be
     * evaluated; callers use it to report every reason a model with external files is refused.
     *
     * @param refusal null when the model-resident Stand passed; otherwise the unsupported or budget
     *     reason, in which case the other fields are 0 or -1
     */
    public record StandFacts(
            int sequenceIndex,
            long durationMillis,
            int keyCount,
            int animatedBones,
            String refusal) {}

    /**
     * Reads {@link StandFacts} for a parsed chunked model. Structural disagreement still fails with
     * {@code MODEL_INVALID}; unsupported content and budget refusals are returned as the refusal.
     */
    public static CompletableFuture<StandFacts> assessStand(
            AssetSource source, M2Types.Model model, Cancellation token) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(token);
        return AssetTasks.run(
                () -> {
                    try {
                        var idle = readCore(source, model, token, false, false);
                        int animated =
                                (int) idle.bones().stream().filter(BoneTracks::animated).count();
                        return new StandFacts(
                                idle.sequenceIndex(),
                                idle.durationMillis(),
                                idle.keyCount(),
                                animated,
                                null);
                    } catch (ModelFormatException refused) {
                        if (!refused.code().equals("MODEL_UNSUPPORTED")
                                && !refused.code().equals("MODEL_LIMIT")) {
                            throw refused;
                        }
                        return new StandFacts(-1, 0, 0, 0, refused.getMessage());
                    }
                });
    }

    private static Idle readCore(
            AssetSource source,
            M2Types.Model model,
            Cancellation token,
            boolean refuseSkeleton,
            boolean refuseBoneFiles) {
        token.check();
        if (!model.isChunked() || model.version() != 272 && model.version() != 274) {
            throw error("UNSUPPORTED", "Idle animation requires a chunked 272/274 model.");
        }
        boolean skeleton = model.skeletonFileDataId() != 0;
        boolean boneFiles = model.boneFileDataIds().stream().anyMatch(id -> id != 0);
        if (refuseBoneFiles && (skeleton || boneFiles)) {
            throw error(
                    "UNSUPPORTED",
                    "The model uses an external skeleton or bone file; idle evaluation reads"
                            + " only bones stored in the model.");
        }
        if (refuseSkeleton && skeleton) {
            throw error(
                    "UNSUPPORTED",
                    "The model uses an external skeleton; idle evaluation reads only bones stored"
                            + " in the model.");
        }
        if (model.bones().isEmpty()) {
            throw error("UNSUPPORTED", "The model has no bones to animate.");
        }
        if (model.bones().size() > MAX_BONES) {
            throw error(
                    "UNSUPPORTED",
                    "The model has "
                            + model.bones().size()
                            + " bones; idle evaluation admits at most "
                            + MAX_BONES
                            + " directly indexed bones.");
        }
        var input = new M2Input(source, model.limits(), token);
        if (input.length != model.sourceBytes()) {
            throw error("INVALID", "Animation source differs from the parsed model.");
        }
        int header = (model.flags() & 8) == 0 ? 304 : 312;
        var core = input.at(model.baseOffset(), 52);
        if (i32(core, 0) != 0x3032444d
                || u32(core, 4) != model.version()
                || u32(core, 16) != model.flags()) {
            throw error("INVALID", "Animation source header differs from the parsed model.");
        }
        var globals = declaration(model, "globalSequences", core, 20);
        var sequences = declaration(model, "sequences", core, 28);
        var bones = declaration(model, "bones", core, 44);
        if (bones.count() != model.bones().size() || bones.stride() != 88) {
            throw error("INVALID", "Animation bone declaration differs from parsed bones.");
        }
        if (globals.count() > MAX_GLOBAL_SEQUENCES || sequences.count() > MAX_SEQUENCES) {
            throw error("LIMIT", "The model's sequence declarations exceed idle policy.");
        }
        long[] globalMillis = new long[(int) globals.count()];
        input.records(
                span(input, model, header, "globalSequences", globals, 4),
                (row, i) -> globalMillis[i] = u32(row));
        var sequenceRows = new ArrayList<byte[]>((int) sequences.count());
        input.records(
                span(input, model, header, "sequences", sequences, 64),
                (row, i) -> sequenceRows.add(row));
        var rows = new ArrayList<byte[]>();
        input.records(span(input, model, header, "bones", bones, 88), (row, i) -> rows.add(row));
        return stand(
                input,
                new Section(model.baseOffset(), model.baseBytes(), header),
                model,
                model.bones(),
                sequenceRows,
                globalMillis,
                rows,
                "model",
                token);
    }

    /**
     * Finds Stand among {@code sequenceRows}, follows its alias chain and reads every bone's own
     * tracks for it. {@code rows} are the raw bone records, checked against {@code rig}.
     */
    private static Idle stand(
            M2Input input,
            Section section,
            M2Types.Model model,
            List<M2Types.Bone> rig,
            List<byte[]> sequenceRows,
            long[] globalMillis,
            List<byte[]> rows,
            String owner,
            Cancellation token) {
        int stand = -1;
        for (int i = 0; i < sequenceRows.size() && stand < 0; i++) {
            byte[] row = sequenceRows.get(i);
            if (u16(row) == STAND && u16(row, 2) == 0) {
                stand = i;
            }
        }
        if (stand < 0) {
            throw error(
                    "UNSUPPORTED",
                    "The " + owner + " records no Stand sequence (ID 0, variation 0).");
        }
        int sequence = aliasTarget(sequenceRows, stand);
        byte[] target = sequenceRows.get(sequence);
        long duration = u32(target, 4);
        int flags = i32(target, 12);
        if ((flags & SEQUENCE_IN_MODEL) == 0) {
            throw error(
                    "UNSUPPORTED",
                    "Stand keys are stored in an external animation file, which idle evaluation"
                            + " does not read.");
        }
        if (duration <= 0 || duration > MAX_DURATION_MILLIS) {
            throw error(
                    "UNSUPPORTED",
                    "The Stand sequence duration is outside the idle playback range.");
        }
        var budget = new int[] {0};
        var result = new ArrayList<BoneTracks>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            token.check();
            byte[] row = rows.get(index);
            var bone = rig.get(index);
            if (bone.keyBoneId() != i32(row, 0)
                    || bone.flags() != u32(row, 4)
                    || bone.parent() != i16(row, 8)
                    || !bone.pivot().equals(v3(row, 76))) {
                throw error("INVALID", "Animation bone row differs from parsed bone metadata.");
            }
            result.add(
                    new BoneTracks(
                            track(
                                    input,
                                    section,
                                    row,
                                    16,
                                    3,
                                    sequence,
                                    globalMillis,
                                    duration,
                                    budget),
                            track(
                                    input,
                                    section,
                                    row,
                                    36,
                                    4,
                                    sequence,
                                    globalMillis,
                                    duration,
                                    budget),
                            track(
                                    input,
                                    section,
                                    row,
                                    56,
                                    3,
                                    sequence,
                                    globalMillis,
                                    duration,
                                    budget)));
        }
        input.finish();
        return new Idle(model, rig, sequence, duration, globalMillis, result, budget[0]);
    }

    /**
     * Follows the alias chain from {@code start} (flag 0x40, next index at byte 62) to the sequence
     * that holds the keys. An alias names another sequence whose keys and duration it plays.
     */
    static int aliasTarget(List<byte[]> rows, int start) {
        int at = start;
        for (int steps = 0; (i32(rows.get(at), 12) & SEQUENCE_ALIAS) != 0; steps++) {
            if (steps >= rows.size()) {
                throw error("INVALID", "The Stand sequence alias chain contains a cycle.");
            }
            int next = u16(rows.get(at), 62);
            if (next >= rows.size()) {
                throw error("INVALID", "The Stand sequence aliases an undeclared sequence.");
            }
            at = next;
        }
        return at;
    }

    private static M2Types.ArrayDeclaration declaration(
            M2Types.Model model, String name, byte[] core, int at) {
        var matches = model.declarations().stream().filter(d -> d.name().equals(name)).toList();
        if (matches.size() != 1) {
            throw error("INVALID", "Animation declaration " + name + " is missing or ambiguous.");
        }
        var declaration = matches.getFirst();
        if (u32(core, at) != declaration.count() || u32(core, at + 4) != declaration.offset()) {
            throw error("INVALID", "Animation declaration " + name + " differs from the model.");
        }
        return declaration;
    }

    private static M2Input.Span span(
            M2Input input,
            M2Types.Model model,
            int header,
            String name,
            M2Types.ArrayDeclaration declaration,
            int stride) {
        return input.span(
                name,
                declaration.count(),
                declaration.offset(),
                stride,
                Integer.MAX_VALUE,
                model.baseOffset(),
                model.baseBytes(),
                header);
    }

    /** Where track arrays may lie: offsets count from {@code origin}, after {@code header}. */
    private record Section(long origin, long bytes, int header) {}

    private static Track track(
            M2Input input,
            Section section,
            byte[] row,
            int at,
            int components,
            int sequence,
            long[] globalMillis,
            long sequenceMillis,
            int[] budget) {
        int interpolation = u16(row, at);
        int global = i16(row, at + 2);
        long timeSlices = u32(row, at + 4), timeOffset = u32(row, at + 8);
        long valueSlices = u32(row, at + 12), valueOffset = u32(row, at + 16);
        if (timeSlices != valueSlices) {
            throw error("INVALID", "A bone track has different time and value slice counts.");
        }
        if (global < -1 || global >= globalMillis.length) {
            throw error("INVALID", "A bone track names an undeclared global sequence.");
        }
        if (timeSlices == 0) {
            return null;
        }
        int slice = global >= 0 ? 0 : sequence;
        if (slice >= timeSlices) {
            // The track declares no keys for this sequence.
            return null;
        }
        var outerTimes =
                input.span(
                        "bone track times",
                        timeSlices,
                        timeOffset,
                        8,
                        MAX_SEQUENCES,
                        section.origin(),
                        section.bytes(),
                        section.header());
        var outerValues =
                input.span(
                        "bone track values",
                        valueSlices,
                        valueOffset,
                        8,
                        MAX_SEQUENCES,
                        section.origin(),
                        section.bytes(),
                        section.header());
        byte[] times = input.at(outerTimes.offset() + 8L * slice, 8);
        byte[] values = input.at(outerValues.offset() + 8L * slice, 8);
        long keys = u32(times);
        if (keys != u32(values)) {
            throw error("INVALID", "A bone track has different time and value key counts.");
        }
        if (keys == 0) {
            return null;
        }
        if (interpolation > 1) {
            throw error(
                    "UNSUPPORTED",
                    "A Stand bone track uses curve interpolation "
                            + interpolation
                            + "; only step and linear keys are evaluated.");
        }
        if (keys > MAX_KEYS_PER_TRACK || keys > MAX_TOTAL_KEYS - budget[0]) {
            throw error("LIMIT", "Stand bone keys exceed the idle evaluation budget.");
        }
        budget[0] += (int) keys;
        // A global sequence of length 0 never advances: its clock stays at 0 and evaluation holds
        // the first key, so its keys may be stamped at any time (client data stamps them past 0).
        long clock = global < 0 ? sequenceMillis : globalMillis[global];
        long limit = global >= 0 && clock == 0 ? Long.MAX_VALUE : clock;
        int count = (int) keys;
        int[] stamps = new int[count];
        input.records(
                input.span(
                        "bone key times",
                        keys,
                        u32(times, 4),
                        4,
                        MAX_KEYS_PER_TRACK,
                        section.origin(),
                        section.bytes(),
                        section.header()),
                (key, i) -> {
                    long stamp = u32(key);
                    if (i > 0 && stamp < stamps[i - 1]) {
                        throw error("INVALID", "Bone key times are unordered.");
                    }
                    if (stamp > limit) {
                        throw error("INVALID", "A bone key time lies beyond its sequence.");
                    }
                    // Stamps are kept as int milliseconds.
                    if (stamp > Integer.MAX_VALUE) {
                        throw error(
                                "INVALID", "A bone key time is outside the supported key range.");
                    }
                    stamps[i] = (int) stamp;
                });
        int stride = components == 4 ? 8 : 12;
        float[] packed = new float[count * components];
        input.records(
                input.span(
                        "bone key values",
                        keys,
                        u32(values, 4),
                        stride,
                        MAX_KEYS_PER_TRACK,
                        section.origin(),
                        section.bytes(),
                        section.header()),
                (key, i) -> {
                    if (components == 3) {
                        packed[i * 3] = f(key, 0);
                        packed[i * 3 + 1] = f(key, 4);
                        packed[i * 3 + 2] = f(key, 8);
                        for (int c = 0; c < 3; c++) {
                            if (Math.abs(packed[i * 3 + c]) > 1_000_000) {
                                throw error("INVALID", "A bone key value is out of range.");
                            }
                        }
                    } else {
                        double x = quaternion(i16(key, 0)),
                                y = quaternion(i16(key, 2)),
                                z = quaternion(i16(key, 4)),
                                w = quaternion(i16(key, 6));
                        double length = Math.sqrt(x * x + y * y + z * z + w * w);
                        if (length < 1e-6) {
                            throw error("INVALID", "A bone rotation key is degenerate.");
                        }
                        packed[i * 4] = (float) (x / length);
                        packed[i * 4 + 1] = (float) (y / length);
                        packed[i * 4 + 2] = (float) (z / length);
                        packed[i * 4 + 3] = (float) (w / length);
                    }
                });
        return new Track(
                interpolation == 0 ? Interpolation.STEP : Interpolation.LINEAR,
                global,
                components,
                stamps,
                packed);
    }

    /** Signed 16-bit compressed quaternion component as written by the client format. */
    static double quaternion(int value) {
        return (value < 0 ? value + 32768 : value - 32767) / 32767.0;
    }
}
