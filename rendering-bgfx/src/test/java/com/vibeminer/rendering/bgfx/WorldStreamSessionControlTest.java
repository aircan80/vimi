package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(5)
class WorldStreamSessionControlTest {
    @TempDir Path directory;

    @Test
    void matchingCloseArmsLifetimeBeforeNativeOwnerLoopAndWrongNonceHasNoEffect() throws Exception {
        var nonce = new WorldStreamState.Nonce(1, 2);
        var session = new WorldStreamEnvelope.Session(nonce, "ab".repeat(32), 1);
        Constructor<WorldStreamSession> constructor =
                WorldStreamSession.class.getDeclaredConstructor(
                        WorldStreamEnvelope.Session.class,
                        Path.class,
                        java.io.OutputStream.class,
                        long.class);
        constructor.setAccessible(true);
        var runtime =
                constructor.newInstance(
                        session,
                        directory,
                        new ByteArrayOutputStream(),
                        ProcessHandle.current().pid());
        var closingField = WorldStreamSession.class.getDeclaredField("closing");
        closingField.setAccessible(true);
        var closing = (AtomicBoolean) closingField.get(runtime);
        byte[] wrong =
                WorldStreamControl.encode(
                        new WorldStreamControl.Close(new WorldStreamState.Nonce(3, 4)));
        byte[] matching = WorldStreamControl.encode(new WorldStreamControl.Close(nonce));
        InputStream control =
                new InputStream() {
                    int position;

                    @Override
                    public int read() {
                        if (position == wrong.length) {
                            assertFalse(closing.get(), "Wrong nonce must not arm shutdown");
                        }
                        if (position >= wrong.length + matching.length) {
                            throw new AssertionError(
                                    "Reader waited for another byte after accepting CLOSE");
                        }
                        int index = position++;
                        return (index < wrong.length
                                        ? wrong[index]
                                        : matching[index - wrong.length])
                                & 255;
                    }
                };
        try {
            // No owner loop/native initialization is running: the reader itself must arm it.
            var read =
                    WorldStreamSession.class.getDeclaredMethod("readControls", InputStream.class);
            read.setAccessible(true);
            read.invoke(runtime, control);
            assertTrue(closing.get());
            var field = WorldStreamSession.class.getDeclaredField("closeStarted");
            field.setAccessible(true);
            assertTrue(System.nanoTime() - field.getLong(runtime) < TimeUnit.SECONDS.toNanos(2));
        } finally {
            var statusField = WorldStreamSession.class.getDeclaredField("status");
            statusField.setAccessible(true);
            ((WorldStreamStatusWriter) statusField.get(runtime)).finish().get(2, TimeUnit.SECONDS);
            var decoderField = WorldStreamSession.class.getDeclaredField("decoder");
            decoderField.setAccessible(true);
            ((ExecutorService) decoderField.get(runtime)).shutdown();
        }
    }
}
