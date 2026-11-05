package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only child process. It prepares tables on a real library and blocks its store writer at the
 * requested occurrence of a write point, so the parent can kill it there.
 */
public final class TableCommitCrashWorker {
    private TableCommitCrashWorker() {}

    public static void main(String[] args) throws Exception {
        String mode = args[1], point = args[2];
        int occurrence = Integer.parseInt(args[3]);
        var scenario =
                TableCommitScenario.open(
                        Path.of(args[0]),
                        args.length > 4
                                ? TableCommitScenario.Config.valueOf(args[4])
                                : TableCommitScenario.Config.DEFAULT);
        var seen = new AtomicInteger();
        scenario.probe(
                (name, key) -> {
                    if (name.equals(point) && seen.incrementAndGet() == occurrence) {
                        System.out.println("READY " + point + " " + occurrence);
                        System.out.flush();
                        try {
                            if (System.in.read() < 0) {
                                throw new IllegalStateException("Parent disconnected");
                            }
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        throw new IllegalStateException("Parent should kill the blocked writer");
                    }
                });
        switch (mode) {
            case "single" -> scenario.check(100, Cancellation.NONE);
            case "concurrent" ->
                    CompletableFuture.allOf(
                                    CompletableFuture.runAsync(
                                            () -> scenario.check(100, Cancellation.NONE)),
                                    CompletableFuture.runAsync(
                                            () -> scenario.check(200, Cancellation.NONE)))
                            .join();
            // The production pass: tables 100 and 200 need building, up to four at once.
            case "prepare" -> scenario.tables().prepareKnown(null, Cancellation.NONE).join();
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
        System.out.println("NOT REACHED " + point + " " + occurrence + " seen " + seen.get());
        System.out.flush();
        System.exit(2);
    }
}
