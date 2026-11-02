package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ItemsInteropTest {
    @Test
    void unchangedOriginalCsharpRegeneratesCheckedGolden() throws Exception {
        assertArrayEquals(
                Files.readAllBytes(Path.of(System.getProperty("vimi.items.goldens"), "facts.tsv")),
                Files.readAllBytes(Path.of(System.getProperty("vimi.items.interop"), "facts.tsv")));
    }
}
