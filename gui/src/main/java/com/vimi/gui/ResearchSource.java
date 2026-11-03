package com.vimi.gui;

import static com.vimi.gui.ResearchPresentation.file;
import static com.vimi.gui.ResearchPresentation.matches;
import static com.vimi.gui.ResearchPresentation.state;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.ConceptQueries;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.ItemQuery;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vimi.gui.ResearchPresentation.Page;
import com.vimi.gui.ResearchPresentation.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Application adapter; all calls run on private workers, and file cursors are scoped to a request.
 */
public final class ResearchSource implements ResearchDataSource {
    private final BuildPreparation.Job job;

    public ResearchSource(BuildPreparation.Job job) {
        this.job = job;
    }

    public String locale() {
        return job.locale();
    }

    private NativePreparationWork work() {
        if (!(job.work() instanceof NativePreparationWork work))
            throw new IllegalStateException("No native workspace");
        return work;
    }

    public CompletableFuture<Page> page(
            String area, String term, String after, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    token.check();
                    var work = work();
                    if (area.equals("Files")) return files(work.filesHandle(), term, after, token);
                    if (area.equals("Tables"))
                        return new Page(
                                job.snapshot().tables().stream()
                                        .map(ResearchPresentation::table)
                                        .filter(row -> matches(row, term))
                                        .toList(),
                                null,
                                "Known table outcomes · includes protected and absent tables");
                    if (area.equals("Items"))
                        return items(new com.vibeminer.concepts.items.ItemGridQuery(), token)
                                .join();
                    if (work.creaturesHandle() == null)
                        throw new IllegalStateException("Creature data unavailable; see Overview");
                    var page =
                            work.creaturesHandle()
                                    .list(
                                            creatureQuery(term, after),
                                            TableTypes.Priority.INTERACTIVE,
                                            token)
                                    .join();
                    return new Page(
                            page.entries() == null
                                    ? List.of()
                                    : page.entries().stream()
                                            .map(ResearchPresentation::creature)
                                            .toList(),
                            page.next(),
                            state(page.state()) + message(page.message()));
                },
                command -> Thread.startVirtualThread(command));
    }

    @Override
    public CompletableFuture<Page> items(
            com.vibeminer.concepts.items.ItemGridQuery query, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var service = work().itemsHandle();
                    if (service == null)
                        throw new IllegalStateException("Item data unavailable; see Overview");
                    var page = service.grid(query, TableTypes.Priority.INTERACTIVE, token).join();
                    String next =
                            query.offset() + page.rows().size() < page.matches()
                                    ? Integer.toString(query.offset() + query.limit())
                                    : null;
                    return new Page(
                            page.rows().stream().map(ResearchPresentation::item).toList(),
                            next,
                            state(page.state())
                                    + " · "
                                    + page.message()
                                    + " · "
                                    + page.available()
                                    + " prepared readable items",
                            page.matches(),
                            page.options());
                },
                command -> Thread.startVirtualThread(command));
    }

    static ItemQuery itemQuery(String term, String after) {
        Long exact = ConceptQueries.tryParseId(term);
        return new ItemQuery(exact == null ? term : "", exact, 100, after);
    }

    static CreatureTypes.CreatureQuery creatureQuery(String term, String after) {
        Long exact = ConceptQueries.tryParseId(term);
        return new CreatureTypes.CreatureQuery(exact == null ? term : "", exact, 100, after);
    }

    static Page files(
            com.vibeminer.files.BuildFiles files, String term, String after, Cancellation token) {
        Long exact = ConceptQueries.tryParseId(term);
        long from = after == null ? 0 : Long.parseLong(after);
        if (exact != null && (exact < 0 || exact > 0xffffffffL))
            return new Page(List.of(), null, "FileDataID is outside the unsigned 32-bit range");
        var filter =
                new FileTypes.FileFilter(
                        exact == null ? from : exact,
                        exact == null ? 0xffffffffL : exact,
                        null,
                        null,
                        null,
                        null);
        var rows = new ArrayList<Row>();
        int scanned = 0;
        long last = from;
        boolean ended = false;
        try (var cursor = files.listMetadata(filter, token)) {
            while (rows.size() < 100 && scanned < 2000) {
                token.check();
                var batch = cursor.next(100, token).join();
                if (batch.isEmpty()) {
                    ended = true;
                    break;
                }
                for (var file : batch) {
                    token.check();
                    last = file.fileDataId();
                    scanned++;
                    var row = file(file);
                    if (exact != null || matches(row, term)) rows.add(row);
                    if (rows.size() == 100) break;
                }
            }
        }
        return new Page(
                rows,
                ended || last == 0xffffffffL || exact != null ? null : Long.toString(last + 1),
                scanned
                        + " file records assessed · selected locale "
                        + files.locale()
                        + (scanned == 2000 ? " · continue to search further" : ""));
    }

    public record ItemDetail(
            com.vibeminer.concepts.items.ItemInspection inspection,
            com.vibeminer.concepts.effects.EffectsTypes.Tooltip effects,
            String effectsIssue) {}

    private CompletableFuture<ItemDetail> itemDetail(
            NativePreparationWork work, long id, Cancellation token) {
        return work.itemsHandle()
                .inspect(id, TableTypes.Priority.INTERACTIVE, token)
                .thenCompose(
                        inspection ->
                                CompletableFuture.supplyAsync(
                                                () ->
                                                        work.effectsHandle()
                                                                .tooltip(
                                                                        id,
                                                                        TableTypes.Priority
                                                                                .INTERACTIVE,
                                                                        token)
                                                                .join(),
                                                command -> Thread.startVirtualThread(command))
                                        .handle(
                                                (effects, failure) -> {
                                                    token.check();
                                                    return new ItemDetail(
                                                            inspection,
                                                            effects,
                                                            failure == null
                                                                    ? null
                                                                    : "Optional item effect text"
                                                                          + " unavailable");
                                                }));
    }

    public CompletableFuture<?> detail(String area, Row row, Cancellation token) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            token.check();
                            var work = work();
                            return switch (area) {
                                case "Files", "Tables" ->
                                        CompletableFuture.completedFuture(row.source());
                                case "Items" -> itemDetail(work, row.id(), token);
                                default ->
                                        work.creaturesHandle()
                                                .getData(
                                                        row.id(),
                                                        TableTypes.Priority.INTERACTIVE,
                                                        token);
                            };
                        },
                        command -> Thread.startVirtualThread(command))
                .thenCompose(value -> value);
    }

    public CompletableFuture<?> media(String area, Row row, Cancellation token) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            token.check();
                            var work = work();
                            return area.equals("Items")
                                    ? work.itemsHandle()
                                            .get(row.id(), TableTypes.Priority.INTERACTIVE, token)
                                    : work.creaturesHandle()
                                            .get(row.id(), TableTypes.Priority.INTERACTIVE, token);
                        },
                        command -> Thread.startVirtualThread(command))
                .thenCompose(value -> value);
    }

    public CompletableFuture<TableRows> tableRows(
            long id, Long after, String identity, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    token.check();
                    var tables = work().tablesHandle();
                    var schema =
                            tables.schema(
                                            TableTypes.Ref.id(id),
                                            TableTypes.Priority.INTERACTIVE,
                                            token)
                                    .join();
                    var page =
                            tables.page(
                                            new TableTypes.PageRequest(
                                                    TableTypes.Ref.id(id),
                                                    after == null ? -1 : after,
                                                    100,
                                                    List.of(),
                                                    identity),
                                            TableTypes.Priority.INTERACTIVE,
                                            token)
                                    .join();
                    return new TableRows(schema, page);
                },
                command -> Thread.startVirtualThread(command));
    }

    public CompletableFuture<com.vibeminer.formats.images.BlpImageTypes.Result> icon(
            long id, Cancellation token) {
        return CompletableFuture.supplyAsync(
                        () -> work().icon(id, token), command -> Thread.startVirtualThread(command))
                .thenCompose(value -> value);
    }

    public record TableRows(TableTypes.SchemaResult schema, TableTypes.Page page) {}

    private static String message(String text) {
        return text == null ? "" : " · " + text;
    }
}
