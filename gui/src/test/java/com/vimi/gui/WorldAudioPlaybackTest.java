package com.vimi.gui;

import com.vibeminer.concepts.world.WorldAreaAudio;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.SourceDataLine;

/**
 * No audio device or game file: authored constant PCM and controlled lines prove fades, kit
 * continuity, ambience looping, recorded silence, mute and owned close.
 */
public final class WorldAudioPlaybackTest {
    private WorldAudioPlaybackTest() {}

    private static final byte[] ENCODED = new byte[64];

    /** Authored mono 8 kHz stream of a constant sample; frames bounds its length. */
    private static final class FakePcm implements WorldAudioPlayback.Pcm {
        final long frames;
        long sent;
        volatile boolean closed;

        FakePcm(long frames) { this.frames = frames; }

        @Override public int sampleRate() { return 8000; }
        @Override public int channels() { return 1; }

        @Override
        public int read(byte[] buffer, int offset, int length, BooleanSupplier canceled)
                throws IOException {
            if (closed) throw new java.util.concurrent.CancellationException();
            if (sent >= frames) return -1;
            int count = (int) Math.min(length / 2, frames - sent);
            for (int i = 0; i < count; i++) {
                buffer[offset + 2 * i] = 0x40;
                buffer[offset + 2 * i + 1] = 0;
            }
            sent += count;
            return count * 2;
        }

        @Override public void close() { closed = true; }
    }

    private static final class Device {
        final AtomicInteger opened = new AtomicInteger(), closedLines = new AtomicInteger();
        final AtomicLong frames = new AtomicLong();
        final ConcurrentLinkedQueue<Integer> peaks = new ConcurrentLinkedQueue<>();
        volatile int last;

        SourceDataLine line() {
            return (SourceDataLine) Proxy.newProxyInstance(
                    WorldAudioPlaybackTest.class.getClassLoader(),
                    new Class<?>[] {SourceDataLine.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "write" -> {
                            byte[] bytes = (byte[]) args[0];
                            int offset = (Integer) args[1], length = (Integer) args[2];
                            int peak = 0;
                            for (int i = offset; i + 1 < offset + length; i += 2)
                                peak = Math.max(peak, Math.abs((short) (bytes[i] << 8
                                        | bytes[i + 1] & 0xff)));
                            peaks.add(peak);
                            last = peak;
                            frames.addAndGet(length / 2);
                            Thread.sleep(1);
                            yield length;
                        }
                        case "close" -> {
                            closedLines.incrementAndGet();
                            yield null;
                        }
                        case "isOpen", "isActive", "isRunning" -> false;
                        default -> null;
                    });
        }
    }

    private static WorldAreaAudio.Track track(WorldAreaAudio.Kind kind, long kit, int silence) {
        return new WorldAreaAudio.Track(kind, 10, "Authored zone", 5, kit, List.of(kit * 10),
                silence, silence);
    }

    private static final WorldAudioPlayback.Source FILES = (id, token) ->
            CompletableFuture.completedFuture(new WorldAreaAudio.AudioFile(
                    id, WorldAreaAudio.FileState.READ, ENCODED, null));

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError(message);
            Thread.sleep(5);
        }
    }

    private static void fadesKeepKitsAndCloseOwnedResources() throws Exception {
        var device = new Device();
        var streams = new ConcurrentLinkedQueue<FakePcm>();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> {
            device.opened.incrementAndGet();
            return device.line();
        }, (bytes, canceled) -> {
            var pcm = new FakePcm(Long.MAX_VALUE);
            streams.add(pcm);
            return pcm;
        }, statuses::add);
        var music = playback.music;
        music.volume(1);
        music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        if (device.opened.get() != 0) throw new AssertionError("Disabled music opened a line");
        music.enabled(true);
        await(() -> device.frames.get() > 8000 * 2, "music did not stream");
        int first = device.peaks.peek();
        if (first > 2000 || device.last < 16000)
            throw new AssertionError("Music must fade in from silence: " + first + " " + device.last);
        if (statuses.stream().noneMatch(s -> s.playing() && s.message().contains("sound kit #100")))
            throw new AssertionError("Playing status must name the recorded sound kit");

        // An inherited parent-zone kit keeps playing across a subzone change.
        music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        Thread.sleep(100);
        if (streams.size() != 1) throw new AssertionError("Same kit restarted");

        // A different kit fades the old track out before the next one starts.
        music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> streams.size() == 2, "new kit did not start");
        var before = streams.peek();
        if (!before.closed) throw new AssertionError("Replaced decoder was not closed");
        if (device.closedLines.get() < 1) throw new AssertionError("Replaced line was not closed");
        long faded = before.sent;
        if (faded < 8000 * WorldAudioPlayback.FADE_SECONDS * 0.9)
            throw new AssertionError("Replacement must fade out over the recorded ramp: " + faded);

        music.enabled(false);
        await(() -> streams.stream().allMatch(s -> s.closed), "muted decoder was not closed");
        int lines = device.opened.get();
        Thread.sleep(50);
        if (device.opened.get() != lines) throw new AssertionError("Muted channel reopened");

        music.enabled(true);
        await(() -> streams.size() == 3, "re-enabled music did not restart");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (!streams.stream().allMatch(s -> s.closed))
            throw new AssertionError("Close must destroy every decoder");
        music.enabled(true);
        music.track(track(WorldAreaAudio.Kind.MUSIC, 300, 0), FILES);
        Thread.sleep(50);
        if (streams.size() != 3) throw new AssertionError("Closed playback started a decoder");
    }

    private static void ambienceLoopsFromMemoryAndMusicWaitsRecordedSilence() throws Exception {
        var device = new Device();
        var opens = new AtomicInteger();
        var openTimes = new ConcurrentLinkedQueue<Long>();
        var playback = new WorldAudioPlayback(format -> {
            device.opened.incrementAndGet();
            return device.line();
        }, (bytes, canceled) -> {
            opens.incrementAndGet();
            openTimes.add(System.nanoTime());
            return new FakePcm(400);
        }, status -> {});
        playback.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 100, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> device.frames.get() > 400 * 5, "ambience did not loop");
        if (opens.get() != 1 || device.opened.get() != 1)
            throw new AssertionError("Ambience loop must replay memory on one line: " + opens.get() + " " + device.opened.get());
        playback.ambience.enabled(false);
        await(() -> device.closedLines.get() == 1, "ambience line was not closed");

        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 300), FILES);
        playback.music.enabled(true);
        await(() -> opens.get() >= 3, "music did not repeat after silence");
        var times = openTimes.toArray(Long[]::new);
        long gap = TimeUnit.NANOSECONDS.toMillis(times[2] - times[1]);
        if (gap < 300) throw new AssertionError("Recorded silence was not observed: " + gap);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static void missingSourcesAreVisibleAndOpenNoLine() throws Exception {
        var device = new Device();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> {
            device.opened.incrementAndGet();
            return device.line();
        }, (bytes, canceled) -> {
            throw new IOException("World audio requires Ogg Vorbis or MPEG-1 audio");
        }, statuses::add);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().contains("cannot be decoded")),
                "decode failure was hidden");
        WorldAudioPlayback.Source missing = (id, token) -> CompletableFuture.completedFuture(
                new WorldAreaAudio.AudioFile(id, WorldAreaAudio.FileState.UNAVAILABLE, null,
                        "Audio file #" + id + " is NOT_FOUND."));
        playback.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 7, 0), missing);
        playback.ambience.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().contains("NOT_FOUND")),
                "missing file was hidden");
        if (device.opened.get() != 0) throw new AssertionError("Failed sources opened a line");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static void gainRampsPerFrame() {
        byte[] block = new byte[8];
        for (int i = 0; i < 8; i += 2) block[i] = 0x40;
        double gain = WorldAudioPlayback.apply(block, 8, 1, 0, 1, 0.5);
        if (gain != 1) throw new AssertionError("Ramp did not reach target");
        int first = (short) (block[0] << 8 | block[1] & 0xff);
        int last = (short) (block[6] << 8 | block[7] & 0xff);
        if (first != 0x4000 / 4 || last != 0x4000)
            throw new AssertionError("Perceptual ramp " + first + " " + last);
    }

    public static void main(String[] args) throws Exception {
        gainRampsPerFrame();
        fadesKeepKitsAndCloseOwnedResources();
        ambienceLoopsFromMemoryAndMusicWaitsRecordedSilence();
        missingSourcesAreVisibleAndOpenNoLine();
        System.out.println("World audio playback: fades, kit continuity, ambience loops,"
                + " recorded silence, mute, missing sources and owned close passed");
    }
}
