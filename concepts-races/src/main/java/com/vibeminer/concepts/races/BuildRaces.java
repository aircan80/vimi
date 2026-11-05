package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only race projection borrowing one build/locale table handle. */
public interface BuildRaces extends AutoCloseable {
    CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<RaceTypes.Overview> overview(long raceId, Cancellation token);

    default CompletableFuture<RaceMediaTypes.Variants> modelVariants(
            long raceId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceMediaTypes.Variants(
                        RaceTypes.State.UNAVAILABLE,
                        java.util.List.of(),
                        java.util.List.of(),
                        "Model data unavailable"));
    }

    default CompletableFuture<RaceMediaTypes.Preview> preview(
            long raceId, long classId, long chrModelId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceMediaTypes.Preview(
                        RaceTypes.State.UNAVAILABLE,
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.List.of(),
                        false,
                        "Model preview unavailable"));
    }

    default CompletableFuture<RaceVoiceTypes.Catalog> voices(
            long raceId, long classId, int sex, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceVoiceTypes.Catalog(
                        RaceTypes.State.UNAVAILABLE,
                        "",
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        "Voice data unavailable"));
    }

    default CompletableFuture<RaceVoiceTypes.Audio> voice(
            long raceId, long classId, int sex, long entryId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceVoiceTypes.Audio(
                        RaceTypes.State.UNAVAILABLE,
                        null,
                        false,
                        java.util.List.of(),
                        "Voice playback unavailable"));
    }

    default CompletableFuture<RaceAppearanceTypes.Result> appearance(
            long raceId, long classId, long modelId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceAppearanceTypes.Result(
                        RaceTypes.State.UNAVAILABLE,
                        null,
                        java.util.Set.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        false,
                        "Textured sample is unavailable"));
    }

    default CompletableFuture<RaceCustomizationTypes.Catalog> customizations(
            long raceId, long classId, long modelId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceCustomizationTypes.Catalog(
                        RaceTypes.State.UNAVAILABLE,
                        raceId,
                        classId,
                        modelId,
                        java.util.List.of(),
                        java.util.List.of(),
                        "Customization controls unavailable"));
    }

    default CompletableFuture<RaceAppearanceTypes.Result> appearance(
            RaceCustomizationTypes.Request request, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceAppearanceTypes.Result(
                        RaceTypes.State.UNAVAILABLE,
                        null,
                        java.util.Set.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        false,
                        "Requested customization is unavailable"));
    }

    /**
     * Assesses the selected model's Stand idle (ID 0, variation 0) against the reviewed playback
     * profile, reporting every reason it cannot be offered.
     */
    default CompletableFuture<RaceMediaTypes.Idle> idle(
            long raceId, long chrModelId, Cancellation token) {
        return CompletableFuture.completedFuture(
                RaceMediaTypes.Idle.unavailable(null, "Idle assessment unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
