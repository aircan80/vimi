package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Known-player sample selection only; unknown unlock/prerequisite requirements are not guessed. */
final class RaceCustomization {
    record Selection(
            RaceMedia owner,
            long raceId,
            long classId,
            long modelId,
            Map<Long, Long> byOption,
            Set<Long> choices,
            Set<Long> allChoices) {
        Selection(
                RaceMedia owner,
                long raceId,
                long classId,
                long modelId,
                Map<Long, Long> byOption,
                Set<Long> allChoices) {
            this(owner, raceId, classId, modelId, byOption, Set.of(), allChoices);
        }

        Selection {
            byOption = java.util.Collections.unmodifiableMap(new TreeMap<>(byOption));
            choices = Set.copyOf(byOption.values());
            allChoices = Set.copyOf(allChoices);
        }

        void requireContext(RaceMedia media, long race, long characterClass, long model) {
            if (owner != media || raceId != race || classId != characterClass || modelId != model) {
                throw new IllegalArgumentException("Customization selection context changed");
            }
        }
    }

    enum Eligibility {
        ALLOWED,
        DENIED,
        UNKNOWN
    }

    record Requirement(
            long type,
            long classMask,
            long raceLow,
            long raceHigh,
            long region,
            long achievement,
            long quest,
            long archive,
            long appearance,
            boolean prerequisites) {}

    static boolean hasRace(long low, long high, int bit) {
        return bit >= 0
                && bit < 64
                && ((((low & 0xffffffffL) | ((high & 0xffffffffL) << 32)) & (1L << bit)) != 0);
    }

    static Eligibility evaluate(Requirement req, long classId, int raceBit) {
        if ((req.type() & 1) == 0) {
            return Eligibility.DENIED;
        }
        if (req.type() != 1 && req.type() != 3) {
            return Eligibility.UNKNOWN;
        }
        long mask = req.classMask() & 0xffffffffL;
        if (mask != 0
                && mask != 0xffffffffL
                && (classId < 1 || classId > 32 || (mask & (1L << (classId - 1))) == 0)) {
            return Eligibility.DENIED;
        }
        if (!hasRace(req.raceLow(), req.raceHigh(), raceBit)) {
            return Eligibility.DENIED;
        }
        if (req.region() != 0
                || req.achievement() != 0
                || req.quest() != 0
                || req.archive() != -1
                || req.appearance() != 0
                || req.prerequisites()) {
            return Eligibility.UNKNOWN;
        }
        return Eligibility.ALLOWED;
    }

    private final RaceMedia media;

    RaceCustomization(RaceMedia media) {
        this.media = media;
    }

    Selection select(
            long raceId, long classId, long modelId, Cancellation token, List<String> issues) {
        var options = media.data("ChrCustomizationOption", 4096, token, issues);
        var choices = media.data("ChrCustomizationChoice", 40000, token, issues);
        var requirements = media.data("ChrCustomizationReq", 4096, token, issues);
        var prerequisites = media.data("ChrCustomizationReqChoice", 4096, token, issues);
        var races = media.data("ChrRaces", 512, token, issues);
        for (var data : List.of(options, choices, requirements, prerequisites, races)) {
            RaceAppearance.requireProfile(data);
        }
        options.field("ChrModelID", true);
        options.field("Requirement", true);
        choices.reference("ChrCustomizationOptionID", "ChrCustomizationOption");
        for (String field : List.of("ChrCustomizationReqID", "ChrCustomizationVisReqID")) {
            choices.field(field, true);
        }
        for (String field :
                List.of(
                        "ReqType",
                        "ClassMask",
                        "RegionGroupMask",
                        "ReqAchievementID",
                        "ReqQuestID",
                        "OverrideArchive",
                        "ReqItemModifiedAppearanceID")) {
            requirements.field(field, true);
        }
        requirements.field("RaceMasks", true, 2);
        prerequisites.field("ChrCustomizationReqID", true);
        races.field("PlayableRaceBit", true);
        var race =
                races.rows.stream()
                        .filter(row -> row.id() == raceId)
                        .findFirst()
                        .orElseThrow(() -> races.unsupported("Race mask is unavailable"));
        long bit = races.number(race, "PlayableRaceBit");
        if (bit < 0 || bit > 63) {
            throw races.unsupported("Unreviewed playable-race bit");
        }
        var selected = new TreeMap<Long, Long>();
        var all = new HashSet<Long>();
        for (var option : options.rows) {
            token.check();
            if (options.number(option, "ChrModelID") != modelId) {
                continue;
            }
            if (!eligible(
                    options.number(option, "Requirement"),
                    classId,
                    (int) bit,
                    requirements,
                    prerequisites,
                    issues)) {
                continue;
            }
            var candidates =
                    choices.rows.stream()
                            .filter(
                                    row ->
                                            choices.number(row, "ChrCustomizationOptionID")
                                                    == option.id())
                            .sorted(Comparator.comparingLong(Db2Types.Row::id))
                            .toList();
            candidates.forEach(row -> all.add(row.id()));
            var match =
                    candidates.stream()
                            .filter(
                                    row ->
                                            choices.number(row, "ChrCustomizationVisReqID") == 0
                                                    && eligible(
                                                            choices.number(
                                                                    row, "ChrCustomizationReqID"),
                                                            classId,
                                                            (int) bit,
                                                            requirements,
                                                            prerequisites,
                                                            issues))
                            .findFirst();
            if (match.isPresent()) {
                selected.put(option.id(), match.get().id());
            } else {
                note(
                        issues,
                        "Some customization options have no known eligible readable sample choice.");
            }
        }
        return new Selection(media, raceId, classId, modelId, selected, all);
    }

    private static boolean eligible(
            long id,
            long classId,
            int raceBit,
            RacesService.Data reqs,
            RacesService.Data prerequisites,
            List<String> issues) {
        if (id == 0) {
            return true;
        }
        var row =
                reqs.rows.stream()
                        .filter(candidate -> candidate.id() == id)
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            note(issues, "Some customization requirements are unavailable.");
            return false;
        }
        var requirement =
                new Requirement(
                        reqs.number(row, "ReqType"),
                        reqs.number(row, "ClassMask"),
                        reqs.number(row, "RaceMasks[0]"),
                        reqs.number(row, "RaceMasks[1]"),
                        reqs.number(row, "RegionGroupMask"),
                        reqs.number(row, "ReqAchievementID"),
                        reqs.number(row, "ReqQuestID"),
                        reqs.number(row, "OverrideArchive"),
                        reqs.number(row, "ReqItemModifiedAppearanceID"),
                        !prerequisites.complete()
                                || prerequisites.rows.stream()
                                        .anyMatch(
                                                candidate ->
                                                        prerequisites.number(
                                                                        candidate,
                                                                        "ChrCustomizationReqID")
                                                                == id));
        var outcome = evaluate(requirement, classId, raceBit);
        if (outcome == Eligibility.UNKNOWN) {
            note(
                    issues,
                    "Unknown account, region, archive or prerequisite requirements are omitted from the sample.");
        }
        return outcome == Eligibility.ALLOWED;
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }
}
