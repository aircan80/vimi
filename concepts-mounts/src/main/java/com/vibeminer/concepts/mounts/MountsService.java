package com.vibeminer.concepts.mounts;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.models.TableProjection;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Lazy bounded mount metadata. Asset preparation is independent of catalogue requests. */
@SuppressWarnings("try")
public final class MountsService implements BuildMounts {
    private final TableProjection tables;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("mount-projection").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Cancellation lifetime = new Cancellation();
    private CompletableFuture<Void> closing;
    private MountTypes.Catalog catalog;
    private TableProjection.Data mounts, links;
    private boolean linksLoaded;
    private List<String> linkIssues = List.of();

    public MountsService(BuildTables tables) {
        this.tables = new TableProjection(tables);
    }

    @Override
    public CompletableFuture<MountTypes.Catalog> catalog(Cancellation token) {
        return submit(token, this::initialize);
    }

    private MountTypes.Catalog initialize(Cancellation token) {
        if (catalog != null) {
            return catalog;
        }
        var issues = new ArrayList<String>();
        var result = new ArrayList<MountTypes.Mount>();
        TableProjection.Data data;
        try {
            data = tables.read("Mount", 20000, token, issues);
        } catch (IllegalArgumentException unavailable) {
            return new MountTypes.Catalog(
                    MountTypes.State.UNAVAILABLE, List.of(), issues, unavailable.getMessage());
        }
        for (var row : data.rows()) {
            token.check();
            String name = text(data, row, "Name_lang", issues);
            result.add(
                    new MountTypes.Mount(
                            row.id(),
                            name == null || name.isBlank() ? "Mount #" + row.id() : name,
                            text(data, row, "Description_lang", issues),
                            text(data, row, "SourceText_lang", issues),
                            number(data, row, "SourceSpellID", issues),
                            number(data, row, "SourceTypeEnum", issues),
                            number(data, row, "MountTypeID", issues),
                            number(data, row, "PlayerConditionID", issues),
                            number(data, row, "Flags", issues)));
        }
        result.sort(
                Comparator.comparing(MountTypes.Mount::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(MountTypes.Mount::id));
        token.check();
        mounts = data;
        catalog =
                new MountTypes.Catalog(
                        issues.isEmpty() ? MountTypes.State.READY : MountTypes.State.PARTIAL,
                        result,
                        issues,
                        result.isEmpty() ? "No readable mount records in this build." : null);
        return catalog;
    }

    @Override
    public CompletableFuture<MountTypes.Overview> overview(long mountId, Cancellation token) {
        checkId(mountId);
        return submit(
                token,
                request -> {
                    var current = initialize(request);
                    var mount =
                            current.mounts().stream()
                                    .filter(m -> m.id() == mountId)
                                    .findFirst()
                                    .orElse(null);
                    var issues = new ArrayList<>(current.issues());
                    if (mount == null) {
                        return new MountTypes.Overview(
                                MountTypes.State.UNAVAILABLE,
                                null,
                                List.of(),
                                issues,
                                "Mount record unavailable.");
                    }
                    var variants = new ArrayList<MountTypes.Variant>();
                    if (!linksLoaded) {
                        var notes = new ArrayList<String>();
                        TableProjection.Data next = null;
                        try {
                            next = tables.read("MountXDisplay", 50000, request, notes);
                            next.reference("MountID", "Mount");
                            next.reference("CreatureDisplayInfoID", "CreatureDisplayInfo");
                        } catch (IllegalArgumentException unavailable) {
                            TableProjection.note(
                                    notes,
                                    "Mount display links unavailable: " + unavailable.getMessage());
                            next = null;
                        }
                        request.check();
                        links = next;
                        linkIssues = List.copyOf(notes);
                        linksLoaded = true;
                    }
                    issues.addAll(linkIssues);
                    if (links != null) {
                        for (var row : links.rows()) {
                            request.check();
                            try {
                                if (links.number(row, "MountID") == mountId) {
                                    long display = links.number(row, "CreatureDisplayInfoID");
                                    if (display > 0 && display <= 0xffffffffL) {
                                        variants.add(
                                                new MountTypes.Variant(
                                                        row.id(),
                                                        display,
                                                        number(
                                                                links,
                                                                row,
                                                                "PlayerConditionID",
                                                                issues),
                                                        "MountXDisplay"));
                                    }
                                }
                            } catch (IllegalArgumentException invalid) {
                                TableProjection.note(
                                        issues,
                                        "Some mount display rows are unreadable or unsupported.");
                            }
                        }
                    }
                    if (variants.isEmpty() && mounts.has("CreatureDisplayInfoID")) {
                        try {
                            mounts.reference("CreatureDisplayInfoID", "CreatureDisplayInfo");
                            var row =
                                    mounts.rows().stream()
                                            .filter(r -> r.id() == mountId)
                                            .findFirst()
                                            .orElseThrow();
                            long display = mounts.number(row, "CreatureDisplayInfoID");
                            if (display > 0 && display <= 0xffffffffL) {
                                variants.add(
                                        new MountTypes.Variant(mountId, display, null, "Mount"));
                            }
                        } catch (IllegalArgumentException invalid) {
                            TableProjection.note(
                                    issues, "Legacy mount display relationship is unavailable.");
                        }
                    }
                    variants.sort(
                            Comparator.comparingLong(MountTypes.Variant::recordId)
                                    .thenComparingLong(MountTypes.Variant::displayId));
                    return new MountTypes.Overview(
                            issues.isEmpty() ? MountTypes.State.READY : MountTypes.State.PARTIAL,
                            mount,
                            variants,
                            issues,
                            variants.isEmpty()
                                    ? "No readable display variants are recorded for this mount."
                                    : null);
                });
    }

    private static String text(
            TableProjection.Data data, Db2Types.Row row, String field, List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            return data.text(row, field);
        } catch (IllegalArgumentException invalid) {
            TableProjection.note(issues, data.name() + "." + field + " is unavailable.");
            return null;
        }
    }

    private static Long number(
            TableProjection.Data data, Db2Types.Row row, String field, List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            return data.number(row, field);
        } catch (IllegalArgumentException invalid) {
            TableProjection.note(issues, data.name() + "." + field + " is unavailable.");
            return null;
        }
    }

    private static void checkId(long id) {
        if (id <= 0 || id > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid mount ID");
        }
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var result = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Mount handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var owner = lifetime.onCancel(request::cancel)) {
                                request.check();
                                result.complete(action.apply(request));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                result.completeExceptionally(new IllegalStateException("Mount queries busy", full));
            }
        }
        var published = new CompletableFuture<T>();
        result.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        published.complete(value);
                                    } else {
                                        published.completeExceptionally(failure);
                                    }
                                }));
        return published;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        lifetime.cancel();
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        closing.complete(null);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(failure);
                    }
                });
        return closing.copy();
    }
}
