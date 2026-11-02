package com.vimi.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Application-owned simulation. Its clock and outcomes are independent of popup visibility. */
public final class MockPreparationService implements AutoCloseable {
    public enum State { RUNNING, STOPPING, READY, FAILED, CANCELED }

    public static final List<String> STAGES = List.of(
            "Verifying configurations", "Preparing published download size",
            "Assessing retained content", "Publishing Overview");

    public static final class Job {
        private final BuildInfo build;
        private final String region;
        private final long generation;
        private State state = State.RUNNING;
        private int stage;
        private String problem = "";

        private Job(BuildInfo build, String region, long generation) {
            this.build = build;
            this.region = region;
            this.generation = generation;
        }

        public BuildInfo build() { return build; }
        public String region() { return region; }
        public long generation() { return generation; }
        public synchronized State state() { return state; }
        public synchronized int stage() { return stage; }
        public synchronized String problem() { return problem; }
        public synchronized boolean active() { return state == State.RUNNING || state == State.STOPPING; }
        public synchronized String summary() {
            return switch (state) {
                case RUNNING -> "Preparing · " + STAGES.get(stage);
                case STOPPING -> "Stopping…";
                case READY -> "Ready to open";
                case FAILED -> "Needs attention";
                case CANCELED -> "Opening canceled";
            };
        }
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService clock;
    private Consumer<Job> observer = job -> {};
    private long generation;

    /** Tests pass false and advance explicitly without timing or sleeping. */
    public MockPreparationService(boolean automatic) {
        clock = automatic ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mock-build-preparation");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public synchronized void observe(Consumer<Job> observer) {
        this.observer = observer;
    }

    public synchronized Job latest(BuildInfo build) {
        return jobs.values().stream().filter(job -> sameBuild(job.build(), build))
                .reduce((before, after) -> after).orElse(null);
    }

    public synchronized Job active() {
        return jobs.values().stream().filter(Job::active).findFirst().orElse(null);
    }

    public synchronized Job start(BuildInfo build, String region) {
        if (build.source() == BuildInfo.BuildSource.COMMUNITY) {
            throw new IllegalArgumentException("Community history has no verified source route");
        }
        String key = contextKey(build, region);
        Job existing = jobs.get(key);
        if (existing != null && existing.active()) {
            return existing;
        }
        if (active() != null) {
            throw new IllegalStateException("Another build is preparing");
        }
        Job job = new Job(build, region, ++generation);
        jobs.remove(key);
        jobs.put(key, job);
        observer.accept(job);
        schedule(job, 3500);
        return job;
    }

    private void schedule(Job job, long milliseconds) {
        if (clock != null) {
            clock.schedule(() -> {
                advance(job);
                if (job.active()) {
                    schedule(job, 3500);
                }
            }, milliseconds, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void advance(Job job) {
        if (!isCurrent(job)) {
            return;
        }
        synchronized (job) {
            if (job.state == State.STOPPING) {
                job.state = State.CANCELED;
            } else if (job.state == State.RUNNING) {
                if (job.stage == STAGES.size() - 1) {
                    job.state = State.READY;
                } else {
                    job.stage++;
                }
            } else {
                return;
            }
        }
        observer.accept(job);
    }

    public synchronized void cancel(Job job) {
        if (!isCurrent(job)) {
            return;
        }
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.STOPPING;
        }
        observer.accept(job);
        if (clock != null) {
            clock.schedule(() -> advance(job), 600, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void fail(Job job) {
        if (!isCurrent(job)) {
            return;
        }
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.state = State.FAILED;
            job.problem = "Simulated connection interruption. Your build selection is kept. Retry opening to try again.";
        }
        observer.accept(job);
    }

    private static String contextKey(BuildInfo build, String region) {
        return build.product() + ":" + build.version() + ":" + build.config() + ":" + region;
    }

    private static boolean sameBuild(BuildInfo first, BuildInfo second) {
        return first.product().equals(second.product()) && first.version().equals(second.version())
                && first.config().equals(second.config());
    }

    private boolean isCurrent(Job job) {
        return jobs.get(contextKey(job.build(), job.region())) == job;
    }

    @Override
    public void close() {
        if (clock != null) {
            clock.shutdownNow();
        }
    }
}
