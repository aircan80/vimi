package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded asynchronous union preserving priority and all evidence, never owning repositories. */
@SuppressWarnings("try")
public final class LocationsService implements BuildLocations {
    private final List<LocationInput> inputs;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().daemon().name("location-projection").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<LocationTypes.Entity, LocationTypes.Locations> cache =
            new LinkedHashMap<>(16, .75f, true);
    private int cachedPoints;
    private CompletableFuture<Void> closing;

    public LocationsService(List<LocationInput> inputs) {
        if (inputs.size() > 16
                || inputs.stream().map(LocationInput::source).distinct().count() != inputs.size()) {
            throw new IllegalArgumentException("Too many or duplicate location inputs");
        }
        this.inputs =
                inputs.stream().sorted(Comparator.comparing(i -> i.source().provider())).toList();
    }

    @Override
    public CompletableFuture<LocationTypes.Locations> locations(
            LocationTypes.Entity entity, Cancellation token) {
        Objects.requireNonNull(entity);
        return submit(token, request -> locate(entity, request));
    }

    private LocationTypes.Locations locate(LocationTypes.Entity entity, Cancellation token) {
        token.check();
        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
            return LocationAdapters.item(entity);
        }
        var cached = cache.get(entity);
        if (cached != null) {
            return cached;
        }
        var result = new java.util.LinkedHashSet<LocationTypes.Location>();
        var issues = new ArrayList<String>();
        boolean assessed = false;
        if (inputs.isEmpty()) {
            LocationAdapters.issue(issues, "No prepared location source is selected.");
        }
        for (var input : inputs) {
            token.check();
            try {
                var found = input.reader().locations(entity, token);
                token.check();
                if (!found.entity().equals(entity)
                        || found.locations().stream()
                                .anyMatch(
                                        p ->
                                                !p.entity().equals(entity)
                                                        || !p.evidence()
                                                                .source()
                                                                .equals(input.source()))) {
                    throw new IllegalArgumentException("Location source identity mismatch");
                }
                assessed |=
                        found.state() == LocationTypes.State.READY
                                || found.state() == LocationTypes.State.PARTIAL;
                for (String issue : found.issues()) {
                    LocationAdapters.issue(issues, input.source().provider() + ": " + issue);
                }
                for (var location : found.locations()) {
                    if (result.size() >= 8192) {
                        LocationAdapters.issue(
                                issues,
                                "Combined location bound reached; remaining points are not assessed.");
                        break;
                    }
                    result.add(location);
                }
                if (found.state() != LocationTypes.State.READY && found.issues().isEmpty()) {
                    LocationAdapters.issue(
                            issues, input.source().provider() + ": " + found.state());
                }
            } catch (Exception failure) {
                token.check();
                LocationAdapters.issue(
                        issues,
                        input.source().provider()
                                + ": locations unavailable; other sources retained.");
            }
        }
        var state =
                !result.isEmpty() || assessed
                        ? LocationAdapters.state(issues)
                        : LocationTypes.State.UNKNOWN;
        var found = new LocationTypes.Locations(state, entity, List.copyOf(result), issues);
        token.check();
        if (state == LocationTypes.State.READY || state == LocationTypes.State.PARTIAL) {
            while (!cache.isEmpty()
                    && (cache.size() >= 64 || cachedPoints + result.size() > 16_384)) {
                var iterator = cache.entrySet().iterator();
                cachedPoints -= iterator.next().getValue().locations().size();
                iterator.remove();
            }
            cache.put(entity, found);
            cachedPoints += found.locations().size();
        }
        return found;
    }

    @Override
    public CompletableFuture<LocationTypes.Pickups> pickups(long questId, Cancellation token) {
        LocationTypes.validId(questId, false);
        return submit(
                token,
                request -> {
                    var grouped =
                            new LinkedHashMap<LocationTypes.Entity, List<LocationTypes.Starter>>();
                    var issues = new ArrayList<String>();
                    boolean assessed = false;
                    for (var input : inputs) {
                        request.check();
                        try {
                            var found = input.reader().starters(questId, request);
                            request.check();
                            if (found.questId() != questId
                                    || found.starters().stream()
                                            .anyMatch(
                                                    s ->
                                                            s.questId() != questId
                                                                    || !s.evidence()
                                                                            .source()
                                                                            .equals(
                                                                                    input
                                                                                            .source()))) {
                                throw new IllegalArgumentException(
                                        "Starter source identity mismatch");
                            }
                            assessed |=
                                    found.state() == LocationTypes.State.READY
                                            || found.state() == LocationTypes.State.PARTIAL;
                            for (String issue : found.issues()) {
                                LocationAdapters.issue(
                                        issues, input.source().provider() + ": " + issue);
                            }
                            for (var starter : found.starters()) {
                                if (grouped.size() >= LocationAdapters.MAX_STARTERS
                                        && !grouped.containsKey(starter.entity())) {
                                    LocationAdapters.issue(
                                            issues, "Combined starter bound reached.");
                                    break;
                                }
                                var group =
                                        grouped.computeIfAbsent(
                                                starter.entity(), ignored -> new ArrayList<>());
                                if (!group.contains(starter) && group.size() < 64) {
                                    group.add(starter);
                                } else if (group.size() >= 64) {
                                    LocationAdapters.issue(
                                            issues, "Starter evidence bound reached.");
                                }
                            }
                            if (found.state() != LocationTypes.State.READY
                                    && found.issues().isEmpty()) {
                                LocationAdapters.issue(
                                        issues, input.source().provider() + ": " + found.state());
                            }
                        } catch (Exception failure) {
                            request.check();
                            LocationAdapters.issue(
                                    issues,
                                    input.source().provider()
                                            + ": quest starters unavailable; other sources retained.");
                        }
                    }
                    var result = new ArrayList<LocationTypes.Pickup>();
                    for (var entry : grouped.entrySet()) {
                        request.check();
                        var places = locate(entry.getKey(), request);
                        if (places.state() != LocationTypes.State.READY) {
                            LocationAdapters.issue(
                                    issues,
                                    "Some starter locations require additional data or conversion.");
                        }
                        result.add(
                                new LocationTypes.Pickup(entry.getKey(), entry.getValue(), places));
                    }
                    if (inputs.isEmpty()) {
                        LocationAdapters.issue(
                                issues, "No prepared quest-starter source is selected.");
                    }
                    var state =
                            !result.isEmpty() || assessed
                                    ? LocationAdapters.state(issues)
                                    : LocationTypes.State.UNKNOWN;
                    return new LocationTypes.Pickups(state, questId, result, issues);
                });
    }

    @Override
    public CompletableFuture<LocationTypes.Frames> frames(Cancellation token) {
        return submit(
                token,
                request -> {
                    var result = new ArrayList<LocationTypes.WorldFrame>();
                    var issues = new ArrayList<String>();
                    for (var input : inputs) {
                        request.check();
                        try {
                            var found = input.reader().frames(request);
                            request.check();
                            if (found.frames().stream()
                                    .anyMatch(f -> !f.evidence().source().equals(input.source()))) {
                                throw new IllegalArgumentException(
                                        "Frame source identity mismatch");
                            }
                            for (var frame : found.frames()) {
                                if (result.size() >= 8192) {
                                    LocationAdapters.issue(
                                            issues, "Combined map frame bound reached.");
                                    break;
                                }
                                result.add(frame);
                            }
                            if (found.state() != LocationTypes.State.UNSUPPORTED) {
                                for (String issue : found.issues()) {
                                    LocationAdapters.issue(
                                            issues, input.source().provider() + ": " + issue);
                                }
                            }
                        } catch (Exception failure) {
                            request.check();
                            LocationAdapters.issue(
                                    issues,
                                    input.source().provider() + ": map frames unavailable.");
                        }
                    }
                    return new LocationTypes.Frames(
                            result.isEmpty()
                                    ? LocationTypes.State.UNKNOWN
                                    : LocationAdapters.state(issues),
                            result,
                            issues);
                });
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var inner = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Location handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var registration = lifetime.onCancel(request::cancel)) {
                                request.check();
                                inner.complete(action.apply(request));
                            } catch (Throwable failure) {
                                inner.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                inner.completeExceptionally(
                        new IllegalStateException("Location queries busy", failure));
            }
        }
        var outer = new CompletableFuture<T>();
        inner.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        outer.complete(value);
                                    } else {
                                        outer.completeExceptionally(failure);
                                    }
                                }));
        return outer;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable failure = null;
        try {
            lifetime.cancel();
        } catch (Throwable error) {
            failure = error;
        }
        var cancellationFailure = failure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        if (cancellationFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancellationFailure);
                        }
                    } catch (Throwable error) {
                        closing.completeExceptionally(error);
                    }
                });
        return closing.copy();
    }
}
