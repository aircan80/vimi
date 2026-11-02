package com.vibeminer.files;

import com.vibeminer.files.FileTypes.EnsureOutcome;
import com.vibeminer.files.FileTypes.EnsureProgress;
import com.vibeminer.files.FileTypes.EnsureResult;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileEnsured;
import com.vibeminer.files.FileTypes.FileRef;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One in-flight progress callback and one replaceable pending snapshot, final publication fenced.
 */
final class EnsureTally {
    private final Consumer<EnsureProgress> observer;
    private final Clock clock;
    private final long interval;
    private final List<FileEnsured> files = new ArrayList<>();
    private long done, total, bytesDone, bytesTotal, cdnDone, cdnCall, last = Long.MIN_VALUE;
    private boolean listed, draining, finished;
    private int call;
    private EnsureProgress pending;
    private CompletableFuture<Void> drained = new CompletableFuture<>();

    EnsureTally(Consumer<EnsureProgress> observer, Clock clock, Duration interval) {
        this.observer = observer;
        this.clock = clock;
        this.interval = interval.toMillis();
    }

    synchronized int add(FileRef ref, Long id, long bytes) {
        int index = files.size();
        files.add(new FileEnsured(ref, id, FileEnsureState.CANCELED, null));
        total++;
        bytesTotal += bytes;
        return index;
    }

    synchronized boolean settled(int index) {
        return files.get(index).state() != FileEnsureState.CANCELED;
    }

    synchronized void settle(int index, FileEnsureState state, String code, long bytes) {
        if (settled(index)) {
            throw new IllegalStateException("Ensure file settled twice");
        }
        var old = files.get(index);
        files.set(index, new FileEnsured(old.file(), old.fileDataId(), state, code));
        done++;
        if (state == FileEnsureState.STORED || state == FileEnsureState.ALREADY_STORED) {
            bytesDone += bytes;
        }
    }

    synchronized void listed() {
        listed = true;
    }

    synchronized int beginCall() {
        cdnDone += cdnCall;
        cdnCall = 0;
        return ++call;
    }

    void cdn(int id, long bytes) {
        synchronized (this) {
            if (id != call || bytes <= cdnCall) {
                return;
            }
            cdnCall = bytes;
        }
        offer();
    }

    private EnsureProgress snapshot() {
        return new EnsureProgress(done, total, bytesDone, bytesTotal, cdnDone + cdnCall, listed);
    }

    void offer() {
        synchronized (this) {
            if (observer == null || finished) {
                return;
            }
            long now = clock.millis();
            if (last != Long.MIN_VALUE && now - last < interval) {
                return;
            }
            last = now;
            queue(snapshot());
        }
    }

    private void queue(EnsureProgress snapshot) {
        pending = snapshot;
        if (draining) {
            return;
        }
        draining = true;
        Thread.startVirtualThread(
                () -> {
                    for (; ; ) {
                        EnsureProgress next;
                        synchronized (this) {
                            next = pending;
                            pending = null;
                            if (next == null) {
                                draining = false;
                                drained.complete(null);
                                return;
                            }
                        }
                        try {
                            observer.accept(next);
                        } catch (Throwable ignored) {
                            /* Public observers cannot fail durable outcomes. */
                        }
                    }
                });
    }

    EnsureResult finish(boolean canceled) {
        EnsureResult result;
        CompletableFuture<Void> wait;
        synchronized (this) {
            finished = true;
            var state = snapshot();
            boolean ok =
                    files.stream()
                            .allMatch(
                                    f ->
                                            f.state() == FileEnsureState.STORED
                                                    || f.state() == FileEnsureState.ALREADY_STORED);
            result =
                    new EnsureResult(
                            canceled
                                    ? EnsureOutcome.CANCELED
                                    : ok ? EnsureOutcome.COMPLETED : EnsureOutcome.INCOMPLETE,
                            state,
                            files);
            if (observer != null) {
                if (drained.isDone()) {
                    drained = new CompletableFuture<>();
                }
                queue(state);
            }
            wait = draining ? drained : CompletableFuture.completedFuture(null);
        }
        wait.join();
        return result;
    }
}
