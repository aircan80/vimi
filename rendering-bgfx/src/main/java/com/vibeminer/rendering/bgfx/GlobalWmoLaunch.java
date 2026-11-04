package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/** Pure global-only launch syntax. No file open, process/context or Source authority. */
record GlobalWmoLaunch(
        Path scene,
        WorldGlobalSceneWriter.InputExpectation expected,
        Path frames,
        long frameNonce,
        long parentPid,
        Backend backend) {
    enum Backend {
        DEFAULT,
        VULKAN,
        METAL,
        D3D11
    }

    private static final Set<String> KEYS =
            Set.of(
                    "--global-scene",
                    "--global-nonce",
                    "--global-scope",
                    "--global-sha256",
                    "--global-length",
                    "--frames",
                    "--global-frame-nonce",
                    "--parent-pid",
                    "--backend");

    GlobalWmoLaunch {
        path(scene, "global.wgs");
        path(frames, "frames.vgpu");
        Objects.requireNonNull(expected);
        Objects.requireNonNull(backend);
        if (frameNonce == 0 || parentPid < 1 || scene.getParent().equals(frames.getParent())) {
            throw new IllegalArgumentException("Invalid global launch ownership");
        }
    }

    static GlobalWmoLaunch parse(String[] args) {
        Objects.requireNonNull(args);
        if (args.length < 16 || args.length > 18 || (args.length & 1) != 0) {
            throw new IllegalArgumentException("Invalid global arguments");
        }
        var values = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i], value = args[i + 1];
            if (key == null
                    || !KEYS.contains(key)
                    || value == null
                    || value.isEmpty()
                    || value.length() > 4096
                    || value.indexOf(0) >= 0
                    || values.put(key, value) != null) {
                throw new IllegalArgumentException("Invalid global argument");
            }
        }
        for (String key : KEYS) {
            if (!key.equals("--backend") && !values.containsKey(key)) {
                throw new IllegalArgumentException("Missing global argument");
            }
        }
        var identity =
                new WorldGlobalStatus.Identity(
                        values.get("--global-nonce"),
                        values.get("--global-scope"),
                        values.get("--global-sha256"));
        int length =
                Math.toIntExact(
                        positive(values.get("--global-length"), WorldGlobalLayout.MAX_FILE_BYTES));
        String nonce = values.get("--global-frame-nonce");
        WorldGlobalLayout.hex(nonce, 16);
        long mappingNonce = Long.parseUnsignedLong(nonce, 16);
        Backend backend =
                switch (values.getOrDefault("--backend", "default")) {
                    case "default" -> {
                        if (values.containsKey("--backend")) {
                            throw new IllegalArgumentException("Explicit default backend");
                        }
                        yield Backend.DEFAULT;
                    }
                    case "vulkan" -> Backend.VULKAN;
                    case "metal" -> Backend.METAL;
                    case "d3d11" -> Backend.D3D11;
                    default -> throw new IllegalArgumentException("Unknown global backend");
                };
        return new GlobalWmoLaunch(
                Path.of(values.get("--global-scene")),
                new WorldGlobalSceneWriter.InputExpectation(identity, length),
                Path.of(values.get("--frames")),
                mappingNonce,
                positive(values.get("--parent-pid"), Long.MAX_VALUE),
                backend);
    }

    void verifyMapping(GpuFrameFile mapping) throws IOException {
        Objects.requireNonNull(mapping).requireGlobalSession(frameNonce);
    }

    static long positive(String text, long maximum) {
        if (text == null || text.isEmpty() || text.length() > 19 || text.charAt(0) == '0') {
            throw new IllegalArgumentException("Invalid global integer");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                throw new IllegalArgumentException("Invalid global integer");
            }
        }
        long value = Long.parseLong(text);
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("Global integer bounds");
        }
        return value;
    }

    private static void path(Path path, String filename) {
        Objects.requireNonNull(path);
        if (path.getFileSystem() != FileSystems.getDefault()
                || !path.isAbsolute()
                || !path.normalize().equals(path)
                || path.toString().length() > 4096
                || path.getParent() == null
                || !filename.equals(path.getFileName().toString())) {
            throw new IllegalArgumentException("Invalid global path");
        }
    }
}
