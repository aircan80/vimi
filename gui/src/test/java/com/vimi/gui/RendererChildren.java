package com.vimi.gui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Live Java child processes of this JVM: the renderer children GpuPreviewService launches. */
final class RendererChildren {
    /** The launcher GpuPreviewService starts: {@code java.home/bin/java}, with .exe on Windows. */
    static final String LAUNCHER =
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";

    /**
     * How long a child whose command cannot be read may take to finish exiting. On Linux a child
     * that has exited stays a zombie, alive to {@link ProcessHandle#isAlive} with no readable
     * command, until the JDK's reaper collects it a few milliseconds later.
     */
    static final Duration EXIT_GRACE = Duration.ofSeconds(2);

    private RendererChildren() {}

    /**
     * Live children started with {@link #LAUNCHER}. A child whose command cannot be read is given
     * {@link #EXIT_GRACE} to finish exiting; one still alive after that fails the proof rather
     * than being dropped, so a renderer is never silently uncounted.
     */
    static Set<Long> live() {
        return live(ProcessHandle.current().children(), EXIT_GRACE);
    }

    static Set<Long> live(Stream<ProcessHandle> children, Duration grace) {
        return children.filter(ProcessHandle::isAlive)
                .filter(p -> launcher(p, grace))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static boolean launcher(ProcessHandle child, Duration grace) {
        var command = child.info().command();
        if (command.isEmpty()) {
            if (exits(child, grace)) return false;
            throw new AssertionError("Cannot read the command of live child " + child.pid());
        }
        return LAUNCHER.equalsIgnoreCase(Path.of(command.get()).getFileName().toString());
    }

    /** Whether {@code child} is gone within {@code grace}: it was exiting, not running. */
    private static boolean exits(ProcessHandle child, Duration grace) {
        try {
            child.onExit().get(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException stillRunning) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failed) {
            // No exit signal; decide from the process state alone.
        }
        return !child.isAlive();
    }

    /** The launcher path under this JVM's {@code java.home}, as GpuPreviewService starts it. */
    static String launcherPath() {
        return Path.of(System.getProperty("java.home"), "bin", LAUNCHER).toString();
    }
}
