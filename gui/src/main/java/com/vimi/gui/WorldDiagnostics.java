package com.vimi.gui;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Bounded, redacted World failure summaries for the application log and short UI status text.
 * Records carry exception types, bounded messages and code locations only: never stack-local data,
 * payload bytes, URL query strings (which can be transient bearer capabilities), absolute local
 * paths such as the game install, or this machine's user and host names.
 */
final class WorldDiagnostics {
    static final int MAX_CAUSES = 6;
    static final int MAX_MESSAGE_CHARS = 200;
    static final int MAX_SUMMARY_CHARS = 2048;
    static final int MAX_BRIEF_CHARS = 120;
    private static final int MAX_FRAMES = 4;
    private static final int RECORDS_PER_MINUTE = 30;
    private static final long DEGRADED_INTERVAL_NANOS = 60_000_000_000L;
    private static final System.Logger LOG = System.getLogger("com.vimi.gui.World");
    private static final Object RATE = new Object();
    private static long windowStart;
    private static int windowRecords;
    private static long suppressed;
    private static final java.util.Map<String, long[]> DEGRADED = new java.util.HashMap<>();

    private WorldDiagnostics() {}

    /** A queued request blocked because earlier World resources never confirmed retirement. */
    static final class RetirementBlocked extends java.io.IOException {
        private static final long serialVersionUID = 1L;

        RetirementBlocked(Throwable cause) {
            super("Previous World renderer did not retire: " + brief(cause), cause);
        }
    }

    /** UI text carrying the original retirement reason, or null for other failures. */
    static String retirementMessage(Throwable failure) {
        try {
            var blocked = find(failure, RetirementBlocked.class);
            return blocked == null ? null : "Previous World renderer did not shut down cleanly ("
                    + brief(blocked.getCause()) + "); reload the workspace.";
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * Only JDK (bootstrap) exception classes and this file's own type have trusted accessors.
     * Other failures contribute their class name only: no overridable message, cause, suppressed
     * or stack accessor is invoked on them, matching the World lifecycle's detached-failure rule.
     */
    private static boolean trusted(Throwable failure) {
        Class<?> type = failure.getClass();
        return type.getClassLoader() == null || type == RetirementBlocked.class;
    }

    private static Throwable cause(Throwable failure) {
        return trusted(failure) ? failure.getCause() : null;
    }

    /**
     * Cancellation is lifecycle, not a fault. Only a pure chain counts: completion wrappers and
     * exact {@code CancellationException} links (nested JDK joins add one per join), with no
     * suppressed failure anywhere, ending in a cause-free cancellation. Any real failure inside
     * the chain, a subclass, a cycle or an over-bound chain is logged.
     */
    static boolean cancellation(Throwable failure) {
        try {
            for (int i = 0; failure != null && i < MAX_CAUSES + 2; i++) {
                Class<?> type = failure.getClass();
                if (type != CancellationException.class && type != CompletionException.class
                        && type != ExecutionException.class) return false;
                if (failure.getSuppressed().length != 0) return false;
                Throwable next = failure.getCause();
                if (next == null) return type == CancellationException.class;
                failure = next;
            }
        } catch (Throwable unreadable) {
            return false;
        }
        return false;
    }

    /** Multi-cause bounded summary for the application log. Never throws. */
    static String summary(Throwable failure) {
        if (failure == null) return "no failure detail";
        var text = new StringBuilder();
        try {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable root = failure;
            for (int i = 0; failure != null && i < MAX_CAUSES && seen.add(failure); i++) {
                if (i > 0) text.append(" | caused by ");
                text.append(describe(failure));
                if (trusted(failure) && failure.getSuppressed().length > 0)
                    text.append(" [+").append(failure.getSuppressed().length).append(" suppressed: ")
                            .append(describe(failure.getSuppressed()[0])).append(']');
                root = failure;
                failure = cause(failure);
            }
            var frames = trusted(root) ? root.getStackTrace() : new StackTraceElement[0];
            for (int i = 0; i < Math.min(MAX_FRAMES, frames.length); i++) {
                var frame = frames[i];
                text.append(i == 0 ? " at " : " < ")
                        .append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
            }
        } catch (Throwable unreadable) {
            text.append(" [failure detail unreadable]");
        }
        return cap(text.toString(), MAX_SUMMARY_CHARS);
    }

    /** One short reason for UI status text: the innermost non-wrapper failure. Never throws. */
    static String brief(Throwable failure) {
        if (failure == null) return "unknown reason";
        Throwable chosen = failure;
        try {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; failure != null && i < MAX_CAUSES && seen.add(failure); i++) {
                var next = cause(failure);
                if (!(failure instanceof CompletionException || failure instanceof ExecutionException)
                        || next == null) chosen = failure;
                failure = next;
            }
            String message = trusted(chosen) ? chosen.getMessage() : null;
            return cap(message == null || message.isBlank()
                    ? name(chosen) : clean(message), MAX_BRIEF_CHARS);
        } catch (Throwable unreadable) {
            return name(chosen);
        }
    }

    /** The failure of a completed future, or null; unlike exceptionNow, never throws. */
    static Throwable failureOf(java.util.concurrent.CompletableFuture<?> future) {
        try {
            return future.handle((ignored, failure) -> failure).getNow(null);
        } catch (Throwable unreadable) {
            return unreadable;
        }
    }

    private static String name(Throwable failure) {
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        try {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; failure != null && i < MAX_CAUSES && seen.add(failure); i++) {
                if (type.isInstance(failure)) return type.cast(failure);
                failure = cause(failure);
            }
        } catch (Throwable unreadable) {
            return null;
        }
        return null;
    }

    /**
     * A failure that degrades one optional World part (exterior objects, creatures, water) while
     * terrain continues. These can repeat on every tile, so each {@code part} logs at most one
     * record a minute, carrying how many were skipped, and cannot crowd out terminal records.
     */
    static void degraded(String part, String context, Throwable failure) {
        try {
            if (cancellation(failure)) return;
            long skipped;
            synchronized (RATE) {
                long now = System.nanoTime();
                long[] state = DEGRADED.computeIfAbsent(part, ignored -> new long[] {now, -1});
                if (state[1] >= 0 && now - state[0] < DEGRADED_INTERVAL_NANOS) {
                    state[1]++;
                    return;
                }
                skipped = Math.max(0, state[1]);
                state[0] = now;
                state[1] = 0;
            }
            warn(context + (skipped == 0 ? "" : " (" + skipped + " similar " + part
                    + " failures since the last record)"), failure);
        } catch (Throwable ignored) {
            // Diagnostics cannot interrupt World lifecycle or JavaFX delivery.
        }
    }

    /** Rate-limited WARNING record; cancellations are not logged. Never throws. */
    static void warn(String context, Throwable failure) {
        warn(context, failure, null);
    }

    /** As {@link #warn(String, Throwable)} with an already bounded, sanitized detail block. */
    static void warn(String context, Throwable failure, String detail) {
        try {
            if (cancellation(failure)) return;
            long dropped;
            synchronized (RATE) {
                long now = System.nanoTime();
                if (windowRecords == 0 || now - windowStart > 60_000_000_000L) {
                    windowStart = now;
                    windowRecords = 0;
                }
                if (windowRecords >= RECORDS_PER_MINUTE) {
                    suppressed++;
                    return;
                }
                windowRecords++;
                dropped = suppressed;
                suppressed = 0;
            }
            LOG.log(System.Logger.Level.WARNING, cap(clean(context), MAX_MESSAGE_CHARS) + ": "
                    + summary(failure)
                    + (dropped == 0 ? "" : " (" + dropped + " earlier World records rate-limited)")
                    + (detail == null ? "" : "; " + detail));
        } catch (Throwable ignored) {
            // Diagnostics cannot interrupt World lifecycle or JavaFX delivery.
        }
    }

    private static String describe(Throwable failure) {
        String message = trusted(failure) ? failure.getMessage() : null;
        return failure.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + cap(clean(message), MAX_MESSAGE_CHARS));
    }

    static String clean(String message) {
        // Pre-truncate so a huge message cannot make regex work expensive on the FX thread.
        if (message.length() > 4 * MAX_SUMMARY_CHARS) message = message.substring(0, 4 * MAX_SUMMARY_CHARS);
        // One redaction for GUI records and helper stderr: control characters, URL queries and
        // user-info, absolute local paths and this machine's user and host names.
        return com.vibeminer.rendering.bgfx.HelperStderrTail.redact(message);
    }

    private static String cap(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
