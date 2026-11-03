package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.audio.VoiceDecoder;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Serialized by the Races worker; media never owns the borrowed build/locale handles. */
@SuppressWarnings("try")
final class RaceVoices {
    private static final Map<String, String> ACTIONS =
            Map.of(
                    "JOKE",
                    "Joke",
                    "FLIRT",
                    "Flirt",
                    "CHARGE",
                    "Charge",
                    "HEALME",
                    "I need healing");
    private final RacesService owner;
    private final RaceMedia media;
    private final BuildTables tables;
    private final BuildFiles files;
    private final GeneratedStore generated;

    RaceVoices(
            RacesService owner,
            RaceMedia media,
            BuildTables tables,
            BuildFiles files,
            GeneratedStore generated) {
        this.owner = owner;
        this.media = media;
        this.tables = tables;
        this.files = files;
        this.generated = generated;
    }

    RaceVoiceTypes.Catalog catalog(long raceId, long classId, int sex, Cancellation token) {
        var clips = new ArrayList<RaceVoiceTypes.Clip>();
        var issues = new ArrayList<String>();
        try {
            var texts = media.data("EmotesText", 2048, token, issues);
            texts.field("Name", false);
            var mappings = media.data("EmotesTextSound", 8192, token, issues);
            mappings.reference("EmotesTextID", "EmotesText");
            mappings.reference("RaceID", "ChrRaces");
            mappings.reference("ClassID", "ChrClasses");
            mappings.field("SexID", true);
            mappings.field("SoundID", true);
            // This exact modern layout retains the historical SoundEntries foreign-key label.
            if (!mappings.schema.layoutHashes().contains("3ECA1BE0")) {
                throw mappings.unsupported("Unreviewed voice SoundID adapter");
            }
            var entries = owner.schema("SoundKitEntry", token, issues);
            entries.reference("SoundKitID", "SoundKit");
            entries.field("FileDataID", true);
            for (var mapping : mappings.rows) {
                token.check();
                long mappedClass = mappings.number(mapping, "ClassID");
                if (mappings.number(mapping, "RaceID") != raceId
                        || mappings.number(mapping, "SexID") != sex
                        || (mappedClass != 0 && mappedClass != classId)) {
                    continue;
                }
                long emote = mappings.number(mapping, "EmotesTextID");
                var text =
                        texts.rows.stream()
                                .filter(row -> row.id() == emote)
                                .findFirst()
                                .orElse(null);
                if (text == null) {
                    continue;
                }
                String action = ACTIONS.get(texts.text(text, "Name"));
                if (action == null) {
                    continue;
                }
                long kit = mappings.number(mapping, "SoundID");
                if (kit <= 0) {
                    continue;
                }
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                TableTypes.Ref.forName("SoundKitEntry"),
                                                -1,
                                                128,
                                                List.of(
                                                        new TableTypes.Filter(
                                                                "SoundKitID",
                                                                TableTypes.FilterOp.EQUALS,
                                                                Long.toString(kit),
                                                                null)),
                                                null),
                                        TableTypes.Priority.INTERACTIVE,
                                        token)
                                .join();
                if (page.state() != TableTypes.PageState.OK) {
                    note(issues, "Some voice entry rows are unavailable: " + page.state());
                    continue;
                }
                if (page.nextAfter() != null) {
                    note(issues, "Voice entry display is bounded to128 clips per kit.");
                }
                for (var entry : page.rows()) {
                    var row = entry.row();
                    long fid = entries.number(row, "FileDataID");
                    if (fid <= 0 || fid > 0xffffffffL) {
                        continue;
                    }
                    if (clips.stream().noneMatch(clip -> clip.entryId() == row.id())) {
                        clips.add(new RaceVoiceTypes.Clip(row.id(), emote, action, kit, fid));
                    }
                    if (clips.size() >= 256) {
                        note(issues, "Voice display is bounded to256 clips.");
                        break;
                    }
                }
                if (clips.size() >= 256) {
                    break;
                }
            }
            clips.sort(
                    java.util.Comparator.comparing(RaceVoiceTypes.Clip::action)
                            .thenComparingLong(RaceVoiceTypes.Clip::entryId));
            note(
                    issues,
                    "Recorded voice previews do not evaluate runtime player conditions or SoundKit mixing/randomization.");
            return new RaceVoiceTypes.Catalog(
                    RaceTypes.State.PARTIAL,
                    tables.locale(),
                    clips,
                    issues,
                    clips.isEmpty()
                            ? "No reviewed voice clips are readable for this race and voice."
                            : null);
        } catch (RacesService.Projection unavailable) {
            return new RaceVoiceTypes.Catalog(
                    unavailable.state, tables.locale(), clips, issues, unavailable.getMessage());
        }
    }

    RaceVoiceTypes.Audio audio(
            long raceId, long classId, int sex, long entryId, Cancellation token) {
        var available = catalog(raceId, classId, sex, token);
        var issues = new ArrayList<>(available.issues());
        var selected =
                available.clips().stream()
                        .filter(clip -> clip.entryId() == entryId)
                        .findFirst()
                        .orElse(null);
        if (selected == null || files == null) {
            return failed(issues, "Selected voice clip is unavailable.");
        }
        var ref = FileTypes.FileRef.id(selected.fileDataId());
        var stat = files.stat(ref, token).join();
        if (stat.state() != FileTypes.FileLookupState.FOUND
                || stat.file() == null
                || stat.file().size() == null
                || stat.file().size() < 32
                || stat.file().size() > VoiceDecoder.MAX_ENCODED) {
            return failed(issues, "Selected voice file is unavailable or exceeds preview limits.");
        }
        var key =
                GeneratedKey.hash(
                        (VoiceDecoder.REVISION
                                        + "\n"
                                        + files.build()
                                        + "\n"
                                        + files.locale()
                                        + "\n"
                                        + stat.file().contentKey())
                                .getBytes(StandardCharsets.UTF_8));
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            if (generated != null) {
                var saved = generated.read(key, lower).join();
                token.check();
                if (saved.state() == GeneratedTypes.ReadState.PRESENT) {
                    try {
                        return new RaceVoiceTypes.Audio(
                                RaceTypes.State.PARTIAL,
                                VoiceDecoder.decodePcm(saved.bytes()),
                                true,
                                issues,
                                null);
                    } catch (java.io.IOException invalid) {
                        note(issues, "Saved voice derivative requires repair.");
                    }
                }
            }
            var bytes = files.readRange(ref, 0, Math.toIntExact(stat.file().size()), token).join();
            if (bytes.state() != FileTypes.FileReadState.COMPLETE || bytes.bytes() == null) {
                return failed(issues, "Voice file is " + bytes.state());
            }
            var pcm = VoiceDecoder.decode(bytes.bytes(), token::isCanceled);
            token.check();
            if (generated != null) {
                try {
                    generated.delete(List.of(key), lower).join();
                    generated
                            .put(
                                    List.of(
                                            new GeneratedTypes.Put(
                                                    key, VoiceDecoder.encodePcm(pcm))),
                                    lower)
                            .join();
                } catch (java.util.concurrent.CancellationException canceled) {
                    throw canceled;
                } catch (RuntimeException failure) {
                    token.check();
                    note(issues, "Voice derivative could not be saved.");
                }
            }
            return new RaceVoiceTypes.Audio(RaceTypes.State.PARTIAL, pcm, false, issues, null);
        } catch (java.io.IOException failure) {
            token.check();
            return failed(issues, failure.getMessage());
        }
    }

    private static RaceVoiceTypes.Audio failed(List<String> issues, String message) {
        return new RaceVoiceTypes.Audio(RaceTypes.State.UNAVAILABLE, null, false, issues, message);
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }
}
