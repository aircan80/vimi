package com.vibeminer.concepts.races;

import com.vibeminer.formats.audio.PcmClip;
import java.util.List;

/** Recorded race/sex/class voice choices; no account or gameplay conditions are inferred. */
public final class RaceVoiceTypes {
    private RaceVoiceTypes() {}

    public record Clip(
            long entryId, long emoteId, String action, long soundKitId, long fileDataId) {}

    public record Catalog(
            RaceTypes.State state,
            String locale,
            List<Clip> clips,
            List<String> issues,
            String message) {
        public Catalog {
            clips = List.copyOf(clips);
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
