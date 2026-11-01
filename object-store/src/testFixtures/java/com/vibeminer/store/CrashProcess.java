package com.vibeminer.store;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Bounded parent/child handshake; never uses timing sleeps to guess a crash boundary. */
public final class CrashProcess {
    private CrashProcess() { }
    public static void killJava(Path folder, String point, String operation) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        String classpath = String.join(File.pathSeparator,
            Path.of(ObjectStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(CrashWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(), "--enable-native-access=ALL-UNNAMED"));
        String sqlite = System.getProperty("vibeminer.sqlite.library");
        if (sqlite != null) command.add("-Dvibeminer.sqlite.library=" + sqlite);
        command.addAll(List.of("-cp",classpath,CrashWorker.class.getName(),folder.toString(),point,operation));
        killAtReady(command, "READY " + point);
    }
    public static void killAtReady(List<String> command, String expected) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<Void> ready = new CompletableFuture<>(); StringBuffer output = new StringBuffer();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null;) {
                    output.append(line).append('\n');
                    if (line.equals(expected)) { ready.complete(null); return; }
                }
                ready.completeExceptionally(new AssertionError("Child exited before " + expected + ":\n" + output));
            } catch (Throwable e) { ready.completeExceptionally(e); }
        });
        try {
            ready.get(20, TimeUnit.SECONDS);
            if (!process.isAlive()) throw new AssertionError("Child did not remain blocked at " + expected);
            process.destroyForcibly();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Child did not terminate");
            if (process.exitValue() == 0) throw new AssertionError("Writer exited cleanly instead of being killed");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            process.getOutputStream().close(); process.getInputStream().close(); reader.join(1000);
        }
    }
}
