package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.TableTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Per-NPC creature model choices for drawn spawn markers, resolved from the opened build's client
 * creature tables with {@link CreatureModelPolicy}. Nothing is drawn from these yet.
 *
 * <p>One daemon worker resolves one NPC at a time; the FX side owns the queue and a bounded cache.
 * A changed source revision cancels the running lookup, empties the queue and the cache, and drops
 * late results. Model file names come from metadata only (listfile), never from payload reads.
 */
final class WorldSpawnModels implements AutoCloseable {
    static final int CACHE = 4096, QUEUE = 512;

    /** Resolves one NPC on the worker. Must not download or prepare data. */
    @FunctionalInterface
    interface Resolver {
        Resolved resolve(long npcId, Cancellation token) throws Exception;
    }

    /**
     * One NPC's outcome: the policy choice, the qualified model's listfile path if known, and the
     * source the chain was read from.
     */
    record Resolved(long npcId, CreatureModelPolicy.Choice choice, String modelPath, String source) {
        Resolved {
            Objects.requireNonNull(choice);
            Objects.requireNonNull(source);
        }
    }

    /** Model outcome counts per state and reason over a set of markers; {@code pending} are resolving. */
    record Counts(Map<CreatureModelPolicy.State, Map<CreatureModelPolicy.Reason, Integer>> outcomes, int pending) {
        Counts {
            var copy = new EnumMap<CreatureModelPolicy.State, Map<CreatureModelPolicy.Reason, Integer>>(
                    CreatureModelPolicy.State.class);
            outcomes.forEach((state, reasons) -> copy.put(state, Map.copyOf(reasons)));
            outcomes = java.util.Collections.unmodifiableMap(copy);
        }

        int count(CreatureModelPolicy.State state) {
            return outcomes.getOrDefault(state, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final Resolver resolver;
    private final Supplier<Object> revision;
    private final Executor fx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "world-spawn-models");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Resolved> cache = new LinkedHashMap<>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Resolved> eldest) {
            return size() > CACHE;
        }
    };
    private final LinkedHashSet<Long> queued = new LinkedHashSet<>();
    private final List<LongConsumer> listeners = new ArrayList<>();
    private Object cachedRevision;
    private Cancellation token = new Cancellation();
    private long generation;
    private Long resolving;
    private long resolvingGeneration;
    private boolean closed;

    WorldSpawnModels(Resolver resolver, Supplier<Object> revision, Executor fx) {
        this.resolver = Objects.requireNonNull(resolver);
        this.revision = Objects.requireNonNull(revision);
        this.fx = Objects.requireNonNull(fx);
    }

    /** Production resolver: data-only creature chain plus a metadata-only listfile name. */
    static Resolver build(Supplier<BuildCreatures> creatures, Supplier<BuildFiles> files, String build) {
        return (npcId, token) -> {
            String source = "client Creature → CreatureDisplayInfo → CreatureModelData · build "
                    + (build == null || build.isBlank() ? "unknown" : build) + " · policy "
                    + CreatureModelPolicy.POLICY;
            var handle = creatures.get();
            if (handle == null)
                return new Resolved(npcId, CreatureModelPolicy.unknown("Creature data is not open for this build"),
                        null, source);
            if (npcId > 0xffffffffL)
                return new Resolved(npcId, CreatureModelPolicy.unknown("NPC ID is outside the client creature range"),
                        null, source);
            var choice = CreatureModelPolicy.choose(
                    handle.getData(npcId, TableTypes.Priority.INTERACTIVE, token).join());
            token.check();
            return new Resolved(npcId, choice, choice.modelFileDataId() == null ? null
                    : path(files.get(), choice.modelFileDataId(), token), source);
        };
    }

    private static String path(BuildFiles files, long fileDataId, Cancellation token) {
        if (files == null) return null;
        try (var cursor = files.listMetadata(
                new FileTypes.FileFilter(fileDataId, fileDataId, null, null, null, null), token)) {
            for (var file : cursor.next(1, token).join()) {
                if (file.fileDataId() == fileDataId && file.path() != null && !file.path().isBlank())
                    return file.path();
            }
            return null;
        } catch (UnsupportedOperationException unsupported) {
            return null;
        }
    }

    /** Called on the FX side with the NPC ID whenever a new outcome is cached. */
    void listen(LongConsumer listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Cached outcome for the current revision. FX side. */
    Optional<Resolved> cached(long npcId) {
        sync();
        return Optional.ofNullable(cache.get(npcId));
    }

    /** Queues NPCs without an outcome, up to the queue bound. FX side. */
    void request(Collection<Long> npcIds) {
        sync();
        if (closed) return;
        for (long id : npcIds) {
            if (queued.size() >= QUEUE) break;
            boolean running = resolving != null && resolving == id && resolvingGeneration == generation;
            if (!cache.containsKey(id) && !running) queued.add(id);
        }
        pump();
    }

    /** Moves one NPC to the front of the queue, for example a clicked marker. FX side. */
    void prioritize(long npcId) {
        sync();
        if (closed || cache.containsKey(npcId)
                || resolving != null && resolving == npcId && resolvingGeneration == generation) return;
        var rest = new ArrayList<>(queued);
        rest.remove(npcId);
        queued.clear();
        queued.add(npcId);
        for (var id : rest) {
            if (queued.size() >= QUEUE) break;
            queued.add(id);
        }
        pump();
    }

    /** Outcome counts over spawn NPC IDs (one entry per marker). FX side. */
    Counts count(Collection<Long> npcIds) {
        sync();
        var outcomes = new EnumMap<CreatureModelPolicy.State, Map<CreatureModelPolicy.Reason, Integer>>(
                CreatureModelPolicy.State.class);
        int pending = 0;
        for (long id : npcIds) {
            var resolved = cache.get(id);
            if (resolved == null) {
                pending++;
                continue;
            }
            outcomes.computeIfAbsent(resolved.choice().state(),
                            ignored -> new EnumMap<>(CreatureModelPolicy.Reason.class))
                    .merge(resolved.choice().reason(), 1, Integer::sum);
        }
        return new Counts(outcomes, pending);
    }

    private void sync() {
        Object current = revision.get();
        if (Objects.equals(current, cachedRevision)) return;
        cachedRevision = current;
        generation++;
        token.cancel();
        token = new Cancellation();
        cache.clear();
        queued.clear();
    }

    private void pump() {
        if (closed || resolving != null || queued.isEmpty()) return;
        var iterator = queued.iterator();
        long id = iterator.next();
        iterator.remove();
        resolving = id;
        long request = generation;
        resolvingGeneration = request;
        var lookup = token;
        try {
            worker.execute(() -> {
                Resolved result;
                try {
                    result = resolver.resolve(id, lookup);
                } catch (Throwable failure) {
                    result = lookup.isCanceled() ? null : new Resolved(id, CreatureModelPolicy.unknown(
                            "Model lookup failed: " + message(failure)), null, "client creature tables");
                }
                var published = result;
                fx.execute(() -> finish(id, request, published));
            });
        } catch (RejectedExecutionException shutDown) {
            resolving = null;
        }
    }

    private void finish(long id, long request, Resolved result) {
        resolving = null;
        if (closed) return;
        if (request == generation && result != null && result.npcId() == id) {
            cache.put(id, result);
            for (var listener : List.copyOf(listeners)) listener.accept(id);
        }
        pump();
    }

    private static String message(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException))
            failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        token.cancel();
        queued.clear();
        cache.clear();
        listeners.clear();
        worker.shutdownNow();
    }
}
