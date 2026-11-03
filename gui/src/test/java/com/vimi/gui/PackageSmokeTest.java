package com.vimi.gui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Authored process outcomes: package checks must reject broken or stuck native loaders. */
public final class PackageSmokeTest {
    private PackageSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String ready = "Native runtime: bgfx API156; "
                + com.vibeminer.rendering.bgfx.GpuRuntimeProbe.PACKAGED_SHADER_BLOBS
                + " packaged shader hashes verified";
        PackageSmoke.checkNativeProbe(new Probe(ready, 0, true), Duration.ofMillis(1));
        reject(new Probe("Native runtime: bgfx API156; 8 packaged shader hashes verified", 0, true), "unavailable");
        reject(new Probe(ready, 1, true), "unavailable");
        reject(new Probe("Unrelated successful child", 0, true), "unavailable");
        reject(new Probe("x".repeat(65_537), 0, true), "output limit");
        var stuck = new Probe("", 0, false);
        reject(stuck, "timed out");
        if (!stuck.killed) throw new AssertionError("Timed-out loader must be terminated");
        System.out.println("Package smoke: native failure, incomplete output, output bound and timeout cleanup passed");
    }

    private static void reject(Probe probe, String message) throws Exception {
        try {
            PackageSmoke.checkNativeProbe(probe, Duration.ofMillis(1));
            throw new AssertionError("Invalid native probe accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains(message)) throw new AssertionError(expected);
        }
    }

    private static final class Probe extends Process {
        private final InputStream output;
        private final int exit;
        private boolean complete, killed;

        Probe(String text, int exit, boolean complete) {
            output = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            this.exit = exit;
            this.complete = complete;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exitValue(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return complete; }
        @Override public int exitValue() {
            if (!complete) throw new IllegalThreadStateException("Still running");
            return exit;
        }
        @Override public void destroy() { killed = true; complete = true; }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
