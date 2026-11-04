package com.vibeminer.concepts.world;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** One pool-wide active preparation and one latest descriptor, including all build sources. */
final class TerrainTextureTasks {
    private final Thread worker, canceler;
    private WorldTerrainTextureSource.Request active, pending, cancelTarget, canceling;
    private boolean stopping, stopCanceler;
    private Throwable cancellationFailure;
    private CompletableFuture<Void> closing;

    TerrainTextureTasks() {
        worker = Thread.ofPlatform().daemon().name("terrain-texture-source").start(this::work);
        canceler =
                Thread.ofPlatform().daemon().name("terrain-texture-cancel").start(this::cancelLoop);
    }

    void submit(WorldTerrainTextureSource.Request request) {
        WorldTerrainTextureSource.Request retired = null;
        Throwable rejected = null;
        synchronized (this) {
            if (stopping || request.source.closed) {
                rejected = new IllegalStateException("Terrain texture source closed");
            } else if (request.canceled || request.caller.isCanceled()) {
                rejected = new CancellationException("Texture batch already canceled");
            } else {
                retired = pending;
                pending = request;
                if (active != null) {
                    markCanceled(active);
                }
                notifyAll();
            }
        }
        if (rejected != null) {
            retire(request, rejected);
            finishSource(request.source);
            return;
        }
        if (retired != null) {
            retire(retired, new CancellationException("Superseded texture batch"));
            finishSource(retired.source);
        }
    }

    synchronized void cancel(WorldTerrainTextureSource.Request request) {
        markCanceled(request);
        notifyAll();
    }

    private void markCanceled(WorldTerrainTextureSource.Request request) {
        request.canceled = true;
        if (active == request && !request.cancelQueued) {
            request.cancelQueued = true;
            // Worker will not promote another active identity until this callback drain finishes.
            cancelTarget = request;
            notifyAll();
        }
    }

    void closeSource(WorldTerrainTextureSource source) {
        WorldTerrainTextureSource.Request retired = null;
        synchronized (this) {
            if (active != null && active.source == source) {
                markCanceled(active);
            }
            if (pending != null && pending.source == source) {
                retired = pending;
                pending = null;
            }
            notifyAll();
        }
        if (retired != null) {
            retire(retired, new CancellationException("Texture source closed"));
        }
        finishSource(source);
    }

    private void work() {
        while (true) {
            WorldTerrainTextureSource.Request request;
            synchronized (this) {
                while (pending == null && !stopping) {
                    await();
                }
                if (pending == null) {
                    return;
                }
                request = pending;
                pending = null;
                active = request;
            }
            try {
                request.check();
                var result = request.source.load(request);
                try {
                    request.check();
                    if (!request.future.complete(result)) {
                        result.close();
                    }
                } catch (Throwable failure) {
                    result.close();
                    throw failure;
                }
            } catch (Throwable failure) {
                request.future.completeExceptionally(failure);
            } finally {
                request.registration.close();
                request.registration = null;
                synchronized (this) {
                    while (cancelTarget == request || canceling == request) {
                        await();
                    }
                    if (active == request) {
                        active = null;
                    }
                    notifyAll();
                }
                if (request.cancellationFailure == null) {
                    request.drained.complete(null);
                } else {
                    request.drained.completeExceptionally(request.cancellationFailure);
                }
                finishSource(request.source);
            }
        }
    }

    private void cancelLoop() {
        while (true) {
            WorldTerrainTextureSource.Request target;
            synchronized (this) {
                while (cancelTarget == null && !stopCanceler) {
                    await();
                }
                if (cancelTarget == null) {
                    return;
                }
                target = cancelTarget;
                cancelTarget = null;
                canceling = target;
            }
            try {
                target.token.cancel();
            } catch (RuntimeException | Error failure) {
                target.cancellationFailure = failure;
                target.source.cancellationFailure = failure;
                synchronized (this) {
                    if (cancellationFailure == null) {
                        cancellationFailure = failure;
                    }
                }
            } finally {
                synchronized (this) {
                    canceling = null;
                    notifyAll();
                }
            }
        }
    }

    CompletableFuture<Void> closeAsync() {
        WorldTerrainTextureSource.Request retired;
        synchronized (this) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            stopping = true;
            if (active != null) {
                markCanceled(active);
            }
            retired = pending;
            pending = null;
            notifyAll();
        }
        if (retired != null) {
            retire(retired, new CancellationException("Texture pool closed"));
            finishSource(retired.source);
        }
        Thread.startVirtualThread(
                () -> {
                    try {
                        worker.join();
                        synchronized (this) {
                            stopCanceler = true;
                            notifyAll();
                        }
                        canceler.join();
                        if (cancellationFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancellationFailure);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }

    private void finishSource(WorldTerrainTextureSource source) {
        synchronized (this) {
            if (!source.closed
                    || (active != null && active.source == source)
                    || (pending != null && pending.source == source)
                    || (cancelTarget != null && cancelTarget.source == source)
                    || (canceling != null && canceling.source == source)) {
                return;
            }
        }
        if (source.cancellationFailure == null) {
            source.closing.complete(null);
        } else {
            source.closing.completeExceptionally(source.cancellationFailure);
        }
    }

    private static void retire(WorldTerrainTextureSource.Request request, Throwable failure) {
        request.canceled = true;
        request.registration.close();
        request.registration = null;
        request.future.completeExceptionally(failure);
        request.drained.complete(null);
    }

    private void await() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Texture worker interrupted", interrupted);
        }
    }
}
