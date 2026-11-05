package com.vibeminer.concepts.races;

import com.vibeminer.formats.audio.PcmClip;
import java.util.List;

/** Recorded race/sex/class voice choices; no account or gameplay conditions are inferred. */
public final class RaceVoiceTypes {
    private RaceVoiceTypes() {}

    /**
     * The recorded rows that admit one clip: EmotesText, the EmotesTextSound link for the race, sex
     * and class (0 means every class), the linked SoundKit and its SoundKitEntry file.
     */
    public record Source(
            String emoteName, long mappingId, long raceId, int sex, long mappedClassId) {}

    public record Clip(
            long entryId,
            long emoteId,
            String action,
            long soundKitId,
            long fileDataId,
            Source source) {
        /** Display-only chain of recorded row identifiers; contains no local paths. */
        public String chain() {
            String link =
                    source == null
                            ? "EmotesTextSound"
                            : "EmotesTextSound #"
                                    + source.mappingId()
                                    + " (race "
                                    + source.raceId()
                                    + ", sex "
                                    + source.sex()
                                    + ", "
                                    + (source.mappedClassId() == 0
                                            ? "every class"
                                            : "class " + source.mappedClassId())
                                    + ")";
            return "EmotesText #"
                    + emoteId
                    + (source == null ? "" : " " + source.emoteName())
                    + " → "
                    + link
                    + " → SoundKit #"
                    + soundKitId
                    + " → SoundKitEntry #"
                    + entryId
                    + " → FileDataID "
                    + fileDataId;
        }
    }

    /** A recorded link that was not admitted for playback, with a fixed path-free reason. */
    public record Refusal(String link, String reason) {}

    public record Catalog(
            RaceTypes.State state,
            String locale,
            List<Clip> clips,
            List<Refusal> refusals,
            List<String> issues,
            String message) {
        public Catalog {
            clips = List.copyOf(clips);
            refusals = List.copyOf(refusals);
            issues = List.copyOf(issues);
        }
    }

    public record Audio(
            RaceTypes.State state,
            PcmClip clip,
            boolean restored,
            List<String> issues,
            String message) {
        public Audio {
            issues = List.copyOf(issues);
        }
    }
}
