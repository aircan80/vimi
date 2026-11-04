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
        cancellationIsLifecycleNotAFault();
        cyclicCausesTerminate();
        retirementBlockedCarriesTheOriginalReason();
        untrustedExceptionAccessorsAreNeverInvoked();
        System.out.println("World diagnostics: cause chains, bounds, redaction, cancellation,"
                + " untrusted accessors and retirement reasons passed");
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
