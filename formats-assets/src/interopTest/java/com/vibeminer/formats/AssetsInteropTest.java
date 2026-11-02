package com.vibeminer.formats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
                        fixtureContent(expected),
                        fixtureContent(actual),
                        expected.getFileName().toString());
            }
        }
    }

    @Test
    void gzipTransportHeaderMayDifferWhileOriginalPayloadAndTrailerRemainChecked()
            throws Exception {
        byte[] payload = "authored native fixture\0\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(payload);
        }
        byte[] mac = output.toByteArray();
        byte[] linux = mac.clone();
        mac[9] = 19;
        linux[9] = 3;
        assertFalse(Arrays.equals(mac, linux));
        assertArrayEquals(payload, fixtureContent(mac, true));
        assertArrayEquals(fixtureContent(mac, true), fixtureContent(linux, true));
        byte[] damaged = linux.clone();
        damaged[damaged.length - 1] ^= 1;
        assertThrows(IOException.class, () -> fixtureContent(damaged, true));
        // Native VMG1/VMIM and all other fixtures remain byte-for-byte comparisons.
        assertArrayEquals(mac, fixtureContent(mac, false));
        assertFalse(Arrays.equals(fixtureContent(mac, false), fixtureContent(linux, false)));
    }

    private static byte[] fixtureContent(Path path) throws IOException {
        return fixtureContent(
                Files.readAllBytes(path), path.getFileName().toString().endsWith(".gz"));
    }

    private static byte[] fixtureContent(byte[] bytes, boolean compressed) throws IOException {
        if (!compressed) {
            return bytes;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        }
    }
}
