package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One bounded off-caller cancellation lane; no callback or waiting occurs in metadata methods. */
final class WorldStreamProducerCancellation {
    final class Producer {
        private final Slot slot;

        private Producer(Slot slot) {
            this.slot = slot;
        }

        Registration onStopping(Runnable action) {
            Objects.requireNonNull(action);
            synchronized (lock) {
                if (current != slot || slot.producerDone || slot.registered) {
                    throw new IllegalStateException("Expired or duplicate producer cancellation");
                }
                slot.registered = true;
                slot.action = action;
                schedule(slot);
                return new Registration(slot);
            }
        }

        /** Triggered by this tile's cancel, independently of session-wide stop. */
        void stop() {
            synchronized (lock) {
                slot.stopping = true;
                if (current == slot) {
                    schedule(slot);
                }
            }
        }

        void finished() {
            synchronized (lock) {
                if (current != slot || slot.producerDone) {
                    return;
                }
                slot.producerDone = true;
                slot.sealed = true;
                if (!slot.scheduled) {
                    slot.action = null;
                }
                lock.notifyAll();
            }
        }

        boolean retired() {
            synchronized (lock) {
                return slot.retired();
            }
        }
    }

    final class Registration implements AutoCloseable {
        private final Slot slot;

        private Registration(Slot slot) {
            this.slot = slot;
        }

        @Override
        public void close() {
            synchronized (lock) {
                slot.sealed = true;
                if (!slot.scheduled) {
                    slot.action = null;
                }
                lock.notifyAll();
            }
        }
    }

    private static final class Slot {
        Runnable action;
        boolean registered, sealed, stopping, scheduled, running, callbackDone, producerDone;

        boolean retired() {
            return producerDone && (!scheduled || callbackDone);
        }
    }

    private final Object lock = new Object();
    private Slot current;
    private boolean stopping, started, drained;
    private IOException failure;

    Producer begin() {
        synchronized (lock) {
            if (stopping || current != null && !current.retired()) {
                throw new IllegalStateException("Producer/cancellation ownership is still active");
            }
            current = new Slot();
            return new Producer(current);
        }
    }

    /** Session stop/failure shares the same one-shot scheduling as per-producer cancel. */
    void stopSession() {
        synchronized (lock) {
            stopping = true;
            if (current != null) {
                schedule(current);
            }
            lock.notifyAll();
        }
    }

    boolean drained() {
        synchronized (lock) {
            return drained;
        }
    }

    IOException failure() {
        synchronized (lock) {
            return failure;
        }
    }

    /** Called on the ONE registered cancellation worker; no per-registration watcher. */
    void work() throws InterruptedException {
        work(() -> false);
    }

    void work(BooleanSupplier ownerStopping) throws InterruptedException {
        Objects.requireNonNull(ownerStopping);
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("Cancellation lane already started");
            }
            started = true;
        }
        while (true) {
            // Only the configured lifecycle owner's cheap stop flag is queried, outside locks.
            if (ownerStopping.getAsBoolean()) {
                stopSession();
            }
            Slot slot;
            Runnable action;
            synchronized (lock) {
                if (current != null
                        && current.scheduled
                        && !current.running
                        && !current.callbackDone) {
                    slot = current;
                    slot.running = true;
                    action = slot.action;
                } else if (stopping && (current == null || current.retired())) {
                    drained = true;
                    return;
                } else {
                    lock.wait(20);
                    continue;
                }
            }
            IOException error = null;
            try {
                action.run();
            } catch (Throwable failure) {
                error = WorldStreamLaunchOwner.Handle.detached(failure);
            } finally {
                synchronized (lock) {
                    slot.action = null;
                    slot.callbackDone = true;
                    slot.running = false;
                    if (error != null) {
                        failure = error;
                        stopping = true;
                    }
                    lock.notifyAll();
                }
            }
            if (error != null) {
                // Let the outer Session mark failure/request close, independently of producer wait.
                return;
            }
        }
    }

    private void schedule(Slot slot) {
        if ((stopping || slot.stopping) && slot.registered && !slot.sealed && !slot.scheduled) {
            slot.scheduled = true;
            lock.notifyAll();
        }
    }
}
