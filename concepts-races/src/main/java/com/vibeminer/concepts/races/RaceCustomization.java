package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        // A class or race mask that excludes this context denies, whatever else the requirement
        // holds: checked before any term that only makes it unknown, so a choice refused for
        // this race or class is never sampled as merely unchecked.
        long mask = req.classMask() & 0xffffffffL;
        if (mask != 0
                && mask != 0xffffffffL
                && (classId < 1 || classId > 32 || (mask & (1L << (classId - 1))) == 0)) {
            return Eligibility.DENIED;
        }
        if (!hasRace(req.raceLow(), req.raceHigh(), raceBit)) {
            return Eligibility.DENIED;
        }
        if (req.type() != 1 && req.type() != 3) {
            return Eligibility.UNKNOWN;
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
        return select(raceId, classId, modelId, token, issues, Map.of());
    }

    Selection select(
            long raceId,
            long classId,
            long modelId,
            Cancellation token,
            List<String> issues,
            Map<Long, Long> requested) {
        if (requested.size() > 4096) {
            throw new IllegalArgumentException("Too many customization overrides");
        }
        var overrides = Map.copyOf(requested);
        for (var entry : overrides.entrySet()) {
            if (entry.getKey() < 1
                    || entry.getKey() > 0xffffffffL
                    || entry.getValue() < 1
                    || entry.getValue() > 0xffffffffL) {
                throw new IllegalArgumentException("Invalid customization IDs");
            }
        }
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
                        .orElseThrow(
                                () ->
                                        new RacesService.Projection(
                                                RaceTypes.State.UNSUPPORTED, NOT_PLAYABLE));
        long bit = races.number(race, "PlayableRaceBit");
        if (bit < 0 || bit > 63) {
            // Stated plainly to the user, without the table name the data refusal prefixes.
            throw new RacesService.Projection(RaceTypes.State.UNSUPPORTED, NOT_PLAYABLE);
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
            var eligibleRows =
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
                            .toList();
            Optional<Db2Types.Row> fallback = Optional.empty();
            if (eligibleRows.isEmpty()) {
                // No choice is known to be eligible. A choice whose requirements only cannot be
                // checked here (an account, archive or prerequisite term), never one refused for
                // this race or class, still shows the option rather than leaving its texture
                // region empty: the first such recorded choice is the sample. The catalog lists it
                // as the default, so it may also be requested.
                fallback =
                        candidates.stream()
                                .filter(
                                        row ->
                                                choices.number(row, "ChrCustomizationVisReqID") == 0
                                                        && eligibility(
                                                                        choices.number(
                                                                                row,
                                                                                "ChrCustomizationReqID"),
                                                                        classId,
                                                                        (int) bit,
                                                                        requirements,
                                                                        prerequisites,
                                                                        new java.util.ArrayList<>())
                                                                == Eligibility.UNKNOWN)
                                .findFirst();
            }
            Optional<Db2Types.Row> match;
            if (overrides.containsKey(option.id())) {
                long requestedChoice = overrides.get(option.id());
                match =
                        eligibleRows.stream()
                                .filter(row -> row.id() == requestedChoice)
                                .findFirst();
                if (match.isEmpty()
                        && fallback.isPresent()
                        && fallback.get().id() == requestedChoice) {
                    match = fallback;
                    note(issues, UNVERIFIED_SAMPLE);
                }
                if (match.isEmpty()) {
                    throw options.unsupported(
                            "Requested choice is not a known eligible option member");
                }
            } else if (!eligibleRows.isEmpty()) {
                match = Optional.of(eligibleRows.getFirst());
            } else {
                match = fallback;
                if (match.isPresent()) {
                    note(issues, UNVERIFIED_SAMPLE);
                }
            }
            if (match.isPresent()) {
                selected.put(option.id(), match.get().id());
            } else {
                note(
                        issues,
                        "Some customization options have no known eligible readable sample choice.");
            }
        }
        if (!selected.keySet().containsAll(overrides.keySet())) {
            throw options.unsupported("Requested option is not eligible for this model");
        }
        return new Selection(media, raceId, classId, modelId, selected, all);
    }

    /**
     * A humanoid NPC's customization (#385): the recorded {@code requested} choices, each checked
     * to belong to an option of this model (NPC choices are not subject to player eligibility),
     * over the sample selection for every option the NPC does not record. A recorded choice that is
     * not a member of one of this model's options is unknown and refuses.
     */
    Selection npc(
            long raceId,
            long classId,
            long modelId,
            Map<Long, Long> requested,
            Cancellation token,
            List<String> issues) {
        var sample = select(raceId, classId, modelId, token, issues);
        if (requested.isEmpty()) {
            return sample;
        }
        var options = media.data("ChrCustomizationOption", 4096, token, issues);
        var choices = media.data("ChrCustomizationChoice", 40000, token, issues);
        RaceAppearance.requireProfile(options);
        RaceAppearance.requireProfile(choices);
        options.field("ChrModelID", true);
        choices.reference("ChrCustomizationOptionID", "ChrCustomizationOption");
        var modelOptions = new HashSet<Long>();
        for (var option : options.rows) {
            if (options.number(option, "ChrModelID") == modelId) {
                modelOptions.add(option.id());
            }
        }
        var optionOf = new java.util.HashMap<Long, Long>();
        for (var choice : choices.rows) {
            optionOf.put(choice.id(), choices.number(choice, "ChrCustomizationOptionID"));
        }
        var byOption = new TreeMap<>(sample.byOption());
        var all = new HashSet<>(sample.allChoices());
        for (var entry : requested.entrySet()) {
            token.check();
            if (!modelOptions.contains(entry.getKey())
                    || !entry.getKey().equals(optionOf.get(entry.getValue()))) {
                throw options.unsupported("the customization choices are unknown");
            }
            byOption.put(entry.getKey(), entry.getValue());
            all.add(entry.getValue());
        }
        return new Selection(media, raceId, classId, modelId, byOption, all);
    }

    RaceCustomizationTypes.Catalog catalog(
            long raceId, long classId, long modelId, Cancellation token) {
        var issues = new java.util.ArrayList<String>();
        try {
            if (raceId != 3 || modelId != 5) {
                throw new RacesService.Projection(
                        RaceTypes.State.UNSUPPORTED,
                        "Appearance controls currently support Dwarf male Skin Color only.");
            }
            var selection = select(raceId, classId, modelId, token, issues);
            var defaultChoice = selection.byOption().get(30L);
            if (defaultChoice == null) {
                throw new RacesService.Projection(
                        RaceTypes.State.UNSUPPORTED,
                        "Skin Color has no known eligible default in this context.");
            }
            var options = media.data("ChrCustomizationOption", 4096, token, issues);
            var choices = media.data("ChrCustomizationChoice", 40000, token, issues);
            var requirements = media.data("ChrCustomizationReq", 4096, token, issues);
            var prerequisites = media.data("ChrCustomizationReqChoice", 4096, token, issues);
            var races = media.data("ChrRaces", 512, token, issues);
            options.field("Name_lang", false);
            var optionRows = options.rows.stream().filter(row -> row.id() == 30).toList();
            if (optionRows.size() != 1
                    || options.number(optionRows.getFirst(), "ChrModelID") != modelId) {
                throw options.unsupported("Skin Color option/model binding is unavailable");
            }
            var race =
                    races.rows.stream().filter(row -> row.id() == raceId).findFirst().orElseThrow();
            int bit = Math.toIntExact(races.number(race, "PlayableRaceBit"));
            var members = new java.util.ArrayList<RaceCustomizationTypes.Choice>();
            var seen = new HashSet<Long>();
            for (var choice :
                    choices.rows.stream()
                            .sorted(Comparator.comparingLong(Db2Types.Row::id))
                            .toList()) {
                token.check();
                if (choices.number(choice, "ChrCustomizationOptionID") != 30) {
                    continue;
                }
                if (!seen.add(choice.id())) {
                    throw choices.unsupported("Ambiguous Skin Color choice identity");
                }
                if (choices.number(choice, "ChrCustomizationVisReqID") != 0
                        || !eligible(
                                choices.number(choice, "ChrCustomizationReqID"),
                                classId,
                                bit,
                                requirements,
                                prerequisites,
                                issues)) {
                    continue;
                }
                if (members.size() == 64) {
                    throw choices.unsupported("Skin Color choices exceed preview bounds");
                }
                members.add(
                        new RaceCustomizationTypes.Choice(choice.id(), "Choice #" + choice.id()));
            }
            if (members.stream().noneMatch(member -> member.id() == defaultChoice)) {
                // The sampled default when no member is known to be eligible: one whose
                // requirements only cannot be checked here. It is listed, and said so.
                if (members.size() == 64) {
                    throw choices.unsupported("Skin Color choices exceed preview bounds");
                }
                members.add(
                        0,
                        new RaceCustomizationTypes.Choice(
                                defaultChoice, "Choice #" + defaultChoice));
                if (issues.size() < 32 && !issues.contains(UNVERIFIED_SAMPLE)) {
                    issues.add(UNVERIFIED_SAMPLE);
                }
            }
            String label = options.text(optionRows.getFirst(), "Name_lang");
            if (label == null || label.isBlank() || label.length() > 256) {
                throw options.unsupported("Skin Color option label is unavailable");
            }
            if (issues.size() < 32) {
                issues.add(
                        "Dwarf male Skin Color preview; other appearance options are not yet exposed.");
            }
            return new RaceCustomizationTypes.Catalog(
                    RaceTypes.State.PARTIAL,
                    raceId,
                    classId,
                    modelId,
                    List.of(new RaceCustomizationTypes.Option(30, label, members, defaultChoice)),
                    issues,
                    null);
        } catch (RacesService.Projection failure) {
            return new RaceCustomizationTypes.Catalog(
                    failure.state,
                    raceId,
                    classId,
                    modelId,
                    List.of(),
                    List.of(),
                    failure.getMessage());
        }
    }

    Map<Long, Long> requested(RaceCustomizationTypes.Request request, Cancellation token) {
        if (request.picks().isEmpty()) {
            return Map.of();
        }
        var catalog = catalog(request.raceId(), request.classId(), request.modelId(), token);
        var result = new TreeMap<Long, Long>();
        for (var pick : request.picks()) {
            var option =
                    catalog.options().stream()
                            .filter(value -> value.id() == pick.optionId())
                            .findFirst();
            if (option.isEmpty()
                    || option.get().choices().stream()
                            .noneMatch(value -> value.id() == pick.choiceId())) {
                throw new RacesService.Projection(
                        RaceTypes.State.UNSUPPORTED,
                        "Requested choice is not in the eligible customization catalog.");
            }
            result.put(pick.optionId(), pick.choiceId());
        }
        return Map.copyOf(result);
    }

    /**
     * A race without a playable-race customization record (an NPC race or a mount). Shown by the
     * geoset sample of the untextured preview too, so it says nothing about textures; the texture
     * path states its own reason ({@link RaceAppearancePlan#NOT_PLAYABLE}).
     */
    static final String NOT_PLAYABLE = "This race has no playable-race customization record.";

    /** The issue a sample choice with uncheckable requirements adds. */
    static final String UNVERIFIED_SAMPLE =
            "Some sample choices have requirements that cannot be checked here; the first recorded"
                    + " choice is shown.";

    private static boolean eligible(
            long id,
            long classId,
            int raceBit,
            RacesService.Data reqs,
            RacesService.Data prerequisites,
            List<String> issues) {
        return eligibility(id, classId, raceBit, reqs, prerequisites, issues)
                == Eligibility.ALLOWED;
    }

    /** {@link #evaluate} for requirement {@code id}; DENIED when its row is unavailable. */
    private static Eligibility eligibility(
            long id,
            long classId,
            int raceBit,
            RacesService.Data reqs,
            RacesService.Data prerequisites,
            List<String> issues) {
        if (id == 0) {
            return Eligibility.ALLOWED;
        }
        var row =
                reqs.rows.stream()
                        .filter(candidate -> candidate.id() == id)
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            note(issues, "Some customization requirements are unavailable.");
            return Eligibility.DENIED;
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
        return outcome;
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }
}
