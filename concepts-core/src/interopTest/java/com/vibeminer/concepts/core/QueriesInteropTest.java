package com.vibeminer.concepts.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class QueriesInteropTest {
    @Test
    void originalPinnedCsharpRegeneratesExactQueryGoldens() throws Exception {
        try (var input = getClass().getResourceAsStream("/golden/queries.json")) {
            assertArrayEquals(
                    java.util.Objects.requireNonNull(input).readAllBytes(),
                    Files.readAllBytes(
                            Path.of(System.getProperty("vimi.concepts.queries.golden"))));
        }
    }
}
