package com.vimi.gui;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Dependency-free checks for bounded, redacted World failure diagnostics. */
public final class WorldDiagnosticsTest {
    public static void main(String[] args) {
        summaryKeepsTheOriginalCauseChainBounded();
        redactsUrlQueriesAndControlCharacters();
        redactsInstallPathsAccountsAndHostnames();
        degradedPartsAreThrottledPerPart();
        terminalRecordsHaveTheirOwnBudget();
        cancellationIsLifecycleNotAFault();
        cyclicCausesTerminate();
        retirementBlockedCarriesTheOriginalReason();
        untrustedExceptionAccessorsAreNeverInvoked();
        System.out.println("World diagnostics: cause chains, bounds, redaction, cancellation,"
                + " degraded-part throttling, untrusted accessors and retirement reasons passed");
    }

    private static void assertTrue(boolean value, String... message) {
        if (!value) throw new AssertionError(message.length == 0 ? "check failed" : message[0]);
    }

    private static void assertFalse(boolean value, String... message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("expected " + expected + " but was " + actual);
    }

    private static void assertNull(Object value) {
        assertTrue(value == null, "expected null");
    }
    private static void summaryKeepsTheOriginalCauseChainBounded() {
        var root = new IOException("World status EOF before CLOSED");
        var wrapped = new CompletionException(new IOException("GPU World owner ended", root));
        String summary = WorldDiagnostics.summary(wrapped);
        assertTrue(summary.contains("GPU World owner ended"), summary);
        assertTrue(summary.contains("caused by java.io.IOException: World status EOF before CLOSED"),
                summary);
        assertTrue(summary.contains(" at com.vimi.gui.WorldDiagnosticsTest."), summary);
        assertEquals("World status EOF before CLOSED", WorldDiagnostics.brief(wrapped));

        Throwable deep = new IOException("m".repeat(10_000));
        for (int i = 0; i < 50; i++) deep = new IOException("level " + i, deep);
        assertTrue(WorldDiagnostics.summary(deep).length() <= WorldDiagnostics.MAX_SUMMARY_CHARS);
        assertTrue(WorldDiagnostics.brief(new IOException("x".repeat(1000))).length()
                <= WorldDiagnostics.MAX_BRIEF_CHARS);
    }

    private static void redactsUrlQueriesAndControlCharacters() {
        var failure = new IOException(
                "GET https://example.invalid/asset?X-Amz-Signature=secret#frag failed\nforged line");
        String summary = WorldDiagnostics.summary(failure);
        assertFalse(summary.contains("secret"), summary);
        assertFalse(summary.contains("\n"), summary);
        assertTrue(summary.contains("https://example.invalid/asset?<redacted>"), summary);
        assertFalse(WorldDiagnostics.brief(failure).contains("secret"));
    }

    private static void redactsInstallPathsAccountsAndHostnames() {
        String home = System.getProperty("user.home");
        var missing = new java.nio.file.NoSuchFileException(
                home + "/Games/World of Warcraft/_classic_/Data/data.000");
        var failure = new IOException("World source failed", new IOException(
                "read C:\\Users\\tester\\Games\\Wow.exe via \\\\nas-tester\\wow", missing));
        String summary = WorldDiagnostics.summary(failure);
        assertFalse(summary.contains(home), summary);
        assertFalse(summary.contains("tester"), summary);
        assertFalse(summary.contains("Games"), summary);
        assertTrue(summary.contains("java.nio.file.NoSuchFileException: <path>"), summary);
        for (var fragment : java.util.List.of("World of Warcraft", "Warcraft", "_classic_", "Data", "Users",
                "Wow.exe", "nas-"))
            assertFalse(summary.contains(fragment), fragment + " survived: " + summary);
        var spaced = new IOException("cannot open \"C:\\Program Files (x86)\\World of Warcraft\\_retail_\\Wow.exe\""
                + " for C:\\Users\\John Smith\\x and \\\\?\\C:\\Users\\jsmith\\y and path:/home/jsmith/z");
        String spacedSummary = WorldDiagnostics.summary(spaced);
        for (var fragment : java.util.List.of("Program Files", "World of Warcraft", "_retail_", "John Smith", "Smith",
                "jsmith", "x86"))
            assertFalse(spacedSummary.contains(fragment), fragment + " survived: " + spacedSummary);
        String user = System.getProperty("user.name");
        if (user.length() >= 3) {
            String named = WorldDiagnostics.summary(new IOException("owner " + user + " denied"));
            assertFalse(named.contains(user), named);
        }
        assertFalse(WorldDiagnostics.brief(missing).contains(home), WorldDiagnostics.brief(missing));
    }

    /** Captures com.vimi.gui.World records; the clock and every window start fresh. */
    private static final class Capture implements AutoCloseable {
        final java.util.List<String> records = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
        private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.vimi.gui.World");
        private final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                synchronized (records) {
                    records.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        Capture() {
            WorldDiagnostics.resetForTest();
            WorldDiagnostics.clock = now::get;
            logger.addHandler(handler);
        }

        void advanceSeconds(long seconds) {
            now.addAndGet(seconds * 1_000_000_000L);
        }

        java.util.List<String> records() {
            synchronized (records) {
                return java.util.List.copyOf(records);
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
            WorldDiagnostics.clock = System::nanoTime;
            WorldDiagnostics.resetForTest();
        }
    }

    private static void degradedPartsAreThrottledPerPart() {
        try (var capture = new Capture()) {
            String home = System.getProperty("user.home");
            for (int i = 0; i < 25; i++)
                WorldDiagnostics.degraded("test water", "World water failed",
                        new IllegalStateException("bad liquid at " + home + "/Data/x.adt"));
            WorldDiagnostics.degraded("test creatures", "World creature models failed",
                    new IllegalStateException("bad model"));
            var records = capture.records();
            assertEquals(2, records.size());
            assertTrue(records.get(0).startsWith("World water failed: java.lang.IllegalStateException:"
                    + " bad liquid at <path>"), records.get(0));
            assertFalse(records.get(0).contains(home), records.get(0));
            assertTrue(records.get(1).contains("bad model"), records.get(1));

            // After the minute, the next record says how many were skipped meanwhile.
            capture.advanceSeconds(61);
            WorldDiagnostics.degraded("test water", "World water failed", new IllegalStateException("again"));
            records = capture.records();
            assertEquals(3, records.size());
            assertTrue(records.get(2).startsWith("World water failed (24 similar test water failures since"
                    + " the last record)"), records.get(2));

            // degraded always logs: its call sites treat the failure, even a cancellation, as a failure.
            WorldDiagnostics.degraded("test lookups", "World lookup failed", new CancellationException());
            assertTrue(capture.records().get(3).startsWith("World lookup failed (cancellation): "),
                    capture.records().get(3));
            // degradedUnlessClean is for sites that treat a clean cancellation as routine.
            WorldDiagnostics.degradedUnlessClean("test other", "World other failed", new CancellationException());
            assertEquals(4, capture.records().size());
        }
    }

    private static void terminalRecordsHaveTheirOwnBudget() {
        try (var capture = new Capture()) {
            for (int i = 0; i < 100; i++)
                WorldDiagnostics.request("World request failed", new IOException("request " + i));
            for (int i = 0; i < 100; i++)
                WorldDiagnostics.degraded("part " + i, "World part failed", new IOException("part " + i));
            WorldDiagnostics.warn("Inline GPU renderer failed", new IOException("terminal"),
                    "helper stderr tail:\nlast line\n");
            var records = capture.records();
            String last = records.get(records.size() - 1);
            assertTrue(last.startsWith("Inline GPU renderer failed: java.io.IOException: terminal"), last);
            assertTrue(last.contains("helper stderr tail"), last);
            assertFalse(last.contains("rate-limited"), last);

            // A terminal site always leaves a record, even for a pure cancellation.
            WorldDiagnostics.warn("World streaming failed", new CancellationException("join"));
            last = capture.records().get(capture.records().size() - 1);
            assertTrue(last.startsWith("World streaming failed (cancellation): "), last);

            // Requests are canceled routinely: a clean cancellation leaves no request record.
            int before = capture.records().size();
            capture.advanceSeconds(61);
            WorldDiagnostics.request("World request failed", new CompletionException(new CancellationException()));
            assertEquals(before, capture.records().size());
            // The next admitted request record reports what its own budget dropped.
            WorldDiagnostics.request("World request failed", new IOException("later"));
            last = capture.records().get(capture.records().size() - 1);
            assertTrue(last.contains("80 earlier similar World records rate-limited"), last);

            // The terminal budget itself is bounded too.
            for (int i = 0; i < 100; i++)
                WorldDiagnostics.warn("World viewer failed", new IOException("terminal " + i));
            assertEquals(before + 1 + 30, capture.records().size());
        }
    }

    private static void untrustedExceptionAccessorsAreNeverInvoked() {
        final class Hostile extends RuntimeException {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
                throw new AssertionError("message accessor invoked");
            }

            @Override
            public synchronized Throwable getCause() {
                throw new AssertionError("cause accessor invoked");
            }

            @Override
            public StackTraceElement[] getStackTrace() {
                throw new AssertionError("stack accessor invoked");
            }
        }
        var hostile = new CompletionException("wrapper", new Hostile());
        assertTrue(WorldDiagnostics.summary(hostile).contains("Hostile"));
        assertEquals("Hostile", WorldDiagnostics.brief(hostile));
        assertFalse(WorldDiagnostics.cancellation(hostile));
        assertNull(WorldDiagnostics.retirementMessage(hostile));
        WorldDiagnostics.warn("hostile", hostile);
        var line = new IOException("a\u2028b\u0085c");
        assertFalse(WorldDiagnostics.summary(line).contains("\u2028"));
        assertFalse(WorldDiagnostics.summary(line).contains("\u0085"));
    }

    private static void cancellationIsLifecycleNotAFault() {
        assertTrue(WorldDiagnostics.cancellation(
                new CompletionException(new CancellationException("closed"))));
        assertFalse(WorldDiagnostics.cancellation(new IOException("broken")));
        assertFalse(WorldDiagnostics.cancellation(null));
        // Nested JDK joins add a CancellationException("join") link per join: still lifecycle.
        var stored = new CompletableFuture<Void>();
        stored.completeExceptionally(new CancellationException());
        Throwable joined = null;
        try {
            stored.join();
        } catch (CancellationException thrown) {
            joined = thrown;
        }
        assertTrue(WorldDiagnostics.cancellation(new CompletionException(joined)));
        // A real failure inside the chain, or a suppressed one, is logged.
        var dirty = new CancellationException("join");
        dirty.initCause(new IllegalStateException("cleanup failed", new CancellationException()));
        assertFalse(WorldDiagnostics.cancellation(dirty));
        var suppressed = new CancellationException("closed");
        suppressed.addSuppressed(new IOException("close failed"));
        assertFalse(WorldDiagnostics.cancellation(new CompletionException(suppressed)));
        assertFalse(WorldDiagnostics.cancellation(new CancellationException() {}));
        // Exactly CleanCancellation.is (#230): wrappers first, then only cancellation links.
        var interleaved = new CancellationException("join");
        interleaved.initCause(new CompletionException(new CancellationException()));
        assertFalse(WorldDiagnostics.cancellation(interleaved));
        Throwable deep = chain(6);
        for (int i = 0; i < 3; i++) deep = new CompletionException(deep);
        assertTrue(WorldDiagnostics.cancellation(deep));
        assertTrue(WorldDiagnostics.cancellation(chain(8)));
        assertFalse(WorldDiagnostics.cancellation(chain(9)));
        Throwable wrapped = chain(1);
        for (int i = 0; i < 8; i++) wrapped = new CompletionException(wrapped);
        assertTrue(WorldDiagnostics.cancellation(wrapped));
        assertFalse(WorldDiagnostics.cancellation(new CompletionException(wrapped)));
        assertFalse(WorldDiagnostics.cancellation(new CompletionException(null)));
    }

    private static CancellationException chain(int links) {
        var link = new CancellationException();
        for (int i = 1; i < links; i++) {
            var outer = new CancellationException("join");
            outer.initCause(link);
            link = outer;
        }
        return link;
    }

    private static void cyclicCausesTerminate() {
        var a = new IOException("a");
        var b = new IOException("b", a);
        a.initCause(b);
        assertTrue(WorldDiagnostics.summary(a).contains("caused by java.io.IOException: b"));
        WorldDiagnostics.brief(a);
    }

    private static void retirementBlockedCarriesTheOriginalReason() {
        var original = new IOException("World child exited without successful CLOSED (exit 134)");
        var retirement = CompletableFuture.allOf(CompletableFuture.failedFuture(original));
        Throwable observed = retirement.handle((ignored, failure) -> failure).join();
        var pending = new CompletableFuture<Void>();
        pending.completeExceptionally(new WorldDiagnostics.RetirementBlocked(observed));
        Throwable seen = pending.handle((ignored, failure) -> failure).join();
        String message = WorldDiagnostics.retirementMessage(seen);
        assertTrue(message.contains("exit 134"), message);
        assertTrue(message.contains("reload the workspace"), message);
        assertTrue(WorldDiagnostics.summary(seen).contains("exit 134"));
        assertNull(WorldDiagnostics.retirementMessage(new IOException("unrelated")));
        assertNull(WorldDiagnostics.retirementMessage(null));
    }
}
