package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CreaturesInteropTest {
    @Test
    void originalPinnedConceptsRegeneratesEveryCreatureGolden() throws Exception {
        Path checked = Path.of(System.getProperty("vimi.creatures.goldens")),
                fresh = Path.of(System.getProperty("vimi.creatures.interop"));
        try (var paths = Files.list(checked)) {
            for (Path path : paths.toList()) {
                assertArrayEquals(
                        Files.readAllBytes(path),
                        Files.readAllBytes(fresh.resolve(path.getFileName())),
                        path.toString());
            }
        }
    }
}
