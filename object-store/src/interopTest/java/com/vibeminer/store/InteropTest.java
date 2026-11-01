package com.vibeminer.store;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.vibeminer.store.StoreTypes.*;

public final class InteropTest {
    static Path dotnet, harness;
    static Process process(String mode, Path folder) throws Exception {
        return new ProcessBuilder(dotnet.toString(), harness.toString(), mode, folder.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static void run(String mode, Path folder) throws Exception {
        Process p = process(mode, folder); System.out.print(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        if (p.waitFor() != 0) throw new AssertionError("C# " + mode + " failed");
    }
    @org.junit.jupiter.api.Test
    void exchangesDataHistoryAndWriterLocksWithCsharp() throws Exception {
        dotnet = Path.of(System.getProperty("vimi.dotnet")).toAbsolutePath();
        harness = Path.of(System.getProperty("vimi.interop.harness")).toAbsolutePath();
        Path root = Files.createTempDirectory("vimi-interop-");
        try {
            ObjectStore s = ObjectStore.open(root).join(); byte[] bytes = "written by Java".getBytes(StandardCharsets.UTF_8);
            s.put(List.of(new PutObject(StoreAssertions.key(bytes), bytes)), true, Cancellation.NONE).join();
            s.appendHistory(new History.Observation(1, "wow", "us", Instant.EPOCH, "https://example.test", bytes), Cancellation.NONE).join();
            run("busy", root); s.closeAsync().join(); run("exchange", root);
            Process holder = process("hold", root);
            try {
                String line = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream())).readLine();
                if (!"READY".equals(line)) throw new AssertionError("C# lock holder failed");
                StoreAssertions.failure(ObjectStore.open(root), "OBJECT_STORE_BUSY");
            } finally { holder.getOutputStream().write('\n'); holder.getOutputStream().close(); if (holder.waitFor() != 0) throw new AssertionError(); }
            s = ObjectStore.open(root).join(); bytes = "written by C#".getBytes(StandardCharsets.UTF_8);
            StoreAssertions.check(Arrays.equals(s.read(StoreAssertions.key(bytes), Cancellation.NONE).join().bytes(), bytes));
            StoreAssertions.check(s.readHistory(new History.Query(), Cancellation.NONE).join().size() == 2);
            StoreAssertions.check(s.scrub(true, Cancellation.NONE).join().indexProblem() == null);
            StoreAssertions.check(s.scan(Cancellation.NONE).join().live() == 6); s.closeAsync().join();
            run("exchange", root);
            System.out.println("Bidirectional reads, writes, history, rebuilds and writer exclusion passed");
        } finally { try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
}
