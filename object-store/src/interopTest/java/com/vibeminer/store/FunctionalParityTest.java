package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.Refusal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FunctionalParityTest {
    @TempDir Path root;

    void csharp(String mode, Path path) throws Exception {
        Process process =
                new ProcessBuilder(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.interop.harness"),
                                mode,
                                path.toString())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        System.out.print(output);
    }

    @Test
    void matchesCsharpVerificationAndRefusalPrecedence() throws Exception {
        List<String> json = new ArrayList<>();
        for (var vector : VerificationVectors.all()) {
            Refusal refusal =
                    Verification.CDN.check(vector.key(), vector.bytes(), vector.declared());
            assertEquals(vector.expected(), refusal == null ? null : refusal.code());
            json.add(
                    "{\"type\":"
                            + vector.key().type()
                            + ",\"namespace\":"
                            + vector.key().namespace()
                            + ",\"key\":\""
                            + vector.key().hex()
                            + "\",\"declared\":"
                            + vector.declared()
                            + ",\"data\":\""
                            + Base64.getEncoder().encodeToString(vector.bytes())
                            + "\",\"expected\":"
                            + (vector.expected() == null ? "null" : "\"" + vector.expected() + "\"")
                            + "}");
        }
        Path manifest = root.resolve("vectors.json");
        Files.writeString(manifest, "[" + String.join(",", json) + "]");
        csharp("verify-vectors", manifest);
    }

    @Test
    void exchangesGeneratedObjectsAndRebuildsWithCsharp() throws Exception {
        Options options = new Options(Kind.GENERATED, 300, Clock.systemUTC());
        ObjectStore store = ObjectStore.open(root, options).join();
        try {
            store.put(
                            List.of(
                                    new PutObject(
                                            new ObjectKey(7, new byte[16]), new byte[] {1, 2, 3})),
                            true,
                            NONE)
                    .join();
        } finally {
            store.closeAsync().join();
        }
        csharp("generated-exchange", root);
        store = ObjectStore.open(root, options).join();
        try {
            byte[] key = new byte[16];
            key[0] = 1;
            assertArrayEquals(
                    new byte[] {4, 5, 6}, store.read(new ObjectKey(7, key), NONE).join().bytes());
            assertEquals(2, store.scan(NONE).join().live());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void csharpResumesJavaChunkedStaging() throws Exception {
        EncodedFixture fixture = EncodedFixture.sample();
        Files.write(root.resolve("encoded.bin"), fixture.bytes());
        ObjectStore store = ObjectStore.open(root.resolve("store")).join();
        try {
            ObjectStaging stage =
                    store.stage(fixture.key(), fixture.bytes().length, Origin.CDN, NONE).join();
            stage.append(Arrays.copyOf(fixture.bytes(), fixture.firstChunkEnd() + 2), NONE).join();
            stage.closeAsync().join();
        } finally {
            store.closeAsync().join();
        }
        csharp("stage-exchange", root);
        store = ObjectStore.open(root.resolve("store")).join();
        try {
            assertArrayEquals(fixture.bytes(), store.read(fixture.key(), NONE).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void javaResumesCsharpChunkedStaging() throws Exception {
        EncodedFixture fixture = EncodedFixture.sample();
        Files.write(root.resolve("encoded.bin"), fixture.bytes());
        csharp("stage-save", root);
        ObjectStore store = ObjectStore.open(root.resolve("store")).join();
        try {
            ObjectStaging stage =
                    store.stage(fixture.key(), fixture.bytes().length, Origin.CDN, NONE).join();
            assertEquals(fixture.firstChunkEnd(), stage.length());
            stage.append(
                            Arrays.copyOfRange(
                                    fixture.bytes(),
                                    fixture.firstChunkEnd(),
                                    fixture.bytes().length),
                            NONE)
                    .join();
            assertEquals(PutOutcome.STORED, stage.commit(NONE).join().outcome());
            assertArrayEquals(fixture.bytes(), store.read(fixture.key(), NONE).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }
}
