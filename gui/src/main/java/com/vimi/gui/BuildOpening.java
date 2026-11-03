package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.Builds;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** One app-owned metadata opening. Observer cancellation cannot release admission or bypass drain. */
public final class BuildOpening {
    @FunctionalInterface
    public interface Opener {
        CompletableFuture<BuildTypes.BuildOpenResult> open(BuildInfo info, BuildTypes.BuildRef ref,
                Cancellation token);
    }

    private final Opener builds;
    private final Observer observer;

    public interface Observer {
        default CompletableFuture<Void> started(BuildInfo info, BuildTypes.BuildRef ref) {
            return CompletableFuture.completedFuture(null);
        }
        default CompletableFuture<Void> finished(BuildInfo info, BuildTypes.BuildOpenResult result,
                Throwable failure) {
            return CompletableFuture.completedFuture(null);
        }
    }
    private final Object gate = new Object();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private Operation operation;
    private boolean closed;

    private static final class Operation {
        final BuildTypes.BuildRef build;
        final BuildInfo info;
        final Cancellation cancellation = new Cancellation();
        final CompletableFuture<BuildTypes.BuildOpenResult> result = new CompletableFuture<>();
        volatile Throwable cancellationFailure;
        Operation(BuildInfo info, BuildTypes.BuildRef build) { this.info = info; this.build = build; }
        void cancel() {
            try { cancellation.cancel(); }
            catch (Throwable failure) { cancellationFailure = failure; }
        }
    }

    public BuildOpening(Builds builds) { this(builds, new Observer() {}); }

    public BuildOpening(Builds builds, Observer observer) {
        this((info, ref, token) -> builds.open(ref, token), observer);
        Objects.requireNonNull(builds);
    }

    public BuildOpening(Opener builds, Observer observer) {
        this.builds = Objects.requireNonNull(builds);
        this.observer = Objects.requireNonNull(observer);
    }

    /** Prefer the unique current CDN evidence, otherwise require one unambiguous saved CDN config. */
    public CompletableFuture<BuildTypes.BuildOpenResult> open(BuildInfo build, String region) {
        Objects.requireNonNull(build);
        Objects.requireNonNull(region);
        List<BuildInfo.RegionEvidence> evidence = build.observations().stream()
                .filter(item -> item.region().equalsIgnoreCase(region)).toList();
        if (evidence.isEmpty()) throw new IllegalArgumentException("No observed CDN config for region " + region);
        var current = evidence.stream().filter(BuildInfo.RegionEvidence::current).toList();
        if (!current.isEmpty()) evidence = current;
        if (evidence.stream().map(BuildInfo.RegionEvidence::cdnConfig).distinct().count() != 1) {
            throw new IllegalArgumentException("Region has multiple observed CDN configs; select exact evidence");
        }
        return open(build, evidence.getFirst());
    }

    /** Uses the selected catalog evidence exactly; no route or CDN configuration is inferred. */
    public CompletableFuture<BuildTypes.BuildOpenResult> open(BuildInfo build, BuildInfo.RegionEvidence evidence) {
        Objects.requireNonNull(build);
        Objects.requireNonNull(evidence);
        if (!build.observations().contains(evidence)) {
            throw new IllegalArgumentException("Region evidence does not belong to this catalog build");
        }
        var requested = new BuildTypes.BuildRef(build.product(),build.config(),evidence.cdnConfig(),evidence.region());
        Operation accepted;
        synchronized (gate) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Build opening closed"));
            if (operation != null) return CompletableFuture.failedFuture(new IllegalStateException("BUILD_OPEN_ACTIVE"));
            accepted = new Operation(build, requested);
            operation = accepted;
        }
        try { Thread.startVirtualThread(() -> execute(accepted)); }
        catch (Throwable failure) { finish(accepted,null,failure); }
        return accepted.result.copy();
    }

    private void execute(Operation accepted) {
        BuildTypes.BuildOpenResult result = null;
        Throwable failure = null;
        try {
            accepted.cancellation.check();
            observer.started(accepted.info, accepted.build).join();
            accepted.cancellation.check();
            result = Objects.requireNonNull(builds.open(accepted.info,accepted.build,accepted.cancellation),
                    "Build opener returned no future").join();
            accepted.cancellation.check();
            validate(accepted.build,result);
        } catch (Throwable error) { failure = unwrap(error); }
        try {
            observer.finished(accepted.info, result, failure).join();
        } catch (Throwable reporting) {
            if (failure == null) failure = unwrap(reporting);
            else failure.addSuppressed(unwrap(reporting));
        }
        finish(accepted,result,failure);
    }

    private static void validate(BuildTypes.BuildRef requested, BuildTypes.BuildOpenResult result) {
        if (result == null || result.state() == null || !requested.equals(result.build())
                || (result.state() == BuildTypes.BuildOpenState.OPEN) != (result.opened() != null)
                || result.opened() != null && (!requested.equals(result.opened().build())
                        || result.opened().metadata() == null)) {
            throw new IllegalStateException("BUILD_OPEN_RESULT_INVALID: opener returned inconsistent metadata identity/state");
        }
    }

    private void finish(Operation accepted, BuildTypes.BuildOpenResult result, Throwable failure) {
        if (accepted.cancellationFailure != null) {
            if (failure == null) failure = accepted.cancellationFailure;
            else failure.addSuppressed(accepted.cancellationFailure);
        }
        boolean completeClose;
        synchronized (gate) {
            operation = null;
            completeClose = closed;
        }
        // Retire resource work before invoking arbitrary observer continuations.
        if (completeClose) drained.complete(null);
        if (failure == null) accepted.result.complete(result);
        else accepted.result.completeExceptionally(failure);
    }

    public boolean active() { synchronized (gate) { return operation != null; } }

    public void cancel() {
        Operation pending;
        synchronized (gate) { pending = operation; }
        if (pending != null) pending.cancel();
    }

    public CompletableFuture<Void> closeAsync() {
        Operation pending;
        synchronized (gate) { closed = true; pending = operation; }
        if (pending != null) pending.cancel();
        else drained.complete(null);
        return drained.copy();
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
