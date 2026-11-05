package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class WorldStreamTransportDiagnosticsTest {
    @TempDir Path directory;
    private final WorldStreamTransport.Spec spec =
            new WorldStreamTransport.Spec("cd".repeat(32), 1, WorldStreamTransport.Backend.VULKAN);

    private static String prefix(java.util.List<String> arguments) throws Exception {
        var descriptor =
                WorldStreamEnvelope.decodeSession(
                        Files.readAllBytes(Path.of(arguments.get(1)).resolve("session.wss")),
                        arguments.get(3));
        return "WS2 "
                + HexFormat.of().toHexDigits(descriptor.nonce().high())
                + HexFormat.of().toHexDigits(descriptor.nonce().low())
                + " ";
    }

    @Test
    void helperCrashLogsExitStatusFailureAndOnlyABoundedStderrTail() throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        var noise = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            noise.append("synthetic startup noise ").append(i).append('\n');
        }
        noise.append("x".repeat(10_000)).append('\n');
        noise.append("Exception in thread \"main\" synthetic helper crash\n");
        noise.append("Cannot open /home/tester/Games/WoW/Data/data.000 or ")
                .append(System.getProperty("user.home"))
                .append("/x\n");
        child.error =
                new ByteArrayInputStream(noise.toString().getBytes(StandardCharsets.US_ASCII));
        var session =
                WorldStreamTransport.prepare(directory, spec)
                        .launch(
                                arguments -> {
                                    // Exit only after the parent's stderr drain has started.
                                    child.killAllowed = false;
                                    Thread.ofVirtual()
                                            .start(
                                                    () -> {
                                                        try {
                                                            child.errorRequested.await();
                                                        } catch (InterruptedException ignored) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                        child.exit(3);
                                                    });
                                    return child;
                                });
        var failure =
                assertThrows(
                        ExecutionException.class,
                        () -> session.completion().get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().startsWith("World"));
        assertEquals(
                3,
                session.terminatedAndDrained().get(5, TimeUnit.SECONDS).exitCode().orElseThrow());
        String report = session.diagnosticReport().get(5, TimeUnit.SECONDS);
        assertTrue(report.startsWith("World helper ended abnormally; pid 919; exit 3"), report);
        assertTrue(report.contains("closed status not received"), report);
        assertTrue(report.contains("; failure: World"), report);
        assertTrue(report.contains("synthetic helper crash"), report);
        assertTrue(report.contains("Cannot open <path>\n"), report);
        assertFalse(report.contains("tester"), report);
        assertFalse(report.contains(System.getProperty("user.home")), report);
        assertTrue(report.contains("earlier stderr lines omitted"), report);
        assertFalse(report.contains("synthetic startup noise 0\n"), report);
        assertTrue(
                report.length()
                        < HelperStderrTail.MAX_LINES * (HelperStderrTail.MAX_LINE_CHARS + 4) + 1024,
                "report must stay bounded");
    }

    @Test
    void objectsWriteFailuresLogTheFirstAndThenEvery64th() {
        var logged = new java.util.ArrayList<Long>();
        for (long count = 1; count <= 200; count++) {
            if (WorldStreamTransport.Session.cadenceLogged(count)) {
                logged.add(count);
            }
        }
        assertEquals(java.util.List.of(1L, 64L, 128L, 192L), logged);
    }

    @Test
    void cleanClosedExitProducesNoDiagnosticRecord() throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        child.error =
                new ByteArrayInputStream(
                        "benign helper warning\n".getBytes(StandardCharsets.US_ASCII));
        var session =
                WorldStreamTransport.prepare(directory, spec)
                        .launch(
                                arguments -> {
                                    String prefix = prefix(arguments);
                                    child.input =
                                            new ByteArrayInputStream(
                                                    (prefix
                                                                    + "SESSION TERRAIN_BASIC_V1\n"
                                                                    + prefix
                                                                    + "CLOSED\n")
                                                            .getBytes(StandardCharsets.US_ASCII));
                                    return child;
                                });
        session.completion().get(5, TimeUnit.SECONDS);
        assertEquals(
                0,
                session.terminatedAndDrained().get(5, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals("", session.diagnosticReport().get(5, TimeUnit.SECONDS));
    }

    /** A session whose fake child writes {@code stdout(prefix)} and then exits. */
    private WorldStreamTransport.Session session(java.util.function.Function<String, String> stdout)
            throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        return WorldStreamTransport.prepare(directory, spec)
                .launch(
                        arguments -> {
                            child.input =
                                    new ByteArrayInputStream(
                                            stdout.apply(prefix(arguments))
                                                    .getBytes(StandardCharsets.US_ASCII));
                            return child;
                        });
    }

    @Test
    void jfrTextInterleavedWithRecordsIsIgnoredAndWarnedOnce() throws Exception {
        var session =
                session(
                        prefix ->
                                "[0.010s][info][jfr,startup] Started recording 1.\n"
                                        + prefix
                                        + "SESSION TERRAIN_BASIC_V1\n"
                                        + "[0.011s][info][jfr,startup] Use jcmd JFR.dump"
                                        + " filename=/home/tester/rec.jfr\r\n"
                                        + "JFR: "
                                        + prefix
                                        + "BOGUS\n"
                                        + prefix
                                        + "CLOSED\n");
        session.initialized().get(5, TimeUnit.SECONDS);
        session.completion().get(5, TimeUnit.SECONDS);
        assertEquals("", session.diagnosticReport().get(5, TimeUnit.SECONDS));
        String warning = session.stdoutDiagnostic().get(5, TimeUnit.SECONDS);
        assertEquals(
                "World helper wrote output unrelated to its protocol on stdout; ignoring it"
                        + " (first line: [0.010s][info][jfr,startup] Started recording 1.)",
                warning);
    }

    @Test
    void malformedProtocolRecordAfterNoiseStillEndsTheSession() throws Exception {
        var session =
                session(
                        prefix ->
                                "Picked up JAVA_TOOL_OPTIONS: -Duser.home=/home/tester\n"
                                        + prefix
                                        + "SESSION TERRAIN_BASIC_V1\n"
                                        + prefix
                                        + "BOGUS\n");
        assertThrows(ExecutionException.class, () -> session.completion().get(5, TimeUnit.SECONDS));
        session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        String report = session.diagnosticReport().get(5, TimeUnit.SECONDS);
        assertTrue(report.contains("failure: Invalid World status"), report);
        assertTrue(report.contains("; unrelated stdout ignored: 1 line, "), report);
        assertTrue(report.contains("-Duser.home=<path>"), report);
        assertFalse(report.contains("tester"), report);
    }

    @Test
    void foreignNonceRecordStillEndsTheSession() throws Exception {
        var session =
                session(
                        prefix ->
                                prefix
                                        + "SESSION TERRAIN_BASIC_V1\n"
                                        + "WS2 "
                                        + "0".repeat(32)
                                        + " CLOSED\n");
        assertThrows(ExecutionException.class, () -> session.completion().get(5, TimeUnit.SECONDS));
        session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        String report = session.diagnosticReport().get(5, TimeUnit.SECONDS);
        assertTrue(report.startsWith("World helper ended abnormally"), report);
        assertFalse(report.contains("unrelated stdout"), report);
    }

    @Test
    void stdoutFloodEndsTheSessionWithAClearFailure() throws Exception {
        var flood = new StringBuilder();
        while (flood.length() <= WorldStreamStdout.MAX_NOISE_BYTES) {
            flood.append("synthetic stdout flood line\n");
        }
        var session =
                session(
                        prefix ->
                                prefix
                                        + "SESSION TERRAIN_BASIC_V1\n"
                                        + flood
                                        + prefix
                                        + "CLOSED\n");
        var failure =
                assertThrows(
                        ExecutionException.class,
                        () -> session.completion().get(5, TimeUnit.SECONDS));
        assertTrue(
                failure.getCause().getMessage().startsWith("World renderer flooded stdout"),
                failure.getCause().getMessage());
        session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        String report = session.diagnosticReport().get(5, TimeUnit.SECONDS);
        assertTrue(report.contains("failure: World renderer flooded stdout"), report);
        assertTrue(report.contains("earlier stdout lines omitted"), report);
        assertTrue(report.length() < 2 * HelperStderrTail.MAX_LINES * 300, "bounded report");
    }

    @Test
    void noChildStartFailureStillReportsWithoutPid() throws Exception {
        var session =
                WorldStreamTransport.prepare(directory, spec)
                        .launch(
                                arguments -> {
                                    throw new java.io.IOException("synthetic start failure");
                                });
        assertThrows(ExecutionException.class, () -> session.completion().get(5, TimeUnit.SECONDS));
        session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        String report = session.diagnosticReport().get(5, TimeUnit.SECONDS);
        assertTrue(report.contains("no child process"), report);
        assertTrue(report.contains("failure: synthetic start failure"), report);
        assertTrue(report.contains("helper stderr not captured"), report);
    }
}
