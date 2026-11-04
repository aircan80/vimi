package com.vibeminer.concepts.races;

import com.vibeminer.concepts.races.RaceMediaTypes.Definition;
import com.vibeminer.concepts.races.RaceMediaTypes.DefinitionState;
import com.vibeminer.concepts.races.RaceMediaTypes.Quality;
import com.vibeminer.formats.db2.Db2Types;

/**
 * States SD or HD only from an explicit, verified ChrRaces display field pair for the selected sex.
 * Fallback slots, file names, polygon counts, LODs and texture mips are never evidence.
 */
final class RaceModelDefinitions {
    private static final String DISPLAYS = "CreatureDisplayInfo";

    private RaceModelDefinitions() {}

    /**
     * @param races ChrRaces schema, or null when the table is unavailable
     * @param race the race row, or null when it is unavailable
     */
    static Definition assess(
            RacesService.Data races, long raceId, Db2Types.Row race, int sex, long displayId) {
        if (races == null) {
            return Definition.refused(
                    DefinitionState.UNREADABLE, "ChrRaces is unavailable; retry can reassess it.");
        }
        if (sex != 0 && sex != 1) {
            return Definition.refused(
                    DefinitionState.UNSUPPORTED_PROFILE,
                    "Only recorded male and female display fields can state a definition.");
        }
        String standard = sex == 0 ? "MaleDisplayID" : "FemaleDisplayID";
        String high = "HighRes" + standard;
        boolean hasStandard = races.has(standard), hasHigh = races.has(high);
        if (!hasStandard && !hasHigh) {
            return Definition.refused(
                    DefinitionState.UNSUPPORTED_PROFILE,
                    "This build's ChrRaces layout records no SD/HD display fields, so the source"
                            + " does not say which definition this model is.");
        }
        if (!hasStandard || !hasHigh) {
            return Definition.refused(
                    DefinitionState.UNSUPPORTED_PROFILE,
                    "ChrRaces records only "
                            + (hasStandard ? standard : high)
                            + "; one field alone does not establish an SD/HD pair.");
        }
        try {
            races.reference(standard, DISPLAYS);
            races.reference(high, DISPLAYS);
        } catch (RacesService.Projection unverified) {
            return Definition.refused(
                    DefinitionState.UNSUPPORTED_PROFILE, unverified.getMessage() + ".");
        }
        if (race == null || race.id() != raceId) {
            return Definition.refused(
                    DefinitionState.UNREADABLE,
                    "ChrRaces #" + raceId + " is unavailable; retry can reassess it.");
        }
        long sd, hd;
        try {
            sd = races.number(race, standard);
            hd = races.number(race, high);
        } catch (RacesService.Projection unreadable) {
            return Definition.refused(DefinitionState.UNREADABLE, unreadable.getMessage() + ".");
        }
        if (sd <= 0 || hd <= 0 || sd > 0xffffffffL || hd > 0xffffffffL) {
            return new Definition(
                    DefinitionState.NO_RECORDED_PAIR,
                    null,
                    null,
                    Math.max(sd, 0),
                    Math.max(hd, 0),
                    "ChrRaces #"
                            + raceId
                            + " records no "
                            + (sd <= 0 || sd > 0xffffffffL ? standard : high)
                            + " display; no SD/HD pair is recorded.");
        }
        if (sd == hd) {
            return new Definition(
                    DefinitionState.AMBIGUOUS,
                    null,
                    null,
                    sd,
                    hd,
                    "ChrRaces #"
                            + raceId
                            + " names display "
                            + sd
                            + " as both "
                            + standard
                            + " and "
                            + high
                            + "; SD and HD cannot be distinguished.");
        }
        if (displayId == hd || displayId == sd) {
            boolean isHigh = displayId == hd;
            return new Definition(
                    DefinitionState.SELECTED,
                    isHigh ? Quality.HD : Quality.SD,
                    "ChrRaces #" + raceId + " " + (isHigh ? high : standard),
                    sd,
                    hd,
                    "Display "
                            + displayId
                            + " is the recorded "
                            + (isHigh ? "high-resolution" : "standard")
                            + " display; its "
                            + (isHigh ? "SD" : "HD")
                            + " counterpart is display "
                            + (isHigh ? sd : hd)
                            + ".");
        }
        return new Definition(
                DefinitionState.UNMATCHED,
                null,
                null,
                sd,
                hd,
                "Display "
                        + displayId
                        + " matches neither ChrRaces #"
                        + raceId
                        + " "
                        + standard
                        + " ("
                        + sd
                        + ") nor "
                        + high
                        + " ("
                        + hd
                        + ").");
    }
}
