package com.vibeminer.formats.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SingleTextTest {
    @Test
    void invariantRoundTripTextMatchesPinnedDotnetGoldenBits() throws Exception {
        try (var lines =
                new BufferedReader(
                        new InputStreamReader(
                                SingleTextTest.class.getResourceAsStream("single-csharp.txt"),
                                StandardCharsets.UTF_8))) {
            for (String line; (line = lines.readLine()) != null; ) {
                int space = line.indexOf(' ');
                int bits = (int) Long.parseLong(line.substring(0, space), 16);
                assertEquals(
                        line.substring(space + 1),
                        SingleText.format(Float.intBitsToFloat(bits)),
                        line.substring(0, space));
            }
        }
    }
}
