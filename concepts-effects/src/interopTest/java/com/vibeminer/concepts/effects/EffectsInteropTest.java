package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EffectsInteropTest {
    @Test
    void originalPinnedCsharpServiceRegeneratesGoldenFactsAndCursorBytes() throws Exception {
        Path checked = Path.of(System.getProperty("vimi.effects.goldens")),
                fresh = Path.of(System.getProperty("vimi.effects.interop"));
        try (var files = Files.list(checked)) {
            for (Path expected : files.toList()) {
                Path actual = fresh.resolve(expected.getFileName());
                assertTrue(Files.isRegularFile(actual));
                assertArrayEquals(
                        Files.readAllBytes(expected),
                        Files.readAllBytes(actual),
                        expected.getFileName().toString());
            }
        }
    }
}
