package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.definitions.BundledDefinitions.DefinitionTable;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Exact bundled build first, exact fetched build next, then same-major layout fallback. */
public final class TableDefinitions {
    public enum Source {
        BUNDLED,
        FETCHED,
        SUPPLEMENTAL
    }

    public enum FetchedBecause {
        EXACT_BUILD,
        LAYOUT_NOT_BUNDLED,
        LAYOUT_MATCH
    }

    public enum State {
        RESOLVED,
        NOT_IN_PROVIDER,
        UNREADABLE,
        FETCHING_OFF,
        NO_MATCH,
        FETCH_FAILED,
        AMBIGUOUS,
        BLOCK_UNSUPPORTED
    }

    public record Definition(
            Source source,
            String sha256,
            String sourceUrl,
            String revision,
            DbdDocument document,
            FetchedDefinitions.Info fetched) {}

    public record Resolution(
            State state,
            DefinitionTable table,
            Definition definition,
            FetchedBecause because,
            Definition fetched,
            SchemaMatch match,
            TableSchema schema,
            DefinitionException problem,
            DefinitionException fetchFailure,
            String definitionFetch,
            boolean asked,
            Boolean bundledFallback) {}

    @FunctionalInterface
    public interface FetchSwitch {
        CompletableFuture<Boolean> fetchEnabled(Cancellation token);
    }

    public static final class FetchBudget {
        private boolean asked;
        private DefinitionException failure;

        public boolean asked() {
            return asked;
        }

        public DefinitionException failure() {
            return failure;
        }

        void record(DefinitionException value) {
            asked = true;
            failure = value;
        }
    }

    public record Lookup(
            FetchBudget budget,
            Function<String, CompletableFuture<Void>> progress,
            boolean deliberate) {
        public static final Lookup LOCAL = new Lookup(null, null, false);

        public static Lookup ask(
                FetchBudget budget,
                Function<String, CompletableFuture<Void>> progress,
                boolean deliberate) {
            return new Lookup(
                    java.util.Objects.requireNonNull(budget),
                    java.util.Objects.requireNonNull(progress),
                    deliberate);
        }
    }

    private final BundledDefinitions bundled;
    private final FetchedDefinitions fetched;
    private final FetchSwitch fetchSwitch;
    private final boolean supplemental;

    public TableDefinitions(
            BundledDefinitions bundled, FetchedDefinitions fetched, FetchSwitch fetchSwitch) {
        this(bundled, fetched, fetchSwitch, false);
    }

    private TableDefinitions(
            BundledDefinitions bundled,
            FetchedDefinitions fetched,
            FetchSwitch fetchSwitch,
            boolean supplemental) {
        this.supplemental = supplemental;
        this.bundled = java.util.Objects.requireNonNull(bundled);
        this.fetched = fetched;
        this.fetchSwitch = fetchSwitch;
    }

    /** Enables only separately pinned exact supplemental profiles; performs no network fetch. */
    public TableDefinitions withSupplementalDefinitions() {
        return new TableDefinitions(bundled, fetched, fetchSwitch, true);
    }

    public List<DefinitionTable> tables() {
        return bundled.tables();
    }

    public CompletableFuture<Boolean> fetchEnabled(Cancellation token) {
        if (fetched == null || fetchSwitch == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            return fetchSwitch
                    .fetchEnabled(token)
                    .handle(
                            (value, error) -> {
                                token.check();
                                return error == null && Boolean.TRUE.equals(value);
                            });
        } catch (RuntimeException e) {
            token.check();
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Resolution> resolve(
            String id,
            String build,
            String layout,
            WowLocale locale,
            Lookup lookup,
            Cancellation token) {
        check(build, layout);
        java.util.Objects.requireNonNull(locale);
        java.util.Objects.requireNonNull(lookup);
        return CompletableFuture.supplyAsync(
                () -> resolveCore(id, build, layout, locale, lookup, token),
                command -> Thread.ofVirtual().start(command));
    }

    private Resolution resolveCore(
            String id,
            String build,
            String layout,
            WowLocale locale,
            Lookup lookup,
            Cancellation token) {
        boolean enabled = fetchEnabled(token).join();
        String reading = enabled ? "on" : "off";
        var table = tables().stream().filter(t -> t.tableId().equals(id)).findFirst().orElse(null);
        if (table == null) {
            return failure(
                    State.NOT_IN_PROVIDER,
                    null,
                    "TABLE_NOT_IN_PROVIDER",
                    "Table is absent from pinned manifest",
                    reading);
        }
        var pinned = bundled.tryGet(id);
        if (pinned.definition() == null) {
            return failure(
                    State.UNREADABLE,
                    table,
                    pinned.failure() == null ? "DEFINITION_NOT_BUNDLED" : pinned.failure().code(),
                    pinned.failure() == null
                            ? "Definition is not bundled"
                            : pinned.failure().getMessage(),
                    reading);
        }
        var own =
                new Definition(
                        Source.BUNDLED,
                        pinned.definition().sha256(),
                        pinned.definition().sourceUrl(),
                        BundledDefinitions.REVISION,
                        pinned.definition().document(),
                        null);
        Definition chosen = null, other = null;
        DefinitionException fetchFailure = null;
        boolean asked = false;
        var supplement =
                supplemental ? SupplementalDefinitions.lookup(table, build, layout, token) : null;
        if (exact(own.document(), build, layout, token)) {
            chosen = own;
        } else if (supplement != null) {
            chosen = supplement;
        } else {
            if (enabled && fetched != null && table.name() != null) {
                FetchedDefinitions.Definition found;
                if (lookup.budget() == null || lookup.progress() == null) {
                    found = fetched.cached(id, table.name(), token).join();
                } else if (lookup.budget().asked()) {
                    found = fetched.cached(id, table.name(), token).join();
                    fetchFailure = lookup.budget().failure();
                } else {
                    var outcome =
                            fetched.ensure(
                                            id,
                                            table.name(),
                                            d -> exact(d, build, layout, token),
                                            lookup.progress(),
                                            token,
                                            lookup.deliberate())
                                    .join();
                    found = outcome.definition();
                    fetchFailure = outcome.failure();
                    asked = outcome.asked();
                    if (asked) {
                        lookup.budget().record(fetchFailure);
                    }
                }
                if (found != null) {
                    other =
                            new Definition(
                                    Source.FETCHED,
                                    found.sha256(),
                                    found.sourceUrl(),
                                    found.revision(),
                                    found.document(),
                                    found.info());
                }
            }
            if (other != null && exact(other.document(), build, layout, token)) {
                chosen = other;
            } else if (byLayout(own.document(), build, layout, token)) {
                chosen = own;
            } else if (other != null && byLayout(other.document(), build, layout, token)) {
                chosen = other;
            }
        }
        if (chosen == null) {
            State state =
                    !enabled
                            ? State.FETCHING_OFF
                            : fetchFailure != null ? State.FETCH_FAILED : State.NO_MATCH;
            String code =
                    fetchFailure != null
                            ? fetchFailure.code()
                            : layout == null ? "SCHEMA_BUILD_NOT_LISTED" : "DB2_SCHEMA_MISSING";
            return new Resolution(
                    state,
                    table,
                    null,
                    null,
                    other,
                    null,
                    null,
                    new DefinitionException(
                            code,
                            "No definition covers this build/layout",
                            fetchFailure != null && fetchFailure.retryable(),
                            new DefinitionException.Problem(id, reading)),
                    fetchFailure,
                    reading,
                    asked,
                    null);
        }
        boolean listed = chosen.document().listsBuild(build, token);
        var selection =
                layout == null
                        ? (listed ? chosen.document().selectBuild(build, null, token) : null)
                        : chosen.document().selectFile(build, layout, token);
        SchemaMatch match =
                layout == null
                        ? (listed ? SchemaMatch.BUILD : SchemaMatch.LAYOUT)
                        : selection.match().equals("layout")
                                ? SchemaMatch.LAYOUT
                                : SchemaMatch.BUILD;
        FetchedBecause because =
                chosen.source() != Source.FETCHED
                        ? null
                        : own.document().listsBuild(build, token)
                                ? FetchedBecause.LAYOUT_NOT_BUNDLED
                                : match == SchemaMatch.LAYOUT
                                        ? FetchedBecause.LAYOUT_MATCH
                                        : FetchedBecause.EXACT_BUILD;
        Boolean fallback =
                chosen.source() == Source.FETCHED && layout != null
                        ? byLayout(own.document(), build, layout, token)
                        : null;
        if (selection != null && selection.blocks().size() != 1) {
            return new Resolution(
                    State.AMBIGUOUS,
                    table,
                    chosen,
                    because,
                    other,
                    match,
                    null,
                    new DefinitionException(
                                    "DEFINITION_AMBIGUOUS", "Several definition blocks match")
                            .withDefinition(id, reading),
                    fetchFailure,
                    reading,
                    asked,
                    fallback);
        }
        try {
            var schema =
                    selection == null
                            ? null
                            : TableSchema.fromBlock(selection.blocks().getFirst(), match, locale);
            return new Resolution(
                    State.RESOLVED,
                    table,
                    chosen,
                    because,
                    other,
                    match,
                    schema,
                    null,
                    fetchFailure,
                    reading,
                    asked,
                    fallback);
        } catch (DefinitionException e) {
            return new Resolution(
                    State.BLOCK_UNSUPPORTED,
                    table,
                    chosen,
                    because,
                    other,
                    match,
                    null,
                    e.withDefinition(id, reading),
                    fetchFailure,
                    reading,
                    asked,
                    fallback);
        }
    }

    public CompletableFuture<Boolean> refreshDue(
            String id, String build, String layout, Cancellation token) {
        check(build, layout);
        var table =
                tables().stream()
                        .filter(t -> t.tableId().equals(id) && t.name() != null)
                        .findFirst()
                        .orElse(null);
        var pinned = bundled.tryGet(id);
        if (fetched == null
                || table == null
                || pinned.definition() == null
                || exact(pinned.definition().document(), build, layout, token)) {
            return CompletableFuture.completedFuture(false);
        }
        return fetchEnabled(token)
                .thenCompose(
                        enabled ->
                                enabled
                                        ? fetched.refreshDue(
                                                id,
                                                table.name(),
                                                d -> exact(d, build, layout, token),
                                                token)
                                        : CompletableFuture.completedFuture(false));
    }

    public CompletableFuture<FetchedDefinitions.Check> lastCheck(
            String id, String build, String layout, Cancellation token) {
        check(build, layout);
        var table =
                tables().stream()
                        .filter(t -> t.tableId().equals(id) && t.name() != null)
                        .findFirst()
                        .orElse(null);
        var pinned = bundled.tryGet(id);
        if (fetched == null
                || table == null
                || pinned.definition() == null
                || exact(pinned.definition().document(), build, layout, token)) {
            return CompletableFuture.completedFuture(null);
        }
        return fetchEnabled(token)
                .thenCompose(
                        enabled ->
                                enabled
                                        ? fetched.lastCheck(
                                                id,
                                                table.name(),
                                                d -> exact(d, build, layout, token),
                                                token)
                                        : CompletableFuture.completedFuture(null));
    }

    public static boolean exact(DbdDocument d, String build, String layout, Cancellation token) {
        return d.listsBuild(build, token)
                && (layout == null || !d.selectBuild(build, layout, token).blocks().isEmpty());
    }

    public static boolean byLayout(DbdDocument d, String build, String layout, Cancellation token) {
        return !d.listsBuild(build, token)
                && (layout == null
                        ? !d.layoutCandidates(build, token).isEmpty()
                        : !d.selectFile(build, layout, token).blocks().isEmpty());
    }

    public static boolean opensBuild(DbdDocument d, String build, Cancellation token) {
        return d.listsBuild(build, token) || !d.layoutCandidates(build, token).isEmpty();
    }

    private static void check(String build, String layout) {
        try {
            DbdTypes.BuildVersion.parse(build);
        } catch (com.vibeminer.formats.Db2FormatException e) {
            throw new IllegalArgumentException("Exact build required", e);
        }
        if (layout != null && !DbdDefinitionReader.isLayout(layout)) {
            throw new IllegalArgumentException("Complete layout required");
        }
    }

    private static Resolution failure(
            State state, DefinitionTable table, String code, String message, String reading) {
        return new Resolution(
                state,
                table,
                null,
                null,
                null,
                null,
                null,
                new DefinitionException(
                        code,
                        message,
                        false,
                        table == null
                                ? null
                                : new DefinitionException.Problem(table.tableId(), reading)),
                null,
                reading,
                false,
                null);
    }
}
