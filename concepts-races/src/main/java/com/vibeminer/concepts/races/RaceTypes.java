package com.vibeminer.concepts.races;

import java.util.List;

/** Client records are not account unlocks or current server character-creation eligibility. */
public final class RaceTypes {
    private RaceTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED,
        INVALID
    }

    public record Race(long id, String name, int order, int recordedClasses) {}

    public record PlayerClass(long id, String name) {}

    public record Ability(
            long id, String name, String description, Long iconFileDataId, int order) {}

    public record Catalog(State state, List<Race> races, List<String> issues, String message) {
        public Catalog {
            races = List.copyOf(races);
            issues = List.copyOf(issues);
        }
    }

    public record Overview(
            State state,
            Race race,
            String lore,
            Long factionTemplateId,
            String faction,
            List<PlayerClass> classes,
            List<Ability> abilities,
            List<String> issues,
            String message) {
        public Overview {
            classes = List.copyOf(classes);
            abilities = List.copyOf(abilities);
            issues = List.copyOf(issues);
        }
    }
}
