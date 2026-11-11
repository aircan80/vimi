package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.definitions.BundledDefinitions.DefinitionTable;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Exact bundled build first, then a pinned supplemental profile, then same-major layout fallback.
 * Definitions are never fetched: a table no bundled definition covers resolves as {@link
 * State#FETCHING_OFF}, its historical name, with problem reading "off".
 */
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
            DbdDocument document) {}

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
    private final boolean supplemental;

    public TableDefinitions(BundledDefinitions bundled) {
        this(bundled, false);
    }

    private TableDefinitions(BundledDefinitions bundled, boolean supplemental) {
        this.supplemental = supplemental;
        this.bundled = java.util.Objects.requireNonNull(bundled);
    }

    /** Enables only separately pinned exact supplemental profiles; performs no network fetch. */
    public TableDefinitions withSupplementalDefinitions() {
        return new TableDefinitions(bundled, true);
    }

    public List<DefinitionTable> tables() {
        return bundled.tables();
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
        token.check();
        String reading = "off";
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
                        pinned.definition().document());
        Definition chosen = null;
        var supplement =
                supplemental ? SupplementalDefinitions.lookup(table, build, layout, token) : null;
        if (exact(own.document(), build, layout, token)) {
            chosen = own;
        } else if (supplement != null) {
            chosen = supplement;
        } else if (byLayout(own.document(), build, layout, token)) {
            chosen = own;
        }
        if (chosen == null) {
            return new Resolution(
                    State.FETCHING_OFF,
                    table,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new DefinitionException(
                            layout == null ? "SCHEMA_BUILD_NOT_LISTED" : "DB2_SCHEMA_MISSING",
                            "No definition covers this build/layout",
                            false,
                            new DefinitionException.Problem(id, reading)),
                    null,
                    reading,
                    false,
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
        if (selection != null && selection.blocks().size() != 1) {
            return new Resolution(
                    State.AMBIGUOUS,
                    table,
                    chosen,
                    null,
                    null,
                    match,
                    null,
                    new DefinitionException(
                                    "DEFINITION_AMBIGUOUS", "Several definition blocks match")
                            .withDefinition(id, reading),
                    null,
                    reading,
                    false,
                    null);
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
                    null,
                    null,
                    match,
                    schema,
                    null,
                    null,
                    reading,
                    false,
                    null);
        } catch (DefinitionException e) {
            return new Resolution(
                    State.BLOCK_UNSUPPORTED,
                    table,
                    chosen,
                    null,
                    null,
                    match,
                    null,
                    e.withDefinition(id, reading),
                    null,
                    reading,
                    false,
                    null);
        }
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
