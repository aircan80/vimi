package com.vibeminer.concepts.professions;

import java.math.BigDecimal;
import java.util.List;

/** Build-local recipe facts, not character skill, eligibility or acquisition guarantees. */
public final class ProfessionTypes {
    private ProfessionTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public enum ResultKind {
        ITEM,
        ENCHANTMENT,
        RANDOM_OR_SCRIPTED,
        OTHER,
        UNRESOLVED
    }

    public record Source(String table, long rowId, String column, String value) {}

    public record Profession(
            long id,
            String name,
            String description,
            long categoryId,
            String categoryName,
            Long iconFileDataId,
            long parentSkillLineId,
            int abilityCount,
            boolean classicProfession) {}

    public record Category(long id, long skillLineId, long parentId, String name, int order) {}

    public record Ability(
            long id,
            long professionId,
            long spellId,
            String name,
            Long iconFileDataId,
            Long minimumRank,
            Long categoryId) {}

    public record Ingredient(int slot, long itemId, Long count, List<Source> sources) {
        public Ingredient {
            sources = List.copyOf(sources);
        }
    }

    public record Tool(int slot, Long itemId, Long totemCategoryId, List<Source> sources) {
        public Tool {
            sources = List.copyOf(sources);
        }
    }

    public record Result(
            ResultKind kind,
            Long itemId,
            Long enchantmentId,
            Long quantity,
            BigDecimal recordedBaseAmount,
            List<Source> sources,
            String message) {
        public Result {
            sources = List.copyOf(sources);
        }
    }

    public record RaceMask(int slot, long value) {}

    public record SkillFacts(
            Long minimumRank,
            Long trivialLow,
            Long trivialHigh,
            Long numSkillUps,
            Long acquireMethod,
            Long classMask,
            List<RaceMask> raceMasks,
            List<Source> sources) {
        public SkillFacts {
            raceMasks = List.copyOf(raceMasks);
            sources = List.copyOf(sources);
        }
    }

    public record Catalog(
            State state,
            List<Profession> professions,
            List<Category> categories,
            List<String> issues,
            String message) {
        public Catalog {
            professions = List.copyOf(professions);
            categories = List.copyOf(categories);
            issues = List.copyOf(issues);
        }
    }

    public record Page(
            State state,
            List<Ability> abilities,
            Long nextAfter,
            List<String> issues,
            String message) {
        public Page {
            abilities = List.copyOf(abilities);
            issues = List.copyOf(issues);
        }
    }

    public record Detail(
            State state,
            Ability ability,
            SkillFacts skill,
            List<Ingredient> reagents,
            State reagentState,
            List<Tool> tools,
            State toolState,
            List<Result> results,
            State resultState,
            List<String> issues,
            String message) {
        public Detail {
            reagents = List.copyOf(reagents);
            tools = List.copyOf(tools);
            results = List.copyOf(results);
            issues = List.copyOf(issues);
        }
    }
}
