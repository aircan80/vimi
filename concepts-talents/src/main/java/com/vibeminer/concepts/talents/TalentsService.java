package com.vibeminer.concepts.talents;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded read-only Classic projection. The workspace owns the borrowed tables and this handle. */
@SuppressWarnings("try")
public final class TalentsService implements BuildTalents {
    public static final int MAX_CLASSES = 64,
            MAX_TABS = 512,
            MAX_TALENTS = 20000,
            MAX_SPELL_MISC = 200000,
            MAX_CLASS_TALENTS = 512;
    private final BuildTables tables;
    private final String version;
    private final com.vibeminer.concepts.effects.SpellTexts spellTexts;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("talent-projection").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Cancellation lifetime = new Cancellation();
    private final Object gate = new Object();
    private CompletableFuture<Void> closing;
    private Data classes, tabs, talents;
    Data spellNames, spellDescriptions;
    final Map<Long, Long> icons = new HashMap<>();
    private final TraitTrees traits = new TraitTrees(this);
    private final java.util.Set<Long> checkedIcons = new HashSet<>();
    final java.util.Set<String> partialTables = new HashSet<>();
    private final List<String> coreIssues = new ArrayList<>();
    private final List<String> metadataIssues = new ArrayList<>();
    private boolean metadataSchemasLoaded;

    public TalentsService(BuildTables tables, String buildVersion) {
        this.tables = Objects.requireNonNull(tables);
        this.version = buildVersion;
        this.spellTexts = new com.vibeminer.concepts.effects.SpellTexts(tables, buildVersion);
    }

    public static boolean classicModel(String product, String version) {
        return product != null
                && (product.equals("wowf")
                        || product.equals("wow_anniversary")
                        || product.equals("wow_cn_beta")
                        || product.startsWith("wow_classic"))
                && version != null
                && version.matches("[1-4]\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?");
    }

    @Override
    public CompletableFuture<com.vibeminer.concepts.effects.SpellTexts.Description> description(
            long spellId, Cancellation token) {
        return submit(
                token,
                request ->
                        spellTexts
                                .description(spellId, TableTypes.Priority.INTERACTIVE, request)
                                .join());
    }

    @Override
    public CompletableFuture<TalentTypes.Catalog> catalog(Cancellation externalToken) {
        return submit(
                externalToken,
                token -> {
                    if (!classicModel(tables.build().product(), version)) {
                        return new TalentTypes.Catalog(
                                TalentTypes.State.UNSUPPORTED,
                                List.of(),
                                "TALENTS_MODEL_UNSUPPORTED",
                                "This build does not use the supported Classic three-tree talent model.");
                    }
                    try {
                        if (traits.selected(tables.build().product(), version, token)) {
                            return traits.catalog(token);
                        }
                        core(token);
                        var all = playerClasses();
                        return new TalentTypes.Catalog(
                                coreIssues.isEmpty()
                                        ? TalentTypes.State.READY
                                        : TalentTypes.State.PARTIAL,
                                all,
                                coreIssues.isEmpty() ? null : "TALENTS_PARTIAL",
                                !coreIssues.isEmpty()
                                        ? String.join(" · ", coreIssues)
                                        : all.isEmpty()
                                                ? "No player-class talent trees are recorded in this build."
                                                : null);
                    } catch (Projection failure) {
                        return new TalentTypes.Catalog(
                                failure.state, List.of(), failure.code, failure.getMessage());
                    }
                });
    }

    @Override
    public CompletableFuture<TalentTypes.ClassTrees> trees(
            long classId, Cancellation externalToken) {
        if (classId < 1 || classId > MAX_CLASSES) {
            throw new IllegalArgumentException("Invalid class ID");
        }
        return submit(
                externalToken,
                token -> {
                    if (!classicModel(tables.build().product(), version)) {
                        return failed(
                                TalentTypes.State.UNSUPPORTED,
                                "TALENTS_MODEL_UNSUPPORTED",
                                "This build requires a different talent layout.");
                    }
                    try {
                        if (traits.selected(tables.build().product(), version, token)) {
                            return traits.trees(classId, token);
                        }
                        core(token);
                        var player =
                                playerClasses().stream().filter(c -> c.id() == classId).findFirst();
                        if (player.isEmpty()) {
                            return failed(
                                    TalentTypes.State.UNAVAILABLE,
                                    "TALENTS_CLASS_UNAVAILABLE",
                                    "This class has no recorded player talent trees.");
                        }
                        var selected =
                                tabs.rows.stream()
                                        .filter(
                                                row ->
                                                        (mask(tabs.number(row, "ClassMask"))
                                                                        & (1L << (classId - 1)))
                                                                != 0)
                                        .filter(
                                                row ->
                                                        partialTables.contains("Talent")
                                                                || talents.rows.stream()
                                                                        .anyMatch(
                                                                                t ->
                                                                                        talents
                                                                                                        .number(
                                                                                                                t,
                                                                                                                "TabID")
                                                                                                == row
                                                                                                        .id()))
                                        .sorted(
                                                java.util.Comparator.comparingLong(
                                                                (Db2Types.Row r) ->
                                                                        tabs.number(
                                                                                r, "OrderIndex"))
                                                        .thenComparingLong(Db2Types.Row::id))
                                        .toList();
                        boolean incompleteGraph =
                                partialTables.contains("Talent")
                                        || partialTables.contains("TalentTab");
                        if (selected.size() != 3 && (!incompleteGraph || selected.size() > 3)) {
                            return new TalentTypes.ClassTrees(
                                    TalentTypes.State.UNSUPPORTED,
                                    player.get(),
                                    List.of(),
                                    List.of(),
                                    "TALENTS_TREE_COUNT_UNSUPPORTED",
                                    "This class has "
                                            + selected.size()
                                            + " recorded talent tabs; no three-tree layout was inferred.");
                        }
                        var issues = new ArrayList<String>(coreIssues);
                        if (selected.size() != 3) {
                            issues.add(
                                    "Only "
                                            + selected.size()
                                            + " talent trees are readable; additional trees may be unavailable in partial tables.");
                        }
                        var tabIds =
                                selected.stream()
                                        .map(Db2Types.Row::id)
                                        .collect(java.util.stream.Collectors.toSet());
                        var wantedSpells =
                                talents.rows.stream()
                                        .filter(
                                                row ->
                                                        tabIds.contains(
                                                                talents.number(row, "TabID")))
                                        .map(row -> talents.array(row, "SpellRank", 9))
                                        .filter(ranks -> ranks.getFirst() != 0)
                                        .map(List::getFirst)
                                        .collect(java.util.stream.Collectors.toSet());
                        optionalMetadata(token, issues, wantedSpells);
                        var result = new ArrayList<TalentTypes.Tree>();
                        int total = 0;
                        for (var tab : selected) {
                            var nodes = new ArrayList<TalentTypes.Talent>();
                            var positions = new HashSet<String>();
                            for (var row : talents.rows) {
                                token.check();
                                if (talents.number(row, "TabID") != tab.id()) {
                                    continue;
                                }
                                int tier =
                                        integer(
                                                talents.number(row, "TierID"),
                                                0,
                                                255,
                                                "Talent tier");
                                int column =
                                        integer(
                                                talents.number(row, "ColumnIndex"),
                                                0,
                                                3,
                                                "Talent column");
                                if (!positions.add(tier + ":" + column)) {
                                    throw invalid("Multiple talents occupy one tree position");
                                }
                                List<Long> ranks = talents.array(row, "SpellRank", 9);
                                boolean seenZero = false;
                                var spells = new ArrayList<Long>();
                                for (long spell : ranks) {
                                    unsigned(spell, "Rank spell");
                                    if (spell == 0) {
                                        seenZero = true;
                                    } else {
                                        if (seenZero) {
                                            throw invalid("Talent rank array contains a gap");
                                        }
                                        spells.add(spell);
                                    }
                                }
                                if (spells.isEmpty()) {
                                    throw invalid("Talent has no recorded rank spells");
                                }
                                var prerequisites = new ArrayList<TalentTypes.Prerequisite>();
                                var required = talents.array(row, "PrereqTalent", 3);
                                var rankIndices = talents.array(row, "PrereqRank", 3);
                                for (int i = 0; i < 3; i++) {
                                    if (required.get(i) != 0) {
                                        unsigned(required.get(i), "Prerequisite talent");
                                        prerequisites.add(
                                                new TalentTypes.Prerequisite(
                                                        required.get(i),
                                                        integer(
                                                                rankIndices.get(i),
                                                                0,
                                                                8,
                                                                "Prerequisite rank")));
                                    }
                                }
                                long spell = spells.getFirst();
                                String name =
                                        optionalText(spellNames, spell, "Name_lang", token, issues);
                                if (name == null || name.isEmpty()) {
                                    name = "Spell " + spell;
                                }
                                String description =
                                        optionalText(
                                                spellDescriptions,
                                                spell,
                                                "Description_lang",
                                                token,
                                                issues);
                                nodes.add(
                                        new TalentTypes.Talent(
                                                row.id(),
                                                tier,
                                                column,
                                                spells,
                                                prerequisites,
                                                name,
                                                description,
                                                icons.get(spell)));
                                if (++total > MAX_CLASS_TALENTS) {
                                    throw invalid("Class talent count exceeds the supported bound");
                                }
                            }
                            validateEdges(nodes, partialTables.contains("Talent"), issues, token);
                            nodes.sort(
                                    java.util.Comparator.comparingInt(TalentTypes.Talent::row)
                                            .thenComparingInt(TalentTypes.Talent::column));
                            result.add(
                                    new TalentTypes.Tree(
                                            tab.id(),
                                            tabs.text(tab, "Name_lang"),
                                            integer(
                                                    tabs.number(tab, "OrderIndex"),
                                                    0,
                                                    255,
                                                    "Talent tab order"),
                                            tabs.text(tab, "BackgroundFile"),
                                            nodes));
                        }
                        return new TalentTypes.ClassTrees(
                                partialTables.isEmpty()
                                        ? TalentTypes.State.READY
                                        : TalentTypes.State.PARTIAL,
                                player.get(),
                                result,
                                issues,
                                null,
                                null,
                                TalentTypes.Model.LEGACY_TALENT);
                    } catch (Projection failure) {
                        return failed(failure.state, failure.code, failure.getMessage());
                    }
                });
    }

    static TalentTypes.ClassTrees failed(TalentTypes.State state, String code, String message) {
        return new TalentTypes.ClassTrees(state, null, List.of(), List.of(), code, message);
    }

    private void core(Cancellation token) {
        if (classes == null) {
            Data newClasses = read("ChrClasses", MAX_CLASSES, token);
            newClasses.textField("Name_lang");
            Data newTabs = read("TalentTab", MAX_TABS, token);
            newTabs.textField("Name_lang");
            newTabs.textField("BackgroundFile");
            newTabs.integerField("ClassMask", 1);
            newTabs.integerField("OrderIndex", 1);
            Data newTalents = read("Talent", MAX_TALENTS, token);
            for (String field : List.of("TabID", "TierID", "ColumnIndex")) {
                newTalents.integerField(field, 1);
            }
            newTalents.integerField("SpellRank", 9);
            newTalents.integerField("PrereqTalent", 3);
            newTalents.integerField("PrereqRank", 3);
            classes = newClasses;
            tabs = newTabs;
            talents = newTalents;
        }
    }

    private List<TalentTypes.PlayerClass> playerClasses() {
        var result = new ArrayList<TalentTypes.PlayerClass>();
        var populatedTabs = new HashSet<Long>();
        for (var talent : talents.rows) {
            populatedTabs.add(talents.number(talent, "TabID"));
        }
        long playerMask = 0;
        for (var tab : tabs.rows) {
            if (partialTables.contains("Talent") || populatedTabs.contains(tab.id())) {
                playerMask |= mask(tabs.number(tab, "ClassMask"));
            }
        }
        for (var row : classes.rows) {
            if (row.id() < 1 || row.id() > MAX_CLASSES) {
                continue;
            }
            long bit = 1L << (row.id() - 1);
            if ((playerMask & bit) == 0
                    && !partialTables.contains("Talent")
                    && !partialTables.contains("TalentTab")) {
                continue;
            }
            result.add(playerClass(classes, row));
        }
        return List.copyOf(result);
    }

    static TalentTypes.PlayerClass playerClass(Data classes, Db2Types.Row row) {
        String name = classes.text(row, "Name_lang");
        Long icon = classes.optionalPositive(row, "IconFileDataID");
        String color = null;
        Long red = classes.optionalPositiveOrZero(row, "ClassColorR"),
                green = classes.optionalPositiveOrZero(row, "ClassColorG"),
                blue = classes.optionalPositiveOrZero(row, "ClassColorB");
        if (red != null
                && green != null
                && blue != null
                && red <= 255
                && green <= 255
                && blue <= 255) {
            color = String.format(java.util.Locale.ROOT, "#%02x%02x%02x", red, green, blue);
        }
        return new TalentTypes.PlayerClass(
                row.id(), name.isEmpty() ? "Class #" + row.id() : name, icon, color);
    }

    void optionalMetadata(Cancellation token, List<String> issues, java.util.Set<Long> wanted) {
        if (!metadataSchemasLoaded) {
            try {
                spellNames = schema("SpellName", token, metadataIssues);
                spellNames.textField("Name_lang");
            } catch (Projection failure) {
                spellNames = null;
                metadataIssues.add(
                        "Localized spell names are unavailable: " + failure.getMessage());
            }
            try {
                spellDescriptions = schema("Spell", token, metadataIssues);
                spellDescriptions.textField("Description_lang");
            } catch (Projection failure) {
                spellDescriptions = null;
                metadataIssues.add("Spell descriptions are unavailable: " + failure.getMessage());
            }
            metadataSchemasLoaded = true;
        }
        issues.addAll(metadataIssues);
        var requested = new HashSet<>(wanted);
        requested.removeAll(checkedIcons);
        if (requested.isEmpty()) {
            return;
        }
        var found = new HashMap<Long, Long>();
        try {
            Data misc = schema("SpellMisc", token, issues);
            misc.integerField("SpellID", 1);
            misc.integerField("DifficultyID", 1);
            misc.integerField("SpellIconFileDataID", 1);
            var ambiguous = new HashSet<Long>();
            stream(
                    misc,
                    MAX_SPELL_MISC,
                    token,
                    row -> {
                        long spell = misc.number(row, "SpellID");
                        if (!requested.contains(spell) || misc.number(row, "DifficultyID") != 0) {
                            return;
                        }
                        long icon = misc.number(row, "SpellIconFileDataID");
                        if (icon <= 0 || icon > 0xffffffffL) {
                            return;
                        }
                        if (found.putIfAbsent(spell, icon) != null) {
                            ambiguous.add(spell);
                        }
                    });
            for (long spell : ambiguous) {
                found.remove(spell);
            }
            if (!ambiguous.isEmpty()) {
                issues.add("Ambiguous spell icons are omitted.");
            }
            icons.putAll(found);
            checkedIcons.addAll(requested);
        } catch (Projection failure) {
            issues.add("Spell icon references are unavailable: " + failure.getMessage());
        }
    }

    String optionalText(Data data, long id, String field, Cancellation token, List<String> issues) {
        try {
            var row = optionalRow(data, id, token, issues);
            return row == null ? null : data.text(row, field);
        } catch (Projection failure) {
            String issue =
                    data.name + ": some optional spell text is unreadable (" + failure.code + ").";
            if (!issues.contains(issue)) {
                issues.add(issue);
            }
            return null;
        }
    }

    private Db2Types.Row optionalRow(Data data, long id, Cancellation token, List<String> issues) {
        if (data == null) {
            return null;
        }
        var result =
                tables.get(
                                TableTypes.Ref.forName(data.name),
                                id,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND || result.row() == null) {
            String issue =
                    data.name + ": some requested spell records are " + result.outcome() + ".";
            if (!issues.contains(issue)) {
                issues.add(issue);
            }
            return null;
        }
        return result.row().row();
    }

    private Data schema(String name, Cancellation token) {
        return schema(name, token, coreIssues);
    }

    /** Every readable partial table contributes its available rows and an explicit diagnostic. */
    Data schema(String name, Cancellation token, List<String> optionalIssues) {
        token.check();
        var result =
                tables.schema(TableTypes.Ref.forName(name), TableTypes.Priority.INTERACTIVE, token)
                        .join();
        var state = result.table().state();
        if ((state != TableTypes.State.READY
                        && (optionalIssues == null || state != TableTypes.State.PARTIAL))
                || result.columns() == null
                || result.schema() == null) {
            throw new Projection(
                    TalentTypes.State.UNAVAILABLE,
                    "TALENTS_TABLE_" + result.table().state(),
                    name
                            + ": "
                            + state
                            + (result.table().reason() == null
                                    ? ""
                                    : " (" + result.table().reason().code() + ")"));
        }
        if (state == TableTypes.State.PARTIAL) {
            partialTables.add(name);
            String issue =
                    name
                            + ": PARTIAL; showing readable records, other records may be protected or unavailable.";
            if (!optionalIssues.contains(issue)) {
                optionalIssues.add(issue);
            }
        }
        String layout =
                result.table().counts() == null ? null : result.table().counts().layoutHash();
        return new Data(
                name,
                result.columns(),
                result.schema(),
                List.of(),
                layout != null && layout.equalsIgnoreCase(TraitTrees.REVIEWED_LAYOUTS.get(name)));
    }

    private Data read(String name, int bound, Cancellation token) {
        return read(name, bound, token, coreIssues);
    }

    Data read(String name, int bound, Cancellation token, List<String> issues) {
        Data data = schema(name, token, issues);
        var rows = new ArrayList<Db2Types.Row>();
        stream(data, bound, token, rows::add);
        return data.withRows(rows);
    }

    TableTypes.SchemaResult probe(String name, Cancellation token) {
        token.check();
        return tables.schema(TableTypes.Ref.forName(name), TableTypes.Priority.INTERACTIVE, token)
                .join();
    }

    TableTypes.RowResult get(String name, long id, Cancellation token) {
        token.check();
        return tables.get(TableTypes.Ref.forName(name), id, TableTypes.Priority.INTERACTIVE, token)
                .join();
    }

    void stream(
            Data data,
            int bound,
            Cancellation token,
            java.util.function.Consumer<Db2Types.Row> consume) {
        long after = -1;
        String identity = null;
        int count = 0;
        while (true) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.forName(data.name),
                                            after,
                                            256,
                                            List.of(),
                                            identity),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (page.state() != TableTypes.PageState.OK) {
                throw new Projection(
                        TalentTypes.State.UNAVAILABLE,
                        "TALENTS_TABLE_UNAVAILABLE",
                        data.name + ": " + page.state());
            }
            if (identity == null) {
                identity = page.identity();
            } else {
                if (!Objects.equals(identity, page.identity())) {
                    throw new Projection(
                            TalentTypes.State.UNAVAILABLE,
                            "TALENTS_TABLE_CHANGED",
                            data.name + " changed during projection");
                }
            }
            for (var row : page.rows()) {
                if (count++ >= bound) {
                    throw new Projection(
                            TalentTypes.State.UNSUPPORTED,
                            "TALENTS_TABLE_LIMIT",
                            data.name + " exceeds " + bound + " rows");
                }
                consume.accept(row.row());
            }
            if (page.nextAfter() == null) {
                break;
            }
            if (page.nextAfter() <= after) {
                throw invalid("Table cursor did not advance");
            }
            after = page.nextAfter();
        }
    }

    private void validateEdges(
            List<TalentTypes.Talent> nodes,
            boolean partial,
            List<String> issues,
            Cancellation token) {
        var byId = new HashMap<Long, TalentTypes.Talent>();
        for (var node : nodes) {
            if (byId.put(node.id(), node) != null) {
                throw invalid("Duplicate talent ID");
            }
        }
        for (var node : nodes) {
            for (var prerequisite : node.prerequisites()) {
                var target = byId.get(prerequisite.talentId());
                if (target == null && partial) {
                    var missing =
                            tables.get(
                                            TableTypes.Ref.forName("Talent"),
                                            prerequisite.talentId(),
                                            TableTypes.Priority.INTERACTIVE,
                                            token)
                                    .join();
                    if (missing.outcome() == TableTypes.RowOutcome.FOUND) {
                        throw invalid("Prerequisite belongs to a different talent tree");
                    }
                    String issue =
                            "Some talent prerequisites are unresolved ("
                                    + missing.outcome()
                                    + ") in the partial Talent table; their arrows are omitted.";
                    if (!issues.contains(issue)) {
                        issues.add(issue);
                    }
                    continue;
                }
                if (target == null
                        || target.id() == node.id()
                        || prerequisite.rankIndex() >= target.rankSpells().size()) {
                    throw invalid("Invalid prerequisite reference or rank");
                }
            }
        }
        var complete = new HashSet<Long>();
        for (var node : nodes) {
            visit(node, byId, new HashSet<>(), complete);
        }
    }

    static void visit(
            TalentTypes.Talent node,
            Map<Long, TalentTypes.Talent> byId,
            java.util.Set<Long> path,
            java.util.Set<Long> complete) {
        if (complete.contains(node.id())) {
            return;
        }
        if (!path.add(node.id())) {
            throw invalid("Cyclic talent prerequisites");
        }
        for (var edge : node.prerequisites()) {
            var target = byId.get(edge.talentId());
            if (target != null) {
                visit(target, byId, path, complete);
            }
        }
        path.remove(node.id());
        complete.add(node.id());
    }

    static int integer(long value, int min, int max, String field) {
        if (value < min || value > max) {
            throw invalid(field + " is out of range");
        }
        return (int) value;
    }

    static long unsigned(long value, String field) {
        if (value < 0 || value > 0xffffffffL) {
            throw invalid(field + " is not uint32");
        }
        return value;
    }

    static long mask(long value) {
        if (value < Integer.MIN_VALUE || value > 0xffffffffL) {
            throw invalid("ClassMask is not a 32-bit mask");
        }
        if (value < 0 && value >= Integer.MIN_VALUE) {
            return value & 0xffffffffL;
        }
        return value;
    }

    static Projection invalid(String message) {
        return new Projection(TalentTypes.State.INVALID, "TALENTS_DATA_INVALID", message);
    }

    static final class Projection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final TalentTypes.State state;
        final String code;

        Projection(TalentTypes.State state, String code, String message) {
            super(message);
            this.state = state;
            this.code = code;
        }
    }

    static final class Data {
        final String name;
        final List<Db2Types.Column> columns;
        final TableSchema schema;
        final List<Db2Types.Row> rows;

        /** The table's physical layout is in the reviewed trait profile; see TraitTrees. */
        final boolean reviewedLayout;

        Data(
                String name,
                List<Db2Types.Column> columns,
                TableSchema schema,
                List<Db2Types.Row> rows,
                boolean reviewedLayout) {
            this.name = name;
            this.columns = columns;
            this.schema = schema;
            this.rows = rows;
            this.reviewedLayout = reviewedLayout;
        }

        Data withRows(List<Db2Types.Row> retained) {
            return new Data(name, columns, schema, List.copyOf(retained), reviewedLayout);
        }

        TableSchema.SchemaColumn field(String name, int count, boolean integer) {
            var fields = schema.columns().stream().filter(f -> f.name().equals(name)).toList();
            if (fields.size() != 1) {
                throw unsupported(name + " is not one verified field");
            }
            var field = fields.getFirst();
            if (!field.verifiedName() && !reviewedLayout
                    || field.arrayLength() != count
                    || integer != (field.kind() == TableSchema.ColumnKind.INT)
                    || integer
                            && (field.bits() == null
                                    || !List.of(8, 16, 32, 64).contains(field.bits()))
                    || !integer
                            && field.kind() != TableSchema.ColumnKind.STRING
                            && field.kind() != TableSchema.ColumnKind.LOCSTRING) {
                throw unsupported(name + " has an unsupported type or cardinality");
            }
            for (int i = 0; i < count; i++) {
                var column = columns.get(index(count == 1 ? name : name + "[" + i + "]"));
                if (!column.kind().equals(integer ? "integer" : "string")
                        || column.interpretation() != null && !rawPhysical(column)
                        || column.isId()) {
                    throw unsupported(name + " is not a plain matching column");
                }
            }
            return field;
        }

        /** Reviewed layouts may keep a raw physical integer wider than the provider candidate. */
        private boolean rawPhysical(Db2Types.Column column) {
            return reviewedLayout
                    && "raw-physical-integer-v1".equals(column.interpretation().policy());
        }

        void integerField(String name, int count) {
            field(name, count, true);
        }

        void textField(String name) {
            field(name, 1, false);
        }

        int index(String field) {
            int found = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(field)) {
                    if (found != -1) {
                        throw unsupported("Duplicate column " + field);
                    }
                    found = i;
                }
            }
            if (found < 0) {
                throw unsupported("Missing column " + field);
            }
            return found;
        }

        String text(Db2Types.Row row, String field) {
            if (row.values().size() != columns.size()) {
                throw invalid("Row width differs from schema");
            }
            int at = index(field);
            if (row.textIssues() != null
                    && row.textIssues().stream().anyMatch(i -> i.columnIndex() == at)) {
                throw invalid("Invalid localized talent text");
            }
            return row.values().get(at);
        }

        long number(Db2Types.Row row, String field) {
            try {
                String raw = text(row, field);
                long value = Long.parseLong(raw);
                if (!Long.toString(value).equals(raw)) {
                    throw invalid("Noncanonical integer " + field);
                }
                return value;
            } catch (NumberFormatException e) {
                throw invalid("Invalid integer " + field);
            }
        }

        List<Long> array(Db2Types.Row row, String field, int count) {
            var values = new ArrayList<Long>();
            for (int i = 0; i < count; i++) {
                values.add(number(row, field + "[" + i + "]"));
            }
            return List.copyOf(values);
        }

        Long optionalPositive(Db2Types.Row row, String name) {
            Long value = optionalPositiveOrZero(row, name);
            return value == null || value == 0 ? null : value;
        }

        Long optionalPositiveOrZero(Db2Types.Row row, String name) {
            try {
                integerField(name, 1);
                long value = number(row, name);
                return value >= 0 && value <= 0xffffffffL ? value : null;
            } catch (Projection ignored) {
                return null;
            }
        }

        Projection unsupported(String why) {
            return new Projection(
                    TalentTypes.State.UNSUPPORTED,
                    "TALENTS_SCHEMA_UNSUPPORTED",
                    this.name + ": " + why);
        }
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var inner = new CompletableFuture<T>();
        synchronized (gate) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Talent handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var registration = lifetime.onCancel(request::cancel)) {
                                request.check();
                                inner.complete(action.apply(request));
                            } catch (Throwable e) {
                                inner.completeExceptionally(e);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                inner.completeExceptionally(new IllegalStateException("Talent queries busy", e));
            }
        }
        var outer = new CompletableFuture<T>();
        inner.whenComplete(
                (v, e) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (e == null) {
                                        outer.complete(v);
                                    } else {
                                        outer.completeExceptionally(e);
                                    }
                                }));
        return outer;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            Throwable cancellationFailure = null;
            try {
                lifetime.cancel();
            } catch (Throwable error) {
                cancellationFailure = error;
            }
            var failure = cancellationFailure;
            var spellClosing = spellTexts.closeAsync();
            worker.shutdown();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                            spellClosing.join();
                            if (failure == null) {
                                closing.complete(null);
                            } else {
                                closing.completeExceptionally(failure);
                            }
                        } catch (Throwable e) {
                            closing.completeExceptionally(e);
                        }
                    });
            return closing.copy();
        }
    }
}
