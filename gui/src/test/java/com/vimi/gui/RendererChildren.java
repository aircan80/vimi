package com.vimi.gui;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Live Java child processes of this JVM: the renderer children GpuPreviewService launches. */
final class RendererChildren {
    /** The launcher GpuPreviewService starts: {@code java.home/bin/java}, with .exe on Windows. */
    static final String LAUNCHER =
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";

    private RendererChildren() {}

    /**
     * Live children started with {@link #LAUNCHER}. A live child whose command cannot be read
     * fails the proof rather than being dropped, so a renderer is never silently uncounted.
     */
    static Set<Long> live() {
        return ProcessHandle.current()
                .children()
                .filter(ProcessHandle::isAlive)
                .filter(
                        p -> {
                            var command = p.info().command();
                            if (command.isEmpty() && p.isAlive()) {
                                throw new AssertionError(
                                        "Cannot read the command of live child " + p.pid());
                            }
                            return command.map(c -> Path.of(c).getFileName().toString())
                                    .map(LAUNCHER::equalsIgnoreCase)
                                    .orElse(false);
                        })
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    /** The launcher path under this JVM's {@code java.home}, as GpuPreviewService starts it. */
    static String launcherPath() {
        return Path.of(System.getProperty("java.home"), "bin", LAUNCHER).toString();
    }
}
