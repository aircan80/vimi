package com.vibeminer.formats.audio;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class WorkerDeadlineTest {
    @Test
    void deadlineKillsWorkerEvenWhenItsInputPipeIsBlocked() throws Exception {
        Process worker = stalled();
        try {
            assertThrows(
                    IOException.class,
                    () ->
                            VoiceDecoder.runWorker(
                                    worker,
                                    new byte[VoiceDecoder.MAX_ENCODED],
                                    () -> false,
                                    TimeUnit.MILLISECONDS.toNanos(250)));
            assertTrue(worker.waitFor(3, TimeUnit.SECONDS));
        } finally {
            worker.destroyForcibly();
        }
    }

    @Test
    void cancellationKillsWorkerEvenWhenItsInputPipeIsBlocked() throws Exception {
        Process worker = stalled();
        long cancelAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        try {
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            VoiceDecoder.runWorker(
                                    worker,
                                    new byte[VoiceDecoder.MAX_ENCODED],
                                    () -> System.nanoTime() >= cancelAt,
                                    TimeUnit.SECONDS.toNanos(8)));
            assertTrue(worker.waitFor(3, TimeUnit.SECONDS));
        } finally {
            worker.destroyForcibly();
        }
    }

    private static Process stalled() throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classes =
                Path.of(
                                StalledVoiceWorker.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-cp",
                        classes,
                        StalledVoiceWorker.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
