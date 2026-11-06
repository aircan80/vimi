package com.vimi.gui;

import com.vibeminer.concepts.world.WorldAreaAudio;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/**
 * Measures an ambience kit change through the real decoder: the authored tone plays as kit A,
 * then the desired kit becomes B. Each file is the real decoder's output repeated to 10 s. Lines are paced like a device with a 250 ms buffer, so each
 * write has a place on the device timeline, and closing a line discards what it still queues. Prints the longest audible gap after the change and
 * the time until kit B plays at full volume; asserts nothing.
 */
public final class WorldAudioZoneChangeProbe {
    private WorldAudioZoneChangeProbe() {}

    /**
     * One write on the device timeline. {@code closedAt[0]} is when its line closed: frames
     * still queued then were never played, as a real line discards its buffer on close.
     */
    private record Write(int line, long start, long nanos, int peak, long[] closedAt) {
        long end() {
            return Math.min(start + nanos, closedAt[0]);
        }

        boolean played() {
            return start < closedAt[0];
        }
    }

    private static final List<Write> WRITES = new ArrayList<>();
    private static int lines;
    /** Length of each repeated file. */
    private static int seconds = 10;

    /** A line that plays written frames in real time behind a 250 ms buffer. */
    private static SourceDataLine paced(AudioFormat format) {
        int line;
        synchronized (WRITES) {
            line = lines++;
        }
        double frameNanos = 1e9 / format.getSampleRate();
        int frame = format.getChannels() * 2;
        long[] queuedUntil = {0}, closedAt = {Long.MAX_VALUE};
        return (SourceDataLine) Proxy.newProxyInstance(
                WorldAudioZoneChangeProbe.class.getClassLoader(),
                new Class<?>[] {SourceDataLine.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "write" -> {
                            byte[] bytes = (byte[]) args[0];
                            int offset = (Integer) args[1], length = (Integer) args[2];
                            long now = System.nanoTime();
                            long buffer = TimeUnit.MILLISECONDS.toNanos(250);
                            if (queuedUntil[0] - now > buffer)
                                Thread.sleep((queuedUntil[0] - now - buffer) / 1_000_000);
                            now = System.nanoTime();
                            long start = Math.max(now, queuedUntil[0]);
                            long nanos = (long) (length / frame * frameNanos);
                            queuedUntil[0] = start + nanos;
                            int peak = 0;
                            for (int i = offset; i + 1 < offset + length; i += 2)
                                peak = Math.max(peak, Math.abs((short) (bytes[i] << 8
                                        | bytes[i + 1] & 0xff)));
                            synchronized (WRITES) {
                                WRITES.add(new Write(line, start, nanos, peak, closedAt));
                            }
                            return length;
                        }
                        case "drain" -> {
                            long wait = queuedUntil[0] - System.nanoTime();
                            if (wait > 0) Thread.sleep(wait / 1_000_000);
                            return null;
                        }
                        case "close" -> {
                            closedAt[0] = Math.min(closedAt[0], System.nanoTime());
                            return null;
                        }
                        case "isOpen", "isActive", "isRunning" -> {
                            return false;
                        }
                        default -> {
                            return null;
                        }
                    }
                });
    }

    /**
     * Real decoder start (child process and all), then the 0.2 s tone repeated to 10 s, so a
     * file outlasts the fades as real game ambience does.
     */
    private static WorldAudioPlayback.Pcm repeated(byte[] encoded,
            java.util.function.BooleanSupplier canceled) throws java.io.IOException {
        var all = new java.io.ByteArrayOutputStream();
        int rate, channels;
        try (var real = WorldAudioPlayback.decode(encoded, canceled)) {
            rate = real.sampleRate();
            channels = real.channels();
            byte[] block = new byte[4096];
            int count;
            while ((count = real.read(block, 0, block.length, canceled)) >= 0)
                all.write(block, 0, count);
        }
        byte[] once = all.toByteArray();
        long total = (long) rate * channels * 2 * seconds;
        return new WorldAudioPlayback.Pcm() {
            long position;

            @Override public int sampleRate() { return rate; }
            @Override public int channels() { return channels; }

            @Override
            public int read(byte[] buffer, int offset, int length,
                    java.util.function.BooleanSupplier c) {
                if (position >= total) return -1;
                int count = (int) Math.min(Math.min(length, total - position),
                        once.length - position % once.length);
                count -= count % (channels * 2);
                System.arraycopy(once, (int) (position % once.length), buffer, offset, count);
                position += count;
                return count;
            }

            @Override public void close() {}
        };
    }

    public static void main(String[] args) throws Exception {
        byte[] tone = Files.readAllBytes(Path.of(System.getProperty("vimi.audioFixture")));
        WorldAudioPlayback.Source source = (id, token) -> CompletableFuture.completedFuture(
                new WorldAreaAudio.AudioFile(id, WorldAreaAudio.FileState.READ, tone, null));
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String mode = args.length > 1 ? args[1] : "change";
        if (mode.equals("loop")) {
            loop(source, runs);
            return;
        }
        if (mode.equals("music")) {
            music(source, runs);
            return;
        }
        var gaps = new ArrayList<Double>();
        var fulls = new ArrayList<Double>();
        for (int run = 0; run < runs; run++) {
            synchronized (WRITES) {
                WRITES.clear();
                lines = 0;
            }
            var playback = new WorldAudioPlayback(() -> 2, WorldAudioZoneChangeProbe::paced,
                    WorldAudioZoneChangeProbe::repeated, status -> {});
            var channel = playback.ambience;
            channel.volume(1);
            channel.track(new WorldAreaAudio.Track(WorldAreaAudio.Kind.AMBIENCE, 10, "Zone A", 5,
                    100, List.of(1000L), 0, 0), source);
            channel.enabled(true);
            Thread.sleep(3000); // well past the fade-in
            int full;
            synchronized (WRITES) {
                full = WRITES.stream().mapToInt(Write::peak).max().orElse(0);
            }
            long change = System.nanoTime();
            channel.track(new WorldAreaAudio.Track(WorldAreaAudio.Kind.AMBIENCE, 11, "Zone B", 6,
                    200, List.of(2000L), 0, 0), source);
            Thread.sleep(6000);
            playback.closeAsync().get(10, TimeUnit.SECONDS);
            List<Write> after;
            synchronized (WRITES) {
                after = WRITES.stream().filter(w -> w.played() && w.end() > change)
                        .sorted(java.util.Comparator.comparingLong(Write::start)).toList();
            }
            // The new kit's line is the last one opened.
            int newLine = after.stream().mapToInt(Write::line).max().orElse(-1);
            long audibleUntil = change, gap = 0;
            long newFull = -1;
            for (var w : after) {
                if (w.peak() < full / 20) continue;
                gap = Math.max(gap, w.start() - audibleUntil);
                audibleUntil = Math.max(audibleUntil, w.end());
                if (newFull < 0 && w.line() == newLine && w.peak() >= full * 95 / 100)
                    newFull = w.start();
            }
            gaps.add(gap / 1e6);
            fulls.add(newFull < 0 ? Double.NaN : (newFull - change) / 1e6);
        }
        System.out.printf("World audio zone change over %d runs: longest audible gap %s ms;"
                + " change to new kit at full volume %s ms%n", runs, gaps, fulls);
    }

    private static void reset() {
        synchronized (WRITES) {
            WRITES.clear();
            lines = 0;
        }
    }

    private static List<Write> writes() {
        synchronized (WRITES) {
            return WRITES.stream().sorted(java.util.Comparator.comparingLong(Write::start))
                    .toList();
        }
    }

    /**
     * A two-file ambience kit of 1 s files loops for 8 s after its fade-in: prints the longest
     * audible gap between files.
     */
    private static void loop(WorldAudioPlayback.Source source, int runs) throws Exception {
        seconds = 1;
        var gaps = new ArrayList<Double>();
        for (int run = 0; run < runs; run++) {
            reset();
            var playback = new WorldAudioPlayback(() -> 2, WorldAudioZoneChangeProbe::paced,
                    WorldAudioZoneChangeProbe::repeated, status -> {});
            playback.ambience.volume(1);
            playback.ambience.track(new WorldAreaAudio.Track(WorldAreaAudio.Kind.AMBIENCE, 10,
                    "Zone A", 5, 100, List.of(1000L, 1001L), 0, 0), source);
            playback.ambience.enabled(true);
            Thread.sleep(2000);
            long from = System.nanoTime();
            Thread.sleep(8000);
            long to = System.nanoTime();
            playback.closeAsync().get(10, TimeUnit.SECONDS);
            var all = writes();
            int full = all.stream().mapToInt(Write::peak).max().orElse(0);
            long audibleUntil = from, gap = 0;
            for (var w : all) {
                if (w.start() + w.nanos() < from || w.start() > to || w.peak() < full / 20)
                    continue;
                gap = Math.max(gap, w.start() - audibleUntil);
                audibleUntil = Math.max(audibleUntil, w.start() + w.nanos());
            }
            gaps.add(gap / 1e6);
        }
        System.out.printf("World audio multi-file ambience loop over %d runs: longest audible gap"
                + " %s ms%n", runs, gaps);
    }

    /**
     * Music of 1 s files with a recorded 1000 ms silence: prints how much longer than the
     * recorded silence each pause between tracks lasts on the device timeline.
     */
    private static void music(WorldAudioPlayback.Source source, int runs) throws Exception {
        seconds = 1;
        reset();
        var playback = new WorldAudioPlayback(() -> 2, WorldAudioZoneChangeProbe::paced,
                WorldAudioZoneChangeProbe::repeated, status -> {});
        playback.music.volume(1);
        playback.music.track(new WorldAreaAudio.Track(WorldAreaAudio.Kind.MUSIC, 10, "Zone A", 5,
                100, List.of(1000L), 1000, 1000), source);
        playback.music.enabled(true);
        Thread.sleep((runs + 1) * 2300L);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var all = writes();
        var extra = new ArrayList<Double>();
        for (int line = 1; line < lines; line++) {
            int previous = line - 1, next = line;
            long end = all.stream().filter(w -> w.line() == previous)
                    .mapToLong(w -> w.start() + w.nanos()).max().orElse(-1);
            long start = all.stream().filter(w -> w.line() == next).mapToLong(Write::start)
                    .min().orElse(-1);
            if (end > 0 && start > 0) extra.add((start - end) / 1e6 - 1000);
        }
        System.out.printf("World audio music: pause beyond the recorded 1000 ms silence %s ms%n",
                extra);
    }
}
