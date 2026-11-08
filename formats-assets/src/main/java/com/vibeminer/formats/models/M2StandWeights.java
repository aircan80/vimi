package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.i16;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Texture weights (transparency tracks) at the sample a static Stand pose is taken at: playback
 * time 0 of the Stand sequence that {@link M2IdleAnimation} resolved (alias chain followed), with
 * global-sequence tracks on their own clocks, also at 0. That is the sample {@code
 * PreviewAnimation.pose(…, 0)} takes for bones: a track holds its first key at or before its first
 * key time, so the value at time 0 is the first key of the played slice, whatever the key times and
 * for step and linear keys alike. A slice without keys is full weight (1), as {@link
 * M2Appearance.ModelFacts#staticWeights} treats it. A track whose value cannot be decided is
 * returned with a refusal, never a guessed value.
 */
public final class M2StandWeights {
    public static final String REVISION = "m2-stand-texture-weights-v1";

    /** Full weight: fixed-point 32767. */
    private static final int FULL = 32767;

    private static final int MAX_SLICES = 4096;

    private M2StandWeights() {}

    /**
     * One weight track at the Stand sample: {@code value} in [0, 1] when {@code refusal} is null;
     * otherwise {@code value} is NaN and {@code refusal} says why it is undecided.
     */
    public record Weight(float value, String refusal) {
        public Weight {
            if (refusal == null ? !(value >= 0 && value <= 1) : !Float.isNaN(value)) {
                throw new IllegalArgumentException("Invalid Stand texture weight");
            }
        }

        static Weight of(float value) {
            return new Weight(value, null);
        }

        static Weight refused(String reason) {
            return new Weight(Float.NaN, Objects.requireNonNull(reason));
        }

        public boolean resolved() {
            return refusal == null;
        }
    }

    /**
     * The value of every texture weight track (in {@link M2Types.Model#textureWeights()} index
     * order of the tracks themselves) at {@code stand}'s sample.
     *
     * @param source the model source {@code model} was parsed from
     * @param stand the Stand read for {@code model}
     */
    public static CompletableFuture<List<Weight>> read(
            AssetSource source,
            M2Types.Model model,
            M2IdleAnimation.Idle stand,
            Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(model);
        Objects.requireNonNull(stand);
        Objects.requireNonNull(token);
        if (stand.model() != model) {
            throw new IllegalArgumentException("Stand facts belong to another parsed model");
        }
        return AssetTasks.run(() -> readCore(source, model, stand, token));
    }

    private static List<Weight> readCore(
            AssetSource source,
            M2Types.Model model,
            M2IdleAnimation.Idle stand,
            Cancellation token) {
        token.check();
        var input = new M2Input(source, model.limits(), token);
        if (input.length != model.sourceBytes()) {
            throw error("INVALID", "Texture weight source differs from the parsed model.");
        }
        int header = (model.flags() & 8) != 0 ? 312 : 304;
        var weights = declaration(model, "weights");
        long globals = declaration(model, "globalSequences").count();
        // A Stand from an external skeleton plays the skeleton's sequence and global sequence
        // numbering; that the model's own tracks are numbered the same way is not verified.
        boolean skeleton = model.skeletonFileDataId() != 0;
        var rows = new ArrayList<byte[]>();
        input.records(
                input.span(
                        "weights",
                        weights.count(),
                        weights.offset(),
                        20,
                        model.limits().maxMetadataEntries(),
                        model.baseOffset(),
                        model.baseBytes(),
                        header),
                (row, i) -> rows.add(row));
        var out = new ArrayList<Weight>(rows.size());
        for (byte[] row : rows) {
            token.check();
            try {
                out.add(track(input, model, header, row, stand.sequenceIndex(), globals, skeleton));
            } catch (ModelFormatException invalid) {
                if (!invalid.code().equals("MODEL_INVALID")
                        && !invalid.code().equals("MODEL_LIMIT")) {
                    throw invalid;
                }
                out.add(Weight.refused("its track is unreadable: " + invalid.getMessage()));
            }
        }
        input.finish();
        return out;
    }

    private static Weight track(
            M2Input input,
            M2Types.Model model,
            int header,
            byte[] row,
            int sequence,
            long globals,
            boolean skeleton) {
        int interpolation = u16(row, 0);
        int global = i16(row, 2);
        long timeSlices = u32(row, 4), timeOffset = u32(row, 8);
        long valueSlices = u32(row, 12), valueOffset = u32(row, 16);
        if (timeSlices != valueSlices) {
            return Weight.refused("its track has different time and value slice counts");
        }
        if (valueSlices == 0) {
            return Weight.of(1);
        }
        if (skeleton) {
            return Weight.refused(
                    "the Stand plays from an external skeleton, whose sequence numbering the"
                            + " model's weight tracks are not verified to share");
        }
        if (global < -1 || global >= globals) {
            return Weight.refused("its track names an undeclared global sequence");
        }
        int slice = global >= 0 ? 0 : sequence;
        if (slice >= valueSlices) {
            // The track declares no keys for the Stand sequence, as for bone tracks.
            return Weight.of(1);
        }
        var times =
                input.span(
                        "weight times",
                        timeSlices,
                        timeOffset,
                        8,
                        MAX_SLICES,
                        model.baseOffset(),
                        model.baseBytes(),
                        header);
        var values =
                input.span(
                        "weight values",
                        valueSlices,
                        valueOffset,
                        8,
                        MAX_SLICES,
                        model.baseOffset(),
                        model.baseBytes(),
                        header);
        byte[] timeSlice = input.at(times.offset() + 8L * slice, 8);
        byte[] valueSlice = input.at(values.offset() + 8L * slice, 8);
        long keys = u32(valueSlice);
        if (keys != u32(timeSlice)) {
            return Weight.refused("its track has different time and value key counts");
        }
        if (keys == 0) {
            return Weight.of(1);
        }
        if (interpolation > 1) {
            return Weight.refused(
                    "its track uses curve interpolation "
                            + interpolation
                            + "; only step and linear keys are evaluated");
        }
        // The first key time only has to be readable: time 0 holds the first key either way.
        input.span(
                "weight key times",
                keys,
                u32(timeSlice, 4),
                4,
                Integer.MAX_VALUE,
                model.baseOffset(),
                model.baseBytes(),
                header);
        var first =
                input.span(
                        "weight keys",
                        keys,
                        u32(valueSlice, 4),
                        2,
                        Integer.MAX_VALUE,
                        model.baseOffset(),
                        model.baseBytes(),
                        header);
        int value = i16(input.at(first.offset(), 2), 0);
        if (value < 0) {
            return Weight.refused("its Stand key is negative");
        }
        return Weight.of(value >= FULL ? 1 : value / (float) FULL);
    }

    private static M2Types.ArrayDeclaration declaration(M2Types.Model model, String name) {
        return model.declarations().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> error("INVALID", "Parsed model lacks its " + name + " declaration."));
    }
}
