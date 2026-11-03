package com.vimi.gui;

import com.vibeminer.store.ObjectStore;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Explicit packaging diagnostic: temporary library only; never opens user data or network sources.
 */
final class PackageSmoke {
    private PackageSmoke() {}

    static void run(String[] args) throws Exception {
        var options = Arrays.asList(args);
        boolean ui = options.contains("--package-ui-smoke-test");
        Class.forName("javafx.scene.Scene", false, PackageSmoke.class.getClassLoader());
        Class.forName(
                "com.vibeminer.concepts.world.WorldService",
                false,
                PackageSmoke.class.getClassLoader());
        Path directory = Files.createTempDirectory("vimi-package-smoke-");
        try {
            var library = ObjectStore.openLibrary(directory.resolve("library")).join();
            try {
                if (library.stats().join().objects() != 0) {
                    throw new IllegalStateException("New smoke library is not empty");
                }
            } finally {
                library.closeAsync().join();
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
        var command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac"))
            command.add("-XstartOnFirstThread");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.vibeminer.rendering.bgfx.GpuRuntimeProbe");
        checkNativeProbe(new ProcessBuilder(command).redirectErrorStream(true).start(), Duration.ofSeconds(15));
        if (ui) {
            var ready = new CompletableFuture<Void>();
            Platform.startup(
                    () ->
                            Platform.runLater(
                                    () -> {
                                        try {
                                            var stage = new Stage();
                                            stage.setTitle("VibeMiner prerelease package check");
                                            stage.setScene(
                                                    new Scene(
                                                            new Label(
                                                                    "VibeMiner · packaged JavaFX"
                                                                            + " startup passed"),
                                                            420,
                                                            100));
                                            stage.show();
                                            Platform.runLater(
                                                    () -> {
                                                        stage.close();
                                                        ready.complete(null);
                                                        Platform.exit();
                                                    });
                                        } catch (Throwable failure) {
                                            ready.completeExceptionally(failure);
                                            Platform.exit();
                                        }
                                    }));
            try {
                ready.get(30, TimeUnit.SECONDS);
            } finally {
                Platform.exit();
            }
        }
        String result =
                "{\"status\":\"ok\",\"version\":\""
                        + escape(System.getProperty("vimi.version", "development"))
                        + "\",\"os\":\""
                        + escape(System.getProperty("os.name"))
                        + "\",\"arch\":\""
                        + escape(System.getProperty("os.arch"))
                        + "\",\"ui\":"
                        + ui
                        + ",\"nativeRuntime\":true,\"shaderBlobs\":"
                        + com.vibeminer.rendering.bgfx.GpuRuntimeProbe.PACKAGED_SHADER_BLOBS
                        + "}";
        int report = options.indexOf("--smoke-report");
        if (report >= 0) {
            if (report + 1 >= args.length)
                throw new IllegalArgumentException("Smoke report path required");
            Path path = Path.of(args[report + 1]).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, result + "\n");
        }
        System.out.println(result);
    }

    /** Bounded output and lifetime: a broken native loader must fail packaging, not hang it. */
    static void checkNativeProbe(Process child, Duration timeout) throws Exception {
        var output = new CompletableFuture<byte[]>();
        var reader = Thread.ofVirtual().name("packaged-gpu-probe-output").start(() -> {
            try {
                output.complete(child.getInputStream().readNBytes(65_537));
            } catch (Exception failure) {
                output.completeExceptionally(failure);
            }
        });
        try {
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Packaged GPU runtime probe timed out");
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (bytes.length > 65_536)
                throw new IllegalStateException("Packaged GPU runtime probe exceeded its output limit");
            String diagnostic = new String(bytes, StandardCharsets.UTF_8);
            if (child.exitValue() != 0 || !diagnostic.contains("bgfx API156")
                    || !diagnostic.contains("8 packaged shader hashes verified"))
                throw new IllegalStateException("Packaged GPU runtime is unavailable: " + diagnostic);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(2, TimeUnit.SECONDS);
            }
            reader.join(1_000);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
