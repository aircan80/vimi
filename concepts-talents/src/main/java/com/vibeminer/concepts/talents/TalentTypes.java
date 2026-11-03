package com.vibeminer.concepts.talents;

import java.util.List;

/** Immutable client facts for read-only Classic talent trees; no character or allocation state. */
public final class TalentTypes {
    private TalentTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED,
        INVALID
    }

    public record PlayerClass(long id, String name, Long iconFileDataId, String color) {}

    /** Rank is the recorded zero-based prerequisite rank index, not points spent. */
    public record Prerequisite(long talentId, int rankIndex) {}

    public record Talent(
            long id,
            int row,
            int column,
            List<Long> rankSpells,
            List<Prerequisite> prerequisites,
            String name,
            String description,
            Long iconFileDataId) {
        public Talent {
            rankSpells = List.copyOf(rankSpells);
            prerequisites = List.copyOf(prerequisites);
        }
    }

    public record Tree(long id, String name, int order, String background, List<Talent> talents) {
        public Tree {
            talents = List.copyOf(talents);
        }
    }

    public record Catalog(State state, List<PlayerClass> classes, String code, String message) {
        public Catalog {
            classes = List.copyOf(classes);
        }
    }

    public record ClassTrees(
            State state,
            PlayerClass playerClass,
            List<Tree> trees,
            List<String> issues,
            String code,
            String message) {
        public ClassTrees {
            trees = List.copyOf(trees);
            issues = List.copyOf(issues);
        }
    }
}
