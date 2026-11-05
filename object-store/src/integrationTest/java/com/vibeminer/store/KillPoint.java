package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs a child process to its kill point, proves it is still running there, and kills it.
 *
 * <p>The wait ends only on real conditions: the child writes its marker, or it exits first (a real
 * failure, reported with its exit code and log). A slow child is not a failure: a stalled Windows
 * runner once took about 10 s to start one (#299), longer than the fixed deadlines these tests
 * used. The guards only stop a genuine hang, and stay well inside the CI job timeout.
 */
final class KillPoint {
    /** About 6x the longest child start seen on a stalled CI runner. */
    static final long READY_GUARD_SECONDS = 60;

    /** {@code destroyForcibly} is TerminateProcess or SIGKILL; exit is not a startup race. */
    static final long KILL_GUARD_SECONDS = 30;

    /** Class timeout for a test that kills children: two guards per child, plus the checks. */
    static final long TEST_TIMEOUT_SECONDS = 240;

    private KillPoint() {}

    /** Waits for {@code marker}, asserts the child is still alive, then kills it and waits. */
    static void kill(Process child, Path marker, Path log) throws Exception {
        Throwable primary = null;
        try {
            awaitMarker(child, marker, log);
            assertTrue(
                    child.isAlive(),
                    () -> "Child exited on its own before the kill; child log:\n" + childLog(log));
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            child.destroyForcibly();
            if (!child.waitFor(KILL_GUARD_SECONDS, TimeUnit.SECONDS)) {
                var killFailure =
                        new AssertionError(
                                "Killed child did not exit within "
                                        + KILL_GUARD_SECONDS
                                        + " s; child log:\n"
                                        + childLog(log));
                if (primary == null) {
                    throw killFailure;
                }
                // Keep the readiness failure as the reported cause.
                primary.addSuppressed(killFailure);
            }
        }
    }

    private static void awaitMarker(Process child, Path marker, Path log) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(READY_GUARD_SECONDS);
        while (!Files.exists(marker)) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (!child.isAlive()) {
                // The marker is written before the child blocks; re-check after observing exit.
                if (Files.exists(marker)) {
                    return;
                }
                fail(
                        "Child exited with "
                                + child.exitValue()
                                + " before its kill point, after "
                                + elapsed
                                + " ms; child log:\n"
                                + childLog(log));
            }
            if (System.nanoTime() - deadline > 0) {
                fail(
                        "Child still alive but not at its kill point after "
                                + elapsed
                                + " ms; child log:\n"
                                + childLog(log));
            }
            Thread.sleep(20);
        }
    }

    static String childLog(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException error) {
            return error.toString();
        }
    }
}
