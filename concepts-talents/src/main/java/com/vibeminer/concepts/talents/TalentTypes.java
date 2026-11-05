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

    /** The client table family that supplied a class's trees. */
    public enum Model {
        /** Legacy TalentTab/Talent rows with one spell per rank. */
        LEGACY_TALENT,
        /** SkillLineXTraitTree, TraitNode/TraitNodeEntry/TraitDefinition and TraitEdge rows. */
        TRAIT_TREE
    }

    public enum PrerequisiteKind {
        /** Legacy Talent PrereqTalent with a recorded zero-based PrereqRank index. */
        LEGACY_RANK,
        /** TraitEdge type 2. The edge records no rank requirement. */
        TRAIT_SUFFICIENT,
        /** TraitEdge type 3. The edge records no rank requirement. */
        TRAIT_REQUIRED
    }

    public record PlayerClass(long id, String name, Long iconFileDataId, String color) {}

    /**
     * {@code rankIndex} is the recorded zero-based legacy prerequisite rank index, not points
     * spent. It is null for trait edges, which record no rank.
     */
    public record Prerequisite(long talentId, Integer rankIndex, PrerequisiteKind kind) {
        public Prerequisite {
            java.util.Objects.requireNonNull(kind);
            if ((kind == PrerequisiteKind.LEGACY_RANK) != (rankIndex != null)) {
                throw new IllegalArgumentException("Only legacy prerequisites record a rank");
            }
        }

        public Prerequisite(long talentId, int rankIndex) {
            this(talentId, rankIndex, PrerequisiteKind.LEGACY_RANK);
        }
    }

    /**
     * Source identifiers for one displayed talent. Trait fields are null for legacy rows. Raw
     * positions are the recorded TraitNode coordinates before grid placement.
     */
    public record Origin(
            Model model,
            Long traitTreeId,
            Long traitNodeEntryId,
            Long traitDefinitionId,
            Long rawPosX,
            Long rawPosY,
            Long overridesSpellId,
            boolean nameOverridden,
            boolean descriptionOverridden) {
        public static final Origin LEGACY =
                new Origin(Model.LEGACY_TALENT, null, null, null, null, null, null, false, false);

        public Origin {
            java.util.Objects.requireNonNull(model);
        }
    }

    /**
     * {@code id} is the Talent row ID for legacy rows and the TraitNode ID for trait nodes. {@code
     * rankSpells} are recorded spell IDs: one per rank for legacy rows and the single definition
     * spell for trait nodes. {@code maxRanks} is the recorded maximum rank. {@code iconIssue}
     * explains a missing icon reference when the source reported a cause; null otherwise. {@code
     * requiredTreePoints} is the number of points that must be spent in this talent's own tree
     * before it can be learned: the legacy Classic rule (5 per tier) for Talent rows, or the
     * recorded TraitCond spend requirement for TraitTree nodes; null when none applies or the
     * requirement is not known.
     */
    public record Talent(
            long id,
            int row,
            int column,
            List<Long> rankSpells,
            int maxRanks,
            List<Prerequisite> prerequisites,
            String name,
            String description,
            Long iconFileDataId,
            Origin origin,
            String iconIssue,
            Integer requiredTreePoints) {
        public Talent {
            rankSpells = List.copyOf(rankSpells);
            prerequisites = List.copyOf(prerequisites);
            java.util.Objects.requireNonNull(origin);
            if (rankSpells.isEmpty() || maxRanks < 1) {
                throw new IllegalArgumentException("A talent records at least one rank");
            }
            if (iconFileDataId != null) {
                iconIssue = null;
            }
            if (requiredTreePoints != null && requiredTreePoints < 1) {
                throw new IllegalArgumentException("A tier requirement needs at least one point");
            }
        }

        public Talent(
                long id,
                int row,
                int column,
                List<Long> rankSpells,
                int maxRanks,
                List<Prerequisite> prerequisites,
                String name,
                String description,
                Long iconFileDataId,
                Origin origin,
                String iconIssue) {
            this(
                    id,
                    row,
                    column,
                    rankSpells,
                    maxRanks,
                    prerequisites,
                    name,
                    description,
                    iconFileDataId,
                    origin,
                    iconIssue,
                    null);
        }

        public Talent(
                long id,
                int row,
                int column,
                List<Long> rankSpells,
                int maxRanks,
                List<Prerequisite> prerequisites,
                String name,
                String description,
                Long iconFileDataId,
                Origin origin) {
            this(
                    id,
                    row,
                    column,
                    rankSpells,
                    maxRanks,
                    prerequisites,
                    name,
                    description,
                    iconFileDataId,
                    origin,
                    null);
        }

        public Talent(
                long id,
                int row,
                int column,
                List<Long> rankSpells,
                List<Prerequisite> prerequisites,
                String name,
                String description,
                Long iconFileDataId) {
            this(
                    id,
                    row,
                    column,
                    rankSpells,
                    rankSpells.size(),
                    prerequisites,
                    name,
                    description,
                    iconFileDataId,
                    Origin.LEGACY);
        }
    }

    /** A readable talent whose recorded position could not be placed on the tree grid. */
    public record UnplacedTalent(Talent talent, String reason) {}

    public record Tree(
            long id,
            String name,
            int order,
            String background,
            List<Talent> talents,
            List<UnplacedTalent> unplaced) {
        public Tree {
            talents = List.copyOf(talents);
            unplaced = List.copyOf(unplaced);
        }

        public Tree(long id, String name, int order, String background, List<Talent> talents) {
            this(id, name, order, background, talents, List.of());
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
            String message,
            Model model) {
        public ClassTrees {
            trees = List.copyOf(trees);
            issues = List.copyOf(issues);
        }

        public ClassTrees(
                State state,
                PlayerClass playerClass,
                List<Tree> trees,
                List<String> issues,
                String code,
                String message) {
            this(state, playerClass, trees, issues, code, message, null);
        }
    }
}
