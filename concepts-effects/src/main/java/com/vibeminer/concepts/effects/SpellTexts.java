package com.vibeminer.concepts.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Lazy, bounded spell-text previews over borrowed immutable build/locale tables. */
public final class SpellTexts implements AutoCloseable {
    public enum State {
        READY,
        RESOLVED,
        UNRESOLVED,
        UNAVAILABLE
    }

    public record Source(String table, long rowId, String column, String value) {}

    public record Substitution(
            String token, Integer effectIndex, String value, List<Source> sources, String issue) {
        public Substitution {
            sources = List.copyOf(sources);
        }
    }

    public record Description(
            long spellId,
            String raw,
            String display,
            State state,
            List<Substitution> substitutions,
            List<String> issues) {
        public Description {
            substitutions = List.copyOf(substitutions);
            issues = List.copyOf(issues);
        }
    }

    private static final List<String> CONTEXT_FLOATS =
            List.of(
                    "Variance",
                    "EffectRealPointsPerLevel",
                    "EffectPointsPerResource",
                    "BonusCoefficientFromAP",
                    "Coefficient",
                    "ResourceCoefficient");
    private static final List<String> NEUTRAL_FLOATS =
            List.of("EffectBonusCoefficient", "GroupSizeBasePointsCoefficient", "PvpMultiplier");
    private final BuildTables tables;
    private final String version;

    /** Default reverse-index budget for each relation table. */
    static final int RELATION_ENTRIES = 1_000_000;

    // One budget per relation: a large SpellEffect index must not leave SpellMisc durations
    // permanently over a shared budget.
    private final RelationIndexes effectIndex, miscIndex, auraOptionsIndex;
    private final Cancellation lifetime = new Cancellation();
    private final Object gate = new Object();
    private final java.util.Set<CompletableFuture<Description>> active = new HashSet<>();
    private final Map<Long, Description> cache = new LinkedHashMap<>();
    private CompletableFuture<Void> closing;
    private Throwable closeFailure;
    private boolean cancellationComplete;

    public SpellTexts(BuildTables tables, String version) {
        this(tables, version, RELATION_ENTRIES);
    }

    SpellTexts(BuildTables tables, String version, int relationEntries) {
        this.tables = Objects.requireNonNull(tables);
        this.version = version;
        this.effectIndex = new RelationIndexes(tables, relationEntries);
        this.miscIndex = new RelationIndexes(tables, relationEntries);
        this.auraOptionsIndex = new RelationIndexes(tables, relationEntries);
    }

    /**
     * Forgets cached previews so the next request resolves again, for example after a transient
     * failure of a referenced spell's rows. Requests in progress are not affected.
     */
    public void forgetCached() {
        synchronized (gate) {
            cache.clear();
        }
    }

    public BuildRef build() {
        return tables.build();
    }

    public String locale() {
        return tables.locale();
    }

    /**
     * A recorded replacement for one of the described spell's effect amounts, for example a talent
     * rank's TraitDefinitionEffectPoints value. Other scaling inputs of the effect still apply.
     */
    public record EffectOverride(
            int effectIndex, BigDecimal value, String issue, List<Source> sources) {
        public EffectOverride {
            if (effectIndex < 0 || effectIndex >= EFFECT_SLOTS) {
                throw new IllegalArgumentException("Unsupported effect index");
            }
            if ((value == null) == (issue == null)) {
                throw new IllegalArgumentException("An override has a value or an issue");
            }
            sources = List.copyOf(sources);
        }
    }

    /** Effect slots that $s1-$s5 can name. */
    public static final int EFFECT_SLOTS = SpellText.EFFECT_SLOTS;

    /**
     * At most four accepted previews; callers must keep borrowed tables alive until close drains.
     */
    public CompletableFuture<Description> description(
            long spellId, TableTypes.Priority priority, Cancellation token) {
        return description(spellId, List.of(), priority, token);
    }

    /**
     * A preview whose listed effect amounts are replaced. Such previews are not cached, so a rank's
     * values never stand in for another rank's.
     */
    @SuppressWarnings("try")
    public CompletableFuture<Description> description(
            long spellId,
            List<EffectOverride> overrides,
            TableTypes.Priority priority,
            Cancellation token) {
        if (spellId < 1 || spellId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid spell ID");
        }
        var replaced = List.copyOf(overrides);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(token);
        token.check();
        var answer = new CompletableFuture<Description>();
        synchronized (gate) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Spell text handle closed"));
            }
            var cached = replaced.isEmpty() ? cache.get(spellId) : null;
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            if (active.size() == 4) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("SPELL_TEXT_OPERATION_LIMIT"));
            }
            active.add(answer);
        }
        EffectsTasks.run(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var own = lifetime.onCancel(request::cancel)) {
                                request.check();
                                return resolve(spellId, replaced, priority, request);
                            }
                        })
                .whenComplete(
                        (value, error) -> {
                            synchronized (gate) {
                                active.remove(answer);
                                if (error == null
                                        && replaced.isEmpty()
                                        && value.state() != State.UNAVAILABLE
                                        && closing == null) {
                                    if (cache.size() == 128) {
                                        cache.remove(cache.keySet().iterator().next());
                                    }
                                    cache.put(spellId, value);
                                }
                                finishClose();
                            }
                            if (error == null) {
                                answer.complete(value);
                            } else {
                                answer.completeExceptionally(EffectsTasks.unwrap(error));
                            }
                        });
        return answer.copy();
    }

    private Description resolve(
            long spell,
            List<EffectOverride> overrides,
            TableTypes.Priority priority,
            Cancellation token) {
        var issues = new ArrayList<String>();
        var schema = tables.schema(TableTypes.Ref.forName("Spell"), priority, token).join();
        var columns = new Columns(schema);
        int text = columns.field("Description_lang", true);
        if (!TableQueries.isReadable(schema.table().state()) || text < 0) {
            return unavailable(
                    spell, "Spell description schema is unavailable: " + schema.table().state());
        }
        var result = tables.get(TableTypes.Ref.forName("Spell"), spell, priority, token).join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND || result.row() == null) {
            return unavailable(spell, "Spell description record is " + result.outcome());
        }
        var row = result.row().row();
        if (row.id() != spell) {
            return unavailable(spell, "Spell description ID does not match");
        }
        String raw = columns.text(row, text);
        if (raw == null) {
            return unavailable(spell, "Spell description text is unreadable");
        }
        var none = new SpellText.Values(Map.of(), Map.of());
        var needs = SpellText.needs(spell, raw);
        if (!needs.any()) {
            return SpellText.project(spell, raw, none, issues);
        }
        // The direct-float profile is reviewed for modern Forever, not legacy integer Classic data.
        if (version == null
                || !version.matches("1\\.60\\.[0-9]+(?:\\.[0-9]+)?")
                || !build().product().equals("wowf")) {
            return SpellText.project(
                    spell,
                    raw,
                    none,
                    List.of("Spell amount profile is unsupported for this build."));
        }
        var amounts = new LinkedHashMap<Long, Map<Integer, SpellText.Amount>>();
        for (long source : needs.amounts()) {
            var found = amounts(source, needs.radii().contains(source), priority, token, issues);
            amounts.put(source, source == spell ? override(found, overrides) : found);
        }
        var durations = new LinkedHashMap<Long, SpellText.Value>();
        for (long source : needs.durations()) {
            durations.put(source, duration(source, priority, token, issues));
        }
        var procs = new LinkedHashMap<Long, SpellText.Value>();
        var charges = new LinkedHashMap<Long, SpellText.Value>();
        var stacks = new LinkedHashMap<Long, SpellText.Value>();
        for (long source : needs.procChances()) {
            var options = auraOptions(source, priority, token, issues);
            procs.put(source, options.chance());
            charges.put(source, options.charges());
            stacks.put(source, options.stacks());
        }
        return SpellText.project(
                spell,
                raw,
                new SpellText.Values(amounts, durations, procs, charges, stacks),
                List.copyOf(new java.util.LinkedHashSet<>(issues)));
    }

    private Map<Integer, SpellText.Amount> amounts(
            long spell,
            boolean radii,
            TableTypes.Priority priority,
            Cancellation token,
            List<String> issues) {
        var effectSchema =
                tables.schema(TableTypes.Ref.forName("SpellEffect"), priority, token).join();
        var effects = new Columns(effectSchema);
        if (!TableQueries.isReadable(effectSchema.table().state()) || !effects.modernProfile()) {
            issues.add("SpellEffect profile is unavailable: " + effectSchema.table().state());
            return Map.of();
        }
        var lookup =
                effectIndex.lookup("SpellEffect", "SpellID", spell, 64, priority, token).join();
        if (lookup.state() != RelationIndexes.State.OK || lookup.matches() > 64) {
            issues.add("SpellEffect lookup is " + lookup.state());
            return Map.of();
        }
        if (!lookup.complete()) {
            issues.add("SpellEffect is PARTIAL; values use readable records only.");
        }
        var amounts = new HashMap<Integer, SpellText.Amount>();
        var ambiguous = new HashSet<Integer>();
        for (var found : lookup.rows()) {
            token.check();
            var effect = found.row();
            var difficulty = effects.number(effect, "DifficultyID");
            var index = effects.number(effect, "EffectIndex");
            if (difficulty == null
                    || index == null
                    || difficulty.signum() != 0
                    || index.scale() > 0
                    || index.signum() < 0
                    || index.compareTo(BigDecimal.valueOf(SpellText.EFFECT_SLOTS - 1)) > 0) {
                continue;
            }
            int slot = index.intValueExact();
            var sources = new ArrayList<Source>();
            for (String field :
                    List.of(
                            "SpellID",
                            "DifficultyID",
                            "EffectIndex",
                            "EffectBasePointsF",
                            "Effect",
                            "EffectAura")) {
                sources.add(
                        new Source(
                                "SpellEffect", effect.id(), field, effects.value(effect, field)));
            }
            sources.add(
                    new Source(
                            "SpellEffect",
                            effect.id(),
                            "column[24] (unverified name)",
                            effects.text(effect, 24)));
            var kind = effects.number(effect, "Effect");
            var aura = effects.number(effect, "EffectAura");
            // Reviewed against Forever 1.60.1.70790 talent spells: an amount is fixed when no
            // random, level, resource, power or scaling input can change it. A spell power
            // coefficient only matters for damage, healing and absorb effects; elsewhere the
            // recorded default of 0 or 1 is inert.
            boolean fixed = effects.rawGuardZero(effect);
            for (String field : CONTEXT_FLOATS) {
                sources.add(
                        new Source(
                                "SpellEffect", effect.id(), field, effects.value(effect, field)));
                fixed &= effects.zero(effect, field);
            }
            for (String field : NEUTRAL_FLOATS) {
                sources.add(
                        new Source(
                                "SpellEffect", effect.id(), field, effects.value(effect, field)));
                var value = effects.number(effect, field);
                if (field.equals("EffectBonusCoefficient")) {
                    // Zero adds no spell power. The recorded default of one is inert only for
                    // aura types reviewed as unable to scale with spell power.
                    fixed &=
                            value != null
                                    && (value.signum() == 0
                                            || inertCoefficient(kind, aura)
                                                    && value.compareTo(BigDecimal.ONE) == 0);
                } else {
                    fixed &= value != null && value.compareTo(BigDecimal.ONE) == 0;
                }
            }
            for (String field : List.of("EffectAttributes", "EffectAuraPeriod")) {
                sources.add(
                        new Source(
                                "SpellEffect", effect.id(), field, effects.value(effect, field)));
            }
            fixed &= effects.zero(effect, "EffectAttributes");
            var period = effects.number(effect, "EffectAuraPeriod");
            Long periodMilliseconds =
                    period != null
                                    && period.scale() <= 0
                                    && period.signum() > 0
                                    && period.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                            ? period.longValueExact()
                            : null;
            var base = effects.number(effect, "EffectBasePointsF");
            var amount =
                    new SpellText.Amount(
                            base,
                            sources,
                            base == null
                                    ? "Effect base amount is unreadable"
                                    : fixed
                                            ? null
                                            : "Effect requires random, scaling or character context",
                            false,
                            periodMilliseconds,
                            radii ? radius(effects, effect, priority, token) : null);
            if (amounts.putIfAbsent(slot, amount) != null) {
                ambiguous.add(slot);
            }
        }
        for (int index : ambiguous) {
            amounts.put(
                    index,
                    new SpellText.Amount(
                            null, List.of(), "Multiple readable effects match this effect index"));
        }
        return amounts;
    }

    /**
     * Replaces the base of overridden effects. The effect's own scaling issue, if any, still
     * applies; an override for an effect without a readable row stands alone.
     */
    private static Map<Integer, SpellText.Amount> override(
            Map<Integer, SpellText.Amount> found, List<EffectOverride> overrides) {
        if (overrides.isEmpty()) {
            return found;
        }
        var result = new HashMap<>(found);
        for (var replacement : overrides) {
            var original = found.get(replacement.effectIndex());
            var sources = new ArrayList<>(replacement.sources());
            String issue = replacement.issue();
            Long period = null;
            SpellText.Value radius = null;
            if (original != null) {
                // The radius does not depend on the rank value.
                radius = original.radius();
            }
            if (original != null && replacement.value() != null) {
                sources.addAll(original.sources());
                period = original.periodMilliseconds();
                // An unreadable base is what the override supplies; other causes still hold.
                issue =
                        "Effect base amount is unreadable".equals(original.issue())
                                ? null
                                : original.issue();
            }
            result.put(
                    replacement.effectIndex(),
                    new SpellText.Amount(
                            replacement.value(), sources, issue, false, period, radius));
        }
        return result;
    }

    /**
     * APPLY_AURA (6) aura types whose amount is a stat, chance, rate, speed or spell modifier and
     * never gains spell power, so a recorded EffectBonusCoefficient of one is inert: threat (10),
     * stat (29), speed (31/33), parry/dodge/crit/hit chances (47, 49, 52, 54, 55, 57), cast speed
     * (65), damage done percent (79), power regeneration (85, 110), attack power (99), flat and
     * percent spell modifiers (107, 108), healing done percent (136), total stat percent (137),
     * melee haste (138), mechanic duration (232) and crit percent (290). Every other effect or aura
     * counts as fixed only with a coefficient of zero.
     */
    static final java.util.Set<Integer> INERT_COEFFICIENT_AURAS =
            java.util.Set.of(
                    10, 29, 31, 33, 47, 49, 52, 54, 55, 57, 65, 79, 85, 99, 107, 108, 110, 136, 137,
                    138, 232, 290);

    private static boolean inertCoefficient(BigDecimal kind, BigDecimal aura) {
        return kind != null
                && aura != null
                && kind.compareTo(BigDecimal.valueOf(6)) == 0
                && aura.scale() <= 0
                && aura.signum() >= 0
                && aura.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                && INERT_COEFFICIENT_AURAS.contains(aura.intValue());
    }

    /** {@code $h}, {@code $n} and {@code $u} from one SpellAuraOptions row. */
    private record AuraOptions(
            SpellText.Value chance, SpellText.Value charges, SpellText.Value stacks) {}

    /**
     * The spell's single DifficultyID 0 SpellAuraOptions row (layout 95F4D5E1, verified field
     * names): ProcChance for {@code $h} (1-100, not procs per minute), ProcCharges for {@code $n}
     * and CumulativeAura for {@code $u} (each 1-1000; a field missing from the layout stays
     * unknown). When the lookup is partial, a second DifficultyID 0 row could be hidden, so {@code
     * $n} and {@code $u} are refused; {@code $h} keeps its readable-records rule.
     */
    private AuraOptions auraOptions(
            long spell, TableTypes.Priority priority, Cancellation token, List<String> issues) {
        var schema =
                tables.schema(TableTypes.Ref.forName("SpellAuraOptions"), priority, token).join();
        var options = new Columns(schema);
        if (!TableQueries.isReadable(schema.table().state()) || !options.auraOptionsProfile()) {
            return failed("SpellAuraOptions profile is unavailable: " + schema.table().state());
        }
        var lookup =
                auraOptionsIndex
                        .lookup("SpellAuraOptions", "SpellID", spell, 64, priority, token)
                        .join();
        if (lookup.state() != RelationIndexes.State.OK || lookup.matches() > 64) {
            return failed("SpellAuraOptions lookup is " + lookup.state());
        }
        if (!lookup.complete()) {
            issues.add("SpellAuraOptions is PARTIAL; proc chance uses readable records only.");
        }
        Db2Types.Row selected = null;
        for (var found : lookup.rows()) {
            var row = found.row();
            var difficulty = options.number(row, "DifficultyID");
            if (difficulty == null || difficulty.signum() != 0) {
                continue;
            }
            if (selected != null) {
                return failed("Multiple readable SpellAuraOptions rows match");
            }
            selected = row;
        }
        if (selected == null) {
            return failed("SpellAuraOptions records no DifficultyID 0 row for this spell");
        }
        var identity = new ArrayList<Source>();
        for (String field : List.of("SpellID", "DifficultyID")) {
            identity.add(
                    new Source(
                            "SpellAuraOptions",
                            selected.id(),
                            field,
                            options.value(selected, field)));
        }
        String partial =
                lookup.complete()
                        ? null
                        : "SpellAuraOptions is PARTIAL; another DifficultyID 0 row may be hidden";
        return new AuraOptions(
                chance(options, selected, identity),
                count(options, selected, identity, "ProcCharges", "proc charges", partial),
                count(options, selected, identity, "CumulativeAura", "maximum stacks", partial));
    }

    private static AuraOptions failed(String issue) {
        var value = new SpellText.Value(null, List.of(), issue);
        return new AuraOptions(value, value, value);
    }

    private static SpellText.Value chance(
            Columns options, Db2Types.Row selected, List<Source> identity) {
        var sources = new ArrayList<>(identity);
        for (String field : List.of("ProcChance", "SpellProcsPerMinuteID")) {
            sources.add(
                    new Source(
                            "SpellAuraOptions",
                            selected.id(),
                            field,
                            options.value(selected, field)));
        }
        var chance = options.number(selected, "ProcChance");
        var perMinute = options.number(selected, "SpellProcsPerMinuteID");
        if (perMinute == null || perMinute.signum() != 0) {
            return new SpellText.Value(
                    null, sources, "The proc rate is per minute and depends on weapon speed");
        }
        if (chance == null
                || chance.scale() > 0
                || chance.signum() <= 0
                || chance.compareTo(BigDecimal.valueOf(100)) > 0) {
            return new SpellText.Value(null, sources, "Recorded proc chance is not 1-100");
        }
        return new SpellText.Value(chance.toPlainString(), sources, null);
    }

    /** A 1-1000 count from the selected row, or unknown with its own reason. */
    private static SpellText.Value count(
            Columns options,
            Db2Types.Row selected,
            List<Source> identity,
            String field,
            String what,
            String partial) {
        var sources = new ArrayList<>(identity);
        if (options.field(field, false) < 0) {
            return new SpellText.Value(
                    null, sources, "SpellAuraOptions has no verified " + field + " field");
        }
        sources.add(
                new Source(
                        "SpellAuraOptions", selected.id(), field, options.value(selected, field)));
        if (partial != null) {
            return new SpellText.Value(null, sources, partial);
        }
        var value = options.number(selected, field);
        if (value == null
                || value.scale() > 0
                || value.signum() <= 0
                || value.compareTo(BigDecimal.valueOf(1000)) > 0) {
            return new SpellText.Value(
                    null, sources, "SpellAuraOptions records no " + what + " (1-1000)");
        }
        return new SpellText.Value(value.toPlainString(), sources, null);
    }

    /**
     * {@code $a}: the effect's EffectRadiusIndex[0] SpellRadius row (layout F9A913EE, verified
     * names). Only a single fixed radius is shown: no second radius index, no per-level growth, and
     * min/max either unset or equal to the radius.
     */
    private SpellText.Value radius(
            Columns effects,
            Db2Types.Row effect,
            TableTypes.Priority priority,
            Cancellation token) {
        var sources = new ArrayList<Source>();
        Long first = effects.element(effect, "EffectRadiusIndex", 2, 0);
        Long second = effects.element(effect, "EffectRadiusIndex", 2, 1);
        if (first == null || second == null) {
            return new SpellText.Value(null, sources, "Effect radius index is unreadable");
        }
        sources.add(
                new Source("SpellEffect", effect.id(), "EffectRadiusIndex[0]", first.toString()));
        sources.add(
                new Source("SpellEffect", effect.id(), "EffectRadiusIndex[1]", second.toString()));
        if (first == 0) {
            return new SpellText.Value(null, sources, "Effect records no radius");
        }
        // Both radius indexes usually name the same row; a different second radius is not shown.
        if (second != 0 && !second.equals(first)) {
            return new SpellText.Value(null, sources, "Effect records a second, different radius");
        }
        var ref = TableTypes.Ref.forName("SpellRadius");
        var schema = tables.schema(ref, priority, token).join();
        var columns = new Columns(schema);
        if (!TableQueries.isReadable(schema.table().state()) || !columns.radiusProfile()) {
            return new SpellText.Value(
                    null, sources, "SpellRadius profile is unavailable: " + schema.table().state());
        }
        var result = tables.get(ref, first, priority, token).join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND
                || result.row() == null
                || result.row().row().id() != first) {
            return new SpellText.Value(null, sources, "SpellRadius record is unavailable");
        }
        var row = result.row().row();
        for (String field : List.of("Radius", "RadiusPerLevel", "RadiusMin", "RadiusMax")) {
            sources.add(new Source("SpellRadius", row.id(), field, columns.value(row, field)));
        }
        var radius = columns.number(row, "Radius");
        var min = columns.number(row, "RadiusMin");
        var max = columns.number(row, "RadiusMax");
        if (radius == null
                || radius.signum() <= 0
                || !columns.zero(row, "RadiusPerLevel")
                || min == null
                || max == null
                || min.signum() != 0 && min.compareTo(radius) != 0
                || max.signum() != 0 && max.compareTo(radius) != 0) {
            return new SpellText.Value(null, sources, "Radius depends on level or has a range");
        }
        var shown = radius.stripTrailingZeros();
        if (shown.scale() > 1 || radius.compareTo(BigDecimal.valueOf(1000)) > 0) {
            return new SpellText.Value(null, sources, "Radius is not a whole or tenth yard");
        }
        return new SpellText.Value(
                (shown.scale() < 0 ? shown.setScale(0) : shown).toPlainString(), sources, null);
    }

    private SpellText.Value duration(
            long spell, TableTypes.Priority priority, Cancellation token, List<String> issues) {
        var sources = new ArrayList<Source>();
        var miscSchema = tables.schema(TableTypes.Ref.forName("SpellMisc"), priority, token).join();
        var misc = new Columns(miscSchema);
        if (!TableQueries.isReadable(miscSchema.table().state()) || !misc.miscProfile()) {
            return new SpellText.Value(null, sources, "SpellMisc duration profile is unavailable");
        }
        var lookup = miscIndex.lookup("SpellMisc", "SpellID", spell, 64, priority, token).join();
        if (lookup.state() != RelationIndexes.State.OK || lookup.matches() > 64) {
            return new SpellText.Value(null, sources, "SpellMisc duration lookup is unavailable");
        }
        if (!lookup.complete()) {
            issues.add("SpellMisc is PARTIAL; duration uses readable records only.");
        }
        Db2Types.Row selected = null;
        for (var found : lookup.rows()) {
            token.check();
            var row = found.row();
            var difficulty = misc.number(row, "DifficultyID");
            if (difficulty == null || difficulty.signum() != 0) {
                continue;
            }
            if (selected != null) {
                return new SpellText.Value(
                        null, sources, "Multiple readable SpellMisc durations match");
            }
            selected = row;
        }
        if (selected == null) {
            return new SpellText.Value(null, sources, "SpellMisc duration is unavailable");
        }
        for (String field : List.of("SpellID", "DifficultyID", "DurationIndex", "MinDuration")) {
            sources.add(new Source("SpellMisc", selected.id(), field, misc.value(selected, field)));
        }
        sources.add(
                new Source(
                        "SpellMisc",
                        selected.id(),
                        "column[21] (unverified name)",
                        misc.text(selected, 21)));
        var id = misc.number(selected, "DurationIndex");
        if (id == null
                || id.scale() > 0
                || id.signum() <= 0
                || id.compareTo(BigDecimal.valueOf(65535)) > 0
                || !misc.rawZero(selected, 21)
                || !misc.zero(selected, "MinDuration")) {
            return new SpellText.Value(
                    null, sources, "Duration requires unsupported context or a valid duration ID");
        }
        // SPELL_ATTR5_EXTRA_INITIAL_PERIOD (Attributes[5] bit 0x200): the aura also ticks on
        // application. $o refuses such spells, and any spell whose attribute is unreadable.
        var attributes = misc.attribute(selected, 5);
        if (attributes != null) {
            sources.add(
                    new Source("SpellMisc", selected.id(), "Attributes[5]", attributes.toString()));
        }
        Boolean initialTick = attributes == null ? null : (attributes & 0x200L) != 0;
        var durationRef = TableTypes.Ref.forName("SpellDuration");
        var durationSchema = tables.schema(durationRef, priority, token).join();
        var columns = new Columns(durationSchema);
        if (!TableQueries.isReadable(durationSchema.table().state())
                || !columns.durationProfile()) {
            return new SpellText.Value(
                    null,
                    sources,
                    "SpellDuration profile is unavailable: " + durationSchema.table().state());
        }
        var result = tables.get(durationRef, id.longValueExact(), priority, token).join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND
                || result.row() == null
                || result.row().row().id() != id.longValueExact()) {
            return new SpellText.Value(null, sources, "SpellDuration record is unavailable");
        }
        var row = result.row().row();
        for (String field :
                List.of("Duration", "DurationPerLevel", "MaxDuration", "DurationPerResource")) {
            sources.add(new Source("SpellDuration", row.id(), field, columns.value(row, field)));
        }
        var milliseconds = columns.number(row, "Duration");
        if (milliseconds == null
                || milliseconds.signum() <= 0
                || milliseconds.scale() > 0
                || milliseconds.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                || !columns.zero(row, "DurationPerLevel")
                || !columns.zero(row, "DurationPerResource")
                || milliseconds.compareTo(
                                columns.number(row, "MaxDuration") == null
                                        ? BigDecimal.ZERO
                                        : columns.number(row, "MaxDuration"))
                        != 0
                || milliseconds.remainder(BigDecimal.valueOf(1000)).signum() != 0) {
            return new SpellText.Value(
                    null,
                    sources,
                    "Duration is contextual, indefinite or not a fixed whole second");
        }
        if (durationSchema.table().state() == TableTypes.State.PARTIAL) {
            issues.add("SpellDuration is PARTIAL; duration uses a readable record only.");
        }
        return new SpellText.Value(
                milliseconds.divide(BigDecimal.valueOf(1000)).toBigIntegerExact() + " sec",
                sources,
                null,
                milliseconds.longValueExact(),
                initialTick);
    }

    private static Description unavailable(long spell, String issue) {
        return new Description(spell, null, null, State.UNAVAILABLE, List.of(), List.of(issue));
    }

    private static final class Columns {
        private final TableTypes.SchemaResult schema;

        Columns(TableTypes.SchemaResult schema) {
            this.schema = schema;
        }

        int field(String name, boolean text) {
            if (schema.schema() == null
                    || schema.schema().columns().stream()
                                    .filter(field -> field.name().equals(name))
                                    .count()
                            != 1) {
                return -1;
            }
            int offset = 0;
            for (var field : schema.schema().columns()) {
                int count = field.arrayLength();
                if (field.name().equals(name)) {
                    boolean kind =
                            text
                                    ? field.kind() == TableSchema.ColumnKind.STRING
                                            || field.kind() == TableSchema.ColumnKind.LOCSTRING
                                    : field.kind() == TableSchema.ColumnKind.INT
                                            || field.kind() == TableSchema.ColumnKind.FLOAT;
                    if (!field.verifiedName()
                            || count != 1
                            || !kind
                            || offset >= schema.columns().size()) {
                        return -1;
                    }
                    var column = schema.columns().get(offset);
                    String expected =
                            text
                                    ? "string"
                                    : field.kind() == TableSchema.ColumnKind.FLOAT
                                            ? "float"
                                            : "integer";
                    if (!name.equals(column.name())
                            || column.interpretation() != null
                            || !expected.equals(column.kind())) {
                        return -1;
                    }
                    return offset;
                }
                offset += count;
            }
            return -1;
        }

        boolean layout(String hash) {
            return schema.schema() != null
                    && schema.table().counts() != null
                    && hash.equalsIgnoreCase(schema.table().counts().layoutHash())
                    && schema.schema().layoutHashes().stream().anyMatch(hash::equalsIgnoreCase);
        }

        boolean miscProfile() {
            if (!layout("434B3607")) {
                return false;
            }
            for (String name : List.of("SpellID", "DifficultyID", "DurationIndex")) {
                if (field(name, false) < 0
                        || schema.schema().columns().stream()
                                .noneMatch(
                                        c ->
                                                c.name().equals(name)
                                                        && c.kind()
                                                                == TableSchema.ColumnKind.INT)) {
                    return false;
                }
            }
            return schema.columns().size() > 21
                    && "integer".equals(schema.columns().get(21).kind())
                    && schema.columns().get(21).interpretation() == null
                    && field("MinDuration", false) >= 0
                    && schema.schema().columns().stream()
                            .anyMatch(
                                    c ->
                                            c.name().equals("MinDuration")
                                                    && c.kind() == TableSchema.ColumnKind.FLOAT);
        }

        boolean radiusProfile() {
            if (!layout("F9A913EE")) {
                return false;
            }
            for (String name : List.of("Radius", "RadiusPerLevel", "RadiusMin", "RadiusMax")) {
                if (field(name, false) < 0
                        || schema.schema().columns().stream()
                                .noneMatch(
                                        c ->
                                                c.name().equals(name)
                                                        && c.kind()
                                                                == TableSchema.ColumnKind.FLOAT)) {
                    return false;
                }
            }
            return true;
        }

        /** One element of a verified unsigned 32-bit INT array field, or null. */
        Long element(Db2Types.Row row, String name, int length, int element) {
            if (schema.schema() == null) {
                return null;
            }
            var fields =
                    schema.schema().columns().stream()
                            .filter(field -> field.name().equals(name))
                            .toList();
            if (fields.size() != 1
                    || !fields.getFirst().verifiedName()
                    || fields.getFirst().kind() != TableSchema.ColumnKind.INT
                    || fields.getFirst().arrayLength() != length) {
                return null;
            }
            String column = name + "[" + element + "]";
            int at = -1;
            for (int i = 0; i < schema.columns().size(); i++) {
                if (schema.columns().get(i).name().equals(column)) {
                    if (at >= 0) {
                        return null;
                    }
                    at = i;
                }
            }
            if (at < 0
                    || !"integer".equals(schema.columns().get(at).kind())
                    || schema.columns().get(at).interpretation() != null) {
                return null;
            }
            try {
                String raw = text(row, at);
                long value = raw == null ? -1 : Long.parseLong(raw);
                return value < 0 || value > 0xffffffffL ? null : value;
            } catch (NumberFormatException failure) {
                return null;
            }
        }

        boolean auraOptionsProfile() {
            if (!layout("95F4D5E1")) {
                return false;
            }
            for (String name :
                    List.of("SpellID", "DifficultyID", "ProcChance", "SpellProcsPerMinuteID")) {
                if (field(name, false) < 0) {
                    return false;
                }
            }
            return true;
        }

        boolean durationProfile() {
            if (!layout("4FDF0951")) {
                return false;
            }
            for (String name :
                    List.of("Duration", "DurationPerLevel", "MaxDuration", "DurationPerResource")) {
                if (field(name, false) < 0
                        || schema.schema().columns().stream()
                                .noneMatch(
                                        c ->
                                                c.name().equals(name)
                                                        && c.kind() == TableSchema.ColumnKind.INT
                                                        && Integer.valueOf(32).equals(c.bits())
                                                        && Boolean.FALSE.equals(c.unsigned()))) {
                    return false;
                }
            }
            return true;
        }

        boolean rawGuardZero(Db2Types.Row row) {
            return rawZero(row, 24);
        }

        boolean rawZero(Db2Types.Row row, int index) {
            String value = text(row, index);
            try {
                return value != null && new BigDecimal(value).signum() == 0;
            } catch (NumberFormatException failure) {
                return false;
            }
        }

        boolean modernProfile() {
            if (!layout("5362E3D4")
                    || schema.columns().size() <= 24
                    || !schema.columns().get(24).kind().equals("integer")) {
                return false;
            }
            if (schema.schema() == null
                    || schema.schema().columns().stream()
                            .anyMatch(
                                    field ->
                                            field.name().equals("EffectBasePoints")
                                                    || field.name().equals("EffectDieSides"))) {
                return false;
            }
            for (String name :
                    List.of(
                            "SpellID",
                            "DifficultyID",
                            "EffectIndex",
                            "EffectBasePointsF",
                            "Effect",
                            "EffectAura")) {
                if (field(name, false) < 0) {
                    return false;
                }
            }
            var floats = new ArrayList<>(CONTEXT_FLOATS);
            floats.add("EffectBasePointsF");
            floats.addAll(NEUTRAL_FLOATS);
            for (String name : floats) {
                if (field(name, false) < 0
                        || schema.schema().columns().stream()
                                .noneMatch(
                                        field ->
                                                field.name().equals(name)
                                                        && field.kind()
                                                                == TableSchema.ColumnKind.FLOAT)) {
                    return false;
                }
            }
            for (String name :
                    List.of("SpellID", "DifficultyID", "EffectIndex", "Effect", "EffectAura")) {
                if (schema.schema().columns().stream()
                        .noneMatch(
                                field ->
                                        field.name().equals(name)
                                                && field.kind() == TableSchema.ColumnKind.INT)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * One verified 32-bit element of the SpellMisc Attributes[17] array, or null when the array
         * or the row value is not readable as recorded.
         */
        Long attribute(Db2Types.Row row, int element) {
            if (schema.schema() == null) {
                return null;
            }
            var fields =
                    schema.schema().columns().stream()
                            .filter(field -> field.name().equals("Attributes"))
                            .toList();
            if (fields.size() != 1
                    || !fields.getFirst().verifiedName()
                    || fields.getFirst().kind() != TableSchema.ColumnKind.INT
                    || fields.getFirst().arrayLength() != 17
                    || !Integer.valueOf(32).equals(fields.getFirst().bits())) {
                return null;
            }
            String name = "Attributes[" + element + "]";
            int at = -1;
            for (int i = 0; i < schema.columns().size(); i++) {
                if (schema.columns().get(i).name().equals(name)) {
                    if (at >= 0) {
                        return null;
                    }
                    at = i;
                }
            }
            if (at < 0
                    || !"integer".equals(schema.columns().get(at).kind())
                    || schema.columns().get(at).interpretation() != null) {
                return null;
            }
            try {
                String raw = text(row, at);
                return raw == null ? null : Long.parseLong(raw) & 0xffffffffL;
            } catch (NumberFormatException failure) {
                return null;
            }
        }

        String text(Db2Types.Row row, int index) {
            if (index < 0
                    || index >= row.values().size()
                    || ItemEffectProfiles.hasIssue(row, index)) {
                return null;
            }
            return row.values().get(index);
        }

        String value(Db2Types.Row row, String field) {
            return text(row, field(field, false));
        }

        BigDecimal number(Db2Types.Row row, String field) {
            String raw = value(row, field);
            try {
                return raw == null ? null : new BigDecimal(raw);
            } catch (NumberFormatException failure) {
                return null;
            }
        }

        boolean zero(Db2Types.Row row, String field) {
            var value = number(row, field);
            return value != null && value.signum() == 0;
        }
    }

    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> result;
        synchronized (gate) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            result = closing;
        }
        try {
            lifetime.cancel();
        } catch (Throwable failure) {
            synchronized (gate) {
                closeFailure = failure;
            }
        }
        synchronized (gate) {
            cancellationComplete = true;
            finishClose();
        }
        return result.copy();
    }

    private void finishClose() {
        if (closing != null && cancellationComplete && active.isEmpty()) {
            cache.clear();
            if (closeFailure == null) {
                closing.complete(null);
            } else {
                closing.completeExceptionally(closeFailure);
            }
        }
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
