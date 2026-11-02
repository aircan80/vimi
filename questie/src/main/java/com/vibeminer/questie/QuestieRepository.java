package com.vibeminer.questie;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.Progress;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import com.vibeminer.questie.data.QuestieDataHandle;
import com.vibeminer.questie.data.QuestieDataRepository;
import com.vibeminer.questie.data.QuestieDataTypes.EntityRecord;
import com.vibeminer.questie.data.QuestieDataTypes.PreparedVersion;
import com.vibeminer.questie.sources.QuestieSourceRepository;
import com.vibeminer.questie.sources.QuestieSourceRepository.Release;
import com.vibeminer.questie.sources.QuestieSourceRepository.ReleaseCheck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Independent Questie application layer: original downloads, immutable preparations and local
 * queries.
 */
public final class QuestieRepository implements AutoCloseable {
    private final QuestieSourceRepository sources;
    private final QuestieDataRepository data;
    private int active;
    private boolean closed;
    private CompletableFuture<Void> closing;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    private QuestieRepository(QuestieSourceRepository sources, QuestieDataRepository data) {
        this.sources = sources;
        this.data = data;
    }

    public static CompletableFuture<QuestieRepository> open(Path directory, Cancellation token) {
        var result = new CompletableFuture<QuestieRepository>();
        Thread.startVirtualThread(
                () -> {
                    QuestieSourceRepository source = null;
                    try {
                        token.check();
                        source =
                                QuestieSourceRepository.open(directory.resolve("sources"), token)
                                        .join();
                        token.check();
                        var repository =
                                new QuestieRepository(
                                        source,
                                        new QuestieDataRepository(directory.resolve("derived")));
                        if (!result.complete(repository)) {
                            repository.closeAsync();
                        }
                    } catch (Throwable failure) {
                        if (source != null) {
                            source.closeAsync();
                        }
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    public CompletableFuture<ReleaseCheck> checkForUpdates(Cancellation token) {
        return call(() -> sources.refresh(token));
    }

    public CompletableFuture<List<Release>> releases(Cancellation token) {
        return call(() -> sources.releases(token));
    }

    public CompletableFuture<Release> release(String tag, Cancellation token) {
        return call(() -> sources.release(tag, token));
    }

    public CompletableFuture<List<SourceSnapshot>> sourceSnapshots(Cancellation token) {
        return call(() -> sources.snapshots(token));
    }

    public CompletableFuture<List<PreparedVersion>> versions(Cancellation token) {
        return call(() -> data.listVersions(token));
    }

    public CompletableFuture<SourceSnapshot> download(
            Release release, Flavor flavor, Consumer<Progress> progress, Cancellation token) {
        return call(() -> sources.acquire(release, flavor, progress, token));
    }

    public CompletableFuture<SourceSnapshot> downloadLatest(
            Flavor flavor, Consumer<Progress> progress, Cancellation token) {
        return call(
                () ->
                        latest(token)
                                .thenCompose(
                                        release ->
                                                sources.acquire(release, flavor, progress, token)));
    }

    public CompletableFuture<PreparedVersion> prepare(
            String sourceId, Consumer<Progress> progress, Cancellation token) {
        return call(
                () ->
                        sources.snapshots(token)
                                .thenCompose(
                                        values -> {
                                            var source =
                                                    values.stream()
                                                            .filter(
                                                                    value ->
                                                                            value.id()
                                                                                    .equals(
                                                                                            sourceId))
                                                            .findFirst()
                                                            .orElseThrow(
                                                                    () ->
                                                                            new IllegalArgumentException(
                                                                                    "Unknown Questie source snapshot"));
                                            return sources.verify(source, token)
                                                    .thenCompose(
                                                            valid -> {
                                                                if (!valid) {
                                                                    throw new IllegalStateException(
                                                                            "Retained Questie source is damaged");
                                                                }
                                                                return data.prepare(
                                                                        source, progress, token);
                                                            });
                                        }));
    }

    public CompletableFuture<PreparedVersion> downloadAndPrepareLatest(
            Flavor flavor, Consumer<Progress> progress, Cancellation token) {
        return call(
                () ->
                        latest(token)
                                .thenCompose(
                                        release ->
                                                sources.acquire(
                                                        release,
                                                        flavor,
                                                        value ->
                                                                progress.accept(
                                                                        value.stage()
                                                                                        == QuestieTypes
                                                                                                .Stage
                                                                                                .READY
                                                                                ? new Progress(
                                                                                        QuestieTypes
                                                                                                .Stage
                                                                                                .VERIFYING,
                                                                                        value
                                                                                                .done(),
                                                                                        value
                                                                                                .total(),
                                                                                        value
                                                                                                .unit(),
                                                                                        "Original Questie package retained; preparing local data")
                                                                                : value),
                                                        token))
                                .thenCompose(source -> data.prepare(source, progress, token)));
    }

    private CompletableFuture<Release> latest(Cancellation token) {
        return sources.refresh(token)
                .thenApply(
                        check -> {
                            if (check.latest() == null) {
                                throw new IllegalStateException(
                                        "No Questie release available: " + check.message());
                            }
                            return check.latest();
                        });
    }

    public CompletableFuture<QuestieDataHandle> openVersion(String versionId, Cancellation token) {
        return call(() -> data.open(versionId, token));
    }

    public record FieldChange(
            String field,
            boolean presentBefore,
            Object before,
            boolean presentAfter,
            Object after) {}

    public record EntityComparison(
            String leftVersion,
            String rightVersion,
            EntityKind kind,
            long id,
            String locale,
            boolean presentBefore,
            boolean presentAfter,
            boolean sameInterpretation,
            List<FieldChange> changes,
            String scope) {
        public EntityComparison {
            changes = List.copyOf(changes);
        }
    }

    /** Field changes describe recorded static snapshots, never additions/removals from the game. */
    public CompletableFuture<EntityComparison> compareEntity(
            String leftId,
            String rightId,
            EntityKind kind,
            long id,
            String locale,
            Cancellation token) {
        return call(
                () ->
                        CompletableFuture.supplyAsync(
                                () -> {
                                    token.check();
                                    try (var a = data.open(leftId, token).join();
                                            var b = data.open(rightId, token).join()) {
                                        var left = a.version();
                                        var right = b.version();
                                        Optional<EntityRecord>
                                                before = a.lookup(kind, id, locale, token).join(),
                                                after = b.lookup(kind, id, locale, token).join();
                                        Map<String, Object>
                                                oldFields =
                                                        before.map(EntityRecord::fields)
                                                                .orElse(Map.of()),
                                                newFields =
                                                        after.map(EntityRecord::fields)
                                                                .orElse(Map.of());
                                        var names = new TreeSet<String>();
                                        names.addAll(oldFields.keySet());
                                        names.addAll(newFields.keySet());
                                        var changes = new ArrayList<FieldChange>();
                                        for (String name : names) {
                                            token.check();
                                            if (oldFields.containsKey(name)
                                                            != newFields.containsKey(name)
                                                    || !java.util.Objects.equals(
                                                            oldFields.get(name),
                                                            newFields.get(name))) {
                                                changes.add(
                                                        new FieldChange(
                                                                name,
                                                                oldFields.containsKey(name),
                                                                oldFields.get(name),
                                                                newFields.containsKey(name),
                                                                newFields.get(name)));
                                            }
                                        }
                                        return new EntityComparison(
                                                leftId,
                                                rightId,
                                                kind,
                                                id,
                                                locale,
                                                before.isPresent(),
                                                after.isPresent(),
                                                left.flavor() == right.flavor()
                                                        && left.schemaProfile()
                                                                .equals(right.schemaProfile()),
                                                changes,
                                                "Static corrected QuestieDB values; character-dependent corrections are not evaluated");
                                    }
                                },
                                command -> Thread.startVirtualThread(command)));
    }

    private <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> action) {
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Questie repository closed"));
            }
            if (active >= 64) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Questie operation limit reached"));
            }
            active++;
        }
        try {
            var result = action.get();
            result.whenComplete((value, error) -> finish());
            return result.copy();
        } catch (Throwable failure) {
            finish();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private synchronized void finish() {
        if (--active == 0 && closed) {
            drained.complete(null);
        }
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closed = true;
        if (active == 0) {
            drained.complete(null);
        }
        closing =
                drained.handle((unused, previous) -> null)
                        .thenCompose(
                                unused ->
                                        CompletableFuture.allOf(
                                                data.closeAsync(), sources.closeAsync()));
        return closing.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
