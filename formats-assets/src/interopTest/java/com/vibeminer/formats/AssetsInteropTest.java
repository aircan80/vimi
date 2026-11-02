package com.vibeminer.formats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AssetsInteropTest {
    @Test
    void originalPinnedReferenceRegeneratesEveryCheckedInAssetGolden() throws Exception {
        Path checked = Path.of(System.getProperty("vimi.assets.goldens")),
                fresh = Path.of(System.getProperty("vimi.assets.interop"));
        try (var files = Files.list(checked)) {
            for (Path expected : files.toList()) {
                Path actual = fresh.resolve(expected.getFileName());
                assertTrue(Files.isRegularFile(actual), expected.toString());
                assertArrayEquals(
                        Files.readAllBytes(expected),
                        Files.readAllBytes(actual),
                        expected.getFileName().toString());
            }
        }
    }
}
