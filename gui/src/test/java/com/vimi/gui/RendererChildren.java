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

    static Set<Long> live() {
        return ProcessHandle.current()
                .children()
                .filter(ProcessHandle::isAlive)
                .filter(
                        p ->
                                p.info()
                                        .command()
                                        .map(c -> Path.of(c).getFileName().toString())
                                        .map(LAUNCHER::equalsIgnoreCase)
                                        .orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }
}
