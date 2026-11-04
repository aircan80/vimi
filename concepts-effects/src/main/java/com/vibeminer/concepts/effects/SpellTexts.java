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
    private final RelationIndexes indexes;
    private final Cancellation lifetime = new Cancellation();
    private final Object gate = new Object();
    private final java.util.Set<CompletableFuture<Description>> active = new HashSet<>();
    private final Map<Long, Description> cache = new LinkedHashMap<>();
    private CompletableFuture<Void> closing;
    private Throwable closeFailure;
    private boolean cancellationComplete;

    public SpellTexts(BuildTables tables, String version) {
        this.tables = Objects.requireNonNull(tables);
        this.version = version;
        this.indexes = new RelationIndexes(tables, 200000);
    }

    public BuildRef build() {
        return tables.build();
    }

    public String locale() {
        return tables.locale();
    }

    /**
     * At most four accepted previews; callers must keep borrowed tables alive until close drains.
     */
    @SuppressWarnings("try")
    public CompletableFuture<Description> description(
            long spellId, TableTypes.Priority priority, Cancellation token) {
        if (spellId < 1 || spellId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid spell ID");
        }
        Objects.requireNonNull(priority);
        Objects.requireNonNull(token);
        token.check();
        var answer = new CompletableFuture<Description>();
        synchronized (gate) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Spell text handle closed"));
            }
            var cached = cache.get(spellId);
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
                                return resolve(spellId, priority, request);
                            }
                        })
                .whenComplete(
                        (value, error) -> {
                            synchronized (gate) {
                                active.remove(answer);
                                if (error == null
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

    private Description resolve(long spell, TableTypes.Priority priority, Cancellation token) {
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
        if (!raw.contains("$") && !raw.contains("|")) {
            return SpellText.project(spell, raw, Map.of(), issues);
        }
        boolean needsAmounts =
                java.util.regex.Pattern.compile("\\$s[1-3](?![A-Za-z0-9])").matcher(raw).find();
        boolean needsDuration =
                java.util.regex.Pattern.compile("\\$d(?![A-Za-z0-9])").matcher(raw).find();
        if (!needsAmounts && !needsDuration) {
            return SpellText.project(spell, raw, Map.of(), issues);
        }
        // The direct-float profile is reviewed for modern Forever, not legacy integer Classic data.
        if (version == null
                || !version.matches("1\\.60\\.[0-9]+(?:\\.[0-9]+)?")
                || !build().product().equals("wowf")) {
            return SpellText.project(
                    spell,
                    raw,
                    Map.of(),
                    List.of("Spell amount profile is unsupported for this build."));
        }
        var amounts =
                needsAmounts
                        ? amounts(spell, priority, token, issues)
                        : Map.<Integer, SpellText.Amount>of();
        var duration = needsDuration ? duration(spell, priority, token, issues) : null;
        return SpellText.project(spell, raw, amounts, duration, issues);
    }

    private Map<Integer, SpellText.Amount> amounts(
            long spell, TableTypes.Priority priority, Cancellation token, List<String> issues) {
        var effectSchema =
                tables.schema(TableTypes.Ref.forName("SpellEffect"), priority, token).join();
        var effects = new Columns(effectSchema);
        if (!TableQueries.isReadable(effectSchema.table().state()) || !effects.modernProfile()) {
            issues.add("SpellEffect profile is unavailable: " + effectSchema.table().state());
            return Map.of();
        }
        var lookup = indexes.lookup("SpellEffect", "SpellID", spell, 64, priority, token).join();
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
                    || index.compareTo(BigDecimal.valueOf(2)) > 0) {
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
            // Existing talent forms stay unchanged; new scalar cases require a literal % at use.
            boolean percentageOnly =
                    (BigDecimal.valueOf(6).equals(kind) && BigDecimal.valueOf(33).equals(aura))
                            || (BigDecimal.valueOf(3).equals(kind) && BigDecimal.ZERO.equals(aura));
            boolean fixed =
                    effects.rawGuardZero(effect)
                            && BigDecimal.valueOf(6).equals(kind)
                            && (BigDecimal.valueOf(108).equals(aura)
                                    || BigDecimal.valueOf(290).equals(aura));
            fixed |= percentageOnly && effects.rawGuardZero(effect);
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
                BigDecimal expected =
                        percentageOnly && field.equals("EffectBonusCoefficient")
                                ? BigDecimal.ZERO
                                : BigDecimal.ONE;
                fixed &= value != null && value.compareTo(expected) == 0;
            }
            if (percentageOnly) {
                for (String field :
                        List.of("EffectAttributes", "EffectAuraPeriod", "EffectTriggerSpell")) {
                    sources.add(
                            new Source(
                                    "SpellEffect",
                                    effect.id(),
                                    field,
                                    effects.value(effect, field)));
                    fixed &= effects.zero(effect, field);
                }
            }
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
                            percentageOnly);
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

    private SpellText.Value duration(
            long spell, TableTypes.Priority priority, Cancellation token, List<String> issues) {
        var sources = new ArrayList<Source>();
        var miscSchema = tables.schema(TableTypes.Ref.forName("SpellMisc"), priority, token).join();
        var misc = new Columns(miscSchema);
        if (!TableQueries.isReadable(miscSchema.table().state()) || !misc.miscProfile()) {
            return new SpellText.Value(null, sources, "SpellMisc duration profile is unavailable");
        }
        var lookup = indexes.lookup("SpellMisc", "SpellID", spell, 64, priority, token).join();
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
                null);
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
