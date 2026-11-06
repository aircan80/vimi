package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Lazy bounded projection; closing drains this component without closing borrowed tables. */
@SuppressWarnings("try")
public final class RacesService implements BuildRaces {
    private static final int MAX_RACES = 1024,
            MAX_CLASSES = 128,
            MAX_LINKS = 16384,
            MAX_ABILITIES = 8192;
    private final BuildTables tables;
    private final RaceMedia media;
    private final RaceVoices voices;
    private final RaceAppearance appearance;
    private final RaceIdleAnimations idle;
    // Voices run beside the projection worker: a first voice catalog scans SoundKitEntry and
    // must not hold back the selected model's surface and textures.
    private final ThreadPoolExecutor worker = worker("race-projection"),
            appearanceWorker = worker("race-appearance"),
            voiceWorker = worker("race-voices");

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private final Cancellation lifetime = new Cancellation();
    private CompletableFuture<Void> closing;
    private Data races, links, classes, abilities;
    private boolean initialized, cardsLoaded;
    private final List<String> coreIssues = new ArrayList<>(), cardIssues = new ArrayList<>();
    private List<RaceTypes.Race> catalog;

    public RacesService(BuildTables tables) {
        this(tables, null, null);
    }

    public RacesService(
            BuildTables tables,
            com.vibeminer.files.BuildFiles files,
            com.vibeminer.generated.GeneratedStore generated) {
        this.tables = Objects.requireNonNull(tables);
        media = new RaceMedia(this, tables, files, generated);
        voices = new RaceVoices(this, media, tables, files, generated);
        appearance = new RaceAppearance(this, media, tables, files, generated);
        idle = new RaceIdleAnimations(media, files);
    }

    @Override
    public CompletableFuture<RaceMediaTypes.Idle> idle(
            long raceId, long chrModelId, Cancellation token) {
        return submit(token, request -> idle.assess(raceId, chrModelId, request));
    }

    @Override
    public CompletableFuture<RaceMediaTypes.Variants> modelVariants(
            long raceId, Cancellation token) {
        return submit(token, request -> media.variants(raceId, request));
    }

    @Override
    public CompletableFuture<RaceMediaTypes.Preview> preview(
            long raceId, long classId, long chrModelId, Cancellation token) {
        return submit(token, request -> media.preview(raceId, classId, chrModelId, request));
    }

    @Override
    public CompletableFuture<RaceVoiceTypes.Catalog> voices(
            long raceId, long classId, int sex, Cancellation token) {
        return submit(voiceWorker, token, request -> voices.catalog(raceId, classId, sex, request));
    }

    @Override
    public CompletableFuture<RaceVoiceTypes.Audio> voice(
            long raceId, long classId, int sex, long entryId, Cancellation token) {
        return submit(
                voiceWorker,
                token,
                request -> voices.audio(raceId, classId, sex, entryId, request));
    }

    @Override
    public CompletableFuture<RaceAppearanceTypes.Result> appearance(
            long raceId, long classId, long modelId, Cancellation token) {
        return submit(token, request -> appearance.prepare(raceId, classId, modelId, request))
                .thenCompose(
                        prepared ->
                                prepared.finished() != null
                                        ? CompletableFuture.completedFuture(prepared.finished())
                                        : submit(
                                                appearanceWorker,
                                                token,
                                                request -> appearance.render(prepared, request)));
    }

    @Override
    public CompletableFuture<RaceCustomizationTypes.Catalog> customizations(
            long raceId, long classId, long modelId, Cancellation token) {
        return submit(
                token,
                request -> {
                    if (!appearance.available()) {
                        return new RaceCustomizationTypes.Catalog(
                                RaceTypes.State.UNAVAILABLE,
                                raceId,
                                classId,
                                modelId,
                                List.of(),
                                List.of(),
                                "Appearance preparation is unavailable for this workspace.");
                    }
                    return new RaceCustomization(media).catalog(raceId, classId, modelId, request);
                });
    }

    @Override
    public CompletableFuture<RaceAppearanceTypes.Result> appearance(
            RaceCustomizationTypes.Request selection, Cancellation token) {
        Objects.requireNonNull(selection);
        return submit(
                        token,
                        request ->
                                appearance.prepare(
                                        selection.raceId(),
                                        selection.classId(),
                                        selection.modelId(),
                                        request,
                                        new RaceCustomization(media).requested(selection, request)))
                .thenCompose(
                        prepared ->
                                prepared.finished() != null
                                        ? CompletableFuture.completedFuture(prepared.finished())
                                        : submit(
                                                appearanceWorker,
                                                token,
                                                request -> appearance.render(prepared, request)));
    }

    @Override
    public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) {
        return submit(
                token,
                request -> {
                    try {
                        initialize(request);
                        return new RaceTypes.Catalog(
                                state(coreIssues),
                                catalog,
                                coreIssues,
                                catalog.isEmpty()
                                        ? "No race records are readable in this build."
                                        : null);
                    } catch (Projection failure) {
                        return new RaceTypes.Catalog(
                                failure.state, List.of(), List.of(), failure.getMessage());
                    }
                });
    }

    @Override
    public CompletableFuture<RaceTypes.Overview> overview(long raceId, Cancellation token) {
        if (raceId < 1 || raceId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid race ID");
        }
        return submit(
                token,
                request -> {
                    try {
                        initialize(request);
                        var summary =
                                catalog.stream()
                                        .filter(race -> race.id() == raceId)
                                        .findFirst()
                                        .orElse(null);
                        if (summary == null) {
                            return failed(
                                    RaceTypes.State.UNAVAILABLE, "Race record is unavailable.");
                        }
                        var issues = new ArrayList<>(coreIssues);
                        var source =
                                races == null
                                        ? null
                                        : races.rows.stream()
                                                .filter(row -> row.id() == raceId)
                                                .findFirst()
                                                .orElse(null);
                        String lore = optionalText(races, source, "LoreDescription_lang", issues);
                        Long faction = optionalNumber(races, source, "FactionID", issues);
                        if (faction != null) {
                            try {
                                races.reference("FactionID", "FactionTemplate");
                            } catch (Projection unsupported) {
                                issue(issues, unsupported.getMessage());
                                faction = null;
                            }
                        }
                        String factionName =
                                faction == null || faction == 0
                                        ? null
                                        : faction(faction, request, issues);
                        var recorded = new ArrayList<RaceTypes.PlayerClass>();
                        for (long id : classIds(raceId, issues)) {
                            var row =
                                    classes == null
                                            ? null
                                            : classes.rows.stream()
                                                    .filter(item -> item.id() == id)
                                                    .findFirst()
                                                    .orElse(null);
                            String name = optionalText(classes, row, "Name_lang", issues);
                            if (name == null || name.isBlank()) {
                                name = "Class #" + id;
                                issue(issues, "Some class names are unavailable.");
                            }
                            recorded.add(new RaceTypes.PlayerClass(id, name));
                        }
                        recorded.sort(
                                Comparator.comparing(RaceTypes.PlayerClass::name)
                                        .thenComparingLong(RaceTypes.PlayerClass::id));
                        loadCards(request);
                        issues.addAll(cardIssues);
                        var cards = new ArrayList<RaceTypes.Ability>();
                        if (abilities != null) {
                            for (var row : abilities.rows) {
                                request.check();
                                try {
                                    if (abilities.number(row, "ChrRacesID") != raceId) {
                                        continue;
                                    }
                                    String name = optionalText(abilities, row, "Name_lang", issues);
                                    String description =
                                            optionalText(
                                                    abilities, row, "Description_lang", issues);
                                    Long icon = optionalNumber(abilities, row, "Icon", issues);
                                    if (cards.size() >= 128) {
                                        issue(
                                                issues,
                                                "Racial ability display exceeds 128 records; additional records are omitted.");
                                        break;
                                    }
                                    cards.add(
                                            new RaceTypes.Ability(
                                                    row.id(),
                                                    name == null || name.isBlank()
                                                            ? "Racial ability #" + row.id()
                                                            : name,
                                                    description,
                                                    icon == null || icon <= 0 ? null : icon,
                                                    order(abilities, row, "Order")));
                                } catch (Projection invalid) {
                                    issue(
                                            issues,
                                            "Some racial ability rows are unreadable or unsupported.");
                                }
                            }
                        }
                        cards.sort(
                                Comparator.comparingInt(RaceTypes.Ability::order)
                                        .thenComparingLong(RaceTypes.Ability::id));
                        return new RaceTypes.Overview(
                                state(issues),
                                summary,
                                lore,
                                faction,
                                factionName,
                                recorded,
                                cards,
                                issues,
                                null);
                    } catch (Projection failure) {
                        return failed(failure.state, failure.getMessage());
                    }
                });
    }

    private static RaceTypes.Overview failed(RaceTypes.State state, String message) {
        return new RaceTypes.Overview(
                state, null, null, null, null, List.of(), List.of(), List.of(), message);
    }

    private static RaceTypes.State state(List<String> issues) {
        return issues.isEmpty() ? RaceTypes.State.READY : RaceTypes.State.PARTIAL;
    }

    private void initialize(Cancellation token) {
        if (initialized) {
            return;
        }
        coreIssues.clear();
        // Build local values first so cancellation cannot cache an incomplete initialization.
        Data nextRaces = optionalRead("ChrRaces", MAX_RACES, token, coreIssues);
        Data nextLinks = optionalRead("CharBaseInfo", MAX_LINKS, token, coreIssues);
        Data nextClasses = optionalRead("ChrClasses", MAX_CLASSES, token, coreIssues);
        if (nextRaces == null && nextLinks == null) {
            throw new Projection(
                    RaceTypes.State.UNAVAILABLE, "Race and race/class tables are unavailable.");
        }
        if (nextLinks != null) {
            try {
                nextLinks.field("RaceID", true);
                nextLinks.field("ClassID", true);
                nextLinks.reference("RaceID", "ChrRaces");
                nextLinks.reference("ClassID", "ChrClasses");
            } catch (Projection unsupported) {
                issue(coreIssues, unsupported.getMessage());
                nextLinks = null;
            }
        }
        var ids = new java.util.TreeSet<Long>();
        if (nextRaces != null) {
            for (var row : nextRaces.rows) {
                if (row.id() > 0 && row.id() <= 0xffffffffL) {
                    ids.add(row.id());
                } else {
                    issue(coreIssues, "Some race IDs are outside the supported range.");
                }
            }
        }
        if (nextLinks != null) {
            for (var row : nextLinks.rows) {
                try {
                    long id = nextLinks.number(row, "RaceID");
                    if (id > 0 && id <= 0xffffffffL) {
                        ids.add(id);
                    }
                } catch (Projection invalid) {
                    issue(coreIssues, "Some race/class records are unreadable.");
                }
            }
        }
        var result = new ArrayList<RaceTypes.Race>();
        for (long id : ids) {
            token.check();
            if (result.size() >= MAX_RACES) {
                issue(
                        coreIssues,
                        "Race catalog exceeds display bounds; additional records are omitted.");
                break;
            }
            var row =
                    nextRaces == null
                            ? null
                            : nextRaces.rows.stream()
                                    .filter(value -> value.id() == id)
                                    .findFirst()
                                    .orElse(null);
            String name = optionalText(nextRaces, row, "Name_lang", coreIssues);
            if (name == null || name.isBlank()) {
                name = "Race #" + id;
                issue(coreIssues, "Some race names are unavailable.");
            }
            int count = classIds(nextLinks, id, coreIssues).size();
            result.add(
                    new RaceTypes.Race(id, name, order(nextRaces, row, "UiDisplayOrder"), count));
        }
        result.sort(
                Comparator.comparingInt(RaceTypes.Race::order)
                        .thenComparing(RaceTypes.Race::name)
                        .thenComparingLong(RaceTypes.Race::id));
        token.check();
        races = nextRaces;
        links = nextLinks;
        classes = nextClasses;
        catalog = List.copyOf(result);
        initialized = true;
    }

    private List<Long> classIds(long raceId, List<String> issues) {
        return classIds(links, raceId, issues);
    }

    private static List<Long> classIds(Data links, long raceId, List<String> issues) {
        var ids = new java.util.TreeSet<Long>();
        if (links != null) {
            for (var row : links.rows) {
                try {
                    if (links.number(row, "RaceID") == raceId) {
                        long id = links.number(row, "ClassID");
                        if (id > 0 && id <= 0xffffffffL) {
                            if (ids.size() < MAX_CLASSES || ids.contains(id)) {
                                ids.add(id);
                            } else {
                                issue(issues, "Some class links exceed display bounds.");
                            }
                        }
                    }
                } catch (Projection invalid) {
                    issue(issues, "Some race/class records are unreadable.");
                }
            }
        }
        return List.copyOf(ids);
    }

    private void loadCards(Cancellation token) {
        if (cardsLoaded) {
            return;
        }
        var data = optionalRead("ChrRaceRacialAbility", MAX_ABILITIES, token, cardIssues);
        if (data != null) {
            try {
                data.field("ChrRacesID", true);
                data.reference("ChrRacesID", "ChrRaces");
                data.field("Name_lang", false);
                data.field("Description_lang", false);
                data.field("Icon", true);
                data.field("Order", true);
            } catch (Projection unsupported) {
                issue(cardIssues, unsupported.getMessage());
                data = null;
            }
        }
        token.check();
        abilities = data;
        cardsLoaded = true;
    }

    private String faction(long id, Cancellation token, List<String> issues) {
        try {
            var template = schema("FactionTemplate", token, issues);
            template.field("Faction", true);
            template.reference("Faction", "Faction");
            var result =
                    tables.get(
                                    TableTypes.Ref.forName("FactionTemplate"),
                                    id,
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (result.outcome() != TableTypes.RowOutcome.FOUND || result.row() == null) {
                issue(issues, "Faction template #" + id + " is " + result.outcome() + ".");
                return null;
            }
            long factionId = template.number(result.row().row(), "Faction");
            var faction = schema("Faction", token, issues);
            faction.field("Name_lang", false);
            var row =
                    tables.get(
                                    TableTypes.Ref.forName("Faction"),
                                    factionId,
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (row.outcome() != TableTypes.RowOutcome.FOUND || row.row() == null) {
                issue(issues, "Faction name is " + row.outcome() + ".");
                return null;
            }
            return faction.text(row.row().row(), "Name_lang");
        } catch (Projection unavailable) {
            issue(issues, "Faction name unavailable: " + unavailable.getMessage());
            return null;
        }
    }

    private static int order(Data data, Db2Types.Row row, String field) {
        Long value = optionalNumber(data, row, field, new ArrayList<>());
        return value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : value.intValue();
    }

    private static Long optionalNumber(
            Data data, Db2Types.Row row, String field, List<String> issues) {
        if (data == null || row == null || !data.has(field)) {
            return null;
        }
        try {
            data.field(field, true);
            return data.number(row, field);
        } catch (Projection unavailable) {
            issue(issues, data.name + ": " + field + " is unavailable.");
            return null;
        }
    }

    private static String optionalText(
            Data data, Db2Types.Row row, String field, List<String> issues) {
        if (data == null || row == null || !data.has(field)) {
            return null;
        }
        try {
            data.field(field, false);
            return data.text(row, field);
        } catch (Projection unavailable) {
            issue(issues, data.name + ": " + field + " is unavailable.");
            return null;
        }
    }

    private static void issue(List<String> issues, String text) {
        if (!issues.contains(text) && issues.size() < 32) {
            issues.add(text);
        }
    }

    private Data optionalRead(String name, int bound, Cancellation token, List<String> issues) {
        try {
            return read(name, bound, token, issues);
        } catch (Projection unavailable) {
            issue(issues, unavailable.getMessage());
            return null;
        }
    }

    Data schema(String name, Cancellation token, List<String> issues) {
        token.check();
        var result =
                tables.schema(TableTypes.Ref.forName(name), TableTypes.Priority.INTERACTIVE, token)
                        .join();
        if (result.table().state() != TableTypes.State.READY
                        && result.table().state() != TableTypes.State.PARTIAL
                || result.schema() == null
                || result.columns() == null) {
            throw new Projection(RaceTypes.State.UNAVAILABLE, name + ": " + result.table().state());
        }
        if (result.table().state() == TableTypes.State.PARTIAL) {
            issue(issues, name + " is partial; readable records are retained.");
        }
        return new Data(name, result.columns(), result.schema(), List.of(), result.table());
    }

    Data read(String name, int bound, Cancellation token, List<String> issues) {
        return read(name, bound, token, issues, List.of());
    }

    /**
     * One row by ID; null when the table records it as absent. A row the store cannot answer for
     * now is unreadable, never a refusal.
     */
    Db2Types.Row row(Data data, long id, Cancellation token, List<String> issues) {
        var result =
                tables.get(
                                TableTypes.Ref.forName(data.name),
                                id,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (result.outcome() == TableTypes.RowOutcome.FOUND && result.row() != null) {
            return result.row().row();
        }
        if (result.outcome() == TableTypes.RowOutcome.ABSENT) {
            issue(issues, data.name + " #" + id + " is absent.");
            return null;
        }
        throw new Unreadable(data.name + " #" + id + " is " + result.outcome());
    }

    /** As {@link #read(String, int, Cancellation, List)}, over only the rows the filters keep. */
    Data read(
            String name,
            int bound,
            Cancellation token,
            List<String> issues,
            List<TableTypes.Filter> filters) {
        Data data = schema(name, token, issues);
        var rows = new ArrayList<Db2Types.Row>();
        long after = -1;
        String identity = null;
        while (true) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.forName(name),
                                            after,
                                            256,
                                            filters,
                                            identity),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (page.state() != TableTypes.PageState.OK) {
                throw new Unreadable(name + ": " + page.state());
            }
            if (identity == null) {
                identity = page.identity();
            } else if (!Objects.equals(identity, page.identity())) {
                throw new Unreadable(name + " changed during projection.");
            }
            for (var row : page.rows()) {
                if (rows.size() >= bound) {
                    throw new Projection(
                            RaceTypes.State.UNSUPPORTED, name + " exceeds projection bounds.");
                }
                rows.add(row.row());
            }
            if (page.nextAfter() == null) {
                break;
            }
            if (page.nextAfter() <= after) {
                throw new Projection(RaceTypes.State.INVALID, name + " cursor did not advance.");
            }
            after = page.nextAfter();
        }
        return new Data(name, data.columns, data.schema, List.copyOf(rows), data.entry);
    }

    static final class Data {
        final String name;
        final List<Db2Types.Column> columns;
        final TableSchema schema;
        final List<Db2Types.Row> rows;
        final TableTypes.Entry entry;

        boolean complete() {
            return entry.state() == TableTypes.State.READY
                    && entry.counts() != null
                    && entry.counts().declaredRows() >= 0
                    && entry.counts().readableRows() >= 0
                    && entry.counts().excludedSections() == 0
                    && entry.counts().excludedRecords() == 0
                    && entry.counts().declaredRows() == entry.counts().readableRows();
        }

        Data(
                String name,
                List<Db2Types.Column> columns,
                TableSchema schema,
                List<Db2Types.Row> rows,
                TableTypes.Entry entry) {
            this.entry = entry;
            this.name = name;
            this.columns = columns;
            this.schema = schema;
            this.rows = rows;
        }

        boolean has(String field) {
            return schema.columns().stream().anyMatch(column -> column.name().equals(field));
        }

        TableSchema.SchemaColumn field(String name, boolean integer) {
            return field(name, integer, 1);
        }

        TableSchema.SchemaColumn field(String name, boolean integer, int arrayLength) {
            var fields =
                    schema.columns().stream().filter(column -> column.name().equals(name)).toList();
            if (fields.size() != 1) {
                throw unsupported("Missing or ambiguous field " + name);
            }
            var field = fields.getFirst();
            boolean reviewedCard =
                    this.name.equals("ChrRaceRacialAbility")
                            && schema.layoutHashes().stream()
                                    .anyMatch(
                                            hash -> List.of("B6115535", "0FC0F0D5").contains(hash));
            if ((!field.verifiedName()
                            && !reviewedCard
                            && !RaceMedia.reviewedField(this.name, schema.layoutHashes(), name)
                            && !RaceAppearance.reviewedField(
                                    this.name, schema.layoutHashes(), name))
                    || field.arrayLength() != arrayLength
                    || integer != (field.kind() == TableSchema.ColumnKind.INT)
                    || integer
                            && (field.bits() == null
                                    || !List.of(8, 16, 32, 64).contains(field.bits()))
                    || !integer
                            && field.kind() != TableSchema.ColumnKind.LOCSTRING
                            && field.kind() != TableSchema.ColumnKind.STRING) {
                throw unsupported("Unsupported field " + name);
            }
            for (int element = 0; element < arrayLength; element++) {
                int at = index(arrayLength == 1 ? name : name + "[" + element + "]");
                var column = columns.get(at);
                if (!column.kind().equals(integer ? "integer" : "string")
                        || column.interpretation() != null
                                && !RaceAppearance.reviewedPhysicalInteger(
                                        this.name, schema.layoutHashes(), name, field, column)
                        || column.isId()) {
                    throw unsupported("Column mismatch " + name);
                }
            }
            return field;
        }

        void reference(String field, String table) {
            var foreign = field(field, true).foreignKey();
            if (foreign == null
                    || !foreign.table().equals(table)
                    || !foreign.column().equals("ID")) {
                throw unsupported("Unverified relationship " + field);
            }
        }

        int index(String field) {
            int at = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(field)) {
                    if (at != -1) {
                        throw unsupported("Ambiguous field " + field);
                    }
                    at = i;
                }
            }
            if (at < 0) {
                throw unsupported("Missing field " + field);
            }
            return at;
        }

        String text(Db2Types.Row row, String field) {
            int at = index(field);
            if (row.values().size() != columns.size()
                    || row.textIssues() != null
                            && row.textIssues().stream()
                                    .anyMatch(issue -> issue.columnIndex() == at)) {
                throw unsupported("Unreadable field " + field);
            }
            String value = row.values().get(at);
            if (value.length() > 32768) {
                throw unsupported("Text exceeds bound");
            }
            return value;
        }

        long number(Db2Types.Row row, String field) {
            try {
                String raw = text(row, field);
                long value = Long.parseLong(raw);
                if (!Long.toString(value).equals(raw)) {
                    throw unsupported("Noncanonical integer " + field);
                }
                return value;
            } catch (NumberFormatException failure) {
                throw unsupported("Invalid integer " + field);
            }
        }

        Projection unsupported(String message) {
            return new Projection(RaceTypes.State.UNSUPPORTED, name + ": " + message);
        }
    }

    static class Projection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final RaceTypes.State state;

        Projection(RaceTypes.State state, String message) {
            super(message);
            this.state = state;
        }
    }

    /** A page that could not be read now; unlike other refusals, asking again may succeed. */
    static final class Unreadable extends Projection {
        private static final long serialVersionUID = 1L;

        Unreadable(String message) {
            super(RaceTypes.State.UNAVAILABLE, message);
        }
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        return submit(worker, token, action);
    }

    private <T> CompletableFuture<T> submit(
            ThreadPoolExecutor pool, Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var inner = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Race handle closed"));
            }
            try {
                pool.execute(
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
                inner.completeExceptionally(new IllegalStateException("Race queries busy", e));
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

    /** Remembered table copies and results of every kind, for tests; read only when idle. */
    int retained() {
        return media.retained()
                + voices.retained()
                + appearance.rememberedCount()
                + idle.cachedCount();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (this) {
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
            worker.shutdown();
            appearanceWorker.shutdown();
            voiceWorker.shutdown();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            while (!worker.awaitTermination(1, TimeUnit.SECONDS)
                                    || !appearanceWorker.awaitTermination(1, TimeUnit.SECONDS)
                                    || !voiceWorker.awaitTermination(1, TimeUnit.SECONDS)) {}
                            // A retired handle that is still referenced holds no derived results.
                            media.forget();
                            voices.forget();
                            appearance.forget();
                            idle.forget();
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
