package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Lazy per-display preview component borrowing one build's tables, files and generated store. */
public interface BuildDisplayModels extends AutoCloseable {
    CompletableFuture<DisplayModelTypes.Preview> preview(long displayId, Cancellation token);

    /**
     * The display's binding for the World (#361), which follows the World objects' batch rule
     * (#360): each section draws texture 1 of its first {@code Diffuse_T1} opaque or alpha-keyed
     * batch, without the preview's reviewed material profiles. Implementations without that rule
     * answer as {@link #preview}.
     */
    default CompletableFuture<DisplayModelTypes.Preview> worldPreview(
            long displayId, Cancellation token) {
        return preview(displayId, token);
    }

    /**
     * The display's table relationships only (model, texture variations, extended display info),
     * without reading its model; null when the display or its model data is not recorded.
     * Implementations without a cheaper path answer from {@link #worldPreview}.
     */
    default CompletableFuture<DisplayModelTypes.Reference> reference(
            long displayId, Cancellation token) {
        return worldPreview(displayId, token)
                .thenApply(preview -> preview == null ? null : preview.reference());
    }

    /**
     * Lazily qualifies the display's idle (Stand) sequence against the same rest surface that
     * {@link #preview} returns. Implementations without animation support report UNAVAILABLE.
     */
    default CompletableFuture<DisplayModelTypes.Idle> idle(long displayId, Cancellation token) {
        return CompletableFuture.completedFuture(
                DisplayModelTypes.Idle.unavailable(
                        "Idle animation is not provided by this source."));
    }

    /**
     * The display's Stand for the World, which draws no camera-facing parts (#384, following the
     * World objects' rule from #360): bones that billboard, and their descendants, are evaluated as
     * ordinary bones and every vertex weighted to them is listed in {@link
     * DisplayModelTypes.Idle#leftOut()} for the World to leave out. Implementations without that
     * profile answer as {@link #idle}.
     */
    default CompletableFuture<DisplayModelTypes.Idle> worldIdle(
            long displayId, Cancellation token) {
        return idle(displayId, token);
    }

    /**
     * Both answers, {@link #idle} then {@link #worldIdle}, for measurements; implementations may
     * read the model once for both.
     */
    default CompletableFuture<java.util.List<DisplayModelTypes.Idle>> stands(
            long displayId, Cancellation token) {
        return idle(displayId, token).thenCombine(worldIdle(displayId, token), java.util.List::of);
    }

    /**
     * The display's sequences for a moving body in the World, by AnimationData ID (Stand 0, Walk 4,
     * Run 5, SwimIdle 41, Swim 42 and so on), each admitted under the World profile against the
     * same rest surface as {@link #worldIdle}. A sequence the model does not store (or stores in an
     * external file) answers UNSUPPORTED; implementations without animation answer UNAVAILABLE.
     */
    default CompletableFuture<java.util.Map<Integer, DisplayModelTypes.Idle>> worldSequences(
            long displayId, int[] animationIds, Cancellation token) {
        var out = new java.util.LinkedHashMap<Integer, DisplayModelTypes.Idle>();
        for (int id : animationIds) {
            out.put(
                    id,
                    DisplayModelTypes.Idle.unavailable(
                            "Sequences are not provided by this source."));
        }
        return CompletableFuture.completedFuture(out);
    }

    /**
     * The display's recorded body size (model box and both scales), for a body's capsule; null when
     * the display or its model is not in the tables. Implementations without tables answer null.
     */
    default CompletableFuture<DisplayModelTypes.BodySize> bodySize(
            long displayId, Cancellation token) {
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
