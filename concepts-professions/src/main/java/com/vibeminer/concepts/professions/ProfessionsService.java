package com.vibeminer.concepts.professions;

import static com.vibeminer.concepts.professions.ProfessionTable.issue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Forever's reviewed client facts. Closing drains owned work, never the borrowed tables. */
@SuppressWarnings("try")
public final class ProfessionsService implements BuildProfessions {
    private static final java.util.Set<Long> CLASSIC_PROFESSIONS =
            java.util.Set.of(
                    129L, 164L, 165L, 171L, 182L, 185L, 186L, 197L, 202L, 333L, 356L, 393L);
    private final BuildTables tables;
    private final com.vibeminer.concepts.core.RelationIndexes indexes;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("profession-projection").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Void> closing;
    private final Map<String, ProfessionTable> schemas = new HashMap<>();
    private final Map<Long, ProfessionTypes.Ability> summaries = new HashMap<>();
    private List<ProfessionTypes.Profession> professions;
    private List<ProfessionTypes.Category> categories;
    private List<Db2Types.Row> abilityRows;
    private List<String> coreIssues;

    public ProfessionsService(BuildTables tables) {
        this.tables = Objects.requireNonNull(tables);
        indexes = new com.vibeminer.concepts.core.RelationIndexes(tables, 200000);
    }

    @Override
    public CompletableFuture<ProfessionTypes.Catalog> catalog(Cancellation token) {
        return submit(
                token,
                request -> {
                    try {
                        initialize(request);
                        return new ProfessionTypes.Catalog(
                                state(coreIssues),
                                professions,
                                categories,
                                coreIssues,
                                professions.isEmpty()
                                        ? "No profession records are readable."
                                        : null);
                    } catch (ProfessionTable.Projection failure) {
                        return new ProfessionTypes.Catalog(
                                failure.state,
                                List.of(),
                                List.of(),
                                List.of(),
                                failure.getMessage());
                    }
                });
    }

    @Override
    public CompletableFuture<ProfessionTypes.Page> abilities(
            long professionId, String query, long afterId, int limit, Cancellation token) {
        validId(professionId);
        if (query == null || query.length() > 128 || afterId < -1 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid profession query");
        }
        return submit(
                token,
                request -> {
                    try {
                        initialize(request);
                        var issues = new ArrayList<>(coreIssues);
                        var found = new ArrayList<ProfessionTypes.Ability>();
                        String needle = query.toLowerCase(Locale.ROOT);
                        var data = schemas.get("SkillLineAbility");
                        for (var row : abilityRows) {
                            request.check();
                            if (row.id() <= afterId) {
                                continue;
                            }
                            try {
                                if (data.number(row, "SkillLine") != professionId) {
                                    continue;
                                }
                                var ability = summary(row, request, issues);
                                if (!needle.isEmpty()
                                        && !ability.name().toLowerCase(Locale.ROOT).contains(needle)
                                        && !Long.toString(ability.id()).contains(needle)
                                        && !Long.toString(ability.spellId()).contains(needle)) {
                                    continue;
                                }
                                found.add(ability);
                                if (found.size() > limit) {
                                    break;
                                }
                            } catch (ProfessionTable.Projection failure) {
                                issue(issues, failure.getMessage());
                            }
                        }
                        Long next = found.size() > limit ? found.get(limit - 1).id() : null;
                        if (found.size() > limit) {
                            found.removeLast();
                        }
                        return new ProfessionTypes.Page(state(issues), found, next, issues, null);
                    } catch (ProfessionTable.Projection failure) {
                        return new ProfessionTypes.Page(
                                failure.state, List.of(), null, List.of(), failure.getMessage());
                    }
                });
    }

    @Override
    public CompletableFuture<ProfessionTypes.Detail> detail(long abilityId, Cancellation token) {
        validId(abilityId);
        return submit(
                token,
                request -> {
                    try {
                        initialize(request);
                        var issues = new ArrayList<>(coreIssues);
                        var row =
                                abilityRows.stream()
                                        .filter(r -> r.id() == abilityId)
                                        .findFirst()
                                        .orElse(null);
                        if (row == null) {
                            return unavailable("Ability row is not readable.");
                        }
                        var data = schemas.get("SkillLineAbility");
                        long professionId = data.number(row, "SkillLine");
                        if (professions.stream().noneMatch(p -> p.id() == professionId)) {
                            return unavailable("Ability does not belong to a readable profession.");
                        }
                        var ability = summary(row, request, issues);
                        var skillSources = new ArrayList<ProfessionTypes.Source>();
                        Long minimum = fact(data, row, "MinSkillLineRank", skillSources, issues);
                        Long low = fact(data, row, "TrivialSkillLineRankLow", skillSources, issues);
                        Long high =
                                fact(data, row, "TrivialSkillLineRankHigh", skillSources, issues);
                        Long ups = fact(data, row, "NumSkillUps", skillSources, issues);
                        Long acquire = fact(data, row, "AcquireMethod", skillSources, issues);
                        Long classes = fact(data, row, "ClassMask", skillSources, issues);
                        var masks = new ArrayList<ProfessionTypes.RaceMask>();
                        for (int slot = 0; slot < 2; slot++) {
                            Long mask =
                                    fact(
                                            data,
                                            row,
                                            "RaceMasks[" + slot + "]",
                                            skillSources,
                                            issues);
                            if (mask != null) {
                                masks.add(new ProfessionTypes.RaceMask(slot, mask));
                            }
                        }
                        var skill =
                                new ProfessionTypes.SkillFacts(
                                        minimum,
                                        low,
                                        high,
                                        ups,
                                        acquire,
                                        classes,
                                        masks,
                                        skillSources);
                        var reagentIssues = new ArrayList<String>();
                        var reagents = reagents(ability.spellId(), request, reagentIssues);
                        var toolIssues = new ArrayList<String>();
                        var tools = tools(ability.spellId(), request, toolIssues);
                        var resultIssues = new ArrayList<String>();
                        var results = results(ability.spellId(), request, resultIssues);
                        for (String message : reagentIssues) {
                            issue(issues, message);
                        }
                        for (String message : toolIssues) {
                            issue(issues, message);
                        }
                        for (String message : resultIssues) {
                            issue(issues, message);
                        }
                        return new ProfessionTypes.Detail(
                                state(issues),
                                ability,
                                skill,
                                reagents.values(),
                                reagents.state(),
                                tools.values(),
                                tools.state(),
                                results.values(),
                                results.state(),
                                issues,
                                "Acquisition relationships are not established by the client acquisition-method value.");
                    } catch (ProfessionTable.Projection failure) {
                        return new ProfessionTypes.Detail(
                                failure.state,
                                null,
                                null,
                                List.of(),
                                failure.state,
                                List.of(),
                                failure.state,
                                List.of(),
                                failure.state,
                                List.of(),
                                failure.getMessage());
                    }
                });
    }

    private void initialize(Cancellation token) {
        if (professions != null) {
            return;
        }
        var issues = new ArrayList<String>();
        var skills = open("SkillLine", token, issues);
        skills.reference("CategoryID", "SkillLineCategory");
        var skillRows = skills.rows(tables, null, 0, 2048, token);
        var abilities = open("SkillLineAbility", token, issues);
        abilities.reference("SkillLine", "SkillLine");
        abilities.reference("Spell", "Spell");
        var rows =
                abilities.rows(tables, null, 0, 65536, token).stream()
                        .sorted(Comparator.comparingLong(Db2Types.Row::id))
                        .toList();
        var counts = new HashMap<Long, Integer>();
        for (var row : rows) {
            token.check();
            try {
                counts.merge(abilities.number(row, "SkillLine"), 1, Integer::sum);
            } catch (ProfessionTable.Projection failure) {
                issue(issues, failure.getMessage());
            }
        }
        var categoryNames = new HashMap<Long, String>();
        try {
            var cats = open("SkillLineCategory", token, issues);
            for (var row : cats.rows(tables, null, 0, 128, token)) {
                try {
                    categoryNames.put(row.id(), cats.text(row, "Name_lang"));
                } catch (ProfessionTable.Projection failure) {
                    issue(issues, failure.getMessage());
                }
            }
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
        }
        var result = new ArrayList<ProfessionTypes.Profession>();
        for (var row : skillRows) {
            token.check();
            try {
                long category = skills.number(row, "CategoryID");
                // Reviewed Classic SkillCategory enum: 9 secondary, 11 primary professions.
                if (category != 9 && category != 11) {
                    continue;
                }
                result.add(
                        new ProfessionTypes.Profession(
                                row.id(),
                                label(skills.text(row, "DisplayName_lang"), "Profession", row.id()),
                                optionalText(skills, row, "Description_lang", issues),
                                category,
                                categoryNames.get(category),
                                positive(optionalNumber(skills, row, "SpellIconFileID", issues)),
                                zero(optionalNumber(skills, row, "ParentSkillLineID", issues)),
                                counts.getOrDefault(row.id(), 0),
                                CLASSIC_PROFESSIONS.contains(row.id())));
            } catch (ProfessionTable.Projection failure) {
                issue(issues, failure.getMessage());
            }
        }
        result.sort(
                Comparator.comparing(ProfessionTypes.Profession::name)
                        .thenComparingLong(ProfessionTypes.Profession::id));
        var cats = new ArrayList<ProfessionTypes.Category>();
        try {
            var data = open("TradeSkillCategory", token, issues);
            data.reference("SkillLineID", "SkillLine");
            for (var row : data.rows(tables, null, 0, 8192, token)) {
                try {
                    cats.add(
                            new ProfessionTypes.Category(
                                    row.id(),
                                    data.number(row, "SkillLineID"),
                                    data.number(row, "ParentTradeSkillCategoryID"),
                                    data.text(row, "Name_lang"),
                                    Math.toIntExact(data.number(row, "OrderIndex"))));
                } catch (ProfessionTable.Projection | ArithmeticException failure) {
                    issue(issues, "Some recipe categories are unreadable.");
                }
            }
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
        }
        token.check();
        professions = List.copyOf(result);
        categories = List.copyOf(cats);
        abilityRows = rows;
        coreIssues = List.copyOf(issues);
    }

    private ProfessionTypes.Ability summary(
            Db2Types.Row row, Cancellation token, List<String> issues) {
        var cached = summaries.get(row.id());
        if (cached != null) {
            return cached;
        }
        var data = schemas.get("SkillLineAbility");
        long spellId = data.number(row, "Spell");
        validReference(spellId, "Spell");
        String name = null;
        Long icon = null;
        try {
            var names = open("SpellName", token, issues);
            var spell = names.get(tables, spellId, token, issues);
            if (spell != null) {
                name = names.text(spell, "Name_lang");
            }
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
        }
        try {
            var misc = open("SpellMisc", token, issues);
            misc.reference("SpellID", "Spell");
            var rows =
                    related(misc, "SpellID", spellId, 32, token).stream()
                            .filter(r -> misc.number(r, "DifficultyID") == 0)
                            .toList();
            if (rows.size() == 1) {
                icon = positive(misc.number(rows.getFirst(), "SpellIconFileDataID"));
            } else if (rows.size() > 1) {
                issue(issues, "Multiple base-difficulty spell icon records.");
            }
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
        }
        var result =
                new ProfessionTypes.Ability(
                        row.id(),
                        data.number(row, "SkillLine"),
                        spellId,
                        label(name, "Spell", spellId),
                        icon,
                        optionalNumber(data, row, "MinSkillLineRank", issues),
                        positive(optionalNumber(data, row, "TradeSkillCategoryID", issues)));
        // Cache resolved labels only: missing optional facts may become available later.
        if (name != null && !name.isBlank() && icon != null && issues.isEmpty()) {
            summaries.put(row.id(), result);
        }
        return result;
    }

    private record Facts<T>(List<T> values, ProfessionTypes.State state) {}

    private Facts<ProfessionTypes.Ingredient> reagents(
            long spell, Cancellation token, List<String> issues) {
        var result = new ArrayList<ProfessionTypes.Ingredient>();
        try {
            var data = open("SpellReagents", token, issues);
            data.reference("SpellID", "Spell");
            data.reference("Reagent[0]", "Item");
            var rows = related(data, "SpellID", spell, 8, token);
            if (rows.size() > 1) {
                issue(issues, "Multiple reagent records; quantities are ambiguous.");
                return new Facts<>(List.of(), ProfessionTypes.State.PARTIAL);
            }
            for (var row : rows) {
                for (int slot = 0; slot < 8; slot++) {
                    try {
                        String item = "Reagent[" + slot + "]", count = "ReagentCount[" + slot + "]";
                        long id = data.number(row, item);
                        if (id == 0 || id == -1) {
                            continue;
                        }
                        validReference(id, "Reagent item");
                        Long quantity = optionalNumber(data, row, count, issues);
                        if (quantity != null && quantity <= 0) {
                            quantity = null;
                            issue(issues, "Reagent quantity is not resolved.");
                        }
                        var sources = new ArrayList<ProfessionTypes.Source>();
                        sources.add(data.source(row, item));
                        if (quantity != null) {
                            sources.add(data.source(row, count));
                        }
                        result.add(new ProfessionTypes.Ingredient(slot, id, quantity, sources));
                    } catch (ProfessionTable.Projection failure) {
                        issue(issues, failure.getMessage());
                    }
                }
            }
            return new Facts<>(result, state(issues));
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
            return new Facts<>(result, failure.state);
        }
    }

    private Facts<ProfessionTypes.Tool> tools(long spell, Cancellation token, List<String> issues) {
        var result = new ArrayList<ProfessionTypes.Tool>();
        try {
            var data = open("SpellTotems", token, issues);
            data.reference("SpellID", "Spell");
            var rows = related(data, "SpellID", spell, 8, token);
            if (rows.size() > 1) {
                issue(issues, "Multiple tool records; requirements are ambiguous.");
                return new Facts<>(List.of(), ProfessionTypes.State.PARTIAL);
            }
            for (var row : rows) {
                for (int slot = 0; slot < 2; slot++) {
                    var sources = new ArrayList<ProfessionTypes.Source>();
                    Long item = positive(fact(data, row, "Totem[" + slot + "]", sources, issues));
                    Long category =
                            positive(
                                    fact(
                                            data,
                                            row,
                                            "RequiredTotemCategoryID[" + slot + "]",
                                            sources,
                                            issues));
                    if (item != null || category != null) {
                        result.add(new ProfessionTypes.Tool(slot, item, category, sources));
                    }
                }
            }
            return new Facts<>(result, state(issues));
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
            return new Facts<>(result, failure.state);
        }
    }

    private Facts<ProfessionTypes.Result> results(
            long spell, Cancellation token, List<String> issues) {
        var result = new ArrayList<ProfessionTypes.Result>();
        try {
            var data = open("SpellEffect", token, issues);
            data.reference("SpellID", "Spell");
            var seen = new java.util.HashSet<Long>();
            for (var row : related(data, "SpellID", spell, 128, token)) {
                try {
                    if (data.number(row, "DifficultyID") != 0) {
                        continue;
                    }
                    long index = data.number(row, "EffectIndex");
                    if (!seen.add(index)) {
                        issue(
                                issues,
                                "Multiple effect records for one slot; results are ambiguous.");
                        return new Facts<>(List.of(), ProfessionTypes.State.PARTIAL);
                    }
                    long effect = data.number(row, "Effect");
                    var sources = new ArrayList<ProfessionTypes.Source>();
                    for (String field :
                            List.of("Effect", "EffectIndex", "SpellID", "DifficultyID")) {
                        sources.add(data.source(row, field));
                    }
                    ProfessionTypes.ResultKind kind;
                    Long item = null, enchantment = null;
                    if (effect == 24) {
                        kind = ProfessionTypes.ResultKind.ITEM;
                        item = positive(fact(data, row, "EffectItemType", sources, issues));
                        if (item == null) {
                            kind = ProfessionTypes.ResultKind.UNRESOLVED;
                            issue(issues, "Created item reference is unavailable.");
                        }
                    } else if (effect == 53 || effect == 54 || effect == 92) {
                        kind = ProfessionTypes.ResultKind.ENCHANTMENT;
                        enchantment =
                                positive(fact(data, row, "EffectMiscValue[0]", sources, issues));
                    } else if (effect == 59 || effect == 77 || effect == 157) {
                        kind = ProfessionTypes.ResultKind.RANDOM_OR_SCRIPTED;
                    } else {
                        continue;
                    }
                    java.math.BigDecimal base = null;
                    try {
                        base = data.decimal(row, "EffectBasePointsF");
                        sources.add(data.source(row, "EffectBasePointsF"));
                    } catch (ProfessionTable.Projection failure) {
                        issue(issues, failure.getMessage());
                    }
                    result.add(
                            new ProfessionTypes.Result(
                                    kind,
                                    item,
                                    enchantment,
                                    null,
                                    base,
                                    sources,
                                    "Output quantity is not resolved; the recorded base amount is not a guaranteed yield."));
                } catch (ProfessionTable.Projection failure) {
                    issue(issues, failure.getMessage());
                }
            }
            if (result.isEmpty()) {
                result.add(
                        new ProfessionTypes.Result(
                                ProfessionTypes.ResultKind.UNRESOLVED,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                "No supported direct result in the readable spell effects."));
            }
            return new Facts<>(result, state(issues));
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
            return new Facts<>(result, failure.state);
        }
    }

    private List<Db2Types.Row> related(
            ProfessionTable data, String column, long id, int bound, Cancellation token) {
        var lookup =
                indexes.lookup(
                                data.name,
                                column,
                                id,
                                bound + 1,
                                com.vibeminer.formats.tables.TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (lookup.state() != com.vibeminer.concepts.core.RelationIndexes.State.OK) {
            throw new ProfessionTable.Projection(
                    ProfessionTypes.State.UNAVAILABLE, data.name + ": " + lookup.state());
        }
        if (lookup.matches() > bound || lookup.rows().size() > bound) {
            throw new ProfessionTable.Projection(
                    ProfessionTypes.State.UNSUPPORTED, data.name + " exceeds related-row bounds");
        }
        return lookup.rows().stream()
                .map(com.vibeminer.formats.tables.TableTypes.Row::row)
                .toList();
    }

    private ProfessionTable open(String name, Cancellation token, List<String> issues) {
        var cached = schemas.get(name);
        if (cached != null) {
            if (cached.entry.state() == com.vibeminer.formats.tables.TableTypes.State.PARTIAL) {
                issue(issues, name + " is partial; readable rows are retained.");
            }
            return cached;
        }
        var result = ProfessionTable.open(tables, name, token, issues);
        schemas.put(name, result);
        return result;
    }

    private static Long fact(
            ProfessionTable data,
            Db2Types.Row row,
            String field,
            List<ProfessionTypes.Source> sources,
            List<String> issues) {
        Long value = optionalNumber(data, row, field, issues);
        if (value != null) {
            sources.add(data.source(row, field));
        }
        return value;
    }

    private static Long optionalNumber(
            ProfessionTable data, Db2Types.Row row, String field, List<String> issues) {
        try {
            return data.number(row, field);
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
            return null;
        }
    }

    private static String optionalText(
            ProfessionTable data, Db2Types.Row row, String field, List<String> issues) {
        try {
            return data.text(row, field);
        } catch (ProfessionTable.Projection failure) {
            issue(issues, failure.getMessage());
            return null;
        }
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    private static Long positive(Long value) {
        return value == null || value <= 0 || value > 0xffffffffL ? null : value;
    }

    private static String label(String name, String kind, long id) {
        return name == null || name.isBlank() ? kind + " #" + id : name;
    }

    private static ProfessionTypes.State state(List<String> issues) {
        return issues.isEmpty() ? ProfessionTypes.State.READY : ProfessionTypes.State.PARTIAL;
    }

    private static void validId(long id) {
        if (id < 1 || id > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid ID");
        }
    }

    private static void validReference(long id, String field) {
        if (id < 1 || id > 0xffffffffL) {
            throw new ProfessionTable.Projection(
                    ProfessionTypes.State.UNSUPPORTED, "Invalid " + field + " reference");
        }
    }

    private static ProfessionTypes.Detail unavailable(String message) {
        return new ProfessionTypes.Detail(
                ProfessionTypes.State.UNAVAILABLE,
                null,
                null,
                List.of(),
                ProfessionTypes.State.UNAVAILABLE,
                List.of(),
                ProfessionTypes.State.UNAVAILABLE,
                List.of(),
                ProfessionTypes.State.UNAVAILABLE,
                List.of(),
                message);
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var inner = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Profession handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var registration = lifetime.onCancel(request::cancel)) {
                                request.check();
                                inner.complete(action.apply(request));
                            } catch (Throwable failure) {
                                inner.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                inner.completeExceptionally(
                        new IllegalStateException("Profession queries busy", failure));
            }
        }
        var outer = new CompletableFuture<T>();
        inner.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        outer.complete(value);
                                    } else {
                                        outer.completeExceptionally(failure);
                                    }
                                }));
        return outer;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable failure = null;
        try {
            lifetime.cancel();
        } catch (Throwable error) {
            failure = error;
        }
        var cancellationFailure = failure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        if (cancellationFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancellationFailure);
                        }
                    } catch (Throwable error) {
                        closing.completeExceptionally(error);
                    }
                });
        return closing.copy();
    }
}
