package com.vimi.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The renderer-child count the GPU UI proofs wait on. A child that has exited but is not yet
 * reaped (a Linux zombie: alive, no readable command) is not a live renderer; a child that stays
 * alive with no readable command still fails. Authored process handles only, no processes.
 */
public final class RendererChildrenTest {
    private RendererChildrenTest() {}

    public static void main(String[] args) {
        var renderer = new Child(11, Optional.of("/jdk/bin/" + RendererChildren.LAUNCHER), true);
        var other = new Child(12, Optional.of("/usr/bin/sh"), true);
        var gone = new Child(13, Optional.of("/jdk/bin/" + RendererChildren.LAUNCHER), false);
        var zombie = new Child(14, Optional.empty(), true);
        // The reaper collects the zombie while the count waits for it.
        zombie.exit.completeAsync(() -> {
            zombie.alive = false;
            return zombie;
        }, CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.MILLISECONDS));
        check(RendererChildren.live(Stream.of(renderer, other, gone, zombie), Duration.ofSeconds(2))
                        .equals(Set.of(11L)),
                "Only the running launcher child counts; an exiting zombie is not a renderer");

        var exitedQuietly = new Child(15, Optional.empty(), true);
        exitedQuietly.exit.completeExceptionally(new IllegalStateException("no exit signal"));
        exitedQuietly.alive = false;
        check(RendererChildren.live(Stream.of(exitedQuietly), Duration.ofMillis(50)).isEmpty(),
                "A child that is gone counts as gone even without an exit signal");

        var stuck = new Child(16, Optional.empty(), true);
        try {
            RendererChildren.live(Stream.of(stuck), Duration.ofMillis(50));
            throw new IllegalStateException("A live child with no readable command was dropped");
        } catch (AssertionError expected) {
            check(expected.getMessage().equals("Cannot read the command of live child 16"),
                    "A child still alive after the grace fails: " + expected.getMessage());
        }
        check(RendererChildren.EXIT_GRACE.compareTo(Duration.ofSeconds(5)) < 0,
                "The grace stays well inside the proofs' 20 s waits");
        System.out.println(
                "Renderer children: running launcher counted, exiting zombie and other commands"
                        + " dropped, stuck unreadable child fails passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** An authored child process: its command, whether it is alive, and its exit. */
    private static final class Child implements ProcessHandle {
        final long pid;
        final Optional<String> command;
        volatile boolean alive;
        final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();

        Child(long pid, Optional<String> command, boolean alive) {
            this.pid = pid;
            this.command = command;
            this.alive = alive;
            if (!alive) exit.complete(this);
        }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return Stream.empty(); }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exit; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { return false; }
        @Override public boolean destroyForcibly() { return false; }
        @Override public boolean isAlive() { return alive; }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }

        @Override
        public Info info() {
            return new Info() {
                @Override public Optional<String> command() { return command; }
                @Override public Optional<String> commandLine() { return command; }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return Optional.empty(); }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }

        @Override public String toString() { return "Child " + pid + " " + List.of(command); }
    }
}
