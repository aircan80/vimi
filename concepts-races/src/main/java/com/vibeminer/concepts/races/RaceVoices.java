package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.audio.VoiceDecoder;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
    static final int MAX_REFUSALS = 32;
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
        var refusals = new ArrayList<RaceVoiceTypes.Refusal>();
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
            var byEmote = new LinkedHashMap<Long, List<Db2Types.Row>>();
            for (var mapping : mappings.rows) {
                token.check();
                long mappedClass = mappings.number(mapping, "ClassID");
                if (mappings.number(mapping, "RaceID") != raceId
                        || mappings.number(mapping, "SexID") != sex
                        || (mappedClass != 0 && mappedClass != classId)) {
                    continue;
                }
                byEmote.computeIfAbsent(
                                mappings.number(mapping, "EmotesTextID"),
                                ignored -> new ArrayList<>())
                        .add(mapping);
            }
            int unreviewed = 0;
            for (var group : byEmote.entrySet()) {
                token.check();
                long emote = group.getKey();
                var links = group.getValue();
                var text =
                        texts.rows.stream()
                                .filter(row -> row.id() == emote)
                                .findFirst()
                                .orElse(null);
                if (text == null) {
                    for (var mapping : links) {
                        refuse(
                                refusals,
                                issues,
                                "EmotesTextSound #" + mapping.id() + " → EmotesText #" + emote,
                                "The linked EmotesText row is not recorded in this build.");
                    }
                    continue;
                }
                String name = texts.text(text, "Name");
                String action = ACTIONS.get(name);
                if (action == null) {
                    unreviewed += links.size();
                    continue;
                }
                var kits = new TreeSet<Long>();
                var kitLinks = new ArrayList<Db2Types.Row>();
                for (var mapping : links) {
                    long kit = mappings.number(mapping, "SoundID");
                    if (kit <= 0) {
                        refuse(
                                refusals,
                                issues,
                                "EmotesTextSound #" + mapping.id() + " (" + action + ")",
                                "No SoundKit is recorded for this link.");
                    } else {
                        kits.add(kit);
                        kitLinks.add(mapping);
                    }
                }
                if (kits.size() > 1) {
                    refuse(
                            refusals,
                            issues,
                            "EmotesText #"
                                    + emote
                                    + " "
                                    + name
                                    + " → EmotesTextSound "
                                    + ids(kitLinks.stream().map(Db2Types.Row::id).toList()),
                            "Links name different SoundKits "
                                    + ids(List.copyOf(kits))
                                    + " for this race, sex and class; the runtime choice is not"
                                    + " evaluated.");
                    continue;
                }
                if (kits.isEmpty()) {
                    continue;
                }
                long kit = kits.first();
                var mapping =
                        kitLinks.stream()
                                .min(java.util.Comparator.comparingLong(Db2Types.Row::id))
                                .orElseThrow();
                var source =
                        new RaceVoiceTypes.Source(
                                name,
                                mapping.id(),
                                raceId,
                                sex,
                                mappings.number(mapping, "ClassID"));
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
                if (page.rows().isEmpty()) {
                    refuse(
                            refusals,
                            issues,
                            "SoundKit #" + kit + " (" + action + ")",
                            "No SoundKitEntry rows are recorded for this SoundKit.");
                    continue;
                }
                if (page.nextAfter() != null) {
                    note(issues, "Voice entry display is bounded to128 clips per kit.");
                }
                for (var entry : page.rows()) {
                    var row = entry.row();
                    long fid = entries.number(row, "FileDataID");
                    if (fid <= 0 || fid > 0xffffffffL) {
                        refuse(
                                refusals,
                                issues,
                                "SoundKitEntry #" + row.id() + " (" + action + ")",
                                "No valid FileDataID is recorded for this entry.");
                        continue;
                    }
                    if (clips.stream().noneMatch(clip -> clip.entryId() == row.id())) {
                        clips.add(
                                new RaceVoiceTypes.Clip(row.id(), emote, action, kit, fid, source));
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
            if (unreviewed > 0) {
                note(
                        issues,
                        unreviewed
                                + " recorded voice links use emotes outside the reviewed joke,"
                                + " flirt, charge and healing actions.");
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
                    refusals,
                    issues,
                    clips.isEmpty()
                            ? "No reviewed voice clips are readable for this race and voice."
                            : null);
        } catch (RacesService.Projection unavailable) {
            return new RaceVoiceTypes.Catalog(
                    unavailable.state,
                    tables.locale(),
                    clips,
                    refusals,
                    issues,
                    unavailable.getMessage());
        }
    }

    private static String ids(List<Long> ids) {
        var text = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 8; i++) {
            text.append(i == 0 ? "#" : ", #").append(ids.get(i));
        }
        return ids.size() > 8 ? text + " and " + (ids.size() - 8) + " more" : text.toString();
    }

    private static void refuse(
            List<RaceVoiceTypes.Refusal> refusals,
            List<String> issues,
            String link,
            String reason) {
        if (refusals.size() < MAX_REFUSALS) {
            refusals.add(new RaceVoiceTypes.Refusal(link, reason));
        } else {
            note(issues, "Refused voice link display is bounded to" + MAX_REFUSALS + " links.");
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
