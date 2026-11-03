package com.vimi.gui;

import com.vibeminer.formats.audio.PcmClip;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.SourceDataLine;

/**
 * No audio device: controlled native-line operations prove real-start and bounded stop ownership.
 */
public final class VoicePlaybackTest {
    private VoicePlaybackTest() {}

    private static void closeFailure() throws Exception {
        var acquisitions = new AtomicInteger();
        SourceDataLine line =
                (SourceDataLine)
                        Proxy.newProxyInstance(
                                VoicePlaybackTest.class.getClassLoader(),
                                new Class<?>[] {SourceDataLine.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "close" ->
                                                    throw new IllegalStateException(
                                                            "Authored line close failure");
                                            case "write" -> args[2];
                                            default -> null;
                                        });
        var player =
                new VoicePlayback(
                        format -> {
                            acquisitions.incrementAndGet();
                            return line;
                        });
        var clip = new PcmClip(8000, 1, new byte[] {0, 0});
        String failed = player.play(clip).get(5, TimeUnit.SECONDS);
        if (failed == null || !failed.contains("unavailable"))
            throw new AssertionError("Close failure was hidden");
        String stopped = player.play(clip).get(1, TimeUnit.SECONDS);
        if (stopped == null || !stopped.contains("closed") || acquisitions.get() != 1)
            throw new AssertionError("Failed line close must prevent repeated device admission");
        player.close();
    }

    public static void main(String[] args) throws Exception {
        verify();
        System.out.println(
                "Voice playback: actual-start feedback, bounded admission, asynchronous owned close"
                    + " and close failure passed");
    }

    static void verify() throws Exception {
        closeFailure();
        var owner = Thread.currentThread();
        var opened = new CompletableFuture<Void>();
        var allowOpen = new CompletableFuture<Void>();
        var started = new CompletableFuture<Void>();
        var draining = new CompletableFuture<Void>();
        var closing = new CompletableFuture<Void>();
        var allowClose = new CompletableFuture<Void>();
        var acquisitions = new AtomicInteger();
        var closes = new AtomicInteger();
        SourceDataLine line =
                (SourceDataLine)
                        Proxy.newProxyInstance(
                                VoicePlaybackTest.class.getClassLoader(),
                                new Class<?>[] {SourceDataLine.class},
                                (proxy, method, values) ->
                                        switch (method.getName()) {
                                            case "open" -> {
                                                opened.complete(null);
                                                allowOpen.get(5, TimeUnit.SECONDS);
                                                yield null;
                                            }
                                            case "start" -> {
                                                started.complete(null);
                                                yield null;
                                            }
                                            case "write" -> values[2];
                                            case "drain" -> {
                                                draining.get(5, TimeUnit.SECONDS);
                                                yield null;
                                            }
                                            case "close" -> {
                                                if (Thread.currentThread() == owner)
                                                    throw new AssertionError(
                                                            "Audio close ran on caller");
                                                closes.incrementAndGet();
                                                closing.complete(null);
                                                draining.complete(null);
                                                allowClose.get(5, TimeUnit.SECONDS);
                                                yield null;
                                            }
                                            default -> null;
                                        });
        var player =
                new VoicePlayback(
                        format -> {
                            acquisitions.incrementAndGet();
                            return line;
                        });
        var callback = new CompletableFuture<Void>();
        var clip = new PcmClip(8000, 1, new byte[] {0, 0});
        try {
            var result =
                    player.play(
                            clip,
                            () -> {
                                if (!started.isDone())
                                    throw new AssertionError("Playing preceded actual line.start");
                                callback.complete(null);
                                throw new IllegalStateException(
                                        "Authored feedback failure cannot interrupt cleanup");
                            });
            opened.get(5, TimeUnit.SECONDS);
            if (callback.isDone())
                throw new AssertionError("Preparing audio was reported as Playing");
            allowOpen.complete(null);
            callback.get(5, TimeUnit.SECONDS);
            player.stop();
            closing.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                String message = player.play(clip).get(1, TimeUnit.SECONDS);
                if (message == null || !message.contains("still stopping"))
                    throw new AssertionError("Stalled audio admitted another worker");
            }
            if (acquisitions.get() != 1 || closes.get() != 1)
                throw new AssertionError("Audio workers/owned closes were not bounded");
            player.close();
            if (allowClose.isDone()) throw new AssertionError("Stop must not wait for line.close");
            allowClose.complete(null);
            result.get(5, TimeUnit.SECONDS);
        } finally {
            allowOpen.complete(null);
            draining.complete(null);
            allowClose.complete(null);
            player.close();
        }
    }
}
