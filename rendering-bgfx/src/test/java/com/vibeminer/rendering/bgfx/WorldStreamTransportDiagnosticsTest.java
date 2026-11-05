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
            if (WorldStreamTransport.Session.objectsWriteLogged(count)) {
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
