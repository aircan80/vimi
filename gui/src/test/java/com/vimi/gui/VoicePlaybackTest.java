package com.vimi.gui;

import com.vibeminer.formats.audio.PcmClip;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;
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

    /**
     * Plays an authored stereo clip on a device reporting {@code device} channels and returns the
     * bytes written; {@code lineChannels} is the format the line must be opened with.
     */
    private static byte[] played(byte[] stereo, int device, int lineChannels) throws Exception {
        var written = new java.io.ByteArrayOutputStream();
        var formats = new java.util.concurrent.ConcurrentLinkedQueue<AudioFormat>();
        SourceDataLine line =
                (SourceDataLine)
                        Proxy.newProxyInstance(
                                VoicePlaybackTest.class.getClassLoader(),
                                new Class<?>[] {SourceDataLine.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "write" -> {
                                                synchronized (written) {
                                                    written.write(
                                                            (byte[]) args[0],
                                                            (Integer) args[1],
                                                            (Integer) args[2]);
                                                }
                                                yield args[2];
                                            }
                                            default -> null;
                                        });
        var player =
                new VoicePlayback(
                        format -> {
                            if (format.getChannels() > device)
                                throw new javax.sound.sampled.LineUnavailableException(
                                        "unsupported");
                            formats.add(format);
                            return line;
                        },
                        () -> device);
        String problem = player.play(new PcmClip(8000, 2, stereo)).get(5, TimeUnit.SECONDS);
        player.close();
        if (problem != null) throw new AssertionError("Stereo clip did not play: " + problem);
        if (formats.size() != 1 || formats.peek().getChannels() != lineChannels)
            throw new AssertionError("Line channels " + formats);
        synchronized (written) {
            return written.toByteArray();
        }
    }

    /** A mono-only device plays a stereo clip as (L + R) / 2; a stereo device is unchanged. */
    private static void monoOnlyDeviceDownmixesStereoClips() throws Exception {
        short[][] frames = {{1000, 3000}, {Short.MIN_VALUE, Short.MIN_VALUE},
            {Short.MAX_VALUE, Short.MIN_VALUE}};
        byte[] stereo = new byte[frames.length * 4];
        for (int i = 0; i < frames.length; i++)
            for (int c = 0; c < 2; c++) {
                stereo[4 * i + 2 * c] = (byte) (frames[i][c] >> 8);
                stereo[4 * i + 2 * c + 1] = (byte) frames[i][c];
            }
        byte[] mono = played(stereo, 1, 1);
        short[] expected = {2000, Short.MIN_VALUE, 0};
        if (mono.length != expected.length * 2)
            throw new AssertionError("Mono bytes " + mono.length);
        for (int i = 0; i < expected.length; i++)
            if ((short) (mono[2 * i] << 8 | mono[2 * i + 1] & 0xff) != expected[i])
                throw new AssertionError("Downmixed frame " + i);
        if (!java.util.Arrays.equals(stereo, played(stereo, 2, 2)))
            throw new AssertionError("A stereo device must receive the clip unchanged");
    }

    public static void main(String[] args) throws Exception {
        verify();
        System.out.println(
                "Voice playback: actual-start feedback, bounded admission, asynchronous owned close,"
                    + " mono downmix and close failure passed");
    }

    static void verify() throws Exception {
        closeFailure();
        monoOnlyDeviceDownmixesStereoClips();
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
